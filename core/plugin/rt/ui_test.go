package rt

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
)

/*
最小 DOM 跟着 Preact 的内部实现走(见 uiruntime.js 顶部那条):它哪天换一个 DOM 接口,
症状是「某些节点不更新」而不是报错。这一组用例就是为那一天存在的 —— 每一条都盯住
一种**具体的变更形状**:建树、改属性、改文本、删子树、移动节点、回调号。
*/

type uiCapture struct {
	mu     sync.Mutex
	frames []UIFrame
	states []UIState
}

func (c *uiCapture) sink() UISink {
	return UISink{
		OnFrame: func(f UIFrame) { c.mu.Lock(); c.frames = append(c.frames, f); c.mu.Unlock() },
		OnState: func(s UIState) { c.mu.Lock(); c.states = append(c.states, s); c.mu.Unlock() },
	}
}

// ops 把收到的所有帧摊平成 []map,按到达顺序。
func (c *uiCapture) ops(t *testing.T) []map[string]any {
	t.Helper()
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []map[string]any
	for _, f := range c.frames {
		for _, raw := range f.Ops {
			var m map[string]any
			if err := json.Unmarshal(raw, &m); err != nil {
				t.Fatalf("op 不是对象: %s", raw)
			}
			out = append(out, m)
		}
	}
	return out
}

func (c *uiCapture) wait(t *testing.T, want func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if want() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("等不到期望的变更;已收到 %d 帧:%s", len(c.frames), c.dump())
}

func (c *uiCapture) dump() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var b strings.Builder
	for _, f := range c.frames {
		for _, o := range f.Ops {
			b.Write(o)
			b.WriteByte('\n')
		}
	}
	return b.String()
}

// newUI 起一个带 UI 的运行时;code 里用 h / useState,就像插件 JSX 编译出来的那样。
func newUI(t *testing.T, code string) (*Runtime, *uiCapture) {
	t.Helper()
	cap := &uiCapture{}
	r := newRT(t, code)
	r.SetUISink(cap.sink())
	return r, cap
}

// 首帧:从根开始的一串 create / props / insert(SPEC 7.3)。
func TestUI首帧建树(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		definePlugin({ pages: { p: () => h('Column', {gap: 10},
			h('Text', null, '你好'),
			h('Button', {title: '点我'})) } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return len(cap.ops(t)) >= 6 })

	ops := cap.ops(t)
	var created []string
	for _, o := range ops {
		if o["op"] == "create" {
			created = append(created, o["type"].(string))
		}
	}
	for _, want := range []string{"Column", "Text", "Button"} {
		if !containsStr(created, want) {
			t.Errorf("没建出 %s,建出来的是 %v", want, created)
		}
	}
	if !hasOp(ops, "root") {
		t.Error("没有 root op —— 壳不知道 insert 的 parent 0 指的是哪个容器")
	}
	if !hasOp(ops, "text") {
		t.Error("文本节点没发 text op")
	}
	// gap:10 是 props 不是 style(SPEC 里 gap 在 style 子集里,但这里是直接写在 props 上的)
	if !hasProp(ops, "gap") && !hasProp(ops, "style") {
		t.Errorf("gap 既没进 props 也没进 style:\n%s", cap.dump())
	}
	if !hasProp(ops, "title") {
		t.Errorf("title 没发出去:\n%s", cap.dump())
	}
}

// 函数属性必须变成回调号,而且壳回传这个号要真能调到闭包(SPEC 7.3)。
func TestUI回调号能往返(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		globalThis.__hits = [];
		definePlugin({ pages: { p: () => h('Button', {
			title: '点我',
			onPress: (a) => { globalThis.__hits.push(a); },
		}) } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "onPress") })

	fn := fnRefOf(t, cap.ops(t), "onPress")
	if err := r.UIEvent("s1", fn, []any{"click!"}); err != nil {
		t.Fatal(err)
	}
	// 回调是投进循环执行的,给它一拍
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		v, err := r.Eval(t.Context(), BudgetData, "x.js", `JSON.stringify(globalThis.__hits)`)
		if err == nil {
			var s string
			_ = json.Unmarshal(v, &s)
			if strings.Contains(s, "click!") {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("回调号回传了,但闭包没被调到")
}

// setState 之后要发出**增量**:改一个字不许把整棵树重建一遍。
func TestUI重渲染只发增量(t *testing.T) {
	r, cap := newUI(t, `
		const { h, useState } = __linplayer_sdk;
		definePlugin({ pages: { p: () => {
			const [n, setN] = useState(0);
			globalThis.__bump = () => setN(v => v + 1);
			return h('Text', null, '计数 ' + n);
		} } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasOp(cap.ops(t), "text") })
	before := len(cap.ops(t))

	if _, err := r.Eval(t.Context(), BudgetData, "x.js", `globalThis.__bump()`); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return len(cap.ops(t)) > before })

	after := cap.ops(t)[before:]
	for _, o := range after {
		if o["op"] == "create" {
			t.Errorf("只改了文字却重建了节点(%v);整棵树重建在 1000 项列表上就是掉帧", o)
		}
	}
	var last string
	for _, o := range after {
		if o["op"] == "text" {
			last, _ = o["value"].(string)
		}
	}
	if last != "计数 1" {
		t.Errorf("文本没更新到「计数 1」,收到的是 %q", last)
	}
}

// 删一个分支要发 remove(壳照着删子树),而且不许连带重建兄弟节点。
func TestUI条件渲染删子树(t *testing.T) {
	r, cap := newUI(t, `
		const { h, useState } = __linplayer_sdk;
		definePlugin({ pages: { p: () => {
			const [on, setOn] = useState(true);
			globalThis.__toggle = () => setOn(v => !v);
			return h('Column', null,
				h('Text', null, '常在'),
				on ? h('Text', null, '会消失') : null);
		} } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return len(cap.ops(t)) >= 5 })
	before := len(cap.ops(t))

	if _, err := r.Eval(t.Context(), BudgetData, "x.js", `globalThis.__toggle()`); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasOp(cap.ops(t)[before:], "remove") })

	for _, o := range cap.ops(t)[before:] {
		if o["op"] == "create" {
			t.Errorf("删一个分支却重建了节点 %v", o)
		}
	}
}

// 错误边界(D136):组件渲染期抛错,surface 进 error 状态,不把运行时带走。
func TestUI错误边界只崩那一块(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		definePlugin({ pages: { bad: () => { throw new Error('故意炸'); } } })
	`)
	if err := r.UIMount("s1", "page", "bad", nil); err != nil {
		t.Fatalf("挂载本身不该失败(错误要变成 surface 状态): %v", err)
	}
	cap.wait(t, func() bool {
		cap.mu.Lock()
		defer cap.mu.Unlock()
		for _, s := range cap.states {
			if s.State == "error" && strings.Contains(s.Message, "故意炸") {
				return true
			}
		}
		return false
	})
	// 运行时还活着:同一个 VM 还能跑别的东西
	if _, err := r.Eval(t.Context(), BudgetData, "x.js", `1 + 1`); err != nil {
		t.Fatalf("一块 UI 崩了把运行时也带走了: %v", err)
	}
}

// 卸载之后,壳上迟到的一次点击不许再打到已经没了的闭包上。
func TestUI卸载后回调号作废(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		globalThis.__hits = 0;
		definePlugin({ pages: { p: () => h('Button', {onPress: () => { globalThis.__hits++; }}) } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "onPress") })
	fn := fnRefOf(t, cap.ops(t), "onPress")

	if err := r.UIUnmount("s1"); err != nil {
		t.Fatal(err)
	}
	if err := r.UIEvent("s1", fn, nil); err != nil {
		t.Fatalf("对已卸载的 surface 发事件应该**静默丢掉**,不该报错: %v", err)
	}
	v, err := r.Eval(t.Context(), BudgetData, "x.js", `String(globalThis.__hits)`)
	if err != nil {
		t.Fatal(err)
	}
	var got string
	_ = json.Unmarshal(v, &got)
	if got != "0" {
		t.Fatalf("卸载后回调仍被调到 %s 次", got)
	}
}

// ---------------------------------------------------------------- 助手

func containsStr(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

func hasOp(ops []map[string]any, op string) bool {
	for _, o := range ops {
		if o["op"] == op {
			return true
		}
	}
	return false
}

func hasProp(ops []map[string]any, name string) bool {
	for _, o := range ops {
		if o["op"] != "props" {
			continue
		}
		if set, ok := o["set"].(map[string]any); ok {
			if _, ok := set[name]; ok {
				return true
			}
		}
	}
	return false
}

func fnRefOf(t *testing.T, ops []map[string]any, name string) int {
	t.Helper()
	for _, o := range ops {
		if o["op"] != "props" {
			continue
		}
		set, ok := o["set"].(map[string]any)
		if !ok {
			continue
		}
		v, ok := set[name].(map[string]any)
		if !ok {
			continue
		}
		if n, ok := v["$fn"].(float64); ok {
			return int(n)
		}
	}
	t.Fatalf("属性 %s 没有序列化成 {$fn:n}", name)
	return 0
}

// D53:UI 回调预算 1 秒。
//
// ☠ 这条挡的是一个真漏洞:回调原来是裸 post 进循环的,**一点预算都没有** ——
// 插件在 onPress 里写个死循环就能把整个事件循环占住,看门狗看不见,
// 表现是「点了那个按钮之后整个插件再也没反应」,而且不报错。
func TestUI回调死循环会被预算打断(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		definePlugin({ pages: { p: () => h('Button', {onPress: () => { for(;;){} }}) } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "onPress") })
	fn := fnRefOf(t, cap.ops(t), "onPress")

	start := time.Now()
	_ = r.UIEvent("s1", fn, nil)
	took := time.Since(start)
	if took > 5*time.Second {
		t.Fatalf("死循环的回调跑了 %v 还没被打断", took.Round(time.Millisecond))
	}
	// 打断之后运行时还能用:被占住的话这一句会一直等下去
	done := make(chan error, 1)
	go func() {
		_, err := r.Eval(t.Context(), BudgetData, "x.js", `1 + 1`)
		done <- err
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("打断之后运行时不能用了: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("回调被打断了,但事件循环还被占着")
	}
}

// 数字样式必须**以数字到达壳**(SPEC 7.5:长度是设备无关像素,不是 CSS)。
//
// ☠ Preact 写 style 时会给数字自动补 px(它以为自己在跟 CSS 打交道),
// 于是 `fontSize: 22` 变成字符串 "22px",壳按数字读读不到 ——
// 表现是「样式一条都没生效」,而且不报错、编译也绿。2026-09-20 截图才看出来。
func TestUI数字样式不带px(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		definePlugin({ pages: { p: () => h('Text', {style: {fontSize: 22, gap: 14, opacity: 0.5, direction: 'row'}}, '标题') } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "style") })

	var style map[string]any
	for _, o := range cap.ops(t) {
		if set, ok := o["set"].(map[string]any); ok {
			if s, ok := set["style"].(map[string]any); ok {
				style = s
			}
		}
	}
	if style == nil {
		t.Fatalf("没收到 style:\n%s", cap.dump())
	}
	for _, k := range []string{"fontSize", "gap", "opacity"} {
		v, ok := style[k]
		if !ok {
			t.Errorf("style 里没有 %s(收到 %v)", k, style)
			continue
		}
		if _, isNum := v.(float64); !isNum {
			t.Errorf("style.%s 到壳那边是 %#v —— 应该是数字", k, v)
		}
	}
	// 字符串值原样保留,别把 'row' 也动了
	if style["direction"] != "row" {
		t.Errorf("style.direction 被改成了 %#v", style["direction"])
	}
}
