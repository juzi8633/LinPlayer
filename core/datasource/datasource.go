// Package datasource 是插件数据源的核心层(SPEC 第 8 章):开放键 → 插件动词、统一结构、
// 列表钩子与屏蔽、聚合搜索、换源、观看记录与收藏。
//
// 数据源在账号表里就是一行账号(见 config/psource.go),UI 按服务器对待它。
package datasource

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"linplayer/core/account"
	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/net/localserve"
	"linplayer/core/plugin"
	"linplayer/core/plugin/rt"
	"linplayer/core/source"
)

// cfgMu 串行化本包对账号表的改动(订阅刷新在插件的 goroutine 里并发加删源)。
var cfgMu sync.Mutex

// Wire 把数据源回调挂到插件宿主上。lp_init 在 plugin.Start 之前调。
func Wire(h *plugin.Host) {
	h.SourceHost = func(pluginID string) rt.Host {
		return rt.Host{
			SourcesList:   func() any { return sourcesOf(pluginID) },
			SourcesAdd:    func(d map[string]any, st string) error { return addSource(pluginID, st, d) },
			SourcesUpdate: func(id string, patch map[string]any) error { return updateSource(pluginID, id, patch) },
			SourcesRemove: func(id string) error { return removeSource(pluginID, id) },
			HostPort:      hostPort,
			AssetURL:      func(p string) string { return assetURL(pluginID, p) },
		}
	}
}

func hostPort() int {
	s := localserve.Default()
	if s == nil {
		return 0
	}
	addr := s.Addr
	if i := strings.LastIndex(addr, ":"); i >= 0 {
		var p int
		fmt.Sscanf(addr[i+1:], "%d", &p)
		return p
	}
	return 0
}

// assetURL 包内资源地址(assets.url)。本地服务没起来时为空(那时图片通道整体不可用)。
func assetURL(pluginID, p string) string {
	s := localserve.Default()
	if s == nil {
		return ""
	}
	return s.PluginAssetURL(pluginID, p)
}

// ---------------------------------------------------------------- 源的增删(sources 命名空间,D132)

// SourceInfo 交给插件的源信息(plugin-sdk.d.ts SourceInfo)。
type SourceInfo struct {
	Key          string          `json:"key"`
	ID           string          `json:"id"`
	Name         string          `json:"name"`
	Config       json.RawMessage `json:"config"`
	HostOverride string          `json:"hostOverride,omitempty"`
	ServerType   string          `json:"serverType,omitempty"`
	Group        string          `json:"group,omitempty"`
}

func infoOf(a config.Account) SourceInfo {
	ps := a.PluginSource()
	cfg := ps.Config
	if len(cfg) == 0 {
		cfg = json.RawMessage("null")
	}
	return SourceInfo{Key: a.Server, ID: ps.ID, Name: a.DisplayName(), Config: cfg, HostOverride: ps.HostOverride, ServerType: ps.ServerType, Group: ps.Group}
}

func sourcesOf(pluginID string) []SourceInfo {
	out := []SourceInfo{}
	for _, a := range config.Current().PluginSources(pluginID) {
		out = append(out, infoOf(a))
	}
	return out
}

// Draft 插件给的源草稿(plugin-sdk.d.ts SourceDraft)。
type Draft struct {
	ID                string          `json:"id"`
	Name              string          `json:"name"`
	Config            json.RawMessage `json:"config"`
	Group             string          `json:"group,omitempty"`
	GroupName         string          `json:"groupName,omitempty"`
	AggregateDefault  bool            `json:"aggregateDefault,omitempty"`
	UnavailableReason string          `json:"unavailableReason,omitempty"`
	Icon              string          `json:"icon,omitempty"`
	DisablePrefetch   bool            `json:"disablePrefetch,omitempty"`
}

func validSourceID(id string) bool {
	if id == "" || strings.Contains(id, "/") {
		return false
	}
	for _, r := range id {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '.' || r == '_' || r == '-') {
			return false
		}
	}
	return true
}

func draftOf(m map[string]any) (Draft, error) {
	var d Draft
	b, _ := json.Marshal(m)
	if err := json.Unmarshal(b, &d); err != nil {
		return d, err
	}
	if !validSourceID(d.ID) {
		return d, fmt.Errorf("源 id %q 不合规:只许字母数字与 . _ -,不能含 /", d.ID)
	}
	if d.Name == "" {
		d.Name = d.ID
	}
	return d, nil
}

func putSource(pluginID, serverType string, d Draft) {
	key := string(source.PluginKind(pluginID, d.ID))
	c := config.Current()
	c.UpsertPluginSource(key, d.Name, config.PluginSource{
		Plugin: pluginID, ID: d.ID, Config: d.Config, ServerType: serverType, Group: d.Group, GroupName: d.GroupName,
		AggregateDefault: d.AggregateDefault, Unavailable: d.UnavailableReason, DisablePrefetch: d.DisablePrefetch, Icon: d.Icon,
	})
}

func addSource(pluginID, serverType string, m map[string]any) error {
	d, err := draftOf(m)
	if err != nil {
		return err
	}
	if serverType != "" && !strings.Contains(serverType, ":") {
		serverType = pluginID + ":" + serverType // 与「添加服务器」那条路记的形状一致
	}
	cfgMu.Lock()
	defer cfgMu.Unlock()
	putSource(pluginID, serverType, d)
	return save()
}

func updateSource(pluginID, id string, patch map[string]any) error {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	key := string(source.PluginKind(pluginID, id))
	c := config.Current()
	a := c.Find(key)
	if a == nil {
		return fmt.Errorf("没有这个源:%s", id)
	}
	ps := a.PluginSource()
	cur := map[string]any{"id": ps.ID, "name": a.DisplayName(), "config": ps.Config, "group": ps.Group, "groupName": ps.GroupName,
		"aggregateDefault": ps.AggregateDefault, "unavailableReason": ps.Unavailable, "icon": ps.Icon, "disablePrefetch": ps.DisablePrefetch}
	for k, v := range patch {
		cur[k] = v
	}
	cur["id"] = id
	d, err := draftOf(cur)
	if err != nil {
		return err
	}
	a.Name = d.Name
	putSource(pluginID, ps.ServerType, d)
	return save()
}

// removeSource 删源:账号行去掉;它的观看记录与收藏在各自的存储里,原样保留(D230 D333)。
func removeSource(pluginID, id string) error {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	config.Current().Remove(string(source.PluginKind(pluginID, id)))
	return save()
}

func save() error {
	if err := config.Current().Save(); err != nil {
		return err
	}
	account.SyncImageAllowlist()
	bus.Emit("account.changed", map[string]any{}, "account.changed")
	return nil
}

// ---------------------------------------------------------------- 调插件

// resolve 开放键 → 账号 + 插件 id。
func resolve(serverID string) (*config.Account, string, error) {
	a := config.Current().Find(serverID)
	if a == nil {
		return nil, "", bus.NewErr(bus.ENotFound, "没有这个数据源:"+serverID)
	}
	ps := a.PluginSource()
	if ps == nil {
		return nil, "", bus.NewErr(bus.EInvalid, "不是插件数据源:"+serverID)
	}
	if ps.Unavailable != "" {
		return nil, "", bus.NewErr(bus.EUnsupported, ps.Unavailable)
	}
	return a, ps.Plugin, nil
}

// callVerb 调数据源动词(30 秒预算,D53),把插件错误映射成 bus.Err(附录 20.2)。
func callVerb(ctx context.Context, serverID, verb string, args ...any) (json.RawMessage, error) {
	a, pid, err := resolve(serverID)
	if err != nil {
		return nil, err
	}
	cctx := map[string]any{"source": infoOf(*a)}
	out, err := plugin.Default().Call(ctx, pid, rt.BudgetData, "dataSource."+verb, args, cctx)
	if err != nil {
		return nil, MapError(err)
	}
	return out, nil
}

// MapError 插件错误 → bus.Err(附录 20.2)。原 kind 与动作参数放进 Detail(JSON),UI 据此出按钮(D323)。
func MapError(err error) error {
	pe, ok := err.(*rt.Error)
	if !ok {
		if be, ok := err.(*bus.Err); ok {
			return be
		}
		return &bus.Err{Code: bus.EInternal, Msg: err.Error()}
	}
	code := map[string]string{
		rt.KindRateLimited: bus.EUpstream, rt.KindNeedVerify: bus.EUpstream, rt.KindNeedLogin: bus.EAuth,
		rt.KindSiteDown: bus.ENetwork, rt.KindNotFound: bus.ENotFound, rt.KindParseFailed: bus.EUpstream,
		rt.KindTimeout: bus.ENetwork, rt.KindNetwork: bus.ENetwork, rt.KindUnsupported: bus.EUnsupported,
		rt.KindPermission: bus.EPermission, rt.KindInvalid: bus.EInvalid, rt.KindInternal: bus.EInternal,
	}[pe.Kind]
	if code == "" {
		code = bus.EInternal
	}
	d, _ := json.Marshal(pe)
	msg := pe.Message
	if msg == "" {
		msg = "数据源出错"
	}
	return &bus.Err{Code: code, Msg: msg, Detail: string(d), Retryable: pe.Kind == rt.KindRateLimited || pe.Kind == rt.KindSiteDown || pe.Kind == rt.KindNetwork || pe.Kind == rt.KindTimeout}
}

// markFailed 首页请求失败时服务器列表标红「上次访问失败」(D122 D384);成功清掉。
func markFailed(serverID string, failed bool) {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	a := config.Current().Find(serverID)
	if a == nil {
		return
	}
	ps := a.PluginSource()
	if ps == nil || ps.LastFailed == failed {
		return
	}
	ps.LastFailed = failed
	a.SetPluginSource(*ps)
	_ = config.Current().Save()
}

func withTimeout(ctx context.Context, d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(ctx, d)
}
