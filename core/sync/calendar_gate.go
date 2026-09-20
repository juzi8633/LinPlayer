package sync

// 追剧日历的付费门。
//
// 门收在核心层**一处**(D551):上一版三个壳各判各的,结果
// `CalendarWorker.kt` 与 `AppJobs.cs` 两条提醒通道直接调 `sync.calendarDue`,
// 没解锁的用户照样收到「xx 开播了」—— 门只挡住了看得见的那个入口。
// 凡是「拉放送表」的命令一律走 gated,新加命令忘了加门会被 calendar_gate_test 抓住。

import (
	"context"
	"strings"
	gosync "sync"

	"linplayer/core/bus"
	"linplayer/core/config"
)

// ErrCalendarLocked 未解锁时所有日历命令的回答。
// 用 E_PERMISSION 而不是返回空表:空表和「这周没有新番」长得一样,壳分不出来。
const calendarLockedMsg = "追剧日历需要赞助解锁"

// calendarUnlocked 凭据 = 校验通过的爱发电订单号(D200 D550),存核心层偏好,三端共用。
func calendarUnlocked() bool {
	return strings.TrimSpace(config.Current().PrefsOf().CalendarUnlockOrder) != ""
}

// gatedCalendarCommands 受付费门保护的命令名。测试拿它对着 bus 注册表逐条验。
var gatedCalendarCommands = []string{
	"sync.traktCalendar",
	"sync.bangumiCalendar",
	"sync.calendarLibrary",
	"sync.calendarDue",
}

func registerGated(name string, h bus.Handler) {
	bus.Register(name, func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		if !calendarUnlocked() {
			return nil, bus.NewErr(bus.EPermission, calendarLockedMsg)
		}
		return h(ctx, seq, a)
	})
}

// regOnce 让包内多个测试共用同一次注册。生产路径由 core/commands 只调一次。
var regOnce gosync.Once
