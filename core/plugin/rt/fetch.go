package rt

// 插件 fetch(SPEC 5.3):Web 语义 + lp 扩展字段。
//
// 默认超时:连接 10 秒 + 空闲 30 秒,不设整体超时(D454)。慢链路拉 4MB 合法要 29~62 秒,
// 整体超时会一刀切死(见 core/httpx 的同一段教训)。

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/dop251/goja"

	"linplayer/core/httpx"
)

// BrowserUA 插件请求没设 UA 时补的浏览器 UA:不发 UA 的请求常吃 403(D63)。
const BrowserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

const (
	defConnect = 10 * time.Second
	defIdle    = 30 * time.Second
)

type fetchReq struct {
	URL      string
	Method   string
	Headers  map[string]string
	Body     []byte
	Redirect string
	LP       struct {
		Insecure  bool
		Direct    bool
		CookieJar string
		Timeout   struct{ Connect, Idle, Total int }
	}
}

// bodyHandle 响应体句柄:JS 侧按需读,空闲超时关掉。
type bodyHandle struct {
	rc   io.ReadCloser
	idle time.Duration
	t    *time.Timer
	stop func()
}

func (r *Runtime) installFetch(n *goja.Object) {
	vm := r.vm
	var inflight sync.Map // id → cancel
	var seq int64
	_ = n.Set("fetch", func(o map[string]any) goja.Value {
		req, err := parseFetchReq(r, o)
		if err != nil {
			panic(r.newPluginError(KindInvalid, err.Error()))
		}
		seq++
		id := seq
		ctx, cancel := context.WithCancel(context.Background())
		inflight.Store(id, cancel)
		p := r.async(func() (any, error) {
			res, err := r.doFetch(ctx, req)
			if err != nil {
				inflight.Delete(id)
				cancel()
				return nil, err
			}
			h := r.bodySeq.Add(1)
			bh := &bodyHandle{rc: res.Body, idle: req.idle(), stop: func() { inflight.Delete(id); cancel() }}
			bh.t = time.AfterFunc(bh.idle, func() { _ = res.Body.Close() })
			r.bodies.Store(h, bh)
			hdr := map[string]string{}
			for k := range res.Header {
				hdr[strings.ToLower(k)] = strings.Join(res.Header.Values(k), ", ")
			}
			return map[string]any{
				"status": res.StatusCode, "statusText": http.StatusText(res.StatusCode), "headers": hdr,
				"url": res.Request.URL.String(), "redirected": res.Request.URL.String() != req.URL, "handle": h,
			}, nil
		})
		out := vm.NewObject()
		_ = out.Set("id", id)
		_ = out.Set("promise", p)
		return out
	})
	_ = n.Set("fetchAbort", func(id int64) {
		if c, ok := inflight.Load(id); ok {
			c.(context.CancelFunc)()
		}
	})
	_ = n.Set("bodyRead", func(h int64) goja.Value {
		return r.async(func() (any, error) {
			bh := r.body(h)
			if bh == nil {
				return nil, nil
			}
			buf := make([]byte, 64<<10)
			k, err := bh.rc.Read(buf)
			bh.t.Reset(bh.idle)
			if k > 0 {
				return buf[:k], nil
			}
			r.closeBody(h)
			if err != nil && !errors.Is(err, io.EOF) {
				return nil, &Error{Kind: KindNetwork, Message: "读取响应中断", Detail: err.Error()}
			}
			return func() goja.Value { return goja.Null() }, nil
		})
	})
	_ = n.Set("bodyAll", func(h int64) goja.Value {
		return r.async(func() (any, error) {
			bh := r.body(h)
			if bh == nil {
				return []byte{}, nil
			}
			defer r.closeBody(h)
			var out bytes.Buffer
			buf := make([]byte, 64<<10)
			for {
				k, err := bh.rc.Read(buf)
				out.Write(buf[:k])
				bh.t.Reset(bh.idle)
				if errors.Is(err, io.EOF) {
					return out.Bytes(), nil
				}
				if err != nil {
					return nil, &Error{Kind: KindNetwork, Message: "读取响应中断", Detail: err.Error()}
				}
			}
		})
	})
	_ = n.Set("bodyClose", func(h int64) { r.closeBody(h) })
}

func (r *Runtime) body(h int64) *bodyHandle {
	v, ok := r.bodies.Load(h)
	if !ok {
		return nil
	}
	return v.(*bodyHandle)
}

func (r *Runtime) closeBody(h int64) {
	if v, ok := r.bodies.LoadAndDelete(h); ok {
		bh := v.(*bodyHandle)
		bh.t.Stop()
		_ = bh.rc.Close()
		bh.stop()
	}
}

func parseFetchReq(r *Runtime, o map[string]any) (*fetchReq, error) {
	q := &fetchReq{Headers: map[string]string{}}
	q.URL, _ = o["url"].(string)
	q.Method, _ = o["method"].(string)
	q.Redirect, _ = o["redirect"].(string)
	if h, ok := o["headers"].(map[string]any); ok {
		for k, v := range h {
			q.Headers[k] = toStr(v)
		}
	}
	if b, ok := o["body"].(goja.ArrayBuffer); ok {
		q.Body = b.Bytes()
	}
	if lp, ok := o["lp"].(map[string]any); ok {
		q.LP.Insecure, _ = lp["insecure"].(bool)
		q.LP.Direct, _ = lp["direct"].(bool)
		q.LP.CookieJar, _ = lp["cookieJar"].(string)
		if t, ok := lp["timeout"].(map[string]any); ok {
			q.LP.Timeout.Connect = toInt(t["connect"])
			q.LP.Timeout.Idle = toInt(t["idle"])
			q.LP.Timeout.Total = toInt(t["total"])
		}
	}
	u, err := url.Parse(q.URL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, errors.New("只支持 http/https 地址: " + q.URL)
	}
	return q, nil
}

func (q *fetchReq) idle() time.Duration {
	if q.LP.Timeout.Idle > 0 {
		return time.Duration(q.LP.Timeout.Idle) * time.Millisecond
	}
	return defIdle
}

// doFetch 发请求(已在 goroutine 里)。
func (r *Runtime) doFetch(ctx context.Context, q *fetchReq) (*http.Response, error) {
	start := time.Now()
	entry := RequestEntry{TS: start.UnixMilli(), Method: q.Method, URL: q.URL}
	res, err := r.roundTrip(ctx, q)
	entry.MS = time.Since(start).Milliseconds()
	if err != nil {
		entry.Err = err.Error()
	} else {
		entry.Status = res.StatusCode
	}
	r.reqs.add(entry)
	return res, err
}

func (r *Runtime) roundTrip(ctx context.Context, q *fetchReq) (*http.Response, error) {
	if q.LP.Timeout.Total > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(q.LP.Timeout.Total)*time.Millisecond)
		_ = cancel // body 读完前不能取消;超时自然触发
	}
	u, _ := url.Parse(q.URL)
	if err := r.checkLAN(ctx, u); err != nil {
		return nil, err
	}
	method := q.Method
	if method == "" {
		method = http.MethodGet
	}
	var body io.Reader
	if q.Body != nil {
		body = bytes.NewReader(q.Body)
	}
	req, err := http.NewRequestWithContext(ctx, method, q.URL, body)
	if err != nil {
		return nil, &Error{Kind: KindInvalid, Message: "请求参数不对", Detail: err.Error()}
	}
	for k, v := range q.Headers {
		if strings.EqualFold(k, "host") {
			req.Host = v // Host 头要走 req.Host,写进 Header 不生效(D455)
			continue
		}
		req.Header.Set(k, v)
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", BrowserUA)
	}
	jarName := q.LP.CookieJar
	if jarName == "" {
		jarName = r.opt.ID
	}
	jar := r.jars.get(jarName)
	c := &http.Client{
		Transport: r.transport(q),
		Jar:       jar,
		CheckRedirect: func(nr *http.Request, via []*http.Request) error {
			if q.Redirect == "manual" {
				return http.ErrUseLastResponse
			}
			if q.Redirect == "error" {
				return &Error{Kind: KindNetwork, Message: "请求被重定向"}
			}
			if len(via) >= 20 {
				return errors.New("重定向次数过多")
			}
			return r.checkLAN(nr.Context(), nr.URL)
		},
	}
	res, err := c.Do(req)
	if err != nil {
		var pe *Error
		if errors.As(err, &pe) {
			return nil, pe
		}
		if ctx.Err() != nil {
			return nil, &Error{Kind: KindTimeout, Message: "请求已取消或超时", Detail: err.Error()}
		}
		return nil, &Error{Kind: KindNetwork, Message: "网络请求失败", Detail: err.Error()}
	}
	r.jars.persist(jarName)
	return res, nil
}

type transportKey struct {
	insecure, direct bool
	connect, idle    time.Duration
	proxy            string
}

// transport 按 lp 选项取一个 Transport(同选项复用连接池)。代理跟随应用设置(D96),回环永不走代理。
func (r *Runtime) transport(q *fetchReq) http.RoundTripper {
	k := transportKey{insecure: q.LP.Insecure, direct: q.LP.Direct, connect: defConnect, idle: q.idle(), proxy: httpx.ProxyURL()}
	if q.LP.Timeout.Connect > 0 {
		k.connect = time.Duration(q.LP.Timeout.Connect) * time.Millisecond
	}
	if t, ok := r.transports.Load(k); ok {
		return t.(http.RoundTripper)
	}
	t := r.newTransport(k)
	r.transports.Store(k, t)
	return t
}

func (r *Runtime) newTransport(k transportKey) http.RoundTripper {
	connect := k.connect
	d := &net.Dialer{Timeout: connect, KeepAlive: 30 * time.Second}
	useProxy := !k.direct && k.proxy != ""
	if !useProxy && !r.opt.LAN {
		// 直连时在拨号那一刻再查一次实际 IP:域名解析到私网(DNS 重绑定)也拦得住。
		d.Control = func(network, address string, _ syscall.RawConn) error {
			host, port, _ := net.SplitHostPort(address)
			if ip := net.ParseIP(host); ip != nil && r.blockedIP(ip, port) {
				return lanError(host)
			}
			return nil
		}
	}
	t := &http.Transport{
		DialContext:           d.DialContext,
		TLSHandshakeTimeout:   connect,
		ResponseHeaderTimeout: k.idle,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       90 * time.Second,
		ForceAttemptHTTP2:     true,
	}
	if useProxy {
		t.Proxy = func(req *http.Request) (*url.URL, error) {
			if httpx.IsLoopbackURL(req.URL.String()) {
				return nil, nil
			}
			return url.Parse(k.proxy)
		}
	}
	if k.insecure {
		t.TLSClientConfig = &tls.Config{InsecureSkipVerify: true} //nolint:gosec // D97:插件显式要求忽略证书
	}
	return t
}

// checkLAN manifest 没声明 lan 时拦截私网目标(L1,D247)。走代理时拨号拿不到目标 IP,所以先解析一遍。
func (r *Runtime) checkLAN(ctx context.Context, u *url.URL) error {
	if r.opt.LAN {
		return nil
	}
	host, port := u.Hostname(), u.Port()
	if port == "" {
		port = map[string]string{"https": "443"}[u.Scheme]
		if port == "" {
			port = "80"
		}
	}
	if strings.EqualFold(host, "localhost") {
		if r.blockedIP(net.IPv4(127, 0, 0, 1), port) {
			return lanError(host)
		}
		return nil
	}
	if ip := net.ParseIP(host); ip != nil {
		if r.blockedIP(ip, port) {
			return lanError(host)
		}
		return nil
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil // 解析失败交给真正的请求报网络错误
	}
	for _, a := range ips {
		if r.blockedIP(a.IP, port) {
			return lanError(host)
		}
	}
	return nil
}

func (r *Runtime) blockedIP(ip net.IP, port string) bool {
	if !(ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified()) {
		return false
	}
	// 宿主自己的本地服务不算局域网
	if ip.IsLoopback() && r.opt.Host.HostPort != nil {
		if p, _ := strconv.Atoi(port); p != 0 && p == r.opt.Host.HostPort() {
			return false
		}
	}
	return true
}

func lanError(host string) *Error {
	return &Error{Kind: KindPermission, Message: "插件未声明 lan,不能访问局域网地址 " + host, Detail: "未声明 lan"}
}

func toStr(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case nil:
		return ""
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	case int64:
		return strconv.FormatInt(x, 10)
	}
	return ""
}

func toInt(v any) int {
	switch x := v.(type) {
	case int64:
		return int(x)
	case float64:
		return int(x)
	case int:
		return x
	}
	return 0
}
