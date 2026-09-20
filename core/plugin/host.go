package plugin

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime/metrics"
	"sort"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/plugin/rt"
)

// 防崩阈值(附录 20.1)。
const (
	errWindow     = 10 * time.Minute // 连错统计窗口
	errLimit      = 5                // 窗口内连续报错次数
	crashLimit    = 2                // 连崩几次进安全模式
	stableAfter   = 30 * time.Second // 稳定运行多久清启动标记
	idleRecycle   = 5 * time.Minute  // lifetime: idle 的闲置回收
	slowStartMark = 500              // 启动耗时标黄线(ms,D437)
)

// Host 插件宿主(进程级单例,见 Default)。
type Host struct {
	mu       sync.Mutex
	st       stateFile
	loaded   map[string]*loaded
	loading  map[string]*sync.WaitGroup
	errs     map[string][]time.Time
	dev      map[string]*devPlugin
	platform string
	version  string
	safeMode bool
	started  bool
	boot     boot

	// SourceHost 数据源相关回调(core/sourcecmd 注入,避免导入环)。
	SourceHost func(pluginID string) rt.Host
	// OnDisabled 插件被卸下时通知(数据源从分派表摘掉等)。
	OnUnloaded []func(id string)
}

type loaded struct {
	rt      *rt.Runtime
	m       *Manifest
	dir     string
	loadMS  int64
	lastUse time.Time
	heap    int64 // 调用前后采样的堆增量累计(内存看门狗定位嫌疑用,D141)
}

var (
	defMu sync.Mutex
	def   *Host
)

// Default 进程级宿主。第一次调用时建(还没 Start)。
func Default() *Host {
	defMu.Lock()
	defer defMu.Unlock()
	if def == nil {
		def = newHost()
	}
	return def
}

// ResetForTest 换一个全新宿主(测试用)。
func ResetForTest() *Host {
	defMu.Lock()
	defer defMu.Unlock()
	if def != nil {
		def.Shutdown()
	}
	uiReset()
	def = newHost()
	return def
}

func newHost() *Host {
	return &Host{loaded: map[string]*loaded{}, loading: map[string]*sync.WaitGroup{}, errs: map[string][]time.Time{}, dev: map[string]*devPlugin{}}
}

// Start 启动:读状态 → 连崩判定 → 应用待重启的改动 → 并行加载 startup 插件。只做一次。
func (h *Host) Start(platform, version string) {
	h.mu.Lock()
	if h.started {
		h.mu.Unlock()
		return
	}
	h.started = true
	h.platform, h.version = platform, version
	legacy := removeLegacy()
	_ = readJSON(stateFilePath(), &h.st)
	_ = readJSON(bootFilePath(), &h.boot)
	if h.boot.Booting {
		h.boot.Crashes++
	} else {
		h.boot.Crashes = 0
	}
	suspect := h.boot.Last
	if h.boot.Crashes >= crashLimit {
		h.enterSafeMode(suspect)
	}
	h.applyPending()
	h.boot.Booting = true
	h.boot.Last = ""
	_ = writeJSON(bootFilePath(), h.boot)
	h.saveLocked()
	var startup []string
	if !h.safeMode {
		for _, r := range h.st.Plugins {
			if r.Enabled {
				if m, err := h.manifestOf(r); err == nil && m.Activation.Load == "startup" {
					startup = append(startup, r.ID)
				}
			}
		}
	}
	h.mu.Unlock()

	if h.safeMode {
		bus.Emit("plugin.safeMode", map[string]string{"suspect": suspect}, "")
	}
	if legacy {
		bus.Emit("plugin.legacyRemoved", map[string]any{}, "")
	}
	// 启动即加载的插件并行加载,首屏不等(D439)
	for _, id := range startup {
		go func(id string) { _, _ = h.get(id, "startup") }(id)
	}
	time.AfterFunc(stableAfter, h.markStable)
	go h.watchdog()
}

// removeLegacy 旧插件系统留在磁盘上的数据静默删掉(D324 SPEC 13.6)。
// 旧系统的 state.json 和新系统同名但格式不同:不先删,新宿主会把旧格式读成一份残缺的状态。
func removeLegacy() bool {
	old := filepath.Join(root(), "installed")
	if _, err := os.Stat(old); err != nil {
		return false
	}
	for _, p := range []string{old, filepath.Join(root(), "storage"), stateFilePath()} {
		_ = os.RemoveAll(p)
	}
	return true
}

// markStable 稳定运行 30 秒:清启动标记与连崩计数。
func (h *Host) markStable() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.boot = boot{}
	_ = writeJSON(bootFilePath(), h.boot)
}

// enterSafeMode 连崩 2 次:全部插件置为停用,弹一次提示(D12 D376)。调用方持锁。
func (h *Host) enterSafeMode(suspect string) {
	h.safeMode = true
	for _, r := range h.st.Plugins {
		if r.Enabled || r.Want {
			r.Enabled, r.Want = false, false
			r.AutoDisabled = "上次启动连续崩溃,已被安全模式关闭"
		}
	}
	h.st.SafeBanner = true
	h.st.SafeSuspect = suspect
	h.boot.Crashes = 0
}

// applyPending 把「待重启」的改动落实:卸载、换版本、启停。调用方持锁。
func (h *Host) applyPending() {
	kept := h.st.Plugins[:0]
	for _, r := range h.st.Plugins {
		if r.Uninstall {
			_ = os.RemoveAll(filepath.Join(DataDir(r.ID), "pkg"))
			if r.DeleteData {
				_ = os.RemoveAll(DataDir(r.ID))
				delete(h.st.Settings, r.ID)
			}
			rt.RegistryClearWriter(r.ID)
			continue
		}
		if r.Staged != "" {
			if r.Version != "" && r.Version != r.Staged {
				if r.Prev != "" && r.Prev != r.Staged {
					_ = os.RemoveAll(pkgDir(r.ID, r.Prev))
				}
				r.Prev = r.Version
			}
			r.Version, r.Staged = r.Staged, ""
			if r.StagedSource != "" {
				r.Source, r.StagedSource = r.StagedSource, ""
			}
		}
		r.PendingNew = false
		if !h.safeMode {
			r.Enabled = r.Want
		}
		kept = append(kept, r)
	}
	h.st.Plugins = kept
}

func (h *Host) saveLocked() { _ = writeJSON(stateFilePath(), h.st) }

func (h *Host) record(id string) *Record {
	for _, r := range h.st.Plugins {
		if r.ID == id {
			return r
		}
	}
	return nil
}

func (h *Host) manifestOf(r *Record) (*Manifest, error) {
	if d, ok := h.dev[r.ID]; ok {
		return d.m, nil
	}
	b, err := os.ReadFile(filepath.Join(pkgDir(r.ID, r.Version), "manifest.json"))
	if err != nil {
		return nil, err
	}
	return ParseManifest(b)
}

// SafeMode 本次是否在安全模式里,以及最可疑的插件。
func (h *Host) SafeMode() (active, banner bool, suspect string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.safeMode, h.st.SafeBanner, h.st.SafeSuspect
}

// ---------------------------------------------------------------- 加载

// ErrNotEnabled 插件没装或没启用。
var ErrNotEnabled = &rt.Error{Kind: rt.KindUnsupported, Message: "插件未安装或未启用"}

// get 取已加载的运行时;没加载就按 manifest 加载并 activate(D51 懒加载)。
func (h *Host) get(id, reason string) (*loaded, error) {
	h.mu.Lock()
	if l := h.loaded[id]; l != nil {
		l.lastUse = time.Now()
		h.mu.Unlock()
		return l, nil
	}
	if wg := h.loading[id]; wg != nil {
		h.mu.Unlock()
		wg.Wait()
		return h.get(id, reason)
	}
	r := h.record(id)
	d := h.dev[id]
	if d == nil && (r == nil || !r.Enabled || h.safeMode) {
		h.mu.Unlock()
		return nil, ErrNotEnabled
	}
	wg := &sync.WaitGroup{}
	wg.Add(1)
	h.loading[id] = wg
	var dir, ver string
	var m *Manifest
	var err error
	if d != nil {
		dir, m, ver = d.dir, d.m, d.m.Version
	} else {
		dir, ver = pkgDir(id, r.Version), r.Version
		m, err = h.manifestOf(r)
	}
	h.mu.Unlock()

	var l *loaded
	if err == nil {
		l, err = h.load(id, ver, dir, m, d != nil, reason)
	}
	h.mu.Lock()
	delete(h.loading, id)
	if err == nil {
		h.loaded[id] = l
	}
	h.mu.Unlock()
	wg.Done()
	if err != nil {
		h.noteError(id, err)
		return nil, err
	}
	return l, nil
}

func (h *Host) load(id, ver, dir string, m *Manifest, dev bool, reason string) (*loaded, error) {
	start := time.Now()
	host := rt.Host{}
	if h.SourceHost != nil {
		host = h.SourceHost(id)
	}
	host.OnError = func(e *rt.Error) { h.noteError(id, e) }
	host.SettingGet = func(k string) any { return h.settingValue(id, m, k) }
	host.SettingSet = func(k string, v any) error { return h.setSetting(id, m, k, v) }
	host.AppSettingGet = appSettingGet
	host.AppSettingSet = appSettingSet
	host.App = rt.AppInfo{Version: h.version, Platform: h.platform, FormFactor: formFactor(h.platform), Locale: "zh-CN", DevMode: DevModeOn()}
	host.Debug = h.debugHooks()
	host.Player = playerHooks()
	host.Servers = serversHooks()
	host.Ext = extHooks()
	host.SyncRequest = func(service, method, path string, body any) (any, error) {
		return bus.Invoke(context.Background(), "sync."+service+"Request",
			map[string]any{"method": method, "path": path, "body": body})
	}
	// sourcemap 跟着入口文件走(SPEC 16.5 D81):有它报错栈才映射得回 TS 行号。
	// 读不到不是错误 —— 手写 JS 的插件本来就没有 .map
	var smap []byte
	if dev {
		smap = h.dev[id].smap
	} else {
		smap, _ = os.ReadFile(filepath.Join(dir, filepath.FromSlash(m.Main)) + ".map")
	}
	r, err := rt.New(rt.Options{ID: id, Version: ver, Dev: dev, PkgDir: dir, DataDir: DataDir(id),
		LAN: m.LAN, Host: host, SourceMap: smap})
	if err != nil {
		return nil, err
	}
	var code []byte
	if dev {
		code = h.dev[id].code
	} else {
		code, err = os.ReadFile(filepath.Join(dir, filepath.FromSlash(m.Main)))
		if err != nil {
			r.Close()
			return nil, &rt.Error{Kind: rt.KindInternal, Message: "插件入口文件读不到", Detail: err.Error()}
		}
	}
	ctx := context.Background()
	if _, err := r.Eval(ctx, rt.BudgetData, m.Main, string(code)); err != nil {
		r.Close()
		return nil, err
	}
	if r.Has("activate") {
		act := map[string]any{"id": id, "version": ver, "dev": dev, "reason": reason, "subscriptions": []any{}}
		if _, err := r.Invoke(ctx, rt.BudgetData, "activate", []any{act}, nil); err != nil {
			r.Close()
			return nil, err
		}
	}
	return &loaded{rt: r, m: m, dir: dir, loadMS: time.Since(start).Milliseconds(), lastUse: time.Now()}, nil
}

func formFactor(platform string) string {
	switch platform {
	case "android":
		return "phone"
	case "android_tv":
		return "tv"
	}
	return "desktop"
}

// unload 卸下运行时(禁用、自动禁用、内存看门狗、闲置回收)。
func (h *Host) unload(id string) {
	h.mu.Lock()
	l := h.loaded[id]
	delete(h.loaded, id)
	h.mu.Unlock()
	if l == nil {
		return
	}
	if l.rt.Has("deactivate") {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_, _ = l.rt.Invoke(ctx, rt.BudgetUI, "deactivate", nil, nil)
		cancel()
	}
	l.rt.Close()
	for _, f := range h.OnUnloaded {
		f(id)
	}
}

// Shutdown 退出时卸下全部运行时(KV 落盘)。
func (h *Host) Shutdown() {
	h.mu.Lock()
	ids := make([]string, 0, len(h.loaded))
	for id := range h.loaded {
		ids = append(ids, id)
	}
	for _, d := range h.dev {
		d.stop()
	}
	h.mu.Unlock()
	for _, id := range ids {
		h.unload(id)
	}
}

// ---------------------------------------------------------------- 调用

// Call 调插件的一个实现,记连错、记「最后在跑的插件」。
func (h *Host) Call(ctx context.Context, id string, budget time.Duration, path string, args []any, callCtx map[string]any) (json.RawMessage, error) {
	l, err := h.get(id, "lazy")
	if err != nil {
		return nil, err
	}
	h.noteRunning(id)
	before := heapBytes()
	out, err := l.rt.Invoke(ctx, budget, path, args, callCtx)
	h.mu.Lock()
	l.heap += heapBytes() - before
	l.lastUse = time.Now()
	h.mu.Unlock()
	if err == nil {
		h.noteSuccess(id)
	}
	return out, err
}

// Has 插件实现了这个路径没有(没实现的动词入口不显示,D226)。插件没加载时会加载。
func (h *Host) Has(id, path string) bool {
	l, err := h.get(id, "lazy")
	if err != nil {
		return false
	}
	return l.rt.Has(path)
}

// noteRunning 记下「最后在跑的插件」,连崩时报嫌疑(D376)。只在换插件时写盘。
func (h *Host) noteRunning(id string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.boot.Booting && h.boot.Last != id {
		h.boot.Last = id
		_ = writeJSON(bootFilePath(), h.boot)
	}
}

func (h *Host) noteSuccess(id string) {
	h.mu.Lock()
	delete(h.errs, id) // 连错只数连续的,一次成功清零(SPEC 4.5)
	h.mu.Unlock()
}

// noteError 计一次报错;10 分钟内连续 5 次立即自动禁用(D53)。
// 只有超时与未捕获异常算「插件写坏了」;插件主动抛的类型化错误(站点挂了、没找到)是正常业务结果。
func (h *Host) noteError(id string, err error) {
	var pe *rt.Error
	if errors.As(err, &pe) && pe.Kind != rt.KindTimeout && pe.Kind != rt.KindInternal {
		return
	}
	now := time.Now()
	h.mu.Lock()
	list := append(h.errs[id], now)
	for len(list) > 0 && now.Sub(list[0]) > errWindow {
		list = list[1:]
	}
	h.errs[id] = list
	trip := len(list) >= errLimit
	var name string
	if trip {
		delete(h.errs, id)
		if r := h.record(id); r != nil {
			r.Enabled, r.Want = false, false
			r.AutoDisabled = fmt.Sprintf("10 分钟内连续出错 %d 次,已自动禁用(最后一次:%v)", errLimit, err)
			if m, e := h.manifestOf(r); e == nil {
				name = m.Name
			}
			h.saveLocked()
		}
		if d := h.dev[id]; d != nil {
			delete(h.dev, id)
			d.stop()
		}
	}
	h.mu.Unlock()
	if trip {
		h.unload(id)
		bus.Emit("plugin.autoDisabled", map[string]string{"id": id, "name": name, "reason": err.Error()}, "")
	}
}

// NoteHookTimeout 钩子超过 300ms 墙钟被跳过:计入连错(D281)。
func (h *Host) NoteHookTimeout(id string) {
	h.noteError(id, &rt.Error{Kind: rt.KindTimeout, Message: "钩子超过 300ms,已跳过"})
}

// ---------------------------------------------------------------- 内存看门狗(D141)

func heapBytes() int64 {
	s := []metrics.Sample{{Name: "/memory/classes/heap/objects:bytes"}}
	metrics.Read(s)
	if s[0].Value.Kind() != metrics.KindUint64 {
		return 0
	}
	return int64(s[0].Value.Uint64())
}

// heapLimit Go 堆水位:TV 512MB / 其余 1.5GB(待实测校准)。
func (h *Host) heapLimit() int64 {
	if h.platform == "android_tv" {
		return 512 << 20
	}
	return 1536 << 20
}

func (h *Host) watchdog() {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for range t.C {
		h.recycleIdle()
		if heapBytes() < h.heapLimit() {
			continue
		}
		h.mu.Lock()
		var worst string
		var max int64
		for id, l := range h.loaded {
			if l.heap > max {
				worst, max = id, l.heap
			}
		}
		var name string
		if l := h.loaded[worst]; l != nil {
			name = l.m.Name
		}
		h.mu.Unlock()
		if worst == "" {
			continue
		}
		h.unload(worst)
		h.noteError(worst, &rt.Error{Kind: rt.KindInternal, Message: "内存占用过高,已暂停"})
		bus.Emit("plugin.memoryKilled", map[string]string{"id": worst, "name": name}, "")
	}
}

// recycleIdle lifetime: idle 的插件 5 分钟没调用就销毁运行时(SPEC 4.5)。
func (h *Host) recycleIdle() {
	h.mu.Lock()
	var ids []string
	for id, l := range h.loaded {
		if l.m.Activation.Lifetime == "idle" && time.Since(l.lastUse) > idleRecycle {
			ids = append(ids, id)
		}
	}
	h.mu.Unlock()
	for _, id := range ids {
		h.unload(id)
	}
}

// ---------------------------------------------------------------- 查询

// Enabled 本次运行启用的插件 id(含开发版),按 id 排序。
func (h *Host) Enabled() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	seen := map[string]bool{}
	var out []string
	if !h.safeMode {
		for _, r := range h.st.Plugins {
			if r.Enabled {
				seen[r.ID] = true
				out = append(out, r.ID)
			}
		}
	}
	for id := range h.dev {
		if !seen[id] {
			out = append(out, id)
		}
	}
	sort.Strings(out)
	return out
}

// Manifest 已装(或开发版)插件的 manifest。
func (h *Host) Manifest(id string) (*Manifest, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if d := h.dev[id]; d != nil {
		return d.m, nil
	}
	r := h.record(id)
	if r == nil {
		return nil, ErrNotEnabled
	}
	return h.manifestOf(r)
}

// PkgDir 本次运行生效的包目录(开发版是源码目录);没装或没启用返回空。
func (h *Host) PkgDir(id string) string {
	h.mu.Lock()
	defer h.mu.Unlock()
	if d := h.dev[id]; d != nil {
		return d.dir
	}
	if r := h.record(id); r != nil && r.Enabled && !h.safeMode {
		return pkgDir(id, r.Version)
	}
	return ""
}

// Runtime 已加载的运行时(错误详情、调试用);没加载返回 nil。
func (h *Host) Runtime(id string) *rt.Runtime {
	h.mu.Lock()
	defer h.mu.Unlock()
	if l := h.loaded[id]; l != nil {
		return l.rt
	}
	return nil
}
