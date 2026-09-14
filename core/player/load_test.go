package player

import (
	"errors"
	"strconv"
	"strings"
	"testing"
)

// fakeMpv 按某个 mpv 版本的 loadfile 语法校验参数,不合法就像真 mpv 一样拒掉(-4)。
//
//	0.38 起:loadfile <url> [<flags> [<index> [<options>]]]
//	0.37 及以前:loadfile <url> [<flags> [<options>]]
//
// 语法原文见 mpv 仓库 DOCS/man/input.rst 的 v0.37.0 / v0.38.0 两个 tag。
func fakeMpv(hasIndex bool, calls *[][]string) func(args ...string) error {
	return func(args ...string) error {
		*calls = append(*calls, append([]string(nil), args...))
		if len(args) == 0 || args[0] != "loadfile" {
			return nil
		}
		rest := args[3:]
		if hasIndex {
			if len(rest) >= 1 {
				if _, err := strconv.Atoi(rest[0]); err != nil {
					return errors.New("-4 invalid parameter")
				}
			}
			return nil
		}
		// 旧语法:第 3 位就是选项,必须是 k=v
		if len(rest) > 1 || (len(rest) == 1 && !strings.Contains(rest[0], "=")) {
			return errors.New("-4 invalid parameter")
		}
		return nil
	}
}

// ☠ 「移动端 mpv 内核:有观看记录的片子播不了」的门禁(2026-09-14)。
//
// 安卓打包的 libmpv 是 v0.36.0-549,没有 index 那一格;桌面端是 0.38 以后的。
// 同一句 `loadfile url replace -1 start=…` 桌面好好的,安卓当场 -4 ——
// 而且只有带续播进度的条目会拼出第 4 段,从头放的片子完全不受影响。
func Test续播起播在有没有index的两种mpv上都能过(t *testing.T) {
	old := mpvCommand
	defer func() { mpvCommand = old }()

	for _, tc := range []struct {
		name     string
		hasIndex bool
	}{{"mpv 0.38+(桌面)", true}, {"mpv 0.36(安卓)", false}} {
		t.Run(tc.name, func(t *testing.T) {
			resetLoadfileSyntax()
			var calls [][]string
			mpvCommand = fakeMpv(tc.hasIndex, &calls)
			if err := loadWith("http://h/a.mkv", 1234.5, nil, ""); err != nil {
				t.Fatalf("带续播位置起播失败: %v(发出去的命令: %q)", err, calls)
			}
			last := loadfileCalls(calls)
			if got := last[len(last)-1]; got[len(got)-1] != "start=1234.500" {
				t.Fatalf("续播位置没交给 mpv: %q", got)
			}
			// 第二次起播要直接用上一次试出来的那种写法,不能每次都先挨一次拒绝
			calls = nil
			if err := loadWith("http://h/b.mkv", 99, nil, ""); err != nil {
				t.Fatalf("第二次起播失败: %v", err)
			}
			if n := len(loadfileCalls(calls)); n != 1 {
				t.Fatalf("第二次起播发了 %d 条 loadfile,应当一次就对: %q", n, calls)
			}
		})
	}
}

func loadfileCalls(calls [][]string) [][]string {
	var out [][]string
	for _, c := range calls {
		if len(c) > 0 && c[0] == "loadfile" {
			out = append(out, c)
		}
	}
	return out
}

// ☠ 「继续观看里的片子一点就 loadfile 失败」的门禁。
//
// mpv 的 loadfile 签名是 `loadfile <url> [<flags> [<index> [<options>]]]` ——
// **第 3 个位置是 index,不是选项**。把 `start=…` 直接拼在 `replace` 后面,
// 真 mpv 回 -4(invalid parameter),而这条路**只有带续播进度的条目才会走到**。
//
// 所以这条测试钉的不是「有没有 start=」,是**它排在第几段**。
// 只断言「参数里含 start=」的话,那个 bug 照样绿。
func Test续播的loadfile参数里start不能占掉index那一格(t *testing.T) {
	// 从头看:三段,不带 start=
	if got := loadArgs("http://h/a.mkv", 0, false); len(got) != 3 {
		t.Fatalf("从头看应当是三段,实得 %d 段: %q", len(got), got)
	}

	got := loadArgs("http://h/a.mkv", 123.5, false)
	if len(got) != 5 {
		t.Fatalf("带续播位置时应当是五段(多一格 index),实得 %d 段: %q", len(got), got)
	}
	// ★ 关键的一格:第 3 位必须是 index,不能是 start=
	if strings.HasPrefix(got[3], "start=") {
		t.Fatalf("start= 落在了 index 那一格 —— 真 mpv 会回 -4 invalid parameter: %q", got)
	}
	if got[3] != "-1" {
		t.Fatalf("index 那一格应当是 -1(追加到末尾),实得 %q: %q", got[3], got)
	}
	if got[4] != "start=123.500" {
		t.Fatalf("选项那一格不对: %q", got[4])
	}
}
