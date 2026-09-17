//go:build windows

package interp

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"

	"linplayer/core/paths"
)

// N 卡加速(TensorRT)直接用上游 hooke007/mpv_PlayKit 的 vsNV 完整包【用户定 2026-09-18:
// 「别人项目有完整的包,直接解析完整的包」】。自己重打的小包发不上 Release(大文件上传一直 HTTP 500)。
// 下两个分卷(共 2.9GB,多线程),按区段校验 sha256,用系统自带的 tar.exe(libarchive 读 7z)
// 只解需要的几个文件(实测 95 秒,字节和 7-Zip 解出来的一致),解完删掉包。
const vsnvTag = "20260510"

type vsnvVolume struct {
	name, sha string
	size      int64
}

var vsnvVolumes = []vsnvVolume{
	{"mpv-lazy-20260510-vsNV.7z.001", "f877973fae54d7cc05451d0369340f5a4ef6c82a2f633c66b786a337d3e6ddda", 1610612736},
	{"mpv-lazy-20260510-vsNV.7z.002", "1c7303d76f3767f16122c5ea16894add08b6c044ab15d2ccefba6155b8782007", 1295256359},
}

// trtCommonFiles 包内路径(mpv-lazy/ 下)。实测少了 nvinfer_plugin 引擎建不出来;
// cuBLAS / cuDNN / cuFFT 用不着:k7sfunc 调 TRT 时 use_cublas / use_cudnn 都是 False。
var trtCommonFiles = []string{
	"vs-plugins/vstrt.dll",
	"vs-plugins/vsmlrt-cuda/cudart64_13.dll",
	"vs-plugins/vsmlrt-cuda/nvinfer_10.dll",
	"vs-plugins/vsmlrt-cuda/nvinfer_plugin_10.dll",
	"vs-plugins/vsmlrt-cuda/nvonnxparser_10.dll",
	"vs-plugins/vsmlrt-cuda/trtexec.exe",
	"vs-plugins/models/drba/distilDRBA_v2_lite_scale_ap.onnx", // DRBA 走 TRT 用 fp32 模型
}

// trtArchs compute capability → 引擎构建资源(一代一个,110~450MB,只解自己那一代)。
// TensorRT 10 不支持 Pascal(GTX 10 系,6.1),那一代走 DirectML。
var trtArchs = map[string]string{
	"7.5": "sm75", "8.0": "sm80", "8.6": "sm86", "8.7": "sm86", "8.9": "sm89", "10.0": "sm100", "12.0": "sm120",
}

// trtDownloadSize 要下的字节数(两个分卷)。
func trtDownloadSize() int64 {
	var n int64
	for _, v := range vsnvVolumes {
		n += v.size
	}
	return n
}

// minTRTDriver 包里是 CUDA 13 运行库,Windows 驱动要 580 起。
const minTRTDriver = 580

// Nvidia N 卡信息。Name 为空 = 没有 N 卡(或没装驱动)。
type Nvidia struct {
	Name, ComputeCap string
	Driver           float64
}

var (
	nvOnce sync.Once
	nvInfo Nvidia
)

// NvidiaGPU 查 N 卡。走驱动自带的 nvidia-smi:不用为这一问再带一份 NVML 绑定。
// 结果缓存 —— 起进程要几十毫秒,显卡也不会中途换。
func NvidiaGPU() Nvidia {
	nvOnce.Do(func() {
		cmd := exec.Command("nvidia-smi", "--query-gpu=name,compute_cap,driver_version", "--format=csv,noheader")
		// 窗口程序起控制台子进程会闪一个黑框
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
		out, err := cmd.Output()
		if err != nil {
			return
		}
		nvInfo = parseNvidiaSMI(string(out))
	})
	return nvInfo
}

func parseNvidiaSMI(out string) Nvidia {
	line := strings.TrimSpace(strings.SplitN(strings.TrimSpace(out), "\n", 2)[0])
	f := strings.Split(line, ",")
	if len(f) < 3 {
		return Nvidia{}
	}
	d, _ := strconv.ParseFloat(strings.TrimSpace(f[2]), 64)
	return Nvidia{Name: strings.TrimSpace(f[0]), ComputeCap: strings.TrimSpace(f[1]), Driver: d}
}

// TRTOffer 这台机器能不能装 N 卡加速:能的话给出显卡架构和要下的字节数;不能给原因。
// 没有 N 卡时 arch 和 why 都为空 —— A 卡 / I 卡界面上压根不提这件事。
func TRTOffer(nv Nvidia) (arch string, size int64, why string) {
	if nv.Name == "" {
		return "", 0, ""
	}
	arch, ok := trtArchs[nv.ComputeCap]
	if !ok {
		return "", 0, fmt.Sprintf("%s(架构 %s)不支持 N 卡加速,继续用通用版", nv.Name, nv.ComputeCap)
	}
	if nv.Driver > 0 && nv.Driver < minTRTDriver {
		return "", 0, fmt.Sprintf("显卡驱动 %.2f 太旧,N 卡加速要 %d 以上", nv.Driver, minTRTDriver)
	}
	return arch, trtDownloadSize(), ""
}

func trtMark() string     { return filepath.Join(Dir(), ".trt-ready") }
func enginesMark() string { return filepath.Join(Dir(), ".trt-engines") }

// TRTReady N 卡加速包装好**且引擎建好了**。只有这时才走 TensorRT:
// 引擎没建好就挂上去,播放时现建要卡 40~55 秒,还会被丢帧闸当成「没生效」撤掉。
func TRTReady() bool {
	_, e1 := os.Stat(trtMark())
	_, e2 := os.Stat(enginesMark())
	return e1 == nil && e2 == nil
}

// TRTInstalled 包装好了(引擎可能还没建)。
func TRTInstalled() bool {
	_, err := os.Stat(trtMark())
	return err == nil
}

// InstallTRT 下载上游完整包、解出 N 卡加速需要的文件叠进运行时目录(没装通用运行时先装)。
// onStage 报阶段:"extract" 表示下完了在解压(tar 没有进度可报,一两分钟)。
func InstallTRT(ctx context.Context, onProgress Progress, onStage func(stage string)) error {
	arch, size, why := TRTOffer(NvidiaGPU())
	if arch == "" {
		if why == "" {
			why = "没有检测到 N 卡"
		}
		return fmt.Errorf("%s", why)
	}
	var base int64
	if !Installed() {
		base = RuntimeSize
		if err := Install(ctx, func(d, _ int64) {
			if onProgress != nil {
				onProgress(d, base+size)
			}
		}); err != nil {
			return err
		}
	}
	pkg := filepath.Join(paths.InterpDir(), "vsNV.7z")
	// 两个分卷首尾相接就是完整的 7z:直接下到同一个文件的对应偏移,省一次 2.9GB 的拼接拷贝
	var off int64
	for _, v := range vsnvVolumes {
		url := "https://github.com/hooke007/mpv_PlayKit/releases/download/" + vsnvTag + "/" + v.name
		start := base + off
		// 上次下完了但解压被打断(程序退出)的话,包还在:校验对得上就不再下 2.9GB
		if st, err := os.Stat(pkg); err == nil && st.Size() >= off+v.size {
			if sum, err := sha256Range(pkg, off, v.size); err == nil && strings.EqualFold(sum, v.sha) {
				off += v.size
				continue
			}
		}
		err := downloadRanged(ctx, url, pkg, off, v.size, func(d int64) {
			if onProgress != nil {
				onProgress(start+d, base+size)
			}
		})
		if err != nil {
			return err
		}
		sum, err := sha256Range(pkg, off, v.size)
		if err != nil {
			return err
		}
		if !strings.EqualFold(sum, v.sha) {
			return fmt.Errorf("N 卡加速包校验失败(%s sha256 %s),可能下载被篡改或中断,请重试", v.name, sum)
		}
		off += v.size
	}
	if onStage != nil {
		onStage("extract")
	}
	members := append([]string{}, trtCommonFiles...)
	members = append(members, "vs-plugins/vsmlrt-cuda/nvinfer_builder_resource_"+arch+"_10.dll")
	if err := extract7z(ctx, pkg, members, Dir()); err != nil {
		return err // 包留着:下次校验对得上就直接解,不用重下
	}
	_ = os.Remove(pkg)
	_ = os.Remove(enginesMark()) // 换了包,旧引擎不算数
	return os.WriteFile(trtMark(), []byte(NvidiaGPU().ComputeCap), 0o644)
}

// extract7z 用系统自带的 tar.exe(Windows 10 1803 起,libarchive 能读 7z)从包里解出 members,
// 包内都在 mpv-lazy/ 下,解完挪到 dir 的同名相对路径。
func extract7z(ctx context.Context, pkg string, members []string, dir string) error {
	tmp := filepath.Join(paths.InterpDir(), "vsNV-extract")
	_ = os.RemoveAll(tmp)
	defer os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return fmt.Errorf("建解压目录失败: %w", err)
	}
	args := []string{"-xf", pkg, "-C", tmp}
	for _, m := range members {
		args = append(args, "mpv-lazy/"+m)
	}
	tar := filepath.Join(os.Getenv("SystemRoot"), "System32", "tar.exe")
	cmd := exec.CommandContext(ctx, tar, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("解压 N 卡加速包失败(系统 tar):%v %s", err, strings.TrimSpace(string(out)))
	}
	for _, m := range members {
		src := filepath.Join(tmp, "mpv-lazy", filepath.FromSlash(m))
		dst := filepath.Join(dir, filepath.FromSlash(m))
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return err
		}
		_ = os.Remove(dst)
		if err := os.Rename(src, dst); err != nil {
			return fmt.Errorf("包里缺 %s(上游换了布局?):%w", m, err)
		}
	}
	return nil
}

// EngineBuilt 某个算法的引擎文件在不在(预建每一步之后核对)。
func EngineBuilt(algo string) bool {
	sub := map[string]string{"drba": "drba", "rife": "rife_v2"}[algo]
	m, _ := filepath.Glob(filepath.Join(Dir(), "vs-plugins", "models", sub, "*.engine"))
	return sub != "" && len(m) > 0
}

// EnginesBuilt 两个算法的引擎都在才算数。
func EnginesBuilt() bool { return EngineBuilt("drba") && EngineBuilt("rife") }

// MarkEnginesReady 预建成功后调。
func MarkEnginesReady() error { return os.WriteFile(enginesMark(), []byte("1"), 0o644) }
