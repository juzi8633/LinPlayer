package rt

import (
	"context"
	"encoding/json"
	"sort"
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
var implementedFully = []string{"storage", "secrets", "cookies", "assets", "crypt", "html", "js", "registry"}

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
