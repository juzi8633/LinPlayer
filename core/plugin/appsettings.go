package plugin

// app.getSetting / app.setSetting(D92 D93 D427):插件读写**应用**设置。
//
// 走命令总线而不是直接摸 config:`prefs.setPrefs` 那边已经有「没传的键一律不动」
// 与逐项拒绝的规矩,绕过去等于再写一份会分叉的校验。
// 可写性(账号 / 服务器 / 代理类拒绝)由 rt.AppSettingWritable 判,那是 D11 的最后一道门。

import (
	"context"
	"encoding/json"
	"fmt"

	"linplayer/core/bus"
)

func appSettingGet(key string) any {
	out, err := bus.Invoke(context.Background(), "prefs.getPrefs", nil)
	if err != nil {
		return nil
	}
	b, err := json.Marshal(out)
	if err != nil {
		return nil
	}
	var m map[string]any
	if json.Unmarshal(b, &m) != nil {
		return nil
	}
	return m[key]
}

func appSettingSet(key string, v any) error {
	if _, err := bus.Invoke(context.Background(), "prefs.setPrefs", map[string]any{key: v}); err != nil {
		return err
	}
	// setPrefs 对不认识的键是**静默忽略**的(它只取自己认的那几项)。
	// 不回读一次的话插件写了个拼错的键会以为写成功了,而设置一直没变。
	if got := appSettingGet(key); got == nil {
		return fmt.Errorf("应用设置里没有 %s 这个键", key)
	}
	return nil
}
