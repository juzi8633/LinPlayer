// Package transcribe 是 Whisper 本地转写(桌面独占)。
//
// 不预置任何模型,用户开启功能后按需下载。模型为 whisper.cpp 的 GGML 量化权重,
// 下载源默认 Hugging Face 官方仓库。转写本身靠外部 ffmpeg + whisper-cli 两个进程。
package transcribe

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"linplayer/core/httpx"
	"linplayer/core/paths"
)

// Model 模型规格。
type Model string

// 四档模型。**这些字面量是存盘键**。
const (
	Tiny   Model = "tiny"
	Base   Model = "base"
	Medium Model = "medium"
	Large  Model = "large"
)

var allModels = []Model{Tiny, Base, Medium, Large}

// Models 顺序即 UI 展示顺序。
func Models() []Model { return append([]Model(nil), allModels...) }

// ModelOf 按 key 取模型。
//
// ☠ **认不出必须报错,不能静默回落默认档** —— 黄金实现的 from_key 会悄悄回落,
// 于是前端传错一个字,用户点「下载 medium」实际下的是 base(1.5GB vs 142MB),
// 而界面上什么都不说。
func ModelOf(key string) (Model, error) {
	for _, m := range allModels {
		if string(m) == key {
			return m, nil
		}
	}
	return "", fmt.Errorf("未知的 Whisper 模型:%s", key)
}

// DisplayName 人话名字。
func (m Model) DisplayName() string {
	switch m {
	case Tiny:
		return "Tiny(最快,精度最低)"
	case Medium:
		return "Medium(较慢,精度好)"
	case Large:
		return "Large(最慢,精度最高)"
	}
	return "Base(快速,日常够用)"
}

// FileName 权重文件名(whisper.cpp GGML 格式)。
func (m Model) FileName() string {
	switch m {
	case Tiny:
		return "ggml-tiny.bin"
	case Medium:
		return "ggml-medium.bin"
	case Large:
		return "ggml-large-v3.bin"
	}
	return "ggml-base.bin"
}

// ApproxSize 权重大致体积(字节)。
//
// 下载确认框要拿字节数说话,UI 提示要拿人读的说法说话 —— 两边各记一份迟早对不上,
// 所以只有这一张表,SizeLabel 从它算。
func (m Model) ApproxSize() int64 {
	const mb = 1024 * 1024
	switch m {
	case Tiny:
		return 75 * mb
	case Medium:
		return 1500 * mb
	case Large:
		return 2900 * mb
	}
	return 142 * mb
}

// SizeLabel 大致体积(UI 提示用)。
func (m Model) SizeLabel() string {
	const mb = 1024 * 1024
	n := m.ApproxSize()
	if n >= 1000*mb {
		return "约 " + strconv.FormatFloat(float64(n)/float64(1000*mb), 'g', 2, 64) + " GB"
	}
	return "约 " + strconv.FormatInt(n/mb, 10) + " MB"
}

// officialBase 官方权重仓库。镜像基址由调用方传入。
const officialBase = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

// DownloadURL 下载地址。mirrorBase 为空用官方源。
func (m Model) DownloadURL(mirrorBase string) string {
	base := officialBase
	if strings.TrimSpace(mirrorBase) != "" {
		base = strings.TrimRight(strings.TrimSpace(mirrorBase), "/")
	}
	return base + "/" + m.FileName()
}

// ModelsDir 模型目录。
//
// ★ **必须在 data/ 而不是 cache/** —— 一个模型几百 MB 到几 GB,
// 被「清理缓存」顺手删掉等于让用户重下一晚上。
func ModelsDir() string { return filepath.Join(paths.Root(), "models") }

// ModelFile 某个模型的权重文件路径。
func ModelFile(m Model) string { return filepath.Join(ModelsDir(), m.FileName()) }

// DownloadedSize 已下载模型的体积(字节),未下载返回 0。
func DownloadedSize(m Model) int64 {
	st, err := os.Stat(ModelFile(m))
	if err != nil {
		return 0
	}
	return st.Size()
}

// Downloaded 已下载**且非半截文件**(> 1MB)。
func Downloaded(m Model) bool { return DownloadedSize(m) > 1024*1024 }

// Delete 删掉一个模型。
func Delete(m Model) error {
	f := ModelFile(m)
	if _, err := os.Stat(f); err != nil {
		return nil
	}
	if err := os.Remove(f); err != nil {
		return fmt.Errorf("删除模型失败: %w", err)
	}
	return nil
}

// Download 下载模型,返回落盘路径。
//
// 流式写临时文件,完成后原子改名 —— 中断不会留下半截损坏文件被当成「已下载」。
func Download(ctx context.Context, m Model, mirrorBase string, onProgress func(done, total int64)) (string, error) {
	u := m.DownloadURL(mirrorBase)
	/* ☠ 强制 https:自定义镜像可能填 http://,而明文下载会被中间人替换成篡改过的
	   GGML 权重,再交给原生 whisper 二进制去解析 —— 那是内存破坏级的攻击面。 */
	if !strings.HasPrefix(strings.ToLower(u), "https://") {
		return "", fmt.Errorf("模型下载地址必须为 https:%s", u)
	}
	dir := ModelsDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("建模型目录失败: %w", err)
	}
	target := ModelFile(m)
	tmp := target + ".part"

	if err := downloadTo(ctx, u, tmp, onProgress); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	_ = os.Remove(target)
	if err := os.Rename(tmp, target); err != nil {
		return "", fmt.Errorf("改名失败: %w", err)
	}
	return target, nil
}

// downloadTo 流式下载到文件(带进度)。
func downloadTo(ctx context.Context, u, out string, onProgress func(done, total int64)) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return fmt.Errorf("下载请求失败: %w", err)
	}
	resp, err := httpx.Client().Do(req)
	if err != nil {
		return fmt.Errorf("下载请求失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("下载失败: HTTP %d", resp.StatusCode)
	}
	total := resp.ContentLength
	if total < 0 {
		total = 0
	}
	f, err := os.Create(out)
	if err != nil {
		return fmt.Errorf("建文件失败: %w", err)
	}
	defer f.Close()

	buf := make([]byte, 256*1024)
	var got int64
	for {
		if ctx.Err() != nil {
			return fmt.Errorf("下载已取消")
		}
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return fmt.Errorf("写入失败: %w", werr)
			}
			got += int64(n)
			if onProgress != nil {
				onProgress(got, total)
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return fmt.Errorf("下载中断: %w", rerr)
		}
	}
	/* ☠ 收到的字节数要对得上 Content-Length。上游提前断流时 `Read` 回的就是
	   EOF —— 不比这一下的话,半截权重会被改名成正式文件,`Downloaded()` 从此
	   一直说「已下载」,而 whisper 每次都在同一个地方解析失败。用户看到的是
	   「转写失败」,删了重下也没用,因为根本不会重下。 */
	if total > 0 && got != total {
		return fmt.Errorf("下载不完整:收到 %d 字节,应当是 %d —— 网络中途断了,请重试", got, total)
	}
	return nil
}
