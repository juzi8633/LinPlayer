//go:build !windows && !android

package interp

import "errors"

// Supported Linux 还没有补帧(PC 的 vapoursynth 方案要换 Linux 运行时,没做)。
const Supported = false

// NeedsRuntime 见 vs_windows.go。
const NeedsRuntime = false

var algos []algo

// Device 见 vs_windows.go。
func Device() (string, int, error) { return "", 0, errors.New("这个平台还没有补帧") }

// Preload 见 vs_windows.go。
func Preload() error { return errors.New("这个平台还没有补帧") }

// DisplayHz 见 vs_windows.go。
func DisplayHz() float64 { return reported() }

// FilterString 见 vs_windows.go。
func FilterString(scriptPath string, spec Spec, gpu int) string { return "" }

// ScriptPath 见 vs_windows.go。
func ScriptPath() (string, error) { return "", errors.New("这个平台还没有补帧") }
