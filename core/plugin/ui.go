package plugin

// surface 管理(SPEC 7.2 7.3,协议见 api/ui-protocol.d.ts)。
//
// 壳 → 核心是命令 `plugin.ui.*`,核心 → 壳是事件 `plugin.ui` / `plugin.ui.surface`。
// 这一层只管三件事:surface 的账本、把命令转给对应插件的运行时、把 ops 按帧合批发出去。

import (
	"context"
	"encoding/json"
	"strconv"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/plugin/rt"
)

// frameWindow 一帧多长:16ms 内的全部变更合成一条事件(D318),壳一帧只重排一次。
const frameWindow = 16 * time.Millisecond

type surface struct {
	id     string
	plugin string
	kind   string
	target string

	mu      sync.Mutex
	pending []json.RawMessage
	timer   *time.Timer
	seq     int
	/* started = mount 已经把首帧取走了。在那之前**一条事件都不许发** ——
	   否则首帧会在壳拿到 surface id 之前当成事件飞出去,没人接。
	   ☠ 光把首帧塞进 mount 的返回值是不够的:16ms 的定时器可能先到,
	   把首帧发成事件、顺手清空 pending,于是 mount 回的是空数组。
	   模拟器上必中,本机上看运气 —— 2026-09-20 就是这么表现成「安卓永远白屏」的。 */
	started bool
}

type uiState struct {
	mu   sync.Mutex
	seq  int
	list map[string]*surface
}

var ui = uiState{list: map[string]*surface{}}

// registerUI 由 RegisterCommands 调。
//
// ★ 命令表是进程级的,而 Host 在测试里会被换掉(ResetForTest)——
//   所以只注册一次,handler 里现取 Default(),别把当时那个 h 闭进去。
var uiOnce sync.Once

func registerUI() {
	uiOnce.Do(registerUICommands)
}

func registerUICommands() {
	bus.Register("plugin.ui.mount", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		id, _ := a["plugin"].(string)
		target, _ := a["target"].(string)
		kind, _ := a["kind"].(string)
		if id == "" || target == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 plugin 或 target")
		}
		if kind == "" {
			kind = "block"
		}
		props, _ := a["props"].(map[string]any)

		h := Default()
		l, err := h.get(id, "ui")
		if err != nil {
			return nil, err
		}
		s := ui.add(id, kind, target)
		// sink 要在 mount 之前装好:首帧是 mount 同步产生的
		l.rt.SetUISink(rt.UISink{OnFrame: onFrame, OnState: onState})
		// 首帧就绪之前壳画官方骨架屏(D271),所以先把 loading 发出去
		bus.Emit("plugin.ui.surface", map[string]any{"surface": s.id, "state": "loading"}, "")
		if err := l.rt.UIMount(s.id, kind, target, props); err != nil {
			ui.drop(s.id)
			// 挂不上是插件的错,按插件出错记账(连错会自动禁用)
			h.noteError(id, err)
			return nil, bus.NewErr(bus.EInternal, "挂载失败: %v", err)
		}
		/* ☠ **首帧跟着返回值走,不走事件**。
		   首渲染是 UIMount 里同步完成的,而壳要拿到 surface id 之后才可能
		   订阅这个 id 的帧 —— 中间那一段里发出去的帧没人接,
		   表现是「永远停在骨架屏」:不报错、不崩、就是不出内容。 */
		return map[string]any{"surface": s.id, "frame": 1, "ops": s.take()}, nil
	})

	bus.Register("plugin.ui.unmount", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		s := ui.get(a)
		if s == nil {
			return nil, nil // 重复卸载是常态(壳的页面销毁路径可能走两次),不报错
		}
		if l, err := Default().get(s.plugin, "ui"); err == nil {
			_ = l.rt.UIUnmount(s.id)
		}
		ui.drop(s.id)
		return nil, nil
	})

	bus.Register("plugin.ui.event", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		s := ui.get(a)
		if s == nil {
			return nil, nil // surface 已经没了:壳上一次迟到的点击,丢掉就是
		}
		fn, ok := a["fn"].(float64)
		if !ok {
			return nil, bus.NewErr(bus.EInvalid, "缺少 fn")
		}
		args, _ := a["args"].([]any)
		h := Default()
		l, err := h.get(s.plugin, "ui")
		if err != nil {
			return nil, err
		}
		if err := l.rt.UIEvent(s.id, int(fn), args); err != nil {
			h.noteError(s.plugin, err)
			return nil, bus.NewErr(bus.EInternal, "%v", err)
		}
		return nil, nil
	})
}

func (u *uiState) add(plugin, kind, target string) *surface {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.seq++
	s := &surface{id: "s" + strconv.Itoa(u.seq), plugin: plugin, kind: kind, target: target}
	u.list[s.id] = s
	return s
}

func (u *uiState) byID(id string) *surface {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.list[id]
}

func (u *uiState) get(a map[string]any) *surface {
	id, _ := a["surface"].(string)
	if id == "" {
		return nil
	}
	return u.byID(id)
}

func (u *uiState) drop(id string) {
	u.mu.Lock()
	s := u.list[id]
	delete(u.list, id)
	u.mu.Unlock()
	if s == nil {
		return
	}
	s.mu.Lock()
	if s.timer != nil {
		s.timer.Stop()
		s.timer = nil
	}
	s.pending = nil
	s.mu.Unlock()
}

/*
onFrame 把渲染器吐的 ops 攒进当前这一帧。

★ 合批在**这一层**而不是 JS 里:JS 那边只知道「一次提交结束了」,
不知道壳一帧多长。渲染器已经把一次交互里的多次 setState 并成一次提交,
这里再把 16ms 内的多次提交并成一条事件 —— 两层合起来才是 D318 要的
「壳一帧只重排一次」。
*/
func onFrame(f rt.UIFrame) {
	s := ui.byID(f.Surface)
	if s == nil {
		return // 已卸载:这一帧没人要了
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pending = append(s.pending, f.Ops...)
	if !s.started || s.timer != nil {
		return
	}
	s.timer = time.AfterFunc(frameWindow, func() { s.emit() })
}

// take 把攒着的 ops 取走(首帧随 mount 的返回值走),并开闸让后续帧走事件。
func (s *surface) take() []json.RawMessage {
	s.mu.Lock()
	defer s.mu.Unlock()
	ops := s.pending
	s.pending = nil
	if s.timer != nil {
		s.timer.Stop()
		s.timer = nil
	}
	s.seq = 1
	s.started = true
	if ops == nil {
		return []json.RawMessage{}
	}
	return ops
}

func (s *surface) emit() {
	s.mu.Lock()
	ops := s.pending
	s.pending = nil
	s.timer = nil
	s.seq++
	seq := s.seq
	s.mu.Unlock()
	if len(ops) == 0 {
		return
	}
	bus.Emit("plugin.ui", map[string]any{"surface": s.id, "frame": seq, "ops": ops}, "")
}

func onState(st rt.UIState) {
	m := map[string]any{"surface": st.Surface, "state": st.State}
	if st.Message != "" {
		e := map[string]any{"message": st.Message}
		if s := ui.byID(st.Surface); s != nil {
			e["plugin"] = s.plugin
		}
		if st.Stack != "" {
			e["dev"] = map[string]any{"stack": st.Stack}
		}
		m["error"] = e
	}
	bus.Emit("plugin.ui.surface", m, "")
}

// uiReset 换 Host 时把 surface 账本清掉(ResetForTest 用)。
func uiReset() {
	ui.mu.Lock()
	list := ui.list
	ui.list = map[string]*surface{}
	ui.seq = 0
	ui.mu.Unlock()
	for id := range list {
		ui.drop(id)
	}
}

// UISurfaceCount 给测试与调试面板用:现在挂着几个 surface。泄漏了这个数会一路涨。
func UISurfaceCount() int {
	ui.mu.Lock()
	defer ui.mu.Unlock()
	return len(ui.list)
}

