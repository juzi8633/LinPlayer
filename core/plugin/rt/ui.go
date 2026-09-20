package rt

// surface 渲染(SPEC 7.2 7.3,D23 D318 D319 D136)。
//
// 运行时这一侧只做三件事:按需把 uiruntime.js(Preact + 最小 DOM + 渲染器)装进 VM、
// 把 mount/unmount/event 转进 JS、把 JS 吐出来的 ops 交给回调。
// 「一帧一条」的合批在 core/plugin/ui 那边做 —— 那里才知道壳的节奏。

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"time"

	"github.com/dop251/goja"
)

//go:embed uiruntime.js
var uiRuntimeJS string

// UIFrame 一次提交产生的 ops(协议见 api/ui-protocol.d.ts)。
type UIFrame struct {
	Surface string            `json:"surface"`
	Frame   int               `json:"frame"`
	Ops     []json.RawMessage `json:"ops"`
}

// UIState surface 的状态:骨架屏 / 首帧就绪 / 出错(D271 D136)。
type UIState struct {
	Surface string `json:"surface"`
	State   string `json:"state"`
	Message string `json:"message,omitempty"`
	Stack   string `json:"stack,omitempty"`
}

// UISink 渲染器把变更交给谁。两个回调都在**事件线程上**被调用。
type UISink struct {
	OnFrame func(UIFrame)
	OnState func(UIState)
}

// SetUISink 装渲染出口。必须在第一次 UIMount 之前设好。
func (r *Runtime) SetUISink(s UISink) { r.uiSink = s }

// installUI 装 Preact + 最小 DOM + 渲染器,并把 h / Fragment / hooks 挂到 SDK 上。
//
// ★ 跟 SDK 一起装,不做懒加载:插件模块顶上那句
// `const { h, useState } = sdk` 在**加载期**就要拿到值,懒到第一次 mount 就晚了。
// 代价是每个插件都付一次 uiruntime.js 的解析(实测见 ui_bench_test.go),
// 换掉的是一整类「什么时候能用 h」的顺序问题。
func (r *Runtime) installUI(sdk *goja.Object) error {
	vm := r.vm
	if _, err := vm.RunString(uiRuntimeJS); err != nil {
		return fmt.Errorf("装 UI 运行时失败: %w", err)
	}
	host := vm.NewObject()
	_ = host.Set("frame", func(surface string, frame int, ops goja.Value) {
		if r.uiSink.OnFrame == nil {
			return
		}
		var raw []json.RawMessage
		b, err := json.Marshal(exportJSON(r, ops))
		if err == nil {
			_ = json.Unmarshal(b, &raw)
		}
		r.uiSink.OnFrame(UIFrame{Surface: surface, Frame: frame, Ops: raw})
	})
	_ = host.Set("surfaceState", func(c goja.FunctionCall) goja.Value {
		if r.uiSink.OnState == nil {
			return goja.Undefined()
		}
		st := UIState{Surface: c.Argument(0).String(), State: c.Argument(1).String()}
		if o, ok := exportJSON(r, c.Argument(2)).(map[string]any); ok {
			st.Message, _ = o["message"].(string)
			st.Stack, _ = o["stack"].(string)
		}
		r.uiSink.OnState(st)
		return goja.Undefined()
	})
	// 合批靠的是「一次提交里的多个 setState 落进同一帧」,所以这里只要一个微任务级的延迟;
	// 16ms 的节流在 core/plugin/ui —— 那边才知道壳一帧多长
	_ = host.Set("nextFrame", func(fn goja.Callable) {
		// 投回循环尾部:同一次交互里的多次 setState 全部提交完才 flush
		r.post(func() {
			if _, err := fn(goja.Undefined()); err != nil {
				r.logs.add(LogEntry{TS: nowMS(), Level: "error", Msg: "UI 刷帧出错: " + err.Error()})
			}
		})
	})

	init, ok := goja.AssertFunction(vm.Get("__lp_ui_init"))
	if !ok {
		return fmt.Errorf("uiruntime.js 没有导出 __lp_ui_init")
	}
	v, err := init(goja.Undefined(), host)
	if err != nil {
		return fmt.Errorf("初始化 UI 渲染器失败: %w", err)
	}
	r.ui = v.ToObject(vm)

	// h / Fragment / hooks 原样转出(SPEC 29 节):语义与 Preact 一致,不另造一套。
	// **必须取渲染器自己那份** —— hooks 挂的是它的 options,换一份就断,而且断得不报错
	pre := r.ui.Get("preact").ToObject(vm)
	hk := r.ui.Get("hooks").ToObject(vm)
	_ = sdk.Set("h", pre.Get("h"))
	_ = sdk.Set("Fragment", pre.Get("Fragment"))
	_ = sdk.Set("createContext", pre.Get("createContext"))
	for _, n := range []string{"useState", "useEffect", "useMemo", "useCallback", "useRef", "useContext", "useReducer", "useErrorBoundary"} {
		_ = sdk.Set(n, hk.Get(n))
	}
	return nil
}

// uiCall 在事件线程上调渲染器的一个方法。
func (r *Runtime) uiCall(method string, args ...any) error {
	done := make(chan error, 1)
	r.post(func() {
		if r.ui == nil {
			done <- fmt.Errorf("UI 渲染器没装起来")
			return
		}
		fn, ok := goja.AssertFunction(r.ui.Get(method))
		if !ok {
			done <- fmt.Errorf("渲染器没有 %s", method)
			return
		}
		vs := make([]goja.Value, 0, len(args))
		for _, a := range args {
			vs = append(vs, r.jsValue(a))
		}
		_, err := fn(r.ui, vs...)
		done <- err
	})
	select {
	case err := <-done:
		return err
	case <-time.After(BudgetHook):
		return fmt.Errorf("渲染器 %s 超时", method)
	}
}

// UIMount 把 definePlugin 里 kind/target 对应的组件挂到 surfaceID 上。
func (r *Runtime) UIMount(surfaceID, kind, target string, props map[string]any) error {
	done := make(chan error, 1)
	r.post(func() {
		if r.ui == nil {
			done <- fmt.Errorf("UI 渲染器没装起来")
			return
		}
		comp, err := r.uiComponent(kind, target)
		if err != nil {
			done <- err
			return
		}
		vm := r.vm
		// render 是个无参闭包:每次重渲染都拿同一份 props 调同一个组件
		render := vm.ToValue(func() goja.Value {
			h, _ := goja.AssertFunction(r.ui.Get("preact").ToObject(vm).Get("h"))
			v, err := h(goja.Undefined(), comp, r.jsValue(props))
			if err != nil {
				panic(vm.ToValue(err.Error()))
			}
			return v
		})
		fn, _ := goja.AssertFunction(r.ui.Get("mount"))
		_, err = fn(r.ui, vm.ToValue(surfaceID), render)
		done <- err
	})
	select {
	case err := <-done:
		return err
	case <-time.After(BudgetHook):
		return fmt.Errorf("挂载 %s 超时", target)
	}
}

// uiComponent 找 definePlugin 里的页面 / 区块。
func (r *Runtime) uiComponent(kind, target string) (goja.Value, error) {
	if r.def == nil {
		return nil, fmt.Errorf("插件没有调用 definePlugin")
	}
	bucket := "blocks"
	if kind == "page" || kind == "window" {
		bucket = "pages"
	}
	o := r.def.Get(bucket)
	if o == nil || goja.IsUndefined(o) || goja.IsNull(o) {
		return nil, fmt.Errorf("definePlugin 里没有 %s", bucket)
	}
	c := o.ToObject(r.vm).Get(target)
	if c == nil || goja.IsUndefined(c) || goja.IsNull(c) {
		return nil, fmt.Errorf("definePlugin.%s 里没有 %q", bucket, target)
	}
	return c, nil
}

// UIUnmount 卸载并丢掉这个 surface 的整份 id 与回调号空间。
func (r *Runtime) UIUnmount(surfaceID string) error {
	return r.uiCall("unmount", surfaceID)
}

// UIEvent 壳回传的一次交互(D53:回调预算在 JS 侧由 loop 的调用预算管)。
func (r *Runtime) UIEvent(surfaceID string, fn int, args []any) error {
	return r.uiCall("event", surfaceID, fn, args)
}
