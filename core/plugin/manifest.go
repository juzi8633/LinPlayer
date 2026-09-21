// Package plugin 是插件宿主:安装、manifest、启停、防崩、市场(SPEC 第 4、14 章)。
package plugin

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"golang.org/x/text/language"
	"golang.org/x/text/message"
)

// schema/ 是 docs/plugin-system/api 下同名文件的副本(go:embed 够不到模块外);
// 两份一致由 TestSchemaCopiesInSync 守着,改 schema 先改 docs 那份再拷过来。
//
//go:embed schema/*.json
var schemaFS embed.FS

var (
	schemaOnce              sync.Once
	manifestSchema, idxSchm *jsonschema.Schema
	schemaErr               error
)

func schemas() (*jsonschema.Schema, *jsonschema.Schema, error) {
	schemaOnce.Do(func() {
		c := jsonschema.NewCompiler()
		for _, n := range []string{"manifest.schema.json", "index.schema.json"} {
			b, _ := schemaFS.ReadFile("schema/" + n)
			doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(b))
			if err != nil {
				schemaErr = err
				return
			}
			if err := c.AddResource(n, doc); err != nil {
				schemaErr = err
				return
			}
		}
		if manifestSchema, schemaErr = c.Compile("manifest.schema.json"); schemaErr != nil {
			return
		}
		idxSchm, schemaErr = c.Compile("index.schema.json")
	})
	return manifestSchema, idxSchm, schemaErr
}

// IDPattern 插件 id 规则(D47 D265)。
var IDPattern = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*/[a-z0-9]+(-[a-z0-9]+)*$`)

// Manifest manifest.json 里宿主要用的字段;完整结构以 schema 为准,原文另存 Raw。
type Manifest struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Version       string   `json:"version"`
	Description   string   `json:"description"`
	Main          string   `json:"main"`
	Icon          string   `json:"icon"`
	IconMono      string   `json:"iconMono"`
	MinAppVersion string   `json:"minAppVersion"`
	Platforms     []string `json:"platforms"`
	Requires      struct {
		Capabilities []string `json:"capabilities"`
		Components   []string `json:"components"`
	} `json:"requires"`
	LAN        bool   `json:"lan"`
	Paid       bool   `json:"paid"`
	Homepage   string `json:"homepage"`
	Repository string `json:"repository"`
	Issues     string `json:"issues"`
	License    string `json:"license"`
	Activation struct {
		Load     string `json:"load"`
		Lifetime string `json:"lifetime"`
	} `json:"activation"`
	Contributes Contributes `json:"contributes"`

	Raw json.RawMessage `json:"-"`
}

// Contributes 阶段 ① 宿主真正消费的贡献点;其余键只用于清单展示(ContributionList)。
type Contributes struct {
	DataSource *struct {
		ServerTypes     []ServerType `json:"serverTypes"`
		DisablePrefetch bool         `json:"disablePrefetch"`
	} `json:"dataSource"`
	Settings []SettingItem `json:"settings"`
	Hooks    *struct {
		ListTransform *struct {
			Lists []string `json:"lists"`
		} `json:"listTransform"`
		Navigate  bool `json:"navigate"`
		CardBadge bool `json:"cardBadge"`
	} `json:"hooks"`
	PageTakeovers []struct {
		Target string `json:"target"`
		Page   string `json:"page"`
	} `json:"pageTakeovers"`
	Registry *struct {
		Channels []struct {
			Name              string `json:"name"`
			RecommendedReader string `json:"recommendedReader"`
		} `json:"channels"`
	} `json:"registry"`
	// Anchors 官方页锚点上的注入 / 接管(SPEC 6.1 6.2,D155 D159)。
	Anchors []Anchor `json:"anchors"`
	// SettingsSections 官方设置页里的一节(D286 D289)。
	SettingsSections []SettingsSection `json:"settingsSections"`
	Pages            []struct {
		ID    string `json:"id"`
		Title string `json:"title"`
	} `json:"pages"`
	// Theme 一包一端的主题(SPEC 11.1,D72 D214 D215);Wallpaper 壁纸接管位(D441)。
	Theme     *ThemeContrib     `json:"theme"`
	Wallpaper *WallpaperContrib `json:"wallpaper"`
	// PlayerOverlays 盖在画面上的层;PlayerPanels 播放页侧栏里的一页(SPEC 9.5,D65 D279 D300)。
	PlayerOverlays []PlayerOverlay `json:"playerOverlays"`
	PlayerPanels   []PlayerPanel   `json:"playerPanels"`
	Sidebar        []SidebarEntry  `json:"sidebar"`
	HomeSections   []HomeSection   `json:"homeSections"`
}

// HomeSection 首页上的一栏(SPEC 6.1,D156 D303)。
//
// kind=items:插件返回条目,宿主画成和官方「最新」一样的海报行(主题自动跟随)。
// kind=custom:插件自己画一块(轮播、日历、流量这类),block 指向 definePlugin 的 blocks。
type HomeSection struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Kind  string `json:"kind"`
	Block string `json:"block,omitempty"`
	Shape string `json:"shape,omitempty"`
	// PluginID 由 HomeSections() 填,manifest 里没有这个字段
	PluginID string `json:"plugin_id,omitempty"`
}

// PlayerOverlay 盖在播放画面上的一层。默认点击穿透,声明 interactive 才拦截。
type PlayerOverlay struct {
	ID          string `json:"id"`
	Interactive bool   `json:"interactive,omitempty"`
}

// PlayerPanel 播放页侧栏里的一页(评论、剧情说明、节目单、解析)。
type PlayerPanel struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Icon  string `json:"icon,omitempty"`
}

// ThemeContrib 主题贡献点。一包一端 —— 三端 token 分开做,官方不做跨端映射(D72)。
type ThemeContrib struct {
	Platform string   `json:"platform"` // desktop | android | android_tv
	Axaml    []string `json:"axaml,omitempty"`
	JSON     string   `json:"json,omitempty"`
	Modes    []string `json:"modes,omitempty"`
}

// WallpaperContrib 壁纸接管位(D441)。
type WallpaperContrib struct {
	ID    string `json:"id"`
	Title string `json:"title,omitempty"`
}

// SidebarEntry 侧栏入口(D158 D305)。
type SidebarEntry struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Icon  string `json:"icon,omitempty"`
	Page  string `json:"page"`
}

// Anchor 一条锚点贡献。mode:before / after(注入,全生效)· replace / hide(接管,只生效一个)。
type Anchor struct {
	Anchor string `json:"anchor"`
	Mode   string `json:"mode"`
	Block  string `json:"block,omitempty"`
}

// SettingsSection 官方设置页里的一节。
type SettingsSection struct {
	Anchor   string        `json:"anchor"`
	Title    string        `json:"title"`
	Settings []SettingItem `json:"settings,omitempty"`
	Block    string        `json:"block,omitempty"`
}

// ServerType 插件在「添加服务器」里提供的服务器类型(D131)。
type ServerType struct {
	ID     string        `json:"id"`
	Name   string        `json:"name"`
	Icon   string        `json:"icon,omitempty"`
	Fields []SettingItem `json:"fields"`
}

// SettingItem manifest 声明的设置项 / 表单字段(D35 D267)。
type SettingItem struct {
	Key         string          `json:"key"`
	Type        string          `json:"type"`
	Title       string          `json:"title,omitempty"`
	Description string          `json:"description,omitempty"`
	Default     json.RawMessage `json:"default,omitempty"`
	Options     []struct {
		Value string `json:"value"`
		Label string `json:"label"`
	} `json:"options,omitempty"`
	Min       *float64        `json:"min,omitempty"`
	Max       *float64        `json:"max,omitempty"`
	Step      *float64        `json:"step,omitempty"`
	Action    string          `json:"action,omitempty"`
	Multiline bool            `json:"multiline,omitempty"`
	When      json.RawMessage `json:"when,omitempty"`
}

// ParseManifest 解析并校验(schema + id + semver)。错误信息给用户看,写中文。
func ParseManifest(b []byte) (*Manifest, error) {
	ms, _, err := schemas()
	if err != nil {
		return nil, fmt.Errorf("内置 schema 损坏: %w", err)
	}
	doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(b))
	if err != nil {
		return nil, fmt.Errorf("manifest.json 不是合法 JSON: %v", err)
	}
	if err := ms.Validate(doc); err != nil {
		return nil, fmt.Errorf("manifest.json 不合规: %s", schemaErrText(err))
	}
	var m Manifest
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, fmt.Errorf("manifest.json 解析失败: %v", err)
	}
	m.Raw = append(json.RawMessage(nil), b...)
	if m.Main == "" {
		m.Main = "main.js"
	}
	return &m, nil
}

// schemaPrinter 校验库只带英文文案;字段指针是中文提示的主体。
var schemaPrinter = message.NewPrinter(language.English)

// schemaErrText 取最深一层的错误,前面带 JSON 指针:用户要知道是哪个字段错了。
func schemaErrText(err error) string {
	ve, ok := err.(*jsonschema.ValidationError)
	if !ok {
		return err.Error()
	}
	var leaves []string
	var walk func(e *jsonschema.ValidationError)
	walk = func(e *jsonschema.ValidationError) {
		if len(e.Causes) == 0 {
			leaves = append(leaves, "/"+strings.Join(e.InstanceLocation, "/")+": "+e.ErrorKind.LocalizedString(schemaPrinter))
			return
		}
		for _, c := range e.Causes {
			walk(c)
		}
	}
	walk(ve)
	sort.Strings(leaves)
	if len(leaves) > 3 {
		leaves = append(leaves[:3], fmt.Sprintf("…共 %d 处", len(leaves)))
	}
	return strings.Join(leaves, ";")
}

// ValidateIndex 校验仓库 index.json(D119)。
func ValidateIndex(b []byte) error {
	_, is, err := schemas()
	if err != nil {
		return err
	}
	doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(b))
	if err != nil {
		return fmt.Errorf("index.json 不是合法 JSON: %v", err)
	}
	if err := is.Validate(doc); err != nil {
		return fmt.Errorf("index.json 不合规: %s", schemaErrText(err))
	}
	return nil
}

// ---------------------------------------------------------------- semver(D180 D181)

type semver struct {
	maj, min, pat int
	pre           []string
}

func parseSemver(s string) (semver, bool) {
	s = strings.SplitN(s, "+", 2)[0]
	core, pre, _ := strings.Cut(s, "-")
	parts := strings.Split(core, ".")
	if len(parts) != 3 {
		return semver{}, false
	}
	var v semver
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 || (len(p) > 1 && p[0] == '0') {
			return semver{}, false
		}
		switch i {
		case 0:
			v.maj = n
		case 1:
			v.min = n
		default:
			v.pat = n
		}
	}
	if pre != "" {
		v.pre = strings.Split(pre, ".")
	}
	return v, true
}

// CompareVersions a<b 返回 -1。预发布按 semver 规则比(1.3.0-beta < 1.3.0),不做特殊通道(D181)。
func CompareVersions(a, b string) int {
	x, ok1 := parseSemver(a)
	y, ok2 := parseSemver(b)
	if !ok1 || !ok2 {
		return strings.Compare(a, b)
	}
	for _, d := range []int{x.maj - y.maj, x.min - y.min, x.pat - y.pat} {
		if d != 0 {
			return sign(d)
		}
	}
	switch {
	case len(x.pre) == 0 && len(y.pre) == 0:
		return 0
	case len(x.pre) == 0:
		return 1
	case len(y.pre) == 0:
		return -1
	}
	for i := 0; i < len(x.pre) && i < len(y.pre); i++ {
		a, aErr := strconv.Atoi(x.pre[i])
		b, bErr := strconv.Atoi(y.pre[i])
		switch {
		case aErr == nil && bErr == nil:
			if a != b {
				return sign(a - b)
			}
		case aErr == nil:
			return -1
		case bErr == nil:
			return 1
		default:
			if c := strings.Compare(x.pre[i], y.pre[i]); c != 0 {
				return c
			}
		}
	}
	return sign(len(x.pre) - len(y.pre))
}

func sign(d int) int {
	switch {
	case d < 0:
		return -1
	case d > 0:
		return 1
	}
	return 0
}

// ContributionList 贡献点清单(安装确认、插件详情、市场分类用,D117 D79)。
func (m *Manifest) ContributionList() []string {
	var raw struct {
		Contributes map[string]json.RawMessage `json:"contributes"`
	}
	_ = json.Unmarshal(m.Raw, &raw)
	labels := map[string]string{
		"pages": "插件页面", "pageTakeovers": "接管官方页面", "anchors": "官方页面区块", "osd": "播放控制栏",
		"theme": "主题", "wallpaper": "壁纸", "launchTargets": "启动页", "nextUp": "下一个播什么",
		"shaders": "着色器", "sidebar": "侧栏入口", "homeSections": "首页栏目", "settings": "设置项",
		"settingsPage": "设置页", "settingsSections": "官方设置分节", "menus": "菜单项",
		"playerOverlays": "画面覆盖层", "globalOverlays": "全局悬浮层", "playerPanels": "播放页侧栏",
		"virtualLibraries": "虚拟媒体库", "searchActions": "搜索建议", "commands": "命令",
		"keybindings": "快捷键", "gestures": "手势", "remoteButtons": "遥控按钮", "windows": "子窗口",
		"trayMenu": "托盘菜单", "android": "Android 系统入口", "deepLinks": "深链", "externalInputs": "外部输入",
		"dataSource": "数据源", "providers": "提供者", "hooks": "钩子", "m3u8Filters": "m3u8 过滤器",
		"registry": "注册表通道", "background": "后台运行",
	}
	var out []string
	for k := range raw.Contributes {
		if l, ok := labels[k]; ok {
			out = append(out, l)
		} else {
			out = append(out, k)
		}
	}
	sort.Strings(out)
	if m.LAN {
		out = append(out, "会访问局域网")
	}
	return out
}

// Supports 这个插件能不能在本平台跑(D138)。
func (m *Manifest) Supports(platform string) bool {
	if len(m.Platforms) == 0 {
		return true
	}
	for _, p := range m.Platforms {
		if p == platform {
			return true
		}
	}
	return false
}
