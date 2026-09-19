package plugin

// plugin.* 命令:插件页四个标签(已安装 / 市场 / 接管位 / 仓库)与插件详情(SPEC 14.4)。

import (
	"context"
	"encoding/json"
	"sort"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/plugin/rt"
)

// RegisterCommands 由 core/commands 调用。
func RegisterCommands() {
	h := func() *Host { return Default() }
	reg := func(name string, f func(ctx context.Context, a map[string]any) (any, error)) {
		bus.Register(name, func(ctx context.Context, _ int64, a map[string]any) (any, error) {
			out, err := f(ctx, a)
			if err != nil {
				if _, ok := err.(*bus.Err); !ok {
					if pe, ok := err.(*rt.Error); ok {
						return nil, &bus.Err{Code: bus.EInvalid, Msg: pe.Message, Detail: pe.Detail}
					}
					return nil, &bus.Err{Code: bus.EInvalid, Msg: err.Error()}
				}
			}
			return out, err
		})
	}
	s := func(a map[string]any, k string) string { v, _ := a[k].(string); return v }
	b := func(a map[string]any, k string) bool { v, _ := a[k].(bool); return v }

	reg("plugin.list", func(ctx context.Context, a map[string]any) (any, error) {
		active, banner, suspect := h().SafeMode()
		ups := map[string]string{}
		if b(a, "with_updates") {
			ups = h().Updates(ctx, false)
		}
		return map[string]any{
			"plugins": h().List(ups), "pending_restart": h().PendingRestart(),
			"safe_mode": active, "safe_banner": banner, "safe_suspect": suspect,
			"disabled_all": len(h().st.DisabledAll) > 0,
		}, nil
	})
	reg("plugin.pendingRestart", func(ctx context.Context, a map[string]any) (any, error) { return h().PendingRestart(), nil })
	reg("plugin.inspect", func(ctx context.Context, a map[string]any) (any, error) {
		return h().Inspect(s(a, "path"), "local")
	})
	reg("plugin.installFile", func(ctx context.Context, a map[string]any) (any, error) {
		m, err := h().Install(s(a, "path"), "local")
		if err != nil {
			return nil, err
		}
		return map[string]string{"id": m.ID, "name": m.Name, "version": m.Version}, nil
	})
	reg("plugin.installFromRepo", func(ctx context.Context, a map[string]any) (any, error) {
		m, err := h().InstallFromRepo(ctx, s(a, "repo"), s(a, "id"), s(a, "version"))
		if err != nil {
			return nil, err
		}
		return map[string]string{"id": m.ID, "name": m.Name, "version": m.Version}, nil
	})
	reg("plugin.uninstall", func(ctx context.Context, a map[string]any) (any, error) {
		return nil, h().Uninstall(s(a, "id"), b(a, "delete_data"))
	})
	reg("plugin.cancelUninstall", func(ctx context.Context, a map[string]any) (any, error) {
		h().CancelUninstall(s(a, "id"))
		return nil, nil
	})
	reg("plugin.setEnabled", func(ctx context.Context, a map[string]any) (any, error) {
		return nil, h().SetEnabled(s(a, "id"), b(a, "enabled"))
	})
	reg("plugin.disableAll", func(ctx context.Context, a map[string]any) (any, error) { h().DisableAll(); return nil, nil })
	reg("plugin.restoreAll", func(ctx context.Context, a map[string]any) (any, error) { h().RestoreAll(); return nil, nil })
	reg("plugin.rollback", func(ctx context.Context, a map[string]any) (any, error) { return nil, h().Rollback(s(a, "id")) })
	reg("plugin.setLocked", func(ctx context.Context, a map[string]any) (any, error) {
		h().SetLocked(s(a, "id"), b(a, "locked"))
		return nil, nil
	})
	reg("plugin.skipVersion", func(ctx context.Context, a map[string]any) (any, error) {
		h().SkipVersion(s(a, "id"), s(a, "version"))
		return nil, nil
	})
	reg("plugin.detail", func(ctx context.Context, a map[string]any) (any, error) {
		id := s(a, "id")
		m, err := h().Manifest(id)
		if err != nil {
			return nil, err
		}
		items, vals, _ := h().Settings(id)
		var info *Info
		for _, in := range h().List(nil) {
			if in.ID == id {
				in := in
				info = &in
			}
		}
		return map[string]any{"info": info, "manifest": json.RawMessage(m.Raw), "settings": items, "values": vals,
			"usage": Usage(id), "contributes": m.ContributionList()}, nil
	})
	reg("plugin.setSetting", func(ctx context.Context, a map[string]any) (any, error) {
		return nil, h().SetSetting(s(a, "id"), s(a, "key"), a["value"])
	})
	reg("plugin.clearData", func(ctx context.Context, a map[string]any) (any, error) {
		return nil, h().ClearData(s(a, "id"), b(a, "cache_only"))
	})
	// 「复制错误详情」:最近 500 条日志 + 200 条请求,内存里,不落盘不上传(D172 D285 D456)
	reg("plugin.errorDetail", func(ctx context.Context, a map[string]any) (any, error) {
		id := s(a, "id")
		r := h().Runtime(id)
		out := map[string]any{"id": id, "logs": []rt.LogEntry{}, "requests": []rt.RequestEntry{}}
		if m, err := h().Manifest(id); err == nil {
			out["version"], out["issues"] = m.Version, m.Issues
		}
		if r != nil {
			out["logs"], out["requests"] = r.Logs(), r.Requests()
		}
		return out, nil
	})
	reg("plugin.market", func(ctx context.Context, a map[string]any) (any, error) { return h().Market(ctx, b(a, "refresh")), nil })
	reg("plugin.repos", func(ctx context.Context, a map[string]any) (any, error) {
		prefix, auto := h().RepoSettings()
		return map[string]any{"repos": h().Repos(), "github_prefix": prefix, "auto_update": auto}, nil
	})
	reg("plugin.addRepo", func(ctx context.Context, a map[string]any) (any, error) { return h().AddRepo(ctx, s(a, "url")) })
	reg("plugin.removeRepo", func(ctx context.Context, a map[string]any) (any, error) { return nil, h().RemoveRepo(s(a, "url")) })
	reg("plugin.setGithubPrefix", func(ctx context.Context, a map[string]any) (any, error) {
		h().SetGithubPrefix(s(a, "prefix"))
		return nil, nil
	})
	reg("plugin.setAutoUpdate", func(ctx context.Context, a map[string]any) (any, error) {
		h().SetAutoUpdate(b(a, "on"))
		return nil, nil
	})
	reg("plugin.updates", func(ctx context.Context, a map[string]any) (any, error) {
		return h().Updates(ctx, b(a, "refresh")), nil
	})
	reg("plugin.updateAll", func(ctx context.Context, a map[string]any) (any, error) {
		var only []string
		if ids, ok := a["ids"].([]any); ok {
			for _, x := range ids {
				if v, ok := x.(string); ok {
					only = append(only, v)
				}
			}
		}
		ok, failed := h().UpdateAll(ctx, only)
		return map[string]any{"ok": ok, "failed": failed}, nil
	})
	reg("plugin.takeovers", func(ctx context.Context, a map[string]any) (any, error) { return h().Takeovers(), nil })
	reg("plugin.setTakeover", func(ctx context.Context, a map[string]any) (any, error) {
		h().SetTakeover(s(a, "slot"), s(a, "plugin_id"))
		return nil, nil
	})
	reg("plugin.devLoad", func(ctx context.Context, a map[string]any) (any, error) {
		m, err := h().DevLoad(s(a, "dir"))
		if err != nil {
			return nil, err
		}
		return map[string]string{"id": m.ID, "name": m.Name, "version": m.Version}, nil
	})
	reg("plugin.devUnload", func(ctx context.Context, a map[string]any) (any, error) { h().DevUnload(s(a, "id")); return nil, nil })
	reg("plugin.devList", func(ctx context.Context, a map[string]any) (any, error) { return h().DevList(), nil })
	// 壳报能力(WebView / jar / py);壳做完宿主请求后回结果(见 rt/shell.go)
	reg("plugin.setCapabilities", func(ctx context.Context, a map[string]any) (any, error) {
		rt.SetShellCaps(rt.ShellCaps{WebView: b(a, "webview"), SpiderJar: b(a, "spider_jar"), SpiderPy: b(a, "spider_py")})
		return nil, nil
	})
	reg("plugin.shellResult", func(ctx context.Context, a map[string]any) (any, error) {
		id, _ := a["id"].(float64)
		data, _ := json.Marshal(a["data"])
		var e *rt.Error
		if m, ok := a["error"].(map[string]any); ok {
			e = &rt.Error{Kind: s(m, "kind"), Message: s(m, "message")}
			if e.Kind == "" {
				e.Kind = rt.KindInternal
			}
		}
		rt.ShellResult(int64(id), b(a, "ok"), data, e)
		return nil, nil
	})
}

// Slot 一个接管位与候选(D15)。
type Slot struct {
	Slot       string      `json:"slot"`
	Current    string      `json:"current"` // 空 = 官方
	Candidates []SlotOwner `json:"candidates"`
}

// SlotOwner 能接管这个位置的插件。
type SlotOwner struct {
	PluginID string `json:"plugin_id"`
	Name     string `json:"name"`
}

// Takeovers 接管位标签页:列出有插件声明的接管位;没有插件接管时为空(D29 D75)。
// ponytail: 阶段 ① 只有整页接管(pageTakeovers)能从 manifest 列出来;OSD、主题、锚点替换随阶段 ②⑤ 的渲染器加入。
func (h *Host) Takeovers() []Slot {
	bySlot := map[string]*Slot{}
	for _, id := range h.Enabled() {
		m, err := h.Manifest(id)
		if err != nil {
			continue
		}
		for _, t := range m.Contributes.PageTakeovers {
			sl := bySlot["page:"+t.Target]
			if sl == nil {
				sl = &Slot{Slot: "page:" + t.Target}
				bySlot[sl.Slot] = sl
			}
			sl.Candidates = append(sl.Candidates, SlotOwner{PluginID: id, Name: m.Name})
		}
	}
	h.mu.Lock()
	out := []Slot{}
	for k, sl := range bySlot {
		sl.Current = h.st.Takeovers[k]
		out = append(out, *sl)
	}
	h.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].Slot < out[j].Slot })
	return out
}

// SetTakeover 选某个接管位由谁接管(空 = 官方)。
func (h *Host) SetTakeover(slot, pluginID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.st.Takeovers == nil {
		h.st.Takeovers = map[string]string{}
	}
	if strings.TrimSpace(pluginID) == "" {
		delete(h.st.Takeovers, slot)
	} else {
		h.st.Takeovers[slot] = pluginID
	}
	h.saveLocked()
}
