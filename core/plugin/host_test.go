package plugin

import (
	"archive/zip"
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

func manifestJSON(id, ver string, extra string) string {
	return `{"id":"` + id + `","name":"测试插件","version":"` + ver + `","description":"d","minAppVersion":"2.0.0"` + extra + `}`
}

// writePkg 按文件表打一个 .lpplugin。
func writePkg(t *testing.T, files map[string]string) string {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range files {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		w.Write([]byte(body))
	}
	zw.Close()
	p := filepath.Join(t.TempDir(), "p.lpplugin")
	if err := os.WriteFile(p, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

const sdkPrelude = "const {definePlugin} = " + rt.SDKGlobal + ";\n"

// installAndRestart 装一个插件并「重启」让它生效。
func installAndRestart(t *testing.T, main string) *Host {
	t.Helper()
	paths.SetRoot(t.TempDir())
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	pkg := writePkg(t, map[string]string{"manifest.json": manifestJSON("alice/demo", "1.0.0", ""), "main.js": sdkPrelude + main})
	if _, err := h.Install(pkg, "local"); err != nil {
		t.Fatal(err)
	}
	h.markStable()
	return restart(t)
}

func restart(t *testing.T) *Host {
	t.Helper()
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	return h
}

func TestInstallTakesEffectAfterRestart(t *testing.T) {
	paths.SetRoot(t.TempDir())
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	pkg := writePkg(t, map[string]string{"manifest.json": manifestJSON("alice/demo", "1.0.0", ""), "main.js": sdkPrelude + "definePlugin({commands:{a(){return 1}}})"})
	if _, err := h.Install(pkg, "local"); err != nil {
		t.Fatal(err)
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil); err == nil {
		t.Fatal("安装应重启后才生效(D170)")
	}
	if l := h.List(nil); len(l) != 1 || l[0].Status != "pendingRestart" {
		t.Fatalf("列表应显示待重启: %+v", l)
	}
	h.markStable()
	h = restart(t)
	out, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil)
	if err != nil || string(out) != "1" {
		t.Fatalf("重启后应能调用: %s %v", out, err)
	}
	if err := h.SetEnabled("alice/demo", false); err != nil {
		t.Fatal(err)
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil); err != nil {
		t.Fatal("停用也应重启后才生效")
	}
	h = restart(t)
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil); err == nil {
		t.Fatal("停用重启后不该还能调")
	}
}

// D322 ④ 防崩三件套之二:10 分钟内连续报错 5 次自动禁用。
func TestConsecutiveErrorsAutoDisable(t *testing.T) {
	h := installAndRestart(t, `definePlugin({ commands: { bad() { null.x }, good() { return 1 } } })`)
	ctx := context.Background()
	for i := 0; i < 4; i++ {
		if _, err := h.Call(ctx, "alice/demo", time.Second, "commands.bad", nil, nil); err == nil {
			t.Fatal("应报错")
		}
	}
	// 一次成功清零:连错只数连续的
	if _, err := h.Call(ctx, "alice/demo", time.Second, "commands.good", nil, nil); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 4; i++ {
		h.Call(ctx, "alice/demo", time.Second, "commands.bad", nil, nil)
	}
	if _, err := h.Call(ctx, "alice/demo", time.Second, "commands.good", nil, nil); err != nil {
		t.Fatalf("4 次连错不该禁用: %v", err)
	}
	for i := 0; i < 5; i++ {
		h.Call(ctx, "alice/demo", time.Second, "commands.bad", nil, nil)
	}
	if _, err := h.Call(ctx, "alice/demo", time.Second, "commands.good", nil, nil); err == nil {
		t.Fatal("连续 5 次报错后应立即自动禁用")
	}
	l := h.List(nil)
	if len(l) != 1 || l[0].Status != "autoDisabled" || !strings.Contains(l[0].AutoDisabled, "连续出错") {
		t.Fatalf("列表应显示自动禁用及原因: %+v", l)
	}
	// 自动禁用是立即的,而且重启后仍是停用
	h = restart(t)
	if _, err := h.Call(ctx, "alice/demo", time.Second, "commands.good", nil, nil); err == nil {
		t.Fatal("自动禁用重启后应保持")
	}
}

// 插件主动抛的类型化错误(站点挂了、没找到)是业务结果,不算「插件写坏了」。
func TestTypedErrorsDoNotCount(t *testing.T) {
	h := installAndRestart(t, `const {PluginError} = `+rt.SDKGlobal+`; definePlugin({ commands: { down() { throw new PluginError({kind:'siteDown', message:'挂了'}) }, good(){return 1} } })`)
	for i := 0; i < 8; i++ {
		h.Call(context.Background(), "alice/demo", time.Second, "commands.down", nil, nil)
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.good", nil, nil); err != nil {
		t.Fatalf("站点挂了不该禁用插件: %v", err)
	}
}

// 超时也计入连错。
func TestTimeoutsAutoDisable(t *testing.T) {
	h := installAndRestart(t, `definePlugin({ commands: { spin() { for(;;){} }, good(){return 1} } })`)
	for i := 0; i < 5; i++ {
		h.Call(context.Background(), "alice/demo", 50*time.Millisecond, "commands.spin", nil, nil)
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.good", nil, nil); err == nil {
		t.Fatal("连续 5 次超时后应自动禁用")
	}
}

// D322 ④ 防崩三件套之三:连续 2 次启动没清掉「启动中」标记,第 3 次启动进安全模式。
func TestCrashLoopEntersSafeMode(t *testing.T) {
	h := installAndRestart(t, `definePlugin({ commands: { a() { return 1 } } })`)
	h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil) // 记下「最后在跑的」
	// 第 1 次崩溃:没等到 30 秒稳定就没了(不调 markStable)
	h = restart(t)
	if active, _, _ := h.SafeMode(); active {
		t.Fatal("崩 1 次不该进安全模式")
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil); err != nil {
		t.Fatal(err)
	}
	// 第 2 次崩溃
	h = restart(t)
	active, banner, suspect := h.SafeMode()
	if !active || !banner {
		t.Fatal("连崩 2 次后这次启动应进安全模式")
	}
	if suspect != "alice/demo" {
		t.Fatalf("最可疑插件应是崩溃前最后在跑的: %q", suspect)
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil); err == nil {
		t.Fatal("安全模式下插件应全部停用")
	}
	// 稳定运行后再重启:插件仍是停用(要用户自己逐个开),横条保留到用户开过任一插件
	h.markStable()
	h = restart(t)
	if active, banner, _ := h.SafeMode(); active || !banner {
		t.Fatalf("安全模式只管那一次启动;横条要保留: active=%v banner=%v", active, banner)
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil); err == nil {
		t.Fatal("安全模式关掉的插件不该自己回来")
	}
	h.SetEnabled("alice/demo", true)
	h.markStable()
	h = restart(t)
	if _, banner, _ := h.SafeMode(); banner {
		t.Fatal("用户开过插件后横条应消失")
	}
	if _, err := h.Call(context.Background(), "alice/demo", time.Second, "commands.a", nil, nil); err != nil {
		t.Fatalf("用户重新启用后应能用: %v", err)
	}
}

// 稳定运行 30 秒清标记:正常重启不该累计成连崩。
func TestStableRunClearsCrashCounter(t *testing.T) {
	h := installAndRestart(t, `definePlugin({ commands: { a() { return 1 } } })`)
	for i := 0; i < 3; i++ {
		h.markStable()
		h = restart(t)
	}
	if active, _, _ := h.SafeMode(); active {
		t.Fatal("每次都稳定运行过,不该进安全模式")
	}
}

func TestInstallerFloor(t *testing.T) {
	paths.SetRoot(t.TempDir())
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	ok := map[string]string{"manifest.json": manifestJSON("alice/demo", "1.0.0", ""), "main.js": "1"}
	cases := map[string]map[string]string{
		"路径穿越":       {"manifest.json": ok["manifest.json"], "main.js": "1", "../evil.txt": "x"},
		"绝对路径":       {"manifest.json": ok["manifest.json"], "main.js": "1", "/etc/evil": "x"},
		"盘符路径":       {"manifest.json": ok["manifest.json"], "main.js": "1", "C:/evil": "x"},
		"缺 manifest": {"main.js": "1"},
		"非 semver":   {"manifest.json": manifestJSON("alice/demo", "1.0", ""), "main.js": "1"},
		"id 大写":      {"manifest.json": manifestJSON("Alice/demo", "1.0.0", ""), "main.js": "1"},
		"接管凭据页":      {"manifest.json": manifestJSON("alice/demo", "1.0.0", `,"contributes":{"pageTakeovers":[{"target":"server.add","page":"x"}]}`), "main.js": "1"},
		"缺入口":        {"manifest.json": manifestJSON("alice/demo", "1.0.0", "")},
		"坏 JSON":     {"manifest.json": "{", "main.js": "1"},
	}
	for name, files := range cases {
		if _, err := h.Install(writePkg(t, files), "local"); err == nil {
			t.Errorf("%s:应拒装", name)
		}
	}
	// 压缩炸弹:高度可压缩的大文件,压缩比远超 100 倍
	bomb := map[string]string{"manifest.json": ok["manifest.json"], "main.js": "1", "big.txt": strings.Repeat("0", 8<<20)}
	if _, err := h.Install(writePkg(t, bomb), "local"); err == nil || !strings.Contains(err.Error(), "压缩比") {
		t.Errorf("压缩炸弹应拒装: %v", err)
	}
	if _, err := h.Install(writePkg(t, ok), "local"); err != nil {
		t.Fatalf("正常包应能装: %v", err)
	}
	// 同 id 不同来源:要换来源先卸载(D48)
	if _, err := h.Install(writePkg(t, map[string]string{"manifest.json": manifestJSON("alice/demo", "1.1.0", ""), "main.js": "1"}), "https://repo.example/index.json"); err == nil {
		t.Error("其它来源的同 id 应拒装")
	}
}

func TestSchemaCopiesInSync(t *testing.T) {
	for _, n := range []string{"manifest.schema.json", "index.schema.json"} {
		a, err := os.ReadFile(filepath.Join("schema", n))
		if err != nil {
			t.Fatal(err)
		}
		b, err := os.ReadFile(filepath.Join("..", "..", "docs", "plugin-system", "api", n))
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(bytes.ReplaceAll(a, []byte("\r\n"), []byte("\n")), bytes.ReplaceAll(b, []byte("\r\n"), []byte("\n"))) {
			t.Errorf("core/plugin/schema/%s 与 docs/plugin-system/api/%s 不一致:改 schema 先改 docs 那份再拷过来", n, n)
		}
	}
}

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.2.9", "1.3.0-beta", -1}, {"1.3.0-beta", "1.3.0", -1}, {"1.3.0", "1.3.0", 0},
		{"2.0.0", "1.99.99", 1}, {"1.0.0-alpha.2", "1.0.0-alpha.10", -1}, {"1.0.0-alpha", "1.0.0-alpha.1", -1},
	}
	for _, c := range cases {
		if got := CompareVersions(c.a, c.b); got != c.want {
			t.Errorf("%s vs %s = %d, 期望 %d", c.a, c.b, got, c.want)
		}
	}
}

func TestUninstallKeepsOrDeletesData(t *testing.T) {
	h := installAndRestart(t, `const {storage} = `+rt.SDKGlobal+`; definePlugin({ commands: { put() { storage.set('k', 1) } } })`)
	h.Call(context.Background(), "alice/demo", time.Second, "commands.put", nil, nil)
	h.Shutdown() // KV 落盘
	h = restart(t)
	h.Uninstall("alice/demo", false)
	h = restart(t)
	if len(h.List(nil)) != 0 {
		t.Fatal("卸载后列表应为空")
	}
	if _, err := os.Stat(filepath.Join(DataDir("alice/demo"), "kv.json")); err != nil {
		t.Fatal("不勾删数据时 KV 应保留")
	}
}
