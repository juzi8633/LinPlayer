package rt

// `events` 命名空间(SPEC 9.1 的事件那一节,D94 D161)。
//
// ☠ 订阅一个**这一版宿主根本不发**的事件必须当场抛错,不能悄悄挂上 ——
//   悄悄挂上的表现是「插件装了,但同步永远不动」,而日志里一条异常都没有:
//   插件作者查不到是宿主不发,只能怀疑自己的回调写错了。

import (
	"sort"
	"strings"
	"sync"

	"github.com/dop251/goja"
)

// appEvents 这一版宿主发得出来的事件名。
//
// 名字是公开契约(`.d.ts` 的 AppEvents),加一条要同时改那边。
// 门禁比对两边:`.d.ts` 里有而这里没有的,订阅时会抛错 —— 那是**有意**的,
// 但必须能被看见,所以 SupportedEvents 是导出的。
var appEvents = map[string]bool{
	"player.start":         true,
	"player.pause":         true,
	"player.resume":        true,
	"player.buffering":     true,
	"player.end":           true,
	"player.episodeChange": true,
	"player.tracks":        true,
	"player.osdVisible":    true,
	"player.pip":           true,
	"nav.page":             true,
	"nav.detail":           true,
	"server.change":        true,
	"app.foreground":       true,
	"app.background":       true,
}

// SupportedEvents 这一版发得出来的事件名,排好序。
func SupportedEvents() []string {
	out := make([]string, 0, len(appEvents))
	for k := range appEvents {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// eventHub 进程级的应用事件总线。
//
// 做成进程级而不是每个运行时一份:事件源(播放状态、导航)只有一个,
// 每个运行时各订阅一次上游等于把同一件事算好几遍。
var eventHub = struct {
	mu   sync.Mutex
	seq  int64
	subs map[string]map[int64]func(any)
}{subs: map[string]map[int64]func(any){}}

// EmitAppEvent 宿主发一条应用事件给所有插件。
func EmitAppEvent(name string, data any) {
	eventHub.mu.Lock()
	fns := make([]func(any), 0, len(eventHub.subs[name]))
	for _, f := range eventHub.subs[name] {
		fns = append(fns, f)
	}
	eventHub.mu.Unlock()
	for _, f := range fns {
		f(data)
	}
}

func subscribeEvent(name string, cb func(any)) func() {
	eventHub.mu.Lock()
	eventHub.seq++
	id := eventHub.seq
	if eventHub.subs[name] == nil {
		eventHub.subs[name] = map[int64]func(any){}
	}
	eventHub.subs[name][id] = cb
	eventHub.mu.Unlock()
	return func() {
		eventHub.mu.Lock()
		delete(eventHub.subs[name], id)
		eventHub.mu.Unlock()
	}
}

func (r *Runtime) installEvents(sdk *goja.Object) {
	vm := r.vm
	o := vm.NewObject()
	_ = sdk.Set("events", o)
	_ = o.Set("on", func(c goja.FunctionCall) goja.Value {
		name := strings.TrimSpace(c.Argument(0).String())
		cb, ok := goja.AssertFunction(c.Argument(1))
		if !ok {
			r.throw(KindInvalid, "events.on 的第二个参数要是函数")
		}
		if !appEvents[name] {
			r.throw(KindUnsupported, "这一版宿主不发 "+name+" 这个事件(发得出来的:"+
				strings.Join(SupportedEvents(), " ")+")")
		}
		stop := subscribeEvent(name, func(v any) {
			// 投进事件循环:事件源在别的线程上,直接调等于在那条线程上跑插件代码
			r.post(func() { _, _ = cb(goja.Undefined(), r.jsValue(v)) })
		})
		r.disposes = append(r.disposes, stop)
		d := vm.NewObject()
		_ = d.Set("dispose", stop)
		return d
	})
}
