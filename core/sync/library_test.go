package sync

import (
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/paths"
)

// 开播提醒(D366):只提醒刚开播的、每集只提醒一次;按周放送的看今天是不是放送日、过没过放送时刻。
func TestDueEntries(t *testing.T) {
	now := time.Date(2026, 9, 20, 21, 0, 0, 0, time.Local) // 周日
	ptr := func(s string) *string { return &s }
	iptr := func(i int) *int { return &i }
	id := int64(9)
	entries := []CalendarEntry{
		{Title: "刚播", AirDate: ptr(now.Add(-time.Hour).Format(time.RFC3339)), TMDBID: &id, Season: iptr(1), Episode: iptr(3)},
		{Title: "前天播的", AirDate: ptr(now.Add(-48 * time.Hour).Format(time.RFC3339))},
		{Title: "还没播", AirDate: ptr(now.Add(time.Hour).Format(time.RFC3339))},
		{Title: "周日 20 点档", Weekday: iptr(7), BroadcastAt: ptr(time.Date(2026, 1, 4, 20, 0, 0, 0, time.Local).UTC().Format(time.RFC3339))},
		{Title: "周日 23 点档", Weekday: iptr(7), BroadcastAt: ptr(time.Date(2026, 1, 4, 23, 0, 0, 0, time.Local).UTC().Format(time.RFC3339))},
		{Title: "周一的", Weekday: iptr(1)},
	}
	seen := map[string]int64{}
	got := dueEntries(entries, now, 24*time.Hour, seen)
	names := map[string]bool{}
	for _, e := range got {
		names[e.Title] = true
	}
	if len(got) != 2 || !names["刚播"] || !names["周日 20 点档"] {
		t.Fatalf("应只提醒「刚播」和「周日 20 点档」,得到 %v", names)
	}
	if again := dueEntries(entries, now.Add(10*time.Minute), 24*time.Hour, seen); len(again) != 0 {
		t.Fatalf("同一集不该提醒第二次: %v", again)
	}
}

// 解锁凭据不落盘 = 用户每次开应用都得重输一遍订单号,而爱发电订单号是 20 多位。
// 这条链路没有报错通道:写失败时界面照样显示「已解锁」,下次启动才暴露。
func TestUnlockCalendar_凭据要落盘(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}

	unlockCalendar("202609201234567890")

	// 从磁盘重新读:只改内存里那份的话,用户重启后又是上锁状态
	c, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	if got := c.PrefsOf().CalendarUnlockOrder; got != "202609201234567890" {
		t.Fatalf("解锁凭据没落盘,读回来是 %q", got)
	}
}
