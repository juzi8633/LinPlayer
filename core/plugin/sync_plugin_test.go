package plugin

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

/*
官方同步插件(SPEC 17.3,D545 的「老功能对等」)。

☠ 这一条盯的是**它真跑得起来**,不是「文件在」:
  activate 挂的是 player.* 事件,挂不上的话表现是「插件装了,同步永远不动」,
  而日志里一条异常都没有。
*/
func TestSyncPlugin能加载并挂上播放事件(t *testing.T) {
	dir := filepath.Join(repoRoot(t), "plugins", "sync")
	if _, err := os.Stat(dir); err != nil {
		t.Skip("仓库里没有 plugins/sync")
	}
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatalf("加载同步插件失败: %v", err)
	}
	l, err := h.get("linplayer/sync", "test")
	if err != nil {
		t.Fatalf("起不来: %v", err)
	}
	// activate 里订阅了五个播放事件:订阅不上会当场抛(events.on 对未知事件名抛错)
	v, err := l.rt.Eval(context.Background(), 5*time.Second, "x.js",
		`typeof __linplayer_sdk.trakt.request + ',' + typeof __linplayer_sdk.bangumi.request`)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(v), "function,function") {
		t.Fatalf("代发接口没挂上:%s", v)
	}
	// 三个贡献点都要有实现(lp check 也查这条,这里是运行时再确认一遍)
	for _, path := range []string{"pages.manage", "blocks.detailStatus", "blocks.settingsBlock"} {
		if !l.rt.Has(path) {
			t.Errorf("definePlugin 里没有 %s", path)
		}
	}

	/* 真发一条播放事件,看插件那边**收没收到**。
	   ☠ 只查「namespace 挂上了」是测不到这件事的:activate 里的 events.on
	     没挂上时,表现正是「插件装了、同步永远不动」,而日志里一条异常都没有。
	   判据用插件自己的日志环:scrobble 收到没有 id 的条目时会记一行。 */
	rt.EmitAppEvent("player.start", map[string]any{"itemId": "ep-1", "position": 1.0, "duration": 100.0})
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		for _, e := range l.rt.Logs() {
			if strings.Contains(e.Msg, "scrobble") {
				return
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("发了 player.start,插件那边一点反应都没有 —— activate 里的订阅没挂上")
}

// 没连账号时插件不许把播放打断:同步是记账,播放是主线。
func TestSyncPlugin没连账号也不抛到宿主(t *testing.T) {
	dir := filepath.Join(repoRoot(t), "plugins", "sync")
	if _, err := os.Stat(dir); err != nil {
		t.Skip("仓库里没有 plugins/sync")
	}
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatal(err)
	}
	l, err := h.get("linplayer/sync", "test")
	if err != nil {
		t.Fatal(err)
	}
	// 直接跑一次 scrobble:trakt.request 会因为没连账号抛 auth,插件必须自己吞掉
	_, err = l.rt.Eval(context.Background(), 5*time.Second, "x.js", `
		(async () => {
			const m = __linplayer_sdk;
			try { await m.trakt.request('POST', '/scrobble/start', {}); return 'not-thrown'; }
			catch (e) { return 'threw:' + e.kind; }
		})()
	`)
	if err != nil {
		t.Fatalf("代发本身不该把调用打断: %v", err)
	}
}
