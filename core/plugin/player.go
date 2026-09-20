package plugin

// `player` 命名空间背后的宿主实现(SPEC 9)。
//
// 走命令总线而不是直接 import core/player:那边的 mpv 句柄、事件线程、
// 记账全在包内,直接调等于再开一条不受那些规矩管的路。
// 脱敏、记账、限频在 rt 那一侧(core/plugin/rt/player.go)—— 它们是**对插件**的约束,
// 不是对 mpv 的约束,放在宿主这边会被 `debug` 之类的别的入口绕过去。

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"linplayer/core/bus"
	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

func playerHooks() *rt.PlayerHooks {
	return &rt.PlayerHooks{
		Get: func(prop string) (any, error) {
			out, err := bus.Invoke(context.Background(), "player.mpvGet", map[string]any{"name": prop})
			if err != nil {
				return nil, err
			}
			if m, ok := out.(map[string]any); ok {
				return m["value"], nil
			}
			return out, nil
		},
		Set: func(prop string, v any) error {
			_, err := bus.Invoke(context.Background(), "player.mpvSet",
				map[string]any{"name": prop, "value": mpvString(v)})
			return err
		},
		Command: func(name string, args []any) (any, error) {
			all := make([]any, 0, len(args)+1)
			all = append(all, name)
			for _, a := range args {
				all = append(all, mpvString(a))
			}
			return bus.Invoke(context.Background(), "player.mpvCommand", map[string]any{"args": all})
		},
		Observe:    observeProp,
		State:      func() any { return invokeOr("player.status", nil) },
		Tracks:     func() any { return invokeOr("player.tracks", nil) },
		Screenshot: screenshot,
		Play: func(item any, opts map[string]any) error {
			a := map[string]any{}
			for k, v := range opts {
				a[k] = v
			}
			switch x := item.(type) {
			case string:
				a["item_id"] = x
			case map[string]any:
				a["item_id"], _ = x["id"].(string)
				if s, ok := x["source"].(string); ok && a["server"] == nil {
					a["server"] = s
				}
			}
			_, err := bus.Invoke(context.Background(), "player.play", a)
			return err
		},
		AddSubtitle: addSubtitle,
		Transcribe:  transcribeCurrent,
		GetSubtitleText: func(trackID int) (any, error) {
			return bus.Invoke(context.Background(), "player.getSubtitleText",
				map[string]any{"track_id": float64(trackID)})
		},
	}
}

// mpvString mpv 的属性与命令参数都是字符串。
//
// ☠ 布尔要写成 yes/no,不是 true/false:写 "true" 的话 mpv 静默忽略这一次设置,
//   而 get 回来还是旧值 —— 表现是「设了没反应」,不报错。
func mpvString(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case bool:
		if x {
			return "yes"
		}
		return "no"
	case float64:
		if x == float64(int64(x)) {
			return fmt.Sprintf("%d", int64(x))
		}
		return fmt.Sprintf("%g", x)
	}
	return fmt.Sprintf("%v", v)
}

func invokeOr(cmd string, args map[string]any) any {
	out, err := bus.Invoke(context.Background(), cmd, args)
	if err != nil {
		return nil
	}
	return out
}

/*
observeProp 订阅一个 mpv 属性。

★ 用轮询而不是 mpv 的 observe_property:后者的回调在 mpv 的事件线程上,
而 FFI 这一层没有把它透出来的通道。按 hz 轮询在 ≤10Hz(D161 的上限)下
是一次属性读,开销远小于把事件线程接出来要付的复杂度。
☠ 值没变就不回调:不去重的话一个 4Hz 的订阅每秒四次把插件叫醒,
  而插件多半只想知道「变了」。
*/
func observeProp(prop string, hz float64, cb func(any)) (func(), error) {
	if hz <= 0 {
		hz = 4
	}
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(time.Duration(float64(time.Second) / hz))
		defer t.Stop()
		var last any
		var seen bool
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				out, err := bus.Invoke(context.Background(), "player.mpvGet", map[string]any{"name": prop})
				if err != nil {
					continue
				}
				m, _ := out.(map[string]any)
				v := m["value"]
				if seen && fmt.Sprint(v) == fmt.Sprint(last) {
					continue
				}
				last, seen = v, true
				cb(v)
			}
		}
	}()
	var once bool
	return func() {
		if once {
			return
		}
		once = true
		close(stop)
	}, nil
}

/*
screenshot 单帧 PNG(D106)。

核心层那条命令只会**落盘**(它是给「用户按截图键」用的),所以这里指定一个
临时目录接住它,读回字节再删掉 —— 插件要的是那一帧像素,不是用户图片文件夹里多一张图。
☠ 不能不指定 dir 就调:那样会往用户设置的截图目录里扔文件,
  插件每问一次画面用户的图片夹就多一张,而且不报错。
*/
func screenshot() ([]byte, error) {
	dir := filepath.Join(paths.CacheDir(), "plugin-shot")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	out, err := bus.Invoke(context.Background(), "player.screenshot", map[string]any{"dir": dir})
	if err != nil {
		return nil, err
	}
	m, _ := out.(map[string]any)
	p, _ := m["path"].(string)
	if p == "" {
		return nil, fmt.Errorf("截图没回路径")
	}
	defer os.Remove(p)
	return os.ReadFile(p)
}

// addSubtitle 把插件给的字幕文本存进宿主字幕缓存再 sub-add(D164 D188)。
func addSubtitle(content any, opts map[string]any) (int, error) {
	a := map[string]any{}
	for k, v := range opts {
		a[k] = v
	}
	switch x := content.(type) {
	case string:
		a["text"] = x
	case []byte:
		a["text"] = string(x)
	default:
		return 0, fmt.Errorf("字幕内容只能是文本或 ArrayBuffer")
	}
	out, err := bus.Invoke(context.Background(), "player.addSubtitle", a)
	if err != nil {
		return 0, err
	}
	if m, ok := out.(map[string]any); ok {
		if n, ok := m["id"].(float64); ok {
			return int(n), nil
		}
	}
	return 0, nil
}
