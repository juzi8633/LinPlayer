package emby

import "testing"

func p(s string) *string    { return &s }
func pf(v float64) *float64 { return &v }
func pi(v int64) *int64     { return &v }

// 收藏的排序只能在本地做 —— 某 fork 在 Filters=IsFavorite 上无视 SortBy(见 Client.Favorites)。
//
// ☠ 四档的期望顺序**两两不同**。写成同一个顺序的话,四档塌成一档也照样绿 ——
// 那种测试比没有更糟,它在报告里占着一行绿。
func Test收藏本地排序四档各不相同(t *testing.T) {
	mk := func() []Item {
		return []Item{
			{ID: "a", Name: "阿", DateUpdated: p("2026-09-01"), SortName: p("ccc"), Rating: pf(5), Year: pi(2020)},
			{ID: "b", Name: "波", DateUpdated: p("2026-01-01"), SortName: p("bbb"), Rating: pf(9), Year: pi(2010)},
			{ID: "c", Name: "慈", DateUpdated: p("2026-05-01"), SortName: p("aaa"), Rating: pf(7), Year: pi(2000)},
		}
	}
	ids := func(v []Item) string {
		s := ""
		for _, it := range v {
			s += it.ID
		}
		return s
	}

	for _, tc := range []struct{ by, want string }{
		{"更新时间", "acb"},
		{"名称", "cba"},
		{"评分", "bca"},
		{"年份", "abc"},
		{"", "acb"},     // 没传 = 默认档 = 第一档
		{"没这一档", "acb"}, // 认不出就按默认档,不报错
	} {
		v := mk()
		SortFavorites(v, tc.by)
		if got := ids(v); got != tc.want {
			t.Fatalf("按「%s」排:得到 %s,要 %s", tc.by, got, tc.want)
		}
	}

	if FavoriteSorts[0] != "更新时间" {
		t.Fatalf("第一档就是默认档,现在是 %q", FavoriteSorts[0])
	}
}

// 缺值必须沉底 —— 排在前面的话首屏全是「问不出这个值」的条目,看着像列表坏了。
func Test收藏排序缺值沉底(t *testing.T) {
	for _, by := range []string{"更新时间", "评分", "年份"} {
		v := []Item{{ID: "空"}, {ID: "有", DateUpdated: p("2020-01-01"), Rating: pf(1), Year: pi(1)}}
		SortFavorites(v, by)
		if v[0].ID != "有" {
			t.Fatalf("按「%s」排:没有这个值的条目排到了前面", by)
		}
	}
}

// TV 收藏页的排序参数(UI_TV.md §7.8):sort_by + sort_order,**缺值无论升降序一律沉底**。
//
// ☠ 升序最容易写成「降序整体反转」—— 那样没值的条目会翻到最前面。
func Test收藏排序参数升降序且缺值沉底(t *testing.T) {
	mk := func() []Item {
		return []Item{
			{ID: "空"},
			{ID: "a", Name: "阿", DateUpdated: p("2026-09-01"), SortName: p("ccc"), Rating: pf(5)},
			{ID: "b", Name: "波", DateUpdated: p("2026-01-01"), SortName: p("aaa"), Rating: pf(9)},
			{ID: "c", Name: "慈", DateUpdated: p("2026-05-01"), SortName: p("bbb"), Rating: pf(7)},
		}
	}
	ids := func(v []Item) string {
		s := ""
		for _, it := range v {
			s += it.ID
		}
		return s
	}
	for _, tc := range []struct{ by, order, want string }{
		{"favorited", "", "空abc"}, // 收藏时间 = 服务端原序,不动
		{"favorited", "asc", "空abc"},
		{"updated", "desc", "acb空"},
		{"updated", "asc", "bca空"},
		{"rating", "desc", "bca空"},
		{"rating", "asc", "acb空"},
		{"name", "asc", "bca空"},
		{"name", "desc", "acb空"},
	} {
		v := mk()
		SortFavoritesBy(v, tc.by, tc.order)
		if got := ids(v); got != tc.want {
			t.Fatalf("sort_by=%s sort_order=%s:得到 %s,要 %s", tc.by, tc.order, got, tc.want)
		}
	}
}

// 名称档优先用服务端的 SortName(「The Matrix」该在 M 上而不是 T),没有才回落 Name。
func Test名称档回落到Name(t *testing.T) {
	v := []Item{{ID: "无", Name: "阿"}, {ID: "有", Name: "波", SortName: p("aaa")}}
	SortFavorites(v, "名称")
	if v[0].ID != "有" {
		t.Fatalf("有 SortName 的应该按 SortName 排在前面,现在是 %s", v[0].ID)
	}
}
