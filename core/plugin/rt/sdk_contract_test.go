package rt

import (
	"context"
	"encoding/json"
	"sort"
	"strconv"
	"strings"
	"testing"
)

/*
D514 的门禁:宿主 API 的**定义源是 `docs/plugin-system/api/plugin-sdk.d.ts`**,
`tools/sdkgen/gen.mjs` 用 TypeScript 编译器 API 读它产出 SDKSpec(`sdkspec_gen.go`),
这里拿 SDKSpec 和运行时**真正挂上去**的 `__linplayer_sdk` 逐名比对。

☠ 这一关抓的是**名字对不上**:`crypt.md5` 写成 `crypt.MD5`、`storage.remove` 写成 `storage.delete`,
   插件调用时是 `undefined is not a function`,而它看起来像插件自己写错了。
   编译器不管这件事 —— 两边一个是 .d.ts 一个是 goja 的字符串键。

阶段 ① 只实现一部分命名空间(见 sdk.go 顶部),所以判据是**单向**的:
运行时挂出来的每个名字都必须在定义源里有。反过来不成立(还没实现的不算错),
但已实现的命名空间少了成员会被 [implementedFully] 那张表挡住。
*/

// implementedFully 这些命名空间声称「定义源里有几个就实现几个」。
// 阶段 ② 起逐个往里加 —— 加进来之后少一个成员就红。
var implementedFully = []string{"storage", "secrets", "cookies", "assets", "crypt", "html", "js", "registry", "settings", "app"}

// sdkShape 问运行时:__linplayer_sdk 上真有哪些命名空间、每个下面有哪些键。
func sdkShape(t *testing.T) map[string][]string {
	t.Helper()
	r := newRT(t, ``)
	// 只收对象型的命名空间;definePlugin / PluginError 是函数与构造器,不是命名空间
	const probe = `(function () {
		var out = {}, sdk = ` + SDKGlobal + `;
		Object.keys(sdk).forEach(function (k) {
			var v = sdk[k];
			if (v === null || typeof v !== 'object') return;
			var names = [];
			for (var p in v) names.push(p);
			Object.getOwnPropertyNames(v).forEach(function (p) { if (names.indexOf(p) < 0) names.push(p); });
			out[k] = names;
		});
		return JSON.stringify(out);
	})()`
	v, err := r.Eval(context.Background(), BudgetData, "probe.js", probe)
	if err != nil {
		t.Fatalf("枚举 SDK 失败: %v", err)
	}
	var raw string
	if err := json.Unmarshal(v, &raw); err != nil {
		t.Fatalf("探针没回字符串: %v (%s)", err, v)
	}
	var got map[string][]string
	if err := json.Unmarshal([]byte(raw), &got); err != nil {
		t.Fatalf("探针结果不是 JSON: %v (%s)", err, raw)
	}
	return got
}

func TestSDK注册名必须在定义源里有(t *testing.T) {
	if len(SDKSpec) == 0 {
		t.Fatal("SDKSpec 是空的 —— sdkspec_gen.go 没生成或生成器坏了,跑 `pnpm --dir tools/sdkgen gen`")
	}
	got := sdkShape(t)
	if len(got) == 0 {
		t.Fatal("运行时一个命名空间都没挂上,探针八成坏了")
	}
	for ns, names := range got {
		want, ok := SDKSpec[ns]
		if !ok {
			t.Errorf("运行时挂了命名空间 %q,而 plugin-sdk.d.ts 里没有它 —— 要么定义源漏写,要么名字拼错了", ns)
			continue
		}
		for _, n := range names {
			if !contains(want, n) {
				t.Errorf("%s.%s 挂上了,但定义源里没有这个名字(定义源里有:%s)", ns, n, strings.Join(want, " "))
			}
		}
	}
}

// realComponents 这几个是**真组件**不是字符串:窗口内的项要由 JS 渲染(D134)。
var realComponents = []string{"VirtualList", "VirtualGrid", "Canvas"}

// JSX 里 <View> 编译成标识符,SDK 上少一个名字就是 `View is not defined` ——
// 报在插件那边,看起来像插件写错了。
func TestSDK组件名全部挂上(t *testing.T) {
	if len(SDKComponents) < 20 {
		t.Fatal("SDKComponents 太少 —— 生成器八成没抽到组件")
	}
	r := newRT(t, ``)
	for _, name := range SDKComponents {
		v, err := r.Eval(context.Background(), BudgetData, "x.js",
			"typeof "+SDKGlobal+"["+strconv.Quote(name)+"] === 'function' ? '#fn' : JSON.stringify("+SDKGlobal+"["+strconv.Quote(name)+"] || null)")
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		var raw string
		_ = json.Unmarshal(v, &raw)
		if contains(realComponents, name) {
			if raw != "#fn" {
				t.Errorf("组件 %s 应该是真组件(函数),拿到 %s", name, raw)
			}
			continue
		}
		// 其余的挂的就是自己的名字:Preact 见到字符串类型就建宿主元素
		if raw != strconv.Quote(name) {
			t.Errorf("组件 %s 没挂上(拿到 %s)", name, raw)
		}
	}
}

func TestSDK已实现的命名空间不许缺成员(t *testing.T) {
	got := sdkShape(t)
	for _, ns := range implementedFully {
		want, ok := SDKSpec[ns]
		if !ok {
			t.Errorf("定义源里没有命名空间 %q —— 它被从 .d.ts 删了?那这张表也要改", ns)
			continue
		}
		have := got[ns]
		var missing []string
		for _, n := range want {
			if !contains(have, n) {
				missing = append(missing, n)
			}
		}
		if len(missing) > 0 {
			sort.Strings(missing)
			t.Errorf("%s 声称已实现,但运行时没挂:%s", ns, strings.Join(missing, " "))
		}
	}
}

func contains(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

/*
hooks 是 D514 一直漏掉的那一半。

☠ 2026-09-20 的复核抓到:`.d.ts` 声明了 useTheme / usePlayerState / useSetting /
   useStorage / useReducedMotion,运行时**一个都没挂**,插件拿到 undefined ——
   而那个错误报在插件那边,看起来像插件自己写错了。组件名有门禁,hooks 没有。

这一条不是自己验自己:SDKHooks 由 gen.mjs 从 `.d.ts` 解析,挂的那一侧来自
Preact 的 hooks 模块与渲染器,两边没有共同的源。
*/
func TestSDKHooks全部挂上且可调用(t *testing.T) {
	if len(SDKHooks) < 10 {
		t.Fatal("SDKHooks 太少 —— 生成器八成没抽到 hooks")
	}
	r := newRT(t, ``)
	for _, name := range SDKHooks {
		v, err := r.Eval(context.Background(), BudgetData, "x.js",
			"typeof "+SDKGlobal+"["+strconv.Quote(name)+"]")
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		var raw string
		_ = json.Unmarshal(v, &raw)
		if raw != "function" {
			t.Errorf("hook %s 没挂上(typeof = %s)—— 插件会拿到 undefined", name, raw)
		}
	}
}

// 渲染器自己那几个 hook 在**真渲染**里要能出值,不能只是挂着一个函数。
func TestSDKHooks在渲染里真能取到值(t *testing.T) {
	r, cap := newUI(t, `
		const { h, useTheme, useReducedMotion, useViewport } = __linplayer_sdk;
		definePlugin({ pages: { p: () => {
			const t = useTheme(), rm = useReducedMotion(), vp = useViewport();
			return h('Text', { mode: t.mode, reduced: String(rm), bp: vp.breakpoint });
		} } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "mode") })
	ops := cap.ops(t)
	for _, want := range []string{"mode", "reduced", "bp"} {
		if !hasProp(ops, want) {
			t.Errorf("hook 的结果没进 props:缺 %s\n%s", want, cap.dump())
		}
	}
}

// 壳报来的主题变化要能叫醒 useTheme(SPEC 7.5 D89)。
//
// 反向验证过:把 renderer.js 的 env() 改成只存不通知,这条当场红在「主题变了界面没跟着变」。
func TestSDKHooks主题变了要重渲染(t *testing.T) {
	r, cap := newUI(t, `
		const { h, useTheme } = __linplayer_sdk;
		definePlugin({ pages: { p: () => {
			const t = useTheme();
			return h('Text', { mode: t.mode, acc: t.token('color.accent') || '' });
		} } })
	`)
	if err := r.UIMount("s1", "page", "p", nil); err != nil {
		t.Fatal(err)
	}
	cap.wait(t, func() bool { return hasProp(cap.ops(t), "mode") })

	SetEnv(Env{ThemeMode: "light", ThemeTokens: map[string]any{"color.accent": "#ff8800"}})
	t.Cleanup(func() { SetEnv(Env{}) })

	cap.wait(t, func() bool {
		var mode, acc bool
		for _, o := range cap.ops(t) {
			set, ok := o["set"].(map[string]any)
			if !ok {
				continue
			}
			mode = mode || set["mode"] == "light"
			acc = acc || set["acc"] == "#ff8800"
		}
		return mode && acc
	})
}

/*
反方向的判据:定义源里有的命名空间,运行时**要么全挂上,要么在这张表上**。

☠ 上一版只有单向判据(挂上的必须在定义源里有),于是「定义源声明了、运行时
  一个都没挂」是完全看不见的 —— `nav` 整个不存在、`ui` 只有 toast,
  两者都活到了第三方审计才被发现。插件那边的表现是
  `typeof sdk.ui === 'object'` 通过特性探测,然后 `ui.confirm(...)` 抛
  「undefined is not a function」,**报在插件那边**,看起来像插件写错了。

这张表是**账**,不是豁免:每一行要写清「为什么还没有」和「什么时候有」。
实现完就把那一行删掉 —— 删不掉说明没实现完。
*/
var notYetImplemented = map[string]string{
	// 账要记到**成员**这一级:记到命名空间那一级的话,`ui` 只挂了 toast
	// 也能被「ui 在账上」放过 —— 那正是这条判据要抓的东西。
	"debug.*": "debug 命名空间跟着开发者模式开关(D555);测试用的运行时没开,所以整个不在",


	"ui.openWindow": "桌面子窗口(D220 D500)要壳做窗口管理,阶段 ⑤ 接",

	"files.pick":      "文件选择器要壳弹系统对话框,阶段 ③ 和 nav 一起接",
	"files.save":      "同上",
	"files.saveImage": "同上",

	"desktop.setTaskbarBadge":    "桌面任务栏集成(D502 托盘那一批)阶段 ⑤ 接",
	"desktop.setTaskbarProgress": "同上",
	"desktop.setWindowTitle":     "同上",

	"tvChannels.publish": "Android TV 推荐行(D505)阶段 ④ 和直播一起接",
	"widgets.update":     "桌面小组件(D506)阶段 ⑤ 接",

	"canvas.renderToPng": "要离屏 GL 上下文,跟阶段 ⑤ 的主题预览一起做",
	"cast.open":          "投屏设备选择在壳那边,阶段 ④ 接",
	"download.enqueue":   "官方下载器的权限门在宿主,阶段 ③ 末尾接",
	"emby.request":       "只读 GET 代发要先做响应脱敏(D472),阶段 ③ 接",
	"oauth.authorize":    "自建中转的 OAuth(D365)跟同步插件一起接",
	"oauth.refresh":      "同上",
	"proxy.route":        "本地代理路由跟阶段 ④ 的直播一起接",
	"proxy.url":          "同上",
	"system.clipboard":   "剪贴板 / 打开外部应用 / 分享要壳实现,阶段 ③ 接",
	"system.openApp":     "同上",
	"system.openUrl":     "同上",
	"system.share":       "同上",
	"wallpaper.set":      "壁纸是阶段 ⑤",

	// media.*(D92 的「封装好的有限接口」):要先把图片地址脱 api_key、
	// 把跨服观看记录合并那一套接过来,阶段 ③ 和同步插件一起做。
	"media.search":      "媒体库查询封装(D92)阶段 ③ 接",
	"media.getItem":     "同上",
	"media.getChildren": "同上",
	"media.getLatest":   "同上",
	"media.history":     "同上",
	"media.favorites":   "同上",
	"media.setFavorite": "同上",
	"media.setPlayed":   "同上",
	"media.setProgress": "同上",
}

// bookedReason 这个成员在不在账上。`命名空间.*` 覆盖整个命名空间。
func bookedReason(ns, member string) (string, bool) {
	if why, ok := notYetImplemented[ns+"."+member]; ok {
		return why, true
	}
	why, ok := notYetImplemented[ns+".*"]
	return why, ok
}

func TestSDK定义源里的命名空间要么全挂要么在账上(t *testing.T) {
	got := sdkShape(t)
	var gaps []string
	for ns, want := range SDKSpec {
		have := got[ns]
		var missing []string
		for _, n := range want {
			if !contains(have, n) {
				missing = append(missing, n)
			}
		}
		for _, n := range missing {
			why, onBook := bookedReason(ns, n)
			switch {
			case !onBook:
				gaps = append(gaps, ns+"."+n+" —— 定义源声明了、运行时没挂,而且不在 notYetImplemented 这张账上")
			case strings.TrimSpace(why) == "":
				gaps = append(gaps, ns+"."+n+" —— 在账上但没写理由;没理由的账等于把它藏起来")
			}
		}
	}
	sort.Strings(gaps)
	if len(gaps) > 0 {
		t.Fatalf("定义源声明了但运行时没挂,又没记在账上:\n  %s", strings.Join(gaps, "\n  "))
	}
}
