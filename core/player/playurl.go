package player

// `player.playUrl` —— 直接播一条地址(直播频道、插件自己找到的流)。
//
// ☠ 它和 `player.play` 是两条路:`play` 走 Emby 的 PlaybackInfo、上报三件套、
//   续播记录;这条**一样都没有**。走错路的表现是「直播播着播着把进度记到上一部剧上」,
//   而且 Emby 那边会多出一条永远不结束的播放会话。所以这里进来先把播放上下文清干净。

import (
	"context"
	"strings"

	"linplayer/core/bus"
)

func registerPlayURL() {
	bus.Register("player.playUrl", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		u, _ := a["url"].(string)
		u = strings.TrimSpace(u)
		if u == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 url")
		}
		if !strings.Contains(u, "://") {
			return nil, bus.NewErr(bus.EInvalid, "不是一条可播的地址:%s", u)
		}
		headers := map[string]string{}
		if m, ok := a["headers"].(map[string]any); ok {
			for k, v := range m {
				if s, ok := v.(string); ok && s != "" {
					headers[k] = s
				}
			}
		}
		// UA 单独拿出来:mpv 的 user-agent 是独立属性,塞进 http-header-fields 会被它忽略
		ua := ""
		for k, v := range headers {
			if strings.EqualFold(k, "User-Agent") {
				ua = v
				delete(headers, k)
				break
			}
		}
		start, _ := a["start_secs"].(float64)

		setShaderScope("")
		if err := loadWith(u, start, headers, ua); err != nil {
			return nil, bus.NewErr(bus.EInternal, "%v", err)
		}
		// 不是 Emby 条目:清掉上报上下文,否则这条流的进度会记到上一部片上
		currentMu.Lock()
		current = nil
		currentCtx = nil
		pendingSubs = nil
		currentMu.Unlock()

		title, _ := a["title"].(string)
		if title != "" {
			setProp("force-media-title", title)
		}
		return map[string]any{"url": u, "title": title}, nil
	})
}

