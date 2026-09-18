//go:build windows

package interp

import (
	"context"
	"fmt"
	"io/fs"
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
// 只解 TRT 相关的几个目录(实测 100 秒),解完删掉包。
const vsnvTag = "20260510"

type vsnvVolume struct {
	name, sha string
	size      int64
}

var vsnvVolumes = []vsnvVolume{
	{"mpv-lazy-20260510-vsNV.7z.001", "f877973fae54d7cc05451d0369340f5a4ef6c82a2f633c66b786a337d3e6ddda", 1610612736},
	{"mpv-lazy-20260510-vsNV.7z.002", "1c7303d76f3767f16122c5ea16894add08b6c044ab15d2ccefba6155b8782007", 1295256359},
}

// trtMembers 包内路径(mpv-lazy/ 下):TRT 相关的目录整个解,不再按文件挑【用户定 2026-09-18】。
// 挑文件那版漏过 nvinfer_plugin(引擎建不出来);整目录解,上游加了依赖也跟得上。
// 唯一剔掉的是别代显卡的构建资源(每份 110~450MB,合计 1.8GB,本机永远用不上)。
var trtMembers = []string{
	"vs-plugins/vstrt.dll",
	"vs-plugins/vsmlrt-cuda",
	"vs-plugins/models/rife_v2",
	"vs-plugins/models/drba",
}

// trtPack 换了解包内容就加一:标记文件名带它,老版本装的包自动算「没装」,重装一遍。
// 2 = 整目录解 + RIFE 改用 v4.26(v4.6 在 TensorRT 下出满屏黑线,见 interp.vpy)。
const trtPack = "2"

// trtArchs compute capability → 引擎构建资源(一代一个,110~450MB,只解自己那一代)。
// TensorRT 10 不支持 Pascal(GTX 10 系,6.1),那一代走 DirectML。
var trtArchs = map[string]string{
	"7.5": "sm75", "8.0": "sm80", "8.6": "sm86", "8.7": "sm86", "8.9": "sm89", "9.0": "sm90", "10.0": "sm100", "12.0": "sm120",
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

func trtMark() string     { return filepath.Join(Dir(), ".trt-ready-"+trtPack) }
func enginesMark() string { return filepath.Join(Dir(), ".trt-engines-"+trtPack) }

// TRTReady N 卡加速包装好**且引擎建好了**。只有这时才走 TensorRT:
// 引擎没建好就挂上去,播放时现建要卡 40~55 秒,还会被丢帧闸当成「没生效」撤掉。
func TRTReady() bool {
	_, e1 := os.Stat(trtMark())
	_, e2 := os.Stat(enginesMark())
	// 引擎也要真在:只建过 1080 那档的老安装少一档,720p 的片会在播放时现建
	return e1 == nil && e2 == nil && EnginesBuilt()
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
	if err := extract7z(ctx, pkg, trtMembers, trtExcludes(arch), Dir()); err != nil {
		return err // 包留着:下次校验对得上就直接解,不用重下
	}
	_ = os.Remove(pkg)
	// 换了包,旧引擎和旧标记都不算数。旧引擎留着的话「引擎建好没」的核对会被它骗过去
	for _, sub := range []string{"rife_v2", "drba"} {
		old, _ := filepath.Glob(filepath.Join(Dir(), "vs-plugins", "models", sub, "*.engine*"))
		for _, f := range old {
			_ = os.Remove(f)
		}
	}
	_ = os.Remove(filepath.Join(Dir(), ".trt-ready"))
	_ = os.Remove(filepath.Join(Dir(), ".trt-engines"))
	_ = os.Remove(enginesMark())
	return os.WriteFile(trtMark(), []byte(NvidiaGPU().ComputeCap), 0o644)
}

// trtExcludes 别代显卡的构建资源(ptx 是没有对应资源时的兜底,trtArchs 覆盖到的显卡用不上)。
func trtExcludes(arch string) []string {
	var ex []string
	seen := map[string]bool{arch: true}
	ex = append(ex, "vs-plugins/vsmlrt-cuda/nvinfer_builder_resource_ptx_10.dll")
	for _, a := range trtArchs {
		if !seen[a] {
			seen[a] = true
			ex = append(ex, "vs-plugins/vsmlrt-cuda/nvinfer_builder_resource_"+a+"_10.dll")
		}
	}
	return ex
}

// extract7z 用系统自带的 tar.exe(Windows 10 1803 起,libarchive 能读 7z)从包里解出 members
// (文件或目录),包内都在 mpv-lazy/ 下,解完逐个文件挪到 dir 的同名相对路径(和已有文件合并)。
// ☠ 剔除只能用 --exclude 点名:bsdtar 里 exclude 压过 include,「先全排除再放行本机那份」会一份都不解。
func extract7z(ctx context.Context, pkg string, members, excludes []string, dir string) error {
	tmp := filepath.Join(paths.InterpDir(), "vsNV-extract")
	_ = os.RemoveAll(tmp)
	defer os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return fmt.Errorf("建解压目录失败: %w", err)
	}
	args := []string{"-xf", pkg, "-C", tmp}
	for _, e := range excludes {
		args = append(args, "--exclude", "mpv-lazy/"+e)
	}
	for _, m := range members {
		args = append(args, "mpv-lazy/"+m)
	}
	tar := filepath.Join(os.Getenv("SystemRoot"), "System32", "tar.exe")
	cmd := exec.CommandContext(ctx, tar, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("解压 N 卡加速包失败(系统 tar):%v %s", err, strings.TrimSpace(string(out)))
	}
	root := filepath.Join(tmp, "mpv-lazy")
	for _, m := range members {
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(m))); err != nil {
			return fmt.Errorf("包里缺 %s(上游换了布局?)", m)
		}
	}
	return filepath.WalkDir(root, func(src string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(root, src)
		dst := filepath.Join(dir, rel)
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return err
		}
		_ = os.Remove(dst)
		return os.Rename(src, dst)
	})
}

// TRTHeights 预建引擎的两档(16:9)。interp.vpy 按补帧前的高度挑:≤720 用 720 那档,其余 1080。
var TRTHeights = []int{1080, 720}

// EngineCount 某个算法建好了几个引擎(预建每一步之后核对)。
func EngineCount(algo string) int {
	sub := map[string]string{"drba": "drba", "rife": "rife_v2"}[algo]
	if sub == "" {
		return 0
	}
	m, _ := filepath.Glob(filepath.Join(Dir(), "vs-plugins", "models", sub, "*.engine"))
	return len(m)
}

// EnginesBuilt 两个算法每一档都建好才算数。
func EnginesBuilt() bool {
	return EngineCount("drba") >= len(TRTHeights) && EngineCount("rife") >= len(TRTHeights)
}

// MarkEnginesReady 预建成功后调。
func MarkEnginesReady() error { return os.WriteFile(enginesMark(), []byte("1"), 0o644) }
