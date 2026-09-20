package plugin

// 锚点与设置分节的查询(SPEC 6.1 6.2,D155 D159 D286 D289)。
//
// 壳画官方页时问一次「这个锚点上有谁」,拿到的是可以直接挂 surface 的清单。
// ☠ 只列**启用中**的插件:停用的插件还在 manifest 里,列出来的话壳会去挂一个
//   永远起不来的 surface,表现是官方页上多一块空白骨架屏。

import (
	"context"

	"linplayer/core/bus"
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
