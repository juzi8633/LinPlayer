package sync

// 追剧日历回宿主时加的两项(D366):
//   ① 已在 Emby 媒体库里的集标「已入库 / 可播」,点了直接播;
//   ② 开播提醒:追的剧有新集发系统通知(应用没开时由 Android 的后台任务来问这一条)。

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

var embyClient *emby.Client

// LibraryHit 日历条目在当前 Emby 服务器上的对应物。
type LibraryHit struct {
	SeriesID string `json:"series_id"`
	// ItemID 能直接播的条目:有这一集就是这一集,只知道剧入库了就是剧本身。
	ItemID   string `json:"item_id"`
	Playable bool   `json:"playable"` // true = 这一集已入库,点了直接播;false = 只是这部剧在库里
}

type libQuery struct {
	Key     string `json:"key"`
	Title   string `json:"title"`
	TMDBID  *int64 `json:"tmdb_id"`
	Season  *int   `json:"season"`
	Episode *int   `json:"episode"`
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

// calendarLibrary 对当前活跃的 Emby 服务器逐部查。先按 TMDB id 批量对,对不上再按片名搜(片名要归一化后完全一致)。
func calendarLibrary(ctx context.Context, qs []libQuery) (map[string]LibraryHit, error) {
	out := map[string]LibraryHit{}
	c := config.Current()
	acc := c.ActiveAccount()
	if acc == nil || acc.IsFileBrowse() {
		return out, nil // 当前不是 Emby:没有媒体库可对,不是错误
	}
	s := &emby.Session{Server: acc.ActiveLineURL(), Token: acc.Token, UserID: acc.UserID, DeviceID: c.DeviceID}

	series := map[string]string{} // key → series id
	var ids []string
	byTmdb := map[string][]string{}
	for _, q := range qs {
		if q.TMDBID != nil {
			t := strconv.FormatInt(*q.TMDBID, 10)
			ids = append(ids, t)
			byTmdb[t] = append(byTmdb[t], q.Key)
		}
	}
	if len(ids) > 0 {
		if items, err := embyClient.ByTmdb(ctx, s, "Series", ids); err == nil {
			for _, it := range items {
				for _, k := range byTmdb[emby.ProviderOf(it.ProviderIDs, "Tmdb")] {
					series[k] = it.ID
				}
			}
		}
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 6)
	for _, q := range qs {
		if series[q.Key] != "" || strings.TrimSpace(q.Title) == "" {
			continue
		}
		wg.Add(1)
		sem <- struct{}{}
		go func(q libQuery) {
			defer wg.Done()
			defer func() { <-sem }()
			items, err := embyClient.Search(ctx, s, q.Title, []string{"Series"}, 5, "")
			if err != nil {
				return
			}
			for _, it := range items {
				if normTitle(it.Name) == normTitle(q.Title) {
					mu.Lock()
					series[q.Key] = it.ID
					mu.Unlock()
					return
				}
			}
		}(q)
	}
	wg.Wait()

	eps := map[string][]emby.Item{}
	for _, q := range qs {
		sid := series[q.Key]
		if sid == "" {
			continue
		}
		hit := LibraryHit{SeriesID: sid, ItemID: sid}
		if q.Episode != nil {
			list, ok := eps[sid]
			if !ok {
				list, _ = embyClient.AllEpisodes(ctx, s, sid)
				eps[sid] = list
			}
			for _, e := range list {
				if e.EpisodeNo != nil && int(*e.EpisodeNo) == *q.Episode && (q.Season == nil || e.SeasonNo == nil || int(*e.SeasonNo) == *q.Season) {
					hit.ItemID, hit.Playable = e.ID, true
					break
				}
			}
		}
		out[q.Key] = hit
	}
	return out, nil
}

// ---------------------------------------------------------------- 开播提醒

type notifiedFile struct {
	Seen map[string]int64 `json:"seen"` // 条目键 → 提醒时刻
}

func notifiedPath() string { return filepath.Join(paths.Root(), "calendar_notified.json") }

// entryKey 一条放送的身份:同一部剧同一集只提醒一次。
func entryKey(e CalendarEntry, day string) string {
	id := e.Title
	if e.TMDBID != nil {
		id = "tmdb" + strconv.FormatInt(*e.TMDBID, 10)
	} else if e.BangumiID != nil {
		id = "bgm" + strconv.FormatInt(*e.BangumiID, 10)
	}
	if e.Season != nil && e.Episode != nil {
		return id + "#S" + strconv.Itoa(*e.Season) + "E" + strconv.Itoa(*e.Episode)
	}
	return id + "@" + day // 按剧的放送表(Bangumi):每个放送日提醒一次
}

// dueEntries 从「只看我追的」放送里挑出:已经开播、开播在 window 以内、还没提醒过的。
func dueEntries(entries []CalendarEntry, now time.Time, window time.Duration, seen map[string]int64) []CalendarEntry {
	var out []CalendarEntry
	for _, e := range entries {
		var at time.Time
		switch {
		case e.AirDate != nil:
			t, err := time.Parse(time.RFC3339, *e.AirDate)
			if err != nil {
				continue
			}
			at = t
		case e.Weekday != nil:
			// 按周放送:今天是放送日,且过了放送时刻(没有时刻就按当天 0 点)
			wd := int(now.Weekday())
			if wd == 0 {
				wd = 7
			}
			if wd != *e.Weekday {
				continue
			}
			at = time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
			if e.BroadcastAt != nil {
				if t, err := time.Parse(time.RFC3339, *e.BroadcastAt); err == nil {
					lt := t.In(now.Location())
					at = time.Date(now.Year(), now.Month(), now.Day(), lt.Hour(), lt.Minute(), 0, 0, now.Location())
				}
			}
		default:
			continue
		}
		if at.After(now) || now.Sub(at) > window {
			continue
		}
		k := entryKey(e, now.Format("2006-01-02"))
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = now.Unix()
		out = append(out, e)
	}
	return out
}

// calendarDue 开播提醒:两家账号「只看我追的」里新开播的。已提醒过的记在数据根下,不重复提醒。
func calendarDue(ctx context.Context) []CalendarEntry {
	var all []CalendarEntry
	if a := Load("trakt"); a != nil {
		all = append(all, TraktCalendar(ctx, a, 2, 4, true)...)
	}
	if a := Load("bangumi"); a != nil {
		all = append(all, BangumiCalendar(ctx, a, true)...)
	}
	var nf notifiedFile
	if b, err := os.ReadFile(notifiedPath()); err == nil {
		_ = json.Unmarshal(b, &nf)
	}
	if nf.Seen == nil {
		nf.Seen = map[string]int64{}
	}
	now := time.Now()
	due := dueEntries(all, now, 24*time.Hour, nf.Seen)
	for k, at := range nf.Seen { // 只留两周,文件不无限长
		if now.Unix()-at > 14*24*3600 {
			delete(nf.Seen, k)
		}
	}
	if b, err := json.Marshal(nf); err == nil {
		_ = os.WriteFile(notifiedPath(), b, 0o644)
	}
	if due == nil {
		due = []CalendarEntry{}
	}
	return due
}

func registerLibrary(version string) {
	embyClient = emby.NewClient(version)
	registerGated("sync.calendarLibrary", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		var qs []libQuery
		b, _ := json.Marshal(a["entries"])
		if err := json.Unmarshal(b, &qs); err != nil {
			return nil, bus.NewErr(bus.EInvalid, "entries 格式不对")
		}
		return calendarLibrary(ctx, qs)
	})
	registerGated("sync.calendarDue", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		if !config.Current().PrefsOf().CalendarNotify {
			return []CalendarEntry{}, nil
		}
		return calendarDue(ctx), nil
	})
}
