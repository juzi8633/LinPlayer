package plugin

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"linplayer/core/paths"
	"linplayer/core/plugin/rt"
)

/*
直播源解析与回看地址(SPEC 17.2,D111 D112 D465 D466 D468)。

☠ 这一段的错法全都不报错,只是「某些台打不开 / 节目单全错位 / 点回看跳直播」:
  · 频道级请求头丢了 → 403,看起来像源挂了;
  · XMLTV 的时区偏移当本地时间解 → 差 8 小时的节目单;
  · 回看模板只认一套占位符 → 地址通的、内容不对,没有任何报错。
  所以这里拿**真的那几个 .ts** 跑,不抄一份到测试里(抄的会漂)。
*/

// buildLive 把 plugins/live/src 下的真文件编出来,挂到 globalThis.__L。
func buildLive(t *testing.T) *rt.Runtime {
	t.Helper()
	srcDir := filepath.Join(repoRoot(t), "plugins", "live", "src")
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "src"), 0o755); err != nil {
		t.Fatal(err)
	}
	// 不是「找不到就跳过」:这几个文件是仓库资产,不在就是真的出事了
	for _, name := range []string{"m3u.ts", "catchup.ts", "epg.ts", "merge.ts"} {
		b, err := os.ReadFile(filepath.Join(srcDir, name))
		if err != nil {
			t.Fatalf("读 plugins/live/src/%s 失败: %v", name, err)
		}
		if err := os.WriteFile(filepath.Join(dir, "src", name), b, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	entry := "import * as M from './m3u'\nimport * as C from './catchup'\n" +
		"import * as E from './epg'\nimport * as G from './merge'\n" +
		";(globalThis as any).__L = { ...M, ...C, ...E, ...G }\n"
	if err := os.WriteFile(filepath.Join(dir, "src", "main.ts"), []byte(entry), 0o644); err != nil {
		t.Fatal(err)
	}
	res, err := Build(dir)
	if err != nil {
		t.Fatalf("编 plugins/live/src 失败: %v", err)
	}
	paths.SetRoot(t.TempDir())
	r, err := rt.New(rt.Options{ID: "test/live", Version: "0", PkgDir: dir, DataDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(r.Close)
	if _, err := r.Eval(context.Background(), rt.BudgetData, "main.js", string(res.JS)); err != nil {
		t.Fatalf("跑 live 解析模块失败: %v", err)
	}
	return r
}

const sampleM3U = `#EXTM3U x-tvg-url="https://epg.example.invalid/x.xml.gz" catchup="append" catchup-source="?utc=${start}"
#EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV1" tvg-logo="https://logo.example.invalid/1.png" group-title="央视" tvg-chno="1",CCTV1 综合频道
#EXTVLCOPT:http-user-agent=okhttp/3.15
#EXTVLCOPT:http-referrer=https://ref.example.invalid/
http://live.example.invalid/cctv1.m3u8
#EXTINF:-1 group-title="卫视" catchup="flussonic" catchup-days="3",湖南卫视
http://live.example.invalid/hunan/index.m3u8|User-Agent=MyPlayer&Referer=https%3A%2F%2Fr.example.invalid%2F
#EXTINF:-1 group-title="央视",CCTV1 4K
http://live.example.invalid/cctv1-4k.m3u8
#EXTINF:-1 group-title="央视",CCTV1 综合频道
http://backup.example.invalid/cctv1.m3u8
`

type liveChannel struct {
	Name    string `json:"name"`
	TvgID   string `json:"tvgId"`
	Logo    string `json:"logo"`
	Group   string `json:"group"`
	Number  int    `json:"number"`
	Catchup *struct {
		Type   string `json:"type"`
		Source string `json:"source"`
		Days   int    `json:"days"`
	} `json:"catchup"`
	URLs []struct {
		URL     string            `json:"url"`
		Headers map[string]string `json:"headers"`
	} `json:"urls"`
}

// 频道级请求头一条都不许丢:丢了就是 403,而表现是「这台打不开」。
func TestLiveM3U带上频道级请求头(t *testing.T) {
	r := buildLive(t)
	var out struct {
		Channels []liveChannel `json:"channels"`
		EPG      []string      `json:"epg"`
	}
	evalJSON(t, r, `JSON.stringify(globalThis.__L.parseM3U(`+jsQuote(sampleM3U)+`))`, &out)

	if len(out.Channels) != 4 {
		t.Fatalf("解出 %d 个频道,应当是 4", len(out.Channels))
	}
	if len(out.EPG) != 1 {
		t.Fatalf("播放列表自带的 EPG 地址没解出来:%v", out.EPG)
	}
	c := out.Channels[0]
	if c.Name != "CCTV1 综合频道" || c.Group != "央视" || c.TvgID != "cctv1" || c.Number != 1 {
		t.Fatalf("第一条的基本字段不对:%+v", c)
	}
	h := c.URLs[0].Headers
	if h["User-Agent"] != "okhttp/3.15" {
		t.Errorf("#EXTVLCOPT 的 UA 丢了:%v", h)
	}
	if h["Referer"] == "" {
		t.Errorf("#EXTVLCOPT 的 Referer 丢了:%v", h)
	}

	// URL 后面挂的 `|User-Agent=…&Referer=…` 也要认,而且要 URL 解码
	h2 := out.Channels[1].URLs[0].Headers
	if h2["User-Agent"] != "MyPlayer" {
		t.Errorf("地址后缀里的 UA 没解出来:%v", h2)
	}
	if h2["Referer"] != "https://r.example.invalid/" {
		t.Errorf("地址后缀里的 Referer 没 URL 解码:%q", h2["Referer"])
	}
	if out.Channels[1].URLs[0].URL != "http://live.example.invalid/hunan/index.m3u8" {
		t.Errorf("地址被 | 切错了:%q", out.Channels[1].URLs[0].URL)
	}

	// 文件级 catchup 要继承到没写 catchup 的频道上
	if out.Channels[0].Catchup == nil || out.Channels[0].Catchup.Type != "append" {
		t.Errorf("文件级 catchup 没继承下来:%+v", out.Channels[0].Catchup)
	}
	// 频道自己写了的不能被文件级盖掉
	if out.Channels[1].Catchup == nil || out.Channels[1].Catchup.Type != "flussonic" ||
		out.Channels[1].Catchup.Days != 3 {
		t.Errorf("频道级 catchup 被文件级盖掉了:%+v", out.Channels[1].Catchup)
	}
}

// 合并保留清晰度后缀(D466):CCTV1 与 CCTV1 4K 是两个频道,CCTV-1 与 CCTV1 是一个。
func TestLive合并保留清晰度后缀(t *testing.T) {
	r := buildLive(t)
	var got []liveChannel
	evalJSON(t, r, `JSON.stringify(globalThis.__L.mergeChannels(
		globalThis.__L.parseM3U(`+jsQuote(sampleM3U)+`).channels))`, &got)

	if len(got) != 3 {
		names := []string{}
		for _, c := range got {
			names = append(names, c.Name)
		}
		t.Fatalf("合并后剩 %d 个:%v —— 应当是 3(CCTV1 综合频道 / CCTV1 4K / 湖南卫视)", len(got), names)
	}
	// CCTV-1 综合 与 CCTV-1 合并成两路地址
	if len(got[0].URLs) != 2 {
		t.Errorf("CCTV1 没合并出两路地址:%+v", got[0].URLs)
	}
	for _, c := range got {
		if c.Name == "CCTV1 4K" && len(c.URLs) != 1 {
			t.Errorf("4K 被合并进普通频道了:%+v", c)
		}
	}
}

// TXT 格式:`#genre#` 是分组行,频道行的多个地址用 # 分开。
func TestLiveTXT分组与多地址(t *testing.T) {
	r := buildLive(t)
	const txt = "央视,#genre#\nCCTV1,http://a.example.invalid/1#http://b.example.invalid/1\n" +
		"说明文字这一行不是频道\n卫视,#genre#\n湖南卫视,http://a.example.invalid/hn\n"
	var out struct {
		Channels []liveChannel `json:"channels"`
	}
	evalJSON(t, r, `JSON.stringify(globalThis.__L.parseTXT(`+jsQuote(txt)+`))`, &out)
	if len(out.Channels) != 2 {
		t.Fatalf("解出 %d 个频道,应当是 2", len(out.Channels))
	}
	if out.Channels[0].Group != "央视" || len(out.Channels[0].URLs) != 2 {
		t.Errorf("第一条:%+v", out.Channels[0])
	}
	if out.Channels[1].Group != "卫视" {
		t.Errorf("分组没跟着 #genre# 切换:%+v", out.Channels[1])
	}
}

// 四种回看格式各跑一条。错了的表现是「点回看跳到直播」,不报错。
func TestLive回看地址四种格式(t *testing.T) {
	r := buildLive(t)
	const start, end, now = 1758400000, 1758403600, 1758500000
	cases := []struct {
		name string
		js   string
		want string
	}{
		{"append", `{type:'append',source:'?utc=${start}&lutc=${offset}'}`,
			"http://live.example.invalid/a/index.m3u8?utc=1758400000&lutc=100000"},
		{"default", `{type:'default',source:'http://vod.example.invalid/p?s=${start}&e=${end}'}`,
			"http://vod.example.invalid/p?s=1758400000&e=1758403600"},
		{"shift 无 source", `{type:'shift'}`,
			"http://live.example.invalid/a/index.m3u8?utcstart=1758400000&utcend=1758403600"},
		{"flussonic", `{type:'flussonic'}`,
			"http://live.example.invalid/a/archive-1758400000-3600.m3u8"},
	}
	for _, c := range cases {
		var got string
		evalJSON(t, r, `JSON.stringify(globalThis.__L.catchupUrl(
			'http://live.example.invalid/a/index.m3u8', `+c.js+`, `+
			itoa(start)+`, `+itoa(end)+`, `+itoa(now)+`))`, &got)
		if got != c.want {
			t.Errorf("%s 回看地址 = %q,应当是 %q", c.name, got, c.want)
		}
	}
	// 没有回看模板时回空串 —— 调用方据此不显示回看入口
	var none string
	evalJSON(t, r, `JSON.stringify(globalThis.__L.catchupUrl('http://x.example.invalid/a', undefined, 1, 2, 3))`, &none)
	if none != "" {
		t.Errorf("没有回看模板却算出了地址:%q", none)
	}
}

// XMLTV 的时区偏移必须当偏移解。差 8 小时的节目单是「有节目单但全错位」。
func TestLiveXMLTV时区与节目解析(t *testing.T) {
	r := buildLive(t)
	const xml = `<?xml version="1.0"?><tv>
<channel id="cctv1"><display-name>CCTV1</display-name><icon src="https://logo.example.invalid/1.png"/></channel>
<programme start="20260921120000 +0800" stop="20260921130000 +0800" channel="cctv1">
<title>新闻 &amp; 天气</title><desc>说明</desc></programme>
<programme start="20260921130000 +0800" channel="cctv1"><title>下一档</title></programme>
</tv>`
	var out []struct {
		Channel string `json:"channel"`
		Start   int64  `json:"start"`
		End     int64  `json:"end"`
		Title   string `json:"title"`
	}
	evalJSON(t, r, `(() => {
		const L = globalThis.__L, ps = [];
		L.scanXmltv(`+jsQuote(xml)+`, (p) => ps.push(p));
		return JSON.stringify(L.normalizePrograms(ps));
	})()`, &out)

	if len(out) != 2 {
		t.Fatalf("解出 %d 条节目,应当是 2", len(out))
	}
	// 2026-09-21 12:00 +0800 = 04:00 UTC = 1789963200
	if out[0].Start != 1789963200 {
		t.Errorf("开始时间 = %d,应当是 1789963200(+0800 当成偏移解,不是当本地时间)", out[0].Start)
	}
	if out[0].Title != "新闻 & 天气" {
		t.Errorf("标题没反转义:%q", out[0].Title)
	}
	// 第二条没有 stop,要补成下一条的 start;它是最后一条,补 1 小时
	if out[1].End != out[1].Start+3600 {
		t.Errorf("缺失的 stop 没补上:%d → %d", out[1].Start, out[1].End)
	}
	if out[0].End != out[1].Start {
		t.Errorf("第一条的结束没接上第二条的开始:%d / %d", out[0].End, out[1].Start)
	}
}

// jsQuote 把一段文本变成 JS 字面量。用 JSON 编码 —— 手写转义迟早漏一个反斜杠。
func jsQuote(s string) string {
	b, err := json.Marshal(s)
	if err != nil {
		return `""`
	}
	return string(b)
}
