package plugin

/*
壳问一句、插件答一句的两条命令(D85 D563)。

`nav.onBack` 与 `player.onKey` 的合同都是「回调返回 true 就别按默认处理了」,
而这件事**只能由壳先问**:哪一层在最上面、这一下按键该归谁,只有壳知道。

☠ 一定要有超时。插件的回调卡住时不能把返回键一起卡住 —— 用户连退出都做不到,
  只能强杀应用。超时按「没人接走」算:默认处理总比按不动强。
*/

import (
	"context"
	"time"

	"linplayer/core/bus"
	"linplayer/core/plugin/rt"
)

// keyBudget 按键/返回键的回答上限。比 BudgetHook(300ms)再紧一点:
// 这是用户按下去到画面反应的时间,超过这个数就已经「按起来发黏」了。
const keyBudget = 200 * time.Millisecond

// backRequest 壳按下返回键时问一次。回 {handled:true} 就别退了。
func (h *Host) backRequest(ctx context.Context, a map[string]any) (any, error) {
	return map[string]any{"handled": h.askSlot(ctx, rt.SlotBack, str(a, "plugin"), nil)}, nil
}

// playerKey 壳在播放器里按下一个键时问一次。回 {consumed:true} 就别按默认处理。
func (h *Host) playerKey(ctx context.Context, a map[string]any) (any, error) {
	key := str(a, "key")
	if key == "" {
		return nil, bus.NewErr(bus.EInvalid, "没给按键名")
	}
	arg := map[string]any{"key": key}
	if b, _ := a["repeat"].(bool); b {
		arg["repeat"] = true
	}
	return map[string]any{"consumed": h.askSlot(ctx, rt.SlotKey, str(a, "plugin"), arg)}, nil
}

/*
askSlot 问一个(或全部)插件。

`only` 非空时只问它:按键归属由壳判断,壳说「现在是这个插件的覆盖层在上面」就只问它。
空串时问所有活着的插件 —— 返回键那条路上壳不一定知道是谁的页面在上面。
*/
func (h *Host) askSlot(ctx context.Context, slot, only string, arg any) bool {
	ctx, cancel := context.WithTimeout(ctx, keyBudget)
	defer cancel()
	for _, r := range h.runtimes(only) {
		ok, err := r.CallSlot(ctx, keyBudget, slot, arg)
		if err != nil {
			// 回调抛了/超时了不算接走:按默认处理,别让按键失灵
			continue
		}
		if ok {
			return true
		}
	}
	return false
}

// runtimes 现在活着的运行时。id 非空时只回那一个。
func (h *Host) runtimes(id string) []*rt.Runtime {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]*rt.Runtime, 0, len(h.loaded))
	for pid, l := range h.loaded {
		if l == nil || l.rt == nil {
			continue
		}
		if id != "" && pid != id {
			continue
		}
		out = append(out, l.rt)
	}
	return out
}
