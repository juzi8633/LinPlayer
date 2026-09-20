package plugin

// 应用事件的**来源**(SPEC 9.1 的事件那一节,D94)。
//
// 两路来源:
//   · 播放类由核心层自己的 `player.status` / `player.fileLoaded` 推导 ——
//     状态机在这儿,壳不该也推一遍;
//   · 导航、服务器切换、前后台由壳报(`plugin.appEvent`):只有壳知道用户在哪一页。
//
// ☠ 推导要**按状态变化发**,不是按 status 到达发:status 是周期推送的,
//   照发的话 player.pause 每秒来好几条,插件那边的 scrobble 会把上游打爆。

import (
	"context"
	"encoding/json"
	"sync"

	"linplayer/core/bus"
	"linplayer/core/plugin/rt"
)

type playerSnapshot struct {
	itemID    string
	paused    bool
	eof       bool
	buffering bool
	started   bool
}

var (
	evMu   sync.Mutex
	evLast playerSnapshot
)

// onPlayerStatus 由 core/commands 把 `player.status` 事件转进来。
func onPlayerStatus(raw json.RawMessage) {
	var s struct {
		ItemID    string  `json:"item_id"`
		Position  float64 `json:"position"`
		Duration  float64 `json:"duration"`
		Paused    bool    `json:"paused"`
		EOF       bool    `json:"eof"`
		Buffering bool    `json:"buffering"`
	}
	if json.Unmarshal(raw, &s) != nil {
		return
	}
	now := playerSnapshot{itemID: s.ItemID, paused: s.Paused, eof: s.EOF, buffering: s.Buffering}
	np := map[string]any{
		"itemId": s.ItemID, "position": s.Position, "duration": s.Duration,
		"paused": s.Paused,
	}

	evMu.Lock()
	prev := evLast
	// started 只在真有条目时置位:没起播时 status 里 item_id 是空的,
	// 不判这一下的话应用一启动就会发一条 player.start
	now.started = s.ItemID != ""
	evLast = now
	evMu.Unlock()

	switch {
	case now.started && !prev.started:
		rt.EmitAppEvent("player.start", np)
	case now.started && prev.started && now.itemID != prev.itemID:
		rt.EmitAppEvent("player.episodeChange", np)
	case !now.started && prev.started:
		reason := "stop"
		if prev.eof {
			reason = "eof"
		}
		end := map[string]any{"itemId": prev.itemID, "reason": reason}
		rt.EmitAppEvent("player.end", end)
	}
	if now.started && prev.started {
		if now.paused != prev.paused {
			if now.paused {
				rt.EmitAppEvent("player.pause", np)
			} else {
				rt.EmitAppEvent("player.resume", np)
			}
		}
		if now.eof && !prev.eof {
			rt.EmitAppEvent("player.end", map[string]any{"itemId": now.itemID, "reason": "eof"})
		}
		if now.buffering != prev.buffering {
			rt.EmitAppEvent("player.buffering", map[string]any{"buffering": now.buffering})
		}
	}
}

func registerEvents() {
	// 壳报的那几路(导航、服务器、前后台):只有壳知道用户在哪一页。
	bus.Register("plugin.appEvent", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		name, _ := a["name"].(string)
		if name == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 name")
		}
		rt.EmitAppEvent(name, a["data"])
		return nil, nil
	})
}

// ObservePlayerStatus 由 core/commands 在注册完之后挂上。
//
// 走 bus 的事件旁路而不是 import core/player:那边的状态机在包内,
// 直接调等于再开一条不受它管的路(和 player 命名空间同一条理由)。
func ObservePlayerStatus() func(name string, data json.RawMessage) {
	return func(name string, data json.RawMessage) {
		if name == "player.status" {
			onPlayerStatus(data)
		}
	}
}
