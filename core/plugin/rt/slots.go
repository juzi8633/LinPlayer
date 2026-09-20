package rt

/*
「壳问一句、插件答一句」的回调槽(`nav.onBack` D85、`player.onKey`)。

☠ 上一版 `nav.onBack` 订的是一个**没人发**的事件,而且回调的返回值被丢掉 ——
  定义源里写着「回调返回 false 阻止返回」,运行时挂得好好的,实际什么都没发生。
  插件作者那头看不出任何异常:注册成功了,回调也不报错,就是拦不住。
  所以这一版改成**壳主动问**:壳按下返回键/遥控键时调一条命令,核心层把所有
  回调跑一遍(异步的等齐),回一个「有没有人接走」。
*/

import (
	"context"
	"encoding/json"
	"time"

	"github.com/dop251/goja"
)

// 槽名。壳那边按这个名字问,插件那边按这个名字注册。
const (
	SlotBack = "nav.back"
	SlotKey  = "player.key"
)

/*
slotsJS 槽的登记与分发都在 JS 里做。

放在 JS 里是因为回调可能返回 Promise(`.d.ts` 写的是 `boolean | Promise<boolean>`),
在 Go 里挨个等 Promise 要把 goja 的事件循环重新实现一遍;`Promise.all` 一行就够。
回调自己抛了不算「接走」—— 但要留一条日志,静默吞掉会让「拦不住」查无可查。
*/
const slotsJS = `(() => {
  const m = new Map();
  return {
    add(slot, fn) {
      const a = m.get(slot) || [];
      a.push(fn);
      m.set(slot, a);
      return () => { const b = m.get(slot) || []; const i = b.indexOf(fn); if (i >= 0) b.splice(i, 1) };
    },
    count(slot) { return (m.get(slot) || []).length },
    call(slot, arg) {
      const a = (m.get(slot) || []).slice();
      if (a.length === 0) return false;
      return Promise.all(a.map((f) => {
        try { return f(arg) } catch (e) { console.error(slot + ' 的回调抛了:' + e); return false }
      })).then((v) => v.some(Boolean));
    },
  };
})()`

// installSlots 建槽。失败是**硬失败**:槽没建起来的话 onBack / onKey 全是空挂。
func (r *Runtime) installSlots() error {
	v, err := r.vm.RunString(slotsJS)
	if err != nil {
		return err
	}
	r.slots = v.ToObject(r.vm)
	return nil
}

/*
addSlot 注册一个回调,返回退订函数。只能在 JS 线程上调。

☠ `goja.Callable` **不能直接** `ToValue` 交回 JS:它的签名是 `(this, ...args)`,
  goja 会把 JS 那边的第一个实参喂给 `this` 这一位,回调收到的第一个参数永远是
  undefined。表现是「回调被调到了、也没报错,就是拿不到按键名」。
*/
func (r *Runtime) addSlot(slot string, cb goja.Callable) func() {
	if r.slots == nil {
		return func() {}
	}
	add, ok := goja.AssertFunction(r.slots.Get("add"))
	if !ok {
		return func() {}
	}
	wrapped := func(c goja.FunctionCall) goja.Value {
		v, err := cb(goja.Undefined(), c.Arguments...)
		if err != nil {
			if ex, ok := err.(*goja.Exception); ok {
				panic(ex) // 原样抛回 JS,槽那边的 try/catch 会记一行日志
			}
			panic(r.vm.ToValue(err.Error()))
		}
		return v
	}
	off, err := add(r.slots, r.vm.ToValue(slot), r.vm.ToValue(wrapped))
	if err != nil {
		return func() {}
	}
	stop, _ := goja.AssertFunction(off)
	var once bool
	return func() {
		if once || stop == nil {
			return
		}
		once = true
		// 退订可能从任何线程来(插件页销毁时),必须回到 JS 线程上动那个数组
		r.post(func() { _, _ = stop(goja.Undefined()) })
	}
}

/*
CallSlot 把一个槽上的回调全跑一遍,任一回 true 就算被接走。

没有回调时**立刻回 false**,不进 JS 线程:返回键是每次都按的,
为一个空槽排一次调用等于给每次返回键加一次跨线程往返。
*/
func (r *Runtime) CallSlot(ctx context.Context, budget time.Duration, slot string, arg any) (bool, error) {
	if r.SlotCount(slot) == 0 {
		return false, nil
	}
	out, err := r.run(ctx, budget, func(vm *goja.Runtime, _ *call) (goja.Value, error) {
		call, ok := goja.AssertFunction(r.slots.Get("call"))
		if !ok {
			return vm.ToValue(false), nil
		}
		v, err := r.fromGo(arg)
		if err != nil {
			return nil, err
		}
		return call(r.slots, vm.ToValue(slot), v)
	})
	if err != nil {
		return false, err
	}
	var b bool
	_ = json.Unmarshal(out, &b)
	return b, nil
}

// SlotCount 这个槽上挂了几个回调。壳拿它判「要不要问」。
func (r *Runtime) SlotCount(slot string) int {
	if r.slots == nil || r.dead.Load() {
		return 0
	}
	ch := make(chan int, 1)
	r.post(func() {
		n := 0
		if fn, ok := goja.AssertFunction(r.slots.Get("count")); ok {
			if v, err := fn(r.slots, r.vm.ToValue(slot)); err == nil {
				n = int(v.ToInteger())
			}
		}
		ch <- n
	})
	select {
	case n := <-ch:
		return n
	case <-r.done:
		return 0
	case <-time.After(2 * time.Second):
		// 运行时正卡在别的调用里。回 0 = 这次按键按默认走,
		// 而不是让返回键跟着卡住 —— 卡住的话用户连退出都做不到。
		return 0
	}
}
