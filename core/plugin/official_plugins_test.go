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
			if _, err := h.get(m.ID, "test"); err != nil {
				t.Fatalf("%s 起不来: %v", m.ID, err)
			}
		})
	}
}
