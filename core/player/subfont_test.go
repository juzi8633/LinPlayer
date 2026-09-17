package player

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 拿本机真字体验族名读取:Windows 上雅黑是 .ttc(多张脸),正好把集合字体那条路也走到。
// 没这个文件的机器(CI 的 Linux)跳过 —— 解析逻辑和平台无关,Windows 上跑到就算数。
func TestFontFamilies读真字体(t *testing.T) {
	f, err := os.Open(`C:\Windows\Fonts\msyh.ttc`)
	if err != nil {
		t.Skip("本机没有 msyh.ttc")
	}
	defer f.Close()
	got := fontFamilies(f)
	if len(got) < 2 || got[0] != "Microsoft YaHei" {
		t.Fatalf("雅黑集合字体读出来是 %q,该有 Microsoft YaHei 和 UI 两张脸", got)
	}
}

func TestSystemSubFont挑有的那个(t *testing.T) {
	src, err := os.ReadFile(`C:\Windows\Fonts\msyh.ttc`)
	if err != nil {
		t.Skip("本机没有 msyh.ttc")
	}
	dir := t.TempDir()
	if systemSubFont(dir) != "" {
		t.Fatal("空目录不该挑出字体 —— 挑出来就是把 sub-font 设成一个不存在的名字")
	}
	// 冒名放进候选文件名里:判据是文件里写的族名,不是文件名
	if err := os.WriteFile(filepath.Join(dir, "DroidSansFallback.ttf"), src, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "NotoSansCJK-Regular.ttc"), []byte("坏文件"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := systemSubFont(dir); got != "Microsoft YaHei" {
		t.Fatalf("得到 %q:坏文件该跳过,落到下一个候选", got)
	}
	if got := fontFamilies(strings.NewReader("ttcf\x00\x01\x00\x00\xff\xff\xff\xff")); len(got) != 0 {
		t.Fatalf("坏的集合头该读不出东西,得到 %q", got)
	}
	if got := preferSC([]string{"Noto Sans CJK JP", "Noto Sans CJK SC"}); got != "Noto Sans CJK SC" {
		t.Fatalf("集合字体该挑 SC 那张脸,得到 %q", got)
	}
}
