package rt

// `nav` 与 `ui` 的对话框那一半(SPEC 7.9 7.10,D84 D85 D86 D219 D416 D547)。
//
// 这两个命名空间的活**全在壳那边**:导航栈、系统对话框、通知都是平台的东西。
// 所以走已有的 `plugin.shellRequest` 通道 —— 那条路的取消、超时、结果回传都写好了,
// 再开一条新的等于把那些规矩重写一遍。
//
// ☠ 壳没声明能做这些事时**当场抛 unsupported**,不排一条注定超时的请求:
//   60 秒后才报「超时」的话,插件作者会以为是自己的对话框参数写错了。

import (
	"context"
	"encoding/json"
	"strconv"
	"sync"
	"time"

	"github.com/dop251/goja"
)

// 对话框没有超时:它等的是**人**。导航与通知是一次性动作,给 10 秒足够。
const (
	dialogTimeout = 0
	actionTimeout = 10 * time.Second
)

func (r *Runtime) installNavUI(sdk *goja.Object) {
	vm := r.vm
	id := r.opt.ID

	ask := r.shellAsk
	/*
		只发不等的那几个:导航与显隐是命令,不是问句。

		☠ 失败要**落进插件自己的日志**。这几个 op 在定义源里的返回值是 `void`,
		  没有 Promise 可拒 —— 上一版直接 `_, _ =` 丢掉错误,于是
		  「路由名写错了」「这一端没有这个页面」「壳没接角标」三种情况
		  插件那头都表现为「调了没反应」,而错误只到得了壳的 logcat。
		  现在它进 console 日志,调试面板的「日志」块里看得到。
	*/
	tell := r.shellTell

	nav := vm.NewObject()
	_ = sdk.Set("nav", nav)
	_ = nav.Set("push", func(c goja.FunctionCall) goja.Value {
		tell("nav.push", map[string]any{"route": c.Argument(0).String(), "params": exportJSON(r, c.Argument(1))})
		return goja.Undefined()
	})
	_ = nav.Set("replace", func(c goja.FunctionCall) goja.Value {
		tell("nav.replace", map[string]any{"route": c.Argument(0).String(), "params": exportJSON(r, c.Argument(1))})
		return goja.Undefined()
	})
	_ = nav.Set("back", func() { tell("nav.back", map[string]any{}) })
	_ = nav.Set("setPageOptions", func(c goja.FunctionCall) goja.Value {
		tell("nav.setPageOptions", map[string]any{"options": exportJSON(r, c.Argument(0))})
		return goja.Undefined()
	})
	_ = nav.Set("setBadge", func(c goja.FunctionCall) goja.Value {
		tell("nav.setBadge", map[string]any{"target": c.Argument(0).String(), "badge": exportJSON(r, c.Argument(1))})
		return goja.Undefined()
	})
	/* onBack 只拦插件自己的页(D85):退出播放、退出应用永远由宿主兜底(D459)。
	   壳每次要返回时问一次,回 false 才拦下 —— 反过来(插件主动说「我要拦」)
	   的话,插件崩了返回键就永远按不动了。 */
	_ = nav.Set("onBack", func(cb goja.Callable) goja.Value {
		stop := r.addSlot(SlotBack, cb)
		r.disposes = append(r.disposes, stop)
		d := vm.NewObject()
		_ = d.Set("dispose", stop)
		return d
	})

	ui := sdk.Get("ui").ToObject(vm) // toast 已经在 installShell 里挂好了
	_ = ui.Set("confirm", func(c goja.FunctionCall) goja.Value {
		return ask("ui.confirm", exportJSON(r, c.Argument(0)), dialogTimeout)
	})
	_ = ui.Set("prompt", func(c goja.FunctionCall) goja.Value {
		return ask("ui.prompt", exportJSON(r, c.Argument(0)), dialogTimeout)
	})
	_ = ui.Set("select", func(c goja.FunctionCall) goja.Value {
		return ask("ui.select", exportJSON(r, c.Argument(0)), dialogTimeout)
	})
	_ = ui.Set("notify", func(c goja.FunctionCall) goja.Value {
		o, _ := exportJSON(r, c.Argument(0)).(map[string]any)
		if o == nil {
			o = map[string]any{}
		}
		if !notifyAllowed(id) {
			/* 超额不是错误,也**不是丢掉**(D547):折叠成一条「xx 还有 N 条通知」。
			   ☠ 上一版到这里就 return 了,于是「折叠」这件事核心层没做、
			   壳那边注释写着「折叠在核心层」—— 两边都以为是对方的事,通知直接没了。 */
			n, send := notifyFold(id)
			if send {
				r.shellTell("ui.notify", map[string]any{
					"plugin": id, "folded": true, "count": n,
					"title": "还有 " + strconv.Itoa(n) + " 条通知",
					"body":  "这个插件这一小时里发得太多,后面的合并成了这一条",
				})
			}
			return r.async(func() (any, error) {
				return map[string]any{"folded": true, "suppressed": n}, nil
			})
		}
		o["plugin"] = id
		return ask("ui.notify", o, actionTimeout)
	})
	_ = ui.Set("openWindow", func(c goja.FunctionCall) goja.Value {
		p, _, reject := vm.NewPromise()
		_ = reject(r.newPluginError(KindUnsupported, "桌面子窗口(D220)这一版还没有"))
		return vm.ToValue(p)
	})
}

/*
通知配额(D547):每插件每小时最多 5 条,超出折叠成一条,**不丢**。

☠ 限在**这一层**而不是壳那边:壳有三个,限三遍就会有三套口径;

	而「一小时 5 条」是产品承诺,不是某个平台的行为。
*/
const notifyPerHour = 5

// foldWindow 折叠通知最快多久发一次。不限的话「超额」本身变成了一条条通知。
const foldWindow = 5 * time.Minute

var notifyLog = struct {
	mu         sync.Mutex
	sent       map[string][]time.Time
	suppressed map[string]int
	lastFold   map[string]time.Time
}{sent: map[string][]time.Time{}, suppressed: map[string]int{}, lastFold: map[string]time.Time{}}

/*
notifyFold 记一条被折叠的通知,并回答「这次要不要把折叠提示发出去」。

回的 n 是**这一轮累计压下了多少条**:折叠提示发出去之后清零,
下一批再攒。不清零的话数字只增不减,用户会以为有几百条没看的。
*/
func notifyFold(plugin string) (int, bool) {
	notifyLog.mu.Lock()
	defer notifyLog.mu.Unlock()
	notifyLog.suppressed[plugin]++
	n := notifyLog.suppressed[plugin]
	if time.Since(notifyLog.lastFold[plugin]) < foldWindow {
		return n, false
	}
	notifyLog.lastFold[plugin] = time.Now()
	notifyLog.suppressed[plugin] = 0
	return n, true
}

func notifyAllowed(plugin string) bool {
	cut := time.Now().Add(-time.Hour)
	notifyLog.mu.Lock()
	defer notifyLog.mu.Unlock()
	kept := notifyLog.sent[plugin][:0]
	for _, t := range notifyLog.sent[plugin] {
		if t.After(cut) {
			kept = append(kept, t)
		}
	}
	notifyLog.sent[plugin] = kept
	if len(kept) >= notifyPerHour {
		return false
	}
	notifyLog.sent[plugin] = append(kept, time.Now())
	return true
}

/*
shellAsk 把一件事交给壳做并等结果。

☠ 壳没声明能做这些事时**当场**回一个已拒绝的 Promise,不排一条注定超时的请求:

	60 秒后才报「超时」的话,插件作者会以为是自己的参数写错了。
*/
func (r *Runtime) shellTell(op string, args any) {
	if !Caps().Shell {
		r.throw(KindUnsupported, "这一版壳还没接 "+op+"(要壳实现)")
	}
	id := r.opt.ID
	go func() {
		if _, err := ShellRequest(context.Background(), id, op, args, actionTimeout); err != nil {
			r.logs.add(LogEntry{TS: nowMS(), Level: "error", Msg: op + " 没做成:" + err.Error()})
		}
	}()
}

func (r *Runtime) shellAsk(op string, args any, timeout time.Duration) goja.Value {
	if !Caps().Shell {
		p, _, reject := r.vm.NewPromise()
		_ = reject(r.newPluginError(KindUnsupported, "这一版壳还没接 "+op+"(要壳实现)"))
		return r.vm.ToValue(p)
	}
	return r.async(func() (any, error) {
		out, err := ShellRequest(context.Background(), r.opt.ID, op, args, timeout)
		if err != nil {
			return nil, err
		}
		return json.RawMessage(out), nil
	})
}
