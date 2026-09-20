package plugin

import (
	"os"
	"path/filepath"
	"testing"

	"linplayer/core/config"
	"linplayer/core/paths"
)

/*
官方开发者工具的另外两页:组件示例(含 Canvas)与一千项大列表。

☠ 它们一直**没有任何 Go 测试渲染过** —— 而「三端截图一致」「TV 1000 项 ≥50fps」
  两条验收量的就是这两页。页面本身崩了的话,那两条验收量的是一张空白。
*/

func loadDevtools(t *testing.T, dev bool) *Host {
	t.Helper()
	dir := filepath.Join(repoRoot(t), "plugins", "devtools")
	if _, err := os.Stat(dir); err != nil {
		t.Fatal("仓库里没有 plugins/devtools —— 它是首发官方插件,不在就是真出事了")
	}
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	devMode(t, dev)
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatalf("加载开发者工具失败: %v", err)
	}
	registerUI()
	return h
}

func createdTypes(ops []map[string]any) map[string]bool {
	seen := map[string]bool{}
	for _, o := range ops {
		if o["op"] == "create" {
			if s, ok := o["type"].(string); ok {
				seen[s] = true
			}
		}
	}
	return seen
}

func TestDevtools示例页画得出Canvas和输入(t *testing.T) {
	loadDevtools(t, true)
	c := captureUI(t)
	mountUI(t, c, map[string]any{"plugin": "linplayer/devtools", "target": "gallery", "kind": "page"})
	c.wait(t, "示例页首帧", func() bool { return len(c.ops()) > 20 })

	seen := createdTypes(c.ops())
	for _, want := range []string{"Canvas", "TextInput", "Switch", "Divider", "Button"} {
		if !seen[want] {
			t.Errorf("示例页没渲染出 %s;建出来的是 %v", want, keysOf(seen))
		}
	}
	// Canvas 必须真带指令流:没有 cmds 的 Canvas 在截图上是一块空白,而「空白」也叫「三端一致」
	var cmds int
	for _, o := range c.ops() {
		if o["op"] == "canvas" {
			if arr, ok := o["cmds"].([]any); ok {
				cmds += len(arr)
			}
		}
		if set, ok := o["set"].(map[string]any); ok {
			if arr, ok := set["cmds"].([]any); ok {
				cmds += len(arr)
			}
		}
	}
	if cmds < 10 {
		t.Errorf("Canvas 只录到 %d 条指令 —— 这一块画出来是空的", cmds)
	}
}

func TestDevtools一千项页只渲染窗口内的项(t *testing.T) {
	loadDevtools(t, true)
	c := captureUI(t)
	mountUI(t, c, map[string]any{"plugin": "linplayer/devtools", "target": "list", "kind": "page"})
	c.wait(t, "大列表首帧", func() bool { return len(c.ops()) > 20 })

	ops := c.ops()
	if !createdTypes(ops)["VirtualList"] {
		t.Fatalf("没建出 VirtualList;建出来的是 %v", keysOf(createdTypes(ops)))
	}
	var texts int
	for _, o := range ops {
		if o["op"] == "create" && o["type"] == "Text" {
			texts++
		}
	}
	if texts == 0 {
		t.Fatal("一项都没渲染 —— 首屏那一窗必须由 JS 先给一批")
	}
	// 一千项全渲染的话壳会收到上千个节点,TV 上直接掉帧(D134)
	if texts > 120 {
		t.Errorf("首帧渲染了 %d 个 Text —— 虚拟列表没生效", texts)
	}
}

// 开发者模式关着时,面板只说一句话:四块数据的来源整个不存在(D555)。
func TestDevtools开发者模式关着时面板只给一句提示(t *testing.T) {
	loadDevtools(t, false)
	c := captureUI(t)
	mountUI(t, c, map[string]any{"plugin": "linplayer/devtools", "target": "panel", "kind": "page"})
	c.wait(t, "面板首帧", func() bool { return len(c.ops()) > 2 })

	seen := createdTypes(c.ops())
	if !seen["EmptyState"] {
		t.Errorf("没画那句提示;建出来的是 %v", keysOf(seen))
	}
	if seen["ChipGroup"] {
		t.Error("开发者模式关着却画出了标签栏 —— 四块的数据源根本不存在,画出来点了也是空的")
	}
}
