package danmaku

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNextEpisodeIDAddsDelta(t *testing.T) {
	got, ok := NextEpisodeID("123450003", 1)
	if !ok || got != "123450004" {
		t.Fatalf("下一集该是 123450004,实得 %q(ok=%v)—— 接力就是靠这一步省掉一整轮搜索", got, ok)
	}
	if _, ok := NextEpisodeID("ep-3-uuid", 1); ok {
		t.Fatal("非数字 id 不该接力:加一得到的是一个不存在的 id,而上游只会回空表")
	}
	if _, ok := NextEpisodeID("123450003", 99); ok {
		t.Fatal("跨 99 集不该接力:中间插过 SP 的作品每跳一集就多错一位")
	}
}

func TestRelaySkipsSearchForNextEpisode(t *testing.T) {
	relayReset()
	var searches int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/api/v2/search/episodes" || r.URL.Path == "/api/v2/search/anime":
			searches++
			_ = json.NewEncoder(w).Encode(map[string]any{"animes": []any{map[string]any{
				"animeId": 12345, "animeTitle": "测试番",
				"episodes": []any{
					map[string]any{"episodeId": 123450001, "episodeTitle": "第1话", "episodeNumber": "1"},
					map[string]any{"episodeId": 123450002, "episodeTitle": "第2话", "episodeNumber": "2"},
				},
			}}})
		default:
			// /comment/{id} —— 每个 id 回一条弹幕,正文写成 id 本身好对账
			id := r.URL.Path[len("/api/v2/comment/"):]
			_ = json.NewEncoder(w).Encode(map[string]any{"comments": []any{
				map[string]any{"cid": 1, "p": "1.0,1,16777215,0", "m": "来自 " + id},
			}})
		}
	}))
	defer srv.Close()

	cfgs := []SourceConfig{{ID: "s1", Name: "测试源", APIURL: srv.URL + "/api/v2"}}
	ep1, ep2 := int64(1), int64(2)
	in1 := &MatchInput{Title: "测试番", EpisodeNo: &ep1}

	cands, err := MatchAll(context.Background(), cfgs, in1)
	if err != nil || len(cands) == 0 {
		t.Fatalf("第一集该匹配得上:err=%v 候选=%d", err, len(cands))
	}
	relayRemember(in1, &cands[0])
	before := searches
	if before == 0 {
		t.Fatal("第一集本来就该搜一轮,一次都没搜说明这个假上游没被打到")
	}

	items, ok := relayLoad(context.Background(), cfgs, &MatchInput{Title: "测试番", EpisodeNo: &ep2}, 0)
	if !ok {
		t.Fatal("第二集该走接力(上一集的 id 加一),实得没走 —— 那等于每集都白搜一轮")
	}
	if searches != before {
		t.Fatalf("接力这一路不许再打搜索接口,实得多打了 %d 次", searches-before)
	}
	if len(items) != 1 || items[0].Text != "来自 123450002" {
		t.Fatalf("接力该取到第 2 集(123450002),实得 %+v", items)
	}
}

func TestBgmSubjectID(t *testing.T) {
	for _, s := range []string{
		"https://bgm.tv/subject/253", "https://bangumi.tv/subject/253/",
		"bgm:253", "bgm 253",
	} {
		if got := BgmSubjectID(s); got != 253 {
			t.Fatalf("%q 该抠出 253,实得 %d", s, got)
		}
	}
	// ☠ 纯数字不算:「253」多半是片名的一部分,猜错了用户看到的是另一部作品
	for _, s := range []string{"253", "全职猎人 2011", ""} {
		if got := BgmSubjectID(s); got != 0 {
			t.Fatalf("%q 不该被当成条目号,实得 %d", s, got)
		}
	}
}

func TestBgmTitlesBecomeSearchTitle(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"name": "とある科学の超電磁砲", "name_cn": "某科学的超电磁炮",
		})
	}))
	defer srv.Close()
	defer SetBgmAPIBase(srv.URL)()

	id := int64(2585)
	in := &MatchInput{Title: "某科学的超电磁炮", BgmID: &id}
	out := withBgmTitles(context.Background(), in)
	if out.Title != "とある科学の超電磁砲" {
		t.Fatalf("主标题该换成 bgm 的原名(弹幕源收的是日文名),实得 %q", out.Title)
	}
	found := false
	for _, a := range out.AltTitles {
		if a == "某科学的超电磁炮" {
			found = true
		}
	}
	if !found {
		t.Fatalf("原来的标题该降级成别名留着兜底,实得别名表 %v", out.AltTitles)
	}
}

func TestSearchGroupKeepsOrderAndCaps(t *testing.T) {
	mk := func(n int) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			list := []any{}
			for i := 0; i < n; i++ {
				list = append(list, map[string]any{
					"animeId": 1000 + i, "animeTitle": fmt.Sprintf("作品%d", i),
				})
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"animes": list})
		}
	}
	big := httptest.NewServer(mk(30))
	defer big.Close()
	small := httptest.NewServer(mk(2))
	defer small.Close()

	// 少结果的源排在前面 —— 组的顺序必须**照这个顺序**出,不许按条数重排
	cfgs := []SourceConfig{
		{ID: "few", Name: "少的", APIURL: small.URL + "/api/v2"},
		{ID: "many", Name: "多的", APIURL: big.URL + "/api/v2"},
	}
	gs := searchAllGrouped(context.Background(), cfgs, "x")
	if len(gs) != 2 || gs[0].SourceID != "few" {
		t.Fatalf("组序该是设置里的顺序(few 在前),实得 %v/%v", gs[0].SourceID, gs[1].SourceID)
	}
	if len(gs[1].Animes) != SearchGroupCap {
		t.Fatalf("一个源最多 %d 部(方便换源),实得 %d 部", SearchGroupCap, len(gs[1].Animes))
	}
}
