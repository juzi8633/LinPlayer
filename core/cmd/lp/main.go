// lp 是插件开发者 CLI(SPEC 16.3,D13 D145 D484):Go 单文件,复用宿主同一份 esbuild + goja,
// 作者不装 Node 也能 create/build/pack/dev/check,打包结果与应用内编译一致。
//
//	lp create <作者/名字> [目录]   空白 TS 模板
//	lp build [目录]                打成 dist/main.js(+ sourcemap),自动填 minAppVersion
//	lp pack  [目录] [-o 输出]      出 .lpplugin(publish 同 pack,D82)
//	lp dev   [目录]                监听源码,改了就重建;应用里「开发者 → 加载本地目录」指向同一目录即热重载
//	lp check [目录]                本地跑一遍官方 CI 的校验(D483)
//	lp submit [目录]               生成上架 PR 要改的 index 条目 JSON 片段
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"linplayer/core/paths"
	"linplayer/core/plugin"
	"linplayer/core/plugin/rt"
)

// version SDK 版本 = 应用版本(D411),构建时 -ldflags "-X main.version=$(cat VERSION)" 注入。
var version string

func sdkVersion() string {
	if version != "" {
		return version
	}
	// go run 开发时:从仓库根的 VERSION 读(版本号唯一权威)
	for dir, _ := os.Getwd(); dir != filepath.Dir(dir); dir = filepath.Dir(dir) {
		if b, err := os.ReadFile(filepath.Join(dir, "VERSION")); err == nil {
			return strings.TrimSpace(string(b))
		}
	}
	return ""
}

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	cmd, args := os.Args[1], os.Args[2:]
	var err error
	if sdkVersion() == "" && cmd != "check" {
		// minAppVersion 要按 SDK 版本填(D411);没有版本号就会写出一个不合规的 manifest
		fmt.Fprintln(os.Stderr, "✗ 这个 lp 构建时没有注入版本号:用 scripts/build-lp.sh 构建,或在 LinPlayer 仓库目录里运行")
		os.Exit(1)
	}
	switch cmd {
	case "create":
		err = create(args)
	case "build":
		err = build(dirArg(args))
	case "pack", "publish":
		err = pack(args)
	case "dev":
		err = dev(dirArg(args))
	case "check":
		err = check(dirArg(args))
	case "submit":
		err = submit(dirArg(args))
	case "version", "-v", "--version":
		fmt.Println(sdkVersion())
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "✗", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "用法:lp create <作者/名字> [目录] | build [目录] | pack [目录] [-o 输出] | dev [目录] | check [目录] | submit [目录]")
	os.Exit(2)
}

func dirArg(args []string) string {
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		return args[0]
	}
	return "."
}

// ---------------------------------------------------------------- create

func create(args []string) error {
	if len(args) < 1 {
		return errors.New("要给插件 id,形如 作者/名字")
	}
	id := args[0]
	if !plugin.IDPattern.MatchString(id) {
		return fmt.Errorf("id %q 不合规:作者/名字 两段都只许小写字母、数字、连字符", id)
	}
	dir := strings.SplitN(id, "/", 2)[1]
	if len(args) > 1 {
		dir = args[1]
	}
	if _, err := os.Stat(dir); err == nil {
		return fmt.Errorf("目录 %s 已存在", dir)
	}
	name := strings.SplitN(id, "/", 2)[1]
	files := map[string]string{
		"manifest.json": fmt.Sprintf(`{
  "id": %q,
  "name": %q,
  "version": "0.1.0",
  "description": "在这里写一句插件做什么",
  "minAppVersion": %q,
  "contributes": {
    "commands": [{ "id": "hello", "title": "打个招呼" }]
  }
}
`, id, name, plugin.AppCore(sdkVersion())),
		"src/main.ts": `import { definePlugin, ui } from '@linplayer/plugin-sdk'

export default definePlugin({
  activate() {
    console.log('插件已加载')
  },
  commands: {
    hello() {
      ui.toast('你好')
    },
  },
})
`,
		"package.json": fmt.Sprintf(`{
  "name": %q,
  "private": true,
  "version": "0.1.0",
  "scripts": { "build": "lp build", "pack": "lp pack", "check": "lp check", "dev": "lp dev" },
  "devDependencies": { "@linplayer/plugin-sdk": %q, "@linplayer/cli": %q, "typescript": "^5" }
}
`, name, "^"+sdkVersion(), "^"+sdkVersion()),
		"tsconfig.json": `{
  "compilerOptions": {
    "target": "ES2017",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "jsx": "react",
    "jsxFactory": "h",
    "jsxFragmentFactory": "Fragment",
    "noEmit": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
`,
		".gitignore": "node_modules/\ndist/\n*.lpplugin\n",
	}
	for p, body := range files {
		full := filepath.Join(dir, filepath.FromSlash(p))
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(full, []byte(body), 0o644); err != nil {
			return err
		}
	}
	fmt.Printf("✓ 已创建 %s\n  cd %s && pnpm install && lp build\n", dir, dir)
	return nil
}

// ---------------------------------------------------------------- build / pack

func fillManifest(dir string) error {
	p := filepath.Join(dir, "manifest.json")
	mb, err := os.ReadFile(p)
	if err != nil {
		return errors.New("目录里没有 manifest.json")
	}
	if v := sdkVersion(); v != "" {
		out, err := plugin.FillMinAppVersion(mb, v)
		if err != nil {
			return err
		}
		if string(out) != string(mb) {
			return os.WriteFile(p, out, 0o644)
		}
	}
	return nil
}

func build(dir string) error {
	if err := fillManifest(dir); err != nil {
		return err
	}
	start := time.Now()
	res, err := plugin.Build(dir)
	if err != nil {
		return err
	}
	out := filepath.Join(dir, "dist")
	if err := os.MkdirAll(out, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(out, "main.js"), res.JS, 0o644); err != nil {
		return err
	}
	if len(res.SourceMap) > 0 {
		_ = os.WriteFile(filepath.Join(out, "main.js.map"), res.SourceMap, 0o644)
	}
	for _, w := range res.Warnings {
		fmt.Println("⚠", w)
	}
	fmt.Printf("✓ dist/main.js  %d KB  %v\n", len(res.JS)/1024, time.Since(start).Round(time.Millisecond))
	return nil
}

func pack(args []string) error {
	fs := flag.NewFlagSet("pack", flag.ExitOnError)
	out := fs.String("o", "", "输出文件")
	dir := dirArg(args)
	if dir != "." || (len(args) > 0 && !strings.HasPrefix(args[0], "-")) {
		args = args[1:]
	}
	_ = fs.Parse(args)
	if err := fillManifest(dir); err != nil {
		return err
	}
	zipped, m, err := plugin.Pack(dir, sdkVersion())
	if err != nil {
		return err
	}
	if *out == "" {
		*out = strings.ReplaceAll(m.ID, "/", "-") + "-" + m.Version + ".lpplugin"
	}
	if err := os.WriteFile(*out, zipped, 0o644); err != nil {
		return err
	}
	fmt.Printf("✓ %s  %d KB\n", *out, len(zipped)/1024)
	return nil
}

// ---------------------------------------------------------------- dev

func dev(dir string) error {
	abs, _ := filepath.Abs(dir)
	fmt.Printf("监听 %s(Ctrl+C 退出)。应用里「设置 → 开发者 → 加载本地目录」选这个目录,改了自动热重载。\n", abs)
	last := ""
	for {
		s := fingerprint(abs)
		if s != last {
			last = s
			if err := build(abs); err != nil {
				fmt.Fprintln(os.Stderr, "✗", err)
			}
		}
		time.Sleep(time.Second)
	}
}

func fingerprint(dir string) string {
	var b strings.Builder
	_ = filepath.WalkDir(dir, func(p string, e os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if e.IsDir() && (e.Name() == "node_modules" || e.Name() == "dist" || strings.HasPrefix(e.Name(), ".")) {
			return filepath.SkipDir
		}
		if info, err := e.Info(); err == nil && !e.IsDir() {
			fmt.Fprintf(&b, "%s%d%d", p, info.ModTime().UnixNano(), info.Size())
		}
		return nil
	})
	return b.String()
}

// ---------------------------------------------------------------- check

func check(dir string) error {
	var fails []string
	fail := func(f string, a ...any) { fails = append(fails, fmt.Sprintf(f, a...)) }
	ok := func(f string, a ...any) { fmt.Printf("  ✓ "+f+"\n", a...) }

	mb, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return errors.New("目录里没有 manifest.json")
	}
	m, err := plugin.ParseManifest(mb)
	if err != nil {
		return err // manifest 不合规后面都没法查
	}
	ok("manifest 合法(schema、id、semver)")

	zipped, _, err := plugin.Pack(dir, sdkVersion())
	if err != nil {
		return err
	}
	ok("打包成功(%d KB)", len(zipped)/1024)

	tmp, err := os.MkdirTemp("", "lp-check-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	pkgFile := filepath.Join(tmp, "p.lpplugin")
	_ = os.WriteFile(pkgFile, zipped, 0o644)
	unpacked := filepath.Join(tmp, "pkg")
	if _, err := plugin.Unpack(pkgFile, unpacked); err != nil {
		fail("安装器底线:%v", err)
	} else {
		ok("解压安全(路径穿越、压缩炸弹)")
	}

	// 真装一遍、执行 activate 不报错(D483)
	paths.SetRoot(filepath.Join(tmp, "data"))
	r, err := rt.New(rt.Options{ID: m.ID, Version: m.Version, PkgDir: unpacked, DataDir: filepath.Join(tmp, "data", "p"), LAN: m.LAN,
		Host: rt.Host{App: rt.AppInfo{Version: sdkVersion(), Platform: platform(), FormFactor: "desktop", Locale: "zh-CN"}}})
	if err != nil {
		return err
	}
	defer r.Close()
	code, err := os.ReadFile(filepath.Join(unpacked, filepath.FromSlash(m.Main)))
	if err != nil {
		fail("包里没有入口 %s", m.Main)
	} else if _, err := r.Eval(context.Background(), rt.BudgetData, m.Main, string(code)); err != nil {
		fail("加载 %s 报错:%v", m.Main, err)
	} else {
		ok("加载入口不报错")
		if r.Has("activate") {
			act := map[string]any{"id": m.ID, "version": m.Version, "dev": false, "reason": "startup", "subscriptions": []any{}}
			if _, err := r.Invoke(context.Background(), rt.BudgetData, "activate", []any{act}, nil); err != nil {
				fail("activate 报错:%v", err)
			} else {
				ok("activate 不报错")
			}
		}
		for _, miss := range missingImpl(m, r) {
			fail("manifest 声明了 %s,但 definePlugin 里没有实现", miss)
		}
		if len(missingImpl(m, r)) == 0 {
			ok("声明的贡献点都有实现")
		}
	}
	if len(fails) > 0 {
		for _, f := range fails {
			fmt.Println("  ✗", f)
		}
		return fmt.Errorf("%d 项没过", len(fails))
	}
	fmt.Println("✓ 全部通过")
	return nil
}

// missingImpl manifest 声明的贡献点在代码里有没有同名实现(D206 D483)。
func missingImpl(m *plugin.Manifest, r *rt.Runtime) []string {
	var raw struct {
		Contributes struct {
			DataSource   *json.RawMessage `json:"dataSource"`
			Commands     []struct{ ID string } `json:"commands"`
			M3u8Filters  []struct{ ID string } `json:"m3u8Filters"`
			Menus        []struct{ Command string } `json:"menus"`
			HomeSections []struct {
				ID   string
				Kind string
			} `json:"homeSections"`
			Pages    []struct{ ID string } `json:"pages"`
			Settings []struct {
				Type   string
				Action string
			} `json:"settings"`
			Hooks *struct {
				Navigate      bool             `json:"navigate"`
				ListTransform *json.RawMessage `json:"listTransform"`
				CardBadge     bool             `json:"cardBadge"`
			} `json:"hooks"`
			NextUp bool `json:"nextUp"`
		} `json:"contributes"`
	}
	_ = json.Unmarshal(m.Raw, &raw)
	var out []string
	need := func(path string) {
		if !r.Has(path) {
			out = append(out, path)
		}
	}
	c := raw.Contributes
	if c.DataSource != nil {
		need("dataSource.detail")
		need("dataSource.play")
	}
	for _, x := range c.Commands {
		need("commands." + x.ID)
	}
	for _, x := range c.Menus {
		need("commands." + x.Command)
	}
	for _, x := range c.M3u8Filters {
		need("m3u8Filters." + x.ID)
	}
	for _, x := range c.HomeSections {
		if x.Kind == "items" {
			need("homeSections." + x.ID)
		}
	}
	for _, x := range c.Pages {
		need("pages." + x.ID)
	}
	for _, x := range c.Settings {
		if x.Type == "button" && x.Action != "" {
			need("settingActions." + x.Action)
		}
	}
	if c.Hooks != nil {
		if c.Hooks.Navigate {
			need("hooks.navigate")
		}
		if c.Hooks.ListTransform != nil {
			need("hooks.listTransform")
		}
		if c.Hooks.CardBadge {
			need("hooks.cardBadge")
		}
	}
	if c.NextUp {
		need("nextUp")
	}
	return out
}

func platform() string {
	switch runtime.GOOS {
	case "windows":
		return "windows"
	case "android":
		return "android"
	}
	return "linux"
}

// ---------------------------------------------------------------- submit

func submit(dir string) error {
	zipped, m, err := plugin.Pack(dir, sdkVersion())
	if err != nil {
		return err
	}
	entry := map[string]any{
		"id": m.ID, "name": m.Name, "description": m.Description, "author": strings.SplitN(m.ID, "/", 2)[0],
		"repository": m.Repository, "contributes": m.ContributionList(), "lan": m.LAN, "paid": m.Paid,
		"versions": []map[string]any{{
			"version": m.Version, "url": "<发布到 GitHub Release 后的 .lpplugin 地址>", "size": len(zipped),
			"minAppVersion": m.MinAppVersion, "released": time.Now().UTC().Format(time.RFC3339),
		}},
	}
	b, _ := json.MarshalIndent(entry, "", "  ")
	fmt.Println(string(b))
	fmt.Fprintln(os.Stderr, "把上面这段加进官方仓库 registry/index.json 的 plugins 数组,填好 url 后提 PR。")
	return nil
}
