package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"linplayer/core/paths"
)

// plant 造一份「上一版安装」:<base>/<name>/userdata/config.json
func plant(t *testing.T, base, name, body string, age time.Duration) string {
	t.Helper()
	dir := filepath.Join(base, name, "userdata")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, "config.json")
	if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	mt := time.Now().Add(-age)
	if err := os.Chtimes(p, mt, mt); err != nil {
		t.Fatal(err)
	}
	return dir
}

// ★★ 用户报的「每次更新账号就没了」:新版解压到旁边的新目录,
// 那里的 userdata/ 是空的。这条用例钉住「空数据根会去旁边接管」。
func TestAdopt空的新装目录从旁边接管(t *testing.T) {
	base := t.TempDir()
	old := plant(t, base, "LinPlayer-1.0", `{"device_id":"dev-old"}`, time.Hour)
	if err := os.WriteFile(filepath.Join(old, "history.json"), []byte(`[]`), 0o644); err != nil {
		t.Fatal(err)
	}

	newRoot := filepath.Join(base, "LinPlayer-1.1", "userdata")
	if err := os.MkdirAll(newRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	paths.SetRoot(newRoot)

	from, n := AdoptPreviousInstall()
	if n < 2 {
		t.Fatalf("该搬 config.json + history.json,实得 %d 项(来源 %q)", n, from)
	}
	b, err := os.ReadFile(filepath.Join(newRoot, "config.json"))
	if err != nil || string(b) != `{"device_id":"dev-old"}` {
		t.Fatalf("配置没搬过来: %v %q", err, b)
	}
}

// ★ 已经有配置了就**一个字都不许动** —— 覆盖用户现有账号比不接管坏得多。
func TestAdopt已有配置绝不覆盖(t *testing.T) {
	base := t.TempDir()
	plant(t, base, "LinPlayer-1.0", `{"device_id":"dev-old"}`, time.Hour)

	newRoot := filepath.Join(base, "LinPlayer-1.1", "userdata")
	if err := os.MkdirAll(newRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	mine := `{"device_id":"dev-mine"}`
	if err := os.WriteFile(filepath.Join(newRoot, "config.json"), []byte(mine), 0o644); err != nil {
		t.Fatal(err)
	}
	paths.SetRoot(newRoot)

	if from, n := AdoptPreviousInstall(); n != 0 {
		t.Fatalf("已有配置却搬了 %d 项(来源 %q)", n, from)
	}
	b, _ := os.ReadFile(filepath.Join(newRoot, "config.json"))
	if string(b) != mine {
		t.Fatalf("现有配置被冲了: %q", b)
	}
}

// 旁边有好几版时取**最后在用的那份**(config.json 最新)。
func TestAdopt多份取最新的那份(t *testing.T) {
	base := t.TempDir()
	plant(t, base, "LinPlayer-0.9", `{"device_id":"dev-ancient"}`, 72*time.Hour)
	plant(t, base, "LinPlayer-1.0", `{"device_id":"dev-recent"}`, time.Minute)

	newRoot := filepath.Join(base, "LinPlayer-1.1", "userdata")
	if err := os.MkdirAll(newRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	paths.SetRoot(newRoot)

	if _, n := AdoptPreviousInstall(); n == 0 {
		t.Fatal("一份都没搬")
	}
	b, _ := os.ReadFile(filepath.Join(newRoot, "config.json"))
	if string(b) != `{"device_id":"dev-recent"}` {
		t.Fatalf("搬的不是最后在用的那份: %q", b)
	}
}
