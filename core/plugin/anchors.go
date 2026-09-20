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
}
