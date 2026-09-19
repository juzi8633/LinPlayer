package plugin

// 插件状态落盘:装了哪些、启停(待重启)、自动禁用原因、安全模式、仓库订阅。

import (
	"encoding/json"
	"os"
	"path/filepath"

	"linplayer/core/paths"
)

// Record 一个已装插件的状态(SPEC 4.5)。
type Record struct {
	ID      string `json:"id"`
	Version string `json:"version"` // 本次运行生效的版本
	// Source 安装来源:仓库 index.json 地址 / "local"(本地文件)。只从这里更新(D48)。
	Source string `json:"source"`
	// Enabled 本次运行是否启用(启动时由 Want 定下,运行中不变 —— 改动重启后生效,D170)。
	Enabled bool `json:"enabled"`
	// Want 用户要的下次启动状态。与 Enabled 不同 = 「待重启」(D221)。
	Want bool `json:"want"`
	// Staged 已下载、下次启动换上的版本(安装/更新都先落这里)。
	Staged string `json:"staged,omitempty"`
	// StagedSource 暂存版本的来源。
	StagedSource string `json:"stagedSource,omitempty"`
	// Uninstall 下次启动删除;DeleteData 同时删数据(D38)。
	Uninstall  bool `json:"uninstall,omitempty"`
	DeleteData bool `json:"deleteData,omitempty"`
	// Prev 上一版,新版连崩被禁时一键回退(D50)。
	Prev string `json:"prev,omitempty"`
	// AutoDisabled 自动禁用的原因(连错 / 安全模式),用户手动启用时清掉。
	AutoDisabled string `json:"autoDisabled,omitempty"`
	// Locked 装了旧版后锁定不自动更新(D448);Skip 跳过的版本(D449)。
	Locked bool   `json:"locked,omitempty"`
	Skip   string `json:"skip,omitempty"`
	// PendingNew 新装、还没经过一次重启:列表里显示「待重启」。
	PendingNew bool  `json:"pendingNew,omitempty"`
	At         int64 `json:"at"`
}

// PendingRestart 这条有没有要重启才生效的改动。
func (r *Record) PendingRestart() bool {
	return r.Want != r.Enabled || r.Staged != "" || r.Uninstall || r.PendingNew
}

type stateFile struct {
	Plugins      []*Record `json:"plugins"`
	Repos        []string  `json:"repos"`        // 用户订阅的仓库(官方市场隐含,不在这里,D118)
	GithubPrefix string    `json:"githubPrefix"` // GitHub 加速前缀(D370)
	AutoUpdate   bool      `json:"autoUpdate"`   // 默认关(D49)
	// SafeBanner 安全模式横条:保留到用户开过任一插件(D376)。
	SafeBanner  bool   `json:"safeBanner,omitempty"`
	SafeSuspect string `json:"safeSuspect,omitempty"`
	// DisabledAll 「一键全部禁用」前各插件的启用状态,可一键恢复。
	DisabledAll map[string]bool `json:"disabledAll,omitempty"`
	// Takeovers 接管位选择:位置 → 插件 id(空 = 官方,D15)。
	Takeovers map[string]string `json:"takeovers,omitempty"`
	// Settings 各插件 manifest 声明的设置项的值(密码类在插件密钥区,D267)。
	Settings map[string]map[string]json.RawMessage `json:"settings,omitempty"`
}

// boot 启动标记(SPEC 4.7):写「启动中」,稳定运行 30 秒后清。连续 2 次没清 = 连崩 2 次。
type boot struct {
	Booting bool   `json:"booting"`
	Crashes int    `json:"crashes"`
	Last    string `json:"last"` // 崩溃前最后在跑的插件
}

func root() string                 { return filepath.Join(paths.Root(), "plugins") }
func stateFilePath() string        { return filepath.Join(root(), "state.json") }
func bootFilePath() string         { return filepath.Join(root(), "boot.json") }
func pkgDir(id, ver string) string { return filepath.Join(root(), filepath.FromSlash(id), "pkg", ver) }

// DataDir 插件私有根:kv.json、secrets.bin、data/、cache/(SPEC 13.1)。
func DataDir(id string) string { return filepath.Join(root(), filepath.FromSlash(id)) }

func readJSON(p string, v any) error {
	b, err := os.ReadFile(p)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func writeJSON(p string, v any) error {
	b, err := json.MarshalIndent(v, "", " ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

func jsonUnmarshal(b []byte, v any) error { return json.Unmarshal(b, v) }
