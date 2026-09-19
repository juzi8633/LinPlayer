// Package source 是文件浏览型数据源的后端抽象。
//
// 三件事:列目录 / 搜索(可降级)/ 把文件解析成可播 URL(含逐流 headers)。
package source

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
)

// Kind 源类型标识。
//
// ★★ **开放键**,不是封闭枚举:内置源是固定小写字面量,插件贡献的源是
// `plugin:<插件id>/<源id>`。封闭枚举意味着加一个源就得改核心层重新编译,
// 插件永远塞不进分派表。
//
// ★ 线上表示是**裸小写字符串**。前端曾整套写成首字母大写,结果是每处比较恒 false、
// 登录送错值,而**两边都不报错**(见 docs/lessons)。
type Kind string

// 内置源。**顺序即枚举顺序**,给需要穷举的地方(跨语言契约测试)用。
// ★ 2026-09-04 范围裁剪:网盘(阿里/百度/115/189/139/夸克)、局域网源(SMB/WebDAV/FTP)、
//   资源站与 Ani-rss 全部**不做了** —— 本项目只做 Emby。资源站(VOD)将来只以**插件**
//   形式出现,走 `plugin:<插件id>/<源id>` 那条开放键通道,不再有内置后端。
//   开放键机制本身保留:它正是给插件源用的。
const (
	KindEmby Kind = "emby"
	// KindLocal 本机文件夹(用户用系统选择器挑的那个目录)。不是网盘 —— 拖个
	// 文件进来就能看,是播放器的基础能力,故在裁剪中保留。
	KindLocal Kind = "local"
)

// Builtin 全部内置源,顺序固定。
var Builtin = []Kind{KindEmby, KindLocal}

// pluginPrefix 插件贡献的源统一形如 `plugin:com.example.foo/mysrc`。
const pluginPrefix = "plugin:"

// IsBuiltin 是不是内置源。
func (k Kind) IsBuiltin() bool {
	for _, b := range Builtin {
		if b == k {
			return true
		}
	}
	return false
}

// IsPlugin 是不是插件贡献的源。
func (k Kind) IsPlugin() bool { return strings.HasPrefix(string(k), pluginPrefix) }

// PluginKind 拼一个插件源的 Kind。
func PluginKind(pluginID, srcID string) Kind { return Kind(pluginPrefix + pluginID + "/" + srcID) }

// SplitPlugin 把插件源的 Kind 拆回 (插件id, 源id)。不是插件源就返回 false。
//
// 按**最后一个** `/` 切:插件 id 自带一个 `/`(`plugin:alice/tvbox/src1` → `alice/tvbox` + `src1`),
// 源 id 规定不含 `/`(SPEC 8.1,D153)。
func SplitPlugin(k Kind) (string, string, bool) {
	rest, ok := strings.CutPrefix(string(k), pluginPrefix)
	if !ok {
		return "", "", false
	}
	i := strings.LastIndex(rest, "/")
	if i <= 0 || i == len(rest)-1 {
		return "", "", false
	}
	return rest[:i], rest[i+1:], true
}

// LegacyDebugLabel 逐字复刻 Rust 早期 `format!("{kind:?}")` 的输出(首字母大写)。
//
// ★ 这个方法看起来毫无道理,但**不能删**:那些字符串已经躺在用户的配置文件里了
// (无 base_url 的源 —— 夸克 Cookie 模式 —— 拿它当账号 id 和用户名)。
// 换个写法会让老账号在 upsert 时匹配不上、变成重复项,旧账号成孤儿。
func (k Kind) LegacyDebugLabel() string {
	s := string(k)
	if s == "" {
		return ""
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

// Entry 浏览返回的一行:文件夹或文件。
type Entry struct {
	// ID 继续浏览 / 取流的标识:OpenList = 完整路径,夸克 = fid,Ani-rss = filename。
	ID       string  `json:"id"`
	Name     string  `json:"name"`
	IsDir    bool    `json:"is_dir"`
	IsVideo  bool    `json:"is_video"`
	Size     *int64  `json:"size"`
	ThumbURL *string `json:"thumb_url"`
	// Raw 源原始数据,供 ResolvePlay 复用(避免二次请求)。
	Raw json.RawMessage `json:"raw"`
}

// PlayQuality 一档可选清晰度(转码源如夸克提供多档)。
type PlayQuality struct {
	ID    string `json:"id"`
	Label string `json:"label"`
	Rank  int    `json:"rank"`
}

// Subtitle 外挂字幕轨。
type Subtitle struct {
	URL         string            `json:"url"`
	Title       *string           `json:"title"`
	Language    *string           `json:"language"`
	HTTPHeaders map[string]string `json:"http_headers"`
}

// ResolvedPlay 交给播放器的最小可播单元:URL + 逐流 headers。
type ResolvedPlay struct {
	URL               string            `json:"url"`
	Title             string            `json:"title"`
	HTTPHeaders       map[string]string `json:"http_headers"`
	UserAgentOverride *string           `json:"user_agent_override"`
	Subtitles         []Subtitle        `json:"subtitles"`
	Qualities         []PlayQuality     `json:"qualities"`
	SelectedQualityID *string           `json:"selected_quality_id"`
}

// Simple 构造一个只有地址和头的可播单元。
//
// ★ 列表字段给**空切片不是 nil**:序列化成 JSON 时 nil 是 null,
// 前端 `.map()` 直接抛,而透明窗口下渲染抛错 = 一片黑且不报错。
func Simple(url, title string, headers map[string]string) ResolvedPlay {
	if headers == nil {
		headers = map[string]string{}
	}
	return ResolvedPlay{URL: url, Title: title, HTTPHeaders: headers,
		Subtitles: []Subtitle{}, Qualities: []PlayQuality{}}
}

// ---------------------------------------------------------------------------
// 错误
// ---------------------------------------------------------------------------

// Error 源后端统一错误。
//
// ★★ 两个布尔位都是**结构化**的,不靠文案判断。
// 黄金实现那边「不支持」只有一句中文,调用方靠字符串比对认它 ——
// 改一次文案就静默失效,而失效的表现是「搜索返回空,用户以为没搜到」。
type Error struct {
	Message string `json:"message"`
	// IsAuth 鉴权失效(UI 可引导重登)。
	IsAuth bool `json:"is_auth"`
	// Unsupported 这个源没有这个能力。调用方据此**静默退回**另一条路径,
	// 而不是把它当成一条真错误弹给用户。
	Unsupported bool `json:"unsupported,omitempty"`
}

func (e *Error) Error() string { return e.Message }

// Msg 一条普通错误。
func Msg(format string, a ...any) *Error { return &Error{Message: fmt.Sprintf(format, a...)} }

// Auth 鉴权失效。
func Auth(format string, a ...any) *Error {
	return &Error{Message: fmt.Sprintf(format, a...), IsAuth: true}
}

// Unsupported 「这个源不支持搜索」。
func Unsupported() *Error { return &Error{Message: "该源不支持搜索", Unsupported: true} }

// IsUnsupported 是不是「没这个能力」。
func IsUnsupported(err error) bool {
	var e *Error
	return errors.As(err, &e) && e.Unsupported
}

// IsAuthErr 是不是鉴权失效。
func IsAuthErr(err error) bool {
	var e *Error
	if errors.As(err, &e) {
		return e.IsAuth
	}
	return false
}

// ---------------------------------------------------------------------------
// 服务器与后端
// ---------------------------------------------------------------------------

// Server 一个浏览型源服务器的连接凭据。随 AppConfig 落盘(重启免登 + 多源并存)。
type Server struct {
	ID       string            `json:"id"`
	BaseURL  string            `json:"base_url"` // = ActiveLineURL,后端内部 normalize
	Username *string           `json:"username"`
	Password *string           `json:"password"`
	Token    *string           `json:"token"`           // 账密型主令牌
	Extra    map[string]string `json:"extra,omitempty"` // 夸克等多凭据(cookie / refresh_token…)
}

// Backend 文件浏览型源后端的最小抽象(三端复用,纯逻辑)。
type Backend interface {
	Kind() Kind

	// ListDir 列目录。dirID 为空表示根目录。
	ListDir(ctx context.Context, c *http.Client, s *Server, dirID string) ([]Entry, error)

	// ResolvePlay 把文件解析成可播单元(含取流所需 headers)。
	// 短效直链过期后播放层回调重解析。
	ResolvePlay(ctx context.Context, c *http.Client, s *Server, e *Entry, qualityID string) (*ResolvedPlay, error)
}

// Searcher 源内搜索。没有源端搜索能力的后端不实现它,UI 退回本地过滤。
type Searcher interface {
	Search(ctx context.Context, c *http.Client, s *Server, query string) ([]Entry, error)
}

// ProgressReporter 播放进度上报。有服务端观看记录的源(飞牛等)实现它。
//
// ★ 失败一律吞掉不打断播放 —— 进度没记上是小事,把正在看的片子打断是大事。
type ProgressReporter interface {
	ReportProgress(ctx context.Context, c *http.Client, s *Server, e *Entry,
		positionSecs, durationSecs float64, finished bool) error
}

// CredentialRotator **凭据轮换回写通道**。
//
// ★★ 存在的理由:后端只拿得到只读的 *Server,而 oplist 系与阿里云盘的
// refresh_token 是**一次性的** —— 刷新一次旧值当场作废。不回写的话内存里能用,
// 一重启就拿着死 token 去刷,表现为「用得好好的,重开就要重新授权」,且不报错。
//
// 调用方在每次 ListDir / Search / ResolvePlay 之后取一次;返回的 map 并入
// Server.Extra 后存盘。
type CredentialRotator interface {
	TakeRotatedCredentials(serverID string) map[string]string
}

// ---------------------------------------------------------------------------
// 公共辅助
// ---------------------------------------------------------------------------

// videoExtensions 认得的视频后缀。和黄金实现逐条一致。
var videoExtensions = map[string]bool{
	"mp4": true, "mkv": true, "avi": true, "mov": true, "wmv": true, "flv": true,
	"webm": true, "m4v": true, "mpg": true, "mpeg": true, "ts": true, "m2ts": true,
	"mts": true, "rmvb": true, "rm": true, "vob": true, "3gp": true, "f4v": true,
	"ogv": true, "m3u8": true, "iso": true, "divx": true, "asf": true, "mxf": true,
}

// IsVideoFileName 按后缀判断是不是视频。
func IsVideoFileName(name string) bool {
	i := strings.LastIndex(name, ".")
	if i < 0 {
		return false
	}
	return videoExtensions[strings.ToLower(name[i+1:])]
}

// SortEntries 文件夹在前、各自按名排序(大小写不敏感)。
func SortEntries(entries []Entry) {
	sort.SliceStable(entries, func(i, j int) bool {
		a, b := entries[i], entries[j]
		if a.IsDir != b.IsDir {
			return a.IsDir
		}
		return strings.ToLower(a.Name) < strings.ToLower(b.Name)
	})
}

// NormalizeBaseURL 去掉首尾空白与结尾斜杠。
func NormalizeBaseURL(raw string) string {
	return strings.TrimRight(strings.TrimSpace(raw), "/")
}
