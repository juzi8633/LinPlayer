//go:build !windows

package interp

import "errors"

// Supported 只有 Windows 有补帧。安卓的调研结论见 docs/lessons/player-mpv.md「补帧」。
const Supported = false

var errUnsupported = errors.New("这个平台还没有补帧")

// Adapter 见 gpu_windows.go。
type Adapter struct {
	Index int
	Name  string
	MB    int
}

func PickAdapter() (Adapter, error) { return Adapter{}, errUnsupported }
func Preload() error                { return errUnsupported }
func DisplayHz() float64            { return 0 }
