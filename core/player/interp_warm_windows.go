//go:build windows

package player

// N 卡加速包装好之后预建 TensorRT 引擎。
//
// 引擎跟显卡和驱动绑定,只能在用户机器上建;一次 40~55 秒(RTX 5060 Laptop 实测)。
// 放到播放时现建的话,画面卡住那一分钟还会被丢帧闸判成「没生效」撤掉。所以装完包就建:
// 起一个 vo=null 的 mpv 实例,喂一段 lavfi 生成的 1920×1080 灰屏过一遍补帧滤镜 ——
// 脚本对 TRT 一律补黑边到 1920×1080,所以建出来的引擎和播放时要的是同一个
// (实测引擎文件名一致:DRBA a32e9cda、RIFE e2f23181)。

/*
#include <stdlib.h>
#include <stdint.h>

extern void*    mpv_create(void);
extern int      mpv_initialize(void*);
extern int      mpv_set_option_string(void*, const char*, const char*);
extern int      mpv_set_property_string(void*, const char*, const char*);
extern int      mpv_command(void*, const char**);
extern void*    mpv_wait_event(void*, double);
extern void     mpv_terminate_destroy(void*);
extern int      mpv_request_log_messages(void*, const char*);

typedef struct lp_warm_event { int event_id; int error; uint64_t reply_userdata; void *data; } lp_warm_event;
static int lp_warm_ev_id(void *ev) { return ev ? ((lp_warm_event*)ev)->event_id : 0; }
typedef struct lp_warm_log { const char *prefix; const char *level; const char *text; int log_level; } lp_warm_log;
static const char* lp_warm_log_text(void *ev) {
    if (!ev || ((lp_warm_event*)ev)->event_id != 2 || !((lp_warm_event*)ev)->data) return 0;
    return ((lp_warm_log*)((lp_warm_event*)ev)->data)->text;
}
*/
import "C"

import (
	"context"
	"fmt"
	"strings"
	"time"
	"unsafe"

	"linplayer/core/bus"
	"linplayer/core/interp"
)

// warmSource 灰屏而不是纯黑:全黑时光流那一段可能被各种「无内容」捷径跳过,建出来的引擎不一定走全路径。
const warmSource = "av://lavfi:color=c=gray:s=1920x1080:r=24000/1001:d=2"

// warmTRT 依次为 DRBA、RIFE 建引擎。onStep(第几个, 共几个)。
func warmTRT(ctx context.Context, onStep func(i, n int)) error {
	if err := interp.Preload(); err != nil {
		return err
	}
	script, err := interp.ScriptPath()
	if err != nil {
		return err
	}
	h := C.mpv_create()
	if h == nil {
		return fmt.Errorf("mpv_create 失败")
	}
	defer C.mpv_terminate_destroy(h)
	set := func(k, v string) {
		ck, cv := C.CString(k), C.CString(v)
		C.mpv_set_option_string(h, ck, cv)
		C.free(unsafe.Pointer(ck))
		C.free(unsafe.Pointer(cv))
	}
	for _, kv := range [][2]string{
		{"vo", "null"}, {"ao", "null"}, {"audio", "no"}, {"untimed", "yes"},
		{"terminal", "no"}, {"config", "no"}, {"load-scripts", "no"}, {"osc", "no"},
		{"ytdl", "no"}, {"hwdec", "no"}, {"idle", "yes"},
	} {
		set(kv[0], kv[1])
	}
	if r := C.mpv_initialize(h); r < 0 {
		return fmt.Errorf("mpv_initialize 失败: %d", int(r))
	}
	// 收 error 级日志:建不出来时把 mpv / 脚本的原话带出去,不然只知道「没建出来」
	cl := C.CString("error")
	C.mpv_request_log_messages(h, cl)
	C.free(unsafe.Pointer(cl))
	algos := []string{"drba", "rife"}
	for i, algo := range algos {
		if onStep != nil {
			onStep(i+1, len(algos))
		}
		vf := interp.FilterString(script, interp.Spec{Algo: algo, Multi: 2, Height: 1080, Backend: "trt"}, 0)
		cn, cv := C.CString("vf"), C.CString(vf)
		C.mpv_set_property_string(h, cn, cv)
		C.free(unsafe.Pointer(cn))
		C.free(unsafe.Pointer(cv))
		/* ☠ 先把队列抽干,再等「这一段开始」,最后才等结束。第一版直接等 END_FILE:
		   DRBA 那一段结束后队列里还留着事件,RIFE 刚 loadfile 就被当成「放完了」——
		   RIFE 的引擎一次都没建(2026-09-18 实测:trtexec 日志都没有产生)。 */
		for int(C.lp_warm_ev_id(C.mpv_wait_event(h, 0))) != 0 {
		}
		args := []*C.char{C.CString("loadfile"), C.CString(warmSource), nil}
		r := C.mpv_command(h, (**C.char)(unsafe.Pointer(&args[0])))
		C.free(unsafe.Pointer(args[0]))
		C.free(unsafe.Pointer(args[1]))
		if r < 0 {
			return fmt.Errorf("预建引擎 loadfile 失败: %d", int(r))
		}
		started := false
		var lastErr string
		deadline := time.Now().Add(10 * time.Minute)
		for {
			if ctx.Err() != nil {
				return fmt.Errorf("已取消")
			}
			if time.Now().After(deadline) {
				return fmt.Errorf("预建 %s 引擎超过 10 分钟没完成", algo)
			}
			ev := C.mpv_wait_event(h, 0.5)
			if t := C.lp_warm_log_text(ev); t != nil {
				lastErr = strings.TrimSpace(C.GoString(t))
				bus.Logf("warn", "补帧:预建 %s 时 mpv 报错:%s", algo, lastErr)
			}
			switch int(C.lp_warm_ev_id(ev)) {
			case evStartFile:
				started = true
			case evEndFile:
				if started {
					goto done
				}
			}
		}
	done:
		if !interp.EngineBuilt(algo) {
			return fmt.Errorf("N 卡加速引擎(%s)没建出来,继续用通用版。mpv 的原话:%s", algo, lastErr)
		}
	}
	if !interp.EnginesBuilt() {
		return fmt.Errorf("N 卡加速引擎没建出来(显卡驱动或显存不够),继续用通用版")
	}
	return interp.MarkEnginesReady()
}
