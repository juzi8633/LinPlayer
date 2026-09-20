package plugin

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

/*
字幕翻译的管线(SPEC 17.4,D364)。

☠ 这一段是整个插件里**最不能出错**的地方,而它的错法全都不报错:
  · 译文贴错一条 → 整轨从那里起错位,字幕看起来「有内容」,没人会怀疑;
  · 全部条目都失败却静默交出原文 → 用户看到「翻译了但没变化」,
    真相是引擎根本不可用(没开通 / 鉴权错)。
  所以这里拿**真的那份 pipeline.ts** 跑,不抄一份到测试里(抄的会漂)。
*/

// buildPipeline 把真的 pipeline.ts 编出来,挂到 globalThis.__P 上。
func buildPipeline(t *testing.T) *rt.Runtime {
	t.Helper()
	src := filepath.Join(repoRoot(t), "plugins", "subtitle-translate", "src", "pipeline.ts")
	b, err := os.ReadFile(src)
	if err != nil {
		t.Fatalf("读 plugins/subtitle-translate/src/pipeline.ts 失败: %v", err)
	}
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "src"), 0o755); err != nil {
		t.Fatal(err)
	}
	// 复制的是**测试运行那一刻的真文件**,不是抄写的副本 —— 源改了这里跟着改
	if err := os.WriteFile(filepath.Join(dir, "src", "pipeline.ts"), b, 0o644); err != nil {
		t.Fatal(err)
	}
	entry := "import * as P from './pipeline'\n;(globalThis as any).__P = P\n"
	if err := os.WriteFile(filepath.Join(dir, "src", "main.ts"), []byte(entry), 0o644); err != nil {
		t.Fatal(err)
	}
	res, err := Build(dir)
	if err != nil {
		t.Fatalf("编 pipeline.ts 失败: %v", err)
	}
	paths.SetRoot(t.TempDir())
	r, err := rt.New(rt.Options{ID: "test/pipeline", Version: "0", PkgDir: dir, DataDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(r.Close)
	if _, err := r.Eval(context.Background(), rt.BudgetData, "main.js", string(res.JS)); err != nil {
		t.Fatalf("跑 pipeline 失败: %v", err)
	}
	return r
}

func evalJSON(t *testing.T, r *rt.Runtime, code string, out any) {
	t.Helper()
	v, err := r.Eval(context.Background(), 10*time.Second, "x.js", code)
	if err != nil {
		t.Fatalf("%s: %v", code, err)
	}
	var s string
	if json.Unmarshal(v, &s) == nil {
		if json.Unmarshal([]byte(s), out) == nil {
			return
		}
	}
	if err := json.Unmarshal(v, out); err != nil {
		t.Fatalf("结果不是 JSON: %s", v)
	}
}

// 译文必须贴回**对应那一条**:错一条整轨就错位。
func TestTranslate译文贴回对应那一条(t *testing.T) {
	r := buildPipeline(t)
	var got []string
	evalJSON(t, r, `(async () => {
		const P = globalThis.__P;
		const cues = [];
		for (let i = 0; i < 97; i++) cues.push({ start: i, end: i + 1, text: '第' + i + '句' });
		// 每批打乱不了:引擎只负责把每条前面加个标记,顺序错了立刻看得出来
		const engine = {
			maxBatch: 7, maxChars: 0, concurrency: 3,
			translate: async (texts) => texts.map((x) => '[' + x + ']'),
		};
		await P.translateDocument(cues, engine, 'auto', 'zh-Hans');
		return JSON.stringify(cues.map((c) => c.translated));
	})()`, &got)

	if len(got) != 97 {
		t.Fatalf("条数变了:%d", len(got))
	}
	for i, v := range got {
		want := "[第" + itoa(i) + "句]"
		if v != want {
			t.Fatalf("第 %d 条贴错了:%q,应当是 %q —— 整轨从这里起错位", i, v, want)
		}
	}
}

// 引擎回包条数不齐 → 二分重试 → 单条仍失败回退原文,不丢条。
func TestTranslate回包不齐时二分重试且不丢条(t *testing.T) {
	r := buildPipeline(t)
	var got []string
	evalJSON(t, r, `(async () => {
		const P = globalThis.__P;
		const cues = [];
		for (let i = 0; i < 16; i++) cues.push({ start: i, end: i + 1, text: 'x' + i });
		// 批大于 1 时故意少回一条(模型合并两条短句就是这个样子)
		const engine = {
			maxBatch: 16, maxChars: 0, concurrency: 1,
			translate: async (texts) => {
				if (texts.length > 1) return texts.slice(1).map((x) => '译' + x);
				return ['译' + texts[0]];
			},
		};
		await P.translateDocument(cues, engine, 'auto', 'zh-Hans');
		return JSON.stringify(cues.map((c) => c.translated));
	})()`, &got)

	if len(got) != 16 {
		t.Fatalf("条数变了:%d —— 二分重试丢条了", len(got))
	}
	for i, v := range got {
		if v != "译x"+itoa(i) {
			t.Fatalf("第 %d 条是 %q —— 二分之后没贴回原位", i, v)
		}
	}
}

// 全部失败要**抛错**,不能静默交出一份没翻的字幕。
func TestTranslate全部失败时要报错(t *testing.T) {
	r := buildPipeline(t)
	var msg string
	evalJSON(t, r, `(async () => {
		const P = globalThis.__P;
		const cues = [{ start: 0, end: 1, text: 'a' }, { start: 1, end: 2, text: 'b' }];
		const engine = {
			maxBatch: 2, maxChars: 0, concurrency: 1,
			translate: async () => { throw new Error('没开通'); },
		};
		try { await P.translateDocument(cues, engine, 'auto', 'zh-Hans'); return JSON.stringify('没抛'); }
		catch (e) { return JSON.stringify(String(e.message || e)); }
	})()`, &msg)

	if msg == "没抛" {
		t.Fatal("全部条目都失败却没抛 —— 用户会看到「翻译了但没变化」")
	}
	if !strings.Contains(msg, "引擎不可用") {
		t.Fatalf("报错没说清是引擎不可用:%s", msg)
	}
}

// 404 的 HTML 错误页不许被当成字幕。
func TestTranslate错误页不许被当成字幕(t *testing.T) {
	r := buildPipeline(t)
	var ok []bool
	evalJSON(t, r, `JSON.stringify([
		globalThis.__P.looksLikeSubtitle('<html><body>404 Not Found</body></html>'),
		globalThis.__P.looksLikeSubtitle('1\n00:00:01,000 --> 00:00:02,000\n你好\n'),
	])`, &ok)
	if len(ok) != 2 || ok[0] || !ok[1] {
		t.Fatalf("判错了:%v —— 把错误页当字幕会报成「源字幕是空的」,查错方向", ok)
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}
