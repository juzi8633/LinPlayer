package config

//
// 备份与还原 —— 一个文件装走「服务器 + 设置」,PC 和手机互通(用户 2026-09-08)。
//
// 容器**就是** transfer.go 那个 Richasy/Rodel CommonConfig 容器,一个字节都没改:
// `{from, version, export_time, configs[], _key}`。备份文件只是把它写成明文 JSON
// (不 gzip、不加前缀,因为文件不用塞进二维码),另外多挂一个
// `linplayer_settings` 段装我们自己的设置。
//
// ★ **另起一套格式是最容易的错**。共用容器换来的是:任何已经能读 CommonConfig
//   的第三方播放器,把我们的备份文件当配置文件喂进去就能拿到服务器和凭据 ——
//   它只要无视那个多出来的 `linplayer_settings` 键。格式文档见
//   docs/backup-format.md。
//
// ☠ **文件里带着 token 和密码**,而加密是混淆级(密钥随文件走)。
//   UI 必须显示这句警示 —— 用户会把备份文件发到群里。

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// backupSettingsKey 设置段挂在容器里的键名。
//
// ★ 名字带 `linplayer` 前缀是**故意**的:第三方实现按未知键忽略处理,
// 前缀让它一眼看出这是谁家的扩展,不会去猜。
const backupSettingsKey = "linplayer_settings"

// backupSettings 备份里带走的设置。
//
// ☠ **device_id 和 active 不在里面,这是有理由的**:
//   - device_id 是这台机器的身份。两台机器顶着同一个 id 连同一台 Emby,
//     会话会互相踢掉,而报出来的是「一登录另一台就掉线」,谁也想不到是备份带的。
//   - active 是账号列表的**下标**。导入是合并不是覆盖,合并之后同一个下标
//     指的不是同一台服务器了。
type backupSettings struct {
	Theme                 string          `json:"theme,omitempty"`
	CompanionEnabled      bool            `json:"companion_enabled"`
	PluginOfficialEnabled bool            `json:"plugin_official_enabled"`
	Prefs                 json.RawMessage `json:"prefs,omitempty"`
	DanmakuSources        json.RawMessage `json:"danmaku_sources,omitempty"`
	Proxy                 json.RawMessage `json:"proxy,omitempty"`
	SyncTrakt             json.RawMessage `json:"sync_trakt,omitempty"`
	SyncBangumi           json.RawMessage `json:"sync_bangumi,omitempty"`
	PluginSources         json.RawMessage `json:"plugin_sources,omitempty"`
}

// EncodeBackup 出一份备份文件的内容。
//
// `withAccounts=false` = 只备份设置,不带任何凭据 —— 想把自己的设置发给别人时用的
// 那一档。没有这一档的话,「分享我的设置」这件事只能靠手抄。
func EncodeBackup(c *AppConfig, exportTimeUnix int64, withAccounts, withSettings bool) ([]byte, error) {
	accounts := c.AccountList
	if !withAccounts {
		accounts = nil
	}
	container := buildContainer(accounts, exportTimeUnix)
	if withSettings {
		b, err := json.Marshal(backupSettings{
			Theme:                 c.Theme,
			CompanionEnabled:      c.CompanionEnabled,
			PluginOfficialEnabled: c.PluginOfficialEnabled,
			Prefs:                 c.Prefs,
			DanmakuSources:        c.DanmakuSources,
			Proxy:                 c.Proxy,
			SyncTrakt:             c.SyncTrakt,
			SyncBangumi:           c.SyncBangumi,
			PluginSources:         c.PluginSources,
		})
		if err != nil {
			return nil, err
		}
		container[backupSettingsKey] = json.RawMessage(b)
	}
	// 缩进过的:备份文件是用户会打开看一眼的东西,压成一行没有任何好处。
	return json.MarshalIndent(container, "", "  ")
}

// BackupPreview 导入前先看清楚要还原什么。
type BackupPreview struct {
	From        string `json:"from"`
	ExportTime  int64  `json:"export_time"`
	Accounts    int    `json:"accounts"`
	HasSettings bool   `json:"has_settings"`
}

// DecodeBackup 解一份备份文件。
//
// ★ **也认二维码那种字符串**:用户把出码时复制的那段文本存成了 .txt 再来导入,
// 这是完全会发生的事,而报「不是备份文件」帮不了他。
func DecodeBackup(raw []byte) (map[string]any, error) {
	if s := strings.TrimSpace(string(raw)); strings.HasPrefix(s, transferPrefix) {
		accounts, err := DecodeTransfer(s)
		if err != nil {
			return nil, err
		}
		return buildContainer(accounts, 0), nil
	}
	var container map[string]any
	if err := json.Unmarshal(raw, &container); err != nil {
		return nil, fmt.Errorf("这不是一份备份文件(JSON 都解不开)")
	}
	if _, ok := container["configs"]; !ok {
		if _, ok := container[backupSettingsKey]; !ok {
			return nil, fmt.Errorf("这不是一份备份文件(既没有 configs 也没有设置段)")
		}
	}
	return container, nil
}

// PreviewBackup 只读不写,给导入确认框用。
func PreviewBackup(container map[string]any) BackupPreview {
	p := BackupPreview{Accounts: len(accountsFromContainer(container))}
	p.From, _ = container["from"].(string)
	if v, ok := container["export_time"].(float64); ok {
		p.ExportTime = int64(v)
	}
	_, p.HasSettings = container[backupSettingsKey]
	return p
}

// ApplyBackup 把备份写回配置。**调用方负责 Save。**
//
// 返回 (导入的账号数, 有没有还原设置)。
func ApplyBackup(c *AppConfig, container map[string]any, withAccounts, withSettings bool) (int, bool) {
	n := 0
	if withAccounts {
		incoming := accountsFromContainer(container)
		n = len(incoming)
		// ★★ **合并不是覆盖**(同 configImportQr):覆盖的话用户在这台机器上
		//   已经加好的服务器会被抹掉,而他以为只是「把老机器上的搬过来」。
		c.AccountList = MergeAccounts(c.AccountList, incoming)
		if c.Active == nil && len(c.AccountList) > 0 {
			zero := 0
			c.Active = &zero
		}
	}
	raw, ok := container[backupSettingsKey]
	if !withSettings || !ok {
		return n, false
	}
	b, err := json.Marshal(raw)
	if err != nil {
		return n, false
	}
	var st backupSettings
	if json.Unmarshal(b, &st) != nil {
		return n, false
	}
	if st.Theme != "" {
		c.Theme = st.Theme
	}
	c.CompanionEnabled = st.CompanionEnabled
	c.PluginOfficialEnabled = st.PluginOfficialEnabled
	assign := func(dst *json.RawMessage, src json.RawMessage) {
		if len(src) > 0 {
			*dst = src
		}
	}
	assign(&c.DanmakuSources, st.DanmakuSources)
	assign(&c.Proxy, st.Proxy)
	assign(&c.SyncTrakt, st.SyncTrakt)
	assign(&c.SyncBangumi, st.SyncBangumi)
	assign(&c.PluginSources, st.PluginSources)
	if len(st.Prefs) > 0 {
		c.Prefs = dropDeadPaths(st.Prefs)
	}
	return n, true
}

// machineLocalPathKeys 偏好里存**这台机器的绝对路径**的那几个键。
var machineLocalPathKeys = []string{"ui_font", "screenshot_dir", "external_player"}

/* dropDeadPaths 摘掉指向不存在路径的那几个偏好。

   ☠ 备份跨设备走的时候,这三个键装的是**另一台机器上的路径**。原样还原的表现:
   界面字体静默回落系统字体(还好)、截图按下去报一个路径错(莫名其妙)、
   外部播放器点了没反应(最难查,因为设置页上明明写着一个路径)。

   ★ 判据是「路径还在不在」而不是「是不是跨设备」:还原到**同一台机器**
   (备份的主要用途)时路径都在,一个都不摘。 */
func dropDeadPaths(prefs json.RawMessage) json.RawMessage {
	var m map[string]json.RawMessage
	if json.Unmarshal(prefs, &m) != nil {
		return prefs
	}
	changed := false
	for _, k := range machineLocalPathKeys {
		raw, ok := m[k]
		if !ok {
			continue
		}
		var p string
		if json.Unmarshal(raw, &p) != nil || strings.TrimSpace(p) == "" {
			continue
		}
		if _, err := os.Stat(p); err != nil {
			delete(m, k)
			changed = true
		}
	}
	if !changed {
		return prefs
	}
	b, err := json.Marshal(m)
	if err != nil {
		return prefs
	}
	return b
}
