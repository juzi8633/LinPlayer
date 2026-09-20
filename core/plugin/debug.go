package plugin

// 调试面板的数据源(SPEC 16.5,D80 D81):`debug` 命名空间背后的那一半。
//
// ★ 开发者模式是总闸:关着的时候 rt.Host.Debug 是 nil,整个命名空间不挂。
//   插件调 debug.logs() 当场报 unsupported —— 那比拿到一张空表好,
//   空表和「这个插件真没日志」长得一模一样。

import (
	"fmt"

	"linplayer/core/config"
	"linplayer/core/plugin/rt"
)

// DevModeOn 开发者模式开着没有。
func DevModeOn() bool { return config.Current().PrefsOf().DevMode }

func (h *Host) debugHooks() *rt.DebugHooks {
	if !DevModeOn() {
		return nil
	}
	return &rt.DebugHooks{
		Plugins: func() any {
			out := []map[string]any{}
			for _, i := range h.List(nil) {
				out = append(out, map[string]any{"id": i.ID, "name": i.Name, "version": i.Version, "enabled": i.Enabled})
			}
			return out
		},
		Logs: func(id string) any {
			if r := h.Runtime(id); r != nil {
				return r.Logs()
			}
			return []rt.LogEntry{}
		},
		Requests: func(id string) any {
			if r := h.Runtime(id); r != nil {
				return r.Requests()
			}
			return []rt.RequestEntry{}
		},
		Surfaces: func() any { return ui.snapshot() },
		UITree: func(surfaceID string) (any, error) {
			s := ui.byID(surfaceID)
			if s == nil {
				return nil, nil // 已卸载不是错误:面板刷新时正好关掉一块是常态
			}
			r := h.Runtime(s.plugin)
			if r == nil {
				return nil, nil
			}
			return r.UITree(surfaceID)
		},
		Storage: func(id string) any {
			if r := h.Runtime(id); r != nil {
				return r.KV()
			}
			return map[string]any{}
		},
		SetStorage: func(id, key string, v any) error {
			r := h.Runtime(id)
			if r == nil {
				return fmt.Errorf("插件没在运行")
			}
			return r.SetKV(key, v)
		},
		Stats: func(id string) any {
			if r := h.Runtime(id); r != nil {
				return r.Stats()
			}
			return rt.CallStats{}
		},
	}
}
