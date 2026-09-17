package player

// 补帧档位:命令 + 起播回挂 + 跑不起来就退回的闸。算法和运行时在 core/interp。
//
// 和画面增强是两件事、两套档位,但口径一致:全局一份 + 按剧一份;
// 挂不上 / 跑不动就**自己退回关闭并说清原因**,不许界面显示「已开」而画面没变。

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/interp"
)

// InterpLevel 补帧档位表里的一档。
type InterpLevel struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Group    string `json:"group"`
	Multi    int    `json:"multi"`
	Selected bool   `json:"selected"`
	// WillRun 片源 / 屏幕未知(没在播)时不发;false 时 Note 说为什么。
	WillRun *bool  `json:"will_run,omitempty"`
	Note    string `json:"note,omitempty"`
	// FPS 在播时的实际帧数,比如「24→48 帧」。没在播为空
	FPS string `json:"fps,omitempty"`
}

// InterpState 补帧面板要的全部东西。
type InterpState struct {
	// Supported false = 这台机器 / 这个平台没有补帧,Reason 是原因。UI 整段不画。
	Supported bool   `json:"supported"`
	Reason    string `json:"reason,omitempty"`
	GPU       string `json:"gpu,omitempty"`
	// Installed false 时档位表照发,UI 显示「下载组件」按钮。
	Installed     bool          `json:"installed"`
	Installing    bool          `json:"installing"`
	DownloadBytes int64         `json:"download_bytes"`
	Levels        []InterpLevel `json:"levels"`
	// Backend 现在补帧走哪条:"trt"(N 卡加速包 + 引擎都就绪)/ "dml" / 安卓为空
	Backend string `json:"backend,omitempty"`
	// TRT 只有 N 卡才给;A 卡 / I 卡为 null,界面上不提这件事
	TRT *InterpTRT `json:"trt,omitempty"`
}

// InterpTRT N 卡加速包的状态。
type InterpTRT struct {
	// Available false 时 Reason 说为什么(架构太老 / 驱动太旧)
	Available bool   `json:"available"`
	Reason    string `json:"reason,omitempty"`
	Ready     bool   `json:"ready"`
	Bytes     int64  `json:"bytes"`
	// Preparing 包下好了,正在预建引擎(一两分钟)
	Preparing bool `json:"preparing"`
}

// InterpApplied setInterpLevel 的回执。
//
// ★ 挂上 ≠ 在补:脚本要一两秒才起来,显卡跟不跟得上要播几秒才知道。
// 所以这里只回「挂上了」,之后跑不起来由 player.interpReverted 事件通知。
type InterpApplied struct {
	Level    string `json:"level"`
	Reverted bool   `json:"reverted,omitempty"`
	Note     string `json:"note,omitempty"`
}

var (
	curInterp atomic.Value // 本进程当前挂着的档位
	// interpGen 每挂一次加一。闸看到代数变了就收手 —— 用户连点两档时,
	// 上一档的闸不能把下一档撤掉。
	interpGen      atomic.Int64
	interpInstMu   sync.Mutex
	interpInstBusy atomic.Bool
	interpWarming  atomic.Bool
)

func interpLevelFor(p config.Prefs, scope string) string {
	return scopedLevel(p.InterpLevel, p.InterpBySeries, scope)
}

func currentInterpLevel() string {
	v, _ := curInterp.Load().(string)
	if v == "" {
		v = interpLevelFor(config.Current().PrefsOf(), currentScope())
	}
	if v == "" {
		return "off"
	}
	return v
}

// rememberInterp 用户自己挑的档才落盘。闸自动退回的那一路**不走这里** ——
// 一次跑不动(比如这一集是 60 帧)不该把他给这部剧记住的档位抹掉。
func rememberInterp(level string) {
	curInterp.Store(level)
	c := config.Current()
	p := c.PrefsOf()
	if !rememberScoped(&p.InterpLevel, &p.InterpBySeries, currentScope(), level) {
		return
	}
	if err := c.SetPrefs(p); err == nil {
		err = c.Save()
	} else {
		bus.Logf("warn", "补帧档位没记住: %v", err)
	}
}

// interpOff 撤掉滤镜。vf 会跨文件粘连,关的时候必须真的清掉。
func interpOff() {
	interpGen.Add(1)
	setProp("vf", "")
	setProp("hr-seek-framedrop", "yes")
	curInterp.Store("off")
}

// mountInterp 挂上一档,起闸。返回空串 = 挂上了;否则是挂不上的原因(已经撤干净)。
func mountInterp(level string) string {
	spec, err := interp.SpecOf(level)
	if err != nil {
		return err.Error()
	}
	if interp.NeedsRuntime && !interp.Installed() {
		return "补帧组件还没下载"
	}
	name, idx, err := interp.Device()
	if err != nil {
		return err.Error()
	}
	if err := interp.Preload(); err != nil {
		return err.Error()
	}
	script, err := interp.ScriptPath()
	if err != nil {
		return err.Error()
	}
	takeInterpErr()
	gen := interpGen.Add(1)
	// 不设成 no 的话 seek 之后画面和时间轴会错开一段(mpv_PlayKit #123 里的配置)
	setProp("hr-seek-framedrop", "no")
	if interp.TRTReady() {
		spec.Backend = "trt"
	}
	vf := interp.FilterString(script, spec, idx)
	setProp("vf", vf)
	curInterp.Store(level)
	bus.Logf("info", "补帧:挂上 %s(%s,设备 #%d %s)", level, vf, idx, name)
	go guardInterp(gen, level, spec)
	return ""
}

// ---------------------------------------------------------------- 闸

var (
	interpErrMu sync.Mutex
	interpErr   string
)

// noteInterpLog 由事件线程调用(只有 error 级日志会到这)。只认补帧滤镜自己的错:
// 模块名是 vapoursynth(PC)/ lpinterp(安卓),或者 mpv 撤滤镜时那句带着我们的标签 `@lpinterp`。
func noteInterpLog(prefix, text string) {
	p, t := strings.ToLower(prefix), strings.ToLower(text)
	if p != "vapoursynth" && !strings.HasPrefix(p, "lpinterp") && !strings.Contains(t, "lpinterp") {
		return
	}
	interpErrMu.Lock()
	if interpErr == "" {
		interpErr = strings.TrimSpace(text)
	}
	interpErrMu.Unlock()
}

func takeInterpErr() string {
	interpErrMu.Lock()
	defer interpErrMu.Unlock()
	e := interpErr
	interpErr = ""
	return e
}

// 闸的时间尺。var 是给测试压短用的。
var (
	interpTick = 500 * time.Millisecond
	// interpStartWindow 播放中累计这么久还没见到输出帧率翻上去,就判「没生效」。
	// 脚本冷启动(Python + onnxruntime 建会话)实测 2~3 秒。
	interpStartWindow = 15 * time.Second
	// interpDropWindow 起来之后量这么久的丢帧。
	interpDropWindow = 8 * time.Second
)

// interpMaxDropRatio 量窗里丢帧占应出帧数的比例上限。
// 实测跑得动的档 10 秒丢 0~7 帧(<2%),跑不动的丢 20%~70%,中间没有灰色地带。
const interpMaxDropRatio = 0.10

// interpEarlyDropRatio 量窗没满时提前撤的门槛,比 interpMaxDropRatio 宽:开头两秒样本少,别误伤。
const interpEarlyDropRatio = 0.25

// guardInterp 挂上之后盯着:脚本报错 / 这一片不该补 / 输出帧率没翻上去 / 丢帧太多 → 撤掉。
//
// 只数**在播**的时间:暂停时没有帧,不数的话暂停十几秒回来就被判成「没生效」。
func guardInterp(gen int64, level string, spec interp.Spec) {
	var playing time.Duration
	var dropBase float64
	var started bool
	var measured time.Duration
	var dc dropCounter
	for interpGen.Load() == gen {
		time.Sleep(interpTick)
		if interpGen.Load() != gen {
			return
		}
		if e := takeInterpErr(); e != "" {
			revertInterp(gen, level, "补帧脚本跑不起来,已退回关闭。mpv 的原话:"+firstLine(e))
			return
		}
		if prop("pause") == "yes" || prop("core-idle") == "yes" {
			continue
		}
		src := propF("container-fps")
		if src <= 0 {
			continue // 文件还没装好
		}
		if why := interp.WhyNot(spec, src, interp.DisplayHz()); why != "" {
			// 这一片不该补:撤掉但**不改记住的档位**,下一集照样挂回来
			revertInterp(gen, level, why+",这一集先不补帧")
			return
		}
		playing += interpTick
		out := propF("estimated-vf-fps")
		if !started {
			if out > src*(float64(spec.Multi)-0.5) {
				started = true
				dropBase = dc.read()
				bus.Logf("info", "补帧:%s 起来了,输出 %.2f 帧(源 %.3f),播放 %v 后", level, out, src, playing)
			} else if playing >= interpStartWindow {
				revertInterp(gen, level, fmt.Sprintf("补帧没有生效(播了 %.0f 秒输出仍是 %.1f 帧),已退回关闭", playing.Seconds(), out))
				return
			}
			continue
		}
		measured += interpTick
		lost := dc.read() - dropBase
		want := measured.Seconds() * src * float64(spec.Multi)
		/* 明显跑不动的不等满量窗:实测跑不动时每秒只出 8 帧上下(应出 96),
		   等 8 秒就是让用户看 8 秒幻灯片。2 秒后丢帧过四分之一就撤。 */
		early := measured >= 2*time.Second && lost > want*interpEarlyDropRatio
		if measured < interpDropWindow && !early {
			continue
		}
		bus.Logf("info", "补帧:%s 量了 %.0f 秒,丢 %.0f 帧 / 应出 %.0f 帧", level, measured.Seconds(), lost, want)
		if lost > want*interpMaxDropRatio {
			revertInterp(gen, level, fmt.Sprintf("显卡跟不上%s(%.0f 秒丢了 %.0f 帧),已退回关闭。可以试试低一档的倍数",
				interp.MultiName(spec.Multi), measured.Seconds(), lost))
		}
		return
	}
}

// dropCounter 累计丢帧数。mpv 的两个丢帧计数**在 seek 时清零**(实测跳转后 19 → 0),
// 直接相减会得出负数,量窗里正好有一次跳转就把真丢的帧抵掉了。
type dropCounter struct{ last, carry float64 }

func (c *dropCounter) read() float64 {
	return c.add(propF("frame-drop-count") + propF("decoder-frame-drop-count"))
}

func (c *dropCounter) add(now float64) float64 {
	if now < c.last {
		c.carry += c.last
	}
	c.last = now
	return c.carry + now
}

// revertInterp 闸撤档。代数不对说明用户已经换了档,什么都不做。
func revertInterp(gen int64, level, note string) {
	if !interpGen.CompareAndSwap(gen, gen+1) {
		return
	}
	setProp("vf", "")
	setProp("hr-seek-framedrop", "yes")
	curInterp.Store("off")
	bus.Logf("warn", "补帧:%s 撤掉 —— %s", level, note)
	bus.Emit("player.interpReverted", InterpApplied{Level: level, Reverted: true, Note: note}, "")
}

// applySavedInterp 起播时把记住的档挂回去。挂不上只进日志,不改记住的值。
func applySavedInterp(level string) {
	if level == "" || level == "off" {
		if v, _ := curInterp.Load().(string); v != "" && v != "off" {
			interpOff()
		}
		return
	}
	if why := mountInterp(level); why != "" {
		interpOff()
		bus.Logf("warn", "记住的补帧档位 %s 挂不上:%s", level, why)
	}
}

// ---------------------------------------------------------------- 命令

// noteDisplayHz 安卓 UI 随命令报屏幕刷新率(核心层在安卓上拿不到窗口系统)。Windows 不传。
func noteDisplayHz(a map[string]any) {
	if hz, ok := a["display_hz"].(float64); ok {
		interp.SetDisplayHz(hz)
	}
}

func registerInterp() {
	bus.Register("player.interpLevels", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		noteDisplayHz(a)
		st := InterpState{Supported: interp.Supported, Installed: !interp.NeedsRuntime || interp.Installed(),
			Installing: interpInstBusy.Load()}
		if interp.NeedsRuntime {
			st.DownloadBytes = interp.RuntimeSize
		}
		if !st.Supported {
			st.Reason = "这个平台还没有补帧"
			return st, nil
		}
		name, _, err := interp.Device()
		if err != nil {
			st.Supported, st.Reason = false, err.Error()
			return st, nil
		}
		st.GPU = name
		if interp.NeedsRuntime {
			st.Backend = "dml"
			if interp.TRTReady() {
				st.Backend = "trt"
			}
			if nv := interp.NvidiaGPU(); nv.Name != "" {
				arch, size, why := interp.TRTOffer(nv)
				// 没装通用运行时的话 N 卡包会顺带装它,下载量要算进去
				if arch != "" && !interp.Installed() {
					size += interp.RuntimeSize
				}
				st.TRT = &InterpTRT{Available: arch != "", Reason: why, Ready: interp.TRTReady(),
					Bytes: size, Preparing: interpWarming.Load()}
			}
		}
		cur := currentInterpLevel()
		src, hz := propF("container-fps"), interp.DisplayHz()
		for _, l := range interp.Levels() {
			one := InterpLevel{ID: l.ID, Name: l.Name, Group: l.Group, Multi: l.Multi, Selected: l.ID == cur,
				FPS: interp.FPSNote(l.Multi, src)}
			if spec, err := interp.SpecOf(l.ID); err == nil && src > 0 {
				why := interp.WhyNot(spec, src, hz)
				run := why == ""
				one.WillRun, one.Note = &run, why
			}
			st.Levels = append(st.Levels, one)
		}
		return st, nil
	})

	bus.Register("player.setInterpLevel", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		noteDisplayHz(a)
		level, _ := a["level"].(string)
		if level == "" || level == "off" {
			interpOff()
			rememberInterp("off")
			return InterpApplied{Level: "off"}, nil
		}
		spec, err := interp.SpecOf(level)
		if err != nil {
			return nil, bus.NewErr(bus.EInvalid, "%v", err)
		}
		if src := propF("container-fps"); src > 0 {
			if why := interp.WhyNot(spec, src, interp.DisplayHz()); why != "" {
				return InterpApplied{Level: level, Reverted: true, Note: why}, nil
			}
		}
		if why := mountInterp(level); why != "" {
			interpOff()
			return InterpApplied{Level: level, Reverted: true, Note: why}, nil
		}
		rememberInterp(level)
		return InterpApplied{Level: level}, nil
	})

	// interpInstall 下载补帧组件。pack="trt" 装 N 卡加速包(没装通用的会先装)并预建引擎。
	// 阻塞到装完;进度走 player.interpInstall 事件:stage=download(done/total 字节)/ prepare(step/steps)。
	bus.Register("player.interpInstall", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		if !interp.NeedsRuntime {
			return nil, bus.NewErr(bus.EUnsupported, "这个平台的补帧不用下载组件")
		}
		if !interpInstMu.TryLock() {
			return nil, bus.NewErr(bus.EInvalid, "补帧组件正在下载")
		}
		defer interpInstMu.Unlock()
		interpInstBusy.Store(true)
		defer interpInstBusy.Store(false)
		last := time.Now()
		progress := func(done, total int64) {
			// 限流:几百 MB 按 32KB 一块报会把事件队列刷爆
			if time.Since(last) < 200*time.Millisecond && done != total {
				return
			}
			last = time.Now()
			bus.Emit("player.interpInstall", map[string]any{"stage": "download", "done": done, "total": total}, "player.interpInstall")
		}
		pack, _ := a["pack"].(string)
		if pack != "trt" {
			if err := interp.Install(ctx, progress); err != nil {
				return nil, bus.NewErr(bus.ENetwork, "%v", err)
			}
			return map[string]any{"ok": true}, nil
		}
		// 阶段进日志(desktop.log 收「补帧」开头的行):几 GB 的下载没有日志的话,排查时分不清卡在哪一步
		t0 := time.Now()
		// 包已经解好、只是引擎没建完(上次预建被打断 / 失败)的话,直接去建,不再下 2.9GB
		if interp.TRTInstalled() {
			bus.Logf("info", "补帧:N 卡加速包已在,只预建引擎")
		} else {
			bus.Logf("info", "补帧:开始下载 N 卡加速包(上游 vsNV 完整包)")
			err := interp.InstallTRT(ctx, progress, func(stage string) {
				bus.Logf("info", "补帧:N 卡加速包下载完,开始解压(下载用时 %.0f 秒)", time.Since(t0).Seconds())
				bus.Emit("player.interpInstall", map[string]any{"stage": stage}, "player.interpInstall")
			})
			if err != nil {
				bus.Logf("warn", "补帧:N 卡加速包没装上 —— %v", err)
				return nil, bus.NewErr(bus.ENetwork, "%v", err)
			}
		}
		interpWarming.Store(true)
		defer interpWarming.Store(false)
		bus.Logf("info", "补帧:N 卡加速包装好(用时 %.0f 秒),开始预建引擎", time.Since(t0).Seconds())
		t0 = time.Now()
		if err := warmTRT(ctx, func(i, n int) {
			bus.Emit("player.interpInstall", map[string]any{"stage": "prepare", "step": i, "steps": n}, "player.interpInstall")
		}); err != nil {
			bus.Logf("warn", "补帧:预建引擎失败 —— %v", err)
			return nil, bus.NewErr(bus.EInternal, "%v", err)
		}
		bus.Logf("info", "补帧:引擎建好,用时 %.0f 秒,之后走 N 卡加速", time.Since(t0).Seconds())
		// 正开着补帧的话换到 N 卡加速上;不重挂的话要等下一次开才生效
		if lv := currentInterpLevel(); lv != "off" && curInterpMounted() {
			if why := mountInterp(lv); why != "" {
				interpOff()
			}
		}
		return map[string]any{"ok": true}, nil
	})
}

// curInterpMounted 本进程里是不是真挂着一档(而不只是配置里记着)。
func curInterpMounted() bool {
	v, _ := curInterp.Load().(string)
	return v != "" && v != "off"
}
