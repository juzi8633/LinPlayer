package rt

// 需要壳执行的宿主能力:WebView(嗅探/取值/可见页,D59 D62)、TVBox spider(jar/py,D349)、Toast。
//
// 核心层发事件 `plugin.shellRequest {id, op, plugin, args}`,壳做完回命令 `plugin.shellResult`。
// 壳在启动时用 `plugin.setCapabilities` 报自己能做什么;报了不能做的直接抛 unsupported,不空等。

import (
	"context"
	"encoding/json"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dop251/goja"

	"linplayer/core/bus"
)

// ShellCaps 壳报上来的能力。
type ShellCaps struct {
	WebView   bool `json:"webview"`
	SpiderJar bool `json:"spiderJar"`
	SpiderPy  bool `json:"spiderPy"`
}

var (
	capsMu   sync.RWMutex
	caps     ShellCaps
	shellSeq atomic.Int64
	pending  sync.Map // id → chan shellResult
)

type shellResult struct {
	OK    bool            `json:"ok"`
	Data  json.RawMessage `json:"data"`
	Error *Error          `json:"error"`
}

// SetShellCaps 壳启动时报能力。
func SetShellCaps(c ShellCaps) {
	capsMu.Lock()
	caps = c
	capsMu.Unlock()
}

// Caps 当前壳能力。
func Caps() ShellCaps {
	capsMu.RLock()
	defer capsMu.RUnlock()
	return caps
}

// ShellResult 壳回结果(plugin.shellResult 命令)。
func ShellResult(id int64, ok bool, data json.RawMessage, e *Error) {
	if ch, found := pending.LoadAndDelete(id); found {
		ch.(chan shellResult) <- shellResult{OK: ok, Data: data, Error: e}
	}
}

// ShellRequest 让壳做一件事并等结果。
func ShellRequest(ctx context.Context, plugin, op string, args any, timeout time.Duration) (json.RawMessage, error) {
	id := shellSeq.Add(1)
	ch := make(chan shellResult, 1)
	pending.Store(id, ch)
	defer pending.Delete(id)
	bus.Emit("plugin.shellRequest", map[string]any{"id": id, "op": op, "plugin": plugin, "args": args}, "")
	var tc <-chan time.Time
	if timeout > 0 {
		t := time.NewTimer(timeout)
		defer t.Stop()
		tc = t.C
	}
	select {
	case r := <-ch:
		if !r.OK {
			if r.Error == nil {
				r.Error = &Error{Kind: KindInternal, Message: "壳执行失败"}
			}
			return nil, r.Error
		}
		return r.Data, nil
	case <-ctx.Done():
		bus.Emit("plugin.shellCancel", map[string]any{"id": id}, "")
		return nil, &Error{Kind: KindTimeout, Message: "已取消"}
	case <-tc:
		bus.Emit("plugin.shellCancel", map[string]any{"id": id}, "")
		return nil, &Error{Kind: KindTimeout, Message: op + " 超时"}
	}
}

// installShell webview / spider / ui 三个命名空间。
func (r *Runtime) installShell(sdk *goja.Object) {
	vm := r.vm
	id := r.opt.ID
	req := func(op string, need func(ShellCaps) (bool, string), args any, timeout time.Duration) goja.Value {
		if ok, why := need(Caps()); !ok {
			p, _, reject := vm.NewPromise()
			_ = reject(r.newPluginError(KindUnsupported, why))
			return vm.ToValue(p)
		}
		return r.async(func() (any, error) {
			out, err := ShellRequest(context.Background(), id, op, args, timeout)
			if err != nil {
				return nil, err
			}
			return json.RawMessage(out), nil
		})
	}
	wantWeb := func(c ShellCaps) (bool, string) { return c.WebView, "本设备没有可用的 WebView" }
	optsOf := func(v goja.Value) map[string]any {
		if absent(v) {
			return map[string]any{}
		}
		if m, ok := exportJSON(r, v).(map[string]any); ok {
			return m
		}
		return map[string]any{}
	}
	wv := vm.NewObject()
	_ = wv.Set("sniff", func(u string, o goja.Value) goja.Value {
		opts := optsOf(o)
		t := 60 * time.Second
		if n, ok := opts["timeout"].(float64); ok && n > 0 {
			t = time.Duration(n) * time.Millisecond
		}
		return req("webview.sniff", wantWeb, map[string]any{"url": u, "opts": opts}, t+10*time.Second)
	})
	_ = wv.Set("evaluate", func(u, script string, o goja.Value) goja.Value {
		return req("webview.evaluate", wantWeb, map[string]any{"url": u, "script": script, "opts": optsOf(o)}, 60*time.Second)
	})
	_ = wv.Set("open", func(u string, o goja.Value) goja.Value {
		return req("webview.open", wantWeb, map[string]any{"url": u, "opts": optsOf(o)}, 0)
	})
	_ = sdk.Set("webview", wv)

	sp := vm.NewObject()
	_ = sp.Set("supported", func(kind string) goja.Value {
		c := Caps()
		ok := (kind == "jar" && c.SpiderJar) || (kind == "py" && c.SpiderPy)
		out := map[string]any{"ok": ok}
		if !ok {
			if kind == "jar" {
				out["reason"], out["component"] = "本设备暂不支持 jar 源", "jar-runtime"
			} else {
				out["reason"], out["component"] = "需要 Python 组件", "python"
			}
		}
		p, resolve, _ := vm.NewPromise()
		_ = resolve(r.jsValue(out))
		return vm.ToValue(p)
	})
	_ = sp.Set("load", func(o goja.Value) goja.Value {
		opts := optsOf(o)
		kind, _ := opts["kind"].(string)
		need := func(c ShellCaps) (bool, string) {
			if kind == "py" {
				return c.SpiderPy, "需要 Python 组件"
			}
			return c.SpiderJar, "本设备暂不支持 jar 源"
		}
		if ok, why := need(Caps()); !ok {
			p, _, reject := vm.NewPromise()
			_ = reject(r.newPluginError(KindUnsupported, why))
			return vm.ToValue(p)
		}
		return r.async(func() (any, error) {
			out, err := ShellRequest(context.Background(), id, "spider.load", opts, 60*time.Second)
			if err != nil {
				return nil, err
			}
			var h struct {
				Handle string `json:"handle"`
			}
			_ = json.Unmarshal(out, &h)
			return func() goja.Value { return r.spiderHandle(h.Handle) }, nil
		})
	})
	_ = sp.Set("compatProxyPort", func() int { return 0 })
	_ = sdk.Set("spider", sp)

	ui := vm.NewObject()
	_ = ui.Set("toast", func(text string) {
		bus.Emit("plugin.toast", map[string]string{"plugin": id, "text": text}, "")
	})
	_ = sdk.Set("ui", ui)
}

// spiderHandle spider.load 的返回:call 墙钟 30 秒(D355)。
func (r *Runtime) spiderHandle(h string) goja.Value {
	vm := r.vm
	o := vm.NewObject()
	_ = o.Set("call", func(c goja.FunctionCall) goja.Value {
		method := c.Argument(0).String()
		args := make([]any, 0, len(c.Arguments))
		for _, a := range c.Arguments[1:] {
			args = append(args, exportJSON(r, a))
		}
		return r.async(func() (any, error) {
			out, err := ShellRequest(context.Background(), r.opt.ID, "spider.call", map[string]any{"handle": h, "method": method, "args": args}, 30*time.Second)
			if err != nil {
				return nil, err
			}
			var s string
			if json.Unmarshal(out, &s) == nil {
				return s, nil
			}
			return string(out), nil
		})
	})
	_ = o.Set("dispose", func() {
		go ShellRequest(context.Background(), r.opt.ID, "spider.dispose", map[string]any{"handle": h}, 5*time.Second)
	})
	return o
}
