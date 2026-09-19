package rt

import (
	"crypto/rand"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"mime"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/dop251/goja"
	"golang.org/x/text/encoding/htmlindex"
)

//go:embed prelude.js
var preludeJS string

var preludePrg = goja.MustCompile("prelude.js", preludeJS, true)

// installGlobals 注入 __lp 原生函数、Web 全局与 SDK 对象。只在 New 里、循环启动前调。
func (r *Runtime) installGlobals() error {
	vm := r.vm
	n := vm.NewObject()
	set := func(name string, f any) { _ = n.Set(name, f) }

	set("utf8Encode", func(s string) goja.ArrayBuffer { return vm.NewArrayBuffer([]byte(s)) })
	set("decode", func(b goja.ArrayBuffer, label string) string { return decodeBytes(b.Bytes(), label) })
	set("decodeAuto", func(b goja.ArrayBuffer, contentType string) string {
		_, params, _ := mime.ParseMediaType(contentType)
		return decodeBytes(b.Bytes(), params["charset"])
	})
	set("urlParse", func(href, base string) goja.Value { return r.urlParts(href, base) })
	set("urlFormat", func(p map[string]any) goja.Value { return r.urlFormat(p) })
	r.installFetch(n)
	if err := vm.Set("__lp", n); err != nil {
		return err
	}

	// console → 每插件 500 条内存日志(D285)
	con := vm.NewObject()
	for _, lv := range []string{"log", "info", "warn", "error", "debug"} {
		level := lv
		if level == "log" {
			level = "info"
		}
		_ = con.Set(lv, func(c goja.FunctionCall) goja.Value {
			parts := make([]string, len(c.Arguments))
			for i, a := range c.Arguments {
				parts[i] = r.display(a)
			}
			r.logs.add(LogEntry{TS: nowMS(), Level: level, Msg: strings.Join(parts, " ")})
			return goja.Undefined()
		})
	}
	_ = vm.Set("console", con)

	r.installTimers()

	_ = vm.Set("atob", func(s string) string {
		b, err := base64.StdEncoding.DecodeString(strings.TrimRight(strings.Map(dropSpace, s), "=") + pad(s))
		if err != nil {
			panic(vm.NewTypeError("atob: 不是合法的 base64"))
		}
		// atob 返回 latin1 字符串:每字节一个字符
		rs := make([]rune, len(b))
		for i, c := range b {
			rs[i] = rune(c)
		}
		return string(rs)
	})
	_ = vm.Set("btoa", func(s string) string {
		b := make([]byte, 0, len(s))
		for _, c := range s {
			if c > 0xff {
				panic(vm.NewTypeError("btoa: 字符超出 Latin1 范围"))
			}
			b = append(b, byte(c))
		}
		return base64.StdEncoding.EncodeToString(b)
	})
	cr := vm.NewObject()
	_ = cr.Set("getRandomValues", func(c goja.FunctionCall) goja.Value {
		arr := c.Argument(0)
		o := arr.ToObject(vm)
		buf, ok := o.Get("buffer").Export().(goja.ArrayBuffer)
		if !ok {
			panic(vm.NewTypeError("getRandomValues 需要 TypedArray"))
		}
		off := int(o.Get("byteOffset").ToInteger())
		ln := int(o.Get("byteLength").ToInteger())
		_, _ = rand.Read(buf.Bytes()[off : off+ln])
		return arr
	})
	_ = cr.Set("randomUUID", func() string {
		var b [16]byte
		_, _ = rand.Read(b[:])
		b[6] = b[6]&0x0f | 0x40
		b[8] = b[8]&0x3f | 0x80
		return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
	})
	_ = vm.Set("crypto", cr)

	if _, err := vm.RunProgram(preludePrg); err != nil {
		return fmt.Errorf("prelude: %w", err)
	}
	return r.installSDK()
}

func dropSpace(r rune) rune {
	if r == ' ' || r == '\n' || r == '\r' || r == '\t' {
		return -1
	}
	return r
}

// pad 补齐 atob 输入缺的 '='(浏览器 atob 接受不带填充的输入)。
func pad(s string) string {
	s = strings.TrimRight(strings.Map(dropSpace, s), "=")
	return strings.Repeat("=", (4-len(s)%4)%4)
}

// decodeBytes 按字符集名解码;认不出的字符集按 UTF-8。GBK 页面很常见(D99)。
func decodeBytes(b []byte, label string) string {
	label = strings.ToLower(strings.TrimSpace(label))
	if label == "" || label == "utf-8" || label == "utf8" {
		if utf8.Valid(b) {
			return string(b)
		}
		return strings.ToValidUTF8(string(b), "�")
	}
	enc, err := htmlindex.Get(label)
	if err != nil {
		return strings.ToValidUTF8(string(b), "�")
	}
	out, err := enc.NewDecoder().Bytes(b)
	if err != nil {
		return strings.ToValidUTF8(string(b), "�")
	}
	return string(out)
}

// display console 输出用:对象走 JSON,其余 String()。
func (r *Runtime) display(v goja.Value) string {
	if o, ok := v.(*goja.Object); ok {
		if e := o.Get("stack"); e != nil && !goja.IsUndefined(e) {
			return e.String()
		}
		if s, err := r.stringify(v); err == nil {
			return s
		}
	}
	return v.String()
}

// ---------------------------------------------------------------- URL

func (r *Runtime) urlParts(href, base string) goja.Value {
	var u *url.URL
	var err error
	if base != "" {
		b, e := url.Parse(base)
		if e != nil || !b.IsAbs() {
			return goja.Null()
		}
		u, err = b.Parse(href)
	} else {
		u, err = url.Parse(href)
	}
	if err != nil || !u.IsAbs() {
		return goja.Null()
	}
	return r.urlValue(u)
}

func (r *Runtime) urlValue(u *url.URL) goja.Value {
	if u.Path == "" && (u.Scheme == "http" || u.Scheme == "https") {
		u.Path = "/"
	}
	user, pass := "", ""
	if u.User != nil {
		user = u.User.Username()
		pass, _ = u.User.Password()
	}
	search := ""
	if u.RawQuery != "" {
		search = "?" + u.RawQuery
	}
	hash := ""
	if u.Fragment != "" {
		hash = "#" + u.EscapedFragment()
	}
	origin := "null"
	if u.Host != "" {
		origin = u.Scheme + "://" + u.Host
	}
	o := r.vm.NewObject()
	for k, v := range map[string]string{
		"href": u.String(), "origin": origin, "protocol": u.Scheme + ":", "username": user, "password": pass,
		"host": u.Host, "hostname": u.Hostname(), "port": u.Port(), "pathname": u.EscapedPath(),
		"search": search, "hash": hash,
	} {
		_ = o.Set(k, v)
	}
	return o
}

func (r *Runtime) urlFormat(p map[string]any) goja.Value {
	s := func(k string) string { v, _ := p[k].(string); return v }
	u := &url.URL{Scheme: strings.TrimSuffix(s("protocol"), ":"), Host: s("hostname")}
	if port := s("port"); port != "" {
		u.Host += ":" + port
	}
	if s("username") != "" {
		if s("password") != "" {
			u.User = url.UserPassword(s("username"), s("password"))
		} else {
			u.User = url.User(s("username"))
		}
	}
	if err := setEscapedPath(u, s("pathname")); err != nil {
		return goja.Null()
	}
	u.RawQuery = strings.TrimPrefix(s("search"), "?")
	u.Fragment = strings.TrimPrefix(s("hash"), "#")
	return r.urlValue(u)
}

func setEscapedPath(u *url.URL, p string) error {
	if p != "" && !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	dec, err := url.PathUnescape(p)
	if err != nil {
		return err
	}
	u.Path, u.RawPath = dec, p
	return nil
}

// ---------------------------------------------------------------- 定时器

func (r *Runtime) installTimers() {
	vm := r.vm
	add := func(c goja.FunctionCall, repeat bool) goja.Value {
		fn, ok := goja.AssertFunction(c.Argument(0))
		if !ok {
			panic(vm.NewTypeError("定时器回调必须是函数"))
		}
		d := time.Duration(c.Argument(1).ToInteger()) * time.Millisecond
		if d < 0 {
			d = 0
		}
		extra := append([]goja.Value(nil), c.Arguments[min(2, len(c.Arguments)):]...)
		r.timerSeq++
		id := r.timerSeq
		var fire func()
		fire = func() {
			r.post(func() {
				if _, live := r.timers[id]; !live {
					return
				}
				if !repeat {
					delete(r.timers, id)
				}
				if _, err := fn(goja.Undefined(), extra...); err != nil {
					r.logs.add(LogEntry{TS: nowMS(), Level: "error", Msg: "定时器回调出错: " + err.Error()})
				}
				if repeat {
					if _, live := r.timers[id]; live {
						r.timers[id] = time.AfterFunc(max(d, time.Millisecond), fire)
					}
				}
			})
		}
		r.timers[id] = time.AfterFunc(d, fire)
		return vm.ToValue(id)
	}
	clear := func(c goja.FunctionCall) goja.Value {
		id := c.Argument(0).ToInteger()
		if t, ok := r.timers[id]; ok {
			t.Stop()
			delete(r.timers, id)
		}
		return goja.Undefined()
	}
	_ = vm.Set("setTimeout", func(c goja.FunctionCall) goja.Value { return add(c, false) })
	_ = vm.Set("setInterval", func(c goja.FunctionCall) goja.Value { return add(c, true) })
	_ = vm.Set("clearTimeout", clear)
	_ = vm.Set("clearInterval", clear)
}

// ---------------------------------------------------------------- Promise 桥

// async 在 goroutine 里做 work,完成后回循环 resolve/reject。只能在循环里调。
func (r *Runtime) async(work func() (any, error)) goja.Value {
	p, resolve, reject := r.vm.NewPromise()
	go func() {
		v, err := work()
		r.post(func() {
			if err != nil {
				_ = reject(r.errValue(err))
				return
			}
			_ = resolve(r.jsValue(v))
		})
	}()
	return r.vm.ToValue(p)
}

// jsValue Go 结果 → JS 值。只能在循环里调。
func (r *Runtime) jsValue(v any) goja.Value {
	switch x := v.(type) {
	case nil:
		return goja.Undefined()
	case goja.Value:
		return x
	case func() goja.Value:
		return x()
	case []byte:
		return r.vm.ToValue(r.vm.NewArrayBuffer(x))
	case string, bool, int, int64, float64:
		return r.vm.ToValue(x)
	case json.RawMessage:
		out, err := r.parseJSON(string(x))
		if err != nil {
			return goja.Undefined()
		}
		return out
	}
	out, err := r.fromGo(v)
	if err != nil {
		return goja.Undefined()
	}
	return out
}

// errValue Go 错误 → PluginError 对象。
func (r *Runtime) errValue(err error) goja.Value {
	e, ok := err.(*Error)
	if !ok {
		e = &Error{Kind: KindInternal, Message: err.Error()}
	}
	o := r.newPluginError(e.Kind, e.Message)
	if e.RetryAfter > 0 {
		_ = o.Set("retryAfter", e.RetryAfter)
	}
	if e.Detail != "" {
		_ = o.Set("detail", e.Detail)
	}
	return o
}

// newAbortController 在循环里 new 一个 AbortController。
func (r *Runtime) newAbortController() *goja.Object {
	o, err := r.vm.New(r.vm.Get("AbortController"))
	if err != nil {
		panic(err)
	}
	return o
}

// bytesOf 把 JS 的 string / ArrayBuffer / TypedArray 取成字节。
func (r *Runtime) bytesOf(v goja.Value) ([]byte, bool) {
	if v == nil || goja.IsUndefined(v) || goja.IsNull(v) {
		return nil, true
	}
	switch x := v.Export().(type) {
	case string:
		return []byte(x), true
	case goja.ArrayBuffer:
		return x.Bytes(), true
	case []byte:
		return x, true
	}
	if o, ok := v.(*goja.Object); ok {
		if ab, ok := o.Get("buffer").Export().(goja.ArrayBuffer); ok {
			off := int(o.Get("byteOffset").ToInteger())
			ln := int(o.Get("byteLength").ToInteger())
			return ab.Bytes()[off : off+ln], true
		}
	}
	return nil, false
}

// absent 参数没给:goja 的反射包装对缺省参数传 nil 接口而不是 undefined。
func absent(v goja.Value) bool { return v == nil || goja.IsUndefined(v) || goja.IsNull(v) }
