package rt

import (
	"fmt"
	"testing"
	"time"
)

/*
D543 的两条数字验收里,核心层这一侧能量到的部分:

  · 插件页首帧 < 300ms —— 这里量的是「UIMount 到第一帧 ops 到手」,
    不含壳把 ops 变成控件的时间。壳那半截在两端各自的自检里量。
  · TV 1000 项 VirtualList ≥ 50fps —— 帧率是壳的事,核心层这边能保证的是
    「一次滚动产生的 ops 不随总项数增长」。这条不成立的话壳再快也没用。
*/

// 一个「像样的插件页」:标题 + 筛选条 + 3×6 海报网格 + 底部按钮,约 60 个节点。
const benchPage = `
	const { h } = __linplayer_sdk;
	function Poster(p) {
		return h('PosterCard', {title: p.title, subtitle: p.year, onPress: () => {}});
	}
	definePlugin({ pages: { p: () => h('Column', {gap: 14},
		h('Text', {style: {fontSize: 22, fontWeight: 'bold'}}, '示例页'),
		h('ChipGroup', null, ['全部','电影','剧集','动画'].map((s, i) => h('Chip', {key: i, label: s}))),
		h('PosterGrid', {columns: 6},
			Array.from({length: 18}, (_, i) => h(Poster, {key: i, title: '片名 ' + i, year: 2020 + (i % 5)}))),
		h('Button', {title: '加载更多', onPress: () => {}})) } })
`

func TestUI首帧预算(t *testing.T) {
	r, cap := newUI(t, benchPage)
	start := time.Now()
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool {
		cap.mu.Lock()
		defer cap.mu.Unlock()
		return len(cap.frames) > 0
	})
	took := time.Since(start)
	ops := cap.ops(t)
	t.Logf("首帧 %v,%d 条 op", took.Round(time.Millisecond), len(ops))
	if len(ops) < 40 {
		t.Fatalf("只发了 %d 条 op,这一页没建全 —— 数字再好看也不算数", len(ops))
	}
	// 300ms 是 D543 的整条预算(含壳渲染);核心层这半截留一半余量
	if took > 150*time.Millisecond {
		t.Errorf("首帧 %v,超过核心层这一半的预算(150ms)", took)
	}
}

// 列表变长时,**一次局部更新**的 ops 不许跟着变多。
//
// ☠ 这条挡的是最容易犯的那个错:某处顺手写了「变了就整棵重画」。
// 它在 20 项时完全看不出来,到 1000 项才表现为滚动掉帧,而那会儿已经没人记得是哪一笔改的。
func TestUI局部更新的ops不随总量增长(t *testing.T) {
	measure := func(n int) int {
		code := fmt.Sprintf(`
			const { h, useState } = __linplayer_sdk;
			definePlugin({ pages: { p: () => {
				const [sel, setSel] = useState(0);
				globalThis.__pick = (i) => setSel(i);
				return h('Column', null, Array.from({length: %d}, (_, i) =>
					h('PosterCard', {key: i, title: '片名 ' + i, selected: i === sel})));
			} } })
		`, n)
		r, cap := newUI(t, code)
		if err := r.UIMount("s1", "page", "p", nil); err != nil {
			t.Fatal(err)
		}
		cap.wait(t, func() bool { return len(cap.ops(t)) >= n })
		before := len(cap.ops(t))

		// 只把选中项从 0 挪到 1:理想变更是两条 props
		if _, err := r.Eval(t.Context(), BudgetData, "x.js", `globalThis.__pick(1)`); err != nil {
			t.Fatal(err)
		}
		cap.wait(t, func() bool { return len(cap.ops(t)) > before })
		// 再等一拍,确保这一帧的 ops 都到齐了
		time.Sleep(50 * time.Millisecond)
		return len(cap.ops(t)) - before
	}

	small, big := measure(20), measure(1000)
	t.Logf("20 项时一次选中变更 %d 条 op;1000 项时 %d 条", small, big)
	if big > small*3 {
		t.Errorf("列表从 20 涨到 1000,一次局部更新的 op 从 %d 涨到 %d —— 这是在整棵重画", small, big)
	}
}

/*
VirtualList(D134):插件给 itemCount + renderItem(i),JS 只渲染**窗口内**那些项。

☠ 这条挡的是「虚拟列表名字叫虚拟、其实把一千项全画了」——
在 20 项的示例里完全看不出来,到 TV 上滑 1000 项才表现为掉帧,
而那会儿已经没人记得是哪一笔改的。判据是 op 数量,不是帧率:
帧率是壳的事,核心层这边能保证的是「不给壳一千个节点」。
*/
func TestUI虚拟列表只渲染窗口内的项(t *testing.T) {
	r, cap := newUI(t, `
		const { h, VirtualList } = __linplayer_sdk;
		definePlugin({ pages: { p: () => h(VirtualList, {
			itemCount: 1000,
			itemHeight: 56,
			renderItem: (i) => h('Text', null, '第 ' + i + ' 项'),
		}) } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return len(cap.ops(t)) > 10 })
	time.Sleep(60 * time.Millisecond)

	ops := cap.ops(t)
	texts := 0
	for _, o := range ops {
		if o["op"] == "create" && o["type"] == "Text" {
			texts++
		}
	}
	t.Logf("1000 项的首帧建了 %d 个 Text、%d 条 op", texts, len(ops))
	if texts == 0 {
		t.Fatal("一项都没渲染 —— 首屏那一窗必须由 JS 先给一批,不能等原生端报范围")
	}
	if texts > 60 {
		t.Errorf("首帧渲染了 %d 项 —— 虚拟列表没生效,壳会收到上千个节点", texts)
	}
	// 列表本体要带上 itemCount,壳靠它算滚动条与可见范围
	var listed bool
	for _, o := range ops {
		if set, ok := o["set"].(map[string]any); ok {
			if v, ok := set["itemCount"].(float64); ok && int(v) == 1000 {
				listed = true
			}
		}
	}
	if !listed {
		t.Error("没把 itemCount 发给壳 —— 壳不知道总共有多少项")
	}
}
