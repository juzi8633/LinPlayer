package transcribe

// 一条龙:音视频地址 → ffmpeg 抽 16kHz 单声道 WAV → whisper-cli 转写 → SRT 文本。

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"linplayer/core/paths"
)

// Options 一次转写的全部输入。
type Options struct {
	// URL 带鉴权 token 的流地址。**只有宿主拿得到**,所以本包收地址不收条目 id。
	URL string
	// Headers 取流要带的请求头(Emby 的 token 头就走这里)。
	Headers map[string]string
	// Lang 源语言,空 = 让 whisper 自己判。
	Lang string
	// Model 用哪一档权重。必须是 ModelOf 认得的值。
	Model Model
	// Whisper / FFmpeg 可执行文件路径,空 = 交给 ResolveDeps 找。
	Whisper string
	FFmpeg  string
}

// Transcribe 转写整条流,返回 SRT 文本(不落盘,由调用方决定怎么用)。
//
// onProgress 收 0..1;可以为 nil。
func Transcribe(ctx context.Context, opt Options, onProgress func(float64)) (string, error) {
	if strings.TrimSpace(opt.URL) == "" {
		return "", fmt.Errorf("缺少媒体地址")
	}
	m, err := ModelOf(string(opt.Model))
	if err != nil {
		return "", err
	}
	if !Downloaded(m) {
		return "", fmt.Errorf("模型 %s 还没下载(%s)", m, m.SizeLabel())
	}
	deps := ResolveDeps(opt.Whisper, opt.FFmpeg)
	if err := depsError(deps); err != nil {
		return "", err
	}

	/* ☠ 每次一个**独立目录**,不能按地址哈希命名:同一条流被转写两次
	   (用户点了两下、或者实时与整轨同时跑)时,两边写同一个 wav、
	   先跑完的那个 defer 把另一边的文件删掉 —— 表现是「转写到一半说没产出字幕」。 */
	root := filepath.Join(paths.TempDir(), "transcribe")
	if err := os.MkdirAll(root, 0o755); err != nil {
		return "", fmt.Errorf("建临时目录失败: %w", err)
	}
	dir, err := os.MkdirTemp(root, "t")
	if err != nil {
		return "", fmt.Errorf("建临时目录失败: %w", err)
	}
	defer os.RemoveAll(dir)
	stem := filepath.Join(dir, "a")
	wav := stem + ".wav"
	srt := stem + ".srt"

	report(onProgress, 0)
	if err := extractAudio(ctx, deps.FFmpeg, opt, wav); err != nil {
		return "", err
	}
	// 抽音频阶段没有进度:总时长要先 ffprobe 才知道,为一个进度条多起一个进程不划算。
	report(onProgress, extractShare)

	if err := runWhisper(ctx, deps.Whisper, ModelFile(m), opt.Lang, wav, stem, onProgress); err != nil {
		return "", err
	}
	out, err := os.ReadFile(srt)
	if err != nil {
		return "", fmt.Errorf("whisper 没产出字幕文件: %w", err)
	}
	report(onProgress, 1)
	return string(out), nil
}

// extractShare 抽音频占进度条的比例。whisper 才是大头,抽音频给一成。
const extractShare = 0.1

func report(f func(float64), v float64) {
	if f != nil {
		f(v)
	}
}

// headerBlock 把请求头拼成 ffmpeg -headers 要的一坨。
//
// ☠ 值里含 CR/LF 就直接拒:拼进去等于让调用方往请求里注入任意头,
// 而 token 就在相邻那一行 —— 这是能把凭据送去别处的口子。
func headerBlock(h map[string]string) (string, error) {
	var b strings.Builder
	for k, v := range h {
		if strings.ContainsAny(k, "\r\n:") || strings.ContainsAny(v, "\r\n") {
			return "", fmt.Errorf("请求头 %q 含非法字符", k)
		}
		b.WriteString(k + ": " + v + "\r\n")
	}
	return b.String(), nil
}

// extractAudio 抽成 16kHz 单声道 WAV —— whisper.cpp 只吃这个格式。
//
// 直接从 HTTP 流抽,不必先把整片下下来。
func extractAudio(ctx context.Context, ffmpeg string, opt Options, out string) error {
	args := []string{"-y", "-loglevel", "error"}
	if len(opt.Headers) > 0 && strings.HasPrefix(strings.ToLower(opt.URL), "http") {
		blk, err := headerBlock(opt.Headers)
		if err != nil {
			return err
		}
		args = append(args, "-headers", blk)
	}
	args = append(args, "-i", opt.URL, "-vn", "-ar", "16000", "-ac", "1", "-f", "wav", out)

	cmd := exec.CommandContext(ctx, ffmpeg, args...)
	hideWindow(cmd)
	stderr, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("ffmpeg 抽音频失败: %v: %s", err, tail(string(stderr)))
	}
	if st, err := os.Stat(out); err != nil || st.Size() < 1024 {
		return fmt.Errorf("ffmpeg 没抽出音频(这一路可能没有音轨)")
	}
	return nil
}

// runWhisper 跑 whisper-cli 出 SRT。
//
// `-pp` 的百分比是 whisper-cli 唯一往外吐的进度,只在 stderr 上 —— 想要进度就得读它。
func runWhisper(ctx context.Context, bin, model, lang, wav, stem string, onProgress func(float64)) error {
	l := strings.TrimSpace(lang)
	if l == "" {
		l = "auto"
	}
	cmd := exec.CommandContext(ctx, bin,
		"-m", model, "-f", wav, "-l", l, "-osrt", "-of", stem, "-pp")
	hideWindow(cmd)
	pipe, err := cmd.StderrPipe()
	if err != nil {
		return fmt.Errorf("whisper 启动失败: %w", err)
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("whisper 启动失败: %w", err)
	}

	var last []string
	sc := bufio.NewScanner(pipe)
	for sc.Scan() {
		line := sc.Text()
		if p, ok := parseProgress(line); ok {
			report(onProgress, extractShare+(1-extractShare)*p)
			continue
		}
		last = append(last, line)
		if len(last) > 8 {
			last = last[1:]
		}
	}
	if err := cmd.Wait(); err != nil {
		return fmt.Errorf("whisper 转写失败: %v: %s", err, tail(strings.Join(last, "\n")))
	}
	return nil
}

// parseProgress 从 `whisper_print_progress_callback: progress =  42%` 里抠出 0..1。
func parseProgress(line string) (float64, bool) {
	i := strings.Index(line, "progress =")
	if i < 0 {
		return 0, false
	}
	s := strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(line[i+len("progress ="):]), "%"))
	n, err := strconv.Atoi(s)
	if err != nil || n < 0 || n > 100 {
		return 0, false
	}
	return float64(n) / 100, true
}

// tail 只留末尾几行喂给错误消息 —— 用户会原样看到它,整篇 ffmpeg 日志没人读。
func tail(s string) string {
	s = strings.TrimSpace(s)
	if len(s) <= 400 {
		return s
	}
	return "..." + s[len(s)-400:]
}
