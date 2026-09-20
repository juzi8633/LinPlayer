package config

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

/*
带 `omitempty` 的偏好项**必须清得掉**。

☠ 这条是 2026-09-21 在主题回退那条路上撞出来的:`prefsTypedKeys` 原来是

	「marshal 一个零值 Prefs{} 再看有哪些键」建的,而带 omitempty 的字段零值时
	根本不出现在那份 JSON 里 —— 于是它被当成「没接的键」收进 rest;
	之后把它置空时,结构体这边省略了,rest 那边又把旧值贴回来。
	表现是「这一项怎么都清不掉」:主题标记成加载失败之后,用户重选同一个主题
	仍然被当成失败的,那个主题被永久拉黑。

	判据不针对某一个字段:**凡是 omitempty 的键都要在 prefsTypedKeys 里**,
	少一个就会犯同一个病。
*/
func TestPrefs带omitempty的键也要认(t *testing.T) {
	typ := reflect.TypeOf(Prefs{})
	for i := 0; i < typ.NumField(); i++ {
		f := typ.Field(i)
		tag := f.Tag.Get("json")
		if tag == "" || tag == "-" {
			continue // 没导出 / 故意不进 JSON
		}
		name := tag
		if c := strings.IndexByte(name, ','); c >= 0 {
			name = name[:c]
		}
		if name == "" {
			continue
		}
		if !prefsTypedKeys[name] {
			t.Errorf("偏好键 %q(字段 %s)不在 prefsTypedKeys 里 —— 它清空之后旧值会从 rest 里复活", name, f.Name)
		}
	}
}

// 真的跑一遍「设了再清」:上面那条是形状,这条是行为。
func TestPrefs清空omitempty项不会复活(t *testing.T) {
	p := DefaultPrefs()
	p.ThemeFailed = "alice/demo"
	b, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	got := ParsePrefs(b)
	if got.ThemeFailed != "alice/demo" {
		t.Fatalf("设了都没存住:%q", got.ThemeFailed)
	}
	got.ThemeFailed = ""
	b2, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	again := ParsePrefs(b2)
	if again.ThemeFailed != "" {
		t.Fatalf("清空之后旧值又回来了:%q —— 这一项永远清不掉", again.ThemeFailed)
	}
}
