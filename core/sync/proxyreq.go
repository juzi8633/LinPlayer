package sync

// Trakt / Bangumi 代发(SPEC 17.3 18.2,D365)。
//
// 账号连接与 token 刷新留在宿主(用户只登一次),同步插件借宿主的 token 发请求。
// ☠ **插件拿不到 token**:这是 D11 在第三方账号上的同一条底线 ——
//   token 交出去等于把用户的 Trakt / Bangumi 账号交出去,而那件事出事时
//   看起来像是上游被盗,查不到是我们放的口。

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/httpx"
)

// ProxyRequest 用宿主的 token 发一条请求,把响应原样交回。
//
// path 只接**相对路径**:给绝对地址的话插件可以让我们带着 Trakt 的
// Authorization 头去打任意站点,那是一个凭据外泄的口子。
func ProxyRequest(ctx context.Context, service, method, path string, body any) (any, error) {
	base, hdr, err := serviceAuth(ctx, service)
	if err != nil {
		return nil, err
	}
	p := strings.TrimSpace(path)
	if p == "" || strings.Contains(p, "://") {
		return nil, bus.NewErr(bus.EInvalid, "path 只能是相对路径(带 token 的请求不许发去任意地址)")
	}
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}

	m := strings.ToUpper(strings.TrimSpace(method))
	switch m {
	case "GET", "POST", "PUT", "PATCH", "DELETE":
	default:
		return nil, bus.NewErr(bus.EInvalid, "不支持的方法 %s", method)
	}

	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, bus.NewErr(bus.EInvalid, "body 序列化失败: %v", err)
		}
		rd = bytes.NewReader(b)
		hdr.Set("Content-Type", "application/json")
	}
	req, err := http.NewRequestWithContext(ctx, m, base+p, rd)
	if err != nil {
		return nil, bus.NewErr(bus.EInvalid, "%v", err)
	}
	req.Header = hdr
	resp, err := httpx.Client().Do(req)
	if err != nil {
		return nil, &bus.Err{Code: bus.ENetwork, Msg: err.Error(), Retryable: true}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))

	if resp.StatusCode >= 400 {
		// 上游说了话就原样带回去:插件要按 409 / 404 分支走,笼统的「失败」它分不出来
		return nil, &bus.Err{
			Code:      bus.EUpstream,
			Msg:       fmt.Sprintf("%s 返回 HTTP %d", service, resp.StatusCode),
			Detail:    truncate(string(raw), 512),
			Retryable: resp.StatusCode == 429 || resp.StatusCode >= 500,
		}
	}
	if len(bytes.TrimSpace(raw)) == 0 {
		return map[string]any{"status": resp.StatusCode}, nil
	}
	var out any
	if json.Unmarshal(raw, &out) != nil {
		return string(raw), nil
	}
	return out, nil
}

// serviceAuth 取这个服务的基址与**带 token 的请求头**。
// 头只在这个函数里造,不往外返回 token 本身。
func serviceAuth(ctx context.Context, service string) (string, http.Header, error) {
	a := Load(service)
	if a == nil {
		return "", nil, bus.NewErr(bus.EAuth, "还没有连接 %s", service)
	}
	switch service {
	case "trakt":
		v := TraktEnsureValid(ctx, a)
		if v == nil {
			return "", nil, bus.NewErr(bus.EAuth, "Trakt 登录已过期,请重新连接")
		}
		return TraktAPI, http.Header{
			"Authorization":     {"Bearer " + v.AccessToken},
			"trakt-api-version": {"2"},
			"trakt-api-key":     {TraktClientID()},
		}, nil
	case "bangumi":
		v := BangumiEnsureValid(ctx, a)
		if v == nil {
			return "", nil, bus.NewErr(bus.EAuth, "Bangumi 登录已过期,请重新连接")
		}
		return BangumiAPIOfficial, http.Header{"Authorization": {"Bearer " + v.AccessToken}}, nil
	}
	return "", nil, bus.NewErr(bus.EInvalid, "未知服务 %s", service)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

func registerProxyRequests() {
	for _, svc := range []string{"trakt", "bangumi"} {
		service := svc
		bus.Register("sync."+service+"Request", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
			method, _ := a["method"].(string)
			path, _ := a["path"].(string)
			return ProxyRequest(ctx, service, method, path, a["body"])
		})
	}
}
