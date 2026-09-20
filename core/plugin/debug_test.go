package plugin

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/paths"
)

/*
`debug` 命名空间(SPEC 16.5,D80 D81)。

☠ 判据里最要紧的是**关着的时候整个命名空间不在**:挂一个永远返回空表的版本,
  调试面板会把「这一版不收数据」显示成「这个插件没日志」—— 两种情况在界面上一模一样。
*/

func devMode(t *testing.T, on bool) {
	t.Helper()
	c := config.Current()
	p := c.PrefsOf()
	p.DevMode = on
	if err := c.SetPrefs(p); err != nil {
		t.Fatal(err)
	}
	if err := c.Save(); err != nil {
		t.Fatal(err)
	}
}

func evalIn(t *testing.T, h *Host, code string) string {
	t.Helper()
	l, err := h.get("alice/demo", "test")
	if err != nil {
		t.Fatal(err)
	}
	v, err := l.rt.Eval(context.Background(), 5*time.Second, "x.js", code)
	if err != nil {
		t.Fatalf("跑 %s 失败: %v", code, err)
	}
	var s string
	if json.Unmarshal(v, &s) == nil {
		return s
	}
	return string(v)
}

func TestDebug命名空间跟着开发者模式开关(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}

	devMode(t, false)
	h := installAndRestart(t, `definePlugin({ commands: { a() { return 1 } } })`)
	if got := evalIn(t, h, `typeof __linplayer_sdk.debug`); got != "undefined" {
		t.Fatalf("开发者模式关着时 debug 命名空间不该存在,typeof = %s", got)
	}

	devMode(t, true)
	h = restart(t)
	if got := evalIn(t, h, `typeof __linplayer_sdk.debug`); got != "object" {
		t.Fatalf("开发者模式开着时 debug 命名空间应当挂上,typeof = %s", got)
	}
}

// 四块数据都要真能取到 —— 只判「函数在不在」的话,返回 undefined 的实现照样绿。
func TestDebug四块数据都取得到(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	devMode(t, true)
	h := installAndRestart(t, `
		__linplayer_sdk.storage.set('k1', {a: 1});
		console.log('一条日志');
		definePlugin({ pages: { p: () => __linplayer_sdk.h('Text', null, '嗨') } })
	`)
	registerUI()
	c := captureUI(t)
	sid := mountUI(t, c, map[string]any{"plugin": "alice/demo", "target": "p", "kind": "page"})
	c.wait(t, "首帧", func() bool { return len(c.ops()) > 0 })

	// 日志
	if got := evalIn(t, h, `__linplayer_sdk.debug.logs('alice/demo').then(r => JSON.stringify(r))`); !strings.Contains(got, "一条日志") {
		t.Errorf("debug.logs 没拿到插件日志:%s", got)
	}
	// 存储
	if got := evalIn(t, h, `__linplayer_sdk.debug.storage('alice/demo').then(r => JSON.stringify(r))`); !strings.Contains(got, "k1") {
		t.Errorf("debug.storage 没拿到 KV:%s", got)
	}
	// surface 清单 + UI 树
	if got := evalIn(t, h, `__linplayer_sdk.debug.surfaces().then(r => JSON.stringify(r))`); !strings.Contains(got, sid) {
		t.Errorf("debug.surfaces 没列出刚挂上的 %s:%s", sid, got)
	}
	tree := evalIn(t, h, `__linplayer_sdk.debug.uiTree(`+jsonStr(sid)+`).then(r => JSON.stringify(r))`)
	if !strings.Contains(tree, `"Text"`) || !strings.Contains(tree, "嗨") {
		t.Errorf("debug.uiTree 里没有这一页的 Text 节点与文字:%s", tree)
	}
	// 性能
	if got := evalIn(t, h, `__linplayer_sdk.debug.stats('alice/demo').then(r => JSON.stringify(r))`); !strings.Contains(got, "calls") {
		t.Errorf("debug.stats 没回统计:%s", got)
	}
	// 写存储
	evalIn(t, h, `__linplayer_sdk.debug.setStorage('alice/demo', 'k2', 42)`)
	if got := evalIn(t, h, `JSON.stringify(__linplayer_sdk.storage.get('k2'))`); got != "42" {
		t.Errorf("debug.setStorage 没写进去,读回来是 %s", got)
	}
}

func jsonStr(s string) string { b, _ := json.Marshal(s); return string(b) }
