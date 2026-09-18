package emby

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 「取消观看记录」只能打 HideFromResume。误打成 PlayedItems 的话,
// 用户说的「不想看也不想标已看」就变成了标已看。
func Test取消观看记录_只藏不标已看(t *testing.T) {
	var method, path, query string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method, path, query = r.Method, r.URL.Path, r.URL.RawQuery
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	s := &Session{Server: srv.URL, UserID: "u1"}
	if err := NewClient("test").HideResume(context.Background(), s, "it-9", true); err != nil {
		t.Fatalf("出错:%v", err)
	}
	if method != http.MethodPost || path != "/Users/u1/Items/it-9/HideFromResume" || query != "Hide=true" {
		t.Fatalf("端点不对:%s %s?%s", method, path, query)
	}
	_ = NewClient("test").HideResume(context.Background(), s, "it-9", false)
	if query != "Hide=false" {
		t.Fatalf("放回去要带 Hide=false:%s", query)
	}
}
