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

// 档位表里引用的每个文件都必须真的嵌进来了 —— 否则**运行时才炸**(超分点了没反应)。
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

// ★★ 本档 opts 里写的每个参数名,都必须真的存在于**本档挂载的 shader** 里。
//
// 这是「强度烧进档位」这个设计唯一会静默失效的地方:
// **mpv 遇到不认识的参数名会把整条 glsl-shader-opts 拒掉** ——
// 于是锐化悄悄回到 shader 自带默认(只开一半),
// 正是用户「看不太出来」的那个状态,**而且不报错**。
func TestEveryPresetOptNamesAParamThisPresetLoads(t *testing.T) {
	for _, tier := range allTiers {
		for id, p := range presetsFor(tier) {
			var available []string
			for _, f := range p.Files {
				available = append(available, ParamsOf(f)...)
			}
			for _, kv := range splitOpts(p.Opts) {
				key, _, _ := strings.Cut(kv, "=")
				key = strings.TrimSpace(key)
				if !contains(available, key) {
					t.Errorf("[%s] 档位 %s 的参数 %q 不属于它挂载的任何 shader —— "+
						"mpv 会把整条 opts 拒掉,强度静默回到默认,而且不报错。可用的:%v",
						tier, id, key, available)
				}
			}
		}
	}
}

// 每个 opts 的值都要落在 shader 声明的区间里。
//
// 越界值 mpv 同样是静默处理 —— 要么钳、要么整条拒,两种都让「强档」变成「默认档」。
func TestEveryPresetOptValueIsInRange(t *testing.T) {
	for _, tier := range allTiers {
		for id, p := range presetsFor(tier) {
			for _, kv := range splitOpts(p.Opts) {
				key, val, _ := strings.Cut(kv, "=")
				key, val = strings.TrimSpace(key), strings.TrimSpace(val)
				v, err := strconv.ParseFloat(val, 64)
				if err != nil {
					t.Errorf("[%s] %s 的 %s 不是数字", tier, id, kv)
					continue
				}
				owner := ""
				for _, f := range p.Files {
					if contains(ParamsOf(f), key) {
						owner = f
						break
					}
				}
				if owner == "" {
					continue // 上一条测试已经报过了
				}
				min, max, ok := paramRange(owner, key)
				if !ok {
					continue // 这个 shader 没声明区间,放行
				}
				if v < min || v > max {
					t.Errorf("[%s] 档位 %s 的 %s=%v 超出 %s 声明的区间 [%v, %v]",
						tier, id, key, v, owner, min, max)
				}
			}
		}
	}
}

// ★★ 每档**只能挂一个锐化器**。
//
// Adaptive / aWarpSharp2 / BCAS 都叫 `STR`,而 `glsl-shader-opts` 是**全局**的 ——
// 叠在同一档里会共用一个值、量纲还不同(0~2 / -20~20 / 0~1),
// **必然串味且不报错**。
func TestSharpenPresetsLoadOnlyOneSharpener(t *testing.T) {
	sharpeners := map[string]bool{
		"Adaptive_sharpen_lite_luma_RT.glsl": true,
		"aWarpSharp2_RT.glsl":                true,
		"AMD_BCAS_RT.glsl":                   true,
		"AMD_CAS_luma_RT.glsl":               true,
	}
	for _, tier := range allTiers {
		for id, p := range presetsFor(tier) {
			var got []string
			for _, f := range p.Files {
				if sharpeners[f] {
					got = append(got, f)
				}
			}
			if len(got) > 1 {
				t.Errorf("[%s] 档位 %s 挂了 %d 个共用 STR 的锐化器 %v —— "+
					"glsl-shader-opts 是全局的,它们会共用一个值而量纲不同,"+
					"必然串味且不报错", tier, id, len(got), got)
			}
		}
	}
}

// ☠☠ **每一档都必须在任意尺寸下有可见效果**【用户定 2026-09-07:「哪怕开了也看不出效果」】。
//
// 上一版有五档是纯 CNN 放大,在 1080p 屏上播 1080p 时一帧都不跑 ——
// 用户点了、UI 说生效了、画面一点没变。现在每档都带一个不挑尺寸的锐化 pass,
// 窗口里退化成只锐化,全屏才补上放大。**这条红了就是那个 bug 回来了。**
func TestEveryLevelDoesSomethingAtAnySize(t *testing.T) {
	for _, tier := range allTiers {
		for id := range presetsFor(tier) {
			if !worksAtAnySizeIn(tier, id) {
				t.Errorf("[%s] 档位 %s 在窗口尺寸下一个 pass 都不跑 —— "+
					"用户会看到「已启用」而画面毫无变化", tier, id)
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

// ★★ 移动端封顶在 medium【用户定 2026-09-07:「移动端最高到 medium,PC 最高到 high」】。
//
// 手机 GPU 是集显且有温度墙,VL 那条链就是用户说的「超级无敌卡」——
// 卡在着色器这一段,和解码软硬无关(安卓这边 hwdec 走 MediaCodec)。
func TestMobileNeverLoadsTheHeavyChain(t *testing.T) {
	heavy := map[string]bool{upVL: true, downPre2: true, downPre4: true}
	for id, p := range presetsFor(TierMedium) {
		for _, f := range p.Files {
			if heavy[f] {
				t.Errorf("移动端档位 %s 挂了 %s —— 手机 GPU 上这条链会卡到个位数帧率", id, f)
			}
		}
	}
	// 反过来:桌面最高那档要真的用上重链,否则「PC 到 high」是句空话
	top := presetsFor(TierHigh)["ak_ca"]
	if !contains(top.Files, upVL) {
		t.Errorf("桌面 ak_ca 该挂 %s,实得 %v", upVL, top.Files)
	}
}

// ★★ 「挂上了」和「会跑」是两件事。
func TestWillRun(t *testing.T) {
	// 六档都不挑尺寸,尺寸未知也该判 true
	for _, id := range []string{"ak_a", "ak_b", "ak_c", "ak_aa", "ak_bb", "ak_ca"} {
		if run, ok := WillRun(id, 0, 0, 0, 0); !ok || !run {
			t.Errorf("%s 该在任意尺寸下有效果,实得 run=%v ok=%v", id, run, ok)
		}
	}
	// off / 未知
	if _, ok := WillRun("off", 1920, 1080, 3840, 2160); ok {
		t.Error("off 不该有结论")
	}
	if _, ok := WillRun("根本没这档", 1920, 1080, 3840, 2160); ok {
		t.Error("未知档位不该有结论")
	}
}

// 放大那半单独有个判据:答的是「你拿到的是完整效果还是退化版」。
func TestUpscaleWillRun(t *testing.T) {
	if run, ok := UpscaleWillRun("ak_aa", 1920, 1080, 1770, 1080); !ok || run {
		t.Errorf("窗口 1770×1080 播 1920×1080 时放大那半不该跑,实得 run=%v ok=%v", run, ok)
	}
	if run, ok := UpscaleWillRun("ak_aa", 1920, 1080, 3840, 2160); !ok || !run {
		t.Errorf("全屏 4K 播 1080p 时放大那半该跑,实得 run=%v ok=%v", run, ok)
	}
	// 恰好 1.2 倍:不算(源里写的是**严格大于**)
	if run, _ := UpscaleWillRun("ak_aa", 1000, 1000, 1200, 1200); run {
		t.Error("恰好 1.2 倍不该跑 —— shader 里写的是严格大于")
	}
	// 没有放大 pass 的档位:不下结论,而不是回一个 false
	if _, ok := UpscaleWillRun("ak_a", 1920, 1080, 3840, 2160); ok {
		t.Error("ak_a 没有放大 pass,不该给结论")
	}
	// 尺寸未知
	if _, ok := UpscaleWillRun("ak_aa", 0, 0, 0, 0); ok {
		t.Error("尺寸未知时不该下结论")
	}
}

// WhenRatio 这个常量必须和 shader 源里写死的门槛一致。
//
// 从**嵌入的源**里抠出来比对,而不是相信注释 —— 换 shader 文件时这条会红。
func TestWhenRatioMatchesShaderSource(t *testing.T) {
	body := bodyOf(upM)
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

// ★ Restore CNN **不许加回来**。
//
// 用户 2026-07-11 明确否掉:动态画面边缘振铃 / 拖影,且最吃显卡。
// 2026-09-07 他说的 A/B/C 是**强度三档**,不是 Anime4K 官方那三种 Restore 模式。
func TestRestoreCNNStaysGone(t *testing.T) {
	for _, tier := range allTiers {
		for id, p := range presetsFor(tier) {
			for _, f := range p.Files {
				if strings.Contains(strings.ToLower(f), "restore") {
					t.Errorf("[%s] 档位 %s 又把 Restore CNN 加回来了(%s)—— "+
						"用户两次明确否掉:边缘振铃/拖影,且最吃显卡", tier, id, f)
				}
			}
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
	target := m1[sharpen]
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
	got, err := Paths(dir, "ak_b")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || !strings.Contains(got[0], "sharpen") {
		t.Fatalf("ak_b 该只有锐化一个 pass,实得 %v", got)
	}
	// 带放大那档:顺序就是 pipeline,先放大再锐化,反了等于锐化了一张没放大的图
	up, err := Paths(dir, "ak_aa")
	if err != nil {
		t.Fatal(err)
	}
	if len(up) < 2 || !strings.Contains(up[len(up)-1], "sharpen") {
		t.Fatalf("ak_aa 该以锐化收尾,实得 %v", up)
	}
	for _, p := range append(got, up...) {
		if !filepath.IsAbs(p) {
			t.Errorf("mpv 的 glsl-shaders 只收绝对路径: %s", p)
		}
	}
	// off / 未知 = 空列表(把上一档关掉)
	for _, id := range []string{"off", "根本没这档"} {
		if g, err := Paths(dir, id); err != nil || len(g) != 0 {
			t.Errorf("%s 该给空列表,实得 %v %v", id, g, err)
		}
	}
}

// 锐化永远在链尾:它 hook 的是 LUMA,放在放大前面等于锐化了一张还没放大的图。
func TestSharpenIsAlwaysLast(t *testing.T) {
	for _, tier := range allTiers {
		for id, p := range presetsFor(tier) {
			if len(p.Files) == 0 || p.Files[len(p.Files)-1] != sharpen {
				t.Errorf("[%s] 档位 %s 的最后一个 pass 该是锐化,实得 %v", tier, id, p.Files)
			}
		}
	}
}

// off 的 opts 是空串 —— 切到 off 时要**顺带把上一档的参数清掉**
// (glsl-shader-opts 是全局的,不清的话下一档会吃到上一档留下的值)。
func TestOptsForOff(t *testing.T) {
	if Opts("off") != "" || Opts("根本没这档") != "" {
		t.Error("off / 未知档位的 opts 该是空串")
	}
	if Opts("ak_b") != strMid {
		t.Errorf("档位参数没取对: %q", Opts("ak_b"))
	}
}

// 强度必须**单调递增**:名字承诺了低/中/高,值反了就是骗人。
func TestStrengthLadderIsMonotonic(t *testing.T) {
	for _, tier := range allTiers {
		ps := presetsFor(tier)
		for _, trio := range [][3]string{{"ak_a", "ak_b", "ak_c"}, {"ak_aa", "ak_bb", "ak_ca"}} {
			var vs []float64
			for _, id := range trio {
				_, v, _ := strings.Cut(ps[id].Opts, "=")
				f, err := strconv.ParseFloat(v, 64)
				if err != nil {
					t.Fatalf("[%s] %s 的 opts 取不出数字: %q", tier, id, ps[id].Opts)
				}
				vs = append(vs, f)
			}
			if !(vs[0] < vs[1] && vs[1] < vs[2]) {
				t.Errorf("[%s] %v 的强度不是递增的: %v", tier, trio, vs)
			}
		}
	}
}

// 每个档位引用的 .glsl 都必须真在 embed 里,且档位表与 preset 表一一对应。
//
// 漏改一处引用的话:`bodyOf` 返回空串 → 落一个空文件 → mpv 收下路径之后
// **静默不跑**,不报错、不影响返回码。正是本仓最讨厌的失败形态。
func Test档位与文件一一对应(t *testing.T) {
	for _, tier := range allTiers {
		ps := presetsFor(tier)
		listed := map[string]bool{}
		for _, lv := range Levels() {
			if lv.ID == "off" {
				continue
			}
			listed[lv.ID] = true
			p, ok := ps[lv.ID]
			if !ok {
				t.Errorf("[%s] 档位 %s 在列表里,却没有对应的 preset —— 点了等于什么都没发生", tier, lv.ID)
				continue
			}
			if len(p.Files) == 0 {
				t.Errorf("[%s] 档位 %s 一个 shader 都没挂", tier, lv.ID)
			}
			for _, f := range p.Files {
				if bodyOf(f) == "" {
					t.Errorf("[%s] 档位 %s 引用了 %s,但它不在 embed 里 —— "+
						"会落一个空文件,mpv 静默不跑", tier, lv.ID, f)
				}
			}
		}
		for id := range ps {
			if !listed[id] {
				t.Errorf("[%s] preset %s 没出现在 Levels() 里 —— 用户永远选不到它,"+
					"却还占着二进制体积", tier, id)
			}
		}
	}
}

// 六档,而且顺序就是 A B C A+A B+B C+A(用户 2026-09-07 点名的那六个)。
func Test档位就是用户点名的六个(t *testing.T) {
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

func splitOpts(s string) []string {
	var out []string
	for _, kv := range strings.Split(s, ",") {
		if strings.TrimSpace(kv) != "" {
			out = append(out, kv)
		}
	}
	return out
}

func contains(all []string, v string) bool {
	for _, x := range all {
		if x == v {
			return true
		}
	}
	return false
}

// paramRange 从 shader 源里抠出某个 //!PARAM 的 //!MINIMUM / //!MAXIMUM。
func paramRange(file, param string) (min, max float64, ok bool) {
	lines := strings.Split(bodyOf(file), "\n")
	i := -1
	for n, l := range lines {
		if strings.TrimSpace(l) == "//!PARAM "+param {
			i = n
			break
		}
	}
	if i < 0 {
		return 0, 0, false
	}
	gotMin, gotMax := false, false
	for _, l := range lines[i+1:] {
		t := strings.TrimSpace(l)
		switch {
		case strings.HasPrefix(t, "//!MINIMUM"):
			if v, err := strconv.ParseFloat(strings.TrimSpace(strings.TrimPrefix(t, "//!MINIMUM")), 64); err == nil {
				min, gotMin = v, true
			}
		case strings.HasPrefix(t, "//!MAXIMUM"):
			if v, err := strconv.ParseFloat(strings.TrimSpace(strings.TrimPrefix(t, "//!MAXIMUM")), 64); err == nil {
				max, gotMax = v, true
			}
		case strings.HasPrefix(t, "//!PARAM"), strings.HasPrefix(t, "//!HOOK"):
			return min, max, gotMin && gotMax // 到下一个声明为止
		}
	}
	return min, max, gotMin && gotMax
}
