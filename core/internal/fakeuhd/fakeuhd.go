// Package fakeuhd 是 UHD 助手插件的验收用假站点:照着真站 `/api/v1` 的**实测形状**
// 回数据(2026-09-21 实测,见 docs/lessons/plugins.md)。
//
// 形状对不上就没有意义,所以这几处是照抄真站的:
//
//	· 登录回 `{ok, data:{token, expires_at}}`,token 是**裸值**不是 Bearer;
//	· 流量回 `used_bytes / limit_bytes / display_unlimited_traffic`;
//	· 线路表里**有一条 domain 前面带空格**(真站上就有,不 trim 就拼出坏地址);
//	· 提交求片时 `content` 为空回 `{ok:false, msg:"参数验证失败"}`;
//	· 测速 `download?size_mb=` 必须等于会话的 `size_mib`,否则只回几十字节。
package fakeuhd

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
)

// Server 假站。Base 由调用方在起服务后填。
type Server struct {
	Base string
	mu   sync.Mutex
	// Created 收到的求片提交(测试拿它对账)
	Created []map[string]any
	// Sessions 建过的测速会话 → 大小(MiB)
	Sessions map[string]int
}

const token = "fake-token-32"

func New() *Server { return &Server{Sessions: map[string]int{}} }

func ok(w http.ResponseWriter, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "data": data})
}

func bad(w http.ResponseWriter, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": false, "msg": msg})
}

// authed 校验裸 token。真站用的就是 `Authorization: <token>`,不是 Bearer ——
// 写成 Bearer 的话真站回 401,而这里如果不较真就测不出来。
func authed(w http.ResponseWriter, r *http.Request) bool {
	if r.Header.Get("Authorization") != token {
		w.WriteHeader(http.StatusUnauthorized)
		bad(w, "invalid or expired token")
		return false
	}
	return true
}

func body(r *http.Request) map[string]any {
	var m map[string]any
	_ = json.NewDecoder(r.Body).Decode(&m)
	return m
}

// Handler 全部路由。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/api/v1/auth/login", func(w http.ResponseWriter, r *http.Request) {
		b := body(r)
		if b["username"] != "阿甲" || b["password"] != "pw" {
			bad(w, "用户名或密码错误")
			return
		}
		ok(w, map[string]any{"token": token, "expires_at": "2099-01-01T00:00:00+08:00"})
	})

	mux.HandleFunc("/api/v1/traffic/me", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		ok(w, map[string]any{
			"display_unlimited_traffic": false,
			"used_bytes":                10737418240,  // 10 GiB
			"limit_bytes":               107374182400, // 100 GiB
		})
	})

	mux.HandleFunc("/api/v1/users/me", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		ok(w, map[string]any{"id": "u1", "name": "阿甲", "balance": 0.5})
	})

	mux.HandleFunc("/api/v1/subscriptions/domains", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		ok(w, []map[string]any{
			{"id": "L1", "name": "国际方向", "description": "海外线路", "domain": s.Base},
			// ☠ 真站上这一条的 domain **前面带一个空格**。不 trim 的话拼出来的地址是坏的,
			//   而表现只是「这条线路测速失败」。
			{"id": "L2", "name": "备用", "domain": " " + s.Base},
		})
	})

	mux.HandleFunc("/api/v1/subscriptions/domains/", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		if !strings.HasSuffix(r.URL.Path, "/resolve") {
			http.NotFound(w, r)
			return
		}
		/* 真站 resolve 出来的是**子节点**,和列表项不是同一个 host。
		   ☠ domain **前面带一个空格** —— 真站上就是这样(2026-09-21 实测)。
		   不 trim 的话拼出来的地址是坏的,而表现只是「这条线路测速失败」。 */
		ok(w, map[string]any{"id": "node1", "name": "cf-l2", "domain": " " + s.Base, "parent_id": "L1"})
	})

	mux.HandleFunc("/api/v1/media-requests/search", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		b := body(r)
		kw, _ := b["keyword"].(string)
		if kw == "" {
			bad(w, "参数验证失败")
			return
		}
		ok(w, []map[string]any{
			{"tmdb_id": 101, "media_type": "movie", "title": kw + "(电影)", "original_title": "Movie", "year": 2024,
				"poster_path": "/img/i/poster/aaa", "overview": "简介", "exists_in_library": false, "allowed_to_create": true},
			{"tmdb_id": 102, "media_type": "tv", "title": kw + "(剧集)", "year": 2023,
				"poster_path": "https://image.invalid/p.jpg", "exists_in_library": true, "allowed_to_create": false,
				"blocked_reason": "媒体库中已存在"},
		})
	})

	mux.HandleFunc("/api/v1/media-requests", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		b := body(r)
		// 说明必填 —— 真站就是这么回的
		if strings.TrimSpace(fmt.Sprint(b["content"])) == "" || b["content"] == nil {
			bad(w, "参数验证失败")
			return
		}
		s.mu.Lock()
		s.Created = append(s.Created, b)
		s.mu.Unlock()
		ok(w, map[string]any{"topic_id": "T1"})
	})

	mux.HandleFunc("/api/v1/media-requests/mine/list", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		ok(w, map[string]any{"page": 1, "page_size": 20, "total": 0, "list": []any{}})
	})

	mux.HandleFunc("/api/v1/media-requests/topics/list", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		ok(w, map[string]any{"page": 1, "page_size": 20, "total": 2, "list": []map[string]any{
			{"id": "T1", "media": map[string]any{"tmdb_id": 9, "media_type": "movie", "title": "广场上的片", "year": 2020,
				"poster_path": "/img/i/poster/bbb", "exists_in_library": false},
				"total_item_count": 3, "open_item_count": 1, "missing_count": 2, "refresh_count": 1, "feedback_count": 0,
				"latest_request_at": "2026-09-21T12:00:00+08:00"},
		}})
	})

	mux.HandleFunc("/api/v1/speed-test/session", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		b := body(r)
		size := int(toF(b["size_mib"]))
		if size != 32 && size != 64 && size != 100 {
			bad(w, "参数验证失败")
			return
		}
		id := "S" + strconv.Itoa(size)
		s.mu.Lock()
		s.Sessions[id] = size
		s.mu.Unlock()
		ok(w, map[string]any{"session_id": id, "report_token": "rt-" + id})
	})

	mux.HandleFunc("/api/v1/speed-test/download", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		q := r.URL.Query()
		s.mu.Lock()
		want := s.Sessions[q.Get("session_id")]
		s.mu.Unlock()
		got, _ := strconv.Atoi(q.Get("size_mb"))
		if want == 0 || got != want {
			// 真站在大小对不上时只回几十字节。分段下载就是这么废掉的。
			_, _ = w.Write(make([]byte, 39))
			return
		}
		// 测试里不真下 32 MiB:回 1 MiB,但**按真实分块**走完流式那条路
		const n = 1 << 20
		w.Header().Set("Content-Length", strconv.Itoa(n))
		buf := make([]byte, 64<<10)
		for sent := 0; sent < n; sent += len(buf) {
			_, _ = w.Write(buf)
			if f, okk := w.(http.Flusher); okk {
				f.Flush()
			}
		}
	})

	mux.HandleFunc("/api/v1/speed-test/report", func(w http.ResponseWriter, r *http.Request) {
		if !authed(w, r) {
			return
		}
		b := body(r)
		if b["session_id"] == nil || b["report_token"] == nil {
			bad(w, "测速上报已失效")
			return
		}
		ok(w, map[string]any{})
	})

	return mux
}

func toF(v any) float64 {
	f, _ := v.(float64)
	return f
}
