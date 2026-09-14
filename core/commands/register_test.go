package commands

import (
	"testing"

	"linplayer/core/bus"
	"linplayer/core/companion"
)

// 手机遥控的白名单是一串命令名字符串,拼错了编译器不管,
// 表现是手机上点了只回「没有这条命令」。拿真注册表对一遍。
func TestCompanion白名单里的命令都真的注册了(t *testing.T) {
	RegisterAll("test")
	have := map[string]bool{}
	for _, c := range bus.Commands() {
		have[c] = true
	}
	for _, c := range companion.AllowedCommands() {
		if !have[c] {
			t.Errorf("手机遥控白名单里的 %s 没有注册", c)
		}
	}
}
