package system

// 几条零散的系统命令。

import (
	"context"
	"os/exec"
	"path/filepath"
	"runtime"

	"linplayer/core/bus"
	"linplayer/core/paths"
)

func registerMiscCommands() {
	// system.openDataDir —— 在系统文件管理器里打开数据目录。
	//
	// ★ sub 只认**白名单**里那几个:直接把用户传的路径拼上去等于给了一个
	//   「用文件管理器打开任意目录」的口子。
	bus.Register("system.openDataDir", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		sub, _ := a["sub"].(string)
		var p string
		switch sub {
		case "logs":
			p = paths.LogsDir()
		case "downloads":
			p = paths.DownloadsDir()
		case "cache":
			p = paths.CacheDir()
		default:
			p = paths.Root()
		}
		if err := openPath(filepath.Clean(p)); err != nil {
			return nil, bus.NewErr(bus.EInternal, "打开目录失败: %v", err)
		}
		return nil, nil
	})
}

// openPath 用系统默认方式打开一个路径。
//
// ★ 核心层是个库,弹不了对话框 —— 但「用资源管理器打开目录」是起个进程,
// 这个它做得到,而且三端各写一遍反而更容易漏。
func openPath(p string) error {
	switch runtime.GOOS {
	case "windows":
		return exec.Command("explorer", p).Start()
	case "darwin":
		return exec.Command("open", p).Start()
	default:
		return exec.Command("xdg-open", p).Start()
	}
}
