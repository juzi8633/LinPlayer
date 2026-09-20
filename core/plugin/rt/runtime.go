// Package rt 是插件运行时:每插件一个 goja + 一个事件循环(SPEC 2.3)。
//
// 宿主对插件的一切调用都投进循环串行执行;耗时宿主 API 返回 Promise,
// 在 Go 侧另起 goroutine 干活,完成后把 resolve 投回循环。
package rt

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dlclark/regexp2/v2"
	"github.com/dop251/goja"
)

// 预算(SPEC 2.3.1 / 附录 20.1)。计的是 JS 连续同步执行时间,等网络、等定时器不计。
const (
	BudgetUI   = time.Second
	BudgetData = 30 * time.Second
	BudgetHook = 300 * time.Millisecond
	// budgetIdle 不属于任何调用的任务(定时器回调、事件回调)单次上限。
	// ponytail: 没有调用可记账时按数据源预算封顶,死循环的定时器也会在 30 秒后被打断。
	budgetIdle = BudgetData
)

func init() {
	// goja 遇到回溯类正则会落到 regexp2,而它默认不超时、匹配中途也不看中断标志:
	// 一条坏规则能把运行时永久卡死。实测与取舍见 docs/research/plugins-v2/09-goja-interrupt-spike.md。
	regexp2.DefaultMatchTimeout = time.Second
}

// Options 创建运行时的参数。
type Options struct {
	ID      string // 插件 id(作者/名字)
	Version string
	Dev     bool
	PkgDir  string // 包解压目录,assets 从这里读
	DataDir string // 插件私有根:data/ cache/ kv.json secrets
	LAN     bool   // manifest 声明了 lan
	Host    Host
	// SourceMap 打包时产的 `main.js.map`(SPEC 16.5 D81)。
	// 有它才能把报错栈里的 `main.js:1:2931` 换成作者写的 `src/panel.tsx:88:12`。
	SourceMap []byte
}

// Runtime 一个插件的主运行时。
type Runtime struct {
	opt   Options
	vm    *goja.Runtime
	tasks chan func()
	quit  chan struct{}
	done  chan struct{}
	dead  atomic.Bool

	mu    sync.Mutex
	calls map[*call]struct{}

	def *goja.Object // definePlugin 交来的对象

	ui     *goja.Object // uiruntime.js 的渲染器,第一次挂 surface 时才建
	uiSink UISink

	logs *ring[LogEntry]
	reqs *ring[RequestEntry]
	kv   *kvStore
	sec  *secretStore
	jars *jarSet

	timerSeq   int64
	timers     map[int64]*time.Timer
	bodies     sync.Map // 响应体句柄 id → *bodyHandle
	bodySeq    atomic.Int64
	subs       sync.Map // 子运行时
	transports sync.Map // transportKey → http.RoundTripper
	disposes   []func()

	// 设置项订阅(settings.onChange / useSetting)。宿主那边改完要叫醒插件,
	// 不然设置页改完插件页还是旧值,用户得退出重进。
	stats   statBox

	// props 这个插件改过的 mpv 属性(SPEC 9.2):停用时按它还原。
	props propLedger

	setMu   sync.Mutex
	setSubs map[string]map[int64]func()
	setSeq  int64
}

// call 一次宿主→插件调用在循环里的记账。
type call struct {
	budget time.Duration
	used   time.Duration
	p      *goja.Promise // 返回 Promise 时等它落定
	abort  func()        // 触发 CallContext.signal
	done   chan callResult
	fin    bool
}

type callResult struct {
	data json.RawMessage
	err  error
}

var errBudget = errors.New("budget")

// New 建运行时并启动事件循环。还没执行任何插件代码。
func New(opt Options) (*Runtime, error) {
	r := &Runtime{
		opt:    opt,
		vm:     goja.New(),
		tasks:  make(chan func(), 1024),
		quit:   make(chan struct{}),
		done:   make(chan struct{}),
		calls:  map[*call]struct{}{},
		logs:   newRing[LogEntry](500),
		reqs:   newRing[RequestEntry](200),
		timers: map[int64]*time.Timer{},
	}
	var err error
	if r.kv, err = openKV(opt.DataDir); err != nil {
		return nil, err
	}
	if r.sec, err = openSecrets(opt.DataDir); err != nil {
		return nil, err
	}
	r.jars = newJarSet(r.sec)
	r.vm.SetFieldNameMapper(goja.UncapFieldNameMapper())
	r.vm.SetMaxCallStackSize(2000)
	if err := r.installGlobals(); err != nil {
		return nil, err
	}
	go r.loop()
	return r, nil
}

// ID 插件 id。
func (r *Runtime) ID() string { return r.opt.ID }

// post 投一个任务进循环。运行时已关就丢弃。
func (r *Runtime) post(f func()) {
	if r.dead.Load() {
		return
	}
	select {
	case r.tasks <- f:
	case <-r.quit:
	}
}

func (r *Runtime) loop() {
	defer close(r.done)
	for {
		select {
		case f := <-r.tasks:
			r.runTask(f)
		case <-r.quit:
			return
		}
	}
}

// runTask 跑一个循环任务并计时。
//
// 这段同步执行的时长记到**所有**进行中调用的账上:单线程循环里分不清这段时间属于谁。
// ponytail: 多个并发调用各自 CPU 重时会互相多记,预算只会更早触发,不会漏;要精确就得给每个 Promise 链打标。
func (r *Runtime) runTask(f func()) {
	limit := budgetIdle
	r.mu.Lock()
	for c := range r.calls {
		if left := c.budget - c.used; left < limit {
			limit = left
		}
	}
	r.mu.Unlock()
	if limit < time.Millisecond {
		limit = time.Millisecond
	}
	fired := atomic.Bool{}
	t := time.AfterFunc(limit, func() {
		fired.Store(true)
		r.vm.Interrupt(errBudget)
	})
	start := time.Now()
	f()
	t.Stop()
	el := time.Since(start)
	// 计时器在 f 返回后、Stop 前触发时,中断标志还挂着,会误伤下一个任务。
	r.vm.ClearInterrupt()

	r.mu.Lock()
	var settled []*call
	for c := range r.calls {
		c.used += el
		if c.used >= c.budget {
			settled = append(settled, c)
		}
	}
	r.mu.Unlock()
	for _, c := range settled {
		r.finish(c, callResult{err: &Error{Kind: KindTimeout, Message: fmt.Sprintf("插件运行超时(超过 %v)", c.budget)}})
	}
	if fired.Load() && len(settled) == 0 {
		// 没有调用可记账的任务被打断(定时器里死循环),报给宿主计一次错。
		r.reportError(&Error{Kind: KindTimeout, Message: "插件后台代码运行超时,已打断"})
	}
	r.checkPromises()
}

// checkPromises 进行中调用的 Promise 落定了就交结果。
func (r *Runtime) checkPromises() {
	r.mu.Lock()
	var ready []*call
	for c := range r.calls {
		if c.p != nil && c.p.State() != goja.PromiseStatePending {
			ready = append(ready, c)
		}
	}
	r.mu.Unlock()
	for _, c := range ready {
		if c.p.State() == goja.PromiseStateFulfilled {
			r.finish(c, r.resultOf(c.p.Result()))
		} else {
			r.finish(c, callResult{err: r.toError(c.p.Result())})
		}
	}
}

func (r *Runtime) finish(c *call, res callResult) {
	r.mu.Lock()
	if c.fin {
		r.mu.Unlock()
		return
	}
	c.fin = true
	delete(r.calls, c)
	r.mu.Unlock()
	if c.abort != nil && res.err != nil {
		c.abort()
	}
	c.done <- res
}

// resultOf 在循环里把 JS 值转成 JSON。
func (r *Runtime) resultOf(v goja.Value) callResult {
	if v == nil || goja.IsUndefined(v) {
		return callResult{data: json.RawMessage("null")}
	}
	s, err := r.stringify(v)
	if err != nil {
		return callResult{err: &Error{Kind: KindInternal, Message: "插件返回值不能序列化", Detail: err.Error()}}
	}
	return callResult{data: json.RawMessage(s)}
}

func (r *Runtime) stringify(v goja.Value) (string, error) {
	fn, _ := goja.AssertFunction(r.vm.Get("JSON").ToObject(r.vm).Get("stringify"))
	out, err := fn(goja.Undefined(), v)
	if err != nil {
		return "", err
	}
	if goja.IsUndefined(out) {
		return "null", nil
	}
	return out.String(), nil
}

// Invoke 调插件定义对象上的一个方法(如 "dataSource.home"),等结果。
//
// args 逐个 JSON 进 JS;callCtx 是附在参数末尾的 CallContext(自动带 signal)。
// callCtx 为 nil 时不追加(activate 这类不带上下文的调用)。
func (r *Runtime) Invoke(ctx context.Context, budget time.Duration, path string, args []any, callCtx map[string]any) (json.RawMessage, error) {
	return r.run(ctx, budget, func(vm *goja.Runtime, c *call) (goja.Value, error) {
		fn, this := r.lookup(path)
		if fn == nil {
			return nil, &Error{Kind: KindUnsupported, Message: "插件没有实现 " + path}
		}
		vals := make([]goja.Value, 0, len(args)+1)
		for _, a := range args {
			v, err := r.fromGo(a)
			if err != nil {
				return nil, err
			}
			vals = append(vals, v)
		}
		if callCtx != nil {
			o, err := r.fromGo(callCtx)
			if err != nil {
				return nil, err
			}
			ctl := r.newAbortController()
			o.ToObject(vm).Set("signal", ctl.Get("signal"))
			c.abort = func() { r.post(func() { r.callMethod(ctl, "abort") }) }
			vals = append(vals, o)
		}
		return fn(this, vals...)
	})
}

// Has 定义对象上有没有这个路径的函数(没实现的动词入口不显示,D226)。
func (r *Runtime) Has(path string) bool {
	ch := make(chan bool, 1)
	r.post(func() {
		fn, _ := r.lookup(path)
		ch <- fn != nil
	})
	select {
	case ok := <-ch:
		return ok
	case <-r.done:
		return false
	}
}

// Eval 在主运行时执行一段脚本(加载 main.js 用)。
func (r *Runtime) Eval(ctx context.Context, budget time.Duration, name, code string) (json.RawMessage, error) {
	return r.run(ctx, budget, func(vm *goja.Runtime, c *call) (goja.Value, error) {
		prg, err := goja.Compile(name, code, false)
		if err != nil {
			return nil, &Error{Kind: KindInternal, Message: "插件脚本语法错误", Detail: err.Error()}
		}
		return vm.RunProgram(prg)
	})
}

// run 把一次调用投进循环并等它结束(同步结果或 Promise 落定)。
func (r *Runtime) run(ctx context.Context, budget time.Duration, f func(*goja.Runtime, *call) (goja.Value, error)) (json.RawMessage, error) {
	if r.dead.Load() {
		return nil, &Error{Kind: KindUnsupported, Message: "插件已停止运行"}
	}
	c := &call{budget: budget, done: make(chan callResult, 1)}
	r.mu.Lock()
	r.calls[c] = struct{}{}
	r.mu.Unlock()
	// 调用统计只在开发者模式记:ReadMemStats 会 stop-the-world,
	// 给每一次数据源调用都来一次是拿全进程的停顿换一个调试面板上的数字
	var t0 time.Time
	var heap0 int64
	if r.opt.Dev {
		t0, heap0 = time.Now(), heapNow()
		defer func() { r.stats.note(time.Since(t0), false, heapNow()-heap0) }()
	}
	r.post(func() {
		v, err := f(r.vm, c)
		if err != nil {
			// 被打断的也在这里结账:打断可能是别的调用超预算殃及的,不收尾它就永远等不到结果。
			r.finish(c, callResult{err: r.toError(err)})
			return
		}
		if p, ok := v.Export().(*goja.Promise); ok {
			r.mu.Lock()
			c.p = p
			r.mu.Unlock()
			return // checkPromises 收尾
		}
		r.finish(c, r.resultOf(v))
	})
	select {
	case res := <-c.done:
		if res.err != nil {
			var pe *Error
			if errors.As(res.err, &pe) && (pe.Kind == KindTimeout || pe.Kind == KindInternal) {
				r.reportError(pe)
			}
		}
		return res.data, res.err
	case <-ctx.Done():
		r.finish(c, callResult{err: ctx.Err()})
		return nil, &Error{Kind: KindTimeout, Message: "调用已取消", Detail: ctx.Err().Error()}
	case <-r.done:
		return nil, &Error{Kind: KindUnsupported, Message: "插件已停止运行"}
	}
}

// lookup 按 "a.b.c" 取定义对象上的函数。只能在循环里调。
func (r *Runtime) lookup(path string) (goja.Callable, goja.Value) {
	if r.def == nil {
		return nil, nil
	}
	var this goja.Value = r.def
	cur := goja.Value(r.def)
	for _, seg := range strings.Split(path, ".") {
		o, ok := cur.(*goja.Object)
		if !ok {
			return nil, nil
		}
		this = o
		cur = o.Get(seg)
		if cur == nil || goja.IsUndefined(cur) || goja.IsNull(cur) {
			return nil, nil
		}
	}
	fn, ok := goja.AssertFunction(cur)
	if !ok {
		return nil, nil
	}
	return fn, this
}

// fromGo Go 值 → 普通 JS 对象(走 JSON,避免 goja 的 Go 包装对象行为不同于普通对象)。
func (r *Runtime) fromGo(v any) (goja.Value, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, &Error{Kind: KindInvalid, Message: "参数不能序列化", Detail: err.Error()}
	}
	return r.parseJSON(string(b))
}

func (r *Runtime) parseJSON(s string) (goja.Value, error) {
	fn, _ := goja.AssertFunction(r.vm.Get("JSON").ToObject(r.vm).Get("parse"))
	return fn(goja.Undefined(), r.vm.ToValue(s))
}

func (r *Runtime) callMethod(o *goja.Object, name string, args ...goja.Value) (goja.Value, error) {
	fn, ok := goja.AssertFunction(o.Get(name))
	if !ok {
		return nil, fmt.Errorf("%s 不是函数", name)
	}
	return fn(o, args...)
}

// Close 停循环、清定时器、关子运行时、落盘 KV。幂等。
func (r *Runtime) Close() {
	if !r.dead.CompareAndSwap(false, true) {
		return
	}
	envUnsubscribe(r)
	// 还原它改过的 mpv 属性(SPEC 9.2 D302)。要赶在关循环之前 ——
	// 关了之后宿主回调还能调,但插件那边已经没人能收结果了
	r.RestoreProps()
	close(r.quit)
	// 循环可能正卡在插件的死循环里:不打断它,停用/卸载这个插件会把调用方一起挂住。
	r.vm.Interrupt("closed")
	<-r.done
	for _, t := range r.timers {
		t.Stop()
	}
	r.subs.Range(func(k, v any) bool {
		v.(*subRuntime).close()
		return true
	})
	for _, f := range r.disposes {
		f()
	}
	r.kv.flush()
	r.mu.Lock()
	pending := make([]*call, 0, len(r.calls))
	for c := range r.calls {
		pending = append(pending, c)
	}
	r.mu.Unlock()
	for _, c := range pending {
		r.finish(c, callResult{err: &Error{Kind: KindUnsupported, Message: "插件已停止运行"}})
	}
}

// Logs 最近 500 条日志(错误详情带出,D285)。
func (r *Runtime) Logs() []LogEntry { return r.logs.list() }

// Requests 最近 200 条网络请求摘要(D456)。
func (r *Runtime) Requests() []RequestEntry { return r.reqs.list() }

// SetCookies 壳里整页 WebView 过完验证后,把拿到的 Cookie 写进这个罐子(D60 D323)。
func (r *Runtime) SetCookies(jar, rawURL string, cookies map[string]string) error {
	u, err := parseHTTPURL(rawURL)
	if err != nil {
		return err
	}
	var cs []*http.Cookie
	for k, v := range cookies {
		cs = append(cs, &http.Cookie{Name: k, Value: v, Path: "/"})
	}
	r.jars.get(jar).SetCookies(u, cs)
	r.jars.persist(jar)
	return nil
}

// Storage 给宿主读写 KV(备份、调试面板)。
func (r *Runtime) Storage() *kvStore { return r.kv }

func (r *Runtime) reportError(e *Error) {
	/* 报错也要进日志环(SPEC 16.5 的「日志」那一块)。
	   ☠ 只走 OnError 的话,调试面板的日志里**一条错误都看不到** ——
	     那一栏本来就是给「刚才到底出了什么事」用的,而错误恰恰不在里面。
	     栈已经在 toError 里映射回 TS 行号了。 */
	msg := e.Message
	if e.Detail != "" {
		msg += "\n" + e.Detail
	}
	r.logs.add(LogEntry{TS: nowMS(), Level: "error", Msg: msg})
	if r.opt.Host.OnError != nil {
		r.opt.Host.OnError(e)
	}
}
