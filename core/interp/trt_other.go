//go:build !windows

package interp

import (
	"context"
	"errors"
)

// N 卡加速包只有 Windows 有(见 trt_windows.go)。

// Nvidia 见 trt_windows.go。
type Nvidia struct {
	Name, ComputeCap string
	Driver           float64
}

func NvidiaGPU() Nvidia                       { return Nvidia{} }
func TRTOffer(Nvidia) (string, int64, string) { return "", 0, "" }
func TRTReady() bool                          { return false }
func TRTInstalled() bool                      { return false }
func InstallTRT(context.Context, Progress, func(string)) error {
	return errors.New("这个平台没有 N 卡加速包")
}
func EngineBuilt(string) bool { return false }
func EnginesBuilt() bool      { return false }
func MarkEnginesReady() error { return nil }
