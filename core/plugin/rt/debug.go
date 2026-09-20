package rt

// `debug` 命名空间(SPEC 16.4 16.5,D80 D81 D128):调试面板要的四块数据。
//
// ★ 只在**开发者模式**下挂上。不是出于安全(完全信任,D5),是出于口径:
//   正式模式下挂一个永远返回空表的 debug,面板会以为「这个插件没日志」——
//   而真相是这一版根本不收。不挂的话调用当场报 unsupported,一眼看得出。

import (
	"context"
	"encoding/json"
	"runtime"
	"sync"
	"time"

	"github.com/dop251/goja"
)

// CallStats 一个运行时的调用统计(性能那一块)。
type CallStats struct {
	Calls    int64 `json:"calls"`
	TotalMS  int64 `json:"total_ms"`
	MaxMS    int64 `json:"max_ms"`
	Timeouts int64 `json:"timeouts"`
	// HeapDelta 这个运行时近一次调用前后的 Go 堆增量(字节)。
	// ☠ 是**进程级**采样,不是这个插件真占了多少 —— goja 不给按运行时的内存账。
	//   内存看门狗(D141)用的也是同一个近似,这里如实标注,别让面板显示成精确值。
	HeapDelta int64 `json:"heap_delta"`
}

type statBox struct {
	mu sync.Mutex
	s  CallStats
}

func (b *statBox) note(d time.Duration, timedOut bool, heapDelta int64) {
	ms := d.Milliseconds()
	b.mu.Lock()
	b.s.Calls++
	b.s.TotalMS += ms
	if ms > b.s.MaxMS {
		b.s.MaxMS = ms
	}
	if timedOut {
		b.s.Timeouts++
	}
	b.s.HeapDelta = heapDelta
	b.mu.Unlock()
}

func (b *statBox) get() CallStats {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.s
}

// Stats 这个运行时的调用统计。
func (r *Runtime) Stats() CallStats { return r.stats.get() }

// heapNow 采一次 Go 堆。ReadMemStats 会 stop-the-world,所以**只在开发者模式**采。
func heapNow() int64 {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return int64(m.HeapAlloc)
}

// KV 这个运行时的键值存储快照(调试面板的「存储」那一块)。
func (r *Runtime) KV() map[string]any {
	out := map[string]any{}
	for k, raw := range r.kv.All() {
		var v any
		if json.Unmarshal(raw, &v) != nil {
			v = string(raw)
		}
		out[k] = v
	}
	return out
}

// SetKV 调试面板改一个键。值为 nil = 删。
func (r *Runtime) SetKV(key string, v any) error {
	if v == nil {
		r.kv.Set(key, nil)
		return nil
	}
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	r.kv.Set(key, b)
	return nil
}

// UITree 一个 surface 当前的组件树(调试面板的「UI 树」那一块)。
func (r *Runtime) UITree(surfaceID string) (any, error) {
	v, err := r.run(context.Background(), BudgetHook, func(vm *goja.Runtime, _ *call) (goja.Value, error) {
		if r.ui == nil {
			return goja.Undefined(), nil
		}
		fn, ok := goja.AssertFunction(r.ui.Get("tree"))
		if !ok {
			return goja.Undefined(), nil
		}
		return fn(r.ui, vm.ToValue(surfaceID))
	})
	if err != nil {
		return nil, err
	}
	var out any
	if err := json.Unmarshal(v, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// installDebug 挂 debug 命名空间。宿主没给 Debug(非开发者模式)就整个不挂。
func (r *Runtime) installDebug(sdk *goja.Object) {
	d := r.opt.Host.Debug
	if d == nil {
		return
	}
	vm := r.vm
	o := vm.NewObject()
	_ = sdk.Set("debug", o)

	/* ☠ 参数**必须在这条线程上取完**再进 async:goja 的值只属于它自己的循环线程,
	   在 r.async 的 goroutine 里调 c.Argument(0).String() 是未定义行为 ——
	   实测表现是取到空串,于是 debug.logs('alice/demo') 查的是 id 为空的插件,
	   返回一张空表。既不报错也不崩,只是「面板上永远没有数据」。 */
	noArg := func(name string, f func() any) {
		_ = o.Set(name, func(goja.FunctionCall) goja.Value {
			return r.async(func() (any, error) { return callOr(f) })
		})
	}
	oneArg := func(name string, f func(string) any) {
		_ = o.Set(name, func(c goja.FunctionCall) goja.Value {
			id := c.Argument(0).String()
			return r.async(func() (any, error) { return callOr1(f, id) })
		})
	}

	noArg("plugins", d.Plugins)
	noArg("surfaces", d.Surfaces)
	oneArg("logs", d.Logs)
	oneArg("requests", d.Requests)
	oneArg("storage", d.Storage)
	oneArg("stats", d.Stats)
	_ = o.Set("uiTree", func(c goja.FunctionCall) goja.Value {
		sid := c.Argument(0).String()
		return r.async(func() (any, error) {
			if d.UITree == nil {
				return nil, &Error{Kind: KindUnsupported, Message: "宿主没有提供 UI 树"}
			}
			return d.UITree(sid)
		})
	})
	_ = o.Set("setStorage", func(c goja.FunctionCall) goja.Value {
		id, key := c.Argument(0).String(), c.Argument(1).String()
		v := exportJSON(r, c.Argument(2))
		return r.async(func() (any, error) {
			if d.SetStorage == nil {
				return nil, &Error{Kind: KindUnsupported, Message: "宿主没有提供存储写入"}
			}
			return nil, d.SetStorage(id, key, v)
		})
	})
}

func callOr(f func() any) (any, error) {
	if f == nil {
		return nil, &Error{Kind: KindUnsupported, Message: "这一版宿主没有提供这项调试数据"}
	}
	return f(), nil
}

func callOr1(f func(string) any, a string) (any, error) {
	if f == nil {
		return nil, &Error{Kind: KindUnsupported, Message: "这一版宿主没有提供这项调试数据"}
	}
	return f(a), nil
}
