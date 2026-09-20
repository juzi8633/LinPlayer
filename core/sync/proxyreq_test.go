package sync

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

/*
代发的两条底线(SPEC 17.3 18.2,D365):

  1. 没连账号 → 抛 auth,不是回空。回空的话插件把「没登录」和
     「这个条目没有记录」当成同一件事,表现是「同步开着但什么都不同步」。
  2. path 只收相对路径。放开绝对地址等于让插件指挥我们带着 Authorization
     去打任意站点 —— 那是一个凭据外泄的口子,而调用看起来完全正常。
*/

func TestProxyRequest_没连账号要抛auth(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	for _, svc := range []string{"trakt", "bangumi"} {
		_, err := ProxyRequest(context.Background(), svc, "GET", "/x", nil)
		e, ok := err.(*bus.Err)
		if !ok || e.Code != bus.EAuth {
			t.Fatalf("%s 没连账号时应当抛 %s,实际 %v", svc, bus.EAuth, err)
		}
	}
}

func TestProxyRequest_绝对地址要被挡住(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	if err := Save("bangumi", &Account{Service: "bangumi", AccessToken: "假token"}); err != nil {
		t.Fatal(err)
	}
	// 拿一个真起来的假上游当「别人的站」——挡住的话它一条请求都不该收到
	var hit int
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit++
		w.Write([]byte(`{}`))
	}))
	defer other.Close()

	_, err := ProxyRequest(context.Background(), "bangumi", "GET", other.URL+"/steal", nil)
	if err == nil {
		t.Fatal("绝对地址竟然放行了 —— 插件能指挥我们带着 token 去打任意站点")
	}
	if hit != 0 {
		t.Fatalf("被挡住了却还是发出去了 %d 次", hit)
	}
}

// 正常的一条:相对路径 + 带上 Authorization 发出去,响应原样回。
func TestProxyRequest_带token发出去并原样回(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	if err := Save("bangumi", &Account{Service: "bangumi", AccessToken: "假token"}); err != nil {
		t.Fatal(err)
	}
	var gotAuth, gotPath, gotMethod string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotPath, gotMethod = r.Header.Get("Authorization"), r.URL.Path, r.Method
		w.Write([]byte(`{"ok":true}`))
	}))
	defer up.Close()
	old := BangumiAPIOfficial
	BangumiAPIOfficial = up.URL
	defer func() { BangumiAPIOfficial = old }()

	out, err := ProxyRequest(context.Background(), "bangumi", "post", "v0/x", map[string]any{"a": 1})
	if err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer 假token" {
		t.Errorf("没带上 token,上游收到的 Authorization 是 %q", gotAuth)
	}
	if gotPath != "/v0/x" || gotMethod != "POST" {
		t.Errorf("方法或路径不对:%s %s", gotMethod, gotPath)
	}
	m, _ := out.(map[string]any)
	if m["ok"] != true {
		t.Errorf("响应没原样回:%v", out)
	}
}

// 上游报错要带着状态码回去 —— 插件按 409 / 404 分支走,笼统的「失败」它分不出来。
func TestProxyRequest_上游报错带着状态码回去(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	if err := Save("bangumi", &Account{Service: "bangumi", AccessToken: "假token"}); err != nil {
		t.Fatal(err)
	}
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(409)
		w.Write([]byte(`{"description":"已经标过了"}`))
	}))
	defer up.Close()
	old := BangumiAPIOfficial
	BangumiAPIOfficial = up.URL
	defer func() { BangumiAPIOfficial = old }()

	_, err := ProxyRequest(context.Background(), "bangumi", "POST", "/v0/x", nil)
	if err == nil || !strings.Contains(err.Error(), "409") {
		t.Fatalf("错误里应当带着 409,实际 %v", err)
	}
	if e, ok := err.(*bus.Err); !ok || !strings.Contains(e.Detail, "已经标过了") {
		t.Fatalf("上游那句话应当原样带回去,实际 %v", err)
	}
}
