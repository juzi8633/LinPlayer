package datasource

// TVBox 插件全链路(D321 D322):假站 fixture + 真的 linplayer/tvbox 插件(从 plugins/tvbox 现编现装)。
// 覆盖验收 ①(订阅 → 选源 → 首页/分类/搜索/详情 → 可播地址)②(订阅增删源,被删源的记录还在)
// ③(换源三层出结果 + 观看记录续播)。真出画面在真机自检里做(scripts/selfcheck-win.sh)。

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"linplayer/core/account"
	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/history"
	"linplayer/core/internal/fakevod"
	"linplayer/core/paths"
	"linplayer/core/player"
	"linplayer/core/plugin"
	"linplayer/core/sourcecmd"
)

var regOnce sync.Once

type played struct {
	url     string
	resume  float64
	headers map[string]string
	rec     *player.SourceHistory
}

type env struct {
	t    *testing.T
	fv   *fakevod.Server
	base string
	last *played
}

func newEnv(t *testing.T) *env {
	t.Helper()
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	regOnce.Do(func() {
		account.RegisterCommands("test")
		plugin.RegisterCommands()
		sourcecmd.RegisterCommands()
		RegisterCommands("test")
	})
	e := &env{t: t}
	playFn = func(url, title string, resume float64, headers map[string]string, ua string, subs []emby.ExternalSub, rec *player.SourceHistory) (map[string]any, error) {
		e.last = &played{url: url, resume: resume, headers: headers, rec: rec}
		return map[string]any{"resume_secs": resume}, nil
	}
	e.fv = fakevod.New("")
	engine, err := os.ReadFile(filepath.Join("..", "..", "plugins", "tvbox", "assets", "drpy", "drpy2.js"))
	if err != nil {
		t.Fatalf("插件内置的 drpy 引擎不在(不入库,先跑 bash scripts/fetch-drpy.sh): %v", err)
	}
	e.fv.EngineJS = string(engine)
	srv := httptest.NewServer(e.fv.Handler())
	t.Cleanup(srv.Close)
	e.fv.Base, e.base = srv.URL, srv.URL

	pkg, _, err := plugin.Pack(filepath.Join("..", "..", "plugins", "tvbox"), "2.0.0")
	if err != nil {
		t.Fatalf("插件编不出来: %v", err)
	}
	p := filepath.Join(t.TempDir(), "tvbox.lpplugin")
	os.WriteFile(p, pkg, 0o644)
	h := plugin.ResetForTest()
	Wire(h)
	h.Start("windows", "2.0.0")
	if _, err := h.Install(p, "local"); err != nil {
		t.Fatal(err)
	}
	h = plugin.ResetForTest() // 「重启」:安装重启后生效
	Wire(h)
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	return e
}

func (e *env) call(cmd string, args map[string]any) json.RawMessage {
	e.t.Helper()
	out, err := e.try(cmd, args)
	if err != nil {
		detail := ""
		if be, ok := err.(*bus.Err); ok {
			detail = be.Detail
		}
		// 带上插件最近的日志:drpy 的 console 输出都在这里,排查规则执行比猜快
		var logs struct {
			Logs []struct {
				Msg string `json:"msg"`
			} `json:"logs"`
		}
		if raw, err := e.try("plugin.errorDetail", map[string]any{"id": "linplayer/tvbox"}); err == nil {
			json.Unmarshal(raw, &logs)
		}
		var tail []string
		for i := max(0, len(logs.Logs)-15); i < len(logs.Logs); i++ {
			tail = append(tail, logs.Logs[i].Msg)
		}
		e.t.Fatalf("%s 失败: %v\n%s\n最近日志:\n%s", cmd, err, detail, strings.Join(tail, "\n"))
	}
	return out
}

func (e *env) try(cmd string, args map[string]any) (json.RawMessage, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	// 参数过一遍 JSON:真实调用来自壳的 JSON,数字都是 float64;直接传 Go int 会测到一条走不到的路
	b, _ := json.Marshal(args)
	var viaJSON map[string]any
	json.Unmarshal(b, &viaJSON)
	v, err := bus.Invoke(ctx, cmd, viaJSON)
	if err != nil {
		return nil, err
	}
	out, _ := json.Marshal(v)
	return out, nil
}

func (e *env) key(site string) string {
	return "plugin:linplayer/tvbox/tv" + shortMD5(e.base+"/config/plain.json") + "." + site
}

func shortMD5(s string) string {
	h := md5hex(s)
	return h[:10]
}

type draftT struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Unavailable string `json:"unavailableReason"`
	Exists      bool   `json:"exists"`
	Aggregate   bool   `json:"aggregateDefault"`
	Group       string `json:"group"`
}

// subscribe 走「添加服务器」:表单 → createSources → 勾选可用的 → addSources。
func (e *env) subscribe(url string) []draftT {
	raw := e.call("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": url}})
	var typed struct {
		Sources []draftT `json:"sources"`
	}
	var full struct {
		Sources []map[string]any `json:"sources"` // 插件原样给的草稿(带 config),勾选后原样交回宿主
	}
	json.Unmarshal(raw, &typed)
	json.Unmarshal(raw, &full)
	var pick []any
	for i, d := range typed.Sources {
		if d.Unavailable == "" {
			pick = append(pick, full.Sources[i])
		}
	}
	e.call("source.addSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "sources": pick})
	return typed.Sources
}

func md5hex(s string) string {
	h := md5.Sum([]byte(s))
	return hex.EncodeToString(h[:])
}

func TestTVBoxFullChain(t *testing.T) {
	e := newEnv(t)

	// 添加服务器页出现插件的服务器类型(D423)
	forms := string(e.call("source.formSchema", nil))
	if !strings.Contains(forms, `"type_id":"subscription"`) || !strings.Contains(forms, `"type_id":"drpy-rule"`) {
		t.Fatalf("表单里没有 TVBox 的服务器类型: %s", forms)
	}

	drafts := e.subscribe(e.base + "/config/plain.json")
	byID := map[string]draftT{}
	for _, d := range drafts {
		byID[d.ID] = d
	}
	if len(drafts) != 6 {
		t.Fatalf("配置里 6 个源,拆出 %d 个: %+v", len(drafts), drafts)
	}
	if byID[strings.TrimPrefix(e.key("jar1"), "plugin:linplayer/tvbox/")].Unavailable == "" {
		t.Fatal("桌面上 jar 源应灰显并写原因(D351)")
	}
	if !byID[strings.TrimPrefix(e.key("cms1"), "plugin:linplayer/tvbox/")].Aggregate || byID[strings.TrimPrefix(e.key("t4"), "plugin:linplayer/tvbox/")].Aggregate {
		t.Fatal("「允许聚合」默认应跟随 searchable(D235)")
	}
	// 源进了账号表,不切换当前服务器
	accs := string(e.call("account.listAccounts", nil))
	if !strings.Contains(accs, e.key("cms1")) || !strings.Contains(accs, `"group_name"`) {
		t.Fatalf("账号表里没有数据源或缺分组: %s", accs)
	}

	for _, site := range []string{"cms1", "cms0", "drpy1", "t4"} {
		t.Run(site, func(t *testing.T) {
			parent := e.t
			e.t = t // 子测试里的失败记在子测试上
			defer func() { e.t = parent }()
			e.browse(t, e.key(site))
		})
	}

	// 解析链:官源线路在 flags 里 → 走配置的 type 1 解析(D58)
	var d map[string]any
	json.Unmarshal(e.call("source.detail", map[string]any{"server_id": e.key("cms1"), "item_id": "102"}), &d)
	e.call("source.playItem", map[string]any{"server_id": e.key("cms1"), "item": d, "line_id": "fakeflag", "episode_id": e.base + "/page/102-2-0.html"})
	if !strings.Contains(e.last.url, "/media/jx-102-2-0.m3u8") {
		t.Fatalf("flags 里的线路应经解析拿到真实地址,得到 %s", e.last.url)
	}
}

// browse 首页 → 分类(翻页)→ 搜索 → 详情 → 播放。
func (e *env) browse(t *testing.T, key string) {
	var caps map[string]bool
	json.Unmarshal(e.call("source.caps", map[string]any{"server_id": key}), &caps)
	var home struct {
		Categories []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"categories"`
		Recommended []map[string]any `json:"recommended"`
	}
	json.Unmarshal(e.call("source.home", map[string]any{"server_id": key}), &home)
	if len(home.Categories) == 0 {
		t.Fatalf("首页没有分类: %+v", home)
	}
	var page struct {
		Items []map[string]any `json:"items"`
		Next  string           `json:"next"`
	}
	json.Unmarshal(e.call("source.category", map[string]any{"server_id": key, "category_id": "1"}), &page)
	if len(page.Items) == 0 {
		t.Fatal("分类页没有条目")
	}
	if page.Items[0]["source"] != key {
		t.Fatal("条目没有带来源(D282)")
	}
	if !strings.HasSuffix(key, ".t4") && !strings.HasSuffix(key, ".drpy1") && page.Next != "2" {
		t.Fatalf("20 条一页、共 41 部电影,应有下一页游标,得到 %q", page.Next)
	}
	var sr struct {
		Items []map[string]any `json:"items"`
	}
	json.Unmarshal(e.call("source.searchItems", map[string]any{"server_id": key, "keyword": "山河"}), &sr)
	if len(sr.Items) == 0 || sr.Items[0]["title"] != "山河故事" {
		t.Fatalf("搜索「山河」: %+v", sr.Items)
	}
	id, _ := sr.Items[0]["id"].(string)
	var d map[string]any
	json.Unmarshal(e.call("source.detail", map[string]any{"server_id": key, "item_id": id}), &d)
	lines, _ := d["lines"].([]any)
	if len(lines) < 2 {
		t.Fatalf("详情应有多条线路: %v", d["lines"])
	}
	l0 := lines[0].(map[string]any)
	eps := l0["episodes"].([]any)
	ep0 := eps[0].(map[string]any)
	if ep0["label"] != "第 1 集" || ep0["index"] != float64(1) {
		t.Fatalf("集名应统一成「第 N 集」并解析出序号(D461): %v", ep0)
	}
	e.call("source.playItem", map[string]any{"server_id": key, "item": d, "line_id": l0["id"], "episode_id": ep0["id"]})
	if e.last == nil || !strings.Contains(e.last.url, ".m3u8") {
		t.Fatalf("没有拿到可播地址: %+v", e.last)
	}
	if e.last.rec == nil || e.last.rec.Scope != Scope(key) {
		t.Fatal("数据源播放要带观看记录上下文(D20)")
	}
}

// 验收 ②:订阅更新后增删源跟着变,被删源的观看记录还在(D230 D231)。
func TestTVBoxSubscriptionUpdate(t *testing.T) {
	e := newEnv(t)
	e.subscribe(e.base + "/config/plain.json")
	gone := e.key("cms0")
	// 在要被删的源上留一条观看记录
	var d map[string]any
	json.Unmarshal(e.call("source.detail", map[string]any{"server_id": gone, "item_id": "101"}), &d)
	e.call("source.playItem", map[string]any{"server_id": gone, "item": d, "line_id": "星空线路", "episode_id": firstEp(d)})
	history.Shared().Capture(history.CaptureOpts{ScopeKey: e.last.rec.Scope, Candidate: e.last.rec.Candidate, PositionTicks: 120 * history.TicksPerSec, Source: history.SourceInternal, WatchedThresholdPercent: 90, Force: true, SourceRef: e.last.rec.Ref})

	e.fv.SetVariant(1)
	e.call("source.runCommand", map[string]any{"plugin_id": "linplayer/tvbox", "command": "refresh", "args": map[string]any{"server_id": e.key("cms1")}})
	accs := string(e.call("account.listAccounts", nil))
	if strings.Contains(accs, gone) {
		t.Fatal("订阅里删掉的源应从服务器列表消失")
	}
	if !strings.Contains(accs, e.key("cmsnew")) {
		t.Fatal("订阅里新加的源应自动加入(D231)")
	}
	if len(history.Shared().LoadScope(Scope(gone))) == 0 {
		t.Fatal("被删源的观看记录必须保留(D230)")
	}
	var hist []map[string]any
	json.Unmarshal(e.call("source.history", nil), &hist)
	if len(hist) == 0 || hist[0]["removed"] != true {
		t.Fatalf("全局观看历史里应灰显「来源已移除」(D333): %v", hist)
	}
}

func firstEp(d map[string]any) string {
	return d["lines"].([]any)[0].(map[string]any)["episodes"].([]any)[0].(map[string]any)["id"].(string)
}

// 验收 ③:换源三层出结果 + 观看记录续播。
func TestTVBoxSwitchAndResume(t *testing.T) {
	e := newEnv(t)
	e.subscribe(e.base + "/config/plain.json")
	src := e.key("cms1")
	var d map[string]any
	json.Unmarshal(e.call("source.detail", map[string]any{"server_id": src, "item_id": "102"}), &d)
	var cands []struct {
		Layer    int            `json:"layer"`
		ServerID string         `json:"server_id"`
		Tier     int            `json:"tier"`
		Item     map[string]any `json:"item"`
		LineName string         `json:"line_name"`
	}
	json.Unmarshal(e.call("source.switchCandidates", map[string]any{"server_id": src, "title": "山河故事", "year": 2021, "episode_index": 11, "line_id": "星空线路", "item": d}), &cands)
	var layer1, same, junk bool
	for _, c := range cands {
		if c.Layer == 1 && c.LineName == "极速线路" {
			t.Fatal("极速线路只有 10 集,不该当第 11 集的候选(D464)")
		}
		if c.Layer == 1 && c.LineName == "官源线路" {
			t.Fatal("官源线路只有 3 集,不该当候选")
		}
		if c.Layer == 1 {
			layer1 = true
		}
		if c.Layer == 2 && c.ServerID == e.key("cmsb") && c.Item["title"] == "山河故事" && c.Tier == 0 {
			same = true
		}
		if c.Item["title"] == "山河故事 解说" && c.Tier != 2 {
			t.Fatal("解说应折叠到「不像」(D259)")
		}
		if c.Item["title"] == "山河故事 解说" {
			junk = true
		}
	}
	if layer1 {
		t.Fatal("本源没有含第 11 集的其它线路,第 ① 层应为空")
	}
	if !same || !junk {
		t.Fatalf("第 ② 层应在另一个源找到「同一部」和折叠的解说: %+v", cands)
	}
	// 第 5 集在「极速线路」有:第 ① 层出候选
	json.Unmarshal(e.call("source.switchCandidates", map[string]any{"server_id": src, "title": "山河故事", "year": 2021, "episode_index": 5, "line_id": "星空线路", "item": d}), &cands)
	if len(cands) == 0 || cands[0].Layer != 1 || cands[0].LineName != "极速线路" {
		t.Fatalf("第 ① 层应排最前且是含第 5 集的极速线路: %+v", cands)
	}

	// 续播:第 3 集看到 100 秒 → 再点第 3 集从 100 秒接着放
	lines := d["lines"].([]any)
	ep3 := lines[0].(map[string]any)["episodes"].([]any)[2].(map[string]any)
	e.call("source.playItem", map[string]any{"server_id": src, "item": d, "line_id": "星空线路", "episode_id": ep3["id"]})
	rt := int64(1200 * history.TicksPerSec)
	e.last.rec.Candidate.RunTimeTicks = &rt
	history.Shared().Capture(history.CaptureOpts{ScopeKey: e.last.rec.Scope, Candidate: e.last.rec.Candidate, PositionTicks: 100 * history.TicksPerSec, Source: history.SourceInternal, WatchedThresholdPercent: 90, Force: true, SourceRef: e.last.rec.Ref})
	e.call("source.playItem", map[string]any{"server_id": src, "item": d, "line_id": "星空线路", "episode_id": ep3["id"]})
	if e.last.resume < 99 || e.last.resume > 101 {
		t.Fatalf("应从 100 秒续播,实际 %.1f", e.last.resume)
	}
	var cont []map[string]any
	json.Unmarshal(e.call("source.continueWatching", map[string]any{"server_id": src}), &cont)
	if len(cont) != 1 {
		t.Fatalf("源首页「继续观看」应有这一部: %v", cont)
	}
	// 再次打开默认上次的线路(D343)
	json.Unmarshal(e.call("source.detail", map[string]any{"server_id": src, "item_id": "102"}), &d)
	if d["lastLine"] != "星空线路" {
		t.Fatalf("应记住上次的线路: %v", d["lastLine"])
	}
}

func TestTVBoxAggregateSearch(t *testing.T) {
	e := newEnv(t)
	e.subscribe(e.base + "/config/plain.json")
	var rows []struct {
		ServerID string           `json:"server_id"`
		Items    []map[string]any `json:"items"`
		Error    *string          `json:"error"`
	}
	json.Unmarshal(e.call("source.aggregateSearch", map[string]any{"query": "山河"}), &rows)
	got := map[string]int{}
	for _, r := range rows {
		got[r.ServerID] = len(r.Items)
		if len(r.Items) > 8 {
			t.Fatal("每源最多 8 条(D258)")
		}
	}
	if got[e.key("cms1")] == 0 || got[e.key("cmsb")] == 0 {
		t.Fatalf("允许聚合的源应出现在聚合搜索里: %v", got)
	}
	if _, ok := got[e.key("t4")]; ok {
		t.Fatal("searchable=0 的源默认不进聚合(D235)")
	}
	// 关掉「允许聚合」后不再出现
	e.call("source.setAggregate", map[string]any{"server_id": e.key("cmsb"), "allow": false})
	json.Unmarshal(e.call("source.aggregateSearch", map[string]any{"query": "山河"}), &rows)
	for _, r := range rows {
		if r.ServerID == e.key("cmsb") {
			t.Fatal("关了「允许聚合」的源不该进聚合搜索")
		}
	}
}

func TestTVBoxConfigDecoding(t *testing.T) {
	e := newEnv(t)
	for _, u := range []string{"/config/json5.json", "/config/b64.txt", "/config/cover.jpg", "/config/cbc.txt", "/config/ecb.txt;pk;pk-secret"} {
		var cs struct {
			Sources []draftT `json:"sources"`
		}
		json.Unmarshal(e.call("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": e.base + u}}), &cs)
		if len(cs.Sources) != 6 {
			t.Errorf("%s:应解出 6 个源,得到 %d", u, len(cs.Sources))
		}
	}
	// 多仓:先列仓让用户勾,勾了再拆源,每个仓一组(D346)
	var mr struct {
		Sources []draftT            `json:"sources"`
		Repos   []map[string]string `json:"repos"`
	}
	json.Unmarshal(e.call("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": e.base + "/config/multi.json"}}), &mr)
	if len(mr.Repos) != 2 || len(mr.Sources) != 0 {
		t.Fatalf("多仓应先列出 2 个仓: %+v", mr)
	}
	json.Unmarshal(e.call("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": e.base + "/config/multi.json"}, "repos": []any{mr.Repos[0]["id"], mr.Repos[1]["id"]}}), &mr)
	groups := map[string]bool{}
	for _, s := range mr.Sources {
		groups[s.Group] = true
	}
	if len(mr.Sources) != 12 || len(groups) != 2 {
		t.Fatalf("勾了两个仓应得 12 个源、2 个分组: %d 个源 %d 组", len(mr.Sources), len(groups))
	}
	// 解不出来要说卡在哪
	_, err := e.try("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": e.base + "/config/bad.txt"}})
	if err == nil || !strings.Contains(err.Error(), "不是 TVBox 格式") {
		t.Fatalf("坏配置应报 parseFailed 并写明原因: %v", err)
	}
}

func TestDrpyRuleAsSource(t *testing.T) {
	e := newEnv(t)
	var cs struct {
		Sources []map[string]any `json:"sources"`
	}
	json.Unmarshal(e.call("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "drpy-rule", "form": map[string]any{"rule": fakevod.DrpyRule(e.base)}}), &cs)
	if len(cs.Sources) != 1 {
		t.Fatalf("一条 drpy 规则就是一个源(D120): %+v", cs)
	}
	e.call("source.addSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "drpy-rule", "sources": []any{cs.Sources[0]}})
	key := "plugin:linplayer/tvbox/" + cs.Sources[0]["id"].(string)
	e.browse(t, key)
}

// 资源站套了「JS 写 cookie 再刷新」的防护:插件抠出 cookie 带上重试一次,不当成「不是 JSON」。
func TestTVBoxJSCookieGuard(t *testing.T) {
	e := newEnv(t)
	ds := e.subscribe(e.base + "/config/jsck.json")
	if len(ds) != 1 {
		t.Fatalf("配置里就一个站: %+v", ds)
	}
	e.browse(t, "plugin:linplayer/tvbox/"+ds[0].ID)
}

// 配置托管在按 UA 分流的中转上:拉配置要用 okhttp 的 UA(TVBox 系客户端都这样),不然拿到的是网页。
func TestTVBoxConfigOkhttpUA(t *testing.T) {
	e := newEnv(t)
	if ds := e.subscribe(e.base + "/config/okhttp.json"); len(ds) == 0 {
		t.Fatal("配置没读出源")
	}
}
