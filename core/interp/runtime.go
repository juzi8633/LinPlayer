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
	"sync"
	"sync/atomic"
	"time"

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
	dir := Dir()
	_ = os.RemoveAll(dir) // 上次解到一半的残留
	if err := fetchInto(ctx, runtimeURL, runtimeSHA256, dir, onProgress); err != nil {
		_ = os.RemoveAll(dir)
		return err
	}
	return os.WriteFile(readyMark(), []byte(RuntimeVersion), 0o644)
}

// fetchInto 下载一个 zip、校验 sha256、解进 dir(叠加,不清空)。
func fetchInto(ctx context.Context, url, wantSHA, dir string, onProgress Progress) error {
	if err := os.MkdirAll(paths.InterpDir(), 0o755); err != nil {
		return fmt.Errorf("建补帧组件目录失败: %w", err)
	}
	tmp := filepath.Join(paths.InterpDir(), filepath.Base(url)+".part")
	defer os.Remove(tmp)
	sum, err := download(ctx, url, tmp, onProgress)
	if err != nil {
		return err
	}
	if !strings.EqualFold(sum, wantSHA) {
		return fmt.Errorf("补帧组件校验失败(sha256 %s),可能下载被篡改或中断,请重试", sum)
	}
	return unzip(tmp, dir)
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

// rangedParts 大文件分几段并发下。用户 2026-09-17 看单线程下 GitHub 的 2.9GB 包「太慢了,开多线程」:
// 实测本机单连接几十 KB/s、16 段并发约 10MB/s。
const rangedParts = 16

// downloadRanged 把 url(size 字节)多线程下到 out 文件的 [off, off+size) 区段。
// 每段断了从断点续,最多重试 10 次;不校验内容,调用方自己按区段算 sha256。
//
// ponytail: 不做跨启动续传 —— 中途退出下次从头下。要续传得把每段进度落盘。
func downloadRanged(ctx context.Context, url, out string, off, size int64, onProgress func(done int64)) error {
	f, err := os.OpenFile(out, os.O_RDWR|os.O_CREATE, 0o644)
	if err != nil {
		return fmt.Errorf("建下载文件失败: %w", err)
	}
	defer f.Close()
	if st, err := f.Stat(); err == nil && st.Size() < off+size {
		if err := f.Truncate(off + size); err != nil {
			return fmt.Errorf("磁盘空间不够放下载文件(要 %d MB): %w", (off+size)>>20, err)
		}
	}
	var done atomic.Int64
	part := (size + rangedParts - 1) / rangedParts
	errs := make(chan error, rangedParts)
	var wg sync.WaitGroup
	for i := int64(0); i < rangedParts; i++ {
		from, to := i*part, min((i+1)*part, size)-1
		if from > to {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs <- fetchRange(ctx, url, f, off, from, to, &done, onProgress)
		}()
	}
	wg.Wait()
	close(errs)
	for e := range errs {
		if e != nil {
			return e
		}
	}
	return nil
}

func fetchRange(ctx context.Context, url string, f *os.File, off, from, to int64, done *atomic.Int64, onProgress func(int64)) error {
	pos := from
	var lastErr error
	for attempt := 0; attempt < 10 && pos <= to; attempt++ {
		if ctx.Err() != nil {
			return fmt.Errorf("下载已取消")
		}
		if attempt > 0 {
			time.Sleep(time.Duration(attempt) * time.Second)
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return fmt.Errorf("下载请求失败: %w", err)
		}
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", pos, to))
		resp, err := httpx.Client().Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		if resp.StatusCode != http.StatusPartialContent {
			resp.Body.Close()
			lastErr = fmt.Errorf("HTTP %d", resp.StatusCode)
			continue
		}
		buf := make([]byte, 256*1024)
		for pos <= to {
			n, rerr := resp.Body.Read(buf)
			if n > 0 {
				if _, werr := f.WriteAt(buf[:n], off+pos); werr != nil {
					resp.Body.Close()
					return fmt.Errorf("写入失败: %w", werr)
				}
				pos += int64(n)
				if onProgress != nil {
					onProgress(done.Add(int64(n)))
				}
			}
			if rerr != nil {
				if rerr != io.EOF {
					lastErr = rerr
				}
				break
			}
		}
		resp.Body.Close()
	}
	if pos <= to {
		return fmt.Errorf("下载中断(重试 10 次仍失败): %v", lastErr)
	}
	return nil
}

// sha256Range 文件 [off, off+size) 的 sha256。
func sha256Range(path string, off, size int64) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, io.NewSectionReader(f, off, size)); err != nil {
		return "", fmt.Errorf("校验下载文件失败: %w", err)
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
