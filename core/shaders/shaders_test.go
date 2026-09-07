package shaders

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// allTiers 两个平台档都要扫 —— 只扫本机那一档的话,安卓那条链改坏了在 CI 上全绿。
var allTiers = []Tier{TierMedium, TierHigh}

/*
	☠☠ 六条链**逐字**对着 Anime4K 官方模板的 input.conf 钉住。

来源(2026-09-07 抓的):

	md/Template/GLSL_Windows_Low-end/input.conf   CTRL+1..6  → medium
	md/Template/GLSL_Windows_High-end/input.conf  CTRL+1..6  → high

抄错一个模型大小、或者「顺手把 B+B 那个夹在中间的 Restore 理顺」,
画面只是**略有不同**,不报错、不掉帧、没人看得出来 —— 只有这张表能。
*/
var officialChains = map[Tier]map[string][]string{
	TierMedium: {
		"ak_a": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl",
			"Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
			"Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_S.glsl"},
		"ak_b": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_Soft_M.glsl",
			"Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
			"Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_S.glsl"},
		"ak_c": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
			"Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Upscale_CNN_x2_S.glsl"},
		"ak_aa": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl",
			"Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_Restore_CNN_S.glsl",
			"Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Upscale_CNN_x2_S.glsl"},
		"ak_bb": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_Soft_M.glsl",
			"Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
			"Anime4K_Restore_CNN_Soft_S.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Upscale_CNN_x2_S.glsl"},
		"ak_ca": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
			"Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Restore_CNN_S.glsl", "Anime4K_Upscale_CNN_x2_S.glsl"},
	},
	TierHigh: {
		"ak_a": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_VL.glsl",
			"Anime4K_Upscale_CNN_x2_VL.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
			"Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_M.glsl"},
		"ak_b": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_Soft_VL.glsl",
			"Anime4K_Upscale_CNN_x2_VL.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
			"Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_M.glsl"},
		"ak_c": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl",
			"Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Upscale_CNN_x2_M.glsl"},
		"ak_aa": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_VL.glsl",
			"Anime4K_Upscale_CNN_x2_VL.glsl", "Anime4K_Restore_CNN_M.glsl",
			"Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Upscale_CNN_x2_M.glsl"},
		"ak_bb": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_Soft_VL.glsl",
			"Anime4K_Upscale_CNN_x2_VL.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
			"Anime4K_Restore_CNN_Soft_M.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Upscale_CNN_x2_M.glsl"},
		"ak_ca": {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl",
			"Anime4K_AutoDownscalePre_x2.glsl", "Anime4K_AutoDownscalePre_x4.glsl",
			"Anime4K_Restore_CNN_M.glsl", "Anime4K_Upscale_CNN_x2_M.glsl"},
	},
}

func Test六条链和Anime4K官方逐字一致(t *testing.T) {
	for _, tier := range allTiers {
		got, want := presetsFor(tier), officialChains[tier]
		if len(got) != len(want) {
			t.Fatalf("[%s] 档位数对不上:实得 %d,官方 %d", tier, len(got), len(want))
		}
		for id, w := range want {
			g, ok := got[id]
			if !ok {
				t.Errorf("[%s] 少了档位 %s", tier, id)
				continue
			}
			if strings.Join(g.Files, ";") != strings.Join(w, ";") {
				t.Errorf("[%s] 档位 %s 与官方 input.conf 不一致\n  实得:%v\n  官方:%v",
					tier, id, g.Files, w)
			}
		}
	}
}

// 档位表里引用的每个文件都必须真的嵌进来了 —— 否则**运行时才炸**(点了没反应)。
func TestEveryPresetFileIsEmbedded(t *testing.T) {
	for _, tier := range allTiers {
		for id, p := range presetsFor(tier) {
			for _, f := range p.Files {
				if bodyOf(f) == "" {
					t.Errorf("[%s] 档位 %s 引用了未嵌入的 shader: %s", tier, id, f)
				}
			}
		}
	}
}

// ☠ **不许再往 files/ 里放需要计算着色器的 shader。**
//
// 桌面走 Avalonia 的 ANGLE(GLES),那条路上不一定有计算着色器;
// mpv 遇到没法 dispatch 的 pass 只在日志里说一句「Failed dispatching COMPUTE shader」,
// 返回码、属性、挂载数全是绿的 —— 就是 2026-09-07 用户报的
// 「PC 端超分失败,哪怕开了也看不出效果」(当时那两档挂的是全 COMPUTE 的 ArtCNN)。
func TestNoComputeOnlyShaders(t *testing.T) {
	ents, err := embedded.ReadDir("files")
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range ents {
		if UsesCompute(e.Name()) {
			t.Errorf("%s 需要计算着色器 —— 桌面 ANGLE 上它会静默不跑,"+
				"而我们照样报「已启用」", e.Name())
		}
	}
}

// ★★ 移动端封顶在 medium【用户定 2026-09-07:「移动端还是最高到 medium 档,PC 端到 High 档」】。
//
// 手机 GPU 是集显且有温度墙,VL 那一族在它上面就是用户说的「超级无敌卡」——
// 卡在着色器这一段,和解码软硬无关(安卓这边 hwdec 走 MediaCodec)。
func TestMobileNeverLoadsTheHeavyModels(t *testing.T) {
	for id, p := range presetsFor(TierMedium) {
		for _, f := range p.Files {
			if strings.Contains(f, "_VL.") || strings.Contains(f, "_L.") ||
				strings.Contains(f, "_UL.") {
				t.Errorf("移动端档位 %s 挂了 %s —— 封顶是 M,再往上手机跑不动", id, f)
			}
		}
	}
	// 反过来:桌面那几档要真的用上 VL,否则「PC 到 High」是句空话
	for _, id := range []string{"ak_a", "ak_b", "ak_c"} {
		hit := false
		for _, f := range presetsFor(TierHigh)[id].Files {
			if strings.Contains(f, "_VL.") {
				hit = true
			}
		}
		if !hit {
			t.Errorf("桌面档位 %s 一个 VL 模型都没挂,实得 %v", id, presetsFor(TierHigh)[id].Files)
		}
	}
}

/*
	☠☠ 六档里**只有 Mode C 允许是纯放大**。

Anime4K 的放大 pass 都带 `//!WHEN 输出 > 源 × 1.2`,1080p 屏播 1080p 就整条空转 ——
用户点了、UI 说生效了、画面一点没变。而 Restore 没有尺寸门槛,所以带 Restore 的
五档在任何尺寸下都有东西在跑。这条把「哪几档会遇到这个坑」钉死:
多一档变成纯放大(比如有人把 A 的 Restore 拿掉),它会当场红。
*/
func TestOnlyModeCIsUpscaleOnly(t *testing.T) {
	for _, tier := range allTiers {
		for id := range presetsFor(tier) {
			want := id != "ak_c" // 只有 Mode C 该是 false
			if got := worksAtAnySizeIn(tier, id); got != want {
				t.Errorf("[%s] 档位 %s 的「任何尺寸都有效果」该是 %v,实得 %v", tier, id, want, got)
			}
		}
	}
}

// ★★ 「挂上了」和「会跑」是两件事。
func TestWillRun(t *testing.T) {
	// 带 Restore 的五档不挑尺寸,尺寸未知也该判 true
	for _, id := range []string{"ak_a", "ak_b", "ak_aa", "ak_bb", "ak_ca"} {
		if run, ok := WillRun(id, 0, 0, 0, 0); !ok || !run {
			t.Errorf("%s 该在任意尺寸下有效果,实得 run=%v ok=%v", id, run, ok)
		}
	}
	// Mode C 是纯放大:窗口 1770×1080 播 1920×1080 不跑,全屏 4K 播 1080p 跑
	if run, ok := WillRun("ak_c", 1920, 1080, 1770, 1080); !ok || run {
		t.Errorf("Mode C 在缩小播放时不该跑,实得 run=%v ok=%v", run, ok)
	}
	if run, ok := WillRun("ak_c", 1920, 1080, 3840, 2160); !ok || !run {
		t.Errorf("Mode C 在 4K 屏播 1080p 时该跑,实得 run=%v ok=%v", run, ok)
	}
	// 恰好 1.2 倍:不算(源里写的是**严格大于**)
	if run, _ := WillRun("ak_c", 1000, 1000, 1200, 1200); run {
		t.Error("恰好 1.2 倍不该跑 —— shader 里写的是严格大于")
	}
	// 尺寸未知(没在播)时**不下结论** —— 猜一个 false 会让还没起播的抽屉变成空表
	if _, ok := WillRun("ak_c", 0, 0, 0, 0); ok {
		t.Error("尺寸未知时不该下结论")
	}
	// off / 未知
	if _, ok := WillRun("off", 1920, 1080, 3840, 2160); ok {
		t.Error("off 不该有结论")
	}
	if _, ok := WillRun("根本没这档", 1920, 1080, 3840, 2160); ok {
		t.Error("未知档位不该有结论")
	}
}

// 放大那半单独有个判据:答的是「你拿到的是完整效果还是只有 Restore」。
func TestUpscaleWillRun(t *testing.T) {
	if run, ok := UpscaleWillRun("ak_a", 1920, 1080, 1770, 1080); !ok || run {
		t.Errorf("窗口 1770×1080 播 1920×1080 时放大那半不该跑,实得 run=%v ok=%v", run, ok)
	}
	if run, ok := UpscaleWillRun("ak_a", 1920, 1080, 3840, 2160); !ok || !run {
		t.Errorf("全屏 4K 播 1080p 时放大那半该跑,实得 run=%v ok=%v", run, ok)
	}
	if _, ok := UpscaleWillRun("ak_a", 0, 0, 0, 0); ok {
		t.Error("尺寸未知时不该下结论")
	}
	if _, ok := UpscaleWillRun("off", 1920, 1080, 3840, 2160); ok {
		t.Error("off 不该有结论")
	}
}

// WhenRatio 这个常量必须和 shader 源里写死的门槛一致。
//
// 从**嵌入的源**里抠出来比对,而不是相信注释 —— 换 shader 文件时这条会红。
func TestWhenRatioMatchesShaderSource(t *testing.T) {
	body := bodyOf("Anime4K_Upscale_CNN_x2_M.glsl")
	if body == "" {
		t.Fatal("拿不到 shader 源")
	}
	found := false
	for _, l := range strings.Split(body, "\n") {
		if !strings.HasPrefix(l, "//!WHEN") || !strings.Contains(l, "OUTPUT.") {
			continue
		}
		found = true
		want := strconv.FormatFloat(WhenRatio, 'f', 3, 64) // "1.200"
		if !strings.Contains(l, want) {
			t.Errorf("WhenRatio=%v 与 shader 源里的门槛对不上:%s", WhenRatio, strings.TrimSpace(l))
		}
	}
	if !found {
		t.Error("这个 shader 里没找到尺寸门槛那一行 —— 是不是换文件了?")
	}
}

// Restore **必须**没有尺寸门槛。
//
// 这是「只有 Mode C 会遇到不生效」这个结论的全部依据。哪天上游给 Restore 加了
// `//!WHEN OUTPUT…`,六档会一起变成「同分辨率下什么都不做」,而没有任何报错。
func TestRestoreHasNoSizeGate(t *testing.T) {
	for _, f := range []string{
		"Anime4K_Restore_CNN_S.glsl", "Anime4K_Restore_CNN_M.glsl", "Anime4K_Restore_CNN_VL.glsl",
		"Anime4K_Restore_CNN_Soft_S.glsl", "Anime4K_Restore_CNN_Soft_M.glsl",
		"Anime4K_Restore_CNN_Soft_VL.glsl",
	} {
		if bodyOf(f) == "" {
			t.Errorf("%s 不在 embed 里", f)
			continue
		}
		if isUpscaleGated(f) {
			t.Errorf("%s 现在带尺寸门槛了 —— 六档会一起变成「同分辨率下什么都不做」", f)
		}
	}
}

// 官方六条链一个 //!PARAM 都不带,所以 Opts 恒为空串。
//
// ★ 它**不是可以删的死代码**:切档时必须显式写一次空串,
// 不写的话上一档留下的 glsl-shader-opts 会被下一档吃掉(那是全局属性)。
func TestOptsIsAlwaysEmptyBecauseChainsHaveNoParams(t *testing.T) {
	for _, tier := range allTiers {
		for id, p := range presetsFor(tier) {
			for _, f := range p.Files {
				if ps := paramsOf(f); len(ps) > 0 {
					t.Errorf("[%s] 档位 %s 的 %s 声明了 %v —— 有参数就得在 Opts 里显式给,"+
						"不给会吃 shader 自带默认", tier, id, f, ps)
				}
			}
		}
	}
	for _, id := range []string{"off", "ak_a", "根本没这档"} {
		if Opts(id) != "" {
			t.Errorf("%s 的 opts 该是空串,实得 %q", id, Opts(id))
		}
	}
}

// 落盘:内容一致时**不重写**(免得每次起播白写),路径能直接喂给 mpv。
func TestEnsureFilesIsIdempotent(t *testing.T) {
	dir := t.TempDir()
	m1, err := EnsureFiles(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(m1) == 0 {
		t.Fatal("一个都没落下来")
	}
	target := m1["Anime4K_Clamp_Highlights.glsl"]
	st1, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	// 把 mtime 拨旧,再跑一次:内容一致就不该被重写
	old := st1.ModTime().Add(-time.Hour)
	if err := os.Chtimes(target, old, old); err != nil {
		t.Fatal(err)
	}
	if _, err := EnsureFiles(dir); err != nil {
		t.Fatal(err)
	}
	st2, _ := os.Stat(target)
	if !st2.ModTime().Equal(old) {
		t.Error("内容没变却重写了 —— 每次起播白写几百 KB")
	}

	// 文件被改坏(长度不同)时要重新落
	if err := os.WriteFile(target, []byte("坏了"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := EnsureFiles(dir); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(target); string(b) == "坏了" {
		t.Error("文件长度对不上时该重新落盘")
	}
}

func TestPaths(t *testing.T) {
	dir := t.TempDir()
	got, err := Paths(dir, "ak_a")
	if err != nil {
		t.Fatal(err)
	}
	want := officialChains[CurrentTier()]["ak_a"]
	if len(got) != len(want) {
		t.Fatalf("ak_a 该有 %d 个 pass,实得 %v", len(want), got)
	}
	for i, p := range got {
		if !filepath.IsAbs(p) {
			t.Errorf("mpv 的 glsl-shaders 只收绝对路径: %s", p)
		}
		if filepath.Base(p) != want[i] {
			t.Errorf("第 %d 个 pass 该是 %s,实得 %s", i, want[i], filepath.Base(p))
		}
	}
	// off / 未知 = 空列表(把上一档关掉)
	for _, id := range []string{"off", "根本没这档"} {
		if g, err := Paths(dir, id); err != nil || len(g) != 0 {
			t.Errorf("%s 该给空列表,实得 %v %v", id, g, err)
		}
	}
}

// 嵌进二进制的每个 .glsl 都必须被某一档用到。
//
// 删档位时最容易漏掉文件本身:它继续占体积、继续每次起播落盘,
// 而没有任何一条路径会读它 —— 编译绿、单测绿、包大了几百 KB 没人发现。
func Test没有没人用的shader(t *testing.T) {
	used := map[string]bool{}
	for _, tier := range allTiers {
		for _, p := range presetsFor(tier) {
			for _, f := range p.Files {
				used[f] = true
			}
		}
	}
	ents, err := embedded.ReadDir("files")
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range ents {
		if !used[e.Name()] {
			t.Errorf("%s 嵌在二进制里却没有任何档位用它 —— 白占体积,还每次起播落一次盘", e.Name())
		}
	}
}

// 六档,而且顺序就是 A B C A+A B+B C+A(用户 2026-09-07 点名的那六个)。
func Test档位就是官方那六个(t *testing.T) {
	want := []string{"off", "ak_a", "ak_b", "ak_c", "ak_aa", "ak_bb", "ak_ca"}
	got := Levels()
	if len(got) != len(want) {
		t.Fatalf("该有 %d 项(含关闭),实得 %d: %v", len(want), len(got), got)
	}
	for i, id := range want {
		if got[i].ID != id {
			t.Errorf("第 %d 项该是 %s,实得 %s", i, id, got[i].ID)
		}
	}
	// 只剩 Anime4K 一族(用户 2026-09-02 拍板)。每多一族,换渲染后端就多一族要真机重验
	for _, lv := range got {
		if lv.ID != "off" && lv.Group != "Anime4K" {
			t.Errorf("档位 %s 的家族是 %q,该只剩 Anime4K", lv.ID, lv.Group)
		}
	}
	// 档位表和 preset 表要一一对应,两边都不许多出来
	for _, tier := range allTiers {
		ps := presetsFor(tier)
		for _, lv := range got {
			if lv.ID == "off" {
				continue
			}
			if _, ok := ps[lv.ID]; !ok {
				t.Errorf("[%s] 档位 %s 在列表里却没有 preset —— 点了等于什么都没发生", tier, lv.ID)
			}
		}
		for id := range ps {
			if !containsLevel(got, id) {
				t.Errorf("[%s] preset %s 没出现在 Levels() 里 —— 用户永远选不到它", tier, id)
			}
		}
	}
}

// 平台档是**构建标签**定的,不是运行期判断。
//
// 本机是桌面构建,所以必须是 high;安卓那一半靠 tier_android.go ——
// 两个文件的标签互斥,少了任何一个,那个平台会**编译失败**(undefined: platformTier),
// 而不是静默退回某个默认值。这条只钉本机这一侧。
func Test本机平台档是high(t *testing.T) {
	if CurrentTier() != TierHigh {
		t.Fatalf("桌面构建的平台档该是 high,实得 %s —— 构建标签接错了", CurrentTier())
	}
}

// ---------------------------------------------------------------- 小工具

// worksAtAnySizeIn 是 WorksAtAnySize 的指定平台档版本(公开的那个只看本机构建档)。
func worksAtAnySizeIn(tier Tier, level string) bool {
	p, ok := presetsFor(tier)[level]
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

func containsLevel(all []Level, id string) bool {
	for _, l := range all {
		if l.ID == id {
			return true
		}
	}
	return false
}

// paramsOf 一个 shader 文件声明了哪些 `//!PARAM`。
func paramsOf(file string) []string {
	var out []string
	for _, l := range strings.Split(bodyOf(file), "\n") {
		if v, ok := strings.CutPrefix(strings.TrimSpace(l), "//!PARAM "); ok {
			out = append(out, strings.TrimSpace(v))
		}
	}
	return out
}
