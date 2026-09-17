// Package interp 是补帧:档位表 + 运行时组件(下载 / 解包 / 加载)。
//
// 画面怎么补由 mpv 的 vf=vapoursynth 跑 files/interp.vpy,模型是 DRBA / RIFE,
// 推理走 DirectML —— 任何 DX12 独显都能跑,不分 N 卡 A 卡。
// 运行时(VapourSynth + Python + onnxruntime + 模型)约 110MB,**用户点了才下**,
// 包由 scripts/pack-interp-runtime.sh 从上游 mpv_PlayKit 精简而来,发在本仓库的 Release 上。
//
// 实测数字和踩过的坑见 docs/lessons/player-mpv.md「补帧」。
package interp

import (
	_ "embed"
	"fmt"
	"strconv"
	"strings"
)

// Level 档位表里的一档。**顺序就是 UI 里的顺序**。
type Level struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Group string `json:"group"`
	// Multi 输出帧率是源的几倍。0 = 关。
	Multi int `json:"multi"`
}

// Spec 一档实际交给脚本的参数。
type Spec struct {
	Algo   string
	Multi  int
	Height int
}

// algos 两种算法各自补帧前降到的高度。
//
// ★ 高度是按 RTX 5060 Laptop 实测定的(1080p24,DirectML):
// DRBA 2 倍 1080p 不丢帧,3 倍 1080p 跟不上、720p 不丢,4 倍 720p 在 LinPlayer 里跑满 96 帧;
// RIFE 比 DRBA 慢,2 倍 1080p 就跟不上、900p 不丢。RIFE 3/4 倍的 720/540 **没单独测过**,
// 是按「倍数越高降得越多」推的 —— 跑不动由 core/player 的丢帧闸退回,不会卡着不动。
var algos = []struct {
	id, name string
	height   map[int]int
}{
	{"drba", "DRBA · 动画", map[int]int{2: 1080, 3: 720, 4: 720}},
	{"rife", "RIFE · 通用", map[int]int{2: 900, 3: 720, 4: 540}},
}

// Levels 全部档位。
func Levels() []Level {
	out := []Level{{ID: "off", Name: "关闭"}}
	for _, a := range algos {
		for _, m := range []int{2, 3, 4} {
			out = append(out, Level{ID: a.id + "_" + strconv.Itoa(m), Name: strconv.Itoa(m) + " 倍", Group: a.name, Multi: m})
		}
	}
	return out
}

// SpecOf 档位 id → 脚本参数。认不出就报错,**不回落默认档** —— 回落等于用户选 A 实际跑 B。
func SpecOf(id string) (Spec, error) {
	name, n, ok := strings.Cut(id, "_")
	m, err := strconv.Atoi(n)
	if ok && err == nil {
		for _, a := range algos {
			if a.id == name && a.height[m] > 0 {
				return Spec{Algo: name, Multi: m, Height: a.height[m]}, nil
			}
		}
	}
	return Spec{}, fmt.Errorf("未知的补帧档位:%s", id)
}

// MaxSourceFPS 超过这个帧率的片源不补。和脚本里那道判断是同一个数。
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

//go:embed files/interp.vpy
var script []byte

// UserData 交给 vf=vapoursynth 的 user-data。
func (s Spec) UserData(gpu int) string {
	return fmt.Sprintf("algo=%s;x=%d;gpu=%d;h=%d", s.Algo, s.Multi, gpu, s.Height)
}

// quote 把值包成 mpv 选项解析器的定长引用 `%字节数%值`。
// 路径里有空格、逗号、冒号都不会被切开 —— 用户的数据目录可能在「D:\新建 文件夹」里。
func quote(v string) string { return "%" + strconv.Itoa(len(v)) + "%" + v }

// FilterString 挂到 mpv `vf` 属性上的那一串。
func FilterString(scriptPath string, spec Spec, gpu int) string {
	return "@lpinterp:vapoursynth=file=" + quote(scriptPath) + ":user-data=" + quote(spec.UserData(gpu))
}
