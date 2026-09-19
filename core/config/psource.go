package config

// 插件数据源在账号表里的形状(SPEC 8.1~8.2)。
//
// 数据源就是一台「服务器」:一个源一行账号,Server = 开放键 `plugin:作者/名字/源id`,
// 于是服务器列表、切换、排序、备份、跨设备搬家全部复用账号表那一套。
// 源的其余信息整块放 rest["psource"],和 rest["source"] 一样透传。

import (
	"encoding/json"
	"strings"
)

// PluginSource 一个插件数据源。
type PluginSource struct {
	Plugin     string          `json:"plugin"`               // 插件 id
	ID         string          `json:"id"`                   // 源 id(不含 /)
	Config     json.RawMessage `json:"config"`               // 插件为这个源存的任意配置(D325)
	ServerType string          `json:"serverType,omitempty"` // 由哪个服务器类型创建
	Group      string          `json:"group,omitempty"`      // 分组(一个订阅一组,D123 D346)
	GroupName  string          `json:"groupName,omitempty"`
	// AggregateDefault 「允许聚合」默认值:TVBox 跟随 searchable(D235)。用户改过的值在 rest["aggregate"]。
	AggregateDefault bool   `json:"aggregateDefault"`
	Unavailable      string `json:"unavailable,omitempty"` // 本设备不可用的原因(灰显,D351)
	DisablePrefetch  bool   `json:"disablePrefetch,omitempty"`
	HostOverride     string `json:"hostOverride,omitempty"` // 用户手改的 host(D176)
	Icon             string `json:"icon,omitempty"`
	// LastFailed 上次访问首页失败:服务器列表标红(D122 D384)。
	LastFailed bool `json:"lastFailed,omitempty"`
}

// IsPluginSource 是不是插件数据源。
func (a Account) IsPluginSource() bool { return strings.HasPrefix(a.SourceKind(), "plugin:") }

// PluginSource 取插件源信息;不是插件源返回 nil。
func (a Account) PluginSource() *PluginSource {
	if !a.IsPluginSource() {
		return nil
	}
	var ps PluginSource
	if raw := a.RestValue("psource"); raw != nil && json.Unmarshal(raw, &ps) == nil {
		return &ps
	}
	return nil
}

// SetPluginSource 写回插件源信息。
func (a *Account) SetPluginSource(ps PluginSource) { a.SetRestValue("psource", ps) }

// AllowAggregate 「允许聚合」:一个开关管聚合搜索、聚合视界、换源(D233 D235)。
// 用户改过就听用户的;没改过:Emby 开,插件源跟随创建时的默认,其它浏览型源关。
func (a Account) AllowAggregate() bool {
	if raw := a.RestValue("aggregate"); raw != nil {
		var v bool
		if json.Unmarshal(raw, &v) == nil {
			return v
		}
	}
	if !a.IsFileBrowse() {
		return true
	}
	if ps := a.PluginSource(); ps != nil {
		return ps.AggregateDefault
	}
	return false
}

// SetAllowAggregate 用户改「允许聚合」。
func (a *Account) SetAllowAggregate(v bool) { a.SetRestValue("aggregate", v) }

// UpsertPluginSource 加入或更新一个插件源,**不改当前活跃服务器**(订阅一次加几十个源,不能每加一个就切过去)。
// 更新时保留用户改过的东西:显示名、手改 host、允许聚合、备注图标。
func (c *AppConfig) UpsertPluginSource(key, name string, ps PluginSource) {
	for i := range c.AccountList {
		a := &c.AccountList[i]
		if a.Server != key {
			continue
		}
		if old := a.PluginSource(); old != nil {
			ps.HostOverride, ps.LastFailed = old.HostOverride, old.LastFailed
		}
		a.SetPluginSource(ps)
		return
	}
	a := Account{Server: key, Name: name}
	a.SetRestValue("source_kind", key)
	a.SetPluginSource(ps)
	c.AccountList = append(c.AccountList, a)
	if c.Active == nil {
		c.setActive(len(c.AccountList) - 1)
	}
}

// PluginSources 账号表里属于某个插件的源(pluginID 空 = 全部插件源)。
func (c *AppConfig) PluginSources(pluginID string) []Account {
	var out []Account
	for _, a := range c.AccountList {
		if ps := a.PluginSource(); ps != nil && (pluginID == "" || ps.Plugin == pluginID) {
			out = append(out, a)
		}
	}
	return out
}
