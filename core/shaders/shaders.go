// Package shaders 是超分 / 画质增强档位 → glsl shader 链。
//
// # 只做两件事:锐化、提分辨率
//
// 用户 2026-09-07 原话:「超分能看得出效果的,一个是锐化,一个是提升分辨率,
// 就这两个,你选择这两个相关的超分算法」。所以这里**只有**自适应锐化和
// Anime4K CNN 放大两种 pass,双边去噪那两个文件同日删除 ——
// 它们改变的是噪点而不是清晰度,用户看不出来,却让每一档多一份开销。
//
// **锐化和放大是两件事,别糅成一坨。** Anime4K 每个 CNN pass 都带门槛
// `//!WHEN OUTPUT.w MAIN.w / 1.200 > ...` —— 输出没比源大 1.2 倍就**一帧都不跑**。
// 所以每一档都由「不挑尺寸的锐化」+「挑尺寸的 CNN 放大」两半组成:
// 窗口里退化成只锐化,全屏才补上放大。上一版有五档是纯放大,
// 在 1080p 屏上播 1080p 时一帧都不跑,就是用户说的「开了也看不出效果」。
//
// # 强度是「档位设计」的一部分,不是用户的活
//
// 用户 2026-07-15 原话:「强度不是靠用户调的 是让你设计挡位的 我说看不太出来
// 你就把各个档位都调高不就好了吗 用户又不会调」。每档的参数在 presetsFor 里调死,
// 梯度由档位名承诺(A/B/C = 低/中/高),UI 上没有任何数字可拧。
//
// # 六档 A/B/C(用户定 2026-09-07)
//
// 用户原话:「只需要三个档位 低中高 也就是 ABC,三个档位排列组合
// A+A B+B A+C A B C 六个档位即可」。ABC 在这里是**锐化强度三档**,
// 不是 Anime4K 官方那三种 Restore 模式(那套要 Restore CNN,用户 2026-07-11 否过)。
// 带 `+` 的三档 = 同强度再叠一层 CNN 放大。
//
// # 历史(别再走回头路)
//
//   - Restore CNN:用户 2026-07-11 明确否掉 —— 边缘振铃 / 拖影,且最吃显卡。有测试钉。
//   - ArtCNN_C4F16:2026-09-07 删除。它**八个 pass 全是 `//!COMPUTE`**,
//     而桌面走 ANGLE 的 GLES;那条路上不一定有计算着色器,mpv 只在日志里说
//     「Failed dispatching COMPUTE shader」,返回码全绿 —— 于是「开了没效果」。
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

// WhenRatio Anime4K CNN pass 的尺寸门槛:输出宽高都要 > 源的 1.2 倍。
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

// 链上的固定件。抽成常量是因为它们在六档里反复出现,而写错一个字母的下场是
// 落一个空文件、mpv 收下路径之后静默不跑。
const (
	sharpen  = "Adaptive_sharpen_lite_luma_RT.glsl"
	clamp    = "Anime4K_Clamp_Highlights.glsl"
	upM      = "Anime4K_Upscale_CNN_x2_M.glsl"
	upVL     = "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl"
	downPre2 = "Anime4K_AutoDownscalePre_x2.glsl"
	downPre4 = "Anime4K_AutoDownscalePre_x4.glsl"
)

// Preset 一个档位 = shader 链 + 这条链**调好的参数**。
type Preset struct {
	// Files **顺序就是 pipeline**:先放大(在源分辨率上做完 CNN)→ 最后锐化。
	Files []string
	// Opts 喂 mpv `glsl-shader-opts` 的 `K=V,K=V`。空 = 这条链没有可调参数。
	//
	// 只能写**本档 Files 里真实存在**的 `//!PARAM` —— mpv 遇到不认识的参数名会
	// **整条 opts 拒掉**(于是锐化强度静默回到 shader 自带默认,正是用户
	// 「看不太出来」的那个状态)。有测试逐档钉这件事。
	Opts string
}

// Level 一个档位的 UI 信息。**顺序就是 UI 里的顺序**,别按字母排。
type Level struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Group string `json:"group"`
}

// Levels 全部档位。六档 A/B/C(见包头)。
//
// 档位表**两个平台一样**,变的只是 `+` 那三档背后挂的放大链粗细(见 presetsFor)——
// 让手机少几个选项没有意义,它需要的是同一档更轻的实现。
func Levels() []Level {
	return []Level{
		{"off", "关闭", ""},
		{"ak_a", "A · 锐化 低", "Anime4K"},
		{"ak_b", "B · 锐化 中", "Anime4K"},
		{"ak_c", "C · 锐化 高", "Anime4K"},
		{"ak_aa", "A+A · 低 + 放大", "Anime4K"},
		{"ak_bb", "B+B · 中 + 放大", "Anime4K"},
		{"ak_ca", "C+A · 高 + 放大", "Anime4K"},
	}
}

/*
	锐化强度。`STR` 是 Adaptive_sharpen_lite 的 `//!PARAM`,区间 0.0~2.0,**越大越锐**;
	0 = 整个 pass 不跑(`//!WHEN STR`)。

shader 自带默认是 0.5 —— 只开一半,就是用户「看不太出来」的根因,所以每档都显式写。
1.30 是 2026-07-20 调出来的「推荐」值,低/高两档以它为中心上下拉开。
*/
const (
	strLow  = "STR=0.90"
	strMid  = "STR=1.30"
	strHigh = "STR=1.70"
)

// upscaleFor 放大那半用哪条链。**这是唯一按平台分岔的地方。**
//
// medium(移动端)封顶在 CNN x2 (M) 单段;high(桌面)最重那一档才上
// VL 放大 + 两级 AutoDownscalePre + 二次 M —— 这是 Anime4K 官方
// C+A 那条链的结构。手机 GPU 是集显且有温度墙,VL 那条链在它上面
// 就是用户说的「超级无敌卡」(和解码软硬无关,卡在着色器这一段)。
func upscaleFor(t Tier, top bool) []string {
	if top && t == TierHigh {
		return []string{clamp, upVL, downPre2, downPre4, upM}
	}
	return []string{clamp, upM}
}

// presetsFor 档位 → shader 链 + 参数。
//
// 每档的形状都是 `[放大] + [锐化]`,其中放大那半可以为空。
// 锐化**永远在最后而且永远在**:它是唯一不挑尺寸的 pass,
// 少了它这一档在窗口模式下就什么都不做。
func presetsFor(t Tier) map[string]Preset {
	up := func(top bool) []string { return append(upscaleFor(t, top), sharpen) }
	return map[string]Preset{
		"ak_a":  {Files: []string{sharpen}, Opts: strLow},
		"ak_b":  {Files: []string{sharpen}, Opts: strMid},
		"ak_c":  {Files: []string{sharpen}, Opts: strHigh},
		"ak_aa": {Files: up(false), Opts: strLow},
		"ak_bb": {Files: up(false), Opts: strMid},
		"ak_ca": {Files: up(true), Opts: strHigh},
	}
}

// PresetOf 取一个档位。off / 未知 = 关(ok=false)。
func PresetOf(level string) (Preset, bool) {
	p, ok := presetsFor(platformTier)[level]
	return p, ok
}

// Opts 这一档的 `glsl-shader-opts` 串。off / 未知 = 空串。
//
// ★ 切到 off 时给空串,**顺带把上一档的参数清掉** —— 不清的话下一档会吃到
// 上一档留下的值(glsl-shader-opts 是全局的)。
func Opts(level string) string {
	p, ok := PresetOf(level)
	if !ok {
		return ""
	}
	return p.Opts
}

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
// 判据:存在至少一个「非辅助、且不挑尺寸」的 pass。
// 语义是「**有效果**」,不是「**全部 pass 都跑**」—— 带放大的三档在窗口下
// 退化成只有锐化,那也算有效果。
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
// 而 UI 还在报「超分已生效 · 挂载 6 个 shader」。那是在撒谎,正是本项目最贵的那类 bug。
func WillRun(level string, videoW, videoH, outW, outH float64) (run bool, ok bool) {
	if _, exists := PresetOf(level); !exists {
		return false, false // off / 未知
	}
	if WorksAtAnySize(level) {
		return true, true // 锐化那半不挑尺寸,永远有效果
	}
	if videoW <= 0 || videoH <= 0 || outW <= 0 || outH <= 0 {
		return false, false // 尺寸未知,不下结论
	}
	return outW/videoW > WhenRatio && outH/videoH > WhenRatio, true
}

// UpscaleWillRun 这档的**放大那半**在当前尺寸下跑不跑。
//
// 和 WillRun 分开是因为两者答的不是同一个问题:WillRun 答「这档有没有用」,
// 这个答「你现在拿到的是完整效果还是只有锐化」。没有放大 pass 的档位 ok=false。
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

// ParamsOf 一个 shader 文件声明了哪些 `//!PARAM`。测试用。
func ParamsOf(file string) []string {
	var out []string
	for _, l := range strings.Split(bodyOf(file), "\n") {
		if v, ok := strings.CutPrefix(strings.TrimSpace(l), "//!PARAM "); ok {
			out = append(out, strings.TrimSpace(v))
		}
	}
	return out
}

// UsesCompute 这个 shader 需要计算着色器吗。
//
// ☠ 这是「开了没效果还不报错」的第二种机制:计算着色器在 ANGLE 的 GLES 上不一定有,
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
