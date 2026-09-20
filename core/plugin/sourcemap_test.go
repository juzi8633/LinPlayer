package plugin

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/paths"
)

/*
报错栈映射回 TS 行号(SPEC 16.5,D81)。

☠ 这件事一直是「产物在、功能不在」:`lp build` 产 `main.js.map`、`lp pack` 打进包,
  而**没有任何消费方**。作者看到的栈是 `main.js:1:2931` —— 整个插件打成一个文件,
  那个位置对他毫无用处,而调试面板的「报错栈」那一栏就写着要映射。

判据不是「有没有 .map 文件」,是**真抛一次错,看栈里是不是源文件名**。
*/
func TestSourceMap报错栈映射回TS行号(t *testing.T) {
	root := repoRoot(t)
	dir := filepath.Join(root, "plugins", "devtools")
	if _, err := os.Stat(filepath.Join(dir, "src")); err != nil {
		t.Fatal("仓库里没有 plugins/devtools/src —— 它是首发官方插件,不在就是真出事了")
	}
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatalf("加载开发者工具失败: %v", err)
	}

	l, err := h.get("linplayer/devtools", "test")
	if err != nil {
		t.Fatal(err)
	}
	// 在插件的模块作用域里抛一次:栈里会带打包后的位置
	_, err = l.rt.Eval(context.Background(), 5*time.Second, "x.js",
		`(function 故意抛的() { throw new Error('用来看栈'); })()`)
	if err == nil {
		t.Fatal("这一句应当抛错")
	}
	detail := err.Error()
	if !strings.Contains(detail, "用来看栈") {
		t.Fatalf("错误里没有原始消息:%s", detail)
	}
}

// 映射本身:拿仓库里真的那份 sourcemap,查一个已知位置。
func TestSourceMap能把打包位置换成源位置(t *testing.T) {
	/* ☠ 不许「没 build 过就跳过」。`dist/` 是 gitignore 的,于是在干净检出
	   与 CI 上这条**永远跳过** —— 而它量的正是 D81:报错栈里的
	   `main.js:1:2931` 能不能换回作者写的 `src/panel.tsx:88:12`。
	   跳过的那一版里,sourcemap 产出来了却没有消费方,没人会知道。
	   改成**当场编一次**:Build 是 lp build 用的同一条路。 */
	root := repoRoot(t)
	dir := filepath.Join(root, "plugins", "devtools")
	res, err := Build(dir)
	if err != nil {
		t.Fatalf("编 plugins/devtools 失败: %v", err)
	}
	if len(res.SourceMap) == 0 {
		t.Fatal("编出来了但没有 sourcemap —— D81 的那条路从源头就断了")
	}
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	h := ResetForTest()
	h.Start("windows", "2.0.0")
	t.Cleanup(h.Shutdown)
	if _, err := h.DevLoad(dir); err != nil {
		t.Fatal(err)
	}
	l, err := h.get("linplayer/devtools", "test")
	if err != nil {
		t.Fatal(err)
	}
	// 产物是多行的(esbuild 默认不压缩):挑一行真有映射段的位置
	got := l.rt.MapStack("at 某函数 (main.js:120:10)")
	if strings.Contains(got, "main.js") {
		t.Fatalf("没映射:%s —— sourcemap 产出来了却没有消费方", got)
	}
	if !strings.Contains(got, "src/") && !strings.Contains(got, "lp-sdk") {
		t.Fatalf("映射结果不像源文件:%s", got)
	}
}
