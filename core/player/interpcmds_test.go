package player

import "testing"

// seek 会把 mpv 的丢帧计数清零。累计值必须单调,不然量窗里一次跳转就把真丢的帧抵掉。
func TestDropCounter跨seek累计(t *testing.T) {
	var c dropCounter
	var got float64
	for _, now := range []float64{0, 5, 19, 0, 3, 10} { // 19 之后 seek 清零
		got = c.add(now)
	}
	if got != 29 {
		t.Fatalf("累计丢帧应为 19+10=29,实得 %v", got)
	}
}

// 用户在剧里选的补帧档记到剧上,不动全局;在剧外选的记全局。和画面增强同一套记法。
func TestInterp按剧记住(t *testing.T) {
	g, m := "drba_2", map[string]string{}
	if !rememberScoped(&g, &m, "srv|s1", "rife_3") || m["srv|s1"] != "rife_3" || g != "drba_2" {
		t.Fatalf("剧内选择记错了:global=%q map=%v", g, m)
	}
	if scopedLevel(g, m, "srv|s1") != "rife_3" || scopedLevel(g, m, "srv|s2") != "drba_2" {
		t.Fatal("取档不对:记过的剧用剧的,没记过的用全局")
	}
	if rememberScoped(&g, &m, "srv|s1", "rife_3") {
		t.Fatal("没变化不该回 true(会白写一次盘)")
	}
}
