package datasource

// 聚合搜索(按源分行,谁先返回谁先显示,D258 D262)、换源三层(D232~D236 D259 D261 D525)、
// 一键检测全部源(D385)。三者都只包括「允许聚合」的服务器/源(D235)。

import (
	"context"
	"encoding/json"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/plugin"
)

const (
	aggPerSource    = 8               // 聚合搜索每源展示条数(D258)
	aggConcurrency  = 8               // 宿主批量调用并发上限(D234 D385)
	switchTimeout   = 8 * time.Second // 换源单源超时(D234)
	searchTimeout   = 20 * time.Second
	checkAllTimeout = 30 * time.Second
)

var embyClient *emby.Client

// SearchRow 聚合搜索的一行(一个源)。
type SearchRow struct {
	ServerID   string      `json:"server_id"`
	ServerName string      `json:"server_name"`
	Kind       string      `json:"kind"` // emby | plugin
	Items      []Item      `json:"items,omitempty"`
	EmbyItems  []emby.Item `json:"emby_items,omitempty"`
	Error      *string     `json:"error,omitempty"`
	MS         int64       `json:"ms"`
}

func sessionOf(c *config.AppConfig, acc config.Account) *emby.Session {
	// 必须走生效线路,见 aggregate.sessionOf 的注释
	return &emby.Session{Server: acc.ActiveLineURL(), Token: acc.Token, UserID: acc.UserID, DeviceID: c.DeviceID}
}

func aggregateTargets(c *config.AppConfig) []config.Account {
	var out []config.Account
	for _, acc := range c.AccountList {
		if !acc.AllowAggregate() {
			continue
		}
		if ps := acc.PluginSource(); ps != nil {
			if ps.Unavailable != "" || !plugin.Default().Has(ps.Plugin, "dataSource.search") {
				continue
			}
			out = append(out, acc)
			continue
		}
		if !acc.IsFileBrowse() {
			out = append(out, acc)
		}
	}
	return out
}

// eachLimited 并发上限 n 地跑。
func eachLimited[T any](xs []T, n int, f func(T)) {
	sem := make(chan struct{}, n)
	var wg sync.WaitGroup
	for _, x := range xs {
		wg.Add(1)
		sem <- struct{}{}
		go func(x T) {
			defer wg.Done()
			defer func() { <-sem }()
			f(x)
		}(x)
	}
	wg.Wait()
}

// cmdAggregateSearch 按回车才发(D258);每源一行,返回一行就 partial 推一行,最后回全部行。
func cmdAggregateSearch(ctx context.Context, seq int64, a map[string]any) (any, error) {
	q := strings.TrimSpace(str(a, "query"))
	if q == "" {
		return []SearchRow{}, nil
	}
	c := config.Current()
	var mu sync.Mutex
	rows := []SearchRow{}
	eachLimited(aggregateTargets(c), aggConcurrency, func(acc config.Account) {
		row := searchOne(ctx, c, acc, q, aggPerSource)
		if row == nil {
			return
		}
		mu.Lock()
		rows = append(rows, *row)
		mu.Unlock()
		bus.Partial(seq, row)
	})
	return rows, nil
}

// searchOne 在一个源里搜;没结果返回 nil(整行不出),失败带 error(没搜成 ≠ 没有)。
func searchOne(ctx context.Context, c *config.AppConfig, acc config.Account, q string, limit int) *SearchRow {
	start := time.Now()
	row := &SearchRow{ServerID: acc.Server, ServerName: acc.DisplayName()}
	if ps := acc.PluginSource(); ps != nil {
		row.Kind = "plugin"
		sctx, cancel := context.WithTimeout(ctx, searchTimeout)
		defer cancel()
		p, err := pageVerb(sctx, acc.Server, "search", "search.aggregate."+acc.Server, map[string]any{"keyword": q})
		row.MS = time.Since(start).Milliseconds()
		if err != nil {
			msg := err.Error()
			if be, ok := err.(*bus.Err); ok {
				msg = be.Msg
			}
			row.Error = &msg
			return row
		}
		if len(p.Items) == 0 {
			return nil
		}
		if len(p.Items) > limit {
			p.Items = p.Items[:limit]
		}
		row.Items = p.Items
		return row
	}
	row.Kind = "emby"
	sctx, cancel := context.WithTimeout(ctx, searchTimeout)
	defer cancel()
	items, err := embyClient.Search(sctx, sessionOf(c, acc), q, []string{"Movie", "Series"}, 0, "")
	row.MS = time.Since(start).Milliseconds()
	if err != nil {
		msg := err.Error()
		row.Error = &msg
		return row
	}
	if len(items) == 0 {
		return nil
	}
	if len(items) > limit {
		items = items[:limit]
	}
	row.EmbyItems = items
	return row
}

// ---------------------------------------------------------------- 换源

// Candidate 换源的一个候选。
type Candidate struct {
	Layer      int        `json:"layer"` // 1 本源其它线路 / 2 数据源 / 3 Emby(D233)
	ServerID   string     `json:"server_id"`
	ServerName string     `json:"server_name"`
	Kind       string     `json:"kind"`
	Tier       int        `json:"tier"` // 0 同一部 / 1 可能是 / 2 不像(折叠到底部,D259 D261)
	MS         int64      `json:"ms"`   // 该源接口响应耗时:档内按它排
	Item       Item       `json:"item,omitempty"`
	EmbyItem   *emby.Item `json:"emby_item,omitempty"`
	LineID     string     `json:"line_id,omitempty"` // 第 1 层:同源另一条线路
	LineName   string     `json:"line_name,omitempty"`
	EpisodeID  string     `json:"episode_id,omitempty"`
}

// cmdSwitchCandidates 换源三层,默认 ①本源其它线路 ②允许聚合的数据源 ③允许聚合的 Emby;顺序可调。
// 并发 8、单源 8 秒、边出边 partial;返回按「层 → 档 → 耗时」排好的全表。
func cmdSwitchCandidates(ctx context.Context, seq int64, a map[string]any) (any, error) {
	sid := str(a, "server_id")
	title := strings.TrimSpace(str(a, "title"))
	year, _ := a["year"].(float64)
	epIdx, _ := a["episode_index"].(float64)
	curLine := str(a, "line_id")
	order := []int{1, 2, 3}
	if o, ok := a["layer_order"].([]any); ok && len(o) == 3 {
		order = order[:0]
		for _, x := range o {
			if f, ok := x.(float64); ok {
				order = append(order, int(f))
			}
		}
	}
	rank := map[int]int{}
	for i, l := range order {
		rank[l] = i
	}
	var mu sync.Mutex
	out := []Candidate{}
	emit := func(cs ...Candidate) {
		if len(cs) == 0 {
			return
		}
		mu.Lock()
		out = append(out, cs...)
		mu.Unlock()
		bus.Partial(seq, cs)
	}

	// ① 当前源的其它线路:含这一集的线路(按集序号对应,D464)
	if item, ok := a["item"].(map[string]any); ok {
		emit(otherLines(sid, item, curLine, int(epIdx))...)
	}

	c := config.Current()
	var targets []config.Account
	for _, acc := range aggregateTargets(c) {
		if acc.Server != sid {
			targets = append(targets, acc)
		}
	}
	eachLimited(targets, aggConcurrency, func(acc config.Account) {
		sctx, cancel := context.WithTimeout(ctx, switchTimeout)
		defer cancel()
		row := searchOne(sctx, c, acc, title, 20)
		if row == nil || row.Error != nil {
			return
		}
		var cs []Candidate
		for _, it := range row.Items {
			cs = append(cs, Candidate{Layer: 2, ServerID: acc.Server, ServerName: acc.DisplayName(), Kind: "plugin", MS: row.MS,
				Item: it, Tier: tierOf(title, int(year), it.str("title"), yearOf(it))})
		}
		for i := range row.EmbyItems {
			e := row.EmbyItems[i]
			y := 0
			if e.Year != nil {
				y = int(*e.Year)
			}
			cs = append(cs, Candidate{Layer: 3, ServerID: acc.Server, ServerName: acc.DisplayName(), Kind: "emby", MS: row.MS,
				EmbyItem: &e, Tier: tierOf(title, int(year), e.Name, y)})
		}
		emit(cs...)
	})
	sort.SliceStable(out, func(i, j int) bool {
		x, y := out[i], out[j]
		if rank[x.Layer] != rank[y.Layer] {
			return rank[x.Layer] < rank[y.Layer]
		}
		if x.Tier != y.Tier {
			return x.Tier < y.Tier
		}
		return x.MS < y.MS
	})
	return out, nil
}

func yearOf(it Item) int {
	if y, ok := it["year"].(float64); ok {
		return int(y)
	}
	return 0
}

func otherLines(sid string, item map[string]any, cur string, epIdx int) []Candidate {
	var out []Candidate
	lines, _ := item["lines"].([]any)
	for _, l := range lines {
		lm, _ := l.(map[string]any)
		id, _ := lm["id"].(string)
		if lm == nil || id == cur {
			continue
		}
		name, _ := lm["name"].(string)
		eps, _ := lm["episodes"].([]any)
		epID := ""
		for k, e := range eps {
			em, _ := e.(map[string]any)
			idx := 0
			if f, ok := em["index"].(float64); ok {
				idx = int(f)
			} else if n, ok := EpisodeIndex(asString(em["name"])); ok {
				idx = n
			}
			if epIdx <= 0 && k == 0 || idx == epIdx {
				epID, _ = em["id"].(string)
				break
			}
		}
		if epID == "" {
			continue // 这条线路没有这一集:不当候选(D464)
		}
		out = append(out, Candidate{Layer: 1, ServerID: sid, Kind: "line", Tier: 0, Item: Item(item), LineID: id, LineName: name, EpisodeID: epID})
	}
	return out
}

func asString(v any) string { s, _ := v.(string); return s }

// tierOf 相似度三档:片名完全一致 > 年份一致 > 包含关键词;明显不对的(解说/预告)折叠(D259)。
func tierOf(want string, wantYear int, got string, gotYear int) int {
	w, g := normTitle(want), normTitle(got)
	junk := []string{"解说", "预告", "花絮", "速看", "片段", "reaction"}
	for _, j := range junk {
		if strings.Contains(strings.ToLower(got), j) && !strings.Contains(strings.ToLower(want), j) {
			return 2
		}
	}
	switch {
	case w == g && (wantYear == 0 || gotYear == 0 || wantYear == gotYear):
		return 0
	case w == g || (strings.Contains(g, w) || strings.Contains(w, g)) && (wantYear == 0 || gotYear == 0 || wantYear == gotYear):
		return 1
	}
	return 2
}

func normTitle(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// ---------------------------------------------------------------- 一键检测

// CheckResult 一个源的检测结果:拿到列表即算活,不测播放地址(D385)。
type CheckResult struct {
	ServerID string  `json:"server_id"`
	OK       bool    `json:"ok"`
	MS       int64   `json:"ms"`
	Error    *string `json:"error,omitempty"`
}

func cmdCheckAll(ctx context.Context, seq int64, a map[string]any) (any, error) {
	c := config.Current()
	var accs []config.Account
	group := str(a, "group")
	for _, acc := range c.PluginSources("") {
		if ps := acc.PluginSource(); ps.Unavailable == "" && (group == "" || ps.Group == group) {
			accs = append(accs, acc)
		}
	}
	var mu sync.Mutex
	out := []CheckResult{}
	eachLimited(accs, aggConcurrency, func(acc config.Account) {
		sctx, cancel := context.WithTimeout(ctx, checkAllTimeout)
		defer cancel()
		start := time.Now()
		err := probe(sctx, acc.Server)
		r := CheckResult{ServerID: acc.Server, OK: err == nil, MS: time.Since(start).Milliseconds()}
		if err != nil {
			msg := err.Error()
			if be, ok := err.(*bus.Err); ok {
				msg = be.Msg
			}
			r.Error = &msg
		}
		markFailed(acc.Server, err != nil)
		mu.Lock()
		out = append(out, r)
		mu.Unlock()
		bus.Partial(seq, r)
	})
	return out, nil
}

// probe 调 home;没有 home 调第一个分类(D385)。
func probe(ctx context.Context, sid string) error {
	_, pid, err := resolve(sid)
	if err != nil {
		return err
	}
	if plugin.Default().Has(pid, "dataSource.home") {
		raw, err := callVerb(ctx, sid, "home")
		if err != nil {
			return err
		}
		var h struct {
			Categories []struct {
				ID string `json:"id"`
			} `json:"categories"`
			Recommended []json.RawMessage `json:"recommended"`
		}
		_ = json.Unmarshal(raw, &h)
		if len(h.Recommended) > 0 || !plugin.Default().Has(pid, "dataSource.category") || len(h.Categories) == 0 {
			return nil
		}
		_, err = callVerb(ctx, sid, "category", map[string]any{"categoryId": h.Categories[0].ID, "filters": map[string]any{}})
		return err
	}
	return bus.NewErr(bus.EUnsupported, "这个源没有首页,无法检测")
}
