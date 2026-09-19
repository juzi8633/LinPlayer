package datasource

// 数据源收藏(D20 D326):数据源没有服务端收藏,宿主本地存条目快照;卸载插件、删订阅后照样保留(D333)。

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/history"
	"linplayer/core/paths"
)

type favEntry struct {
	ServerID string `json:"server_id"`
	Item     Item   `json:"item"`
	At       int64  `json:"at"`
}

var favMu sync.Mutex

func favFile() string { return filepath.Join(paths.Root(), "source_favorites.json") }

func loadFavs() []favEntry {
	var v []favEntry
	b, err := os.ReadFile(favFile())
	if err == nil {
		_ = json.Unmarshal(b, &v)
	}
	return v
}

func saveFavs(v []favEntry) error {
	b, _ := json.Marshal(v)
	tmp := favFile() + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, favFile())
}

func favKey(sid, id string) string { return sid + "\x00" + id }

func cmdSetFavorite(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	it, _ := a["item"].(map[string]any)
	fav, _ := a["favorite"].(bool)
	if it == nil {
		return nil, bus.NewErr(bus.EInvalid, "缺少 item")
	}
	id := Item(it).str("id")
	favMu.Lock()
	defer favMu.Unlock()
	list := loadFavs()
	kept := list[:0]
	for _, f := range list {
		if favKey(f.ServerID, f.Item.str("id")) != favKey(sid, id) {
			kept = append(kept, f)
		}
	}
	if fav {
		snap := Item{}
		for k, v := range it {
			if k != "lines" && k != "seasons" {
				snap[k] = v
			}
		}
		snap["source"] = sid
		kept = append(kept, favEntry{ServerID: sid, Item: snap, At: time.Now().Unix()})
	}
	if err := saveFavs(kept); err != nil {
		return nil, bus.NewErr(bus.EInternal, "收藏保存失败: %v", err)
	}
	return map[string]bool{"favorite": fav}, nil
}

func cmdIsFavorite(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, id := str(a, "server_id"), str(a, "item_id")
	favMu.Lock()
	defer favMu.Unlock()
	for _, f := range loadFavs() {
		if f.ServerID == sid && f.Item.str("id") == id {
			return true, nil
		}
	}
	return false, nil
}

// FavGroup 全部收藏页按来源分组的一组。
type FavGroup struct {
	ServerID   string `json:"server_id"`
	ServerName string `json:"server_name"`
	Removed    bool   `json:"removed"` // 来源已移除(D333)
	Items      []Item `json:"items"`
}

// cmdFavorites 数据源收藏;server_id 空 = 全部源(全局「全部收藏」里数据源那部分,D326)。
func cmdFavorites(ctx context.Context, _ int64, a map[string]any) (any, error) {
	only := str(a, "server_id")
	favMu.Lock()
	list := loadFavs()
	favMu.Unlock()
	sort.SliceStable(list, func(i, j int) bool { return list[i].At > list[j].At })
	c := config.Current()
	groups := []*FavGroup{}
	idx := map[string]*FavGroup{}
	for _, f := range list {
		if only != "" && f.ServerID != only {
			continue
		}
		g := idx[f.ServerID]
		if g == nil {
			g = &FavGroup{ServerID: f.ServerID, ServerName: f.ServerID, Items: []Item{}}
			if acc := c.Find(f.ServerID); acc != nil {
				g.ServerName = acc.DisplayName()
			} else {
				g.Removed = true
			}
			idx[f.ServerID] = g
			groups = append(groups, g)
		}
		allowImages(f.Item)
		g.Items = append(g.Items, f.Item)
	}
	return groups, nil
}

// HistoryEntry 全局观看历史里数据源的一条(D430 D431 D333)。
type HistoryEntry struct {
	ContinueEntry
	ServerName string `json:"server_name"`
	Removed    bool   `json:"removed"`
}

// cmdHistory 全部数据源的观看记录,按时间混排;同一部片在多个源都有时按「换源链路」合并取进度最远(D431)。
func cmdHistory(ctx context.Context, _ int64, a map[string]any) (any, error) {
	c := config.Current()
	all := history.Shared().LoadAll()
	var out []HistoryEntry
	for _, r := range sortByRecent(all) {
		if r.SourceRef == nil {
			continue
		}
		sid := history.ServerFromScope(r.ScopeKey)
		e := HistoryEntry{ContinueEntry: ContinueEntry{RecordID: r.RecordID, ServerID: sid, Ref: r.SourceRef, UpdatedAt: r.LastPlayedAt,
			Position: float64(r.LastPositionTicks) / float64(history.TicksPerSec)}}
		if r.RunTimeTicks != nil {
			e.Duration = float64(*r.RunTimeTicks) / float64(history.TicksPerSec)
		}
		if acc := c.Find(sid); acc != nil {
			e.ServerName = acc.DisplayName()
		} else {
			e.ServerName, e.Removed = sid, true
		}
		out = append(out, e)
	}
	return mergeLinked(out), nil
}

// ---------------------------------------------------------------- 换源链路(D236 D431)

type link struct{ From, To string } // 「服务器#条目 id」

func linkFile() string { return filepath.Join(paths.Root(), "source_links.json") }

// cmdLinkSwitch 用户从 A 换到 B 播放时记一条链路;历史合并只靠它,不靠片名猜(D431)。
func cmdLinkSwitch(ctx context.Context, _ int64, a map[string]any) (any, error) {
	from := str(a, "from_server") + "#" + str(a, "from_item")
	to := str(a, "to_server") + "#" + str(a, "to_item")
	favMu.Lock()
	defer favMu.Unlock()
	var ls []link
	if b, err := os.ReadFile(linkFile()); err == nil {
		_ = json.Unmarshal(b, &ls)
	}
	ls = append(ls, link{From: from, To: to})
	b, _ := json.Marshal(ls)
	if err := os.WriteFile(linkFile(), b, 0o644); err != nil {
		return nil, bus.NewErr(bus.EInternal, "保存失败: %v", err)
	}
	return nil, nil
}

// mergeLinked 有换源链路相连的记录合并成一条,取进度最远的(A 10%、B 30% → 显示 B)。
func mergeLinked(in []HistoryEntry) []HistoryEntry {
	var ls []link
	if b, err := os.ReadFile(linkFile()); err == nil {
		_ = json.Unmarshal(b, &ls)
	}
	parent := map[string]string{}
	var find func(string) string
	find = func(x string) string {
		for parent[x] != "" && parent[x] != x {
			x = parent[x]
		}
		return x
	}
	for _, l := range ls {
		a, b := find(l.From), find(l.To)
		if a != b {
			parent[a] = b
		}
	}
	best := map[string]int{}
	var out []HistoryEntry
	for _, e := range in {
		var ref sourceRef
		_ = json.Unmarshal(e.Ref, &ref)
		root := find(e.ServerID + "#" + ref.Item.str("id"))
		if i, ok := best[root]; ok {
			if progress(e) > progress(out[i]) {
				out[i] = e
			}
			continue
		}
		best[root] = len(out)
		out = append(out, e)
	}
	if out == nil {
		out = []HistoryEntry{}
	}
	return out
}

func progress(e HistoryEntry) float64 {
	if e.Duration <= 0 {
		return 0
	}
	return e.Position / e.Duration
}
