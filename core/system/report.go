package system

// system.sendReport:把崩溃报告 / 问题反馈经自建代理转到开发者的 Telegram。
//
// 放在核心层而不是两个 UI 各写一份:代理地址和密钥是编译期注入到核心层的,
// 脱敏规则也只该有一份 —— 两份迟早一份漏抹。

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"regexp"
	"runtime"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/httpx"
	"linplayer/core/paths"
)

// 自建代理(oauth-proxy)的地址和共享密钥,由 `-ldflags -X` 注入(见 core/cmd/sealsecrets)。
// 两者都不进提交:地址是我们自己的中转,密钥拿到就能刷代理。空 = 这个构建没配。
var (
	proxyBase string
	proxyKey  string
)

// ProxyBase / ProxyKey 给 core/sync(Trakt/Bangumi 换 token、爱发电校验)用同一份注入值,
// 不再往两个包各注一份 —— 两份就会有一份漏配。
func ProxyBase() string { return strings.TrimRight(strings.TrimSpace(proxyBase), "/") }
func ProxyKey() string  { return proxyKey }

// postProxy 往自建代理 POST 一段 JSON,原样交回状态码和响应体。
func postProxy(ctx context.Context, path string, body any) (int, []byte, error) {
	base := strings.TrimRight(strings.TrimSpace(proxyBase), "/")
	if base == "" {
		return 0, nil, fmt.Errorf("这个构建没有配反馈服务(需要在构建环境里提供 LP_SYNC_PROXY_BASE)")
	}
	b, err := json.Marshal(body)
	if err != nil {
		return 0, nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+path, strings.NewReader(string(b)))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if proxyKey != "" {
		req.Header.Set("X-LinPlayer-Key", proxyKey)
	}
	resp, err := httpx.Client().Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	out, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	return resp.StatusCode, out, err
}

// maxLogBytes 日志只带尾部。TG 单文件上限 50MB,这里卡小是为了国内慢链路也发得出去。
const maxLogBytes = 256 << 10

func registerReportCommands() {
	bus.Register("system.sendReport", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		kind, _ := a["kind"].(string)
		if kind != "crash" {
			kind = "feedback"
		}
		text, _ := a["text"].(string)
		crash, _ := a["crash"].(string)
		log, _ := a["log"].(string)
		if strings.TrimSpace(text+crash+log) == "" {
			return nil, bus.NewErr(bus.EInvalid, "报告是空的")
		}
		r := buildReport(kind, text, crash, log)
		// dry:发不出去时给「复制报告」用 —— 复制出去的也必须是脱敏后的那份
		if dry, _ := a["dry"].(bool); dry {
			return fmt.Sprintf("LinPlayer %s · %s · %s\n\n%s\n\n== 崩溃 ==\n%s\n\n== 日志 ==\n%s",
				r.Kind, r.Version, r.Platform, r.Text, r.Crash, r.Log), nil
		}
		code, body, err := postProxy(ctx, "/report", r)
		if err != nil {
			return nil, bus.NewErr(bus.ENetwork, "发送失败: %v", err)
		}
		if code == 429 {
			return nil, bus.NewErr(bus.EUpstream, "发得太频繁了,过一分钟再试")
		}
		if code < 200 || code >= 300 {
			return nil, bus.NewErr(bus.EUpstream, "发送失败: HTTP %d %s", code, strings.TrimSpace(string(body)))
		}
		return nil, nil
	})
}

type report struct {
	Kind     string `json:"kind"`
	Version  string `json:"version"`
	Platform string `json:"platform"`
	Text     string `json:"text"`
	Crash    string `json:"crash"`
	Log      string `json:"log"`
}

func buildReport(kind, text, crash, log string) report {
	if len(log) > maxLogBytes {
		log = "…(前面截掉了)\n" + log[len(log)-maxLogBytes:]
	}
	home, _ := os.UserHomeDir()
	s := func(v string) string { return Scrub(v, home, paths.Root()) }
	return report{
		Kind:     kind,
		Version:  Version,
		Platform: fmt.Sprintf("%s/%s panic=%d", runtime.GOOS, runtime.GOARCH, bus.PanicCount()),
		Text:     s(text),
		Crash:    s(crash),
		Log:      s(log),
	}
}

var (
	secretParam = regexp.MustCompile(`(?i)\b(api_key|apikey|x-emby-token|x-mediabrowser-token|token|access_token|refresh_token|pw|password|passwd|sign|authorization)(["']?\s*[=:]\s*["']?)(?:bearer\s+)?[^&\s"'<>,;]+`)
	// 用户自己的服务器地址也不外发(隐私说明里承诺过):只留协议,主机和端口抹掉
	urlHost = regexp.MustCompile(`(?i)\b(https?|wss?)://[^/\s"'<>]+`)
)

// Scrub 报告离开本机前的最后一道:数据目录 / 主目录(里面嵌着系统用户名)、
// 凭据类参数、URL 里的主机。本机回环地址留着 —— 那是核心层自己的本地通道,排查要看。
func Scrub(s, home, dataRoot string) string {
	if s == "" {
		return s
	}
	// 先抹数据目录:它通常在主目录底下,反过来就只剩半截
	for _, p := range [][2]string{{dataRoot, "<data>"}, {home, "~"}} {
		if len(p[0]) >= 4 {
			// 大小写不敏感:Windows 路径大小写不固定
			s = regexp.MustCompile("(?i)"+regexp.QuoteMeta(p[0])).ReplaceAllLiteralString(s, p[1])
		}
	}
	s = secretParam.ReplaceAllString(s, "${1}${2}<redacted>")
	return urlHost.ReplaceAllStringFunc(s, func(m string) string {
		i := strings.Index(m, "://")
		host := m[i+3:]
		if strings.HasPrefix(host, "127.0.0.1") || strings.HasPrefix(host, "localhost") {
			return m
		}
		return m[:i+3] + "<host>"
	})
}
