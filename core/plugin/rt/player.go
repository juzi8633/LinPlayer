package rt

// `player` 命名空间(SPEC 9,D32 D33 D95 D106 D161 D299 D301 D302 D487 D488 D538)。
//
// 三件事在**这一层**做,不能推给壳或插件:
//   · 脱敏(9.1 的清单):带 token 的地址、请求头、播放列表路径读出来必须是脱敏值,
//     否则 D11「插件拿不到 Emby 凭据」被一条 player.get('path') 绕开;
//   · 记账(9.2):插件改过的每个属性记「改之前的值」,停用时还原;
//   · 限频(D161):time-pos 这类高频属性默认 4Hz、上限 10Hz —— 不限的话
//     一个订阅就能把事件循环占满,而表现是「插件一装播放就卡」。

import (
	"context"
	"strings"
	"sync"

	"github.com/dop251/goja"
)

// ---------------------------------------------------------------- 脱敏(SPEC 9.1,E3)

// redactedProps 读出来是脱敏值的 mpv 属性。
//
// ☠ 这张表是 D11 的最后一道门:`path` / `stream-open-filename` 里带着 api_key,
//
//	`http-header-fields` 里带着 Authorization。少一条就等于把 Emby 凭据交出去,
//	而调用方看到的是一条完全正常的属性读。
var redactedProps = map[string]bool{
	"path":                 true,
	"stream-open-filename": true,
	"stream-path":          true,
	"http-header-fields":   true,
	"working-directory":    true,
	"playlist":             true,
	"playlist-path":        true,
	"file-local-options":   true,
	"referrer":             true,
	"user-agent":           true,
	"cookies-file":         true,
	"sub-file-paths":       true,
	"screenshot-directory": true,
	"input-ipc-server":     true,
	"log-file":             true,
	"config-dir":           true,
}

// bannedCommands 对插件不开放的 mpv 命令(SPEC 9.1)。
//
// 换片由宿主做(player.play / playUrl):放开 loadfile 等于让插件自己拼一条
// 带 token 的地址去播,记账、进度、历史全都绕过去了。
var bannedCommands = map[string]bool{
	"loadfile": true, "loadlist": true, "playlist-play-index": true,
	"playlist-next": true, "playlist-prev": true, "playlist-remove": true,
	"playlist-clear": true, "playlist-move": true, "playlist-shuffle": true,
	"playlist-unshuffle": true, "run": true, "subprocess": true,
	"quit": true, "quit-watch-later": true, "load-config-file": true,
	"load-script": true, "dump-cache": true,
}

// RedactedProps / BannedCommands 给门禁测试用 —— 清单藏在包里的话,
// 「有没有人偷偷从表里删了一行」没有判据。
func RedactedProps() []string  { return keysOf(redactedProps) }
func BannedCommands() []string { return keysOf(bannedCommands) }

func keysOf(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

// PropRedacted 这个属性读出来要不要脱敏。前缀匹配管住 `playlist/0/filename` 这一类。
func PropRedacted(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	if redactedProps[n] {
		return true
	}
	return strings.HasPrefix(n, "playlist/")
}

// CommandBanned 这条 mpv 命令对插件开不开放。
func CommandBanned(name string) bool { return bannedCommands[strings.ToLower(strings.TrimSpace(name))] }

// redactedValue 脱敏后给插件看的值。给一个**看得出是脱敏**的占位,
// 不是空串 —— 空串会让插件以为「这一项没有」而去走别的分支。
const redactedValue = "<已脱敏:插件拿不到带凭据的地址(D11)>"

// ---------------------------------------------------------------- 记账(SPEC 9.2)

// propLedger 一个运行时改过的属性:属性名 → 它**第一次改之前**的值。
//
// 记第一次改之前的那个值,不是最近一次:D302 要的是「还原到这个插件介入之前」,
// 中间被别人改过也照样还原(已知会冲掉用户后来的手动调整,那是决定里写明的代价)。
type propLedger struct {
	mu   sync.Mutex
	prev map[string]any
}

func (l *propLedger) note(name string, before any) {
	l.mu.Lock()
	if l.prev == nil {
		l.prev = map[string]any{}
	}
	if _, seen := l.prev[name]; !seen {
		l.prev[name] = before
	}
	l.mu.Unlock()
}

// entries 取走全部记账项。还原是一次性的,取走之后账本清空。
func (l *propLedger) entries() map[string]any {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := l.prev
	l.prev = nil
	return out
}

// RestoreProps 插件停用 / 卸载时把它改过的属性还原(SPEC 9.2 D302)。
func (r *Runtime) RestoreProps() {
	h := r.opt.Host.Player
	if h == nil || h.Set == nil {
		return
	}
	for name, before := range r.props.entries() {
		if err := h.Set(name, before); err != nil {
			r.logs.add(LogEntry{TS: nowMS(), Level: "warn", Msg: "还原属性 " + name + " 失败: " + err.Error()})
		}
	}
}

// ---------------------------------------------------------------- 宿主回调

// PlayerHooks `player` 命名空间背后的宿主实现(core/plugin 提供)。
type PlayerHooks struct {
	Get     func(prop string) (any, error)
	Set     func(prop string, v any) error
	Command func(name string, args []any) (any, error)
	// Observe 订阅一个属性。返回退订函数;cb 由宿主按 hz 限频后调用。
	Observe func(prop string, hz float64, cb func(any)) (func(), error)
	State   func() any
	Tracks  func() any

	Play            func(item any, opts map[string]any) error
	PlayURL         func(url string, headers map[string]any, meta map[string]any) error
	Screenshot      func() ([]byte, error)
	GetSubtitleText func(trackID int) (any, error)
	AddSubtitle     func(content any, opts map[string]any) (int, error)
	// Transcribe ctx 是插件的生命周期:插件被停用时正在跑的子进程要跟着停。
	Transcribe    func(ctx context.Context, opts map[string]any, onProgress func(float64)) (any, error)
	ExtractFrames func(item any, times []float64, opts map[string]any) ([][]byte, error)
}

// ---------------------------------------------------------------- 安装

// 限频档位(D161):默认 4Hz,上限 10Hz。0 / 负数 = 用默认。
const (
	observeDefaultHz = 4.0
	observeMaxHz     = 10.0
)

func clampHz(v float64) float64 {
	if v <= 0 {
		return observeDefaultHz
	}
	if v > observeMaxHz {
		return observeMaxHz
	}
	return v
}

func (r *Runtime) installPlayer(sdk *goja.Object) {
	h := r.opt.Host.Player
	vm := r.vm
	o := vm.NewObject()
	_ = sdk.Set("player", o)

	need := func(fn any, what string) error {
		if h == nil || fn == nil {
			return &Error{Kind: KindUnsupported, Message: "这一版宿主没有提供 " + what}
		}
		return nil
	}

	_ = o.Set("get", func(prop string) goja.Value {
		return r.async(func() (any, error) {
			if err := need(h.Get, "player.get"); err != nil {
				return nil, err
			}
			if PropRedacted(prop) {
				return redactedValue, nil
			}
			return h.Get(prop)
		})
	})

	_ = o.Set("set", func(c goja.FunctionCall) goja.Value {
		prop := c.Argument(0).String()
		v := exportJSON(r, c.Argument(1))
		return r.async(func() (any, error) {
			if err := need(h.Set, "player.set"); err != nil {
				return nil, err
			}
			// 先读旧值再写:记账要的是「这个插件介入之前长什么样」。
			// 读不到就记 nil —— 还原时按「没设过」处理,总好过不记
			if h.Get != nil {
				before, _ := h.Get(prop)
				r.props.note(prop, before)
			} else {
				r.props.note(prop, nil)
			}
			return nil, h.Set(prop, v)
		})
	})

	_ = o.Set("command", func(c goja.FunctionCall) goja.Value {
		name := c.Argument(0).String()
		args := make([]any, 0, len(c.Arguments))
		for _, a := range c.Arguments[1:] {
			args = append(args, exportJSON(r, a))
		}
		return r.async(func() (any, error) {
			if CommandBanned(name) {
				return nil, &Error{Kind: KindPermission,
					Message: "mpv 命令 " + name + " 对插件不开放:换片走 player.play / player.playUrl(SPEC 9.1)"}
			}
			/* ☠ 改属性的那几条命令要**走 set 的那条路**,不能从 command 溜过去。
			   mpv 的 `set` / `cycle` / `add` / `multiply` 能改任意属性 ——
			   上一版这里不记账也不看脱敏清单,于是插件用 command('set','sub-scale',…)
			   改的东西停用时不还原(D302 白写),而 `cycle pause` 这种连改了什么都不知道。 */
			if isPropWrite(name) {
				if len(args) == 0 {
					return nil, &Error{Kind: KindInvalid, Message: "mpv 命令 " + name + " 要指明改哪个属性"}
				}
				prop, _ := args[0].(string)
				if err := r.notePropWrite(h, prop); err != nil {
					return nil, err
				}
			}
			if err := need(h.Command, "player.command"); err != nil {
				return nil, err
			}
			return h.Command(name, args)
		})
	})

	_ = o.Set("observe", func(c goja.FunctionCall) goja.Value {
		prop := c.Argument(0).String()
		cb, _ := goja.AssertFunction(c.Argument(1))
		hz := observeDefaultHz
		if m, ok := exportJSON(r, c.Argument(2)).(map[string]any); ok {
			if v, ok := m["hz"].(float64); ok {
				hz = v
			}
		}
		if h == nil || h.Observe == nil || cb == nil {
			r.throw(KindUnsupported, "这一版宿主没有提供 player.observe")
		}
		redacted := PropRedacted(prop)
		stop, err := h.Observe(prop, clampHz(hz), func(v any) {
			if redacted {
				v = redactedValue
			}
			// 回调投进事件循环:直接调会在 mpv 的事件线程上跑插件代码
			r.post(func() { _, _ = cb(goja.Undefined(), r.jsValue(v)) })
		})
		if err != nil {
			r.throwErr(err)
		}
		r.disposes = append(r.disposes, stop)
		d := vm.NewObject()
		_ = d.Set("dispose", stop)
		return d
	})

	// state / tracks 是同步的(.d.ts 里不带 Promise):它们读的是宿主内存里的快照
	_ = o.Set("state", func() goja.Value {
		if h == nil || h.State == nil {
			return goja.Null()
		}
		return r.jsValue(h.State())
	})
	_ = o.Set("tracks", func() goja.Value {
		if h == nil || h.Tracks == nil {
			return r.jsValue(map[string]any{"audio": []any{}, "subtitle": []any{}, "video": []any{}})
		}
		return r.jsValue(h.Tracks())
	})

	_ = o.Set("play", func(c goja.FunctionCall) goja.Value {
		item := exportJSON(r, c.Argument(0))
		opts, _ := exportJSON(r, c.Argument(1)).(map[string]any)
		return r.async(func() (any, error) {
			if err := need(h.Play, "player.play"); err != nil {
				return nil, err
			}
			return nil, h.Play(item, opts)
		})
	})

	_ = o.Set("playUrl", func(c goja.FunctionCall) goja.Value {
		u := c.Argument(0).String()
		headers, _ := exportJSON(r, c.Argument(1)).(map[string]any)
		meta, _ := exportJSON(r, c.Argument(2)).(map[string]any)
		return r.async(func() (any, error) {
			if err := need(h.PlayURL, "player.playUrl"); err != nil {
				return nil, err
			}
			return nil, h.PlayURL(u, headers, meta)
		})
	})

	_ = o.Set("screenshot", func() goja.Value {
		return r.async(func() (any, error) {
			if err := need(h.Screenshot, "player.screenshot"); err != nil {
				return nil, err
			}
			return h.Screenshot()
		})
	})

	/* 这两件是**壳的活**:打开官方子面板、显隐 OSD 都在界面那一层。
	   上一版留了两个宿主钩子,而 core/plugin 一个都没填 —— 插件调了没反应。 */
	_ = o.Set("openPanel", func(panel string) {
		r.shellTell("player.openPanel", map[string]any{"panel": panel})
	})
	_ = o.Set("setOsdVisible", func(visible bool) {
		r.shellTell("player.setOsdVisible", map[string]any{"visible": visible})
	})

	_ = o.Set("getSubtitleText", func(c goja.FunctionCall) goja.Value {
		id := -1
		if v := c.Argument(0); !absent(v) {
			id = int(v.ToInteger())
		}
		return r.async(func() (any, error) {
			if err := need(h.GetSubtitleText, "player.getSubtitleText"); err != nil {
				return nil, err
			}
			return h.GetSubtitleText(id)
		})
	})

	_ = o.Set("addSubtitle", func(c goja.FunctionCall) goja.Value {
		content := exportJSON(r, c.Argument(0))
		if b, ok := c.Argument(0).Export().(goja.ArrayBuffer); ok {
			content = b.Bytes()
		}
		opts, _ := exportJSON(r, c.Argument(1)).(map[string]any)
		return r.async(func() (any, error) {
			if err := need(h.AddSubtitle, "player.addSubtitle"); err != nil {
				return nil, err
			}
			return h.AddSubtitle(content, opts)
		})
	})

	_ = o.Set("transcribe", func(c goja.FunctionCall) goja.Value {
		opts, _ := exportJSON(r, c.Argument(0)).(map[string]any)
		var onProgress func(float64)
		if opts != nil {
			if fn, ok := goja.AssertFunction(c.Argument(0).ToObject(vm).Get("onProgress")); ok {
				onProgress = func(p float64) { r.post(func() { _, _ = fn(goja.Undefined(), vm.ToValue(p)) }) }
			}
			delete(opts, "onProgress")
		}
		return r.async(func() (any, error) {
			if err := need(h.Transcribe, "player.transcribe"); err != nil {
				return nil, err
			}
			return h.Transcribe(r.ctx, opts, onProgress)
		})
	})

	/* 按键(D563)。壳按下键时问一次核心层,回调回 true 就不再按默认处理。
	   只在这个插件有可见的播放器面板/覆盖层时问得到 —— 判可见在壳那边,
	   因为「哪一层在最上面」只有壳知道。 */
	_ = o.Set("onKey", func(cb goja.Callable) goja.Value {
		stop := r.addSlot(SlotKey, cb)
		r.disposes = append(r.disposes, stop)
		d := vm.NewObject()
		_ = d.Set("dispose", stop)
		return d
	})

	_ = o.Set("extractFrames", func(c goja.FunctionCall) goja.Value {
		item := exportJSON(r, c.Argument(0))
		var times []float64
		if arr, ok := exportJSON(r, c.Argument(1)).([]any); ok {
			for _, v := range arr {
				if f, ok := v.(float64); ok {
					times = append(times, f)
				}
			}
		}
		opts, _ := exportJSON(r, c.Argument(2)).(map[string]any)
		return r.async(func() (any, error) {
			if err := need(h.ExtractFrames, "player.extractFrames"); err != nil {
				return nil, err
			}
			bs, err := h.ExtractFrames(item, times, opts)
			if err != nil {
				return nil, err
			}
			// 每一帧是一段 PNG 字节;转成 ArrayBuffer 要在 JS 线程上做
			return func() goja.Value {
				out := make([]any, 0, len(bs))
				for _, b := range bs {
					out = append(out, vm.NewArrayBuffer(b))
				}
				return vm.ToValue(out)
			}, nil
		})
	})
}

// throwErr 把 Go 错误原样抛成 PluginError。
func (r *Runtime) throwErr(err error) {
	if e, ok := err.(*Error); ok {
		r.throw(e.Kind, e.Message)
	}
	r.throw(KindInternal, err.Error())
}

// propWriteCommands 会改属性的 mpv 命令。第一个参数是属性名。
var propWriteCommands = map[string]bool{
	"set": true, "cycle": true, "add": true, "multiply": true,
	"cycle-values": true, "change-list": true,
}

func isPropWrite(name string) bool { return propWriteCommands[strings.ToLower(strings.TrimSpace(name))] }

// notePropWrite 走一遍 player.set 的规矩:脱敏清单上的不许改,改了的记账。
func (r *Runtime) notePropWrite(h *PlayerHooks, prop string) error {
	if prop == "" {
		return &Error{Kind: KindInvalid, Message: "没给属性名"}
	}
	if PropRedacted(prop) {
		return &Error{Kind: KindPermission, Message: "属性 " + prop + " 对插件只读(SPEC 9.1)"}
	}
	if h != nil && h.Get != nil {
		before, _ := h.Get(prop)
		r.props.note(prop, before)
	} else {
		r.props.note(prop, nil)
	}
	return nil
}
