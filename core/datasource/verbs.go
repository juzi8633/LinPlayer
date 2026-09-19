package datasource

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"

	"linplayer/core/account"
	"linplayer/core/blocklist"
	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/history"
	"linplayer/core/player"
	"linplayer/core/plugin"
	"linplayer/core/plugin/rt"
)

// Item 统一结构条目(plugin-sdk.d.ts MediaItem)。宿主只看这几个字段,其余原样透传。
type Item map[string]any

func (it Item) str(k string) string { s, _ := it[k].(string); return s }

func (it Item) BlockID() string         { return it.str("id") }
func (it Item) BlockName() string       { return it.str("title") }
func (it Item) BlockSeriesID() string   { return "" }
func (it Item) BlockSeriesName() string { return "" }
func (it Item) HasSeriesName() bool     { return false }

// Page 不透明游标翻页(D255)。
type Page struct {
	Items []Item `json:"items"`
	Next  string `json:"next,omitempty"`
}

func register() {
	bus.Register("source.caps", cmdCaps)
	bus.Register("source.home", cmdHome)
	bus.Register("source.category", cmdCategory)
	bus.Register("source.searchItems", cmdSearchItems)
	bus.Register("source.person", cmdPerson)
	bus.Register("source.detail", cmdDetail)
	bus.Register("source.playItem", cmdPlayItem)
	bus.Register("source.continueWatching", cmdContinue)
}

func str(a map[string]any, k string) string { s, _ := a[k].(string); return s }

func serverArg(a map[string]any) (string, error) {
	s := str(a, "server_id")
	if s == "" {
		return "", bus.NewErr(bus.EInvalid, "缺少 server_id")
	}
	return s, nil
}

// cmdCaps 这个源实现了哪些可选动词;没实现的入口不显示(D226 D260)。
func cmdCaps(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	_, pid, err := resolve(sid)
	if err != nil {
		return nil, err
	}
	h := plugin.Default()
	return map[string]bool{
		"home": h.Has(pid, "dataSource.home"), "category": h.Has(pid, "dataSource.category"),
		"search": h.Has(pid, "dataSource.search"), "person": h.Has(pid, "dataSource.person"),
	}, nil
}

func cmdHome(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	raw, err := callVerb(ctx, sid, "home")
	markFailed(sid, err != nil)
	if err != nil {
		return nil, err
	}
	var out struct {
		Categories  []map[string]any `json:"categories"`
		Recommended []Item           `json:"recommended"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, bus.NewErr(bus.EUpstream, "数据源首页返回的结构不对")
	}
	cats := []map[string]any{}
	for _, c := range out.Categories {
		id, _ := c["id"].(string)
		// 数据源分类可屏蔽,复用媒体库屏蔽与集中解除列表(D516)
		if blocklist.IsBlockedID(CategoryBlockID(sid, id)) {
			continue
		}
		cats = append(cats, c)
	}
	rec := finishList(ctx, sid, "source.home", out.Recommended)
	return map[string]any{"categories": cats, "recommended": rec}, nil
}

// CategoryBlockID 数据源分类在屏蔽名单里的 id。
func CategoryBlockID(serverID, categoryID string) string { return "cat:" + serverID + "#" + categoryID }

func cmdCategory(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	filters := map[string][]string{}
	if f, ok := a["filters"].(map[string]any); ok {
		for k, v := range f {
			switch x := v.(type) {
			case []any:
				for _, s := range x {
					if s, ok := s.(string); ok {
						filters[k] = append(filters[k], s)
					}
				}
			case string:
				filters[k] = []string{x}
			}
		}
	}
	req := map[string]any{"categoryId": str(a, "category_id"), "filters": filters}
	if c := str(a, "cursor"); c != "" {
		req["cursor"] = c
	}
	return pageVerb(ctx, sid, "category", "source.category", req)
}

func cmdSearchItems(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	req := map[string]any{"keyword": str(a, "keyword")}
	if c := str(a, "cursor"); c != "" {
		req["cursor"] = c
	}
	return pageVerb(ctx, sid, "search", "search.results", req)
}

func cmdPerson(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	req := map[string]any{"name": str(a, "name")}
	if v := str(a, "id"); v != "" {
		req["id"] = v
	}
	if c := str(a, "cursor"); c != "" {
		req["cursor"] = c
	}
	return pageVerb(ctx, sid, "person", "source.category", req)
}

func pageVerb(ctx context.Context, sid, verb, list string, req map[string]any) (*Page, error) {
	raw, err := callVerb(ctx, sid, verb, req)
	if err != nil {
		return nil, err
	}
	var p Page
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, bus.NewErr(bus.EUpstream, "数据源返回的列表结构不对")
	}
	p.Items = finishList(ctx, sid, list, p.Items)
	return &p, nil
}

// finishList 官方列表出场前:补来源 → 插件列表变换钩子 → 宿主屏蔽(D278 D515),顺带登记图片 origin。
func finishList(ctx context.Context, sid, list string, items []Item) []Item {
	for _, it := range items {
		if it.str("source") == "" && sid != "" {
			it["source"] = sid
		}
	}
	items = RunListHooks(ctx, list, items)
	out := make([]Item, 0, len(items))
	for _, it := range items {
		if blocklist.IsBlocked(it) {
			continue
		}
		allowImages(it)
		out = append(out, it)
	}
	return out
}

// RunListHooks 按顺序串联各插件的列表变换钩子;单个出错或超过 300ms 墙钟就跳过并计连错(D281)。
// ponytail: 顺序按插件 id;钩子顺序的用户设置(D280)随阶段 ② 的接管位页做。
func RunListHooks(ctx context.Context, list string, items []Item) []Item {
	h := plugin.Default()
	for _, id := range h.Enabled() {
		m, err := h.Manifest(id)
		if err != nil || m.Contributes.Hooks == nil || m.Contributes.Hooks.ListTransform == nil {
			continue
		}
		if !listMatches(m.Contributes.Hooks.ListTransform.Lists, list) {
			continue
		}
		hctx, cancel := context.WithTimeout(ctx, rt.BudgetHook)
		raw, err := h.Call(hctx, id, rt.BudgetHook, "hooks.listTransform", []any{map[string]any{"name": list, "items": items}}, map[string]any{})
		timedOut := hctx.Err() != nil
		cancel()
		if err != nil {
			if timedOut {
				h.NoteHookTimeout(id)
			}
			continue
		}
		var next []Item
		if json.Unmarshal(raw, &next) == nil && next != nil {
			items = next
		}
	}
	return items
}

func listMatches(lists []string, name string) bool {
	for _, l := range lists {
		if l == name || (strings.HasSuffix(l, "*") && strings.HasPrefix(name, strings.TrimSuffix(l, "*"))) {
			return true
		}
	}
	return false
}

// allowImages 条目图片走官方图片通道:登记 origin 与请求头(D87)。
func allowImages(it Item) {
	for _, k := range []string{"poster", "backdrop"} {
		ref, ok := it[k].(map[string]any)
		if !ok {
			continue
		}
		u, _ := ref["url"].(string)
		pu, err := url.Parse(u)
		if err != nil || (pu.Scheme != "http" && pu.Scheme != "https") {
			continue
		}
		hdr := http.Header{}
		if hs, ok := ref["headers"].(map[string]any); ok {
			for hk, hv := range hs {
				if s, ok := hv.(string); ok {
					hdr.Set(hk, s)
				}
			}
		}
		if hdr.Get("User-Agent") == "" {
			hdr.Set("User-Agent", rt.BrowserUA)
		}
		account.AllowImageOrigin(pu.Scheme+"://"+pu.Host, hdr)
	}
}

// ---------------------------------------------------------------- 详情

func cmdDetail(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	raw, err := callVerb(ctx, sid, "detail", str(a, "item_id"))
	if err != nil {
		return nil, err
	}
	var d Item
	if err := json.Unmarshal(raw, &d); err != nil || d == nil {
		return nil, bus.NewErr(bus.EUpstream, "数据源详情返回的结构不对")
	}
	if d.str("source") == "" {
		d["source"] = sid
	}
	if ov := d.str("overview"); ov != "" {
		d["overview"] = StripHTML(ov)
	}
	allowImages(d)
	normalizeLines(d["lines"])
	if seasons, ok := d["seasons"].([]any); ok {
		for _, s := range seasons {
			if sm, ok := s.(map[string]any); ok {
				normalizeLines(sm["lines"])
			}
		}
	}
	// 再次打开默认上次的线路:按剧记线路名,线路没了退回第一条(D343)
	if last := lastLineName(sid, d.str("id")); last != "" {
		d["lastLine"] = last
	}
	return d, nil
}

// normalizeLines 给每集补 index(没给就从名字解析)和 label(统一「第 N 集」,D461)。
func normalizeLines(v any) {
	lines, _ := v.([]any)
	for _, l := range lines {
		lm, _ := l.(map[string]any)
		eps, _ := lm["episodes"].([]any)
		for _, e := range eps {
			em, _ := e.(map[string]any)
			if em == nil {
				continue
			}
			name, _ := em["name"].(string)
			idx, has := em["index"].(float64)
			if !has {
				if n, ok := EpisodeIndex(name); ok {
					em["index"] = n
					idx, has = float64(n), true
				}
			}
			em["label"] = EpisodeLabel(name, int(idx), has)
		}
	}
}

var (
	dateEp   = regexp.MustCompile(`(19|20)\d{2}[-./]?(0[1-9]|1[0-2])[-./]?(0[1-9]|[12]\d|3[01])`)
	numEpPat = []*regexp.Regexp{
		regexp.MustCompile(`第\s*(\d{1,4})\s*[集话話回]`),
		regexp.MustCompile(`(?i)^\s*(?:EP?|第)\s*(\d{1,4})\b`),
		regexp.MustCompile(`^\s*(\d{1,4})\s*(?:集|话|話|回)?\s*$`),
		regexp.MustCompile(`(?i)\bE(\d{1,4})\b`),
	}
)

// EpisodeIndex 从集名解析序号。日期型(综艺期数)与无序号的(「正片」「花絮」)返回 false。
func EpisodeIndex(name string) (int, bool) {
	if dateEp.MatchString(name) {
		return 0, false
	}
	for _, re := range numEpPat {
		if m := re.FindStringSubmatch(name); m != nil {
			n, err := strconv.Atoi(m[1])
			if err == nil && n > 0 {
				return n, true
			}
		}
	}
	return 0, false
}

// EpisodeLabel 选集格显示名:有序号显示「第 N 集」,日期型与无序号的原样显示(D461)。
func EpisodeLabel(name string, idx int, has bool) string {
	if dateEp.MatchString(name) || !has || idx <= 0 {
		return name
	}
	return "第 " + strconv.Itoa(idx) + " 集"
}

// StripHTML 简介去标签转纯文本,保留换行(D462)。
func StripHTML(s string) string {
	if !strings.ContainsAny(s, "<&") {
		return strings.TrimSpace(s)
	}
	s = regexp.MustCompile(`(?i)<br\s*/?>|</p>|</div>|</li>`).ReplaceAllString(s, "\n")
	doc, err := goquery.NewDocumentFromReader(strings.NewReader("<body>" + s + "</body>"))
	if err != nil {
		return s
	}
	lines := strings.Split(doc.Find("body").Text(), "\n")
	var out []string
	for _, l := range lines {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return strings.Join(out, "\n")
}

// ---------------------------------------------------------------- 播放

// sourceRef 观看记录里留的数据源快照(history.Record.SourceRef)。
type sourceRef struct {
	Item      Item   `json:"item"`
	LineID    string `json:"lineId"`
	LineName  string `json:"lineName,omitempty"`
	EpisodeID string `json:"episodeId"`
	EpIndex   int    `json:"episodeIndex,omitempty"`
	EpName    string `json:"episodeName,omitempty"`
}

// playFn 起播;测试换成记录参数的假实现(门禁里没有 mpv)。
var playFn = player.PlaySourceRecorded

// Scope 数据源在观看记录里的 scope:开放键 + 空用户(数据源没有用户)。
func Scope(serverID string) string { return history.ScopeKey(serverID, "") }

func cmdPlayItem(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	item, _ := a["item"].(map[string]any)
	lineID, epID := str(a, "line_id"), str(a, "episode_id")
	if item == nil || epID == "" {
		return nil, bus.NewErr(bus.EInvalid, "缺少 item / episode_id")
	}
	req := map[string]any{"item": item, "lineId": lineID, "episodeId": epID}
	raw, err := callVerb(ctx, sid, "play", req)
	if err != nil {
		return nil, err
	}
	var pr struct {
		URL       string            `json:"url"`
		Headers   map[string]string `json:"headers"`
		Subtitles []struct {
			URL   string `json:"url"`
			Title string `json:"title"`
			Lang  string `json:"lang"`
		} `json:"subtitles"`
		Parser string `json:"parser"`
	}
	if err := json.Unmarshal(raw, &pr); err != nil || pr.URL == "" {
		return nil, bus.NewErr(bus.EUpstream, "数据源没有给出可播地址")
	}
	ref := sourceRef{Item: Item(item), LineID: lineID, EpisodeID: epID}
	ep := findEpisode(item, lineID, epID)
	if ep != nil {
		ref.EpName, _ = ep["name"].(string)
		if f, ok := ep["index"].(float64); ok {
			ref.EpIndex = int(f)
		}
	}
	ref.LineName = lineName(item, lineID)
	delete(ref.Item, "lines") // 快照不存整张线路表
	delete(ref.Item, "seasons")
	refB, _ := json.Marshal(ref)
	cand := candidateOf(Item(item), ref)
	resume, hasResume := a["resume_secs"].(float64)
	if !hasResume {
		resume = resumeOf(sid, cand)
	}
	subs := make([]emby.ExternalSub, 0, len(pr.Subtitles))
	for _, s := range pr.Subtitles {
		t := s.Title
		if t == "" {
			t = s.Lang
		}
		subs = append(subs, emby.ExternalSub{URL: s.URL, Title: t})
	}
	rememberLine(sid, Item(item).str("id"), ref.LineName)
	title := Item(item).str("title")
	if ref.EpName != "" {
		title += " " + ref.EpName
	}
	out, err := playFn(pr.URL, title, resume, pr.Headers, "", subs,
		&player.SourceHistory{Scope: Scope(sid), Candidate: cand, Ref: refB})
	if err != nil {
		return nil, err
	}
	out["url"] = pr.URL
	out["parser"] = pr.Parser
	return out, nil
}

func findEpisode(item map[string]any, lineID, epID string) map[string]any {
	var scan func(v any) map[string]any
	scan = func(v any) map[string]any {
		lines, _ := v.([]any)
		for _, l := range lines {
			lm, _ := l.(map[string]any)
			if lm == nil || (lineID != "" && lm["id"] != lineID) {
				continue
			}
			eps, _ := lm["episodes"].([]any)
			for _, e := range eps {
				if em, _ := e.(map[string]any); em != nil && em["id"] == epID {
					return em
				}
			}
		}
		return nil
	}
	if e := scan(item["lines"]); e != nil {
		return e
	}
	seasons, _ := item["seasons"].([]any)
	for _, s := range seasons {
		if sm, _ := s.(map[string]any); sm != nil {
			if e := scan(sm["lines"]); e != nil {
				return e
			}
		}
	}
	return nil
}

func lineName(item map[string]any, lineID string) string {
	lines, _ := item["lines"].([]any)
	for _, l := range lines {
		if lm, _ := l.(map[string]any); lm != nil && lm["id"] == lineID {
			n, _ := lm["name"].(string)
			return n
		}
	}
	return ""
}

// candidateOf 统一结构 → 观看记录候选。有集序号按剧集记,否则按电影记。
func candidateOf(it Item, ref sourceRef) history.Candidate {
	c := history.Candidate{ID: it.str("id"), Name: it.str("title"), Type: "Movie"}
	if y, ok := it["year"].(float64); ok && y > 0 {
		v := int64(y)
		c.Year = &v
	}
	if ref.EpIndex > 0 || (ref.EpName != "" && countEpisodes(it) > 1) {
		c.Type = "Episode"
		series, sid := it.str("title"), it.str("id")
		c.SeriesName, c.SeriesID = &series, &sid
		c.ID = sid + "#" + ref.EpisodeID
		c.Name = ref.EpName
		if ref.EpIndex > 0 {
			e, s := int64(ref.EpIndex), int64(1)
			c.EpisodeNo, c.SeasonNo = &e, &s
		}
	}
	if ext, ok := it["externalIds"].(map[string]any); ok {
		if t, ok := ext["tmdb"].(string); ok && t != "" && c.Type == "Movie" {
			c.TmdbID = &t
		}
	}
	return c
}

func countEpisodes(it Item) int {
	if n, ok := it["episodeCount"].(float64); ok {
		return int(n)
	}
	return 2 // 不知道集数时按剧集处理,宁可多记一个集号
}

// resumeOf 同一条目的续播位置:先按条目 id,对不上按片名+年份+集序号找回(D55)。
func resumeOf(sid string, c history.Candidate) float64 {
	recs := history.Shared().LoadScope(Scope(sid))
	fp, ok := history.FingerprintOfCandidate(c, nil)
	for _, r := range recs {
		hit := r.LastEmbyItemID != nil && *r.LastEmbyItemID == c.ID
		if !hit && ok && r.CanonicalKey == fp.CanonicalKey {
			hit = true
		}
		if hit && !r.Played {
			return float64(r.LastPositionTicks) / float64(history.TicksPerSec)
		}
	}
	return 0
}

// cmdContinue 该源的「继续观看」(源首页顶部,D175)。
func cmdContinue(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	return continueOf(sid), nil
}

// ContinueEntry 继续观看一项。
type ContinueEntry struct {
	RecordID  string          `json:"record_id"`
	ServerID  string          `json:"server_id"`
	Ref       json.RawMessage `json:"ref"`
	Position  float64         `json:"position_secs"`
	Duration  float64         `json:"duration_secs"`
	UpdatedAt int64           `json:"updated_at"`
}

func continueOf(sid string) []ContinueEntry {
	recs := history.Shared().LoadScope(Scope(sid))
	seen := map[string]bool{}
	out := []ContinueEntry{}
	// 同一部剧只出最近那一集
	for _, r := range sortByRecent(recs) {
		if r.Played || r.SourceRef == nil || r.LastPositionTicks <= 0 {
			continue
		}
		var ref sourceRef
		if json.Unmarshal(r.SourceRef, &ref) != nil {
			continue
		}
		key := ref.Item.str("id")
		if seen[key] {
			continue
		}
		seen[key] = true
		e := ContinueEntry{RecordID: r.RecordID, ServerID: sid, Ref: r.SourceRef, UpdatedAt: r.LastPlayedAt,
			Position: float64(r.LastPositionTicks) / float64(history.TicksPerSec)}
		if r.RunTimeTicks != nil {
			e.Duration = float64(*r.RunTimeTicks) / float64(history.TicksPerSec)
		}
		out = append(out, e)
	}
	return out
}

func sortByRecent(recs []history.Record) []history.Record {
	out := append([]history.Record(nil), recs...)
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].LastPlayedAt > out[j-1].LastPlayedAt; j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

// ---------------------------------------------------------------- 按剧记线路(D343)

func lastLineName(sid, itemID string) string {
	m := config.Current().PrefsOf().SourceLines
	return m[sid+"#"+itemID]
}

func rememberLine(sid, itemID, line string) {
	if line == "" || itemID == "" {
		return
	}
	cfgMu.Lock()
	defer cfgMu.Unlock()
	c := config.Current()
	p := c.PrefsOf()
	if p.SourceLines[sid+"#"+itemID] == line {
		return
	}
	if p.SourceLines == nil {
		p.SourceLines = map[string]string{}
	}
	p.SourceLines[sid+"#"+itemID] = line
	if c.SetPrefs(p) == nil {
		_ = c.Save()
	}
}

var _ = time.Second
