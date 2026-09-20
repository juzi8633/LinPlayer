package transcribe

// 外部二进制定位与安装:whisper-cli 与 ffmpeg 都不随包发,按需找/按需下。

import (
	"archive/zip"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"

	"linplayer/core/paths"
)

// Deps 两个外部可执行文件的定位结果。空串 = 没找到。
type Deps struct {
	Whisper string
	FFmpeg  string
	Ready   bool
}

// ResolveDeps 一次定位两个。
func ResolveDeps(configuredWhisper, configuredFFmpeg string) Deps {
	d := Deps{Whisper: ResolveWhisper(configuredWhisper), FFmpeg: ResolveFFmpeg(configuredFFmpeg)}
	d.Ready = d.Whisper != "" && d.FFmpeg != ""
	return d
}

// depsError 缺了哪个就说哪个。
//
// 笼统一句「依赖不全」会让用户在两个下载按钮之间瞎点 —— 这两样的装法完全不同:
// ffmpeg 应用内能下,whisper-cli 只能用户自己放。
func depsError(d Deps) error {
	switch {
	case d.Whisper == "" && d.FFmpeg == "":
		return fmt.Errorf("缺少 whisper-cli 和 ffmpeg,两个都要先装好才能转写")
	case d.Whisper == "":
		return fmt.Errorf("找不到 whisper-cli,把它放进 PATH 或应用的 bin 目录(应用内下不了:官方不发布可直接下载的构建)")
	case d.FFmpeg == "":
		return fmt.Errorf("找不到 ffmpeg,去设置的「扩展组件」页下载,或自己装好放进 PATH")
	}
	return nil
}

func exeName(stem string) string {
	if runtime.GOOS == "windows" {
		return stem + ".exe"
	}
	return stem
}

// binDir 下载来的 whisper/ffmpeg 可执行文件。同 ModelsDir:重下代价高,放 data/ 不放 cache/。
func binDir() string { return filepath.Join(paths.Root(), "bin") }

func exeDir() string {
	p, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(p)
}

func isFile(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

var (
	runsOKMu   sync.Mutex
	runsOKMemo = map[string]bool{}
)

// runsOk 这个名字在 PATH 上跑得起来吗。
//
// ★ 结果**按 exe 名缓存**。这是「每次打开字幕翻译都卡」的元凶那一半:
// 探测要真的 spawn 一次子进程,最多 4 次;不缓存的话每次打开设置页都重来一遍。
// 缓存是正确的:一次会话内 PATH 不会变,而用户装了 ffmpeg 之后重开一次应用即可。
func runsOk(exe string) bool {
	runsOKMu.Lock()
	if v, ok := runsOKMemo[exe]; ok {
		runsOKMu.Unlock()
		return v
	}
	runsOKMu.Unlock()

	cmd := exec.Command(exe, "-version")
	hideWindow(cmd)
	ok := cmd.Run() == nil

	runsOKMu.Lock()
	runsOKMemo[exe] = ok
	runsOKMu.Unlock()
	return ok
}

func commonFFmpegLocations() []string {
	switch runtime.GOOS {
	case "windows":
		return []string{`C:\ffmpeg\bin\ffmpeg.exe`, `C:\Program Files\ffmpeg\bin\ffmpeg.exe`}
	case "darwin":
		return []string{"/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"}
	}
	return []string{"/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/snap/bin/ffmpeg"}
}

// ResolveFFmpeg 定位 ffmpeg,找不到返回空串。
//
// 顺序:用户指定 → 已下载缓存 → 随应用打包 → 系统 PATH → 常见安装位置。
func ResolveFFmpeg(configured string) string {
	name := exeName("ffmpeg")
	if configured != "" {
		/* 指定了就**只认它**。指错了回空串(=「没找到」),不偷偷回落到 PATH:
		   回落的话用户指哪儿都不影响结果,而他以为自己换了一个 ffmpeg。 */
		if isFile(configured) {
			return configured
		}
		return ""
	}
	if cached := filepath.Join(binDir(), name); isFile(cached) {
		return cached
	}
	d := exeDir()
	for _, c := range []string{
		filepath.Join(d, name),
		filepath.Join(d, "ffmpeg", name),
		filepath.Join(d, "bin", name),
	} {
		if isFile(c) {
			return c
		}
	}
	if runsOk(name) {
		return name // PATH
	}
	for _, c := range commonFFmpegLocations() {
		if isFile(c) {
			return c
		}
	}
	return ""
}

// ResolveWhisper 定位 whisper-cli(用户指定/缓存/内置/PATH/旧名 main|whisper),
// 找不到返回空串。
func ResolveWhisper(configured string) string {
	name := exeName("whisper-cli")
	if configured != "" {
		if isFile(configured) {
			return configured
		}
		return "" // 同 ResolveFFmpeg:指定了就只认它
	}
	if cached := filepath.Join(binDir(), name); isFile(cached) {
		return cached
	}
	d := exeDir()
	for _, c := range []string{
		filepath.Join(d, name),
		filepath.Join(d, "whisper", name),
		filepath.Join(d, "bin", name),
		filepath.Join(d, "..", "Resources", "whisper", name),
	} {
		if isFile(c) {
			return c
		}
	}
	if runsOk(name) {
		return name // PATH
	}
	// 兼容旧名 main / whisper。
	for _, alt := range []string{"main", "whisper"} {
		if c := filepath.Join(d, exeName(alt)); isFile(c) {
			return c
		}
	}
	return ""
}

// ffmpeg 静态构建下载地址。
//
// 这两个是 ffmpeg 官网 Download 页给各平台指的源,属于**公开的官方分发地址**,
// 和 API 基址一个性质;而且用户自己装一个放进 PATH 就会优先用那一个,绕得开。
const (
	ffmpegWinURL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
	ffmpegMacURL = "https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip"
)

// DownloadFFmpeg 下载并安装 ffmpeg 到应用 bin 目录,返回可执行文件路径。
//
// Linux 上游是 .tar.xz,Go 标准库解不了 —— **明确报错让用户走包管理器**,
// 而不是下完 30MB 再失败。
func DownloadFFmpeg(ctx context.Context, onProgress func(done, total int64)) (string, error) {
	var u string
	switch runtime.GOOS {
	case "windows":
		u = ffmpegWinURL
	case "darwin":
		u = ffmpegMacURL
	default:
		return "", fmt.Errorf(
			"Linux 上请用发行版包管理器安装 ffmpeg(如 apt install ffmpeg),放进 PATH —— " +
				"上游只提供 .tar.xz,应用内解不了")
	}

	dir := binDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("建 bin 目录失败: %w", err)
	}
	tmp := filepath.Join(dir, "ffmpeg_dl.zip")
	if err := downloadTo(ctx, u, tmp, onProgress); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	defer os.Remove(tmp)

	out := filepath.Join(dir, exeName("ffmpeg"))
	if err := extractFFmpegFromZip(tmp, out); err != nil {
		return "", err
	}
	setExecutable(out)
	return out, nil
}

// extractFFmpegFromZip 从 zip 里挑出 ffmpeg 可执行文件。
//
// **按文件名找,不按路径找**:包内路径含版本号(ffmpeg-7.x-essentials_build/bin/ffmpeg.exe),
// 写死路径会在上游发版时静默失效。
func extractFFmpegFromZip(zipPath, out string) error {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return fmt.Errorf("解包失败: %w", err)
	}
	defer zr.Close()
	want := exeName("ffmpeg")
	for _, f := range zr.File {
		if filepath.Base(strings.ReplaceAll(f.Name, "\\", "/")) != want {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return fmt.Errorf("解包失败: %w", err)
		}
		defer rc.Close()
		/* ☠ 先写 .part 再改名。直接往最终名字上写的话,中途失败会留下一个
		   半截的 ffmpeg.exe,而 ResolveFFmpeg 只判「文件在不在」—— 它会被
		   一直当成可用的 ffmpeg,每次转写都在同一个地方失败,重下也不会覆盖。 */
		tmp := out + ".part"
		dst, err := os.Create(tmp)
		if err != nil {
			return fmt.Errorf("写 ffmpeg 失败: %w", err)
		}
		_, cerr := io.Copy(dst, rc)
		closeErr := dst.Close()
		if cerr != nil || closeErr != nil {
			_ = os.Remove(tmp)
			if cerr == nil {
				cerr = closeErr
			}
			return fmt.Errorf("写 ffmpeg 失败: %w", cerr)
		}
		_ = os.Remove(out)
		if err := os.Rename(tmp, out); err != nil {
			_ = os.Remove(tmp)
			return fmt.Errorf("写 ffmpeg 失败: %w", err)
		}
		return nil
	}
	return fmt.Errorf("包内未找到 %s", want)
}

func setExecutable(p string) {
	if runtime.GOOS != "windows" {
		_ = os.Chmod(p, 0o755)
	}
}
