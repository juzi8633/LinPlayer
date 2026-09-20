package rt

import (
	"context"
	"strings"
	"testing"
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

// 通知配额(D547):超出**折叠**,不是抛错、更不是丢。
func TestUI通知配额超了要折叠不是报错(t *testing.T) {
	SetShellCaps(ShellCaps{Shell: true})
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })
	const id = "配额测试/插件"
	for i := 0; i < notifyPerHour; i++ {
		if !notifyAllowed(id) {
			t.Fatalf("第 %d 条就被拦了,配额是 %d", i+1, notifyPerHour)
		}
	}
	if notifyAllowed(id) {
		t.Fatalf("发了 %d 条之后还放行 —— 配额没生效", notifyPerHour)
	}
}
