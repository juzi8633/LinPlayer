package rt

import (
	"errors"
	"sync"
	"time"

	"github.com/dop251/goja"

	"linplayer/core/bus"
)

// 错误类型,与 plugin-sdk.d.ts 的 PluginErrorKind 一一对应(附录 20.2)。
const (
	KindRateLimited = "rateLimited"
	KindNeedVerify  = "needVerify"
	KindNeedLogin   = "needLogin"
	KindSiteDown    = "siteDown"
	KindNotFound    = "notFound"
	KindParseFailed = "parseFailed"
	KindTimeout     = "timeout"
	KindNetwork     = "network"
	KindUnsupported = "unsupported"
	KindPermission  = "permission"
	KindInvalid     = "invalid"
	KindInternal    = "internal"
)

var knownKinds = map[string]bool{
	KindRateLimited: true, KindNeedVerify: true, KindNeedLogin: true, KindSiteDown: true,
	KindNotFound: true, KindParseFailed: true, KindTimeout: true, KindNetwork: true,
	KindUnsupported: true, KindPermission: true, KindInvalid: true, KindInternal: true,
}

// Error 插件抛出或宿主 API 拒绝的类型化错误。Message 是中文,用户原样看到。
type Error struct {
	Kind       string `json:"kind"`
	Message    string `json:"message"`
	RetryAfter int    `json:"retryAfter,omitempty"`
	VerifyURL  string `json:"verifyUrl,omitempty"`
	LoginPage  string `json:"loginPage,omitempty"`
	Detail     string `json:"detail,omitempty"`
}

func (e *Error) Error() string { return e.Kind + ": " + e.Message }

// toError 把 JS 抛出/拒绝的值或 goja 错误转成 *Error。只能在循环里调。
func (r *Runtime) toError(v any) error {
	var ie *goja.InterruptedError
	switch x := v.(type) {
	case *Error:
		return x
	case *goja.Exception:
		return r.toError(x.Value())
	case error:
		if errors.As(x, &ie) {
			return &Error{Kind: KindTimeout, Message: "插件运行超时,已打断"}
		}
		if e := fromBusErr(x); e != nil {
			return e
		}
		return &Error{Kind: KindInternal, Message: "插件内部错误", Detail: x.Error()}
	case goja.Value:
		e := valueToError(r.vm, x)
		// 栈里的位置全是 `main.js:1:2931`(整个插件打成一个文件),对作者毫无用处 ——
		// 换成他写的 `src/panel.tsx:88:12`(SPEC 16.5 D81)
		e.Detail = r.MapStack(e.Detail)
		return e
	}
	return &Error{Kind: KindInternal, Message: "插件内部错误"}
}

func valueToError(vm *goja.Runtime, v goja.Value) *Error {
	o, ok := v.(*goja.Object)
	if !ok || o == nil {
		return &Error{Kind: KindInternal, Message: "插件内部错误", Detail: v.String()}
	}
	get := func(k string) string {
		x := o.Get(k)
		if x == nil || goja.IsUndefined(x) || goja.IsNull(x) {
			return ""
		}
		return x.String()
	}
	kind := get("kind")
	if !knownKinds[kind] {
		// 未捕获的普通异常一律 internal(附录 20.2)
		return &Error{Kind: KindInternal, Message: "插件内部错误:" + get("message"), Detail: get("stack")}
	}
	e := &Error{Kind: kind, Message: get("message"), VerifyURL: get("verifyUrl"), LoginPage: get("loginPage"), Detail: get("detail")}
	if ra := o.Get("retryAfter"); ra != nil && !goja.IsUndefined(ra) {
		e.RetryAfter = int(ra.ToInteger())
	}
	return e
}

// throw 在宿主函数里抛一个类型化错误给 JS(new PluginError)。
func (r *Runtime) throw(kind, msg string) {
	panic(r.newPluginError(kind, msg))
}

func (r *Runtime) newPluginError(kind, msg string) *goja.Object {
	ctor := r.vm.Get("PluginError").ToObject(r.vm)
	init := r.vm.NewObject()
	init.Set("kind", kind)
	init.Set("message", msg)
	o, err := r.vm.New(ctor, init)
	if err != nil {
		panic(err)
	}
	return o
}

// Host 宿主给运行时的回调(core/plugin 提供)。字段可为 nil。
type Host struct {
	// OnError 计入连错(超时、未捕获异常)。
	OnError func(e *Error)
	// Sources* 实现 sources 命名空间(D132)。
	SourcesList   func() any
	SourcesAdd    func(draft map[string]any, serverType string) error
	SourcesUpdate func(id string, patch map[string]any) error
	SourcesRemove func(id string) error
	// Setting* 实现 settings 命名空间(D35 D267)。
	SettingGet func(key string) any
	SettingSet func(key string, v any) error
	// AppSetting* 实现 app.getSetting / app.setSetting(D92 D93 D427):**应用**设置,
	// 不是插件自己的设置项。可写性由 AppSettingWritable 判,拒绝的原因要能说给用户听。
	AppSettingGet func(key string) any
	AppSettingSet func(key string, v any) error
	// HostPort 宿主本地服务端口:访问它不算局域网(L1 例外)。
	HostPort func() int
	// AssetURL 包内资源在数据通道上的地址(assets.url)。
	AssetURL func(p string) string
	// App 给 app 命名空间的静态信息。
	App AppInfo
	// Debug 实现 debug 命名空间(SPEC 16.5,D80 D81)。只在开发者模式下非 nil。
	Debug *DebugHooks
	// Player 实现 player 命名空间(SPEC 9)。
	Player *PlayerHooks
	// SyncRequest 实现 trakt / bangumi 代发(SPEC 17.3 18.2):宿主带 token 发。
	SyncRequest func(service, method, path string, body any) (any, error)
	// Servers 实现 servers 命名空间(D92):只给 id 与名称。
	Servers *ServersHooks
	// Ext 实现 ext 命名空间(SPEC 18.5,D380~D382):大件原生运行物的装没装与下载。
	Ext *ExtHooks
	// Wallpaper 实现 wallpaper 命名空间(SPEC 11.5,D441~D443)。
	Wallpaper *WallpaperHooks
}

// DebugHooks 调试面板要的四块数据。整块为 nil = 这一版不挂 debug 命名空间。
type DebugHooks struct {
	Plugins    func() any
	Logs       func(pluginID string) any
	Requests   func(pluginID string) any
	Surfaces   func() any
	UITree     func(surfaceID string) (any, error)
	Storage    func(pluginID string) any
	SetStorage func(pluginID, key string, v any) error
	Stats      func(pluginID string) any
}

// AppInfo app 命名空间的只读字段。
type AppInfo struct {
	Version    string `json:"version"`
	Platform   string `json:"platform"`
	FormFactor string `json:"formFactor"`
	Locale     string `json:"locale"`
	DevMode    bool   `json:"devMode"`
}

// LogEntry 一条插件日志。
type LogEntry struct {
	TS    int64  `json:"ts"`
	Level string `json:"level"`
	Msg   string `json:"msg"`
}

// RequestEntry 一条网络请求摘要。
type RequestEntry struct {
	TS     int64  `json:"ts"`
	Method string `json:"method"`
	URL    string `json:"url"`
	Status int    `json:"status"`
	MS     int64  `json:"ms"`
	Err    string `json:"err,omitempty"`
}

// ring 定长环形缓冲。
type ring[T any] struct {
	mu  sync.Mutex
	buf []T
	n   int
	i   int
}

func newRing[T any](n int) *ring[T] { return &ring[T]{buf: make([]T, n), n: n} }

func (g *ring[T]) add(v T) {
	g.mu.Lock()
	g.buf[g.i%g.n] = v
	g.i++
	g.mu.Unlock()
}

func (g *ring[T]) list() []T {
	g.mu.Lock()
	defer g.mu.Unlock()
	cnt := min(g.i, g.n)
	out := make([]T, 0, cnt)
	for k := g.i - cnt; k < g.i; k++ {
		out = append(out, g.buf[k%g.n])
	}
	return out
}

func nowMS() int64 { return time.Now().UnixMilli() }

/*
fromBusErr 核心层命令的错误码 → 插件那边的 kind。

☠ 不转的话**所有**宿主命令失败在插件那头都是 `internal`:

	「还没连 Trakt」和「Trakt 服务器挂了」长得一模一样,插件只能都当成故障重试。
	少了这一层,`.d.ts` 那张 PluginErrorKind 表对宿主 API 就是摆设。
*/
func fromBusErr(err error) *Error {
	var be *bus.Err
	if !errors.As(err, &be) {
		return nil
	}
	kind := KindInternal
	switch be.Code {
	case bus.EAuth:
		kind = KindNeedLogin
	case bus.ENetwork:
		kind = KindNetwork
	case bus.EUpstream:
		kind = KindSiteDown
	case bus.EUnsupported, bus.EShutdown:
		kind = KindUnsupported
	case bus.ENotFound:
		kind = KindNotFound
	case bus.EPermission:
		kind = KindPermission
	case bus.EInvalid:
		kind = KindInvalid
	}
	return &Error{Kind: kind, Message: be.Msg, Detail: be.Detail}
}
