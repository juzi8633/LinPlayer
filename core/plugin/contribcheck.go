package plugin

/*
manifest 声明的贡献点,代码里有没有对应实现。

☠ 这张表**漏一个贡献点,那个贡献点就永远查不出「声明了没实现」** ——
  而那正是「装了没反应」的头号来源:用户装上、界面上什么都不多,一条错也没有。
  2026-09-21 实测:表里缺 `playerOverlays` / `anchors` / `settingsSections` 这几种,
  于是一个声明了播放器覆盖层却没实现它的包,`lp check` 照样报「声明的贡献点都有实现」。

`lp check`(作者本地)与 CI 走的是同一个函数 —— 两边各写一份的话,
本地绿 CI 红,作者只会以为是 CI 的问题(D484 的原意)。
*/

import (
	"encoding/json"

	"linplayer/core/plugin/rt"
)

// Runner 能回答「这个路径上有没有实现」的东西。运行时满足它。
type Runner interface {
	Has(path string) bool
}

var _ Runner = (*rt.Runtime)(nil)

// MissingImpl manifest 声明了、definePlugin 里却没有的那些。
func MissingImpl(m *Manifest, r Runner) []string {
	var raw struct {
		Contributes struct {
			DataSource   *json.RawMessage           `json:"dataSource"`
			Commands     []struct{ ID string }      `json:"commands"`
			M3u8Filters  []struct{ ID string }      `json:"m3u8Filters"`
			Menus        []struct{ Command string } `json:"menus"`
			HomeSections []struct {
				ID   string
				Kind string
			} `json:"homeSections"`
			Pages    []struct{ ID string } `json:"pages"`
			Settings []struct {
				Type   string
				Action string
			} `json:"settings"`
			Hooks *struct {
				Navigate      bool             `json:"navigate"`
				ListTransform *json.RawMessage `json:"listTransform"`
				CardBadge     bool             `json:"cardBadge"`
			} `json:"hooks"`
			NextUp bool `json:"nextUp"`

			// 下面这几种是阶段 ②~⑤ 加的,原来一个都不在表上
			PlayerOverlays []struct{ ID string } `json:"playerOverlays"`
			PlayerPanels   []struct{ ID string } `json:"playerPanels"`
			OSD            []struct{ ID string } `json:"osd"`
			Anchors        []struct {
				Mode  string
				Block string
			} `json:"anchors"`
			SettingsSections []struct{ Block string } `json:"settingsSections"`
			Sidebar          []struct{ Page string }  `json:"sidebar"`
			LaunchTargets    []struct{ Page string }  `json:"launchTargets"`
			SettingsPage     string                   `json:"settingsPage"`
		} `json:"contributes"`
	}
	_ = json.Unmarshal(m.Raw, &raw)

	var out []string
	need := func(path string) {
		if path == "" || r.Has(path) {
			return
		}
		out = append(out, path)
	}
	// 可空的 id:设置分节可以只给 `settings` 不给 `block`,锚点的 hide 也没有 block。
	// 不判空的话会去查 `blocks.` 这个路径,于是**每个这样的包都红** —— 门禁当场变噪音。
	needOf := func(prefix, id string) {
		if id != "" {
			need(prefix + id)
		}
	}
	c := raw.Contributes

	if c.DataSource != nil {
		need("dataSource.detail")
		need("dataSource.play")
	}
	for _, x := range c.Commands {
		need("commands." + x.ID)
	}
	for _, x := range c.Menus {
		need("commands." + x.Command)
	}
	for _, x := range c.M3u8Filters {
		need("m3u8Filters." + x.ID)
	}
	for _, x := range c.HomeSections {
		if x.Kind == "items" {
			need("homeSections." + x.ID)
		}
	}
	for _, x := range c.Pages {
		need("pages." + x.ID)
	}
	for _, x := range c.Settings {
		if x.Type == "button" && x.Action != "" {
			need("settingActions." + x.Action)
		}
	}
	if c.Hooks != nil {
		if c.Hooks.Navigate {
			need("hooks.navigate")
		}
		if c.Hooks.ListTransform != nil {
			need("hooks.listTransform")
		}
		if c.Hooks.CardBadge {
			need("hooks.cardBadge")
		}
	}
	if c.NextUp {
		need("nextUp")
	}

	// 画在别处的那几块:全部是 blocks.<id>
	for _, x := range c.PlayerOverlays {
		needOf("blocks.", x.ID)
	}
	for _, x := range c.PlayerPanels {
		needOf("blocks.", x.ID)
	}
	for _, x := range c.OSD {
		needOf("blocks.", x.ID)
	}
	for _, x := range c.Anchors {
		// hide 只是把官方那块藏掉,没有自己的内容要画
		if x.Mode != "hide" {
			needOf("blocks.", x.Block)
		}
	}
	for _, x := range c.SettingsSections {
		needOf("blocks.", x.Block)
	}
	needOf("blocks.", c.SettingsPage)

	// 入口指向的页面必须真有
	for _, x := range c.Sidebar {
		needOf("pages.", x.Page)
	}
	for _, x := range c.LaunchTargets {
		needOf("pages.", x.Page)
	}
	return out
}
