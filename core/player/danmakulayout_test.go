package player

import (
	"strings"
	"testing"
)

func testStyle() dmStyle {
	return dmStyle{
		Area: 1.0, Scale: 1.0, Opacity: 1.0, Speed: 1.0,
		TopLines: 10, BottomLines: 10,
	}
}

// 同一条轨道上,两条滚动弹幕**不许在屏幕上同时出现却撞在一起**。
//
// 这是分轨的全部意义。判据取「后一条进场时,前一条的尾巴必须已经进了画面」——
// 不满足就是屏幕上两行字叠在一起。
func TestRollLanesNeverCollide(t *testing.T) {
	in := make([]danmakuItem, 0, 200)
	for i := 0; i < 200; i++ {
		in = append(in, danmakuItem{
			Time: float64(i) * 0.05, Mode: 1, Count: 1,
			Text: strings.Repeat("弹", 8+i%20),
		})
	}
	s := testStyle()
	out := layout(in, s)
	if len(out) == 0 {
		t.Fatal("一条都没排上,分轨等于把弹幕全丢了")
	}
	roll := rollSeconds / s.Speed
	last := map[int]danmakuItem{}
	for _, d := range out {
		prev, ok := last[d.lane]
		if ok {
			// 前一条的尾巴进画面的时刻
			tailIn := prev.Time + prev.w/(float64(danmakuResX)+prev.w)*roll
			if d.Time < tailIn-1e-9 {
				t.Fatalf("第 %d 轨撞了:前一条 %.3fs 的尾巴要到 %.3fs 才进画面,后一条 %.3fs 就来了",
					d.lane, prev.Time, tailIn, d.Time)
			}
		}
		last[d.lane] = d
	}
}

// 显示范围调小 = 轨道数跟着少。四分之一屏时轨道号不许越过 1080*0.25/54。
func TestAreaLimitsRollLanes(t *testing.T) {
	// 密到一定挤满:间隔 0.02 秒,而一条长弹幕要 0.5 秒尾巴才进画面
	in := make([]danmakuItem, 0, 300)
	for i := 0; i < 300; i++ {
		in = append(in, danmakuItem{
			Time: float64(i) * 0.02, Mode: 1, Count: 1, Text: strings.Repeat("长", 20),
		})
	}
	s := testStyle()
	s.Area = 0.25
	max := 0
	for _, d := range layout(in, s) {
		if d.lane > max {
			max = d.lane
		}
	}
	want := int(float64(danmakuResY)*0.25/laneHeight) - 1 // = 4
	if max > want {
		t.Fatalf("四分之一屏该只有 %d..%d 轨,实得最大 %d 轨", 0, want, max)
	}
	if max == 0 {
		t.Fatal("只用了一条轨:范围限制生效过头了,弹幕会全挤在一行")
	}
}

// 置顶行数设成 0 = 这一类**一条都不显示**。
//
// ★ 这一条最容易写错成「回默认值 10」——0 在别的数值项上确实是「没设」,
//   但在行数上它是用户真会选的一档。
func TestZeroTopLinesDropsTopDanmaku(t *testing.T) {
	in := []danmakuItem{
		{Time: 1, Mode: 5, Count: 1, Text: "顶"},
		{Time: 2, Mode: 4, Count: 1, Text: "底"},
		{Time: 3, Mode: 1, Count: 1, Text: "滚"},
	}
	s := testStyle()
	s.TopLines = 0
	for _, d := range layout(in, s) {
		if d.Mode == 5 {
			t.Fatal("置顶行数是 0,顶部弹幕还是排上了")
		}
	}
	// 另外两类不许受连累
	kinds := map[int]bool{}
	for _, d := range layout(in, s) {
		kinds[d.Mode] = true
	}
	if !kinds[4] || !kinds[1] {
		t.Fatalf("关掉置顶把别的类型也带走了:实得 %v", kinds)
	}
}

// 合并重复弹幕:窗口内同文本同类型合成一条,带 xN。
func TestMergeSameCounts(t *testing.T) {
	in := []danmakuItem{
		{Time: 1.0, Mode: 1, Count: 1, Text: "草"},
		{Time: 2.0, Mode: 1, Count: 1, Text: "草"},
		{Time: 3.0, Mode: 1, Count: 1, Text: "草"},
		{Time: 30.0, Mode: 1, Count: 1, Text: "草"}, // 窗口外,自成一条
		{Time: 1.5, Mode: 5, Count: 1, Text: "草"}, // 类型不同,不合并
	}
	out := mergeSame(in, 10.0)
	if len(out) != 3 {
		t.Fatalf("该合成 3 条(窗口内的 3 条草 / 窗口外那条 / 顶部那条),实得 %d 条", len(out))
	}
	got := map[string]int{}
	for _, d := range out {
		got[displayText(d)]++
	}
	if got["草 ×3"] != 1 {
		t.Fatalf("没合出「草 ×3」,实得 %v", got)
	}
	if got["草"] != 2 {
		t.Fatalf("窗口外那条和顶部那条不该带次数,实得 %v", got)
	}
}

// 热力图归一化到峰值:最热那一格恰好是 1,其余都在 [0,1]。
func TestHeatmapNormalizes(t *testing.T) {
	styMu.Lock()
	dmRaw = []danmakuItem{
		{Time: 1}, {Time: 2}, // 前 10% 两条
		{Time: 55}, {Time: 56}, {Time: 57}, {Time: 58}, // 中间四条 = 峰
	}
	styMu.Unlock()
	h := danmakuHeatmap(10, 100)
	if len(h) != 10 {
		t.Fatalf("要 10 格,实得 %d 格", len(h))
	}
	peak := 0.0
	for _, v := range h {
		if v < 0 || v > 1 {
			t.Fatalf("密度越界:%v", h)
		}
		if v > peak {
			peak = v
		}
	}
	if peak != 1.0 {
		t.Fatalf("最热那一格该正好是 1,实得 %.3f(%v)", peak, h)
	}
	if h[5] != 1.0 {
		t.Fatalf("峰该落在第 5 格(55~60 秒),实得 %v", h)
	}
}

// ASS 的颜色是 BGR,弹幕源给的是 RGB。红色进去必须是 &H0000FF& 出来。
//
// ☠ 白色和灰色换不换都一样,所以这个 bug 能活很久 —— 判据必须用纯红。
func TestBuildLayersSwapsRgbToBgr(t *testing.T) {
	styMu.Lock()
	sty = testStyle()
	styMu.Unlock()
	dmMu.Lock()
	dmItems = []danmakuItem{{Time: 0, Mode: 5, Count: 1, Text: "红", Color: 0xFF0000, w: 26}}
	dmMu.Unlock()

	_, fix := buildLayers(0.5)
	if !strings.Contains(fix, `\c&H0000FF&`) {
		t.Fatalf("红色(RGB FF0000)该写成 ASS 的 &H0000FF&,实得:%s", fix)
	}
}

// 透明度 / 粗体 / 缩放要真的写进 ASS 标签里。
func TestBuildLayersAppliesStyle(t *testing.T) {
	s := testStyle()
	s.Opacity = 0.5
	s.Bold = true
	s.Scale = 2.0
	styMu.Lock()
	sty = s
	styMu.Unlock()
	dmMu.Lock()
	dmItems = []danmakuItem{{Time: 0, Mode: 5, Count: 1, Text: "样", Color: 0xFFFFFF, w: 52}}
	dmMu.Unlock()

	_, fix := buildLayers(0.5)
	for _, want := range []string{`\fs80`, `\b1`, `\alpha&H7F&`} {
		if !strings.Contains(fix, want) {
			t.Fatalf("ASS 里没写 %s,实得:%s", want, fix)
		}
	}
}
