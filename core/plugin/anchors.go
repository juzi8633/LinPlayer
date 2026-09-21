package plugin

// 锚点与设置分节的查询(SPEC 6.1 6.2,D155 D159 D286 D289)。
//
// 壳画官方页时问一次「这个锚点上有谁」,拿到的是可以直接挂 surface 的清单。
// ☠ 只列**启用中**的插件:停用的插件还在 manifest 里,列出来的话壳会去挂一个
//   永远起不来的 surface,表现是官方页上多一块空白骨架屏。

import (
	"context"
	"encoding/json"

	"linplayer/core/bus"
	"linplayer/core/plugin/rt"
)

// AnchorBlock 壳要画的一块。
type AnchorBlock struct {
	PluginID string `json:"plugin_id"`
	Name     string `json:"name"`
	Anchor   string `json:"anchor"`
	Mode     string `json:"mode"`
	Block    string `json:"block"`
}

// SettingsSectionInfo 官方设置页里的一节。
type SettingsSectionInfo struct {
	PluginID string        `json:"plugin_id"`
	Name     string        `json:"name"`
	Anchor   string        `json:"anchor"`
	Title    string        `json:"title"`
	Settings []SettingItem `json:"settings,omitempty"`
	Block    string        `json:"block,omitempty"`
}

// AnchorsAt 这个锚点上的注入块。anchor 为空 = 全部。
//
// 顺序按安装顺序(D155):List 已经是那个顺序,这里不再排。
func (h *Host) AnchorsAt(anchor string) []AnchorBlock {
	out := []AnchorBlock{}
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil {
			continue
		}
		for _, a := range m.Contributes.Anchors {
			if anchor != "" && a.Anchor != anchor {
				continue
			}
			out = append(out, AnchorBlock{PluginID: i.ID, Name: i.Name, Anchor: a.Anchor, Mode: a.Mode, Block: a.Block})
		}
	}
	return out
}

// SettingsSectionsAt 这个设置锚点上的分节。anchor 为空 = 全部。
func (h *Host) SettingsSectionsAt(anchor string) []SettingsSectionInfo {
	out := []SettingsSectionInfo{}
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil {
			continue
		}
		for _, s := range m.Contributes.SettingsSections {
			if anchor != "" && s.Anchor != anchor {
				continue
			}
			out = append(out, SettingsSectionInfo{
				PluginID: i.ID, Name: i.Name, Anchor: s.Anchor,
				Title: s.Title, Settings: s.Settings, Block: s.Block,
			})
		}
	}
	return out
}

func registerAnchors() {
	bus.Register("plugin.anchors", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		s, _ := a["anchor"].(string)
		return Default().AnchorsAt(s), nil
	})
	bus.Register("plugin.settingsSections", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		s, _ := a["anchor"].(string)
		return Default().SettingsSectionsAt(s), nil
	})
	bus.Register("plugin.playerSurfaces", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		s, _ := a["kind"].(string)
		return Default().PlayerSurfaces(s), nil
	})
	bus.Register("plugin.sidebar", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return Default().SidebarEntries(), nil
	})
	bus.Register("plugin.homeSections", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return Default().HomeSections(), nil
	})
	/* 用户选中的整页接管(D15 D29 D586)。
	   ☠ 这一条以前不存在:插件页的「接管位」标签能列、能选、选完还弹「重启后生效」,
	     而**没有任何一个壳去问过谁接管了哪一页** —— 三层 UI 都写了,只差最后一段,
	     表现是「选了接管,重启,一点变化没有」。 */
	bus.Register("plugin.pageTakeovers", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return Default().PageTakeovers(), nil
	})
	/* kind=items 那一栏的数据。**壳不直接调插件**:预算、连错计数、
	   「最后在跑的插件」这些都在 Host.Call 里,绕过去的话一个跑飞的插件
	   会把首页一起拖住,而崩了也不会被记账。 */
	bus.Register("plugin.homeItems", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		id, _ := a["plugin_id"].(string)
		sec, _ := a["id"].(string)
		if id == "" || sec == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 plugin_id 或 id")
		}
		out, err := Default().Call(ctx, id, rt.BudgetData, "homeSections."+sec, []any{}, map[string]any{})
		if err != nil {
			return nil, err
		}
		return json.RawMessage(out), nil
	})
}

/*
PlayerSurface 播放页上要挂的一块(SPEC 9.5,D65 D279 D300)。

☠ 这张表在阶段 ④ 之前**根本不存在**,于是两端的壳都没有挂载点:

	插件声明了 `playerOverlays`,核心层的按键通道也接好了,而那一层永远挂不出来 ——
	插件那头 `player.onKey` 注册得上、一次都不回调。两个壳各自独立报了这件事。
*/
type PlayerSurface struct {
	PluginID string `json:"plugin_id"`
	Name     string `json:"name"`
	// Kind overlay(盖画面上)| panel(侧栏一页)
	Kind  string `json:"kind"`
	ID    string `json:"id"`
	Title string `json:"title,omitempty"`
	Icon  string `json:"icon,omitempty"`
	// Interactive 只对 overlay 有意义:false = 点击穿透(默认)。
	Interactive bool `json:"interactive,omitempty"`
}

// PlayerSurfaces 播放页上所有启用插件的覆盖层与侧栏页。kind 为空 = 两种都要。
func (h *Host) PlayerSurfaces(kind string) []PlayerSurface {
	out := []PlayerSurface{}
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil {
			continue
		}
		if kind == "" || kind == "overlay" {
			for _, o := range m.Contributes.PlayerOverlays {
				out = append(out, PlayerSurface{
					PluginID: i.ID, Name: i.Name, Kind: "overlay", ID: o.ID, Interactive: o.Interactive,
				})
			}
		}
		if kind == "" || kind == "panel" {
			for _, o := range m.Contributes.PlayerPanels {
				out = append(out, PlayerSurface{
					PluginID: i.ID, Name: i.Name, Kind: "panel", ID: o.ID, Title: o.Title, Icon: o.Icon,
				})
			}
		}
	}
	return out
}

/*
HomeSections 首页栏目(SPEC 6.1,D156 D303)。

☠ 这张表和侧栏入口一样,以前**声明了没人要**:插件写了 homeSections,
核心层没有取它的命令,两个壳自然也画不出来 —— 而 manifest 合法、lp check 通过、
贡献点清单里还列着它。表现是「装了插件,首页什么都没多出来」。
*/
func (h *Host) HomeSections() []HomeSection {
	out := []HomeSection{}
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil {
			continue
		}
		for _, e := range m.Contributes.HomeSections {
			e.PluginID = i.ID
			out = append(out, e)
		}
	}
	return out
}

// SidebarEntries 侧栏入口(D158 D305)。
func (h *Host) SidebarEntries() []SidebarEntry {
	out := []SidebarEntry{}
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil {
			continue
		}
		for _, e := range m.Contributes.Sidebar {
			e.ID = i.ID + ":" + e.ID
			out = append(out, e)
		}
	}
	return out
}
