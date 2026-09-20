package plugin

import (
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

/*
官方同步插件(SPEC 17.3,D545 的「老功能对等」)。

☠ 判据必须是「`trakt.request` 真的被调到了」。
  上一版判的是「插件日志里出现 scrobble 这个词」,而插件收到对不上的条目时
  会记一行「scrobble 跳过:这一条没有 TMDB / IMDb id」—— 那行里正好有这个词。
  于是**记账一次都没发生**,测试却一直绿;把那行日志改几个字测试就红了,
  这说明它量的是日志文案,不是行为。
*/

// syncHarness 装好插件,并把 trakt / bangumi 的代发命令换成记账的假实现。
type syncHarness struct {
	l     *loaded
	mu    sync.Mutex
	calls []string
}

func (s *syncHarness) note(v string) {
	s.mu.Lock()
	s.calls = append(s.calls, v)
	s.mu.Unlock()
}

func (s *syncHarness) sawContaining(sub string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.calls {
		if strings.Contains(c, sub) {
			return true
		}
	}
	return false
}

func (s *syncHarness) waitFor(t *testing.T, sub string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if s.sawContaining(sub) {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	s.mu.Lock()
	got := append([]string(nil), s.calls...)
	s.mu.Unlock()
	t.Fatalf("没等到代发 %q;这一轮实际发出去的是:%v\n插件日志:%v", sub, got, logMsgs(s.l))
}

func logMsgs(l *loaded) []string {
	var out []string
	for _, e := range l.rt.Logs() {
		out = append(out, e.Msg)
	}
	return out
}

// curHarness 当前用例的记账板。bus.Register 一个名字只能注册一次(重复会 panic),
// 所以假命令**整包只装一次**,每个用例换的是这块板子。
var (
	curHarness atomic.Pointer[syncHarness]
	fakesOnce  sync.Once
)

func installSyncFakes() {
	fakesOnce.Do(func() {
		for _, svc := range []string{"trakt", "bangumi"} {
			svc := svc
			bus.Register("sync."+svc+"Request", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
				sh := curHarness.Load()
				if sh == nil {
					return nil, bus.NewErr(bus.EAuth, "还没有连接")
				}
				b, _ := json.Marshal(a["body"])
				sh.note(svc + " " + str(a, "method") + " " + str(a, "path") + " " + string(b))
				return map[string]any{"ok": true}, nil
			})
		}
		// 条目详情:给一条**带 TMDB id** 的,scrobble 才有条目可对
		bus.Register("emby.itemDetail", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
			return map[string]any{
				"id": str(a, "item_id"), "name": "某剧 第1集", "type_": "Episode",
				"series_id": "series-1", "episode_no": float64(1), "bgm_id": float64(4242),
			}, nil
		})
		bus.Register("emby.providers", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
			return map[string]any{"tmdb": "12345", "imdb": "tt7654321"}, nil
		})
	})
}

func newSyncHarness(t *testing.T) *syncHarness {
	t.Helper()
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)

	sh := &syncHarness{}
	installSyncFakes()
	curHarness.Store(sh)
	t.Cleanup(func() { curHarness.Store((*syncHarness)(nil)) })
	// 条目信息在进程内按 itemID 缓存,跨用例会串:每个用例从干净的开始
	npMu.Lock()
	npCache = map[string]npItem{}
	npMu.Unlock()

	dir := filepath.Join(repoRoot(t), "plugins", "sync")
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatalf("加载同步插件失败: %v", err)
	}
	l, err := h.get("linplayer/sync", "test")
	if err != nil {
		t.Fatalf("起不来: %v", err)
	}
	sh.l = l
	return sh
}

// 播一条带外部 id 的 → Trakt 必须收到 scrobble/start。
func TestSyncPlugin起播时真的上报Trakt(t *testing.T) {
	sh := newSyncHarness(t)

	// 三个贡献点都要有实现(lp check 也查这条,这里是运行时再确认一遍)
	for _, path := range []string{"pages.manage", "blocks.detailStatus", "blocks.settingsBlock"} {
		if !sh.l.rt.Has(path) {
			t.Errorf("definePlugin 里没有 %s", path)
		}
	}

	rt.EmitAppEvent("player.start", nowPlaying("ep-1", 12, 1200))
	sh.waitFor(t, "/scrobble/start")

	// 发出去的身子里必须带上真 id,不然 Trakt 那边对不上条目
	if !sh.sawContaining(`"tmdb":12345`) && !sh.sawContaining(`"tmdb":"12345"`) {
		t.Errorf("上报里没有 TMDB id —— Trakt 只能按片名猜")
	}
}

// 暂停 → scrobble/pause;播完到阈值 → Bangumi 标记。
func TestSyncPlugin暂停与播完各走各的(t *testing.T) {
	sh := newSyncHarness(t)
	rt.EmitAppEvent("player.start", nowPlaying("ep-1", 12, 1200))
	sh.waitFor(t, "/scrobble/start")

	rt.EmitAppEvent("player.pause", nowPlaying("ep-1", 600, 1200))
	sh.waitFor(t, "/scrobble/pause")

	// 播到 95%:超过默认阈值 80,要把 Bangumi 条目标成在看
	end := nowPlaying("ep-1", 1140, 1200)
	end["reason"] = "eof"
	rt.EmitAppEvent("player.end", end)
	sh.waitFor(t, "/scrobble/stop")
	sh.waitFor(t, "bangumi POST /v0/users/-/collections/4242")
}

// 没到阈值就播完(用户中途退出)→ 不许标「看过」。
func TestSyncPlugin没到阈值不标看过(t *testing.T) {
	sh := newSyncHarness(t)
	rt.EmitAppEvent("player.start", nowPlaying("ep-1", 12, 1200))
	sh.waitFor(t, "/scrobble/start")

	end := nowPlaying("ep-1", 120, 1200) // 10%
	end["reason"] = "stop"
	rt.EmitAppEvent("player.end", end)
	sh.waitFor(t, "/scrobble/stop")

	// stop 已经到了,说明这一轮事件处理完了;此时不该有 Bangumi 收藏调用
	if sh.sawContaining("/v0/users/-/collections/") {
		t.Fatal("才看了 10% 就把条目标成在看了")
	}
}

// 没连账号时插件不许把播放打断:同步是记账,播放是主线。
func TestSyncPlugin代发抛错时不打断播放(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)

	var got string
	installSyncFakes()
	curHarness.Store((*syncHarness)(nil)) // 没有记账板 = 假代发按「没连账号」抛 auth
	dir := filepath.Join(repoRoot(t), "plugins", "sync")
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatal(err)
	}
	l, err := h.get("linplayer/sync", "test")
	if err != nil {
		t.Fatal(err)
	}
	v, err := l.rt.Eval(context.Background(), 5*time.Second, "x.js", `
		(async () => {
			try { await __linplayer_sdk.trakt.request('POST', '/scrobble/start', {}); return 'not-thrown'; }
			catch (e) { return 'threw:' + e.kind; }
		})()
	`)
	if err != nil {
		t.Fatalf("代发本身不该把调用打断: %v", err)
	}
	_ = json.Unmarshal(v, &got)
	if got != "threw:needLogin" {
		t.Fatalf("没连账号时代发应当抛 needLogin,拿到 %q —— 回空值的话插件会以为发成功了", got)
	}
}
