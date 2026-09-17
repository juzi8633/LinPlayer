// Package interp 是补帧:档位表、平台后端、(Windows)运行时组件。
//
// 两个平台两套后端,档位口径一致(算法 × 2/3/4 倍,补完超过屏幕刷新率不给开):
//   - Windows(vs_windows.go):mpv 的 vf=vapoursynth 跑 DRBA / RIFE,推理走 DirectML;
//     运行时约 110MB,用户点了才下(runtime.go)。
//   - 安卓(ocl_android.go):自编 libmpv 里的 vf=lpinterp,OpenCL 光流(third_party/mpv-interp)。
//
// 实测数字和踩过的坑见 docs/lessons/player-mpv.md「补帧」。
package interp

import (
	"fmt"
	"math"
	"strconv"
	"strings"
	"sync/atomic"
)

// Level 档位表里的一档。**顺序就是 UI 里的顺序**。
type Level struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Group string `json:"group"`
	// Multi 输出帧率是源的几倍。0 = 关。
	Multi int `json:"multi"`
}

// Spec 一档实际交给滤镜的参数。
type Spec struct {
	Algo   string
	Multi  int
	Height int // 补帧前降到的高度;0 = 滤镜自己定
}

// algo 平台后端提供的一种算法。height[倍数] 必须有键,那一档才存在。
type algo struct {
	id, name string
	height   map[int]int
}

// Levels 全部档位。平台不支持时只有「关闭」。
func Levels() []Level {
	out := []Level{{ID: "off", Name: "关闭"}}
	for _, a := range algos {
		for _, m := range []int{2, 3, 4} {
			if _, ok := a.height[m]; ok {
				out = append(out, Level{ID: a.id + "_" + strconv.Itoa(m), Name: strconv.Itoa(m) + " 倍", Group: a.name, Multi: m})
			}
		}
	}
	return out
}

// SpecOf 档位 id → 滤镜参数。认不出就报错,**不回落默认档** —— 回落等于用户选 A 实际跑 B。
func SpecOf(id string) (Spec, error) {
	name, n, ok := strings.Cut(id, "_")
	m, err := strconv.Atoi(n)
	if ok && err == nil {
		for _, a := range algos {
			if h, has := a.height[m]; a.id == name && has {
				return Spec{Algo: name, Multi: m, Height: h}, nil
			}
		}
	}
	return Spec{}, fmt.Errorf("未知的补帧档位:%s", id)
}

// MaxSourceFPS 超过这个帧率的片源不补。和两个平台滤镜里的判断是同一个数。
const MaxSourceFPS = 32

// WhyNot 这一档在当前片源 / 屏幕下为什么不该开。空串 = 可以开。
// 片源帧率或刷新率未知(<=0)时不判那一条 —— 猜一个会把整张表藏空。
//
// ★ 补出来的帧率超过屏幕刷新率就没有意义:多出来的帧 VO 只能扔,画面看不出区别,
// 还会被丢帧闸当成「显卡跑不动」。
func WhyNot(spec Spec, srcFPS, displayHz float64) string {
	if srcFPS > MaxSourceFPS {
		return fmt.Sprintf("片源已经是 %.0f 帧,不需要补帧", srcFPS)
	}
	if srcFPS > 0 && displayHz > 0 && srcFPS*float64(spec.Multi) > displayHz+1 {
		return fmt.Sprintf("补到 %.0f 帧超过了屏幕刷新率 %.0fHz,多出来的帧显示不出来",
			srcFPS*float64(spec.Multi), displayHz)
	}
	return ""
}

// quote 把值包成 mpv 选项解析器的定长引用 `%字节数%值`。
// 路径里有空格、逗号、冒号都不会被切开 —— 用户的数据目录可能在「D:\新建 文件夹」里。
func quote(v string) string { return "%" + strconv.Itoa(len(v)) + "%" + v }

// reportedHz UI 报上来的屏幕刷新率(安卓的核心层拿不到窗口系统,只能由 UI 传)。
var reportedHz atomic.Uint64

// SetDisplayHz 记下 UI 报的刷新率。<=0 忽略。
func SetDisplayHz(hz float64) {
	if hz > 0 {
		reportedHz.Store(math.Float64bits(hz))
	}
}

func reported() float64 { return math.Float64frombits(reportedHz.Load()) }
