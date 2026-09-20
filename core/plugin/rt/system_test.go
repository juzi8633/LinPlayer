package rt

import (
	"context"
	"strings"
	"testing"
)

/*
`system` 命名空间(SPEC 13,D196 D197 D444)。

☠ 这一组盯的是那条线:插件可以唤起**别的 App**,不可以唤起**本机程序**。
  放开 `file://` 与 `C:\...` 的话,「装一个插件」就等于「让它在你机器上开东西」,
  而用户看到的只是系统默认程序弹出来 —— 没有任何东西提示这是插件干的。
*/
func TestSystem不许借openApp唤起本机程序(t *testing.T) {
	SetShellCaps(ShellCaps{Shell: true}) // 壳接了,挡下来的只能是这条规矩本身
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })
	r := newRT(t, ``)

	for _, target := range []string{
		`'file:///C:/Windows/System32/cmd.exe'`,
		`'FILE://etc/passwd'`,
		`'content://com.x/y'`,
		`'C:\\Windows\\System32\\cmd.exe'`,
		`{data:'file:///tmp/x.sh'}`,
		`'\\\\主机\\共享\\x.exe'`,
		`'/usr/bin/xterm'`,
	} {
		_, err := r.Eval(context.Background(), BudgetData, "x.js",
			`__linplayer_sdk.system.openApp(`+target+`)`)
		if err == nil {
			t.Errorf("openApp(%s) 放行了", target)
			continue
		}
		if kindOf(err) != string(KindPermission) {
			t.Errorf("openApp(%s) 拒绝的类型是 %s,应当是 permission", target, kindOf(err))
		}
	}
}

// 正常深链不许被误伤 —— 全拦的话上面那条也绿,等于没判据。
func TestSystem正常深链照常放行(t *testing.T) {
	SetShellCaps(ShellCaps{})
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })
	r := newRT(t, ``)
	for _, target := range []string{`'weixin://dl/scan'`, `'linplayer://add-server'`, `'https://例子.测试/a'`} {
		_, err := r.Eval(context.Background(), BudgetData, "x.js",
			`__linplayer_sdk.system.openApp(`+target+`)`)
		// 壳没接,所以这里期望的是 unsupported —— 只要**不是** permission 就说明没被规矩挡下
		if kindOf(err) == string(KindPermission) {
			t.Errorf("openApp(%s) 被当成本机程序挡了", target)
		}
	}
}

// openUrl 只收 http/https:`javascript:` 交给系统浏览器是另一回事。
func TestSystemOpenUrl只收http(t *testing.T) {
	SetShellCaps(ShellCaps{Shell: true})
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })
	r := newRT(t, ``)
	_, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.system.openUrl('javascript:alert(1)')`)
	if err == nil || !strings.Contains(err.Error(), "http") {
		t.Fatalf("javascript: 地址应当被挡下并说清收什么,拿到:%v", err)
	}
}
