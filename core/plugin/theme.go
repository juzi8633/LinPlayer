package plugin

/*
主题与壁纸(SPEC 11,D69 D72 D210 D212 D214~D216 D372 D441~D443 D527 D564)。

分工:**挑哪一个在核心层,长什么样在壳里**。
核心层只回答「现在该用谁的主题 / 壁纸、文件在哪、上次加载崩过没有」;
`.axaml` 怎么叠、JSON 怎么解释是各端自己的事(它们本来就不共用一套控件)。

☠ 加载失败要**回退官方主题**并记一笔(D372):
  主题坏了的表现是整个界面画不出来,而这时候用户连「换回官方主题」的按钮都点不到 ——
  所以回退必须由核心层记住,下次启动直接用官方,而不是等用户自己想办法。
*/

import (
	"context"
	"encoding/json"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/plugin/rt"
)

// ThemeInfo 一个可选的主题。
type ThemeInfo struct {
	PluginID string `json:"plugin_id"`
	Name     string `json:"name"`
	// Platform desktop | android | android_tv,一包一端(D72)。
	Platform string   `json:"platform"`
	Modes    []string `json:"modes,omitempty"`
	// Axaml 桌面主题的样式文件(包内相对路径);JSON 是手机 / TV 主题的那一份。
	Axaml []string `json:"axaml,omitempty"`
	JSON  string   `json:"json,omitempty"`
	// Dir 包解压目录:壳按它拼绝对路径。
	Dir string `json:"dir"`
}

// WallpaperInfo 一个可选的壁纸接管位。
type WallpaperInfo struct {
	PluginID string `json:"plugin_id"`
	Name     string `json:"name"`
	ID       string `json:"id"`
	Title    string `json:"title,omitempty"`
}

// Themes 这一端能选的主题。platform 为空 = 全部。
func (h *Host) Themes(platform string) []ThemeInfo {
	out := []ThemeInfo{}
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil || m.Contributes.Theme == nil {
			continue
		}
		t := m.Contributes.Theme
		if platform != "" && t.Platform != platform {
			continue
		}
		out = append(out, ThemeInfo{
			PluginID: i.ID, Name: i.Name, Platform: t.Platform, Modes: t.Modes,
			Axaml: t.Axaml, JSON: t.JSON, Dir: h.PkgDir(i.ID),
		})
	}
	return out
}

// Wallpapers 能选的壁纸。
func (h *Host) Wallpapers() []WallpaperInfo {
	out := []WallpaperInfo{}
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil || m.Contributes.Wallpaper == nil {
			continue
		}
		w := m.Contributes.Wallpaper
		out = append(out, WallpaperInfo{PluginID: i.ID, Name: i.Name, ID: w.ID, Title: w.Title})
	}
	return out
}

/*
ActiveTheme 这一端当前该用的主题。没选 / 选的那个已经不在 / 上次加载崩过 → 回空(= 官方主题)。

☠ 「上次崩过就不再自动用它」是这条路上唯一能救回来的机制:主题坏了整个界面画不出来,

	用户连换回官方的按钮都点不到。
*/
func (h *Host) ActiveTheme(platform string) *ThemeInfo {
	p := config.Current().PrefsOf()
	id := strings.TrimSpace(p.ActiveTheme)
	if id == "" || id == p.ThemeFailed {
		return nil
	}
	for _, t := range h.Themes(platform) {
		if t.PluginID == id {
			return &t
		}
	}
	return nil
}

// SetActiveTheme 选一个主题(下次启动生效,D69)。空串 = 官方主题。
func (h *Host) SetActiveTheme(id string) error {
	return updatePrefs(func(p *config.Prefs) {
		p.ActiveTheme = strings.TrimSpace(id)
		// 手动选过就把「上次崩了」清掉:用户可能刚修好这个主题
		p.ThemeFailed = ""
	})
}

// updatePrefs 改一项偏好并落盘。
func updatePrefs(f func(*config.Prefs)) error {
	c := config.Current()
	p := c.PrefsOf()
	f(&p)
	if err := c.SetPrefs(p); err != nil {
		return err
	}
	return c.Save()
}

// NoteThemeFailed 壳加载主题失败时报一次。下次启动不再自动用它(D372)。
func (h *Host) NoteThemeFailed(id, detail string) {
	if strings.TrimSpace(id) == "" {
		return
	}
	bus.Logf("error", "主题 %s 加载失败,已改用官方主题:%s", id, detail)
	_ = updatePrefs(func(p *config.Prefs) { p.ThemeFailed = id })
}

// ActiveWallpaper 当前选中的壁纸插件 id。空 = 用主题自带的(D441)。
func (h *Host) ActiveWallpaper() string {
	return strings.TrimSpace(config.Current().PrefsOf().ActiveWallpaper)
}

func (h *Host) SetActiveWallpaper(id string) error {
	return updatePrefs(func(p *config.Prefs) { p.ActiveWallpaper = strings.TrimSpace(id) })
}

func registerTheme() {
	bus.Register("plugin.themes", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		return Default().Themes(str(a, "platform")), nil
	})
	bus.Register("plugin.activeTheme", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		return Default().ActiveTheme(str(a, "platform")), nil
	})
	bus.Register("plugin.setActiveTheme", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		return nil, Default().SetActiveTheme(str(a, "id"))
	})
	bus.Register("plugin.themeFailed", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		Default().NoteThemeFailed(str(a, "id"), str(a, "detail"))
		return nil, nil
	})
	bus.Register("plugin.wallpapers", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		return Default().Wallpapers(), nil
	})
	bus.Register("plugin.activeWallpaper", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		return map[string]any{"id": Default().ActiveWallpaper()}, nil
	})
	// 壳在启动与切换壁纸之后问一次:只实现了 `wallpaper` 入口、从不调 set 的插件靠它才画得出来
	bus.Register("plugin.initialWallpaper", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		return Default().InitialWallpaper(ctx)
	})
	bus.Register("plugin.setActiveWallpaper", func(ctx context.Context, _ int64, a map[string]any) (any, error) {
		return nil, Default().SetActiveWallpaper(str(a, "id"))
	})
}

/*
wallpaperHooks 壁纸的宿主侧。

内容不落盘:壁纸是**这一刻长什么样**,插件重启后由它自己再 set 一次
(D442 的「按时间 / 按页面 / 随正在看的片变」本来就要每次算)。
存下来的话用户看到的是上次那张,而插件以为自己换过了。
*/
func wallpaperHooks() *rt.WallpaperHooks {
	return &rt.WallpaperHooks{
		Active: func() string { return Default().ActiveWallpaper() },
		Set: func(pluginID string, content map[string]any) error {
			m := map[string]any{"plugin": pluginID}
			for k, v := range content {
				m[k] = v
			}
			// 切换即时生效(D441):发事件,壳收到就换,不用重启
			bus.Emit("plugin.wallpaper", m, "wallpaper")
			return nil
		},
	}
}

/*
InitialWallpaper 问当前选中的壁纸插件要一份初始内容(`definePlugin({ wallpaper })`,D441)。

☠ 不问的话,`load: startup` 之外的壁纸插件**永远画不出来**:
  `wallpaper.set()` 是插件主动调的,而一个只实现了 `wallpaper` 入口的插件
  从头到尾不会调它 —— 用户选了壁纸,界面一点反应都没有,也不报错。
  壳在启动与切换壁纸之后各问一次。
*/
func (h *Host) InitialWallpaper(ctx context.Context) (any, error) {
	id := h.ActiveWallpaper()
	if id == "" {
		return nil, nil
	}
	l, err := h.get(id, "wallpaper")
	if err != nil {
		return nil, err
	}
	if !l.rt.Has("wallpaper") {
		// 只用 wallpaper.set() 的插件没有这个入口,这不是错
		return nil, nil
	}
	raw, err := l.rt.Invoke(ctx, rt.BudgetData, "wallpaper", nil, map[string]any{})
	if err != nil {
		return nil, err
	}
	var out map[string]any
	if json.Unmarshal(raw, &out) != nil || out == nil {
		return nil, nil
	}
	out["plugin"] = id
	return out, nil
}
