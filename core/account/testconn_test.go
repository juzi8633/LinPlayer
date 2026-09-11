package account

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"linplayer/core/bus"
)

// callErr 发一条命令,**要求它失败**,返回错误码和给用户看的那句话。
func callErr(t *testing.T, seq int64, cmd string, args map[string]any) (string, string) {
	t.Helper()
	b, _ := json.Marshal(args)
	if err := bus.Call(seq, cmd, string(b)); err != nil {
		t.Fatalf("发命令失败: %v", err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ev := bus.NextEvent(200)
		if len(ev) == 0 {
			continue
		}
		var e struct {
			T   string `json:"t"`
			Seq int64  `json:"seq"`
			Err *struct {
				Code string `json:"code"`
				Msg  string `json:"msg"`
			} `json:"err"`
		}
		if json.Unmarshal(ev, &e) != nil || e.T != "result" || e.Seq != seq {
			continue
		}
		if e.Err == nil {
			t.Fatalf("%s 居然成功了 —— 这条用例要的是失败", cmd)
		}
		return e.Err.Code, e.Err.Msg
	}
	t.Fatalf("%s 等不到结果", cmd)
	return "", ""
}

// fakeEmby 一台只认一组账密的假服务器。
func fakeEmby(user, pass string, hits *int) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/System/Info/Public":
			_, _ = w.Write([]byte(`{"ServerName":"某服务器","Version":"4.9.5","Id":"abc"}`))
		case "/Users/AuthenticateByName":
			*hits++
			var body struct{ Username, Pw string }
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body.Username != user || body.Pw != pass {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			_, _ = w.Write([]byte(`{"AccessToken":"tk","User":{"Id":"u1","Name":"阿林"}}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

// ★ 「测试连接」给了账号就必须**真登一次**。
// 只探 /System/Info/Public 的话,密码写错的服务器照样报「连上了」。
func TestTestConnection给了账号就真登一次(t *testing.T) {
	setup(t)
	hits := 0
	up := fakeEmby("阿林", "对的密码", &hits)
	defer up.Close()

	got := call(t, 711, "account.testConnection", map[string]any{
		"server": up.URL, "username": "阿林", "password": "对的密码",
	})
	if hits == 0 {
		t.Fatal("一次登录请求都没发 —— 那还是「只探不登」")
	}
	if got["user_name"] != "阿林" {
		t.Fatalf("没把登录用户带回来: %+v", got)
	}
	if got["name"] != "某服务器" {
		t.Fatalf("服务器信息丢了: %+v", got)
	}
	if len(callList(t, 712, "account.listAccounts", nil)) != 0 {
		t.Fatal("测试连接不该往账号表里加东西")
	}
}

// ★★ 401 要翻成人话。用户看到的是 Msg —— 「HTTP 401」不是人话。
func TestTestConnection密码错了给人话(t *testing.T) {
	setup(t)
	hits := 0
	up := fakeEmby("阿林", "对的密码", &hits)
	defer up.Close()

	code, msg := callErr(t, 721, "account.testConnection", map[string]any{
		"server": up.URL, "username": "阿林", "password": "错的",
	})
	if code != bus.EAuth {
		t.Fatalf("401 该是 %s,实得 %s", bus.EAuth, code)
	}
	if !strings.Contains(msg, "账号或密码") {
		t.Fatalf("这句话用户看不懂:%q", msg)
	}
	if strings.Contains(msg, "401") {
		t.Fatalf("人话里不该只剩状态码:%q", msg)
	}
}

// 没给账号时仍然只探一下 —— 老行为不能被这次改动带走。
func TestTestConnection没给账号就只探(t *testing.T) {
	setup(t)
	hits := 0
	up := fakeEmby("阿林", "对的密码", &hits)
	defer up.Close()

	got := call(t, 731, "account.testConnection", map[string]any{"server": up.URL})
	if hits != 0 {
		t.Fatal("没给账号却发了登录请求")
	}
	if got["version"] != "4.9.5" {
		t.Fatalf("探测结果不对: %+v", got)
	}
}
