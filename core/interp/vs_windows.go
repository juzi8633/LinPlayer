//go:build windows

package interp

import (
	_ "embed"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"unsafe"
)

// Supported 这个平台有补帧。
const Supported = true

// NeedsRuntime Windows 的补帧要先下载运行时(runtime.go)。
const NeedsRuntime = true

// algos 两种算法各自补帧前降到的高度。
//
// ★ 高度是按 RTX 5060 Laptop 实测定的(1080p24,DirectML):
// DRBA 2 倍 1080p 不丢帧,3 倍 1080p 跟不上、720p 不丢,4 倍 720p 在 LinPlayer 里跑满 96 帧;
// RIFE 比 DRBA 慢,2 倍 1080p 就跟不上、900p 不丢。RIFE 3/4 倍的 720/540 **没单独测过**,
// 是按「倍数越高降得越多」推的 —— 跑不动由 core/player 的丢帧闸退回,不会卡着不动。
var algos = []algo{
	{"drba", "DRBA · 动画", map[int]int{2: 1080, 3: 720, 4: 720}},
	{"rife", "RIFE · 通用", map[int]int{2: 900, 3: 720, 4: 540}},
}

//go:embed files/interp.vpy
var script []byte

// UserData 交给 vf=vapoursynth 的 user-data。
func (s Spec) UserData(gpu int) string {
	return fmt.Sprintf("algo=%s;x=%d;gpu=%d;h=%d", s.Algo, s.Multi, gpu, s.Height)
}

// FilterString 挂到 mpv `vf` 属性上的那一串。
func FilterString(scriptPath string, spec Spec, gpu int) string {
	return "@lpinterp:vapoursynth=file=" + quote(scriptPath) + ":user-data=" + quote(spec.UserData(gpu))
}

// ScriptPath 把嵌入的脚本落到运行时目录,返回路径。每次都写:升级后脚本要跟着换。
func ScriptPath() (string, error) {
	p := filepath.Join(Dir(), "linplayer-interp.vpy")
	if err := os.WriteFile(p, script, 0o644); err != nil {
		return "", fmt.Errorf("写补帧脚本失败: %w", err)
	}
	return p, nil
}

// Device 跑补帧的那块卡:名字 + DXGI 序号。
func Device() (string, int, error) {
	a, err := PickAdapter()
	return a.Name, a.Index, err
}

// minDedicatedMB 独显门槛。核显报的专用显存是 128MB 上下,实测跑 720p DRBA 10 秒丢 178 帧。
const minDedicatedMB = 2048

// Adapter 挑出来跑推理的那块卡。
type Adapter struct {
	Index int
	Name  string
	MB    int
}

// PickAdapter 在 **本进程里** 枚举 DXGI 适配器,挑专用显存最大的那块。
//
// ★ 序号必须在本进程里算:onnxruntime 的 device_id 就是 IDXGIFactory1::EnumAdapters1
// 的序号,而这个顺序受「设置 → 图形」里的 per-exe 偏好影响(gpupref_windows.go 会把
// 本程序钉到独显)。在别的进程里量出来的序号 —— 实测独显在 1 号 —— 放到这里可能是 0 号。
// ★ 远程桌面软件装的虚拟显示卡、Microsoft Basic Render Driver 都报 0 专用显存,自然落选。
func PickAdapter() (Adapter, error) {
	all, err := enumAdapters()
	if err != nil {
		return Adapter{}, err
	}
	best := Adapter{Index: -1}
	for _, a := range all {
		if a.MB > best.MB {
			best = a
		}
	}
	if best.Index < 0 || best.MB < minDedicatedMB {
		return Adapter{}, fmt.Errorf("没有找到独立显卡(补帧需要至少 %dMB 专用显存的 DX12 显卡)", minDedicatedMB)
	}
	return best, nil
}

var (
	dxgi               = syscall.NewLazyDLL("dxgi.dll")
	procCreateFactory1 = dxgi.NewProc("CreateDXGIFactory1")
	kernel32           = syscall.NewLazyDLL("kernel32.dll")
	procLoadLibraryEx  = kernel32.NewProc("LoadLibraryExW")
	user32             = syscall.NewLazyDLL("user32.dll")
	procEnumDisplay    = user32.NewProc("EnumDisplaySettingsW")
)

// IID_IDXGIFactory1 {770aae78-f26f-4dba-a829-253c83d1b387}
var iidFactory1 = syscall.GUID{Data1: 0x770aae78, Data2: 0xf26f, Data3: 0x4dba,
	Data4: [8]byte{0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87}}

// dxgiAdapterDesc1 DXGI_ADAPTER_DESC1。
type dxgiAdapterDesc1 struct {
	Description           [128]uint16
	VendorID, DeviceID    uint32
	SubSysID, Revision    uint32
	DedicatedVideoMemory  uintptr
	DedicatedSystemMemory uintptr
	SharedSystemMemory    uintptr
	LuidLow               uint32
	LuidHigh              int32
	Flags                 uint32
}

// comObj COM 对象的内存布局:头一个字就是虚表指针。用类型化指针而不是 uintptr 做算术,
// go vet 才不会报 unsafe.Pointer 误用(那条警告在 GC 移动对象时是真 bug)。
type comObj struct{ vtbl *[16]uintptr }

// comCall 调 COM 接口虚表第 slot 个方法。
func comCall(obj *comObj, slot int, args ...uintptr) uintptr {
	r, _, _ := syscall.SyscallN(obj.vtbl[slot], append([]uintptr{uintptr(unsafe.Pointer(obj))}, args...)...)
	return r
}

const (
	slotRelease         = 2
	slotEnumAdapters1   = 12 // IDXGIFactory1::EnumAdapters1
	slotGetDesc1        = 10 // IDXGIAdapter1::GetDesc1
	dxgiErrNotFound     = 0x887A0002
	adapterFlagSoftware = 2
)

func enumAdapters() ([]Adapter, error) {
	if err := procCreateFactory1.Find(); err != nil {
		return nil, fmt.Errorf("没有 DXGI: %w", err)
	}
	var factory *comObj
	if hr, _, _ := procCreateFactory1.Call(uintptr(unsafe.Pointer(&iidFactory1)), uintptr(unsafe.Pointer(&factory))); int32(hr) < 0 {
		return nil, fmt.Errorf("CreateDXGIFactory1 失败: 0x%x", uint32(hr))
	}
	defer comCall(factory, slotRelease)
	var out []Adapter
	for i := 0; ; i++ {
		var ad *comObj
		hr := comCall(factory, slotEnumAdapters1, uintptr(i), uintptr(unsafe.Pointer(&ad)))
		if uint32(hr) == dxgiErrNotFound {
			return out, nil
		}
		if int32(hr) < 0 {
			return out, fmt.Errorf("EnumAdapters1 失败: 0x%x", uint32(hr))
		}
		var d dxgiAdapterDesc1
		hr = comCall(ad, slotGetDesc1, uintptr(unsafe.Pointer(&d)))
		comCall(ad, slotRelease)
		if int32(hr) < 0 || d.Flags&adapterFlagSoftware != 0 {
			continue
		}
		out = append(out, Adapter{Index: i, Name: syscall.UTF16ToString(d.Description[:]), MB: int(d.DedicatedVideoMemory >> 20)})
	}
}

var (
	preloadOnce sync.Once
	preloadErr  error
)

// Preload 按完整路径把 VSScript.dll 先装进本进程。
//
// ★ libmpv 是 `LoadLibraryW("VSScript.dll")` 按名字找的:只搜 exe 目录和 PATH,不搜我们的组件目录。
// 同名模块已经在进程里时 LoadLibraryW 直接返回它,所以先按全路径装一次就够。
// 不走 VSSCRIPT_PATH 环境变量:mpv 读的是 C 运行库的 getenv,Go 这边 os.Setenv 改不到它那份。
// ★ LOAD_WITH_ALTERED_SEARCH_PATH:让 VSScript.dll 的依赖(python314.dll)从它自己的目录找。
func Preload() error {
	preloadOnce.Do(func() {
		p, err := syscall.UTF16PtrFromString(filepath.Join(Dir(), "VSScript.dll"))
		if err != nil {
			preloadErr = err
			return
		}
		const loadWithAlteredSearchPath = 0x8
		if h, _, e := procLoadLibraryEx.Call(uintptr(unsafe.Pointer(p)), 0, loadWithAlteredSearchPath); h == 0 {
			preloadErr = fmt.Errorf("加载补帧组件失败: %v", e)
		}
	})
	return preloadErr
}

// DisplayHz 主屏刷新率。拿不到返回 0(调用方当「未知」处理)。UI 报过的话以 UI 为准。
//
// ponytail: 只看主屏。窗口拖到副屏且两块屏刷新率不同时会判错,要准就得按窗口所在显示器查。
func DisplayHz() float64 {
	if hz := reported(); hz > 0 {
		return hz
	}
	// DEVMODEW 共 220 字节:dmSize 在 68,dmDisplayFrequency 在 184(本机实测:168 位深 32、172/176 宽高、184 刷新率)。
	// 按偏移读而不是抄整个结构体:它中间两个 union,抄错一个字段后面全错位,编译照样绿。
	var dm [220]byte
	*(*uint16)(unsafe.Pointer(&dm[68])) = uint16(len(dm))
	const enumCurrentSettings = 0xFFFFFFFF
	if r, _, _ := procEnumDisplay.Call(0, enumCurrentSettings, uintptr(unsafe.Pointer(&dm[0]))); r == 0 {
		return 0
	}
	hz := *(*uint32)(unsafe.Pointer(&dm[184]))
	if hz <= 1 { // 0 / 1 是「硬件默认」,不是真刷新率
		return 0
	}
	return float64(hz)
}
