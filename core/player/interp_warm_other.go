//go:build !windows

package player

import (
	"context"
	"errors"
)

// warmTRT 见 interp_warm_windows.go。
func warmTRT(context.Context, func(i, n int)) error {
	return errors.New("这个平台没有 N 卡加速包")
}
