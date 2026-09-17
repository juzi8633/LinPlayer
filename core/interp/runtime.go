package interp

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"linplayer/core/httpx"
	"linplayer/core/paths"
)

// 运行时包。换包 = 改这三行 + 重跑 scripts/pack-interp-runtime.sh 发新 Release。
//
// ★ 版本号进目录名:新旧包解到不同目录,换包时不会出现「一半新文件一半旧文件」。
const (
	RuntimeVersion = "1"
	runtimeURL     = "https://github.com/zzzwannasleep/LinPlayer/releases/download/interp-runtime-1/linplayer-interp-runtime-win-x64-1.zip"
	runtimeSHA256  = "a10b4934010f0d3a9d8bfd2a09354983166c3dc584e750bbfe9dcad9f0ce627d"
	// RuntimeSize 下载体积(字节),给 UI 说「要下多少」。
	RuntimeSize = 66295617
)

// Dir 运行时解到哪。data/ 而不是 cache/:重下要一百多 MB,不能被「清理缓存」顺手删掉。
func Dir() string { return filepath.Join(paths.InterpDir(), "runtime-"+RuntimeVersion) }

// readyMark 解包完成才写的标记。只看 VSScript.dll 在不在不够 —— 解到一半断电也会有它。
func readyMark() string { return filepath.Join(Dir(), ".ready") }

// Installed 运行时已经完整解好。
func Installed() bool {
	_, err := os.Stat(readyMark())
	return err == nil
}

// Progress 下载进度:(已收字节, 总字节)。
type Progress func(done, total int64)

// Install 下载并解包运行时。已装好直接返回。
//
// ★ 先校验 sha256 再解包:这个包里是会被加载进本进程的 DLL 和 Python,
// 被替换过的包等于在播放器里执行任意代码。
func Install(ctx context.Context, onProgress Progress) error {
	if Installed() {
		return nil
	}
	if err := os.MkdirAll(paths.InterpDir(), 0o755); err != nil {
		return fmt.Errorf("建补帧组件目录失败: %w", err)
	}
	tmp := filepath.Join(paths.InterpDir(), "runtime-"+RuntimeVersion+".zip.part")
	defer os.Remove(tmp)
	sum, err := download(ctx, runtimeURL, tmp, onProgress)
	if err != nil {
		return err
	}
	if !strings.EqualFold(sum, runtimeSHA256) {
		return fmt.Errorf("补帧组件校验失败(sha256 %s),可能下载被篡改或中断,请重试", sum)
	}
	dir := Dir()
	_ = os.RemoveAll(dir) // 上次解到一半的残留
	if err := unzip(tmp, dir); err != nil {
		_ = os.RemoveAll(dir)
		return err
	}
	return os.WriteFile(readyMark(), []byte(RuntimeVersion), 0o644)
}

func download(ctx context.Context, u, out string, onProgress Progress) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", fmt.Errorf("下载补帧组件失败: %w", err)
	}
	resp, err := httpx.Client().Do(req)
	if err != nil {
		return "", fmt.Errorf("下载补帧组件失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("下载补帧组件失败: HTTP %d", resp.StatusCode)
	}
	f, err := os.Create(out)
	if err != nil {
		return "", fmt.Errorf("建临时文件失败: %w", err)
	}
	defer f.Close()
	h := sha256.New()
	pw := &progressWriter{total: resp.ContentLength, fn: onProgress}
	if _, err := io.Copy(io.MultiWriter(f, h, pw), resp.Body); err != nil {
		if ctx.Err() != nil {
			return "", fmt.Errorf("下载已取消")
		}
		return "", fmt.Errorf("下载补帧组件中断: %w", err)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

type progressWriter struct {
	done, total int64
	fn          Progress
}

func (p *progressWriter) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	if p.fn != nil {
		p.fn(p.done, p.total)
	}
	return len(b), nil
}

// unzip 解到 dir。条目路径逃出 dir 的(`../`、绝对路径)直接报错 —— 哈希对上了也查,
// 包是脚本打的,出现这种条目只能说明打包脚本坏了。
func unzip(src, dir string) error {
	zr, err := zip.OpenReader(src)
	if err != nil {
		return fmt.Errorf("解包补帧组件失败: %w", err)
	}
	defer zr.Close()
	root := filepath.Clean(dir) + string(os.PathSeparator)
	for _, f := range zr.File {
		dst := filepath.Join(dir, filepath.FromSlash(f.Name))
		if !strings.HasPrefix(dst, root) {
			return fmt.Errorf("补帧组件包里有非法路径:%s", f.Name)
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(dst, 0o755); err != nil {
				return fmt.Errorf("解包补帧组件失败: %w", err)
			}
			continue
		}
		if err := extractOne(f, dst); err != nil {
			return err
		}
	}
	return nil
}

func extractOne(f *zip.File, dst string) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return fmt.Errorf("解包补帧组件失败: %w", err)
	}
	rc, err := f.Open()
	if err != nil {
		return fmt.Errorf("解包补帧组件失败: %w", err)
	}
	defer rc.Close()
	w, err := os.Create(dst)
	if err != nil {
		return fmt.Errorf("解包补帧组件失败: %w", err)
	}
	defer w.Close()
	if _, err := io.Copy(w, rc); err != nil {
		return fmt.Errorf("解包补帧组件失败: %w", err)
	}
	return nil
}
