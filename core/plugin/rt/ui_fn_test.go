package rt

import (
	"encoding/json"
	"strings"
	"testing"
)

/*
回调号的回收。这一组盯的是**泄漏**和**作废**两件事,它们在界面上都看不出来:
泄漏表现成插件跑久了越来越吃内存,作废没做表现成「壳上迟到的一次点击打进了已卸载的闭包」。

☠ 上一版的「卸载后回调号作废」只证明了 `surfaces.has()` 那道门 —— 把整段回收代码
   删掉照样绿。这里的三条都不靠 surface 存在性:surface 全程挂着。
*/

// 节点被删(不是整块卸载)后,它的回调号必须作废。
func TestUI节点删掉后回调号就地作废(t *testing.T) {
	r, cap := newUI(t, `
		const { h, useState } = __linplayer_sdk;
		globalThis.__hits = 0;
		globalThis.__hide = null;
		definePlugin({ pages: { p: () => {
			const [show, setShow] = useState(true);
			globalThis.__hide = () => setShow(false);
			return h('Column', null, show ? h('Button', {onPress: () => { globalThis.__hits++; }}) : null);
		} } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "onPress") })
	fn := fnRefOf(t, cap.ops(t), "onPress")

	if _, err := r.Eval(t.Context(), BudgetData, "x.js", `globalThis.__hide()`); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasOp(cap.ops(t), "remove") })

	// surface 还挂着 —— 这一条不许靠「surface 没了」兜住
	if err := r.UIEvent("s1", fn, nil); err != nil {
		t.Fatalf("对已作废的回调号发事件应当静默丢掉: %v", err)
	}
	if got := evalStr(t, r, `String(globalThis.__hits)`); got != "0" {
		t.Fatalf("节点删了以后那个回调号还能调到(%s 次)—— dropSubtree 没回收它", got)
	}
}

// 嵌套在对象/数组里的函数属性也要回收。
//
// 反向验证过:把 renderer.js 的 fnsByNode 改回只记顶层属性名(旧实现),
// 这条当场红在「回调号只增不减」。
func TestUI嵌套的回调号不许泄漏(t *testing.T) {
	r, cap := newUI(t, `
		const { h, useState } = __linplayer_sdk;
		globalThis.__bump = null;
		definePlugin({ pages: { p: () => {
			const [n, setN] = useState(0);
			globalThis.__bump = () => setN((x) => x + 1);
			// 回调**藏在对象和数组里**:SDK 里 PosterRow 的 items、FilterPanel 的
			// dimensions 都是这个形状,一点都不罕见
			return h('Column', { menu: { rows: [{ onPick: () => n }, { onPick: () => n }] } });
		} } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "menu") })

	base, err := r.UIFnCount()
	if err != nil {
		t.Fatal(err)
	}
	if base < 2 {
		t.Fatalf("两个嵌套回调应该各拿到一个号,实际挂着 %d 个 —— 序列化没往里递归?", base)
	}
	for i := 0; i < 20; i++ {
		if _, err := r.Eval(t.Context(), BudgetData, "x.js", `globalThis.__bump()`); err != nil {
			t.Fatal(err)
		}
	}
	cap.wait(t, func() bool { n, _ := r.UIFnCount(); return n > 0 })

	got, err := r.UIFnCount()
	if err != nil {
		t.Fatal(err)
	}
	// 每次重渲染换两个新闭包;回收对的话稳定在 base,回收不了就是 base + 2*20
	if got > base {
		t.Fatalf("重渲染 20 次后回调号从 %d 涨到 %d —— 嵌套在对象/数组里的 $fn 没被回收", base, got)
	}
}

// 整块卸载后回调号全部归零(这条才该用 surface 卸载来验)。
func TestUI卸载后回调号全部归零(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		definePlugin({ pages: { p: () => h('Column', null,
			h('Button', {onPress: () => {}}),
			h('Button', {onPress: () => {}})) } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "onPress") })
	if n, err := r.UIFnCount(); err != nil || n < 2 {
		t.Fatalf("挂载后应当有两个回调号,拿到 %d(err=%v)", n, err)
	}
	if err := r.UIUnmount("s1"); err != nil {
		t.Fatal(err)
	}
	if n, err := r.UIFnCount(); err != nil || n != 0 {
		t.Fatalf("卸载后回调号应当清零,还剩 %d(err=%v)", n, err)
	}
}

func evalStr(t *testing.T, r *Runtime, code string) string {
	t.Helper()
	v, err := r.Eval(t.Context(), BudgetData, "x.js", code)
	if err != nil {
		t.Fatal(err)
	}
	var s string
	_ = json.Unmarshal(v, &s)
	return s
}

/*
未知属性:忽略,但开发者模式下报一次 warn(D319)。

☠ 判据要同时钉住三件事,少一件这条 warn 就废了:
  · 写错的属性**真的报**(不报的话「写了没反应」还是查无可查);
  · 对的属性**不许报**(骂正确的属性,作者只能选择忽略它,等于没有);
  · 同一个写错的属性**只报一次**(长列表里每行触发一次,刷屏会把有用的那条冲掉)。
*/
func TestUI未知属性在开发者模式下报一次warn(t *testing.T) {
	r, cap := newUI(t, `
		const { h } = __linplayer_sdk;
		definePlugin({ pages: { p: () => h('Column', null,
			// 三行都写错同一个属性名:报一次就够
			h('Button', { title: 'a', titel: '写错的' }),
			h('Button', { title: 'b', titel: '写错的' }),
			h('Button', { title: 'c', titel: '写错的' })) } })
	`, func(o *Options) { o.Dev = true })
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "title") })

	var warns int
	var sawRight bool
	for _, e := range r.Logs() {
		if e.Level != "warn" {
			continue
		}
		if strings.Contains(e.Msg, "titel") {
			warns++
		}
		if strings.Contains(e.Msg, "`title`") {
			sawRight = true
		}
	}
	if warns == 0 {
		t.Fatalf("写错的属性没报 warn —— 插件作者看到的是「写了没反应」。日志:%v", r.Logs())
	}
	if warns > 1 {
		t.Errorf("同一个写错的属性报了 %d 次 —— 长列表里会刷屏", warns)
	}
	if sawRight {
		t.Error("把正确的属性也骂了 —— 作者只能选择忽略这条 warn,等于没有")
	}
}
