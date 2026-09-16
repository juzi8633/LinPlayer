//go:build !windows

package system

import "os/exec"

// hideConsole 非 Windows 上没有「控制台窗口」这回事,sh 也不开窗。
func hideConsole(*exec.Cmd) {}
