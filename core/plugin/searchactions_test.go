package plugin

import (
	"context"
	"strings"
	"testing"
	"time"

	"linplayer/core/paths"
)

func installSearchPlugin(t *testing.T, main string) *Host {
	t.Helper()
	paths.SetRoot(t.TempDir())
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	pkg := writePkg(t, map[string]string{
		"manifest.json": manifestJSON("alice/demo", "1.0.0",
			`,"contributes":{"searchActions":true,"commands":[{"id":"open","title":"打开"}]}`),
		"main.js": sdkPrelude + main,
	})
	if _, err := h.Install(pkg, "local"); err != nil {
		t.Fatal(err)
	}
	h.markStable()
	return restart(t)
}

func Test搜索快捷动作列得出来也跑得动(t *testing.T) {
	h := installSearchPlugin(t, `
let last = null
definePlugin({
  searchActions: (q) => [{title: '在豆瓣搜「' + q + '」', command: 'open', args: {q}}],
  commands: {open(a){ last = a && a.q; return '开了 ' + last }},
})`)

	got := h.SearchActions(context.Background(), "三体")
	if len(got) != 1 {
		t.Fatalf("插件给了一颗按钮,应该列出来: %+v", got)
	}
	if !strings.Contains(got[0].Title, "三体") {
		t.Errorf("插件拿得到用户输的那串字: %+v", got[0])
	}
	if got[0].PluginID != "alice/demo" || got[0].Command != "open" {
		t.Errorf("点了要知道找谁跑哪条命令: %+v", got[0])
	}

	// 另一半:点了要能跑。只接「列出来」的话,搜索页上是一排点了没反应的按钮
	out, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.open",
		[]any{map[string]any{"q": "三体"}}, map[string]any{})
	if err != nil || !strings.Contains(string(out), "三体") {
		t.Fatalf("命令没跑起来: %s %v", out, err)
	}
}

// 一个插件抽风不该把整排按钮连坐,更不该把搜索框卡住。
func Test一个插件抛错不影响这次搜索(t *testing.T) {
	h := installSearchPlugin(t, `
definePlugin({
  searchActions: () => { throw new Error('我坏了') },
  commands: {open(){ return 1 }},
})`)
	if got := h.SearchActions(context.Background(), "三体"); len(got) != 0 {
		t.Fatalf("抛错的插件不该有按钮,也不该崩: %+v", got)
	}
}

// 标题或命令缺一个 = 点了没反应,宁可不画。
func Test缺标题或命令的按钮不画(t *testing.T) {
	h := installSearchPlugin(t, `
definePlugin({
  searchActions: () => [{title: '', command: 'open'}, {title: '有标题没命令'}, {title: '好的', command: 'open'}],
  commands: {open(){ return 1 }},
})`)
	got := h.SearchActions(context.Background(), "x")
	if len(got) != 1 || got[0].Title != "好的" {
		t.Fatalf("只该留下齐全的那一颗: %+v", got)
	}
}
