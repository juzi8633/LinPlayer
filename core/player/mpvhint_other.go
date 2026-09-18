//go:build !linux || android

package player

const mpvDownMsg = "mpv 起不来"

// desktopOptions:Windows 没有专属选项(安卓走 surface_android.go,不经过这里)。
func desktopOptions() [][2]string { return nil }
