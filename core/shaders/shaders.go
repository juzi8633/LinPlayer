// Package shaders 是画面增强档位 → glsl shader 链。
//
// # 档位就照 Anime4K 官方那六个(用户定 2026-09-07)
//
// A / B / C / A+A / B+B / C+A,链**一字不改**地抄自官方模板的 input.conf:
//
//	md/Template/GLSL_Windows_Low-end/input.conf   → medium 档(移动端)
//	md/Template/GLSL_Windows_High-end/input.conf  → high   档(桌面)
//
// 官方对档位的说明:A 给 1080p 片源、B 给 720p、C 给 480p;带 `+` 的多跑一趟 Restore。
// 两个模板的差别只是模型大小(Low-end 用 M/S,High-end 用 VL/M),模式结构完全一样。
//
// ★ **别自己拼链。** 抄官方的好处不是省事,是「换了档位还原不回去」这件事不会发生 ——
// 出问题时能一行一行和上游对,而自拼的链只能靠记忆争论。
//
// # 平台分层
//
// 用户原话:「移动端还是最高到 medium 档,PC 端到 High 档」。
// 由构建标签定(tier_android.go / tier_other.go),不给用户选。
//
// # Restore CNN 回来了
//
// 用户 2026-07-11 / 07-15 两次否掉过 Restore(边缘振铃 / 拖影),仓库里为此立过一条测试。
// **2026-09-07 用户改了主意**:「按照 Anime4K 里面说的档位去分吧」——
// 而官方 A/B 就是 Restore,没有 Restore 就没有 A/B。那条测试同日删除。
//
// # 「不生效」这件事,只有 Mode C 摊上
//
// Anime4K 的放大 pass 都带 `//!WHEN OUTPUT.w MAIN.w / 1.200 > …`,输出没比源大 1.2 倍
// 就一帧都不跑;而 **Restore 没有尺寸门槛**(实测:Restore_CNN_* 里 0 处 OUTPUT 比较)。
// 所以六档里只有 Mode C 是纯放大 —— 1080p 屏播 1080p 时它整条空转,
// 由 WillRun 判 false,两端 UI 都会把它藏掉。其余五档在任何尺寸下都有 Restore 在干活。
//
// # 为什么把 .glsl 编进二进制
//
// 绿色版是平铺分发,没有 resources 目录可用。首次用时落盘到数据目录 ——
// mpv 的 glsl-shaders 只收**文件路径**。`files/` 下这份是本仓唯一一份。
package shaders

import (
	"embed"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

//go:embed files/*.glsl
var embedded embed.FS

// WhenRatio Anime4K 放大 pass 的尺寸门槛:输出宽高都要 > 源的 1.2 倍。
//
// shader 源里写死的是 `//!WHEN OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 * >`。
// 有测试从嵌入的源里抠出这个数比对 —— 免得哪天换了 shader 文件而这里还写着旧值。
const WhenRatio = 1.2

// helpers 辅助 pass:自己不产生可见效果,只服务于放大链(高光钳位 / 降回显示分辨率)。
//
// ★ 判断「这档在窗口下有没有效果」时**不算数** —— 只有它们跑起来等于什么都没发生。
var helpers = map[string]bool{
	"Anime4K_Clamp_Highlights.glsl":    true,
	"Anime4K_AutoDownscalePre_x2.glsl": true,
	"Anime4K_AutoDownscalePre_x4.glsl": true,
}

// Preset 一个档位 = shader 链。**顺序就是 pipeline**,和官方 input.conf 逐个对齐。
type Preset struct{ Files []string }

// Level 一个档位的 UI 信息。**顺序就是 UI 里的顺序**,别按字母排。
type Level struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Group string `json:"group"`
}

// Levels 全部档位。副标题里的分辨率是官方给的适用片源(A→1080p / B→720p / C→480p)。
func Levels() []Level {
	return []Level{
		{"off", "关闭", ""},
		{"ak_a", "A · 1080p 片源", "Anime4K"},
		{"ak_b", "B · 720p 片源", "Anime4K"},
		{"ak_c", "C · 480p 片源", "Anime4K"},
		{"ak_aa", "A+A · 1080p 加强", "Anime4K"},
		{"ak_bb", "B+B · 720p 加强", "Anime4K"},
		{"ak_ca", "C+A · 480p 加强", "Anime4K"},
	}
}

// 模型大小按平台换一档:high 用 VL 打头 + M 收尾,medium 用 M 打头 + S 收尾。
// 官方两个模板的差别就只有这三个名字,模式结构一模一样。
type models struct{ big, small string }

func modelsFor(t Tier) models {
	if t == TierHigh {
		return models{big: "VL", small: "M"}
	}
	return models{big: "M", small: "S"}
}

const clamp = "Anime4K_Clamp_Highlights.glsl"

func restore(sz string) string     { return "Anime4K_Restore_CNN_" + sz + ".glsl" }
func restoreSoft(sz string) string { return "Anime4K_Restore_CNN_Soft_" + sz + ".glsl" }
func upscale(sz string) string     { return "Anime4K_Upscale_CNN_x2_" + sz + ".glsl" }
func upDenoise(sz string) string   { return "Anime4K_Upscale_Denoise_CNN_x2_" + sz + ".glsl" }

const (
	downPre2 = "Anime4K_AutoDownscalePre_x2.glsl"
	downPre4 = "Anime4K_AutoDownscalePre_x4.glsl"
)

/*
	presetsFor 六条链,逐行对应官方 input.conf 的 CTRL+1..6。

☠ 顺序里有两处**看着像笔误其实是官方就这么写的**,别去「顺手理顺」:
  - B+B 的第二趟 Restore 夹在两个 AutoDownscalePre **中间**(A+A 和 C+A 都在两个之后)。
  - C+A 的第二趟用的是 Restore(不带 Soft),不是 Restore_Soft。
*/
func presetsFor(t Tier) map[string]Preset {
	m := modelsFor(t)
	return map[string]Preset{
		"ak_a": {Files: []string{
			clamp, restore(m.big), upscale(m.big), downPre2, downPre4, upscale(m.small),
		}},
		"ak_b": {Files: []string{
			clamp, restoreSoft(m.big), upscale(m.big), downPre2, downPre4, upscale(m.small),
		}},
		"ak_c": {Files: []string{
			clamp, upDenoise(m.big), downPre2, downPre4, upscale(m.small),
		}},
		"ak_aa": {Files: []string{
			clamp, restore(m.big), upscale(m.big), restore(m.small),
			downPre2, downPre4, upscale(m.small),
		}},
		"ak_bb": {Files: []string{
			clamp, restoreSoft(m.big), upscale(m.big), downPre2,
			restoreSoft(m.small), downPre4, upscale(m.small),
		}},
		"ak_ca": {Files: []string{
			clamp, upDenoise(m.big), downPre2, downPre4,
			restore(m.small), upscale(m.small),
		}},
	}
}

// PresetOf 取一个档位。off / 未知 = 关(ok=false)。
func PresetOf(level string) (Preset, bool) {
	p, ok := presetsFor(platformTier)[level]
	return p, ok
}

// Opts 这一档的 `glsl-shader-opts` 串。
//
// ★ 官方六条链一个 `//!PARAM` 都不带 —— 强度写死在模型权重里,只能换模型大小。
// 所以这里恒为空串。**留着这个函数是因为切档时必须显式写一次空串**:
// glsl-shader-opts 是全局的,上一档留下的值会被下一档吃掉。
func Opts(level string) string { return "" }

func bodyOf(name string) string {
	b, err := embedded.ReadFile("files/" + name)
	if err != nil {
		return ""
	}
	return string(b)
}

// isUpscaleGated 这个 shader 是不是「只有放大才跑」。
//
// 从源里现算,不手工维护名单 —— 换 shader 文件时结论自动跟着变,
// 不会留下过期的白名单。判据:`//!WHEN` 里有没有拿 OUTPUT 比尺寸。
func isUpscaleGated(name string) bool {
	for _, l := range strings.Split(bodyOf(name), "\n") {
		if strings.HasPrefix(l, "//!WHEN") && strings.Contains(l, "OUTPUT.") {
			return true
		}
	}
	return false
}

// WorksAtAnySize 这档在**任意尺寸**(含窗口模式、缩小播放)下有可见效果吗。
//
// 判据:存在至少一个「非辅助、且不挑尺寸」的 pass。Restore 就是这样的 pass,
// 所以带 Restore 的五档都判 true,只有 Mode C 判 false。
func WorksAtAnySize(level string) bool {
	p, ok := PresetOf(level)
	if !ok {
		return false
	}
	for _, f := range p.Files {
		if !helpers[f] && !isUpscaleGated(f) {
			return true
		}
	}
	return false
}

// WillRun 当前尺寸下这档会不会真的有效果。ok=false 表示尺寸未知(没在播),**不下结论**。
//
// ☠ 存在的理由:**mpv 收下 glsl-shaders 路径 ≠ shader 会执行**。
// 2026-07-15 真机:窗口 1770×1080 播 1920×1080,六个 CNN pass 全被 //!WHEN 跳过,
// 而 UI 还在报「已生效 · 挂载 6 个 shader」。那是在撒谎,正是本项目最贵的那类 bug。
func WillRun(level string, videoW, videoH, outW, outH float64) (run bool, ok bool) {
	if _, exists := PresetOf(level); !exists {
		return false, false // off / 未知
	}
	if WorksAtAnySize(level) {
		return true, true // Restore 不挑尺寸,永远有效果
	}
	if videoW <= 0 || videoH <= 0 || outW <= 0 || outH <= 0 {
		return false, false // 尺寸未知,不下结论
	}
	return outW/videoW > WhenRatio && outH/videoH > WhenRatio, true
}

// UpscaleWillRun 这档的**放大那半**在当前尺寸下跑不跑。
//
// 和 WillRun 分开是因为两者答的不是同一个问题:WillRun 答「这档有没有用」,
// 这个答「你现在拿到的是完整效果还是只有 Restore」。没有放大 pass 的档位 ok=false。
func UpscaleWillRun(level string, videoW, videoH, outW, outH float64) (run bool, ok bool) {
	p, exists := PresetOf(level)
	if !exists {
		return false, false
	}
	gated := false
	for _, f := range p.Files {
		if isUpscaleGated(f) {
			gated = true
			break
		}
	}
	if !gated || videoW <= 0 || videoH <= 0 || outW <= 0 || outH <= 0 {
		return false, false
	}
	return outW/videoW > WhenRatio && outH/videoH > WhenRatio, true
}

// EnsureFiles 把嵌入的 shader 落到 dir 下,返回文件名 → 绝对路径。
//
// 内容是编译期常量,**长度一致即认为已是当前版本** —— 免得每次起播重写几百 KB。
func EnsureFiles(dir string) (map[string]string, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("建 shader 目录失败: %w", err)
	}
	ents, err := embedded.ReadDir("files")
	if err != nil {
		return nil, err
	}
	out := map[string]string{}
	for _, e := range ents {
		name := e.Name()
		body, err := embedded.ReadFile("files/" + name)
		if err != nil {
			return nil, err
		}
		p := filepath.Join(dir, name)
		if st, err := os.Stat(p); err != nil || st.Size() != int64(len(body)) {
			if err := os.WriteFile(p, body, 0o644); err != nil {
				return nil, fmt.Errorf("写 %s 失败: %w", name, err)
			}
		}
		out[name] = p
	}
	return out, nil
}

// Paths 档位 → 可直接喂给 mpv glsl-shaders 的绝对路径列表。
// off / 未知 → 空列表(= 关)。
func Paths(dir, level string) ([]string, error) {
	p, ok := PresetOf(level)
	if !ok {
		return []string{}, nil
	}
	files, err := EnsureFiles(dir)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(p.Files))
	for _, n := range p.Files {
		abs, ok := files[n]
		if !ok {
			return nil, fmt.Errorf("缺少 shader: %s", n)
		}
		out = append(out, abs)
	}
	return out, nil
}

// UsesCompute 这个 shader 需要计算着色器吗。
//
// ☠ 这是「开了没效果还不报错」的另一种机制:计算着色器在 ANGLE 的 GLES 上不一定有,
// mpv 遇到没法 dispatch 的 pass **只在日志里说一句**,返回码和属性全是绿的。
// 有测试拿它扫全表 —— 再往 files/ 里放 `//!COMPUTE` 的 shader 会当场红。
func UsesCompute(file string) bool {
	for _, l := range strings.Split(bodyOf(file), "\n") {
		if strings.HasPrefix(strings.TrimSpace(l), "//!COMPUTE") {
			return true
		}
	}
	return false
}
