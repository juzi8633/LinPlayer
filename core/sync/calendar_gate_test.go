package sync

import (
	"context"
	"strings"
	"testing"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

// registerOnce 整个包只能注册一次(bus.Register 重复注册 panic)。
func withCommands(t *testing.T) {
	t.Helper()
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	regOnce.Do(func() { RegisterCommands("test") })
}

// TestCalendarGate_未解锁四条命令全部拒绝 —— 这是 D551 的门。
//
// 反向验证过:把 commands.go / library.go 的 registerGated 改回 bus.Register,
// 四条全部返回数据,本测试四条全红。
func TestCalendarGate_未解锁一条都不给(t *testing.T) {
	withCommands(t)

	for _, cmd := range gatedCalendarCommands {
		_, err := bus.Invoke(context.Background(), cmd, map[string]any{"only_mine": true})
		if err == nil {
			t.Fatalf("%s 未解锁时仍返回了数据 —— 付费门漏了", cmd)
		}
		e, ok := err.(*bus.Err)
		if !ok || e.Code != bus.EPermission {
			t.Fatalf("%s 未解锁时的错误应当是 %s,实际 %v", cmd, bus.EPermission, err)
		}
	}
}

// TestCalendarGate_解锁后放行:证明上一条红的原因是门,不是命令本身就抛错。
// only_mine=true 是为了让四条都在没连账号时就地返回(EAuth / 空表),不打上游。
func TestCalendarGate_解锁后不再是门挡的(t *testing.T) {
	withCommands(t)
	unlockCalendar("202609210000000001")

	for _, cmd := range gatedCalendarCommands {
		_, err := bus.Invoke(context.Background(), cmd, map[string]any{"only_mine": true})
		if e, ok := err.(*bus.Err); ok && e.Code == bus.EPermission && e.Msg == calendarLockedMsg {
			t.Fatalf("%s 解锁后还被门挡着", cmd)
		}
	}
}

// TestCalendarGate_新命令别漏挂门:任何名字里带 Calendar 的命令都必须在清单里。
func TestCalendarGate_名单覆盖全部日历命令(t *testing.T) {
	withCommands(t)

	in := map[string]bool{}
	for _, c := range gatedCalendarCommands {
		in[c] = true
	}
	for _, c := range bus.Commands() {
		if !strings.HasPrefix(c, "sync.") || !strings.Contains(strings.ToLower(c), "calendar") {
			continue
		}
		if !in[c] {
			t.Fatalf("%s 是日历命令却没走 registerGated —— 新命令漏挂付费门", c)
		}
	}
}
