package plugin

/*
`NowPlaying`(`.d.ts` 第 20 节)—— 播放类事件的载荷。

☠ 这个形状是**定义源说了算**:`item: MediaItem` / `positionSec` / `durationSec`。
  上一版发的是 `itemId` / `position` / `duration`,和定义源三个字段名全对不上 ——
  照定义源写的插件读 `np.item.title` 当场 TypeError,读 `np.item.externalIds`
  拿到 undefined 就走「没有外部 id」那条降级路。同步插件的 scrobble 因此
  **一次都没真发出去过**,而用户能看到的唯一痕迹是插件日志里一句
  「这一条没有 TMDB / IMDb id」—— 把宿主的问题说成了片源的问题。

☠ 外部 id 必须真去取:Trakt 靠 tmdb/imdb 对条目,对不上就只能按片名搜,
  而片名搜在多语言库里经常搜错部。分集自己没刮到这些,要回头问它所属的剧。
*/

import (
	"context"
	"encoding/json"
	"strconv"
	"sync"
	"time"

	"linplayer/core/bus"
)

// npItem 一条已经取好的条目信息。按 itemID 记住,同一集不重复问服务器。
type npItem struct {
	item map[string]any
	at   time.Time
}

var (
	npMu    sync.Mutex
	npCache = map[string]npItem{}
)

// npTTL 条目信息的缓存时限。连看一部番时同一集会被问好几次(start / pause / resume),
// 而这些字段在一次播放里不会变。
const npTTL = 30 * time.Minute

// nowPlaying 组一条 NowPlaying。取不到条目详情时 item 只有 id ——
// 少字段比少事件好:插件至少知道「在播这一条」。
func nowPlaying(itemID string, positionSec, durationSec float64) map[string]any {
	np := map[string]any{
		"item":        mediaItemOf(itemID),
		"positionSec": positionSec,
		"durationSec": durationSec,
	}
	return np
}

func mediaItemOf(itemID string) map[string]any {
	if itemID == "" {
		return map[string]any{"id": "", "kind": "movie", "title": ""}
	}
	npMu.Lock()
	if e, ok := npCache[itemID]; ok && time.Since(e.at) < npTTL {
		npMu.Unlock()
		return e.item
	}
	npMu.Unlock()

	it := fetchMediaItem(itemID)
	npMu.Lock()
	npCache[itemID] = npItem{item: it, at: time.Now()}
	npMu.Unlock()
	return it
}

// mediaKind Emby 的 Type → `.d.ts` 的 MediaKind。
func mediaKind(t string) string {
	switch t {
	case "Episode":
		return "episode"
	case "Series":
		return "series"
	case "Season":
		return "season"
	case "Person":
		return "person"
	}
	return "movie"
}

func fetchMediaItem(itemID string) map[string]any {
	out := map[string]any{"id": itemID, "kind": "movie", "title": ""}
	raw, err := bus.Invoke(context.Background(), "emby.itemDetail",
		map[string]any{"item_id": itemID, "with_children": false})
	if err != nil {
		return out
	}
	d := asMap(raw)
	if d == nil {
		return out
	}
	out["kind"] = mediaKind(strOf(d["type_"]))
	out["title"] = strOf(d["name"])
	if v := strOf(d["overview"]); v != "" {
		out["overview"] = v
	}
	if y, ok := d["year"].(float64); ok && y > 0 {
		out["year"] = y
	}
	if v := strOf(d["series_name"]); v != "" {
		out["seriesName"] = v
	}
	if n, ok := d["episode_no"].(float64); ok && n > 0 {
		out["episodeNo"] = n
	}
	if n, ok := d["season_no"].(float64); ok && n > 0 {
		out["seasonNo"] = n
	}

	ext := map[string]any{}
	if b, ok := d["bgm_id"].(float64); ok && b > 0 {
		ext["bangumi"] = formatInt(int64(b))
	}
	args := map[string]any{"item_id": itemID}
	if sid := strOf(d["series_id"]); sid != "" {
		args["series_id"] = sid
	}
	if raw, err := bus.Invoke(context.Background(), "emby.providers", args); err == nil {
		for k, v := range asMap(raw) {
			if s := strOf(v); s != "" {
				ext[k] = s
			}
		}
	}
	if len(ext) > 0 {
		out["externalIds"] = ext
	}
	return out
}

func strOf(v any) string {
	s, _ := v.(string)
	return s
}

func formatInt(v int64) string { return strconv.FormatInt(v, 10) }

// asMap 把命令回的 any 摊成 map。回具体结构体时走一次 JSON 往返。
func asMap(v any) map[string]any {
	if m, ok := v.(map[string]any); ok {
		return m
	}
	if m, ok := v.(map[string]string); ok {
		out := make(map[string]any, len(m))
		for k, s := range m {
			out[k] = s
		}
		return out
	}
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var out map[string]any
	if json.Unmarshal(b, &out) != nil {
		return nil
	}
	return out
}
