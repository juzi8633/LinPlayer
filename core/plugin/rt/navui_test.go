package rt

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/bus"
)

/*
`nav` 与 `ui` 的对话框那一半(SPEC 7.9 7.10)。

☠ 这一组盯的是**壳没接时会怎样**。上一版这两个命名空间一个不存在、一个只有 toast,
  插件那边 `typeof sdk.ui === 'object'` 让特性探测通过,然后
  `ui.confirm(...)` 抛「undefined is not a function」—— 报在插件那边,
  看起来像插件自己写错了。现在要么真能用,要么当场说是壳没接。
*/

func TestNavUI壳没接时当场说清楚(t *testing.T) {
	SetShellCaps(ShellCaps{}) // 壳什么都没报
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })
	r := newRT(t, ``)

	for _, code := range []string{
		`__linplayer_sdk.ui.confirm({title:'x'})`,
		`__linplayer_sdk.ui.prompt({title:'x'})`,
		`__linplayer_sdk.ui.select({title:'x', options:[]})`,
	} {
		_, err := r.Eval(context.Background(), BudgetData, "x.js", code)
		if err == nil {
			t.Errorf("%s 在壳没接时应当抛错", code)
			continue
		}
		if !strings.Contains(err.Error(), "壳还没接") {
			t.Errorf("%s 的错误没说清是壳没接:%v", code, err)
		}
	}
	// nav 是「只发不等」的,壳没接时也要当场抛,不能悄悄丢
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.nav.push('home')`); err == nil {
		t.Error("nav.push 在壳没接时悄悄成功了 —— 插件会以为自己跳过去了")
	}
}

// 六个 nav 成员与五个 ui 成员都要在,少一个插件拿到的就是 undefined。
func TestNavUI成员一个都不许少(t *testing.T) {
	r := newRT(t, ``)
	for _, path := range []string{
		"nav.push", "nav.replace", "nav.back", "nav.onBack", "nav.setPageOptions", "nav.setBadge",
		"ui.toast", "ui.confirm", "ui.prompt", "ui.select", "ui.notify", "ui.openWindow",
	} {
		v, err := r.Eval(context.Background(), BudgetData, "x.js",
			"typeof "+SDKGlobal+"."+path)
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		if !strings.Contains(string(v), "function") {
			t.Errorf("%s 没挂上(typeof = %s)", path, v)
		}
	}
}

/*
通知配额(D547):超出**折叠**,不是抛错、更不是丢。

☠ 上一版只调 `notifyAllowed` 这个 helper,从不走 `ui.notify` —— 而真实路径上

	超额那一支直接 return 了,「折叠」一条都没发过;壳那边的注释还写着
	「折叠在核心层」。两边都以为是对方的事,通知就这么没了。
	所以这一条**必须从 `ui.notify` 进去**,并且看壳真的收到了那条折叠通知。
*/
func TestUI通知超额要真发出一条折叠通知(t *testing.T) {
	SetShellCaps(ShellCaps{Shell: true})
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })

	var live atomic.Bool
	live.Store(true)
	t.Cleanup(func() { live.Store(false) })
	var mu sync.Mutex
	var got []string
	bus.Tap(func(name string, data json.RawMessage) {
		if name != "plugin.shellRequest" || !live.Load() {
			return
		}
		var m struct {
			ID   int64           `json:"id"`
			Op   string          `json:"op"`
			Args json.RawMessage `json:"args"`
		}
		if json.Unmarshal(data, &m) != nil {
			return
		}
		if m.Op == "ui.notify" {
			mu.Lock()
			got = append(got, string(m.Args))
			mu.Unlock()
		}
		ShellResult(m.ID, true, json.RawMessage("null"), nil)
	})

	r := newRT(t, ``)
	// 配额 + 3 条:前 5 条照发,后面的要折叠成一条
	for i := 0; i < notifyPerHour+3; i++ {
		if _, err := r.Eval(context.Background(), BudgetData, "x.js",
			`__linplayer_sdk.ui.notify({title:'第几条'})`); err != nil {
			t.Fatalf("第 %d 条 notify 抛了:%v", i+1, err)
		}
	}

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(got)
		folded := false
		for _, g := range got {
			if strings.Contains(g, `"folded":true`) {
				folded = true
			}
		}
		mu.Unlock()
		if folded {
			if n > notifyPerHour+1 {
				t.Fatalf("超额的那几条没被折叠,壳收到了 %d 条", n)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	t.Fatalf("超额之后一条折叠通知都没发出去,壳收到的是:%v", got)
}

/*
壳把 nav 这类命令办砸了,插件那头要看得见。

☠ 这几个 op 在定义源里返回 `void`,没有 Promise 可拒。上一版把错误

	`_, _ =` 丢了,于是「路由名写错」「这一端没这个页面」「壳没接角标」
	三种都表现为「调了没反应」—— 两端的壳各自独立报了这同一件事。
	判据不是「有没有打日志」这种形式,是**错误文本真的出现在插件自己的日志里**。
*/
func TestNav壳办砸了要进插件日志(t *testing.T) {
	SetShellCaps(ShellCaps{Shell: true})
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })

	// 装一个「什么都办不成」的壳:收到请求立刻回失败。
	// bus.Tap 没有退订,所以用一个开关在用例结束时把它关掉 —— 不关的话
	// 同一个包里后面的用例都会被这个假壳回失败。
	var live atomic.Bool
	live.Store(true)
	t.Cleanup(func() { live.Store(false) })
	bus.Tap(func(name string, data json.RawMessage) {
		if name != "plugin.shellRequest" || !live.Load() {
			return
		}
		var m struct {
			ID int64 `json:"id"`
		}
		if json.Unmarshal(data, &m) != nil {
			return
		}
		ShellResult(m.ID, false, nil, &Error{Kind: KindInvalid, Message: "不认识的路由「没有这一页」"})
	})

	r := newRT(t, ``)
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.nav.push('没有这一页')`); err != nil {
		t.Fatalf("nav.push 本身不该抛:%v", err)
	}

	var got string
	for i := 0; i < 200 && got == ""; i++ { // tell 是异步的,等日志落下来
		for _, e := range r.Logs() {
			if strings.Contains(e.Msg, "没有这一页") {
				got = e.Level
			}
		}
		if got == "" {
			time.Sleep(5 * time.Millisecond)
		}
	}
	if got == "" {
		t.Fatalf("壳报了错,插件日志里一条都没有 —— 插件作者只会看到「调了没反应」:%v", r.Logs())
	}
	if got != "error" {
		t.Errorf("这条日志的级别是 %q,应当是 error", got)
	}
}
