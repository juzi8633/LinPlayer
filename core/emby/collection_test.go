package emby

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 合集分堆。**「其它」那一堆不许丢** —— 丢掉的表现和 bug 原样(页面上什么都没有)。
func Test合集分堆_影片剧集其它各归各(t *testing.T) {
	got := splitCollection([]Item{
		{ID: "a", Name: "片1", Type: "Movie"},
		{ID: "b", Name: "剧1", Type: "Series"},
		{ID: "c", Name: "片2", Type: "Movie"},
		{ID: "d", Name: "谁知道", Type: "MusicVideo"},
	})
	if len(got.Movies) != 2 || len(got.Series) != 1 || len(got.Others) != 1 {
		t.Fatalf("分堆错了:影片 %d 剧集 %d 其它 %d(要 2/1/1)",
			len(got.Movies), len(got.Series), len(got.Others))
	}
	if got.Movies[0].ID != "a" || got.Series[0].ID != "b" || got.Others[0].ID != "d" {
		t.Fatalf("分错堆了:%+v", got)
	}
}

// 空合集要给**空切片**不是 nil。nil 序列化成 JSON null,前端 .map() 当场抛错,
// 而透明窗口下抛错就是一片黑且不报错。
func Test合集分堆_空表也要三个空数组(t *testing.T) {
	got := splitCollection(nil)
	if got.Movies == nil || got.Series == nil || got.Others == nil {
		t.Fatalf("有字段是 nil,会序列化成 null:%+v", got)
	}
}

// 主查询**不带 Recursive**:合集的成员就是直接子项,递归会把成员剧里的
// 季和集一起翻出来 —— 一个 3 部片的合集会显示成上百条。
func Test合集查询_主查询不递归(t *testing.T) {
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.URL.RawQuery)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"Items":[{"Id":"m1","Name":"片","Type":"Movie"}],"TotalRecordCount":1}`))
	}))
	defer srv.Close()

	got, err := NewClient("test").Collection(context.Background(),
		&Session{Server: srv.URL, UserID: "u"}, "bs-1")
	if err != nil {
		t.Fatalf("拉合集失败:%v", err)
	}
	if len(seen) != 1 {
		t.Fatalf("非空结果不该再打第二次:发了 %d 次 %v", len(seen), seen)
	}
	if strings.Contains(seen[0], "Recursive=true") {
		t.Fatalf("主查询带了 Recursive,成员剧的季和集会一起翻出来:%s", seen[0])
	}
	if !strings.Contains(seen[0], "ParentId=bs-1") {
		t.Fatalf("没按 ParentId 查:%s", seen[0])
	}
	if len(got.Movies) != 1 {
		t.Fatalf("影片没进 movies:%+v", got)
	}
}

// 主查询回空时**必须再试一次 Recursive** —— 有的 fork 对 BoxSet 的非递归查询
// 恒回空,而「回空」和「这个合集本来就是空的」长得一模一样。
func Test合集查询_回空要退一步用递归(t *testing.T) {
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.URL.RawQuery)
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.RawQuery, "Recursive=true") {
			_, _ = w.Write([]byte(`{"Items":[{"Id":"s1","Name":"剧","Type":"Series"}],"TotalRecordCount":1}`))
			return
		}
		_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
	}))
	defer srv.Close()

	got, err := NewClient("test").Collection(context.Background(),
		&Session{Server: srv.URL, UserID: "u"}, "bs-1")
	if err != nil {
		t.Fatalf("拉合集失败:%v", err)
	}
	if len(seen) != 2 {
		t.Fatalf("回空之后没退这一步,合集页还是空的:发了 %d 次 %v", len(seen), seen)
	}
	// 退一步时必须带类型过滤,否则递归会把季和集全翻出来
	if !strings.Contains(seen[1], "IncludeItemTypes=Movie%2CSeries") &&
		!strings.Contains(seen[1], "IncludeItemTypes=Movie,Series") {
		t.Fatalf("递归那一次没限类型,季和集会全翻出来:%s", seen[1])
	}
	if len(got.Series) != 1 {
		t.Fatalf("退一步拿到的剧集没进 series:%+v", got)
	}
}
