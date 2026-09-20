package rt

// SDK 对象:插件 `import {...} from '@linplayer/plugin-sdk'` 被 esbuild 映射到 globalThis.__linplayer_sdk(D207)。
// 命名空间与 api/plugin-sdk.d.ts 同名;阶段 ① 实现 app/storage/secrets/files/assets/cookies/registry/js/html/crypt/sources/settings。

import (
	"encoding/json"
	"errors"
	"io/fs"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/dop251/goja"
)

// SDKGlobal 注入的 SDK 全局名。lp build 与应用内 esbuild 都把 SDK 包映射到它。
const SDKGlobal = "__linplayer_sdk"

func (r *Runtime) installSDK() error {
	vm := r.vm
	sdk := vm.NewObject()
	ns := func(name string) *goja.Object {
		o := vm.NewObject()
		_ = sdk.Set(name, o)
		return o
	}

	_ = sdk.Set("PluginError", vm.Get("PluginError"))
	_ = sdk.Set("definePlugin", func(def *goja.Object) *goja.Object {
		r.def = def
		return def
	})

	// app
	app := ns("app")
	info := r.opt.Host.App
	_ = app.Set("version", info.Version)
	_ = app.Set("platform", info.Platform)
	_ = app.Set("formFactor", info.FormFactor)
	_ = app.Set("locale", info.Locale)
	_ = app.Set("devMode", info.DevMode)
	_ = app.Set("reducedMotion", false)
	// 能力由壳在启动后报上来,晚于插件加载也要读到最新的:用 getter 不用快照
	_ = app.DefineAccessorProperty("capabilities", vm.ToValue(func() goja.Value {
		c := Caps()
		return r.jsValue(map[string]any{"webview": c.WebView, "tv": info.FormFactor == "tv", "touch": info.FormFactor != "desktop",
			"components": map[string]bool{"jar-runtime": c.SpiderJar, "python": c.SpiderPy}})
	}), nil, goja.FLAG_FALSE, goja.FLAG_TRUE)

	// storage:同步 API(D284)
	st := ns("storage")
	_ = st.Set("get", func(k string) goja.Value {
		raw := r.kv.Get(k)
		if raw == nil {
			return goja.Undefined()
		}
		v, err := r.parseJSON(string(raw))
		if err != nil {
			return goja.Undefined()
		}
		return v
	})
	_ = st.Set("set", func(k string, v goja.Value) {
		if absent(v) {
			r.kv.Set(k, nil)
			return
		}
		s, err := r.stringify(v)
		if err != nil {
			r.throw(KindInvalid, "storage.set 的值不能序列化成 JSON:"+err.Error())
		}
		r.kv.Set(k, json.RawMessage(s))
	})
	_ = st.Set("remove", func(k string) { r.kv.Set(k, nil) })
	_ = st.Set("keys", func(c goja.FunctionCall) goja.Value {
		p := ""
		if a := c.Argument(0); !goja.IsUndefined(a) {
			p = a.String()
		}
		return vm.ToValue(r.kv.Keys(p))
	})

	// secrets
	sec := ns("secrets")
	_ = sec.Set("get", func(k string) goja.Value {
		if v, ok := r.sec.Get(k); ok {
			return vm.ToValue(v)
		}
		return goja.Undefined()
	})
	_ = sec.Set("set", func(k, v string) {
		if err := r.sec.Set(k, v); err != nil {
			r.throw(KindInternal, "密钥区写入失败:"+err.Error())
		}
	})
	_ = sec.Set("remove", func(k string) { _ = r.sec.Remove(k) })

	// cookies
	ck := ns("cookies")
	_ = ck.Set("get", func(jar, u string) goja.Value {
		return r.async(func() (any, error) {
			pu, err := parseHTTPURL(u)
			if err != nil {
				return nil, err
			}
			out := map[string]string{}
			for _, c := range r.jars.get(jar).Cookies(pu) {
				out[c.Name] = c.Value
			}
			return out, nil
		})
	})
	_ = ck.Set("set", func(jar, u, cookie string) goja.Value {
		return r.async(func() (any, error) {
			pu, err := parseHTTPURL(u)
			if err != nil {
				return nil, err
			}
			r.jars.get(jar).SetCookies(pu, parseSetCookies(strings.Split(cookie, "\n")))
			r.jars.persist(jar)
			return nil, nil
		})
	})
	_ = ck.Set("clear", func(jar string) goja.Value {
		return r.async(func() (any, error) { r.jars.clear(jar); return nil, nil })
	})

	// files / assets
	fl := ns("files")
	_ = fl.Set("data", r.pluginDir(filepath.Join(r.opt.DataDir, "data")))
	_ = fl.Set("cache", r.pluginDir(filepath.Join(r.opt.DataDir, "cache")))
	as := ns("assets")
	_ = as.Set("readText", func(p string) goja.Value {
		return r.async(func() (any, error) {
			b, err := r.readAsset(p)
			return string(b), err
		})
	})
	_ = as.Set("readBytes", func(p string) goja.Value {
		return r.async(func() (any, error) { return r.readAsset(p) })
	})
	_ = as.Set("url", func(p string) string {
		if r.opt.Host.AssetURL != nil {
			return r.opt.Host.AssetURL(p)
		}
		return ""
	})

	// sources / settings(宿主回调)
	src := ns("sources")
	h := r.opt.Host
	_ = src.Set("list", func() goja.Value {
		if h.SourcesList == nil {
			return vm.NewArray()
		}
		return r.jsValue(h.SourcesList())
	})
	_ = src.Set("add", func(d map[string]any, serverType goja.Value) goja.Value {
		st := ""
		if !absent(serverType) {
			st = serverType.String()
		}
		return r.async(func() (any, error) {
			return nil, callHost(h.SourcesAdd != nil, func() error { return h.SourcesAdd(d, st) })
		})
	})
	_ = src.Set("update", func(id string, patch map[string]any) goja.Value {
		return r.async(func() (any, error) {
			return nil, callHost(h.SourcesUpdate != nil, func() error { return h.SourcesUpdate(id, patch) })
		})
	})
	_ = src.Set("remove", func(id string) goja.Value {
		return r.async(func() (any, error) {
			return nil, callHost(h.SourcesRemove != nil, func() error { return h.SourcesRemove(id) })
		})
	})
	set := ns("settings")
	_ = set.Set("get", func(k string) goja.Value {
		if h.SettingGet == nil {
			return goja.Undefined()
		}
		return r.jsValue(h.SettingGet(k))
	})
	_ = set.Set("set", func(k string, v goja.Value) {
		if h.SettingSet == nil {
			r.throw(KindUnsupported, "宿主没有提供设置存储")
		}
		if err := h.SettingSet(k, v.Export()); err != nil {
			r.throw(KindInvalid, err.Error())
		}
	})

	r.installRegistry(ns("registry"))
	r.installJS(ns("js"))
	r.installHTML(ns("html"))
	r.installCrypt(ns("crypt"))
	r.installShell(sdk)
	if err := r.installUI(sdk); err != nil {
		return err
	}
	return vm.Set(SDKGlobal, sdk)
}

func parseHTTPURL(u string) (*url.URL, error) {
	pu, err := url.Parse(u)
	if err != nil || (pu.Scheme != "http" && pu.Scheme != "https") {
		return nil, &Error{Kind: KindInvalid, Message: "不是 http/https 地址:" + u}
	}
	return pu, nil
}

func callHost(ok bool, f func() error) error {
	if !ok {
		return &Error{Kind: KindUnsupported, Message: "宿主没有提供这项能力"}
	}
	if err := f(); err != nil {
		var pe *Error
		if errors.As(err, &pe) {
			return pe
		}
		return &Error{Kind: KindInvalid, Message: err.Error()}
	}
	return nil
}

// safeJoin 把插件给的相对路径钉在 root 里;越界返回错误。
func safeJoin(root, p string) (string, error) {
	clean := path.Clean("/" + strings.ReplaceAll(p, "\\", "/"))
	full := filepath.Join(root, filepath.FromSlash(clean))
	if full != root && !strings.HasPrefix(full, root+string(filepath.Separator)) {
		return "", &Error{Kind: KindPermission, Message: "路径越界:" + p}
	}
	return full, nil
}

func (r *Runtime) readAsset(p string) ([]byte, error) {
	full, err := safeJoin(r.opt.PkgDir, p)
	if err != nil {
		return nil, err
	}
	b, err := os.ReadFile(full)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, &Error{Kind: KindNotFound, Message: "包内没有这个文件:" + p}
	}
	return b, err
}

// pluginDir files.data / files.cache 的实现:只能读写自己的目录(D245)。
func (r *Runtime) pluginDir(root string) *goja.Object {
	vm := r.vm
	o := vm.NewObject()
	resolve := func(p string) (string, error) { return safeJoin(root, p) }
	_ = o.Set("readText", func(p string) goja.Value {
		return r.async(func() (any, error) {
			f, err := resolve(p)
			if err != nil {
				return nil, err
			}
			b, err := os.ReadFile(f)
			if err != nil {
				return nil, fileErr(err, p)
			}
			return string(b), nil
		})
	})
	_ = o.Set("readBytes", func(p string) goja.Value {
		return r.async(func() (any, error) {
			f, err := resolve(p)
			if err != nil {
				return nil, err
			}
			b, err := os.ReadFile(f)
			if err != nil {
				return nil, fileErr(err, p)
			}
			return b, nil
		})
	})
	_ = o.Set("write", func(p string, data goja.Value) goja.Value {
		b, ok := r.bytesOf(data)
		if !ok {
			r.throw(KindInvalid, "write 需要字符串或 ArrayBuffer")
		}
		b = append([]byte(nil), b...) // goroutine 里用,拷一份
		return r.async(func() (any, error) {
			f, err := resolve(p)
			if err != nil {
				return nil, err
			}
			return nil, writeFileAtomic(f, b)
		})
	})
	_ = o.Set("openWrite", func(p string) goja.Value {
		return r.async(func() (any, error) {
			f, err := resolve(p)
			if err != nil {
				return nil, err
			}
			if err := os.MkdirAll(filepath.Dir(f), 0o755); err != nil {
				return nil, err
			}
			fh, err := os.Create(f)
			if err != nil {
				return nil, err
			}
			return func() goja.Value { return r.writableStream(fh) }, nil
		})
	})
	_ = o.Set("exists", func(p string) goja.Value {
		return r.async(func() (any, error) {
			f, err := resolve(p)
			if err != nil {
				return nil, err
			}
			_, err = os.Stat(f)
			return err == nil, nil
		})
	})
	_ = o.Set("remove", func(p string) goja.Value {
		return r.async(func() (any, error) {
			f, err := resolve(p)
			if err != nil {
				return nil, err
			}
			if err := os.RemoveAll(f); err != nil {
				return nil, err
			}
			return nil, nil
		})
	})
	_ = o.Set("list", func(c goja.FunctionCall) goja.Value {
		dir := ""
		if a := c.Argument(0); !goja.IsUndefined(a) {
			dir = a.String()
		}
		return r.async(func() (any, error) {
			f, err := resolve(dir)
			if err != nil {
				return nil, err
			}
			ents, err := os.ReadDir(f)
			if errors.Is(err, fs.ErrNotExist) {
				return []any{}, nil
			}
			if err != nil {
				return nil, err
			}
			out := make([]map[string]any, 0, len(ents))
			for _, e := range ents {
				info, _ := e.Info()
				var size int64
				if info != nil {
					size = info.Size()
				}
				out = append(out, map[string]any{"name": e.Name(), "size": size, "dir": e.IsDir()})
			}
			return out, nil
		})
	})
	return o
}

func fileErr(err error, p string) error {
	if errors.Is(err, fs.ErrNotExist) {
		return &Error{Kind: KindNotFound, Message: "文件不存在:" + p}
	}
	return err
}

// writableStream openWrite 返回的最小 WritableStream:getWriter().write/close,也可直接 write/close。
func (r *Runtime) writableStream(fh *os.File) goja.Value {
	vm := r.vm
	w := vm.NewObject()
	_ = w.Set("write", func(chunk goja.Value) goja.Value {
		b, ok := r.bytesOf(chunk)
		if !ok {
			r.throw(KindInvalid, "write 需要 Uint8Array 或字符串")
		}
		b = append([]byte(nil), b...)
		return r.async(func() (any, error) { _, err := fh.Write(b); return nil, err })
	})
	_ = w.Set("close", func() goja.Value { return r.async(func() (any, error) { return nil, fh.Close() }) })
	_ = w.Set("abort", func() goja.Value { return r.async(func() (any, error) { return nil, fh.Close() }) })
	_ = w.Set("releaseLock", func() {})
	_ = w.Set("getWriter", func() goja.Value { return w })
	r.disposes = append(r.disposes, func() { _ = fh.Close() })
	return w
}
