package plugin

import (
	"os"
	"path/filepath"
	"testing"

	"linplayer/core/config"
	"linplayer/core/paths"
)

/*
2.0.0 首发的官方插件(SPEC 17 的表,D313)。

☠ 判据是**每一个都真加载得起来**,不是「目录在」:
  贡献点声明了没实现、入口报错、依赖的命名空间没挂 —— 这三种都只在加载那一刻现形,
  而它们的表现都是「装了没反应」。
*/
func TestOfficialPlugins每一个都加载得起来(t *testing.T) {
	want := []string{"tvbox", "devtools", "sync", "subtitle-translate", "rule-editor", "live"}
	for _, name := range want {
		t.Run(name, func(t *testing.T) {
			dir := filepath.Join(repoRoot(t), "plugins", name)
			if _, err := os.Stat(dir); err != nil {
				t.Fatalf("仓库里没有 plugins/%s —— SPEC 17 的首发表里有它", name)
			}
			paths.SetRoot(t.TempDir())
			if _, err := config.Load(); err != nil {
				t.Fatal(err)
			}
			h := ResetForTest()
			h.Start("windows", "2.0.0")
			t.Cleanup(h.Shutdown)
			m, err := h.DevLoad(dir)
			if err != nil {
				t.Fatalf("加载失败: %v", err)
			}
			l, err := h.get(m.ID, "test")
			if err != nil {
				t.Fatalf("%s 起不来: %v", m.ID, err)
			}
			if miss := MissingImpl(m, l.rt); len(miss) > 0 {
				t.Fatalf("%s 的 manifest 声明了这些,definePlugin 里却没有:%v", m.ID, miss)
			}
		})
	}
}

/*
`examples/` 里每个示例都要**真装得起来**(D151:每个扩展点一个几十行的最小示例)。

☠ 示例是开发者接触插件系统的第一样东西。它跑不起来的伤害比官方插件跑不起来更大 ——
  作者会以为是自己环境的问题,然后放弃。而「目录在、文件也在」一点都证明不了它能跑。
*/
func TestExamples每个示例都装得起来(t *testing.T) {
	dir := filepath.Join(repoRoot(t), "docs", "plugin-system", "api", "examples")
	ents, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("读 examples 失败: %v", err)
	}
	var n int
	for _, e := range ents {
		if !e.IsDir() {
			continue
		}
		sub := filepath.Join(dir, e.Name())
		if _, err := os.Stat(filepath.Join(sub, "manifest.json")); err != nil {
			continue // 不是插件包(tsconfig 那类)
		}
		n++
		t.Run(e.Name(), func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			if _, err := config.Load(); err != nil {
				t.Fatal(err)
			}
			h := ResetForTest()
			h.Start("windows", "2.0.0")
			t.Cleanup(h.Shutdown)
			m, err := h.DevLoad(sub)
			if err != nil {
				t.Fatalf("装不起来: %v", err)
			}
			l, err := h.get(m.ID, "test")
			if err != nil {
				t.Fatalf("起不来: %v", err)
			}
			// 「装得起来」不够:声明了贡献点却没实现的表现是「装了没反应」
			if miss := MissingImpl(m, l.rt); len(miss) > 0 {
				t.Fatalf("manifest 声明了这些,definePlugin 里却没有:%v", miss)
			}
		})
	}
	if n < 5 {
		t.Fatalf("只找到 %d 个示例 —— SPEC 15.1 要求每个扩展点一个", n)
	}
}
