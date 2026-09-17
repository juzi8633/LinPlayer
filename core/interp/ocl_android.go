//go:build android

package interp

/*
#cgo LDFLAGS: -ldl
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>

// 和 third_party/mpv-interp/lpinterp_ocl.c 同一份名单、同一个挑卡规则:
// 这里只是「开档之前先问一句」,真正跑的是 libmpv 里的滤镜。两边说法不一致的话,
// 界面给了档,滤镜却在别的库上找 GPU。
static const char *const lp_cl_names[] = {
    "libOpenCL.so", "libOpenCL.so.1", "libOpenCL-pixel.so", "libGLES_mali.so", "libmali.so",
};
typedef int32_t (*get_platforms_fn)(uint32_t, void **, uint32_t *);
typedef int32_t (*get_devices_fn)(void *, uint64_t, uint32_t, void **, uint32_t *);
typedef int32_t (*get_info_fn)(void *, uint32_t, size_t, void *, size_t *);

// 返回 0 = 找到 GPU,name 里是名字;1 = 没有 OpenCL 库;2 = 库缺函数;3 = 没有 GPU 设备。
static int lp_probe_opencl(char *name, size_t namelen) {
    void *h = NULL;
    for (size_t i = 0; i < sizeof(lp_cl_names) / sizeof(lp_cl_names[0]) && !h; i++)
        h = dlopen(lp_cl_names[i], RTLD_NOW | RTLD_LOCAL);
    if (!h) return 1;
    get_platforms_fn gp = (get_platforms_fn)dlsym(h, "clGetPlatformIDs");
    get_devices_fn gd = (get_devices_fn)dlsym(h, "clGetDeviceIDs");
    get_info_fn gi = (get_info_fn)dlsym(h, "clGetDeviceInfo");
    if (!gp || !gd || !gi) return 2;
    void *plats[8];
    uint32_t np = 0;
    if (gp(8, plats, &np) != 0) return 3;
    uint64_t best_mem = 0;
    int found = 0;
    for (uint32_t i = 0; i < np && i < 8; i++) {
        void *devs[8];
        uint32_t nd = 0;
        if (gd(plats[i], 1 << 2, 8, devs, &nd) != 0) continue;  // CL_DEVICE_TYPE_GPU
        for (uint32_t j = 0; j < nd && j < 8; j++) {
            uint64_t mem = 0;
            gi(devs[j], 0x101F, sizeof(mem), &mem, NULL);  // CL_DEVICE_GLOBAL_MEM_SIZE
            if (!found || mem > best_mem) {
                found = 1;
                best_mem = mem;
                memset(name, 0, namelen);
                gi(devs[j], 0x102B, namelen - 1, name, NULL);  // CL_DEVICE_NAME
            }
        }
    }
    // 库句柄不关:libmpv 里的滤镜马上还要用同一个库,关了再开白费一次加载
    return found ? 0 : 3;
}
*/
import "C"

import (
	"errors"
	"strconv"
	"strings"
	"sync"
	"unsafe"
)

// Supported 安卓走自编 libmpv 里的 OpenCL 光流滤镜。设备有没有 OpenCL 由 Device() 说。
const Supported = true

// NeedsRuntime 滤镜编在 libmpv 里,不用下载。
const NeedsRuntime = false

// algos 只有一种算法。高度交给滤镜(光流固定在 270p 上算,输出原分辨率)。
var algos = []algo{
	{"ocl", "OpenCL 光流", map[int]int{2: 0, 3: 0, 4: 0}},
}

var (
	probeOnce sync.Once
	probeName string
	probeErr  error
)

// Device 这台设备能用的 OpenCL GPU。结果缓存:探测要 dlopen 厂商驱动,不便宜,也不会中途变。
func Device() (string, int, error) {
	probeOnce.Do(func() {
		buf := make([]byte, 128)
		switch C.lp_probe_opencl((*C.char)(unsafe.Pointer(&buf[0])), C.size_t(len(buf))) {
		case 0:
			probeName = strings.TrimRight(string(buf), "\x00")
		case 1:
			probeErr = errors.New("这台手机没有对应用开放 OpenCL,补帧用不了")
		case 2:
			probeErr = errors.New("这台手机的 OpenCL 库缺少必要的函数,补帧用不了")
		default:
			probeErr = errors.New("这台手机的 OpenCL 里没有 GPU,补帧用不了")
		}
	})
	return probeName, 0, probeErr
}

// Preload 安卓不需要预加载。
func Preload() error { return nil }

// DisplayHz 屏幕刷新率,由 UI 报(见 SetDisplayHz)。
func DisplayHz() float64 { return reported() }

// FilterString 挂到 mpv `vf` 属性上的那一串。scriptPath / gpu 在安卓上用不着。
func FilterString(scriptPath string, spec Spec, gpu int) string {
	return "@lpinterp:lpinterp=multi=" + strconv.Itoa(spec.Multi)
}

// ScriptPath 安卓没有脚本。
func ScriptPath() (string, error) { return "", nil }
