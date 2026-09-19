package rt

// 子运行时与运行时打包(SPEC 5.7,D127 D316)。
//
// 子运行时在自己的 goroutine,所以可以给同步阻塞的 req()(drpy 靠它);主运行时只有异步 fetch。
// 预算同样 30 秒,只计 JS 时间:同步宿主函数里等网络的时间从账上扣掉。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"path"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dop251/goja"
	"github.com/evanw/esbuild/pkg/api"
)

type subRuntime struct {
	parent *Runtime
	vm     *goja.Runtime
	ops    chan func()
	quit   chan struct{}
	host   atomic.Int64 // 花在同步宿主函数里的纳秒(不计入预算)
	jar    string
	id     int64
}

var subSeq atomic.Int64

func (r *Runtime) installJS(o *goja.Object) {
	vm := r.vm
	_ = o.Set("createContext", func(c goja.FunctionCall) goja.Value {
		var globals map[string]goja.Value
		withSync := false
		jar := r.opt.ID
		if opt, ok := c.Argument(0).(*goja.Object); ok {
			if g, ok := opt.Get("globals").(*goja.Object); ok {
				globals = map[string]goja.Value{}
				for _, k := range g.Keys() {
					globals[k] = g.Get(k)
				}
			}
			if v := opt.Get("withSyncHost"); v != nil {
				withSync = v.ToBoolean()
			}
			if v := opt.Get("cookieJar"); v != nil && !goja.IsUndefined(v) {
				jar = v.String()
			}
		}
		s, err := r.newSub(globals, withSync, jar)
		if err != nil {
			panic(r.newPluginError(KindInternal, "子运行时创建失败:"+err.Error()))
		}
		p, resolve, _ := vm.NewPromise()
		_ = resolve(s.handle())
		return vm.ToValue(p)
	})
	_ = o.Set("bundle", func(entry string, opt *goja.Object) goja.Value {
		var resolveFn goja.Callable
		if opt != nil {
			resolveFn, _ = goja.AssertFunction(opt.Get("resolve"))
		}
		if resolveFn == nil {
			r.throw(KindInvalid, "js.bundle 需要 resolve 函数")
		}
		globalName := ""
		if opt != nil {
			if g := opt.Get("globalName"); g != nil && !goja.IsUndefined(g) {
				globalName = g.String()
			}
		}
		return r.async(func() (any, error) {
			return Bundle(entry, globalName, func(p, importer string) (string, bool, error) {
				v, err := r.awaitCall(resolveFn, p, importer)
				if err != nil {
					return "", false, err
				}
				if v == nil {
					return "", false, nil
				}
				s, _ := v.(string)
				return s, true, nil
			})
		})
	})
}

// Bundle 用 esbuild 把 ESM 打成 ES2017 IIFE。load(路径, 导入者) 返回模块源码;路径是已按导入者拼好的绝对形式
// (保留自定义 scheme,如 assets://libs/x.js)。
func Bundle(entry, globalName string, load func(p, importer string) (string, bool, error)) (string, error) {
	var mu sync.Mutex
	srcs := map[string]string{}
	plug := api.Plugin{Name: "lp-resolve", Setup: func(b api.PluginBuild) {
		b.OnResolve(api.OnResolveOptions{Filter: ".*"}, func(a api.OnResolveArgs) (api.OnResolveResult, error) {
			p := joinModule(a.Importer, a.Path)
			src, ok, err := load(p, a.Importer)
			if err != nil {
				return api.OnResolveResult{}, err
			}
			if !ok {
				if !strings.Contains(p, "://") && !strings.HasPrefix(p, ".") && !strings.HasPrefix(p, "/") {
					// 裸模块名(如 UMD 库里 try 着的 require('crypto'))交还 esbuild:
					// 它对 try 里解析不到的 require 会推迟成运行时错误,由库自己兜住
					return api.OnResolveResult{}, nil
				}
				return api.OnResolveResult{}, fmt.Errorf("找不到模块 %s", p)
			}
			mu.Lock()
			srcs[p] = src
			mu.Unlock()
			return api.OnResolveResult{Path: p, Namespace: "lp"}, nil
		})
		b.OnLoad(api.OnLoadOptions{Filter: ".*", Namespace: "lp"}, func(a api.OnLoadArgs) (api.OnLoadResult, error) {
			mu.Lock()
			src := srcs[a.Path]
			mu.Unlock()
			loader := api.LoaderJS
			if strings.HasSuffix(a.Path, ".json") {
				loader = api.LoaderJSON
			} else if strings.HasSuffix(a.Path, ".ts") {
				loader = api.LoaderTS
			}
			return api.OnLoadResult{Contents: &src, Loader: loader}, nil
		})
	}}
	res := api.Build(api.BuildOptions{
		EntryPoints: []string{entry},
		Bundle:      true,
		Format:      api.FormatIIFE,
		GlobalName:  globalName,
		Target:      api.ES2017,
		Plugins:     []api.Plugin{plug},
		Write:       false,
		LogLevel:    api.LogLevelSilent,
	})
	if len(res.Errors) > 0 {
		e := res.Errors[0]
		loc := ""
		if e.Location != nil {
			loc = fmt.Sprintf("%s:%d: ", e.Location.File, e.Location.Line)
		}
		return "", &Error{Kind: KindParseFailed, Message: "打包失败:" + loc + e.Text}
	}
	return string(res.OutputFiles[0].Contents), nil
}

// joinModule 按导入者拼模块路径。自定义 scheme 不能交给 url.ResolveReference(它会把 scheme 后第一段当主机)。
func joinModule(importer, p string) string {
	if !strings.HasPrefix(p, "./") && !strings.HasPrefix(p, "../") {
		return p
	}
	scheme, rest := "", importer
	if i := strings.Index(importer, "://"); i >= 0 {
		scheme, rest = importer[:i+3], importer[i+3:]
	}
	return scheme + strings.TrimPrefix(path.Join(path.Dir("/"+rest), p), "/")
}

// awaitCall 在循环外调一个 JS 函数并等结果(Promise 会等它落定)。返回 Export 后的 Go 值。
func (r *Runtime) awaitCall(fn goja.Callable, args ...any) (any, error) {
	type res struct {
		v   any
		err error
	}
	ch := make(chan res, 1)
	r.post(func() {
		vals := make([]goja.Value, len(args))
		for i, a := range args {
			vals[i] = r.vm.ToValue(a)
		}
		v, err := fn(goja.Undefined(), vals...)
		if err != nil {
			ch <- res{err: r.toError(err)}
			return
		}
		p, ok := v.Export().(*goja.Promise)
		if !ok {
			ch <- res{v: exportJSON(r, v)}
			return
		}
		then, _ := goja.AssertFunction(r.vm.ToValue(p).ToObject(r.vm).Get("then"))
		_, _ = then(r.vm.ToValue(p),
			r.vm.ToValue(func(x goja.Value) { ch <- res{v: exportJSON(r, x)} }),
			r.vm.ToValue(func(x goja.Value) { ch <- res{err: r.toError(x)} }))
	})
	select {
	case x := <-ch:
		return x.v, x.err
	case <-r.done:
		return nil, &Error{Kind: KindUnsupported, Message: "插件已停止运行"}
	}
}

// exportJSON 把 JS 值转成普通 Go 值(对象走 JSON,避免带出 goja 内部类型)。
func exportJSON(r *Runtime, v goja.Value) any {
	if v == nil || goja.IsUndefined(v) || goja.IsNull(v) {
		return nil
	}
	if _, ok := v.(*goja.Object); !ok {
		return v.Export()
	}
	s, err := r.stringify(v)
	if err != nil {
		return nil
	}
	var out any
	_ = json.Unmarshal([]byte(s), &out)
	return out
}

func (r *Runtime) newSub(globals map[string]goja.Value, withSync bool, jar string) (*subRuntime, error) {
	s := &subRuntime{parent: r, vm: goja.New(), ops: make(chan func(), 16), quit: make(chan struct{}), jar: jar, id: subSeq.Add(1)}
	vm := s.vm
	vm.SetMaxCallStackSize(4000)
	con := vm.NewObject()
	for _, lv := range []string{"log", "info", "warn", "error", "debug"} {
		level := lv
		_ = con.Set(lv, func(c goja.FunctionCall) goja.Value {
			parts := make([]string, len(c.Arguments))
			for i, a := range c.Arguments {
				parts[i] = a.String()
			}
			r.logs.add(LogEntry{TS: nowMS(), Level: level, Msg: "[子运行时] " + strings.Join(parts, " ")})
			return goja.Undefined()
		})
	}
	_ = vm.Set("console", con)
	_ = vm.Set("atob", r.vm.Get("atob").Export())
	_ = vm.Set("btoa", r.vm.Get("btoa").Export())
	for k, v := range globals {
		if fn, ok := goja.AssertFunction(v); ok {
			_ = vm.Set(k, s.proxyFunc(fn))
			continue
		}
		s2, err := r.stringify(v)
		if err != nil {
			return nil, fmt.Errorf("全局 %s 不能序列化: %w", k, err)
		}
		pv, err := vm.RunString("(" + s2 + ")")
		if err != nil {
			return nil, err
		}
		_ = vm.Set(k, pv)
	}
	if withSync {
		s.installSyncHost()
	}
	r.subs.Store(s.id, s)
	go s.loop()
	return s, nil
}

func (s *subRuntime) loop() {
	for {
		select {
		case f := <-s.ops:
			f()
		case <-s.quit:
			return
		}
	}
}

func (s *subRuntime) close() {
	select {
	case <-s.quit:
	default:
		close(s.quit)
		s.vm.Interrupt("closed")
	}
	s.parent.subs.Delete(s.id)
}

// exec 在子运行时里跑一步,只计 JS 时间地套 30 秒预算。
func (s *subRuntime) exec(f func() (goja.Value, error)) (json.RawMessage, error) {
	type out struct {
		b   json.RawMessage
		err error
	}
	ch := make(chan out, 1)
	job := func() {
		start := time.Now()
		host0 := s.host.Load()
		var t *time.Timer
		var check func()
		check = func() {
			used := time.Since(start) - time.Duration(s.host.Load()-host0)
			if used >= BudgetData {
				s.vm.Interrupt(errBudget)
				return
			}
			t = time.AfterFunc(BudgetData-used, check)
		}
		t = time.AfterFunc(BudgetData, check)
		v, err := f()
		t.Stop()
		s.vm.ClearInterrupt()
		if err != nil {
			var ie *goja.InterruptedError
			if errors.As(err, &ie) {
				e := &Error{Kind: KindTimeout, Message: "子运行时运行超时(超过 30 秒),已打断"}
				s.parent.reportError(e)
				ch <- out{err: e}
				return
			}
			var ex *goja.Exception
			if errors.As(err, &ex) {
				ch <- out{err: valueToError(s.vm, ex.Value())}
				return
			}
			ch <- out{err: &Error{Kind: KindInternal, Message: "子运行时出错", Detail: err.Error()}}
			return
		}
		if p, ok := v.Export().(*goja.Promise); ok {
			// 子运行时没有事件循环:同步宿主函数让 Promise 在这一步里就落定
			switch p.State() {
			case goja.PromiseStateFulfilled:
				v = p.Result()
			case goja.PromiseStateRejected:
				ch <- out{err: valueToError(s.vm, p.Result())}
				return
			default:
				ch <- out{err: &Error{Kind: KindInternal, Message: "子运行时返回了未完成的 Promise(子运行时没有事件循环)"}}
				return
			}
		}
		b, err := s.stringify(v)
		ch <- out{b: b, err: err}
	}
	select {
	case s.ops <- job:
	case <-s.quit:
		return nil, &Error{Kind: KindUnsupported, Message: "子运行时已销毁"}
	}
	select {
	case o := <-ch:
		return o.b, o.err
	case <-s.quit:
		return nil, &Error{Kind: KindUnsupported, Message: "子运行时已销毁"}
	}
}

func (s *subRuntime) stringify(v goja.Value) (json.RawMessage, error) {
	if v == nil || goja.IsUndefined(v) {
		return json.RawMessage("null"), nil
	}
	fn, _ := goja.AssertFunction(s.vm.Get("JSON").ToObject(s.vm).Get("stringify"))
	out, err := fn(goja.Undefined(), v)
	if err != nil {
		return nil, &Error{Kind: KindInternal, Message: "子运行时返回值不能序列化", Detail: err.Error()}
	}
	if goja.IsUndefined(out) {
		return json.RawMessage("null"), nil
	}
	return json.RawMessage(out.String()), nil
}

// handle 给主运行时的 JsContext 对象。只能在主循环里调。
func (s *subRuntime) handle() goja.Value {
	r := s.parent
	o := r.vm.NewObject()
	_ = o.Set("run", func(code string, name goja.Value) goja.Value {
		file := "context.js"
		if !absent(name) {
			file = name.String()
		}
		return r.async(func() (any, error) {
			b, err := s.exec(func() (goja.Value, error) {
				prg, err := goja.Compile(file, code, false)
				if err != nil {
					return nil, err
				}
				return s.vm.RunProgram(prg)
			})
			return b, err
		})
	})
	_ = o.Set("call", func(c goja.FunctionCall) goja.Value {
		fnName := c.Argument(0).String()
		args := make([]string, 0, len(c.Arguments))
		for _, a := range c.Arguments[1:] {
			js, err := r.stringify(a)
			if err != nil {
				r.throw(KindInvalid, "参数不能序列化")
			}
			args = append(args, js)
		}
		return r.async(func() (any, error) {
			b, err := s.exec(func() (goja.Value, error) {
				fv, err := s.vm.RunString(fnName)
				if err != nil {
					return nil, err
				}
				fn, ok := goja.AssertFunction(fv)
				if !ok {
					return nil, &Error{Kind: KindInvalid, Message: fnName + " 不是函数"}
				}
				vals := make([]goja.Value, len(args))
				for i, a := range args {
					if vals[i], err = s.vm.RunString("(" + a + ")"); err != nil {
						return nil, err
					}
				}
				return fn(goja.Undefined(), vals...)
			})
			return b, err
		})
	})
	_ = o.Set("dispose", func() { s.close() })
	return o
}

// proxyFunc 把主运行时的函数塞进子运行时:同步调用,背后投回主循环等结果。
func (s *subRuntime) proxyFunc(fn goja.Callable) func(goja.FunctionCall) goja.Value {
	return func(c goja.FunctionCall) goja.Value {
		args := make([]any, len(c.Arguments))
		for i, a := range c.Arguments {
			args[i] = a.Export()
		}
		t0 := time.Now()
		v, err := s.parent.awaitCall(fn, args...)
		s.host.Add(int64(time.Since(t0)))
		if err != nil {
			panic(s.vm.NewGoError(err))
		}
		return s.vm.ToValue(v)
	}
}

// installSyncHost drpy 需要的同步宿主函数(SPEC 5.7):req / pdfh / pdfa / pd / joinUrl / local。
func (s *subRuntime) installSyncHost() {
	vm := s.vm
	r := s.parent
	timed := func(f func()) {
		t0 := time.Now()
		f()
		s.host.Add(int64(time.Since(t0)))
	}
	_ = vm.Set("req", func(u string, opt goja.Value) goja.Value {
		var out map[string]any
		timed(func() { out = s.syncReq(u, opt) })
		return vm.ToValue(out)
	})
	_ = vm.Set("pdfh", func(src, rule string, base goja.Value) (v string) {
		b := ""
		if !absent(base) {
			b = base.String()
		}
		timed(func() { v = Pdfh(src, rule, b) })
		return
	})
	_ = vm.Set("pdfa", func(src, rule string) (v []string) {
		timed(func() { v = Pdfa(src, rule) })
		return
	})
	_ = vm.Set("pd", func(src, rule string, base goja.Value) (v string) {
		b := ""
		if !absent(base) {
			b = base.String()
		}
		timed(func() { v = Pd(src, rule, b) })
		return
	})
	_ = vm.Set("joinUrl", JoinURL)
	local := vm.NewObject()
	key := func(a, b string) string { return "local:" + a + "\x00" + b }
	_ = local.Set("get", func(a, b string) string {
		raw := r.kv.Get(key(a, b))
		var v string
		_ = json.Unmarshal(raw, &v)
		return v
	})
	_ = local.Set("set", func(a, b string, v goja.Value) {
		bs, _ := json.Marshal(v.String())
		r.kv.Set(key(a, b), bs)
	})
	_ = local.Set("delete", func(a, b string) { r.kv.Set(key(a, b), nil) })
	_ = vm.Set("local", local)
}

// syncReq drpy 的 req(url, {headers, method, body|data, timeout, redirect, buffer})→{content, headers}。
func (s *subRuntime) syncReq(u string, opt goja.Value) map[string]any {
	r := s.parent
	q := &fetchReq{URL: u, Method: "GET", Headers: map[string]string{}}
	q.LP.CookieJar = s.jar
	timeout := 15 * time.Second
	buffer := 0
	if o, ok := opt.(*goja.Object); ok && o != nil {
		if h, ok := o.Get("headers").(*goja.Object); ok {
			for _, k := range h.Keys() {
				q.Headers[k] = h.Get(k).String()
			}
		}
		if m := o.Get("method"); m != nil && !goja.IsUndefined(m) {
			q.Method = strings.ToUpper(m.String())
		}
		if b := o.Get("body"); b != nil && !goja.IsUndefined(b) && !goja.IsNull(b) {
			q.Body = []byte(b.String())
		} else if d := o.Get("data"); d != nil && !goja.IsUndefined(d) && !goja.IsNull(d) {
			ct := strings.ToLower(headerGet(q.Headers, "content-type"))
			if strings.Contains(ct, "x-www-form-urlencoded") {
				form := url.Values{}
				if do, ok := d.(*goja.Object); ok {
					for _, k := range do.Keys() {
						form.Set(k, do.Get(k).String())
					}
				}
				q.Body = []byte(form.Encode())
			} else {
				b, _ := json.Marshal(d.Export())
				q.Body = b
				if ct == "" {
					q.Headers["Content-Type"] = "application/json"
				}
			}
		}
		if t := o.Get("timeout"); t != nil && !goja.IsUndefined(t) && t.ToInteger() > 0 {
			timeout = time.Duration(t.ToInteger()) * time.Millisecond
		}
		if rd := o.Get("redirect"); rd != nil && !goja.IsUndefined(rd) && (rd.String() == "0" || rd.String() == "false") {
			q.Redirect = "manual"
		}
		if b := o.Get("buffer"); b != nil && !goja.IsUndefined(b) {
			buffer = int(b.ToInteger())
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	res, err := r.doFetch(ctx, q)
	if err != nil {
		msg := "req 失败: " + u + ": " + err.Error()
		if pe, ok := err.(*Error); ok && pe.Detail != "" {
			msg += "(" + pe.Detail + ")" // 「网络请求失败」分不清是超时、证书还是被拦,要带底层原因
		}
		r.logs.add(LogEntry{TS: nowMS(), Level: "warn", Msg: msg})
		return map[string]any{"content": "", "headers": map[string]any{}, "code": 0}
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	hdr := map[string]any{}
	for k := range res.Header {
		hdr[strings.ToLower(k)] = res.Header.Get(k)
	}
	var content string
	switch buffer {
	case 2:
		content = encodeOut(body, "base64")
	case 1:
		content = string(body)
	default:
		cs := charsetOf(res.Header.Get("Content-Type"))
		if cs == "" {
			cs = charsetOf(headerGet(q.Headers, "content-type")) // drpy 用请求头的 charset 暗示页面编码
		}
		content = decodeBytes(body, cs)
	}
	return map[string]any{"content": content, "headers": hdr, "code": res.StatusCode}
}

func charsetOf(ct string) string {
	_, p, _ := mime.ParseMediaType(ct)
	return p["charset"]
}

func headerGet(h map[string]string, k string) string {
	for hk, v := range h {
		if strings.EqualFold(hk, k) {
			return v
		}
	}
	return ""
}

var _ = http.MethodGet
