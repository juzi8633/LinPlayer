package plugin

import (
	"path/filepath"
	"testing"

	"linplayer/core/config"
	"linplayer/core/paths"
)

/*
播放页挂载点与侧栏入口的查询(SPEC 9.5 6.1,D65 D158 D279 D300 D305)。

☠ 这张表在阶段 ④ 之前不存在,于是两端的壳都没有挂载点:插件声明了 `playerOverlays`,
  核心层的按键通道也接好了,而那一层永远挂不出来 —— 插件那头 `player.onKey`
  注册得上、一次都不回调。两个壳各自独立报了这件事,说明「没有表」这件事
  在壳那边是看不出来的(它们只会以为没有插件声明)。
*/
func TestPlayerSurfaces把直播插件的覆盖层列出来(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	if _, err := h.DevLoad(filepath.Join(repoRoot(t), "plugins", "live")); err != nil {
		t.Fatal(err)
	}

	overlays := h.PlayerSurfaces("overlay")
	var hit *PlayerSurface
	for i := range overlays {
		if overlays[i].PluginID == "linplayer/live" && overlays[i].ID == "osd" {
			hit = &overlays[i]
		}
	}
	if hit == nil {
		t.Fatalf("直播插件的 osd 覆盖层没被列出来:%+v", overlays)
	}
	if !hit.Interactive {
		t.Error("直播 OSD 声明了 interactive,表里却是 false —— 壳会把它做成点击穿透,频道列表点不动")
	}

	// 侧栏入口同理:列不出来的话侧栏里没有「直播」
	side := h.SidebarEntries()
	found := false
	for _, e := range side {
		if e.Title == "直播" && e.Page == "live" {
			found = true
		}
	}
	if !found {
		t.Errorf("侧栏入口没列出来:%+v", side)
	}
}

// 停用的插件不许出现在表里:壳会去挂一个永远起不来的 surface。
func TestPlayerSurfaces停用的不列(t *testing.T) {
	const extra = `,"main":"main.js","contributes":{"playerOverlays":[{"id":"osd","interactive":true}]}`
	h := installAndRestart2(t, extra)

	if len(h.PlayerSurfaces("overlay")) == 0 {
		t.Fatal("装上之后一条都没列出来 —— 下面那半条判据就成了恒真")
	}
	// SetEnabled 改的是**下次启动**的启停(D170),所以要重启一次才算数
	if err := h.SetEnabled("alice/demo", false); err != nil {
		t.Fatal(err)
	}
	h.Shutdown()
	h2 := ResetForTest()
	h2.Start("windows", "2.0.0")
	t.Cleanup(h2.Shutdown)
	for _, s := range h2.PlayerSurfaces("") {
		if s.PluginID == "alice/demo" {
			t.Fatalf("停用之后还列着:%+v", s)
		}
	}
}

// installAndRestart2 装一个带指定 contributes 的小包再重启。
func installAndRestart2(t *testing.T, extra string) *Host {
	t.Helper()
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	pkg := writePkg(t, map[string]string{
		"manifest.json": manifestJSON("alice/demo", "1.0.0", extra),
		"main.js":       sdkPrelude + `definePlugin({ blocks: { osd: () => null } })`,
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
