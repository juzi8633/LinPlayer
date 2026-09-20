package rt

import (
	"context"
	"strings"
	"sync"
	"testing"
)

/*
`ext` 命名空间(SPEC 18.5,D380~D382)。

☠ 这一组盯的是 D381 那一句:「**真正用到时**宿主才弹下载确认」。
  判据不是「有没有弹框」(没人验得了弹框长什么样),是
  **没问到人的时候 Ensure 一次都不许被调到** —— 那才是「几百 MB 悄悄开始下」
  这件事唯一能在代码里验的形状。
*/

type fakeExt struct {
	mu      sync.Mutex
	st      ExtStatus
	ensured []string
}

func (f *fakeExt) hooks() *ExtHooks {
	return &ExtHooks{
		Status: func(string) (ExtStatus, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			return f.st, nil
		},
		Ensure: func(c string) error {
			f.mu.Lock()
			f.ensured = append(f.ensured, c)
			f.mu.Unlock()
			return nil
		},
	}
}

func (f *fakeExt) calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.ensured)
}

func extRT(t *testing.T, st ExtStatus) (*Runtime, *fakeExt) {
	t.Helper()
	fe := &fakeExt{st: st}
	return newRT(t, ``, func(o *Options) { o.Host.Ext = fe.hooks() }), fe
}

// 没装 + 壳问不了人 = 不下。
func TestExt问不到人就不许下(t *testing.T) {
	SetShellCaps(ShellCaps{}) // 壳没接对话框
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })
	r, fe := extRT(t, ExtStatus{Installed: false, Size: 142 * 1024 * 1024})

	_, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.ext.ensure('whisper')`)
	if err == nil {
		t.Fatal("壳问不了人,ensure 却成功了")
	}
	if kindOf(err) != string(KindUnsupported) {
		t.Errorf("拒绝要用 unsupported(插件据此降级),拿到 %s:%v", kindOf(err), err)
	}
	if n := fe.calls(); n != 0 {
		t.Errorf("没问到人却调了 %d 次 Ensure —— 几百 MB 已经开始下了", n)
	}
}

// 已经装了就直接过:不问、也不下。
func TestExt已装的不再问也不再下(t *testing.T) {
	SetShellCaps(ShellCaps{})
	t.Cleanup(func() { SetShellCaps(ShellCaps{}) })
	r, fe := extRT(t, ExtStatus{Installed: true})

	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.ext.ensure('ffmpeg')`); err != nil {
		t.Fatalf("已装的组件 ensure 应当直接过:%v", err)
	}
	if n := fe.calls(); n != 0 {
		t.Errorf("已经装了还调了 %d 次 Ensure", n)
	}
}

// 组件名拼错要**当场**说,不是挂在那里等下载。
func TestExt组件名拼错当场报(t *testing.T) {
	r, fe := extRT(t, ExtStatus{})
	for _, code := range []string{
		`__linplayer_sdk.ext.status('wisper')`,
		`__linplayer_sdk.ext.ensure('wisper')`,
	} {
		_, err := r.Eval(context.Background(), BudgetData, "x.js", code)
		if err == nil {
			t.Errorf("%s 竟然放行了", code)
			continue
		}
		if kindOf(err) != string(KindInvalid) || !strings.Contains(err.Error(), "wisper") {
			t.Errorf("%s 的错误没指名道姓:%s / %v", code, kindOf(err), err)
		}
	}
	if n := fe.calls(); n != 0 {
		t.Errorf("名字不认识却调了 %d 次 Ensure", n)
	}
}

// status 要把宿主报的三样原样交出去 —— 确认框拿 size 说「要下多少」。
func TestExtStatus把体积交给插件(t *testing.T) {
	r, _ := extRT(t, ExtStatus{Installed: true, Version: "base", Size: 1234})
	v, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.ext.status('whisper')`)
	if err != nil {
		t.Fatal(err)
	}
	s := string(v)
	for _, want := range []string{`"installed":true`, `"version":"base"`, `"size":1234`} {
		if !strings.Contains(s, want) {
			t.Errorf("status 少了 %s:%s", want, s)
		}
	}
}
