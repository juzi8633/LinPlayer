package companion

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

var loopback = net.IPv4(127, 0, 0, 1)

// 被调过的假命令及其参数。假命令只替身「业务」,路由 / 白名单 / 事件走的是真代码。
var (
	seenMu sync.Mutex
	seen   = map[string]map[string]any{}
)

func fake(name string, fail bool) {
	bus.Register(name, func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		seenMu.Lock()
		seen[name] = a
		seenMu.Unlock()
		if fail && a["server_id"] == "bad" {
			return nil, bus.NewErr(bus.ENotFound, "没有这个服务器: bad")
		}
		return map[string]any{"ok": true}, nil
	})
}

func called(name string) (map[string]any, bool) {
	seenMu.Lock()
	defer seenMu.Unlock()
	a, ok := seen[name]
	return a, ok
}

func TestMain(m *testing.M) {
	bus.Init()
	dir, _ := os.MkdirTemp("", "companion-test-")
	paths.SetRoot(dir)
	if _, err := config.Load(); err != nil {
		panic(err)
	}
	lanIP = func() (net.IP, error) { return loopback, nil }
	// 形状照抄真 emby.currentSession
	bus.Register("emby.currentSession", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return map[string]any{"server": "http://emby.invalid", "token": "tok", "user_id": "u1", "user_name": "某人"}, nil
	})
	fake("account.removeAccount", true)
	fake("player.seek", false)
	fake("system.exportDiagnostics", false) // 真实存在但不在白名单里
	RegisterCommands()
	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

func invoke(t *testing.T, name string, args map[string]any) map[string]any {
	t.Helper()
	out, err := bus.Invoke(context.Background(), name, args)
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return out.(map[string]any)
}

// reopen 关再开,拿新的状态。
func reopen(t *testing.T) map[string]any {
	t.Helper()
	invoke(t, "companion.setEnabled", map[string]any{"enabled": false})
	return invoke(t, "companion.setEnabled", map[string]any{"enabled": true})
}

func do(t *testing.T, method, url, body string) (int, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest(method, url, strings.NewReader(body))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, url, err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	var j map[string]any
	_ = json.Unmarshal(b, &j) // 404/405 没有正文,解不出来是对的
	return resp.StatusCode, j
}

func drain() {
	for bus.NextEvent(0) != nil {
	}
}

// waitEvent 等 d 时间内第一条名为 name 的事件;没等到返回 nil。
func waitEvent(name string, d time.Duration) map[string]any {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		raw := bus.NextEvent(50)
		if raw == nil {
			continue
		}
		var e struct {
			Name string         `json:"name"`
			Data map[string]any `json:"data"`
		}
		if json.Unmarshal(raw, &e) == nil && e.Name == name {
			if e.Data == nil {
				e.Data = map[string]any{}
			}
			return e.Data
		}
	}
	return nil
}

func TestToken不对一律404(t *testing.T) {
	s := reopen(t)
	url := s["url"].(string)
	if code, _ := do(t, "GET", url, ""); code != 200 {
		t.Fatalf("正确的地址打不开: %d", code)
	}
	if code, _ := do(t, "GET", url+"/", ""); code != 200 {
		t.Fatalf("末尾带 / 打不开: %d", code)
	}
	wrong := url[:strings.LastIndex(url, "/c/")] + "/c/000000000000"
	for _, u := range []string{wrong, wrong + "/api/key", url[:strings.LastIndex(url, "/c/")] + "/"} {
		method := "GET"
		if strings.HasSuffix(u, "/api/key") {
			method = "POST"
		}
		if code, _ := do(t, method, u, `{"key":"up"}`); code != 404 {
			t.Fatalf("%s %s 应当 404,实际 %d", method, u, code)
		}
	}
}

func Test接口只收POST(t *testing.T) {
	s := reopen(t)
	if code, _ := do(t, "GET", s["url"].(string)+"/api/key", ""); code != 405 {
		t.Fatalf("GET 接口应当 405,实际 %d", code)
	}
}

func Test关再开换token旧码作废(t *testing.T) {
	a := reopen(t)
	b := reopen(t)
	ua, ub := a["url"].(string), b["url"].(string)
	ta, tb := ua[strings.LastIndex(ua, "/c/"):], ub[strings.LastIndex(ub, "/c/"):]
	if ta == tb {
		t.Fatalf("关再开 token 没变: %s", ta)
	}
	old := ub[:strings.LastIndex(ub, "/c/")] + ta // 新端口 + 旧 token
	if code, _ := do(t, "GET", old, ""); code != 404 {
		t.Fatalf("旧二维码在新服务上还能打开: %d", code)
	}
}

func Test白名单外的命令被拒(t *testing.T) {
	s := reopen(t)
	code, j := do(t, "POST", s["url"].(string)+"/api/system.exportDiagnostics", "{}")
	if code != 403 {
		t.Fatalf("白名单外的命令应当 403,实际 %d %v", code, j)
	}
	if _, ok := called("system.exportDiagnostics"); ok {
		t.Fatal("被拒的命令仍然被执行了")
	}
}

func Test按键发出companion_key事件(t *testing.T) {
	s := reopen(t)
	drain()
	if code, j := do(t, "POST", s["url"].(string)+"/api/key", `{"key":"up"}`); code != 200 {
		t.Fatalf("按键接口失败: %d %v", code, j)
	}
	ev := waitEvent("companion.key", 2*time.Second)
	if ev == nil || ev["key"] != "up" {
		t.Fatalf("没收到 companion.key{key:up},收到 %v", ev)
	}
	if code, _ := do(t, "POST", s["url"].(string)+"/api/key", `{"key":"rm -rf"}`); code != 400 {
		t.Fatalf("不认识的按键应当 400,实际 %d", code)
	}
	if Status()["connected"] != true {
		t.Fatal("刚收到手机页请求,connected 应为 true")
	}
}

func Test改账号成功后发account_status(t *testing.T) {
	s := reopen(t)
	api := s["url"].(string) + "/api/account.removeAccount"
	drain()
	if _, j := do(t, "POST", api, `{"server_id":"bad"}`); j["error"] == nil {
		t.Fatalf("失败的命令应当回 error: %v", j)
	}
	if ev := waitEvent("account.status", 300*time.Millisecond); ev != nil {
		t.Fatal("命令失败了却广播了 account.status")
	}
	if _, j := do(t, "POST", api, `{"server_id":"good"}`); j["error"] != nil {
		t.Fatalf("删除失败: %v", j)
	}
	if ev := waitEvent("account.status", 2*time.Second); ev == nil {
		t.Fatal("删账号成功后没有广播 account.status —— 电视会停在旧的那一屏")
	}
}

func Test拿不到IP服务照起(t *testing.T) {
	lanIP = func() (net.IP, error) { return nil, errors.New("探测不到局域网地址:测试") }
	defer func() { lanIP = func() (net.IP, error) { return loopback, nil } }()
	s := reopen(t)
	if s["running"] != true || s["ip_error"] == "" || s["url"] != "" {
		t.Fatalf("拿不到 IP 时应 running=true + ip_error + url 为空,实际 %v", s)
	}
	port := s["port"].(int)
	st.mu.Lock()
	tok := st.token
	st.mu.Unlock()
	u := "http://" + net.JoinHostPort(loopback.String(), strconv.Itoa(port)) + "/c/" + tok
	if code, _ := do(t, "GET", u, ""); code != 200 {
		t.Fatalf("状态说在跑,但端口上打不开: %d", code)
	}
}

func Test播放器命令并入当前会话(t *testing.T) {
	s := reopen(t)
	do(t, "POST", s["url"].(string)+"/api/player.seek", `{"pos":10}`)
	a, ok := called("player.seek")
	if !ok || a["token"] != "tok" || a["user_id"] != "u1" || a["device_id"] == "" || a["pos"] != 10.0 {
		t.Fatalf("会话四件套没并进去: %v", a)
	}
}

// 页面调用的接口必须和白名单一一对上:多了是手机上点了被拒,少了是白名单开得比用得宽。
func Test页面接口与白名单一致(t *testing.T) {
	used := map[string]bool{}
	for _, m := range regexp.MustCompile(`api\("([a-zA-Z.]+)"`).FindAllStringSubmatch(string(page), -1) {
		used[m[1]] = true
	}
	for name := range used {
		if name != "key" && name != "open" && name != "state" && !allowed[name] {
			t.Errorf("页面调了白名单外的 %s", name)
		}
	}
	for name := range allowed {
		if !used[name] {
			t.Errorf("白名单里的 %s 页面没用到", name)
		}
	}
}
