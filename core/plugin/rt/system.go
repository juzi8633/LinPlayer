package rt

// `system` 命名空间(SPEC 13,D196 D197 D444 D536)。
//
// 四件事全在壳那边:打开浏览器、调起别的 App、剪贴板、系统分享。
// 核心层只做两件事 —— 转发,和**挡住不该开的**:
// D444 划了线(相机、语音、振动、独立音频不开放),这里不能因为「顺手」多开一个。
//
// ☠ `openApp` 的目标不做白名单,但 `file://` 与本机可执行路径要拦:
//   插件能拿它去唤起任意本地程序的话,「装一个插件」就等于「让它在你机器上开东西」。

import (
	"encoding/base64"
	"strings"

	"github.com/dop251/goja"
)

func (r *Runtime) installSystem(sdk *goja.Object) {
	vm := r.vm
	sys := vm.NewObject()
	_ = sdk.Set("system", sys)

	_ = sys.Set("openUrl", func(c goja.FunctionCall) goja.Value {
		u := strings.TrimSpace(c.Argument(0).String())
		if _, err := parseHTTPURL(u); err != nil {
			p, _, reject := vm.NewPromise()
			_ = reject(r.newPluginError(KindInvalid, "openUrl 只收 http/https 地址:"+u))
			return vm.ToValue(p)
		}
		return r.shellAsk("system.openUrl", map[string]any{"url": u}, actionTimeout)
	})

	_ = sys.Set("openApp", func(c goja.FunctionCall) goja.Value {
		target := exportJSON(r, c.Argument(0))
		if why := badAppTarget(target); why != "" {
			p, _, reject := vm.NewPromise()
			_ = reject(r.newPluginError(KindPermission, why))
			return vm.ToValue(p)
		}
		return r.shellAsk("system.openApp", map[string]any{"target": target}, actionTimeout)
	})

	clip := vm.NewObject()
	_ = sys.Set("clipboard", clip)
	_ = clip.Set("read", func(goja.FunctionCall) goja.Value {
		return r.shellAsk("system.clipboardRead", map[string]any{}, actionTimeout)
	})
	_ = clip.Set("write", func(c goja.FunctionCall) goja.Value {
		return r.shellAsk("system.clipboardWrite", map[string]any{"text": c.Argument(0).String()}, actionTimeout)
	})

	_ = sys.Set("share", func(c goja.FunctionCall) goja.Value {
		content, _ := exportJSON(r, c.Argument(0)).(map[string]any)
		if content == nil {
			content = map[string]any{}
		}
		// 图片是 ArrayBuffer(D536):JSON 化会变成一坨数字,转成 base64 交给壳
		if b, ok := c.Argument(0).ToObject(vm).Get("image").Export().(goja.ArrayBuffer); ok {
			content["image"] = base64.StdEncoding.EncodeToString(b.Bytes())
		}
		return r.shellAsk("system.share", content, actionTimeout)
	})
}

/*
badAppTarget 不许唤起的目标。空串 = 放行。

挡的是**本地文件与本地程序**,不是「不认识的 scheme」:深链的花样太多
(`weixin://`、`intent://`、厂商自定义),按白名单放行等于把 D196 做成摆设。
*/
func badAppTarget(target any) string {
	var s string
	switch x := target.(type) {
	case string:
		s = x
	case map[string]any:
		s, _ = x["data"].(string)
	}
	low := strings.ToLower(strings.TrimSpace(s))
	switch {
	case strings.HasPrefix(low, "file:"):
		return "openApp 不能打开本地文件(插件借它唤起本机程序)"
	case strings.HasPrefix(low, "jar:"), strings.HasPrefix(low, "content:"):
		return "openApp 不收 " + strings.SplitN(low, ":", 2)[0] + ": 这类本地资源地址"
	case len(low) > 1 && low[1] == ':' && (strings.Contains(low, "\\") || strings.Contains(low, "/")):
		return "openApp 不收本机路径" // C:\... 这种
	case strings.HasPrefix(low, "\\\\"):
		return "openApp 不收本机路径" // UNC 路径（双反斜杠开头）
	case strings.HasPrefix(low, "/") && !strings.HasPrefix(low, "//"):
		return "openApp 不收本机路径" // POSIX 绝对路径
	}
	return ""
}
