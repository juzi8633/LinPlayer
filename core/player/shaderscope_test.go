package player

import (
	"testing"

	"linplayer/core/config"
	"linplayer/core/history"
)

// 用户 2026-09-17:「在一个番剧里面选了,后续都是使用该模型进行画面增强」。
func TestShader按剧记(t *testing.T) {
	p := config.DefaultPrefs()
	p.ShaderLevel = "ak_sharp"
	a, b := "srv|series-a", "srv|series-b"

	if !rememberInto(&p, a, "ak_mode_a") {
		t.Fatal("第一次给这部剧选档位该落盘")
	}
	if rememberInto(&p, a, "ak_mode_a") {
		t.Fatal("没变化不该再落一次盘")
	}
	if got := shaderLevelFor(p, a); got != "ak_mode_a" {
		t.Fatalf("同一部剧下一集该是 ak_mode_a,得到 %q", got)
	}
	if got := shaderLevelFor(p, b); got != "ak_sharp" {
		t.Fatalf("没记过的剧该用全局那档,得到 %q —— 一部剧的选择漏到了别的剧上", got)
	}
	if p.ShaderLevel != "ak_sharp" {
		t.Fatalf("在剧里选不该改全局,全局变成了 %q", p.ShaderLevel)
	}
	// 在剧里关掉也是一种选择,要记住,不能回落到全局那档
	rememberInto(&p, a, "off")
	if got := shaderLevelFor(p, a); got != "off" {
		t.Fatalf("剧里关掉之后该是 off,得到 %q", got)
	}
	// 电影 / 本地文件:scope 为空,记到全局
	rememberInto(&p, "", "ak_mode_b")
	if p.ShaderLevel != "ak_mode_b" || shaderLevelFor(p, "") != "ak_mode_b" {
		t.Fatalf("剧外选的该记到全局,得到 %q", p.ShaderLevel)
	}
}

func TestSeriesScope只认剧集(t *testing.T) {
	sid := "s1"
	ep := &historyContext{candidate: history.Candidate{SeriesID: &sid}}
	if got := seriesScope("http://h", ep); got != "http://h|s1" {
		t.Fatalf("剧集的 scope 该是 服务器|剧 id,得到 %q", got)
	}
	empty := ""
	for _, h := range []*historyContext{nil, {}, {candidate: history.Candidate{SeriesID: &empty}}} {
		if got := seriesScope("http://h", h); got != "" {
			t.Fatalf("电影 / 判据没取到时该回空串,得到 %q", got)
		}
	}
}
