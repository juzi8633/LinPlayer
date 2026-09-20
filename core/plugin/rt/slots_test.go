package rt

import (
	"context"
	"testing"
	"time"
)

/*
回调槽(`nav.onBack` D85、`player.onKey` D563)。

☠ 上一版 `nav.onBack` 订的是一个**没人发**的事件,返回值也被丢掉:
  注册成功、回调不报错、就是拦不住。判据必须是**真的问一次**并看回答,
  「onBack 存在」「注册不抛错」这两条都会在那一版上绿。
*/

func TestSlot回调说接走了就要答接走了(t *testing.T) {
	r := newRT(t, ``)
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.nav.onBack(() => true)`); err != nil {
		t.Fatal(err)
	}
	ok, err := r.CallSlot(context.Background(), BudgetHook, SlotBack, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !ok {
		t.Fatal("回调回了 true,槽却说没人接 —— 返回键会照样退出去")
	}
}

// 回 false 的不算接走:不然插件注册了就再也退不出去。
func TestSlot回false的不算接走(t *testing.T) {
	r := newRT(t, ``)
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.nav.onBack(() => false)`); err != nil {
		t.Fatal(err)
	}
	ok, _ := r.CallSlot(context.Background(), BudgetHook, SlotBack, nil)
	if ok {
		t.Fatal("回调回了 false 却算成接走了 —— 用户退不出去")
	}
}

// 异步回调要等齐:直接拿 Promise 当真值会**永远**是 true。
func TestSlot异步回调要等结果(t *testing.T) {
	r := newRT(t, ``)
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.nav.onBack(async () => false)`); err != nil {
		t.Fatal(err)
	}
	ok, err := r.CallSlot(context.Background(), BudgetHook, SlotBack, nil)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Fatal("async 回调回的 false 被当成了 true(Promise 对象恒真)")
	}
}

// 退订之后不许再被问到。
func TestSlot退订后不再问(t *testing.T) {
	r := newRT(t, ``)
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`globalThis.__d = __linplayer_sdk.nav.onBack(() => true)`); err != nil {
		t.Fatal(err)
	}
	if _, err := r.Eval(context.Background(), BudgetData, "x.js", `globalThis.__d.dispose()`); err != nil {
		t.Fatal(err)
	}
	// dispose 走 post,要等它落到 JS 线程上
	deadline := time.Now().Add(2 * time.Second)
	for r.SlotCount(SlotBack) > 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if n := r.SlotCount(SlotBack); n != 0 {
		t.Fatalf("退订后槽上还剩 %d 个回调", n)
	}
	if ok, _ := r.CallSlot(context.Background(), BudgetHook, SlotBack, nil); ok {
		t.Fatal("退订了还在接返回键")
	}
}

// 按键参数要原样交到回调手上 —— 收不到键名的话插件只知道「有人按了」。
func TestSlot按键参数交到回调手上(t *testing.T) {
	r := newRT(t, ``)
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`globalThis.__got = null; __linplayer_sdk.player.onKey((e) => { globalThis.__got = e; return e.key === '7' })`); err != nil {
		t.Fatal(err)
	}
	ok, err := r.CallSlot(context.Background(), BudgetHook, SlotKey, map[string]any{"key": "7"})
	if err != nil {
		t.Fatal(err)
	}
	if !ok {
		t.Fatal("回调按键名判断后回了 true,槽却说没接走")
	}
	v, err := r.Eval(context.Background(), BudgetData, "x.js", `JSON.stringify(globalThis.__got)`)
	if err != nil {
		t.Fatal(err)
	}
	if string(v) == `""` || string(v) == "null" {
		t.Fatalf("回调收到的参数是空的:%s", v)
	}
}

// 一个回调抛了不能把整条链拖垮:其余回调照问,这一下按默认处理。
func TestSlot回调抛了不影响别人(t *testing.T) {
	r := newRT(t, ``)
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.player.onKey(() => { throw new Error('坏回调') });
		 __linplayer_sdk.player.onKey((e) => e.key === 'ok')`); err != nil {
		t.Fatal(err)
	}
	ok, err := r.CallSlot(context.Background(), BudgetHook, SlotKey, map[string]any{"key": "ok"})
	if err != nil {
		t.Fatalf("一个回调抛了就整条失败了:%v", err)
	}
	if !ok {
		t.Fatal("第二个回调接走了,却被第一个回调的异常盖掉")
	}
}
