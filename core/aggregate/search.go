package aggregate

import (
	"context"
	"sync"
	"time"

	"linplayer/core/config"
	"linplayer/core/emby"
)

// perServerTimeout 一台服最多拖多久。以前没有这一道:一台慢服拖住整条命令,
// 而结果是等所有服都回来才一次性给的 —— 表现是「搜了半分钟什么都没有」。
const perServerTimeout = 20 * time.Second

type serverSearch struct {
	acc   config.Account
	s     *emby.Session
	items []emby.Item
	err   error
}

/*
searchAll 聚合搜索。两步:

 1. 每台服用自己的搜索接口搜这个词;

 2. 把所有命中条目的 TMDB id 拿去**每台服**按 id 查一次,补上换了译名的同一部。

    ★ 第二步是「聚合不到别的剧集」的修法(2026-09-17 两台真服实测):同一部片两台服译名不同时,
    拿名字去对面搜 40 部只中 3 部;加上按 TMDB 补,两个方向都是 40/40。
    也顺带补上了「有的服搜索只按词前缀」:只要有一台搜得到,别的服就能靠 TMDB 带出来。
*/
func searchAll(ctx context.Context, c *config.AppConfig, query string, includeEpisodes bool) []ServerGroup {
	/* 用户 2026-07-16:「跨服查找剧/电影、聚合搜索,都只出剧/电影的条目,
	   不要出『集』这种条目」。不传 include_episodes = 关。 */
	types := []string{"Movie", "Series"}
	if includeEpisodes {
		types = append(types, "Episode")
	}
	var all []*serverSearch
	for _, acc := range c.AccountList {
		if acc.IsFileBrowse() || !acc.AllowAggregate() {
			continue // 浏览型源没有 Emby 搜索接口;关了「允许聚合」的服不进聚合(D235)
		}
		all = append(all, &serverSearch{acc: acc, s: sessionOf(c, acc)})
	}

	parallel(all, func(x *serverSearch) {
		sctx, cancel := context.WithTimeout(ctx, perServerTimeout)
		defer cancel()
		x.items, x.err = client.Search(sctx, x.s, query, types, 0, "")
	})

	type key struct{ typ, tmdb string }
	want := map[key]bool{}
	for _, x := range all {
		for _, it := range x.items {
			if t := emby.ProviderOf(it.ProviderIDs, "Tmdb"); t != "" && it.Type != "Episode" {
				want[key{it.Type, t}] = true
			}
		}
	}
	parallel(all, func(x *serverSearch) {
		if x.err != nil {
			return // 搜都没搜成的服,按 id 查多半也不通,别再等一轮超时
		}
		have := map[key]bool{}
		for _, it := range x.items {
			have[key{it.Type, emby.ProviderOf(it.ProviderIDs, "Tmdb")}] = true
		}
		missing := map[string][]string{}
		for k := range want {
			if !have[k] {
				missing[k.typ] = append(missing[k.typ], k.tmdb)
			}
		}
		sctx, cancel := context.WithTimeout(ctx, perServerTimeout)
		defer cancel()
		seen := map[string]bool{}
		for _, it := range x.items {
			seen[it.ID] = true
		}
		for typ, ids := range missing {
			extra, err := client.ByTmdb(sctx, x.s, typ, ids)
			if err != nil {
				continue // 补不上只是少几条,本服自己搜到的照出
			}
			for _, it := range extra {
				if !seen[it.ID] {
					seen[it.ID] = true
					x.items = append(x.items, it)
				}
			}
		}
	})

	// ★ 按账号表顺序拼回去,不按谁先返回 —— 否则每次搜索服务器顺序都在跳
	out := []ServerGroup{}
	for _, x := range all {
		g := ServerGroup{ServerID: x.acc.Server, ServerName: x.acc.DisplayName()}
		switch {
		case x.err != nil:
			// 单台失败隔离:其余照出。但**要说出来**,不能悄悄消失 —— 「没搜成」和「没有」是两回事
			msg := x.err.Error()
			g.Error = &msg
		case len(x.items) == 0:
			continue // 这台没有这部片 —— 不是失败,整条不出
		default:
			g.Items = x.items
		}
		out = append(out, g)
	}
	return out
}

func parallel[T any](xs []T, f func(T)) {
	var wg sync.WaitGroup
	for _, x := range xs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			f(x)
		}()
	}
	wg.Wait()
}
