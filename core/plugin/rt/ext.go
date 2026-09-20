package rt

// `ext` 命名空间(SPEC 18.5,D380~D382):宿主可下载的大件原生运行物。
//
// ☠ `ensure` 是**会弹确认框的**:几十到上千 MB 的东西不能悄悄开始下。
//   用户拒绝时抛 unsupported(不是 internal)—— 插件该据此降级,
//   而不是把它当成「出错了」去重试。

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"

	"github.com/dop251/goja"
)

// ExtHooks `ext` 命名空间背后的宿主实现。
type ExtHooks struct {
	// Status 这个组件装了没有。size 是**还要下多少**,确认框拿它说话。
	Status func(component string) (ExtStatus, error)
	// Ensure 真的去下。到这里时用户已经点过确认了。
	Ensure func(component string) error
}

// ExtStatus `.d.ts` 的 ext.status 返回值。
type ExtStatus struct {
	Installed bool   `json:"installed"`
	Version   string `json:"version,omitempty"`
	Size      int64  `json:"size,omitempty"`
}

// extComponents 合法的组件名(`.d.ts` 的 ExtComponent)。
//
// 名字拼错时要当场说清楚:传 "wisper" 的表现否则是「ensure 一直不返回」,
// 而插件作者会以为是下载卡住了。
var extComponents = map[string]bool{
	"interp": true, "jar-runtime": true, "python": true,
	"geckoview": true, "whisper": true, "ffmpeg": true,
}

func (r *Runtime) installExt(sdk *goja.Object) {
	h := r.opt.Host.Ext
	vm := r.vm
	o := vm.NewObject()
	_ = sdk.Set("ext", o)

	name := func(c goja.FunctionCall) (string, error) {
		n := strings.TrimSpace(c.Argument(0).String())
		if !extComponents[n] {
			return "", &Error{Kind: KindInvalid, Message: "没有这个扩展组件:" + n}
		}
		return n, nil
	}

	_ = o.Set("status", func(c goja.FunctionCall) goja.Value {
		n, err := name(c)
		return r.async(func() (any, error) {
			if err != nil {
				return nil, err
			}
			if h == nil || h.Status == nil {
				return ExtStatus{}, nil
			}
			return h.Status(n)
		})
	})

	_ = o.Set("ensure", func(c goja.FunctionCall) goja.Value {
		n, err := name(c)
		return r.async(func() (any, error) {
			if err != nil {
				return nil, err
			}
			if h == nil || h.Ensure == nil || h.Status == nil {
				return nil, &Error{Kind: KindUnsupported, Message: "这一版宿主不能下载扩展组件"}
			}
			st, err := h.Status(n)
			if err != nil {
				return nil, err
			}
			if st.Installed {
				return nil, nil
			}
			if err := r.confirmDownload(n, st.Size); err != nil {
				return nil, err
			}
			return nil, h.Ensure(n)
		})
	})
}

/*
confirmDownload D381 的那道确认框。

放在 rt 这一层而不是各宿主实现里:这条规矩是**对插件**的 —— 哪个组件都一样要问,
落到宿主实现里就变成「哪个实现记得问哪个就问」。
☠ 壳没接对话框时**不下**:几百 MB 悄悄开始下是这条决定要防的正事,
  「问不了就默认同意」等于没有这条决定。
*/
func (r *Runtime) confirmDownload(component string, size int64) error {
	if !Caps().Shell {
		return &Error{Kind: KindUnsupported, Message: "这一版壳还没接确认框,不能开始下载 " + component}
	}
	out, err := ShellRequest(context.Background(), r.opt.ID, "ui.confirm", map[string]any{
		"title":   "下载扩展组件",
		"message": "「" + component + "」还没装" + sizeHint(size) + ",现在下载吗?",
		"confirm": "下载",
	}, dialogTimeout)
	if err != nil {
		return err
	}
	var ok bool
	if json.Unmarshal(out, &ok) != nil || !ok {
		return &Error{Kind: KindUnsupported, Message: "用户没同意下载 " + component}
	}
	return nil
}

func sizeHint(size int64) string {
	if size <= 0 {
		return ""
	}
	const mb = 1024 * 1024
	return ",要下大约 " + strconv.FormatInt((size+mb-1)/mb, 10) + " MB"
}
