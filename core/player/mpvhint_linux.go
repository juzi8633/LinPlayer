//go:build linux && !android

package player

// Linux 不随包带 libmpv(见 mpv_dlopen_linux.c),起不来时多半是系统没装 —— 直接给安装命令
const mpvDownMsg = "mpv 起不来:系统里没找到 libmpv。" +
	"Debian/Ubuntu 装 libmpv2(Ubuntu 22.04 叫 libmpv1),Fedora 装 mpv-libs,Arch 装 mpv"

// desktopOptions:Linux 的显存互通只留 vaapi。
//
// ☠ issue #65:Ubuntu 26.04 + 英特尔核显 / NVIDIA 独显 + Wayland,一开就 SIGSEGV,
// 崩前最后加载的是 libcuda.so.1。libmpv 建渲染上下文时默认**预加载全部**互通,
// CUDA 那个要把 GL 上下文绑到 N 卡上 —— 而界面的 GL 跑在核显(XWayland/Mesa)上,
// 崩在驱动里,我们接不住。只留 vaapi:核显/A 卡照样零拷贝;N 卡由 hwdec=auto 落到 *-copy。
func desktopOptions() [][2]string {
	return [][2]string{{"gpu-hwdec-interop", "vaapi"}}
}
