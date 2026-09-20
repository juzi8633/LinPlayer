package plugin

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/paths"
)

/*
主题与壁纸(SPEC 11,D69 D372 D441 D546)。

☠ 这一组里最要紧的是「主题加载失败要回退」:主题坏了的表现是**整个界面画不出来**,
  这时候用户连「换回官方主题」的按钮都点不到。所以回退必须由核心层记住 ——
  下次启动直接用官方,而不是等用户自己想办法。
  判据因此是「重启之后 ActiveTheme 回的是空」,不是「有没有打日志」。
*/

const themeExtra = `,"main":"main.js","contributes":{"theme":{"platform":"desktop","axaml":["theme/app.axaml"],"modes":["dark"]}}`

func TestTheme加载失败要回退官方并且记住(t *testing.T) {
	h := installAndRestart2(t, themeExtra)

	list := h.Themes("desktop")
	if len(list) != 1 || list[0].PluginID != "alice/demo" {
		t.Fatalf("主题没列出来:%+v", list)
	}
	if len(list[0].Axaml) != 1 || list[0].Dir == "" {
		t.Fatalf("样式文件或包目录没给全,壳拼不出绝对路径:%+v", list[0])
	}

	if err := h.SetActiveTheme("alice/demo"); err != nil {
		t.Fatal(err)
	}
	if got := h.ActiveTheme("desktop"); got == nil {
		t.Fatal("选了主题却回空 —— 壳会一直用官方主题,用户以为没生效")
	}

	// 壳报一次加载失败
	h.NoteThemeFailed("alice/demo", "XAML 里引用了不存在的资源")

	/* 重启:这一次不许再自动用它。
	   ☠ 要先 markStable:连崩检测(crashLimit=2)把「30 秒内又起一次」算成一次崩,
	     两次快速重启会进安全模式,那时候所有插件都不加载 —— 判据会因为
	     一个完全不相干的原因变绿。 */
	h.markStable()
	h.Shutdown()
	h2 := ResetForTest()
	h2.Start("windows", "2.0.0")
	t.Cleanup(h2.Shutdown)
	if got := h2.ActiveTheme("desktop"); got != nil {
		t.Fatalf("上次加载崩了,重启后还在用它:%+v —— 用户会卡在一个画不出来的界面里", got)
	}

	// 用户手动重选(他可能刚把主题修好):要能再用起来
	h2.markStable()
	if err := h2.SetActiveTheme("alice/demo"); err != nil {
		t.Fatal(err)
	}
	if got := h2.ActiveTheme("desktop"); got == nil {
		t.Fatal("手动重选之后还是回空 —— 修好了也用不上,等于这个主题被永久拉黑")
	}
}

// 一包一端(D72):这一端没有的主题不许出现在选择列表里。
func TestTheme一包一端(t *testing.T) {
	h := installAndRestart2(t, themeExtra)
	if n := len(h.Themes("android")); n != 0 {
		t.Fatalf("桌面主题出现在手机端的可选列表里(%d 条)—— 用户选了会看到「不支持此设备」", n)
	}
	if n := len(h.Themes("desktop")); n != 1 {
		t.Fatalf("桌面端应当看得到它,却有 %d 条 —— 上面那条判据就成了恒真", n)
	}
}

const wallpaperExtra = `,"main":"main.js","contributes":{"wallpaper":{"id":"bg","title":"示范壁纸"}}`

/*
壁纸只有**当前选中的那个插件**换得动(D441)。

☠ 不判这一下的话,任何装上的插件都能把用户的桌布换掉,而用户看到的是

	「我的壁纸自己变了」,根本不知道是哪个插件干的。
*/
func TestWallpaper只有选中的那个换得动(t *testing.T) {
	h := installAndRestart2(t, wallpaperExtra)
	if len(h.Wallpapers()) != 1 {
		t.Fatalf("壁纸接管位没列出来:%+v", h.Wallpapers())
	}
	l, err := h.get("alice/demo", "test")
	if err != nil {
		t.Fatal(err)
	}

	// 还没选它:换不动
	_, err = l.rt.Eval(context.Background(), 5*time.Second, "x.js",
		`__linplayer_sdk.wallpaper.set({kind:'image', image:'bg.webp'})`)
	if err == nil {
		t.Fatal("没被选中的插件也换得动壁纸")
	}
	if !strings.Contains(err.Error(), "壁纸") {
		t.Errorf("拒绝的话没说清原因:%v", err)
	}

	// 选它之后就能换
	if err := h.SetActiveWallpaper("alice/demo"); err != nil {
		t.Fatal(err)
	}
	if _, err := l.rt.Eval(context.Background(), 5*time.Second, "x.js",
		`__linplayer_sdk.wallpaper.set({kind:'image', image:'bg.webp'})`); err != nil {
		t.Fatalf("选中之后还是换不动:%v", err)
	}

	// 认不出来的类型要当场说,不能让壳收到一个不认识的 kind 之后默默忽略
	_, err = l.rt.Eval(context.Background(), 5*time.Second, "x.js",
		`__linplayer_sdk.wallpaper.set({kind:'gif'})`)
	if err == nil {
		t.Fatal("认不出来的壁纸类型放行了 —— 壳只能忽略,表现是「set 了没反应」")
	}
}

// 壁纸切换**不用重启**(D441):set 之后壳要立刻收到一条事件。
func TestWallpaper切换要当场发事件(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := installAndRestart2(t, wallpaperExtra)
	if err := h.SetActiveWallpaper("alice/demo"); err != nil {
		t.Fatal(err)
	}
	l, err := h.get("alice/demo", "test")
	if err != nil {
		t.Fatal(err)
	}

	c := captureUI(t)
	if _, err := l.rt.Eval(context.Background(), 5*time.Second, "x.js",
		`__linplayer_sdk.wallpaper.set({kind:'video', url:'https://例子.测试/bg.mp4'})`); err != nil {
		t.Fatal(err)
	}
	c.wait(t, "壁纸事件", func() bool {
		for _, e := range c.events("plugin.wallpaper") {
			if strings.Contains(e, "video") {
				return true
			}
		}
		return false
	})
}

/*
官方示范主题(D546:各端一套)。

☠ 判据是**三端各有一个、而且真的能被各自那一端选到**。只查目录在不在是不够的:

	`platform` 写错的话包在,列表里却一个都不出现,用户看到的是「官方主题呢?」。
*/
func TestTheme官方示范主题三端各一套(t *testing.T) {
	want := map[string]string{
		"theme-midnight":       "desktop",
		"theme-midnight-phone": "android",
		"theme-midnight-tv":    "android_tv",
	}
	for dir, platform := range want {
		t.Run(dir, func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			if _, err := config.Load(); err != nil {
				t.Fatal(err)
			}
			h := ResetForTest()
			h.Start("windows", "2.0.0")
			t.Cleanup(h.Shutdown)
			if _, err := h.DevLoad(filepath.Join(repoRoot(t), "plugins", dir)); err != nil {
				t.Fatalf("加载失败: %v", err)
			}
			list := h.Themes(platform)
			if len(list) != 1 {
				t.Fatalf("%s 端拿到 %d 个主题,应当是 1 —— platform 写错的话包在、列表里却没有", platform, len(list))
			}
			th := list[0]
			if platform == "desktop" {
				if len(th.Axaml) == 0 {
					t.Error("桌面主题没给样式文件,壳没东西可加载")
				}
			} else if th.JSON == "" {
				t.Error("手机 / TV 主题没给 JSON,壳没东西可解释")
			}
			// 文件要真在包里:路径写错的表现是「选了没变化」
			for _, rel := range append(append([]string{}, th.Axaml...), th.JSON) {
				if rel == "" {
					continue
				}
				if _, err := os.Stat(filepath.Join(th.Dir, filepath.FromSlash(rel))); err != nil {
					t.Errorf("主题声明了 %s,包里却没有:%v", rel, err)
				}
			}
		})
	}
}

/*
只实现了 `definePlugin({ wallpaper })`、从不调 `wallpaper.set()` 的插件也要画得出来(D441)。

☠ 不问这一下的话,用户选了壁纸界面一点反应都没有、也不报错 ——
  而插件作者那边同样看不出问题:他按定义源实现了 `wallpaper` 入口,只是没人调。
*/
func TestWallpaper只给初始内容的插件也画得出来(t *testing.T) {
	const extra = `,"main":"main.js","contributes":{"wallpaper":{"id":"bg","title":"示范壁纸"}}`
	h := installAndRestartWith(t, extra,
		`definePlugin({ wallpaper: async () => ({ kind: 'image', image: 'bg.webp' }) })`)
	if err := h.SetActiveWallpaper("alice/demo"); err != nil {
		t.Fatal(err)
	}
	got, err := h.InitialWallpaper(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	m, _ := got.(map[string]any)
	if m == nil || m["kind"] != "image" {
		t.Fatalf("没问到插件的初始壁纸:%+v —— 用户选了壁纸界面一点反应都没有", got)
	}
	if m["plugin"] != "alice/demo" {
		t.Errorf("回的内容里没带是谁给的:%+v", m)
	}
}

// 没选壁纸时不许去问任何插件。
func TestWallpaper没选的时候不问(t *testing.T) {
	const extra = `,"main":"main.js","contributes":{"wallpaper":{"id":"bg"}}`
	h := installAndRestartWith(t, extra,
		`definePlugin({ wallpaper: async () => { globalThis.__asked = true; return { kind: 'image', image: 'x' } } })`)
	got, err := h.InitialWallpaper(context.Background())
	if err != nil || got != nil {
		t.Fatalf("没选壁纸却问了插件:%v / %v", got, err)
	}
}

// installAndRestartWith 装一个带指定 contributes 与入口的小包再重启。
func installAndRestartWith(t *testing.T, extra, main string) *Host {
	t.Helper()
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	pkg := writePkg(t, map[string]string{
		"manifest.json": manifestJSON("alice/demo", "1.0.0", extra),
		"main.js":       sdkPrelude + main,
	})
	if _, err := h.Install(pkg, "local"); err != nil {
		t.Fatal(err)
	}
	h.markStable()
	h2 := ResetForTest()
	h2.Start("windows", "2.0.0")
	t.Cleanup(h2.Shutdown)
	return h2
}
