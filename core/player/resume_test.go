package player

import (
	"testing"

	"linplayer/core/history"
)

// 起播位置:调用方没给就用服务器上那份。
//
// ☠ 这条钉的是**安卓两个内核都不续播**的根因(2026-09-07):UI 起播时没传
// resume_secs,而核心层把「没传」当成了「从头放」。判据必须在核心层,
// 不能靠每个调用方各自记得传 —— 漏一处就是那一端整个不续播,而且不报错。
func Test没传续播位置就用服务器上的(t *testing.T) {
	const ticks = 123 * history.TicksPerSec
	if got := resumeFor(0, ticks); got != 123 {
		t.Fatalf("调用方没给时该回落到服务器的 123s,实得 %v", got)
	}
	if got := resumeFor(45, ticks); got != 45 {
		t.Fatalf("调用方给了 45s 就该听它的,实得 %v", got)
	}
	if got := resumeFor(0, 0); got != 0 {
		t.Fatalf("两边都没有就是从头放,实得 %v", got)
	}
}

// 「从头播放」要能真的从头放(UI_TV.md §7.4:「继续」和「从头」并列显式给出)。
// ☠ resume_secs=0 的含义是「我不知道,你来定」—— 于是传 0 的从头播放会被服务端进度顶回续播,
// 两边都不报错。必须有一个单独的信号,而且它要压过服务端进度和跨服续播。
func Test从头播放不被服务端进度顶回去(t *testing.T) {
	if got := resumeArg(map[string]any{"from_start": true, "resume_secs": 30.0}); got >= 0 {
		t.Fatalf("from_start 没生效:resumeArg=%v,要一个负数哨兵", got)
	}
	if got := resumeArg(map[string]any{"resume_secs": 30.0}); got != 30 {
		t.Fatalf("普通续播被改坏了:%v", got)
	}
	if got := resumeArg(map[string]any{}); got != 0 {
		t.Fatalf("不传 = 交给核心层定,应当是 0,得到 %v", got)
	}
}
