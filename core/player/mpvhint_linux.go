//go:build linux && !android

package player

// Linux 不随包带 libmpv(见 mpv_dlopen_linux.c),起不来时多半是系统没装 —— 直接给安装命令
const mpvDownMsg = "mpv 起不来:系统里没找到 libmpv。" +
	"Debian/Ubuntu 装 libmpv2(Ubuntu 22.04 叫 libmpv1),Fedora 装 mpv-libs,Arch 装 mpv"
