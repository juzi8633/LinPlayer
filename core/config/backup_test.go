package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func backupCfg() *AppConfig {
	c := &AppConfig{
		DeviceID: "这台机器的身份", Theme: "dark", CompanionEnabled: true,
		AccountList: []Account{{
			Server: "https://例子.invalid", UserName: "u1",
			Token: "tok-1", UserID: "uid-1",
		}},
	}
	zero := 0
	c.Active = &zero
	p := DefaultPrefs()
	p.SubScale, p.DefaultSpeed = 1.6, 1.25
	_ = c.SetPrefs(p)
	return c
}

// ☠ **device_id 和 active 不许进备份**。两台机器顶着同一个 device_id 连同一台 Emby
// 会话会互相踢,而报出来的是「一登录另一台就掉线」—— 谁也想不到是备份带的。
func Test备份_不带机器身份(t *testing.T) {
	b, err := EncodeBackup(backupCfg(), 100, true, true)
	if err != nil {
		t.Fatalf("编码失败:%v", err)
	}
	s := string(b)
	if strings.Contains(s, "这台机器的身份") {
		t.Fatalf("备份里带上了 device_id:%s", s)
	}
	if strings.Contains(s, `"active"`) {
		t.Fatalf("备份里带上了 active 下标(合并之后它指的不是同一台服务器了):%s", s)
	}
}

// 容器**必须和二维码那条路一模一样** —— 第三方播放器的互通全靠这一点。
// 多出来的只能是 linplayer_settings 这一个键。
func Test备份_容器和二维码同一个形状(t *testing.T) {
	b, _ := EncodeBackup(backupCfg(), 100, true, true)
	var m map[string]any
	if json.Unmarshal(b, &m) != nil {
		t.Fatal("备份不是合法 JSON")
	}
	for _, k := range []string{"from", "version", "export_time", "configs", "_key"} {
		if _, ok := m[k]; !ok {
			t.Fatalf("缺 CommonConfig 容器的 %q 键,第三方就解不开了:%v", m, k)
		}
	}
	for k := range m {
		switch k {
		case "from", "version", "export_time", "configs", "_key", backupSettingsKey:
		default:
			t.Fatalf("多了一个第三方不认识的顶层键 %q", k)
		}
	}
	if m["from"] != transferClient {
		t.Fatalf("from 该是 %q,实得 %v", transferClient, m["from"])
	}
}

// 明文凭据**不许直接躺在文件里**。加密只是混淆级,但至少不能是明文。
func Test备份_凭据不是明文(t *testing.T) {
	b, _ := EncodeBackup(backupCfg(), 100, true, true)
	if strings.Contains(string(b), "tok-1") {
		t.Fatalf("token 明文躺在备份里了:%s", b)
	}
}

// 「只导设置不导账号」这一档要真的不带任何凭据 —— 它就是为「把设置发给别人」造的。
func Test备份_只导设置时一个账号都没有(t *testing.T) {
	b, _ := EncodeBackup(backupCfg(), 100, false, true)
	container, err := DecodeBackup(b)
	if err != nil {
		t.Fatalf("解不开:%v", err)
	}
	if p := PreviewBackup(container); p.Accounts != 0 {
		t.Fatalf("只导设置却带了 %d 个账号", p.Accounts)
	}
	if !PreviewBackup(container).HasSettings {
		t.Fatal("说好的设置没带上")
	}
}

// 一整圈:导出 → 解析 → 还原到一台干净的机器上。
func Test备份_导出再导入一圈下来值还在(t *testing.T) {
	b, _ := EncodeBackup(backupCfg(), 100, true, true)
	container, err := DecodeBackup(b)
	if err != nil {
		t.Fatalf("解不开:%v", err)
	}
	fresh := &AppConfig{DeviceID: "另一台机器"}
	n, restored := ApplyBackup(fresh, container, true, true)
	if n != 1 || !restored {
		t.Fatalf("还原结果不对:账号 %d 设置 %v", n, restored)
	}
	if fresh.DeviceID != "另一台机器" {
		t.Fatalf("还原把这台机器的 device_id 覆盖掉了:%q", fresh.DeviceID)
	}
	if len(fresh.AccountList) != 1 || fresh.AccountList[0].Token != "tok-1" {
		t.Fatalf("账号没还原回来:%+v", fresh.AccountList)
	}
	if fresh.Theme != "dark" || !fresh.CompanionEnabled {
		t.Fatalf("设置没还原回来:theme=%q companion=%v", fresh.Theme, fresh.CompanionEnabled)
	}
	if got := fresh.PrefsOf(); got.SubScale != 1.6 || got.DefaultSpeed != 1.25 {
		t.Fatalf("偏好没还原回来:%+v", got)
	}
}

// ☠ 导入是**合并不是覆盖**:这台机器上已经加好的服务器不许被抹掉。
func Test备份_导入是合并(t *testing.T) {
	b, _ := EncodeBackup(backupCfg(), 100, true, true)
	container, _ := DecodeBackup(b)
	mine := &AppConfig{AccountList: []Account{{Server: "https://我自己的.invalid", UserName: "me"}}}
	ApplyBackup(mine, container, true, true)
	if len(mine.AccountList) != 2 {
		t.Fatalf("合并之后该有 2 台,实得 %d:%+v", len(mine.AccountList), mine.AccountList)
	}
}

// 跨设备还原时,指向**另一台机器路径**的那几个偏好要摘掉。
// 留着的表现:设置页上明明写着一个外部播放器路径,点了没反应。
func Test备份_死路径要摘掉而活的要留下(t *testing.T) {
	alive := filepath.Join(t.TempDir(), "字体.ttf")
	if err := os.WriteFile(alive, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	raw := json.RawMessage(`{"ui_font":` + quote(alive) +
		`,"external_player":"D:\\不存在的\\播放器.exe","default_speed":1.5}`)
	got := dropDeadPaths(raw)
	var m map[string]any
	if json.Unmarshal(got, &m) != nil {
		t.Fatalf("摘完不是合法 JSON:%s", got)
	}
	if _, ok := m["external_player"]; ok {
		t.Fatalf("指向不存在路径的 external_player 该摘掉:%s", got)
	}
	if m["ui_font"] != alive {
		t.Fatalf("路径还在的 ui_font 不该摘 —— 还原到同一台机器时它是对的:%s", got)
	}
	if m["default_speed"] != 1.5 {
		t.Fatalf("不相干的偏好被动了:%s", got)
	}
}

func quote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// 用户把出码时复制的那段文本存成 .txt 再来导入 —— 这完全会发生,
// 而报「不是备份文件」帮不了他。
func Test备份_也认二维码那种字符串(t *testing.T) {
	qr := EncodeTransfer(backupCfg().AccountList, 100)
	container, err := DecodeBackup([]byte("  " + qr + "\n"))
	if err != nil {
		t.Fatalf("二维码文本该也认:%v", err)
	}
	if PreviewBackup(container).Accounts != 1 {
		t.Fatalf("从二维码文本里没解出账号")
	}
}
