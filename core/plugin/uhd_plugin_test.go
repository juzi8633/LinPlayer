package plugin

// UHD 助手插件的验收:流量 / 求片 / 测速三块各走一遍完整调用链。
//
// ☠ 判据钉的是**画出来的东西**和**发出去的请求体**,不是「函数没抛」:
// 这三块在真机上的失败长得都一样 —— 页面空着,或者点了没反应。
//
// 假站的形状照真站实测(core/internal/fakeuhd):裸 token 鉴权、
// 线路 domain 带空格、说明必填、下载大小必须等于会话大小。

import (
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/internal/fakeuhd"
	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

type uhdEnv struct {
	fu *fakeuhd.Server
	h  *Host
	l  *loaded
	c  *evCapture
}

func newUhdEnv(t *testing.T, settings map[string]string) *uhdEnv {
	t.Helper()
	fu := fakeuhd.New()
	srv := httptest.NewServer(fu.Handler())
	fu.Base = srv.URL
	t.Cleanup(srv.Close)

	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	/* 假站起在回环上,而这个插件**故意不声明 lan**(它连的是公网站点,
	   不该有翻本机内网的本事)。宿主本来就有一条例外:「宿主自己的本地服务不算局域网」,
	   测试里把假站的端口当成那个本地服务 —— 用已有的口子,不为了跑测试去放宽插件的权限。 */
	port := 0
	if i := strings.LastIndex(srv.URL, ":"); i > 0 {
		port, _ = strconv.Atoi(srv.URL[i+1:])
	}
	h.SourceHost = func(string) rt.Host { return rt.Host{HostPort: func() int { return port }} }
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	registerUI()
	if _, err := h.DevLoad(filepath.Join(repoRoot(t), "plugins", "uhd")); err != nil {
		t.Fatalf("加载 UHD 插件失败: %v", err)
	}
	if settings == nil {
		settings = map[string]string{"site": srv.URL, "username": "阿甲", "password": "pw"}
	} else if settings["site"] == "$base" {
		settings["site"] = srv.URL
	}
	for k, v := range settings {
		if err := h.SetSetting("linplayer/uhd", k, v); err != nil {
			t.Fatalf("设 %s 失败: %v", k, err)
		}
	}
	l, err := h.get("linplayer/uhd", "test")
	if err != nil {
		t.Fatalf("起不来: %v", err)
	}
	return &uhdEnv{fu: fu, h: h, l: l, c: captureUI(t)}
}

/*
answerConfirm 扮演壳回答 `ui.confirm`。

☠ 不扮的话 `ui.confirm` 一直等到 60 秒超时,测试里看到的是「提交没发出去」——
和「代码写错了」长得一模一样。提交不可撤回,确认框该留着,所以这里补一个壳。

★ **不能另起一个 bus 消费者**:`captureUI` 已经在读同一条队列,两个消费者会互相抢事件,

	表现是 UI 帧时有时无。所以从 captureUI 已经收下的 `plugin.shellRequest` 里取。
*/
func answerConfirm(t *testing.T, c *evCapture, answer bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		for _, raw := range c.events("plugin.shellRequest") {
			var d struct {
				ID int64  `json:"id"`
				Op string `json:"op"`
			}
			if json.Unmarshal([]byte(raw), &d) == nil && d.Op == "ui.confirm" {
				rt.ShellResult(d.ID, true, json.RawMessage(strconv.FormatBool(answer)), nil)
				return
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("等不到 ui.confirm —— 提交没走到确认那一步")
}

// waitText 等页面上出现某段文字(真 HTTP 往返,要等)。
func (e *uhdEnv) waitText(t *testing.T, want, what string) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		for _, o := range e.c.ops() {
			if s, _ := o["value"].(string); strings.Contains(s, want) {
				return
			}
			if set, ok := o["set"].(map[string]any); ok {
				for _, v := range set {
					if s, _ := v.(string); strings.Contains(s, want) {
						return
					}
				}
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	var got []string
	for _, o := range e.c.ops() {
		if s, _ := o["value"].(string); s != "" {
			got = append(got, s)
		}
		if set, ok := o["set"].(map[string]any); ok {
			for k, v := range set {
				if s, _ := v.(string); s != "" {
					got = append(got, k+"="+s)
				}
			}
		}
	}
	t.Fatalf("%s:页面上等不到「%s」\n画出来的:%v\n插件日志:%v", what, want, got, logMsgs(e.l))
}

/*
tap 点一个元素:先按文本找,再按 `title` / `label` 属性找 —— Button 与 Chip 的字
是**属性**不是子节点,只按文本找的话这两种永远点不到(live_plugin_test 的 press
只管文本,那边点的是 Text)。找到之后顺着父链往上找最近的 onPress。
*/
func tap(t *testing.T, c *evCapture, sid, want string) {
	t.Helper()
	var target float64
	parent := map[float64]float64{}
	onPress := map[float64]int{}
	for _, o := range c.ops() {
		id, _ := o["id"].(float64)
		switch o["op"] {
		case "text":
			if s, _ := o["value"].(string); strings.Contains(s, want) {
				target = id
			}
		case "insert":
			if p, ok := o["parent"].(float64); ok {
				parent[id] = p
			}
		case "props":
			set, ok := o["set"].(map[string]any)
			if !ok {
				break
			}
			for _, k := range []string{"title", "label"} {
				if s, _ := set[k].(string); s != "" && strings.Contains(s, want) {
					target = id
				}
			}
			if v, ok := set["onPress"].(map[string]any); ok {
				if n, ok := v["$fn"].(float64); ok {
					onPress[id] = int(n)
				}
			}
		}
	}
	for n, hops := target, 0; hops < 8; hops++ {
		if fn, ok := onPress[n]; ok {
			call(t, "plugin.ui.event", map[string]any{"surface": sid, "fn": fn, "args": []any{}})
			return
		}
		p, ok := parent[n]
		if !ok {
			break
		}
		n = p
	}
	t.Fatalf("点不到「%s」—— 它要么没画出来,要么没有 onPress", want)
}

// typeInto 往 placeholder 命中的输入框里打字(触发它的 onChangeText)。
func typeInto(t *testing.T, c *evCapture, sid, placeholder, text string) {
	t.Helper()
	var target float64
	fns := map[float64]int{}
	for _, o := range c.ops() {
		id, _ := o["id"].(float64)
		set, ok := o["set"].(map[string]any)
		if !ok {
			continue
		}
		if s, _ := set["placeholder"].(string); strings.Contains(s, placeholder) {
			target = id
		}
		if v, ok := set["onChangeText"].(map[string]any); ok {
			if n, ok := v["$fn"].(float64); ok {
				fns[id] = int(n)
			}
		}
	}
	fn, ok := fns[target]
	if !ok {
		t.Fatalf("找不到 placeholder 含「%s」的输入框(或它没有 onChangeText)", placeholder)
	}
	call(t, "plugin.ui.event", map[string]any{"surface": sid, "fn": fn, "args": []any{text}})
}

func TestUhdPlugin流量(t *testing.T) {
	e := newUhdEnv(t, nil)
	sid := mountUI(t, e.c, map[string]any{"plugin": "linplayer/uhd", "target": "uhd", "kind": "page"})
	if sid == "" {
		t.Fatal("页面没挂上")
	}
	// 100 GiB 里用了 10 GiB
	e.waitText(t, "剩余 90 GB", "流量")
	// 文本是分片发的:「已用 」「10 GB」「 / 共 」「100 GB」各一条 op
	e.waitText(t, "10 GB", "已用")
	e.waitText(t, "100 GB", "总量")
	e.waitText(t, "阿甲", "登录用户名")
}

func TestUhdPlugin没填站点时说人话(t *testing.T) {
	// ☠ 不填站点就去请求一个空地址的话,用户看到的是「网络错误」——
	//   而真正该说的是「你还没填站点地址」。
	e := newUhdEnv(t, map[string]string{"site": "", "username": "", "password": ""})
	sid := mountUI(t, e.c, map[string]any{"plugin": "linplayer/uhd", "target": "uhd", "kind": "page"})
	if sid == "" {
		t.Fatal("页面没挂上")
	}
	e.waitText(t, "先填站点和账号", "未配置时的引导")
}

func TestUhdPlugin求片(t *testing.T) {
	e := newUhdEnv(t, nil)
	rt.SetShellCaps(rt.ShellCaps{Shell: true})
	t.Cleanup(func() { rt.SetShellCaps(rt.ShellCaps{}) })
	sid := mountUI(t, e.c, map[string]any{"plugin": "linplayer/uhd", "target": "uhd", "kind": "page"})
	e.waitText(t, "剩余 90 GB", "流量")

	// 只填片名、不填说明就搜 —— 搜索能出结果,但提交时要拦住(说明必填)
	typeInto(t, e.c, sid, "片名", "测试片")
	tap(t, e.c, sid, "搜索")
	e.waitText(t, "测试片(电影)", "搜索结果")
	e.waitText(t, "已在库", "库内标记")

	// 点一条提交:没写说明 → 页面上要说清楚是哪一格没填
	tap(t, e.c, sid, "测试片(电影)")
	e.waitText(t, "「说明」是必填的", "空说明的拦截")
	if n := len(e.fu.Created); n != 0 {
		t.Fatalf("说明没填就不该发出提交,站点却收到了 %d 条", n)
	}

	// 补上说明再提交
	typeInto(t, e.c, sid, "说明", "要 4K 原盘")
	tap(t, e.c, sid, "测试片(电影)")
	answerConfirm(t, e.c, true)
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) && len(e.fu.Created) == 0 {
		time.Sleep(50 * time.Millisecond)
	}
	if len(e.fu.Created) != 1 {
		t.Fatalf("应发出 1 条提交,实得 %d 条\n插件日志:%v", len(e.fu.Created), logMsgs(e.l))
	}
	got := e.fu.Created[0]
	if got["request_type"] != "missing" || got["media_type"] != "movie" || got["content"] != "要 4K 原盘" {
		t.Fatalf("提交的字段不对: %+v", got)
	}
	if n, _ := got["tmdb_id"].(float64); int(n) != 101 {
		t.Fatalf("tmdb_id 应是搜索结果那一条的 101,实得 %v", got["tmdb_id"])
	}
}

func TestUhdPlugin测速(t *testing.T) {
	e := newUhdEnv(t, nil)
	sid := mountUI(t, e.c, map[string]any{"plugin": "linplayer/uhd", "target": "uhd", "kind": "page"})
	e.waitText(t, "剩余 90 GB", "流量")

	tap(t, e.c, sid, "测速") // 切到测速那一栏
	e.waitText(t, "国际方向", "线路表")
	// ★ 线路名要显示,**线路地址不显示**(用户要求不暴露 domain)
	for _, o := range e.c.ops() {
		if set, ok := o["set"].(map[string]any); ok {
			for _, v := range set {
				if s, _ := v.(string); strings.Contains(s, "127.0.0.1") {
					t.Fatalf("线路地址不该画到界面上: %q", s)
				}
			}
		}
	}

	tap(t, e.c, sid, "测速") // 第一条线路的那个按钮
	// ☠ 判据要带**下到多少**:`download?size_mb=` 和会话的 size_mib 对不上时,
	//   服务端只回几十字节,而插件照样能算出一个 Mbps 数 —— 只断言「有 Mbps」
	//   的话那种坏法完全测不出来(假站按真站的行为回 39 字节)。
	e.waitText(t, "1 MiB", "测速结果里下到的量")
	e.waitText(t, "Mbps", "测速结果")
	if len(e.fu.Sessions) == 0 {
		t.Fatal("没建过测速会话 —— 那这个数是编的")
	}
}
