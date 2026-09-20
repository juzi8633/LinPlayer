package plugin

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

/*
这一层测的是**壳看得见的那条线**:发 plugin.ui.mount → 收到 plugin.ui 事件 →
把 {$fn} 号发回 plugin.ui.event → 插件的闭包被调到。
渲染器内部怎么 diff 是 rt 那边的事,这里只认命令与事件。
*/

type evCapture struct {
	mu     sync.Mutex
	frames []map[string]any
	states []map[string]any
	// others 别的事件按名字存原文(壁纸切换这类只发一条 JSON 的)
	others map[string][]string
	stop   func()
}

// events 这个名字的事件收到过哪些(原始 JSON)。
func (c *evCapture) events(name string) []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.others[name]...)
}

// captureUI 起一个事件消费者。事件走的是真队列(bus.NextEvent),
// 所以这里测到的就是壳那边收到的东西 —— 不是另外搭一条旁路。
func captureUI(t *testing.T) *evCapture {
	t.Helper()
	bus.Init()
	c := &evCapture{}
	done := make(chan struct{})
	c.stop = func() { close(done) }
	go func() {
		for {
			select {
			case <-done:
				return
			default:
			}
			raw := bus.NextEvent(20)
			if raw == nil {
				continue
			}
			var e struct {
				Name string          `json:"name"`
				Data json.RawMessage `json:"data"`
			}
			if json.Unmarshal(raw, &e) != nil {
				continue
			}
			var m map[string]any
			if json.Unmarshal(e.Data, &m) != nil {
				continue
			}
			c.mu.Lock()
			switch e.Name {
			case "plugin.ui":
				c.frames = append(c.frames, m)
			case "plugin.ui.surface":
				c.states = append(c.states, m)
			default:
				if c.others == nil {
					c.others = map[string][]string{}
				}
				c.others[e.Name] = append(c.others[e.Name], string(e.Data))
			}
			c.mu.Unlock()
		}
	}()
	t.Cleanup(c.stop)
	return c
}

func (c *evCapture) ops() []map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []map[string]any
	for _, f := range c.frames {
		for _, o := range f["ops"].([]any) {
			if m, ok := o.(map[string]any); ok {
				out = append(out, m)
			}
		}
	}
	return out
}

func (c *evCapture) wait(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if ok() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	t.Fatalf("等不到 %s;收到 %d 帧、%d 条状态", what, len(c.frames), len(c.states))
}

/* mountUI 照真实壳的做法挂一块 UI:首帧从 mount 的**返回值**吃进来,
   后续帧才走事件。测试里也这么做,是为了让这两条路都被走到。 */
func mountUI(t *testing.T, c *evCapture, args map[string]any) string {
	t.Helper()
	r := call(t, "plugin.ui.mount", args)
	sid, _ := r["surface"].(string)
	if ops, ok := r["ops"].([]any); ok && len(ops) > 0 {
		c.mu.Lock()
		c.frames = append(c.frames, map[string]any{"surface": sid, "frame": 1.0, "ops": ops})
		c.mu.Unlock()
	}
	return sid
}

func call(t *testing.T, name string, args map[string]any) map[string]any {
	t.Helper()
	// 参数过一遍 JSON:真实调用来自壳的 JSON,数字都是 float64
	raw, _ := json.Marshal(args)
	var viaJSON map[string]any
	_ = json.Unmarshal(raw, &viaJSON)
	out, err := bus.Invoke(context.Background(), name, viaJSON)
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	b, _ := json.Marshal(out)
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return m
}

const uiPlugin = `definePlugin({ pages: { hello: (p) => {
	const { h } = ` + `__linplayer_sdk;
	globalThis.__clicks = 0;
	return h('Column', null,
		h('Text', null, '你好 ' + (p.who || '')),
		h('Button', {title: '点我', onPress: () => { globalThis.__clicks++; }}));
} } })`

func TestUI挂载走命令总线(t *testing.T) {
	h := installAndRestart(t, uiPlugin)
	registerUI()
	c := captureUI(t)

	sid := mountUI(t, c, map[string]any{
		"plugin": "alice/demo", "target": "hello", "kind": "page",
		"props": map[string]any{"who": "世界"},
	})
	if sid == "" {
		t.Fatal("mount 没回 surface id")
	}
	c.wait(t, "首帧", func() bool { return len(c.ops()) > 0 })

	// 骨架屏 → 就绪(D271):两条状态都要有,顺序也不能反
	c.wait(t, "ready 状态", func() bool {
		c.mu.Lock()
		defer c.mu.Unlock()
		var seenLoading bool
		for _, s := range c.states {
			if s["surface"] != sid {
				continue
			}
			if s["state"] == "loading" {
				seenLoading = true
			}
			if s["state"] == "ready" {
				return seenLoading
			}
		}
		return false
	})

	ops := c.ops()
	var types []string
	for _, o := range ops {
		if o["op"] == "create" {
			types = append(types, o["type"].(string))
		}
	}
	if !has(types, "Column") || !has(types, "Button") {
		t.Fatalf("首帧没建出组件,建出来的是 %v", types)
	}
	// props 里的 who 要真传进去
	var texts []string
	for _, o := range ops {
		if o["op"] == "text" {
			texts = append(texts, o["value"].(string))
		}
	}
	if !strings.Contains(strings.Join(texts, "|"), "世界") {
		t.Errorf("mount 的 props 没传进组件,文本是 %v", texts)
	}

	// 回传一次点击
	fn := findFn(t, ops, "onPress")
	call(t, "plugin.ui.event", map[string]any{"surface": sid, "fn": fn, "args": []any{}})

	l, err := h.get("alice/demo", "test")
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		v, err := l.rt.Eval(context.Background(), time.Second, "x.js", `String(globalThis.__clicks)`)
		if err == nil && strings.Contains(string(v), "1") {
			goto clicked
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("点击没到插件")
clicked:

	// 卸载之后账本要清干净,否则每开一次插件页就漏一个 surface
	call(t, "plugin.ui.unmount", map[string]any{"surface": sid})
	if n := UISurfaceCount(); n != 0 {
		t.Errorf("卸载后还挂着 %d 个 surface", n)
	}
	// 重复卸载是常态(壳的销毁路径可能走两次),不许报错
	call(t, "plugin.ui.unmount", map[string]any{"surface": sid})
}

/*
一帧一条(D318)有**两层**合批,要分开测,不然容易误以为测到了:

  · JS 那层:一次交互里的多次 setState 并成一次**提交** —— 在 rt 包里测
    (`rt/ui_test.go` 的「一次交互只提交一次」),那里数的是渲染器调 OnFrame 的次数。
  · Go 这层:16ms 内的多次提交并成一条**事件** —— 下面这条。

☠ 原来这里有一条叫「JS 层合批」的用例,数的却是 Go 发出的事件 ——
  把 JS 那层删掉照样全绿,因为 Go 这层会把它们重新并成一条。两层的判据不能共用一个计数器。
*/

// Go 这层的 16ms 窗口:**两次独立提交**落在同一个窗口里,也只能出一条事件。
func TestUI十六毫秒窗口内的多次提交合成一条(t *testing.T) {
	h := installAndRestart(t, `definePlugin({ pages: { p: () => {
		const { h, useState } = `+`__linplayer_sdk;
		const [n, setN] = useState(0);
		globalThis.__inc = () => setN(v => v + 1);
		return h('Text', null, '计数 ' + n);
	} } })`)
	registerUI()
	c := captureUI(t)

	mountUI(t, c, map[string]any{"plugin": "alice/demo", "target": "p", "kind": "page"})
	c.wait(t, "首帧", func() bool { return len(c.ops()) > 0 })
	time.Sleep(40 * time.Millisecond) // 等首帧那个窗口关掉
	c.mu.Lock()
	before := len(c.frames)
	c.mu.Unlock()

	// 两次**独立**的交互,间隔远小于 16ms:JS 那层会各提交一次,Go 这层必须并成一条
	l, _ := h.get("alice/demo", "test")
	for i := 0; i < 2; i++ {
		if _, err := l.rt.Eval(context.Background(), 2*time.Second, "x.js", `globalThis.__inc()`); err != nil {
			t.Fatal(err)
		}
	}
	c.wait(t, "更新帧", func() bool {
		c.mu.Lock()
		defer c.mu.Unlock()
		return len(c.frames) > before
	})
	time.Sleep(80 * time.Millisecond)

	c.mu.Lock()
	added := len(c.frames) - before
	c.mu.Unlock()
	if added != 1 {
		t.Errorf("16ms 内两次提交发了 %d 条事件 —— 合批没生效,壳会一帧重排两次", added)
	}
}

func has(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

func findFn(t *testing.T, ops []map[string]any, name string) int {
	t.Helper()
	for _, o := range ops {
		set, ok := o["set"].(map[string]any)
		if !ok {
			continue
		}
		if v, ok := set[name].(map[string]any); ok {
			if n, ok := v["$fn"].(float64); ok {
				return int(n)
			}
		}
	}
	t.Fatalf("没找到 %s 的回调号", name)
	return 0
}

/*
官方调试面板是渲染器的**第一个真实用户**(D543):它用到组件、样式、事件、
条件分支、列表 key、hooks 副作用。这条测试拿仓库里那份真插件渲染一遍 ——
比再写一个「渲染器自测页」有用,自测页只会用到写它时想得起来的那几条路径。
*/
func TestUI调试面板能渲染出来(t *testing.T) {
	root := repoRoot(t)
	dir := filepath.Join(root, "plugins", "devtools")
	if _, err := os.Stat(dir); err != nil {
		t.Fatal("仓库里没有 plugins/devtools —— 它是首发官方插件,不在就是真出事了")
	}
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	// 面板的四块靠 `debug` 命名空间,而它跟着开发者模式开关(D555)——
	// 不开的话这一页只画一句「去打开开发者模式」,测不到渲染器
	devMode(t, true)
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	// 用 dev 目录加载:不必先打包再装,改插件源码这条测试立刻跟着变
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatalf("加载调试面板失败: %v", err)
	}
	registerUI()
	c := captureUI(t)

	sid := mountUI(t, c, map[string]any{
		"plugin": "linplayer/devtools", "target": "panel", "kind": "page",
	})
	c.wait(t, "首帧", func() bool { return len(c.ops()) > 20 })

	ops := c.ops()
	seen := map[string]bool{}
	for _, o := range ops {
		if o["op"] == "create" {
			seen[o["type"].(string)] = true
		}
	}
	for _, want := range []string{"Column", "Text", "Chip", "ChipGroup", "Button", "View"} {
		if !seen[want] {
			t.Errorf("调试面板没渲染出 %s;建出来的是 %v", want, keysOf(seen))
		}
	}
	// 样式要作为 style 属性整体传下去,不是拆成一堆散属性
	if !hasStyleProp(ops) {
		t.Error("一条 style 属性都没有 —— 样式没传到壳那边,界面会是裸的")
	}
	// 切到**另一个**标签页,内容要换。
	// ☠ 点第一个标签是点不出变化的 —— 那是当前选中的那个,状态没变就不该重渲染。
	fns := allFns(ops, "onPress")
	if len(fns) < 2 {
		t.Fatalf("只找到 %d 个 onPress,标签页没渲染出来", len(fns))
	}
	before := len(ops)
	call(t, "plugin.ui.event", map[string]any{"surface": sid, "fn": fns[1], "args": []any{}})
	c.wait(t, "切标签后的更新帧", func() bool { return len(c.ops()) > before })
}

func allFns(ops []map[string]any, name string) []int {
	var out []int
	for _, o := range ops {
		set, ok := o["set"].(map[string]any)
		if !ok {
			continue
		}
		if v, ok := set[name].(map[string]any); ok {
			if n, ok := v["$fn"].(float64); ok {
				out = append(out, int(n))
			}
		}
	}
	return out
}

func hasStyleProp(ops []map[string]any) bool {
	for _, o := range ops {
		if set, ok := o["set"].(map[string]any); ok {
			if _, ok := set["style"]; ok {
				return true
			}
		}
	}
	return false
}

func keysOf(m map[string]bool) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// repoRoot 从测试所在包往上找到仓库根(有 go.mod 的那一层的父目录)。
func repoRoot(t *testing.T) string {
	t.Helper()
	d, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 6; i++ {
		if _, err := os.Stat(filepath.Join(d, "go.mod")); err == nil {
			return filepath.Dir(d)
		}
		d = filepath.Dir(d)
	}
	t.Skip("找不到仓库根")
	return ""
}

/*
首帧必须跟着 mount 的返回值走(不是事件)。

☠ 这条挡的是一个只在慢一点的壳上才现形的竞态:首渲染是 mount 里**同步**做完的,
而壳要拿到 surface id 之后才可能订阅这个 id 的帧 —— 中间那一段发出去的帧没人接。
桌面上侥幸没事(Go 这边压了 16ms 才发),安卓模拟器上重组慢一点就永远停在骨架屏:
不报错、不崩、就是不出内容。2026-09-20 截图才看出来。
*/
func TestUI首帧跟着mount的返回值(t *testing.T) {
	h := installAndRestart(t, uiPlugin)
	registerUI()
	c := captureUI(t)
	// **故意不从返回值之外拿**:首帧要是走事件,这条就拿不到任何 op
	r := call(t, "plugin.ui.mount", map[string]any{
		"plugin": "alice/demo", "target": "hello", "kind": "page",
		"props": map[string]any{"who": "世界"},
	})
	_ = h
	ops, _ := r["ops"].([]any)
	if len(ops) < 5 {
		t.Fatalf("mount 只回了 %d 条 op —— 首帧没跟着返回值走", len(ops))
	}
	/* 首帧不许**再**以事件的形式发一遍(发了就是重复应用)。
	   ☠ 另半条 —— 「定时器先于 mount 返回把首帧发走」—— 这台机器上复现不出来:
	   onFrame 是 UIMount 里同步调的,take() 紧跟其后,AfterFunc 的 goroutine
	   抢不到那个窗口。它的证据是模拟器:没有 surface.started 那道闸时,
	   安卓端 mount 回的 ops 是 **0 条**,界面永远白屏(2026-09-20 实测日志)。
	   所以那道闸按「已知会在慢设备上发生」保留,不假装这里能测到它。 */
	time.Sleep(60 * time.Millisecond)
	c.mu.Lock()
	n := len(c.frames)
	c.mu.Unlock()
	if n != 0 {
		t.Errorf("首帧之外还发了 %d 条 plugin.ui 事件 —— 闸没生效", n)
	}
	var types []string
	for _, o := range ops {
		if m, ok := o.(map[string]any); ok && m["op"] == "create" {
			types = append(types, m["type"].(string))
		}
	}
	if !has(types, "Column") || !has(types, "Button") {
		t.Fatalf("mount 回的 ops 不是首帧,建出来的是 %v", types)
	}
}

/*
视口 / 断点 / 安全区(SPEC 7.7,D217 D425 D426)。

☠ 这条挡的是「插件按安全区让开了,但那个值永远是 0」——
界面在有刘海、有手势条、有电视过扫描边的设备上被切掉一圈,
而开发机上一切正常,编译也绿。

判据有三条,少一条这件事就是半残:
  · 壳报来的视口能到插件手里(useViewport 拿得到)
  · 变了要**重渲染**,不是只改一个读不到的值
  · 没变**不许**重渲染(尺寸变化一秒几十条,每条都重画等于自己做 DDoS)
*/
func TestUI视口与安全区送得到插件手里(t *testing.T) {
	h := installAndRestart(t, `definePlugin({ pages: { p: () => {
		const { h, useViewport } = `+`__linplayer_sdk;
		const v = useViewport();
		globalThis.__renders = (globalThis.__renders || 0) + 1;
		return h('Text', null, v.breakpoint + ' ' + v.width + 'x' + v.height + ' 底' + v.insets.bottom);
	} } })`)
	registerUI()
	c := captureUI(t)
	sid := mountUI(t, c, map[string]any{"plugin": "alice/demo", "target": "p", "kind": "page"})
	c.wait(t, "首帧", func() bool { return len(c.ops()) > 0 })

	call(t, "plugin.ui.viewport", map[string]any{
		"surface": sid, "width": 480, "height": 900,
		"breakpoint": "compact", "formFactor": "phone",
		"insets": map[string]any{"top": 44, "right": 0, "bottom": 34, "left": 0},
	})
	c.wait(t, "视口变化后的重渲染", func() bool {
		for _, o := range c.ops() {
			if o["op"] == "text" && strings.Contains(o["value"].(string), "compact 480x900 底34") {
				return true
			}
		}
		return false
	})

	// 再报一条**一模一样**的:不许再重渲染
	l, _ := h.get("alice/demo", "test")
	before := renderCount(t, l.rt)
	call(t, "plugin.ui.viewport", map[string]any{
		"surface": sid, "width": 480, "height": 900,
		"breakpoint": "compact", "formFactor": "phone",
		"insets": map[string]any{"top": 44, "right": 0, "bottom": 34, "left": 0},
	})
	time.Sleep(80 * time.Millisecond)
	if after := renderCount(t, l.rt); after != before {
		t.Errorf("视口没变却重渲染了(%d → %d)—— 尺寸变化一秒几十条,这样会把自己拖垮", before, after)
	}
}

func renderCount(t *testing.T, r *rt.Runtime) int {
	t.Helper()
	v, err := r.Eval(context.Background(), 2*time.Second, "x.js", `String(globalThis.__renders || 0)`)
	if err != nil {
		t.Fatal(err)
	}
	var s string
	_ = json.Unmarshal(v, &s)
	n, _ := strconv.Atoi(s)
	return n
}
