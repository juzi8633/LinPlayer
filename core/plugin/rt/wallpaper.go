package rt

/*
`wallpaper` 命名空间(SPEC 11.5,D441 D442 D443)。

☠ **只有当前选中的那个壁纸插件调得动**。不判这一下的话,任何装上的插件都能
  把用户的桌布换掉 —— 而用户看到的是「我的壁纸自己变了」,根本不知道是哪个插件干的。
  拒绝要说清是「你不是当前选中的壁纸」,不是笼统一句没权限。
*/

import (
	"strings"

	"github.com/dop251/goja"
)

// WallpaperHooks 壁纸的宿主侧。
type WallpaperHooks struct {
	// Active 当前选中的壁纸插件 id。空 = 用主题自带的。
	Active func() string
	// Set 把内容交给壳(切换即时生效,不重启)。
	Set func(pluginID string, content map[string]any) error
}

// wallpaperKinds `.d.ts` 的 WallpaperContent 四种。认不出来的当场报,
// 不然壳收到一个不认识的 kind 只能忽略,表现是「set 了没反应」。
var wallpaperKinds = map[string]bool{"image": true, "video": true, "canvas": true, "shader": true}

func (r *Runtime) installWallpaper(sdk *goja.Object) {
	h := r.opt.Host.Wallpaper
	vm := r.vm
	o := vm.NewObject()
	_ = sdk.Set("wallpaper", o)

	_ = o.Set("set", func(c goja.FunctionCall) goja.Value {
		content, _ := exportJSON(r, c.Argument(0)).(map[string]any)
		id := r.opt.ID
		return r.async(func() (any, error) {
			if h == nil || h.Set == nil {
				return nil, &Error{Kind: KindUnsupported, Message: "这一版宿主还没有壁纸"}
			}
			if content == nil {
				return nil, &Error{Kind: KindInvalid, Message: "wallpaper.set 要给一份内容"}
			}
			kind := strings.TrimSpace(strOf(content["kind"]))
			if !wallpaperKinds[kind] {
				return nil, &Error{Kind: KindInvalid, Message: "认不出来的壁纸类型:" + kind}
			}
			if active := h.Active(); active != id {
				return nil, &Error{Kind: KindPermission, Message: "当前选中的壁纸不是这个插件,换不了壁纸"}
			}
			return nil, h.Set(id, content)
		})
	})
}

func strOf(v any) string {
	s, _ := v.(string)
	return s
}
