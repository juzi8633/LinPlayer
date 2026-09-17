package player

import (
	"testing"

	"linplayer/core/config"
)

// 安卓 mpv 内核「字幕显示出问题」那次查出来的(2026-09-17):选轨偏好从没在起播时应用过。
func TestChooseTracks起播选字幕(t *testing.T) {
	ext := []Track{
		{ID: "1", Kind: "audio"},
		{ID: "1", Kind: "sub", Title: "内封 英文", Lang: "eng"},
		{ID: "2", Kind: "sub", Title: "简体中文", External: true},
	}
	on := config.DefaultPrefs()
	off := config.DefaultPrefs()
	off.SubEnabled = false
	zh := config.DefaultPrefs()
	zh.SubRegex = "简体"

	for _, tc := range []struct {
		name   string
		tracks []Track
		p      config.Prefs
		want   string
	}{
		{"关了字幕就显式 no,不留上一片的轨", ext, off, "no"},
		{"开着、没偏好、mpv 一条没选 → 补选一条(外挂字幕 sid=auto 永远选不上)", ext, on, "1"},
		{"正则优先", ext, zh, "2"},
		{"mpv 已经按 default 标记选了就不动", []Track{{ID: "1", Kind: "sub"}, {ID: "2", Kind: "sub", Selected: true}}, on, ""},
		{"没有字幕轨就不设", []Track{{ID: "1", Kind: "audio"}}, on, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got, _ := chooseTracks(tc.tracks, tc.p); got != tc.want {
				t.Fatalf("sid = %q,该是 %q", got, tc.want)
			}
		})
	}
}
