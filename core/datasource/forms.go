package datasource

// 添加服务器:插件服务器类型的表单 → createSources → 用户勾选 → addSources(D131 D45 D346)。
// 以及服务器列表的分组操作:改「允许聚合」、手改 host、删整个订阅、插件声明的分组菜单。

import (
	"context"
	"encoding/json"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/plugin"
	"linplayer/core/plugin/rt"
	"linplayer/core/source"
	"linplayer/core/sourcecmd"
)

func pluginForms() []sourcecmd.SourceForm {
	h := plugin.Default()
	var out []sourcecmd.SourceForm
	for _, id := range h.Enabled() {
		m, err := h.Manifest(id)
		if err != nil || m.Contributes.DataSource == nil {
			continue
		}
		for _, st := range m.Contributes.DataSource.ServerTypes {
			f := sourcecmd.SourceForm{Kind: "plugin", Label: st.Name, PluginID: id, PluginName: m.Name, TypeID: st.ID, CanTest: false, Fields: []sourcecmd.FormField{}}
			for _, fd := range st.Fields {
				ff := sourcecmd.FormField{Key: fd.Key, Label: fd.Title, Type: fd.Type, Placeholder: fd.Description}
				switch fd.Type {
				case "text", "":
					ff.Type = "text"
					ff.Multiline = fd.Multiline
				case "password":
				default:
					continue // 阶段 ① 表单只渲染文本类字段;复杂表单走插件自己的页面(阶段 ②)
				}
				f.Fields = append(f.Fields, ff)
			}
			if len(f.Fields) > 0 {
				f.Fields[0].Required = true
			}
			out = append(out, f)
		}
	}
	return out
}

// cmdCreateSources 表单提交 → 插件给出一个或多个源草稿(或先给多仓让用户勾)。
// 用户勾选的仓以 `$repos` 放进 form 再回调一次(见 plugin-sdk.d.ts createSources)。
func cmdCreateSources(ctx context.Context, _ int64, a map[string]any) (any, error) {
	pid, typeID := str(a, "plugin_id"), str(a, "type_id")
	form, _ := a["form"].(map[string]any)
	if pid == "" || typeID == "" {
		return nil, bus.NewErr(bus.EInvalid, "缺少 plugin_id / type_id")
	}
	if form == nil {
		form = map[string]any{}
	}
	if repos, ok := a["repos"].([]any); ok {
		form["$repos"] = repos
	}
	raw, err := plugin.Default().Call(ctx, pid, rt.BudgetData, "dataSource.createSources", []any{typeID, form}, map[string]any{})
	if err != nil {
		return nil, MapError(err)
	}
	var out struct {
		Sources []map[string]any `json:"sources"`
		Repos   []map[string]any `json:"repos"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, bus.NewErr(bus.EUpstream, "插件返回的源列表结构不对")
	}
	c := config.Current()
	for _, s := range out.Sources {
		id, _ := s["id"].(string)
		s["exists"] = c.Find(string(source.PluginKind(pid, id))) != nil
	}
	if out.Sources == nil {
		out.Sources = []map[string]any{}
	}
	if out.Repos == nil {
		out.Repos = []map[string]any{}
	}
	return out, nil
}

// cmdAddSources 用户勾好的源落进账号表。不切换当前服务器。
func cmdAddSources(ctx context.Context, _ int64, a map[string]any) (any, error) {
	pid, typeID := str(a, "plugin_id"), str(a, "type_id")
	list, _ := a["sources"].([]any)
	if pid == "" || len(list) == 0 {
		return nil, bus.NewErr(bus.EInvalid, "没有要添加的源")
	}
	cfgMu.Lock()
	defer cfgMu.Unlock()
	added := []string{}
	for _, x := range list {
		m, _ := x.(map[string]any)
		d, err := draftOf(m)
		if err != nil {
			return nil, bus.NewErr(bus.EInvalid, err.Error())
		}
		putSource(pid, pid+":"+typeID, d)
		added = append(added, string(source.PluginKind(pid, d.ID)))
	}
	if err := save(); err != nil {
		return nil, bus.NewErr(bus.EInternal, "保存失败: %v", err)
	}
	return map[string]any{"added": added}, nil
}

// cmdSetAggregate 「允许聚合」开关(D233),Emby 与数据源通用。
func cmdSetAggregate(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	allow, _ := a["allow"].(bool)
	cfgMu.Lock()
	defer cfgMu.Unlock()
	acc := config.Current().Find(sid)
	if acc == nil {
		return nil, bus.NewErr(bus.ENotFound, "没有这个服务器:"+sid)
	}
	acc.SetAllowAggregate(allow)
	if err := save(); err != nil {
		return nil, bus.NewErr(bus.EInternal, "保存失败: %v", err)
	}
	return map[string]bool{"aggregate": allow}, nil
}

// cmdSetHost 用户在服务器列表手改源的 host,插件在 ctx.source.hostOverride 拿到(D176)。
func cmdSetHost(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid, err := serverArg(a)
	if err != nil {
		return nil, err
	}
	cfgMu.Lock()
	defer cfgMu.Unlock()
	acc := config.Current().Find(sid)
	ps := (*config.PluginSource)(nil)
	if acc != nil {
		ps = acc.PluginSource()
	}
	if ps == nil {
		return nil, bus.NewErr(bus.ENotFound, "没有这个数据源:"+sid)
	}
	ps.HostOverride = strings.TrimRight(strings.TrimSpace(str(a, "host")), "/")
	acc.SetPluginSource(*ps)
	if err := save(); err != nil {
		return nil, bus.NewErr(bus.EInternal, "保存失败: %v", err)
	}
	return map[string]string{"host": ps.HostOverride}, nil
}

// cmdRemoveGroup 删整个订阅分组:源的账号行去掉,收藏与观看记录保留(D333)。
func cmdRemoveGroup(ctx context.Context, _ int64, a map[string]any) (any, error) {
	group := str(a, "group")
	if group == "" {
		return nil, bus.NewErr(bus.EInvalid, "缺少 group")
	}
	cfgMu.Lock()
	defer cfgMu.Unlock()
	c := config.Current()
	var keys []string
	for _, acc := range c.PluginSources("") {
		if acc.PluginSource().Group == group {
			keys = append(keys, acc.Server)
		}
	}
	for _, k := range keys {
		c.Remove(k)
	}
	if err := save(); err != nil {
		return nil, bus.NewErr(bus.EInternal, "保存失败: %v", err)
	}
	return map[string]int{"removed": len(keys)}, nil
}

// GroupMenu 插件在 manifest 声明的服务器菜单(menus[].target = server,D157 D379)。
type GroupMenu struct {
	PluginID string `json:"plugin_id"`
	Command  string `json:"command"`
	Title    string `json:"title"`
}

func cmdServerMenus(ctx context.Context, _ int64, a map[string]any) (any, error) {
	pid := str(a, "plugin_id")
	h := plugin.Default()
	m, err := h.Manifest(pid)
	if err != nil {
		return []GroupMenu{}, nil
	}
	var raw struct {
		Contributes struct {
			Menus []struct {
				Target  string `json:"target"`
				Title   string `json:"title"`
				Command string `json:"command"`
			} `json:"menus"`
		} `json:"contributes"`
	}
	_ = json.Unmarshal(m.Raw, &raw)
	out := []GroupMenu{}
	for _, mn := range raw.Contributes.Menus {
		if mn.Target == "server" {
			out = append(out, GroupMenu{PluginID: pid, Command: mn.Command, Title: mn.Title})
		}
	}
	return out, nil
}

// cmdRunCommand 执行插件命令(菜单项、设置按钮)。参数原样给插件。
func cmdRunCommand(ctx context.Context, _ int64, a map[string]any) (any, error) {
	pid, cmd := str(a, "plugin_id"), str(a, "command")
	args, _ := a["args"].(map[string]any)
	if args == nil {
		args = map[string]any{}
	}
	out, err := plugin.Default().Call(ctx, pid, rt.BudgetData, "commands."+cmd, []any{args}, map[string]any{})
	if err != nil {
		return nil, MapError(err)
	}
	return json.RawMessage(out), nil
}
