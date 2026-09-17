package emby

import (
	"context"
	"fmt"
	"net/url"
	"strings"
)

// tmdbBatch 一次请求带几个 TMDB id。实测 20 个一次 2.2s,多了 URL 太长。
const tmdbBatch = 20

// ByTmdb 按 TMDB id 找这台服上的条目(聚合视界跨服对齐用)。
//
// ★ 同一部片在两台服上的译名常常不同(实测两台服共有的 3718 部里 151 部名字不一样),
// 拿名字去对面搜几乎搜不到(抽 40 部命中 3),拿 TMDB id 查是 40/40。
// ★ 返回前再按 TMDB 滤一遍:服务端要是不认 AnyProviderIdEquals 会回整个库,
// 不滤的话那台服的全部片子都会被当成「同一部」塞进结果。
func (c *Client) ByTmdb(ctx context.Context, s *Session, typ string, ids []string) ([]Item, error) {
	want := map[string]bool{}
	var uniq []string
	for _, id := range ids {
		if id != "" && !want[id] {
			want[id] = true
			uniq = append(uniq, id)
		}
	}
	out := []Item{}
	for len(uniq) > 0 {
		n := min(tmdbBatch, len(uniq))
		keys := make([]string, n)
		for i, id := range uniq[:n] {
			keys[i] = "tmdb." + id
		}
		uniq = uniq[n:]
		u := fmt.Sprintf("%s/Users/%s/Items?Recursive=true&IncludeItemTypes=%s&AnyProviderIdEquals=%s&Fields=%s,%s&Limit=%d",
			s.Server, url.PathEscape(s.UserID), url.QueryEscape(typ), url.QueryEscape(strings.Join(keys, ",")),
			cardFields, HistoryFields, ServerPageCap)
		items, err := c.fetchItems(ctx, s, u)
		if err != nil {
			return nil, err
		}
		for _, it := range items {
			if it.Type == typ && want[ProviderOf(it.ProviderIDs, "Tmdb")] {
				out = append(out, it)
			}
		}
	}
	return out, nil
}
