// Package companion 是电视端的「手机扫码遥控」(UI_TV §9)。
//
// 电视在局域网上起一个小网页,手机扫码打开就是遥控器。本包只管传输:
// 监听、路由、发页面、把接口请求转给**已注册的命令**(bus.Invoke)——
// 业务抄第二份的话,手机上改的设置和电视上改的会慢慢走成两套行为。
//
// 明文 HTTP:与同 Wi-Fi 下访问自建 Emby 同档风险,不上自签 TLS(UI_TV §9.3)。
package companion

import (
	"context"
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
)

//go:embed page.html
var page []byte

// allowed 手机页能调的命令。白名单而不是黑名单:
// 新加一条命令不该自动对局域网里任何拿到二维码的人开放。
var allowed = map[string]bool{
	"account.listAccounts":    true,
	"account.setActiveServer": true,
	"account.removeAccount":   true,
	"account.batchParse":      true,
	"account.batchAddServers": true,
	"emby.login":              true,
	"emby.aggregateSearch":    true,
	"player.setPause":         true,
	"player.seek":             true,
	"player.stopPlayback":     true,
	"prefs.getPrefs":          true,
	"prefs.setPrefs":          true,
	"prefs.getProxy":          true,
	"prefs.setProxy":          true,
	"system.cacheSize":        true,
	"system.clearCache":       true,
	"sync.bangumiAccount":     true,
	"sync.bangumiLoginToken":  true,
	"danmaku.getBlockwords":   true,
	"danmaku.setBlockwords":   true,
}

// changesAccounts 成功后要广播 account.status 的命令。
// 电视端靠这个事件换屏;旧版漏发,手机上登录成功电视还停在「添加服务器」。
var changesAccounts = map[string]bool{
	"emby.login":              true,
	"account.removeAccount":   true,
	"account.setActiveServer": true,
	"account.batchAddServers": true,
}

var keys = map[string]bool{
	"up": true, "down": true, "left": true, "right": true,
	"ok": true, "back": true, "home": true, "menu": true, "playpause": true,
}

// AllowedCommands 白名单,给 core/commands 的测试核对「名字都真的注册了」。
func AllowedCommands() []string {
	out := make([]string, 0, len(allowed))
	for k := range allowed {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

const connectedWindow = 15 * time.Second

// fallbackDeviceID 配置里还没有设备 id 时用。emby.login 缺它直接拒。
const fallbackDeviceID = "linplayer-companion"

// lanIP 是变量只为测试能换成「拿不到地址」。
var lanIP = detectLANIP

var st struct {
	mu        sync.Mutex
	srv       *http.Server
	token     string
	port      int
	url       string
	err       string
	ipErr     string
	title     string
	connected bool
	lastSeen  time.Time
	idle      *time.Timer
}

// RegisterCommands 由 core/commands 调用。
func RegisterCommands() {
	bus.Register("companion.start", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		start()
		return Status(), nil
	})
	bus.Register("companion.status", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return Status(), nil
	})
	bus.Register("companion.setEnabled", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		on, ok := a["enabled"].(bool)
		if !ok {
			return nil, bus.NewErr(bus.EInvalid, "缺少 enabled")
		}
		c := config.Current()
		c.CompanionEnabled = on
		if err := c.Save(); err != nil {
			return nil, bus.NewErr(bus.EInternal, "配置保存失败: %v", err)
		}
		stop()
		if on {
			start() // 先停再起 = 换新 token,旧二维码作废
		}
		return Status(), nil
	})
	bus.Register("companion.setNowPlaying", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		t, _ := a["title"].(string)
		st.mu.Lock()
		st.title = strings.TrimSpace(t)
		st.mu.Unlock()
		return map[string]any{"title": strings.TrimSpace(t)}, nil
	})
}

// Status 状态对象。**不许只回一个可空 URL**:旧版因此只能猜「没开或没联网」。
func Status() map[string]any {
	st.mu.Lock()
	defer st.mu.Unlock()
	return statusLocked()
}

func statusLocked() map[string]any {
	return map[string]any{
		"enabled":   config.Current().CompanionEnabled,
		"running":   st.srv != nil,
		"url":       st.url,
		"port":      st.port,
		"error":     st.err,
		"ip_error":  st.ipErr,
		"connected": st.connected,
	}
}

func start() {
	st.mu.Lock()
	if st.srv != nil || !config.Current().CompanionEnabled {
		st.mu.Unlock()
		return
	}
	// 独立监听器绑全部 IPv4 网卡;localserve 只绑回环,手机连不上
	ln, err := net.Listen("tcp4", ":0")
	if err != nil {
		st.err = err.Error()
		s := statusLocked()
		st.mu.Unlock()
		bus.Emit("companion.status", s, "")
		return
	}
	b := make([]byte, 6)
	_, _ = rand.Read(b) // crypto/rand.Read 按文档不会返回错误
	tok := hex.EncodeToString(b)
	srv := &http.Server{Handler: handler(tok), ReadHeaderTimeout: 10 * time.Second}
	st.srv, st.token, st.err = srv, tok, ""
	st.port = ln.Addr().(*net.TCPAddr).Port
	st.url, st.ipErr = "", ""
	// 拿不到地址不算失败:服务照起,电视上给出端口让用户手敲
	if ip, err := lanIP(); err != nil {
		st.ipErr = err.Error()
	} else {
		st.url = "http://" + net.JoinHostPort(ip.String(), strconv.Itoa(st.port)) + "/c/" + tok
	}
	s := statusLocked()
	st.mu.Unlock()
	go func() { _ = srv.Serve(ln) }() // Close 之后必回 ErrServerClosed,不是故障
	bus.Emit("companion.status", s, "")
}

func stop() {
	st.mu.Lock()
	if st.srv == nil {
		st.mu.Unlock()
		return
	}
	_ = st.srv.Close() // 只可能报监听器已关,停服这件事照样成立
	st.srv, st.token, st.url, st.ipErr, st.port = nil, "", "", "", 0
	st.connected = false
	s := statusLocked()
	st.mu.Unlock()
	bus.Emit("companion.status", s, "")
}

func handler(tok string) http.Handler {
	base := "/c/" + tok
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if p == base || p == base+"/" {
			if r.Method != http.MethodGet && r.Method != http.MethodHead {
				w.WriteHeader(http.StatusMethodNotAllowed)
				return
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			_, _ = w.Write(page)
			return
		}
		name, ok := strings.CutPrefix(p, base+"/api/")
		if !ok || name == "" {
			w.WriteHeader(http.StatusNotFound) // 不带正文:token 不对时什么都不透露
			return
		}
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		touch()
		serveAPI(w, r, name)
	})
}

func reply(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func serveAPI(w http.ResponseWriter, r *http.Request, name string) {
	var args map[string]any
	err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256<<10)).Decode(&args)
	if err != nil && !errors.Is(err, io.EOF) {
		reply(w, http.StatusBadRequest, map[string]any{"error": "请求体不是合法 JSON"})
		return
	}
	if args == nil {
		args = map[string]any{}
	}
	switch name {
	case "key":
		k, _ := args["key"].(string)
		if !keys[k] {
			reply(w, http.StatusBadRequest, map[string]any{"error": "不认识的按键: " + k})
			return
		}
		bus.Emit("companion.key", map[string]any{"key": k}, "")
		reply(w, http.StatusOK, map[string]any{"data": true})
		return
	case "open":
		id, _ := args["item_id"].(string)
		if id == "" {
			reply(w, http.StatusBadRequest, map[string]any{"error": "缺少 item_id"})
			return
		}
		typ, _ := args["type"].(string)
		sid, _ := args["server_id"].(string)
		bus.Emit("companion.open", map[string]any{"item_id": id, "type": typ, "server_id": sid}, "")
		reply(w, http.StatusOK, map[string]any{"data": true})
		return
	case "state":
		// 片名只有电视端报得出来(播放器状态里没有),所以这条要拼一下
		ps, _ := bus.Invoke(r.Context(), "player.status", nil) // 播放器没起时手机页照样要能开
		st.mu.Lock()
		title := st.title
		st.mu.Unlock()
		reply(w, http.StatusOK, map[string]any{"data": map[string]any{"title": title, "player": ps}})
		return
	}
	if !allowed[name] {
		reply(w, http.StatusForbidden, map[string]any{"error": "手机遥控不开放这条命令: " + name})
		return
	}
	if strings.HasPrefix(name, "emby.") || strings.HasPrefix(name, "player.") {
		mergeSession(r.Context(), args)
	}
	out, err := bus.Invoke(r.Context(), name, args)
	if err != nil {
		msg := err.Error()
		var be *bus.Err
		if errors.As(err, &be) {
			msg = be.Msg
		}
		reply(w, http.StatusOK, map[string]any{"error": msg})
		return
	}
	if changesAccounts[name] {
		bus.Emit("account.status", map[string]any{}, "")
	}
	reply(w, http.StatusOK, map[string]any{"data": out})
}

// mergeSession 给 emby/player 命令补上当前会话四件套。调用方显式传了的键不动。
func mergeSession(ctx context.Context, args map[string]any) {
	cur, _ := bus.Invoke(ctx, "emby.currentSession", nil) // 没登录回 nil,不是错
	if m, ok := cur.(map[string]any); ok {
		for _, k := range []string{"server", "token", "user_id"} {
			if _, has := args[k]; !has {
				args[k] = m[k]
			}
		}
	}
	if _, has := args["device_id"]; !has {
		dev := config.Current().DeviceID
		if dev == "" {
			dev = fallbackDeviceID
		}
		args["device_id"] = dev
	}
}

// touch 记一次手机页来访;connected 翻转时发 companion.status。
func touch() {
	st.mu.Lock()
	flipped := !st.connected
	st.connected, st.lastSeen = true, time.Now()
	if st.idle == nil {
		st.idle = time.AfterFunc(connectedWindow, idleOut)
	} else {
		st.idle.Reset(connectedWindow)
	}
	s := statusLocked()
	st.mu.Unlock()
	if flipped {
		bus.Emit("companion.status", s, "")
	}
}

func idleOut() {
	st.mu.Lock()
	// 定时器触发和新请求 Reset 可能擦肩而过,以 lastSeen 为准
	if !st.connected || time.Since(st.lastSeen) < connectedWindow {
		st.mu.Unlock()
		return
	}
	st.connected = false
	s := statusLocked()
	st.mu.Unlock()
	bus.Emit("companion.status", s, "")
}

// detectLANIP 先问内核「往外走用哪块网卡」:UDP Dial 不发包,只选路由。
// 安卓 11+ 上枚举网卡要 netlink 权限会被拦,所以遍历网卡只当兜底。
func detectLANIP() (net.IP, error) {
	for _, dst := range []net.IP{net.IPv4(223, 5, 5, 5), net.IPv4(8, 8, 8, 8)} {
		c, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: dst, Port: 53})
		if err != nil {
			continue // 没有这条路由,换下一个方向
		}
		ip := c.LocalAddr().(*net.UDPAddr).IP
		_ = c.Close()
		if !ip.IsLoopback() && !ip.IsUnspecified() {
			return ip, nil
		}
	}
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil, fmt.Errorf("探测不到局域网地址:%v", err)
	}
	for _, a := range addrs {
		if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsPrivate() {
			return n.IP, nil
		}
	}
	return nil, errors.New("探测不到局域网地址:没有路由可走,也没有私网 IPv4 网卡")
}
