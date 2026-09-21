package plugin

import (
	"testing"

	"linplayer/core/paths"
)

// 接管位以前只差最后一段:能列、能选、选完弹「重启后生效」,而没有任何一个壳
// 去问过谁接管了哪一页 —— 选了等于没选(D586)。这条钉的就是最后那一段。
func Test选了接管之后壳问得到是谁(t *testing.T) {
	paths.SetRoot(t.TempDir())
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	pkg := writePkg(t, map[string]string{
		"manifest.json": manifestJSON("alice/demo", "1.0.0",
			`,"contributes":{"pages":[{"id":"myhome","title":"我的首页"}],"pageTakeovers":[{"target":"home","page":"myhome"}]}`),
		"main.js": sdkPrelude + "definePlugin({pages:{myhome(){return null}}})",
	})
	if _, err := h.Install(pkg, "local"); err != nil {
		t.Fatal(err)
	}
	h.markStable()
	h = restart(t)

	// 没选之前:候选里有它,但生效表是空的 —— 默认官方(D15)
	if slots := h.Takeovers(); len(slots) != 1 || len(slots[0].Candidates) != 1 {
		t.Fatalf("接管位标签应列出这个候选: %+v", slots)
	}
	if got := h.PageTakeovers(); len(got) != 0 {
		t.Fatalf("没选就不该接管,默认官方: %+v", got)
	}

	h.SetTakeover("page:home", "alice/demo")
	got := h.PageTakeovers()
	if len(got) != 1 {
		t.Fatalf("选了之后壳必须问得到,否则「重启后生效」是句空话: %+v", got)
	}
	if got[0].Target != "home" || got[0].PluginID != "alice/demo" || got[0].Page != "myhome" {
		t.Fatalf("接管的是哪一页、由谁画,三样都要对: %+v", got[0])
	}

	// 停用之后要还回去:停用的插件还在 manifest 里,留着接管的话首页会变成
	// 一块永远起不来的空白,而用户根本不知道该去哪儿关。
	// 停用和安装一样是重启才生效(D170),所以这里也得重启一次再看。
	if err := h.SetEnabled("alice/demo", false); err != nil {
		t.Fatal(err)
	}
	h = restart(t)
	if got := h.PageTakeovers(); len(got) != 0 {
		t.Fatalf("插件停用了,首页得还给官方: %+v", got)
	}
}
