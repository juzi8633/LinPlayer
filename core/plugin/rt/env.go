package rt

// 壳报上来的**运行环境**:主题与「减少动态效果」(SPEC 7.6 7.5,D89 D428)。
//
// 为什么不塞进 ShellCaps:能力是「这台机器能不能做某件事」,一辈子不变;
// 环境是「此刻长什么样」,用户切一次深浅色就变一次,每变一次所有挂着的
// surface 都要重渲染。两者的生命周期不一样,合成一个的下场是切主题要重报能力。

import (
	"strings"
	"sync"
)

// Env 壳报来的环境。tokens 是主题 token 名 → 值(颜色写成 #rrggbbaa,尺寸写成数字)。
type Env struct {
	ReducedMotion bool           `json:"reduced_motion"`
	ThemeMode     string         `json:"theme_mode"` // light / dark
	ThemeTokens   map[string]any `json:"theme_tokens"`
}

var (
	envMu    sync.RWMutex
	curEnv   = Env{ThemeMode: "dark", ThemeTokens: map[string]any{}}
	envSubs  = map[*Runtime]struct{}{}
	envSubMu sync.Mutex
)

// SetEnv 壳报环境(plugin.setEnv 命令),并推给所有活着的运行时。
func SetEnv(e Env) {
	if e.ThemeMode == "" {
		e.ThemeMode = "dark"
	}
	if e.ThemeTokens == nil {
		e.ThemeTokens = map[string]any{}
	}
	envMu.Lock()
	curEnv = e
	envMu.Unlock()

	envSubMu.Lock()
	rs := make([]*Runtime, 0, len(envSubs))
	for r := range envSubs {
		rs = append(rs, r)
	}
	envSubMu.Unlock()
	for _, r := range rs {
		_ = r.pushEnv(e)
	}
}

// CurrentEnv 当前环境。
func CurrentEnv() Env {
	envMu.RLock()
	defer envMu.RUnlock()
	return curEnv
}

func envSubscribe(r *Runtime) {
	envSubMu.Lock()
	envSubs[r] = struct{}{}
	envSubMu.Unlock()
}

func envUnsubscribe(r *Runtime) {
	envSubMu.Lock()
	delete(envSubs, r)
	envSubMu.Unlock()
}

func envPayload(e Env) map[string]any {
	return map[string]any{
		"reducedMotion": e.ReducedMotion,
		"theme":         map[string]any{"mode": e.ThemeMode, "tokens": e.ThemeTokens},
	}
}

// pushEnv 把环境交给渲染器,它再叫醒 useTheme / useReducedMotion 的订阅者。
func (r *Runtime) pushEnv(e Env) error {
	if r.ui == nil {
		return nil // 这个插件没装 UI(纯数据源),没人要这条
	}
	return r.uiCall(BudgetEvent, "env", envPayload(e))
}

// ---------------------------------------------------------------- 应用设置(D92 D93 D427)

// appSettingDenied 插件不可写的应用设置前缀(D93)。
//
// 账号 / 服务器 / 代理这三类一改就等于把凭据送出去 —— D11「插件拿不到 Emby 凭据」
// 会被一条 setSetting 绕开。按**前缀**判而不是逐键枚举:新加一个 `proxy_xxx` 的偏好
// 不需要有人记得回来补这张表,漏了的代价是默认放行。
var appSettingDenied = []string{
	"account", "server", "emby", "proxy", "cf_", "line", "token", "secret", "password",
	"calendar_unlock_order", // 付费凭据(D551):能写等于软锁形同虚设
}

// AppSettingWritable 这个键插件能不能写。
func AppSettingWritable(key string) bool {
	k := strings.ToLower(key)
	for _, p := range appSettingDenied {
		if strings.Contains(k, p) {
			return false
		}
	}
	return true
}

// ---------------------------------------------------------------- 设置变更通知

// watchSetting 订阅一个设置项的变化,返回退订函数。
func (r *Runtime) watchSetting(key string, cb func()) func() {
	r.setMu.Lock()
	defer r.setMu.Unlock()
	if r.setSubs == nil {
		r.setSubs = map[string]map[int64]func(){}
	}
	if r.setSubs[key] == nil {
		r.setSubs[key] = map[int64]func(){}
	}
	r.setSeq++
	id := r.setSeq
	r.setSubs[key][id] = cb
	return func() {
		r.setMu.Lock()
		delete(r.setSubs[key], id)
		r.setMu.Unlock()
	}
}

// notifySetting 这个设置项变了。回调投进事件循环跑 —— 直接调会在写设置的那条线程上
// 执行插件代码,而那条线程可能是壳的 UI 线程。
func (r *Runtime) notifySetting(key string) {
	r.setMu.Lock()
	subs := make([]func(), 0, len(r.setSubs[key]))
	for _, f := range r.setSubs[key] {
		subs = append(subs, f)
	}
	r.setMu.Unlock()
	for _, f := range subs {
		fn := f
		r.post(func() { fn() })
	}
}

// NotifySettingChanged 宿主(设置页 / plugin.setSetting 命令)改了设置项。
func (r *Runtime) NotifySettingChanged(key string) { r.notifySetting(key) }
