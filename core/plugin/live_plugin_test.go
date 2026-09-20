package plugin

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
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
直播插件的验收(D544:能播能换台、节目单对、回看跑通、TV 遥控全流程)。

☠ 这几件在真机上的失败长得都一样 ——「点了没反应」,所以判据必须钉**行为**:
  · 换台要**真的换到下一条地址**,而且只在前一条不出画时才换(D61);
  · 遥控器的上下键 / 数字键 / 确认 / 返回要**真的被插件接走**(D563),
    接不走的话宿主按默认处理,表现是「按了直接退出播放」;
  · 返回键第一次不退、第二次才退(D358)—— 一次就退是最容易写错的那一处。
*/

// liveHarness 装好直播插件,假的 player 命令记账,并起一个吐 m3u 的本地服务器。
type liveHarness struct {
	h   *Host
	l   *loaded
	srv *httptest.Server
	mu  sync.Mutex
	// played 依次播过的地址
	played []string
	// pos 假的播放进度:>0 且在涨 = 出画了
	pos map[string]float64
}

func (lh *liveHarness) playedList() []string {
	lh.mu.Lock()
	defer lh.mu.Unlock()
	return append([]string(nil), lh.played...)
}

func (lh *liveHarness) waitPlayed(t *testing.T, n int) []string {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if got := lh.playedList(); len(got) >= n {
			return got
		}
		time.Sleep(30 * time.Millisecond)
	}
	t.Fatalf("等不到第 %d 次起播,已播:%v\n插件日志:%v", n, lh.playedList(), logMsgs(lh.l))
	return nil
}

// livePlaylist 两个频道:第一个有两条地址(第一条永远不出画),第二个一条。
const livePlaylist = `#EXTM3U x-tvg-url="__EPG__"
#EXTINF:-1 tvg-id="ch1" group-title="测试" tvg-chno="1",一号台
__BASE__/dead.m3u8
#EXTINF:-1 tvg-id="ch1" group-title="测试",一号台
__BASE__/good.m3u8
#EXTINF:-1 tvg-id="ch2" group-title="测试" tvg-chno="2",二号台
__BASE__/two.m3u8
`

const liveEPG = `<?xml version="1.0"?><tv>
<channel id="ch1"><display-name>一号台</display-name></channel>
<programme start="__S__" stop="__E__" channel="ch1"><title>正在播的这一档</title></programme>
</tv>`

var liveFakesOnce sync.Once
var curLive struct {
	mu sync.Mutex
	h  *liveHarness
}

func installLiveFakes() {
	liveFakesOnce.Do(func() {
		bus.Register("player.playUrl", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
			curLive.mu.Lock()
			lh := curLive.h
			curLive.mu.Unlock()
			if lh == nil {
				return nil, bus.NewErr(bus.EInternal, "没有测试台")
			}
			u := str(a, "url")
			lh.mu.Lock()
			lh.played = append(lh.played, u)
			lh.mu.Unlock()
			return map[string]any{"url": u}, nil
		})
		bus.Register("player.status", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
			curLive.mu.Lock()
			lh := curLive.h
			curLive.mu.Unlock()
			if lh == nil {
				return map[string]any{"position": 0.0}, nil
			}
			lh.mu.Lock()
			defer lh.mu.Unlock()
			last := ""
			if n := len(lh.played); n > 0 {
				last = lh.played[n-1]
			}
			// 「出画」= 进度在涨。dead 那条永远停在 0:插件必须据此换下一条
			if strings.Contains(last, "dead") {
				return map[string]any{"position": 0.0, "duration": 0.0}, nil
			}
			lh.pos[last] += 1.0
			return map[string]any{"position": lh.pos[last], "duration": 0.0}, nil
		})
	})
}

func newLiveHarness(t *testing.T) *liveHarness {
	t.Helper()
	lh := &liveHarness{pos: map[string]float64{}}
	now := time.Now()
	lh.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/list.m3u":
			base := "http://" + r.Host
			body := strings.ReplaceAll(livePlaylist, "__BASE__", base)
			body = strings.ReplaceAll(body, "__EPG__", base+"/epg.xml")
			_, _ = w.Write([]byte(body))
		case "/epg.xml":
			/* ☠ 故意用 +0800 而不是 +0000:用 UTC 写的话「时区符号搞反了」
			   这个 bug 在这条判据上量不出来(0 的正负一样),而那正是节目单
			   最容易错的地方。写成 +0800 之后,符号反了就差 16 小时,
			   当前节目当场选不中。 */
			f := func(tm time.Time) string {
				return tm.UTC().Add(8 * time.Hour).Format("20060102150405") + " +0800"
			}
			body := strings.ReplaceAll(liveEPG, "__S__", f(now.Add(-10*time.Minute)))
			body = strings.ReplaceAll(body, "__E__", f(now.Add(50*time.Minute)))
			_, _ = w.Write([]byte(body))
		default:
			_, _ = w.Write([]byte("#EXTM3U\n"))
		}
	}))
	t.Cleanup(lh.srv.Close)

	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	installLiveFakes()
	curLive.mu.Lock()
	curLive.h = lh
	curLive.mu.Unlock()
	t.Cleanup(func() {
		curLive.mu.Lock()
		curLive.h = nil
		curLive.mu.Unlock()
	})

	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	registerUI()
	registerKeys()
	if _, err := h.DevLoad(filepath.Join(repoRoot(t), "plugins", "live")); err != nil {
		t.Fatalf("加载直播插件失败: %v", err)
	}
	l, err := h.get("linplayer/live", "test")
	if err != nil {
		t.Fatalf("起不来: %v", err)
	}
	lh.h, lh.l = h, l

	// 插件的源表存在它自己的 storage 里:直接灌一条指向本地服务器的
	if _, err := l.rt.Eval(context.Background(), 10*time.Second, "x.js",
		`__linplayer_sdk.storage.set('sources', [{id:'s1', name:'测试源', url:'`+lh.srv.URL+`/list.m3u', enabled:true}])`); err != nil {
		t.Fatal(err)
	}
	return lh
}

// 一条地址不出画要自动换下一条,并把能用的那条记住(D61)。
func TestLivePlugin播不动要换下一条地址并记住(t *testing.T) {
	lh := newLiveHarness(t)

	sid := mountLive(t, lh, "osd")
	defer call(t, "plugin.ui.unmount", map[string]any{"surface": sid})

	// 数字键去 1 号台(它的第一条地址永远不出画)。频道表还在拉时插件会提示
	// 「稍等一下」—— 那是正确行为(不静默),所以这里像用户一样再按一次。
	pressUntilPlayed(t, lh, "1", 1)
	// 起播之后**不要再按**:插件要等满 8 秒(D61)确认这条不出画才换下一条,
	// 中途再按一次等于重新开始,永远等不到那次换源
	got := lh.waitPlayed(t, 2)

	if !strings.Contains(got[0], "dead") {
		t.Fatalf("第一次起播的不是候选链里的第一条:%v", got)
	}
	if !strings.Contains(got[1], "good") {
		t.Fatalf("第一条不出画却没换到第二条:%v", got)
	}

	// 记住了能用的那条:再回这个台时第一次就该直接是 good
	lh.mu.Lock()
	lh.played = nil
	lh.mu.Unlock()
	again := pressUntilPlayed(t, lh, "1", 1)
	if strings.Contains(again[0], "dead") {
		t.Errorf("没记住上次能用的地址,又从坏的那条试起:%v", again)
	}
}

// 数字键输频道号:1.5 秒不按就跳过去(D357)。
func TestLivePlugin数字键跳频道(t *testing.T) {
	lh := newLiveHarness(t)
	sid := mountLive(t, lh, "osd")
	defer call(t, "plugin.ui.unmount", map[string]any{"surface": sid})

	if !pressKey(t, "2") {
		t.Fatal("数字键没被插件接走 —— 宿主会按默认处理,TV 上就是直接退出")
	}
	got := lh.waitPlayed(t, 1)
	if !strings.Contains(got[0], "two") {
		t.Fatalf("按 2 没跳到二号台:%v", got)
	}
}

/*
返回键:第一次不退、两秒内第二次才退(D358)。

☠ 一次就退是最容易写错的那一处:`consumed` 一直回 true 的话用户永远退不出去,

	一直回 false 的话按一下就退——两种都不对,所以两次都要验。
*/
func TestLivePlugin返回键要按两次才退(t *testing.T) {
	lh := newLiveHarness(t)
	sid := mountLive(t, lh, "osd")
	defer call(t, "plugin.ui.unmount", map[string]any{"surface": sid})

	if !pressKey(t, "back") {
		t.Fatal("第一次返回没被接走 —— 按一下就退出直播了")
	}
	if pressKey(t, "back") {
		t.Fatal("第二次返回还被接走 —— 用户退不出去")
	}
}

// 确认键呼出频道列表(D357):第一次接走(开列表),开着时交给列表里的按钮。
func TestLivePlugin确认键呼出列表(t *testing.T) {
	lh := newLiveHarness(t)
	sid := mountLive(t, lh, "osd")
	defer call(t, "plugin.ui.unmount", map[string]any{"surface": sid})

	if !pressKey(t, "ok") {
		t.Fatal("确认键没被接走 —— 呼不出频道列表")
	}
	if pressKey(t, "ok") {
		t.Error("列表开着时确认键还被整体接走,列表里的按钮就永远按不到")
	}
}

// mountLive 挂插件的播放器覆盖层,并等它把 onKey 挂上。
func mountLive(t *testing.T, lh *liveHarness, target string) string {
	sid, _ := mountLiveCap(t, lh, target)
	return sid
}

func mountLiveCap(t *testing.T, lh *liveHarness, target string) (string, *evCapture) {
	t.Helper()
	c := captureUI(t)
	sid := mountUI(t, c, map[string]any{
		"plugin": "linplayer/live", "target": target, "kind": "overlay",
	})
	if sid == "" {
		t.Fatal("挂覆盖层没回 surface id")
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if lh.l.rt.SlotCount(rt.SlotKey) > 0 {
			return sid, c
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("覆盖层挂上了但没注册 onKey —— 遥控器一个键都收不到。插件日志:%v", logMsgs(lh.l))
	return "", nil
}

/*
换台信息条上要有**当前这一档节目**(D544 的「XMLTV 节目单正确」)。

☠ 节目单错位不报错:时区当本地时间解就差 8 小时,当前节目会指到别的档上。

	所以判据是「信息条上真的出现了假服务器里那一档的标题」,
	而那一档的时间是按现在前后算出来的 —— 解错时区就选不中它。
*/
func TestLivePlugin信息条上要有当前节目(t *testing.T) {
	lh := newLiveHarness(t)
	sid, c := mountLiveCap(t, lh, "osd")
	defer call(t, "plugin.ui.unmount", map[string]any{"surface": sid})

	pressUntilPlayed(t, lh, "1", 1)
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		blob, _ := json.Marshal(c.ops())
		// ☠ 必须连「正在播:」一起判。只找标题的话,时区解错 16 小时时
		// 这一档会落到「接下来」那一行上 —— 标题照样出现在画面里,判据照样绿。
		if strings.Contains(string(blob), "正在播:正在播的这一档") {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	blob, _ := json.Marshal(c.ops())
	t.Fatalf("换台之后信息条上没有「正在播:」那一行。画出来的是:%s", tailStr(string(blob), 800))
}

func tailStr(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return "…" + s[len(s)-n:]
}

// pressKey 走**真实那条路**:壳调 plugin.playerKey,回 consumed。
func pressKey(t *testing.T, key string) bool {
	t.Helper()
	out := call(t, "plugin.playerKey", map[string]any{"key": key, "plugin": "linplayer/live"})
	b, _ := out["consumed"].(bool)
	return b
}

// pressUntilPlayed 像用户一样按:频道表还在拉时插件会提示稍等,按到有反应为止。
func pressUntilPlayed(t *testing.T, lh *liveHarness, key string, want int) []string {
	t.Helper()
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		if got := lh.playedList(); len(got) >= want {
			return got
		}
		if !pressKey(t, key) {
			t.Fatalf("%s 键没被插件接走 —— 宿主会按默认处理", key)
		}
		// 2 秒:比插件里数字键那个 1.5 秒的等待长 —— 按得太密的话
		// 每次按键都把定时器重置,数字会一直攒着永远不跳(TVBox 就是这手感)
		time.Sleep(2 * time.Second)
	}
	t.Fatalf("按了一路 %s 也没播到 %d 次,已播:%v;插件日志:%v", key, want, lh.playedList(), logMsgs(lh.l))
	return nil
}
