package rt

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
)

/*
mpv 脱敏清单(SPEC 9.1 的 E3,19.5 列为必须有的门禁)。

☠ 这张表是 D11「插件拿不到 Emby 凭据」的最后一道门:`path` 里带着 api_key,
  `http-header-fields` 里带着 Authorization。少一条就等于把凭据交出去,
  而调用方看到的是一条完全正常的属性读 —— 没有任何东西会变红。

判据不是「表里有几项」(那会跟着表一起改),是**真的读一次**:
让宿主回一个带凭据的假值,插件那边必须拿不到它。
*/

func playerRT(t *testing.T, code string) (*Runtime, *fakePlayer) {
	t.Helper()
	fp := &fakePlayer{vals: map[string]any{}}
	r := newRT(t, code, func(o *Options) { o.Host.Player = fp.hooks() })
	return r, fp
}

type fakePlayer struct {
	mu   sync.Mutex
	vals map[string]any
	sets []string
	cmds []string
}

func (f *fakePlayer) hooks() *PlayerHooks {
	return &PlayerHooks{
		Get: func(p string) (any, error) {
			f.mu.Lock()
			defer f.mu.Unlock()
			if v, ok := f.vals[p]; ok {
				return v, nil
			}
			return "旧值-" + p, nil
		},
		Set: func(p string, v any) error {
			f.mu.Lock()
			f.sets = append(f.sets, p+"="+jsonPlain(v))
			f.vals[p] = v
			f.mu.Unlock()
			return nil
		},
		Command: func(name string, args []any) (any, error) {
			f.mu.Lock()
			f.cmds = append(f.cmds, name)
			f.mu.Unlock()
			return "ok", nil
		},
	}
}

func jsonPlain(v any) string {
	b, _ := json.Marshal(v)
	return strings.Trim(string(b), `"`)
}

// 清单里的属性读出来必须是脱敏值 —— 一条都不许漏。
func TestPlayer脱敏清单上的属性读不到真值(t *testing.T) {
	const secret = "http://服务器/x?api_key=不该被插件看见"
	names := RedactedProps()
	if len(names) < 8 {
		t.Fatalf("脱敏清单只有 %d 项 —— 表被删空了?", len(names))
	}
	for _, name := range append(names, "playlist/0/filename") {
		r, fp := playerRT(t, ``)
		fp.vals[name] = secret
		v, err := r.Eval(context.Background(), BudgetData, "x.js",
			`__linplayer_sdk.player.get(`+jsonQuote(name)+`)`)
		if err != nil {
			t.Fatalf("读 %s 失败: %v", name, err)
		}
		var got string
		_ = json.Unmarshal(v, &got)
		if strings.Contains(got, "api_key") || got == secret {
			t.Errorf("player.get(%q) 把真值交出去了:%s", name, got)
		}
		if !strings.Contains(got, "脱敏") {
			t.Errorf("player.get(%q) 回的不是脱敏占位:%s —— 空值会让插件以为「这一项没有」", name, got)
		}
		r.Close()
	}
}

// 不在清单上的属性照常读得到 —— 否则「全都脱敏」也能让上面那条绿。
func TestPlayer清单外的属性照常读得到(t *testing.T) {
	r, fp := playerRT(t, ``)
	fp.vals["time-pos"] = 12.5
	v, err := r.Eval(context.Background(), BudgetData, "x.js", `__linplayer_sdk.player.get('time-pos')`)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(v), "脱敏") || !strings.Contains(string(v), "12.5") {
		t.Fatalf("time-pos 不该被脱敏,拿到 %s", v)
	}
}

// 换片类命令对插件不开放(SPEC 9.1):放开等于让插件自己拼带 token 的地址去播。
func TestPlayer换片类命令对插件不开放(t *testing.T) {
	for _, name := range BannedCommands() {
		r, fp := playerRT(t, ``)
		_, err := r.Eval(context.Background(), BudgetData, "x.js",
			`__linplayer_sdk.player.command(`+jsonQuote(name)+`)`)
		if err == nil {
			t.Errorf("mpv 命令 %s 竟然放行了", name)
		}
		fp.mu.Lock()
		n := len(fp.cmds)
		fp.mu.Unlock()
		if n != 0 {
			t.Errorf("mpv 命令 %s 被挡住了却还是发到宿主了", name)
		}
		r.Close()
	}
}

// 记账与还原(SPEC 9.2 D302):插件停用时改过的属性回到它介入之前的值。
func TestPlayer停用时还原改过的属性(t *testing.T) {
	r, fp := playerRT(t, ``)
	fp.vals["sub-scale"] = "1.0"
	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.player.set('sub-scale', 2).then(() => __linplayer_sdk.player.set('sub-scale', 3))`); err != nil {
		t.Fatal(err)
	}
	r.Close()

	fp.mu.Lock()
	defer fp.mu.Unlock()
	last := fp.sets[len(fp.sets)-1]
	if last != "sub-scale=1.0" {
		t.Fatalf("停用后应当还原成插件介入之前的 1.0,实际最后一次写的是 %q(全过程:%v)", last, fp.sets)
	}
}

func jsonQuote(s string) string { b, _ := json.Marshal(s); return string(b) }
