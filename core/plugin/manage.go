package plugin

// 安装 / 卸载 / 启停 / 回退 / 设置项。安装、更新、卸载、启停都重启后生效(D170);开发版例外(D451)。

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"linplayer/core/plugin/rt"
)

// OfficialPrefix 官方插件作者名(D266)。
const OfficialPrefix = "linplayer/"

// Inspection 安装确认页要的信息(D117 D381 D247 D142)。
type Inspection struct {
	Manifest     json.RawMessage `json:"manifest"`
	ID           string          `json:"id"`
	Name         string          `json:"name"`
	Version      string          `json:"version"`
	Description  string          `json:"description"`
	Contributes  []string        `json:"contributes"`
	Components   []string        `json:"components"`
	LAN          bool            `json:"lan"`
	Size         int64           `json:"size"`
	Unofficial   bool            `json:"unofficial"`
	Installed    string          `json:"installed,omitempty"` // 已装版本
	OtherSource  bool            `json:"otherSource"`         // 已从别的来源装过(D48)
	Incompatible string          `json:"incompatible,omitempty"`
}

// Inspect 解一遍包、校验,但不落地。source 是将要记下的安装来源。
func (h *Host) Inspect(pkg, source string) (*Inspection, error) {
	tmp, err := os.MkdirTemp(root(), ".inspect-")
	if err != nil {
		if err := os.MkdirAll(root(), 0o755); err != nil {
			return nil, err
		}
		if tmp, err = os.MkdirTemp(root(), ".inspect-"); err != nil {
			return nil, err
		}
	}
	defer os.RemoveAll(tmp)
	m, err := Unpack(pkg, filepath.Join(tmp, "x"))
	if err != nil {
		return nil, err
	}
	st, _ := os.Stat(pkg)
	in := &Inspection{
		Manifest: m.Raw, ID: m.ID, Name: m.Name, Version: m.Version, Description: m.Description,
		Contributes: m.ContributionList(), Components: m.Requires.Components, LAN: m.LAN,
		Unofficial:   !(strings.HasPrefix(m.ID, OfficialPrefix) && source == OfficialMarketURL()),
		Incompatible: h.incompatible(m),
	}
	if st != nil {
		in.Size = st.Size()
	}
	h.mu.Lock()
	if r := h.record(m.ID); r != nil {
		in.Installed = r.Version
		in.OtherSource = r.Source != source
	}
	h.mu.Unlock()
	return in, nil
}

// incompatible 本机装不了的原因;空 = 能装。
func (h *Host) incompatible(m *Manifest) string {
	if h.platform != "" && !m.Supports(h.platform) {
		return "这个插件不支持当前设备"
	}
	if h.version != "" && CompareVersions(appCore(h.version), m.MinAppVersion) < 0 {
		return "需要升级 LinPlayer 到 " + m.MinAppVersion + " 或更高版本"
	}
	return ""
}

// appCore 应用版本去掉构建后缀:发布号是 `<VERSION>-build<N>`,按 semver 那是预发布、比 VERSION 小,
// 不去掉的话 2.0.0-build12 会被判成装不了要求 2.0.0 的插件。
func appCore(v string) string { return strings.SplitN(strings.SplitN(v, "+", 2)[0], "-", 2)[0] }

// AppCore 同 appCore,给 lp 填 minAppVersion 用。
func AppCore(v string) string { return appCore(v) }

// Install 把包暂存为下次启动生效的版本。source:仓库 index.json 地址或 "local"。
func (h *Host) Install(pkg, source string) (*Manifest, error) {
	if err := os.MkdirAll(root(), 0o755); err != nil {
		return nil, err
	}
	tmp, err := os.MkdirTemp(root(), ".stage-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(tmp)
	m, err := Unpack(pkg, filepath.Join(tmp, "x"))
	if err != nil {
		return nil, err
	}
	if why := h.incompatible(m); why != "" {
		return nil, errors.New(why)
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.record(m.ID)
	if r != nil && r.Source != source && r.Source != "" {
		return nil, fmt.Errorf("「%s」已从其它来源安装,要换来源请先卸载", m.Name)
	}
	if r != nil && r.Version == m.Version && r.Staged == "" && !r.Uninstall {
		return nil, fmt.Errorf("「%s」%s 已经装好了", m.Name, m.Version)
	}
	dest := pkgDir(m.ID, m.Version)
	_ = os.RemoveAll(dest)
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return nil, err
	}
	if err := os.Rename(filepath.Join(tmp, "x"), dest); err != nil {
		return nil, err
	}
	if r == nil {
		r = &Record{ID: m.ID, Source: source, Want: true, PendingNew: true, At: time.Now().Unix()}
		h.st.Plugins = append(h.st.Plugins, r)
	}
	r.Staged, r.StagedSource, r.Uninstall = m.Version, source, false
	h.saveLocked()
	return m, nil
}

// Uninstall 标记下次启动卸载(D38)。数据源的收藏与观看记录属于宿主,永远保留(D333)。
func (h *Host) Uninstall(id string, deleteData bool) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.record(id)
	if r == nil {
		return errors.New("没有安装这个插件")
	}
	r.Uninstall, r.DeleteData, r.Want = true, deleteData, false
	h.saveLocked()
	return nil
}

// CancelUninstall 撤回还没生效的卸载。
func (h *Host) CancelUninstall(id string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if r := h.record(id); r != nil && r.Uninstall {
		r.Uninstall, r.DeleteData, r.Want = false, false, r.Enabled
		h.saveLocked()
	}
}

// SetEnabled 改下次启动的启停(D170)。手动启用清掉自动禁用原因与安全模式横条(D376)。
func (h *Host) SetEnabled(id string, want bool) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.record(id)
	if r == nil {
		return errors.New("没有安装这个插件")
	}
	r.Want = want
	if want {
		r.AutoDisabled = ""
		h.st.SafeBanner = false
		h.st.DisabledAll = nil
	}
	h.saveLocked()
	return nil
}

// DisableAll 一键全部禁用,记住之前的状态(SPEC 14.4)。
func (h *Host) DisableAll() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.st.DisabledAll = map[string]bool{}
	for _, r := range h.st.Plugins {
		h.st.DisabledAll[r.ID] = r.Want
		r.Want = false
	}
	h.saveLocked()
}

// RestoreAll 恢复一键禁用前的状态。
func (h *Host) RestoreAll() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, r := range h.st.Plugins {
		if v, ok := h.st.DisabledAll[r.ID]; ok {
			r.Want = v
		}
	}
	h.st.DisabledAll = nil
	h.saveLocked()
}

// Rollback 回退到上一版并锁定不自动更新(D50 D448)。
func (h *Host) Rollback(id string) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	r := h.record(id)
	if r == nil || r.Prev == "" {
		return errors.New("没有可回退的版本")
	}
	if _, err := os.Stat(pkgDir(id, r.Prev)); err != nil {
		return errors.New("上一版的包已经不在了")
	}
	r.Staged, r.Locked, r.Want = r.Prev, true, true
	r.AutoDisabled = ""
	h.saveLocked()
	return nil
}

// SetLocked / SkipVersion:锁定版本、跳过某版(D448 D449)。
func (h *Host) SetLocked(id string, locked bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if r := h.record(id); r != nil {
		r.Locked = locked
		h.saveLocked()
	}
}

func (h *Host) SkipVersion(id, ver string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if r := h.record(id); r != nil {
		r.Skip = ver
		h.saveLocked()
	}
}

// PendingRestart 有没有插件改动等着重启生效(离开插件页 / 退出时弹窗,D170)。
func (h *Host) PendingRestart() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, r := range h.st.Plugins {
		if r.PendingRestart() {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------- 列表

// Info 插件页「已安装」一项(D221)。
type Info struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Version      string   `json:"version"`
	Author       string   `json:"author"`
	Description  string   `json:"description"`
	Source       string   `json:"source"`
	Official     bool     `json:"official"`
	Enabled      bool     `json:"enabled"`
	Want         bool     `json:"want"`
	Status       string   `json:"status"` // ok | autoDisabled | pendingRestart | update | dev
	AutoDisabled string   `json:"autoDisabled,omitempty"`
	Staged       string   `json:"staged,omitempty"`
	Uninstall    bool     `json:"uninstall,omitempty"`
	Prev         string   `json:"prev,omitempty"`
	Locked       bool     `json:"locked,omitempty"`
	Update       string   `json:"update,omitempty"` // 市场上可更新到的版本
	LoadMS       int64    `json:"loadMs,omitempty"`
	SlowStart    bool     `json:"slowStart,omitempty"`
	Loaded       bool     `json:"loaded"`
	Dev          bool     `json:"dev"`
	Contributes  []string `json:"contributes"`
	Icon         string   `json:"icon,omitempty"`
	Issues       string   `json:"issues,omitempty"`
	LAN          bool     `json:"lan"`
	Components   []string `json:"components"` // 需要的扩展组件(扩展组件页「谁在用」)
}

// List 已装插件,有问题的在前(已自动禁用 / 有更新 / 待重启),其余按名称(SPEC 14.4)。
func (h *Host) List(updates map[string]string) []Info {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := []Info{}
	seen := map[string]bool{}
	for _, r := range h.st.Plugins {
		seen[r.ID] = true
		in := Info{ID: r.ID, Version: r.Version, Source: r.Source, Enabled: r.Enabled, Want: r.Want,
			AutoDisabled: r.AutoDisabled, Staged: r.Staged, Uninstall: r.Uninstall, Prev: r.Prev, Locked: r.Locked,
			Official: strings.HasPrefix(r.ID, OfficialPrefix) && r.Source == OfficialMarketURL(), Contributes: []string{}}
		ver := r.Version
		if ver == "" {
			ver = r.Staged
		}
		if b, err := os.ReadFile(filepath.Join(pkgDir(r.ID, ver), "manifest.json")); err == nil {
			if m, err := ParseManifest(b); err == nil {
				in.Name, in.Description, in.Contributes, in.Icon, in.Issues, in.LAN = m.Name, m.Description, m.ContributionList(), m.Icon, m.Issues, m.LAN
				in.Components = m.Requires.Components
			}
		}
		if in.Name == "" {
			in.Name = r.ID
		}
		in.Author = strings.SplitN(r.ID, "/", 2)[0]
		if l := h.loaded[r.ID]; l != nil {
			in.Loaded, in.LoadMS, in.SlowStart = true, l.loadMS, l.loadMS > slowStartMark
		}
		if u := updates[r.ID]; u != "" && u != r.Skip && !r.Locked && CompareVersions(u, r.Version) > 0 {
			in.Update = u
		}
		switch {
		case r.AutoDisabled != "":
			in.Status = "autoDisabled"
		case in.Update != "":
			in.Status = "update"
		case r.PendingRestart():
			in.Status = "pendingRestart"
		default:
			in.Status = "ok"
		}
		if d := h.dev[r.ID]; d != nil {
			in.Dev, in.Status = true, "dev"
		}
		out = append(out, in)
	}
	for id, d := range h.dev {
		if seen[id] {
			continue
		}
		out = append(out, Info{ID: id, Name: d.m.Name, Version: d.m.Version, Author: strings.SplitN(id, "/", 2)[0],
			Description: d.m.Description, Source: "dev", Enabled: true, Want: true, Status: "dev", Dev: true,
			Contributes: d.m.ContributionList(), Components: d.m.Requires.Components, Loaded: h.loaded[id] != nil})
	}
	rank := map[string]int{"autoDisabled": 0, "update": 1, "pendingRestart": 2, "dev": 3, "ok": 4}
	sort.SliceStable(out, func(i, j int) bool {
		if rank[out[i].Status] != rank[out[j].Status] {
			return rank[out[i].Status] < rank[out[j].Status]
		}
		return out[i].Name < out[j].Name
	})
	return out
}

// ---------------------------------------------------------------- 设置项(D35 D267)

func findSetting(m *Manifest, key string) *SettingItem {
	for i := range m.Contributes.Settings {
		if m.Contributes.Settings[i].Key == key {
			return &m.Contributes.Settings[i]
		}
	}
	return nil
}

func (h *Host) settingValue(id string, m *Manifest, key string) any {
	s := findSetting(m, key)
	if s != nil && s.Type == "password" {
		sec, err := rt.OpenSecrets(DataDir(id))
		if err == nil {
			if v, ok := sec.Get("setting:" + key); ok {
				return v
			}
		}
		return nil
	}
	h.mu.Lock()
	raw, ok := h.st.Settings[id][key]
	h.mu.Unlock()
	if !ok && s != nil {
		raw = s.Default
	}
	if len(raw) == 0 {
		return nil
	}
	var v any
	_ = json.Unmarshal(raw, &v)
	return v
}

func (h *Host) setSetting(id string, m *Manifest, key string, v any) error {
	s := findSetting(m, key)
	if s == nil {
		return fmt.Errorf("manifest 里没有声明设置项 %s", key)
	}
	if s.Type == "password" {
		sec, err := rt.OpenSecrets(DataDir(id))
		if err != nil {
			return err
		}
		str, _ := v.(string)
		return sec.Set("setting:"+key, str)
	}
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.st.Settings == nil {
		h.st.Settings = map[string]map[string]json.RawMessage{}
	}
	if h.st.Settings[id] == nil {
		h.st.Settings[id] = map[string]json.RawMessage{}
	}
	h.st.Settings[id][key] = b
	h.saveLocked()
	return nil
}

// Settings 插件详情页:设置项定义 + 当前值(密码类只报「已设置」)。
func (h *Host) Settings(id string) ([]SettingItem, map[string]any, error) {
	m, err := h.Manifest(id)
	if err != nil {
		return nil, nil, err
	}
	vals := map[string]any{}
	for _, s := range m.Contributes.Settings {
		if s.Type == "password" {
			vals[s.Key] = h.settingValue(id, m, s.Key) != nil
			continue
		}
		vals[s.Key] = h.settingValue(id, m, s.Key)
	}
	items := m.Contributes.Settings
	if items == nil {
		items = []SettingItem{}
	}
	return items, vals, nil
}

// SetSetting 宿主设置页写入(插件没加载也能写)。
func (h *Host) SetSetting(id, key string, v any) error {
	m, err := h.Manifest(id)
	if err != nil {
		return err
	}
	return h.setSetting(id, m, key, v)
}

// Usage 占用:KV + data + cache(D143 D224)。
func Usage(id string) map[string]int64 {
	dir := DataDir(id)
	size := func(p string) int64 {
		var n int64
		_ = filepath.Walk(p, func(_ string, info os.FileInfo, err error) error {
			if err == nil && !info.IsDir() {
				n += info.Size()
			}
			return nil
		})
		return n
	}
	kv, _ := os.Stat(filepath.Join(dir, "kv.json"))
	var kvn int64
	if kv != nil {
		kvn = kv.Size()
	}
	return map[string]int64{"kv": kvn, "data": size(filepath.Join(dir, "data")), "cache": size(filepath.Join(dir, "cache"))}
}

// ClearData 清缓存或清数据。清数据要先卸下运行时(KV 在它内存里)。
func (h *Host) ClearData(id string, cacheOnly bool) error {
	dir := DataDir(id)
	if cacheOnly {
		return os.RemoveAll(filepath.Join(dir, "cache"))
	}
	h.unload(id)
	for _, p := range []string{"cache", "data", "kv.json", "secrets.bin"} {
		if err := os.RemoveAll(filepath.Join(dir, p)); err != nil {
			return err
		}
	}
	h.mu.Lock()
	delete(h.st.Settings, id)
	h.saveLocked()
	h.mu.Unlock()
	return nil
}
