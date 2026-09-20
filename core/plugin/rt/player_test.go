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
	/* ☠ 判据是**逐名对账**,不是「表里至少有几项」。
	   上一版写的是 `len(names) < 8`(表里有 16 项)—— 删掉
	   `http-header-fields` 与 `loadfile` 之后四条用例全绿,
	   而那两行正是 Authorization 与「插件自己拼地址去播」这两个口子。
	   加新项不用改这里;**删项会当场红**,要删就得在这里一起删、说清为什么。 */
	must := []string{
		"path", "stream-open-filename", "stream-path", "http-header-fields",
		"working-directory", "playlist", "playlist-path", "file-local-options",
		"referrer", "user-agent", "cookies-file", "sub-file-paths",
		"screenshot-directory", "input-ipc-server", "log-file", "config-dir",
	}
	names := RedactedProps()
	have := map[string]bool{}
	for _, n := range names {
		have[n] = true
	}
	for _, n := range must {
		if !have[n] {
			t.Fatalf("脱敏清单里少了 %q —— 这一项读出来带着凭据", n)
		}
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
	// 同上:逐名对账。删一行表的话这里先红。
	for _, n := range []string{
		"loadfile", "loadlist", "playlist-play-index", "playlist-next", "playlist-prev",
		"playlist-remove", "playlist-clear", "run", "subprocess", "quit",
		"load-config-file", "load-script",
	} {
		if !CommandBanned(n) {
			t.Fatalf("禁用命令表里少了 %q —— 插件能拿它绕开宿主换片", n)
		}
	}
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

/*
改属性的 mpv 命令不许绕过 set 的规矩(SPEC 9.1 9.2,D302)。

☠ `command('set', 'sub-scale', '2')` 和 `set('sub-scale','2')` 对用户是同一件事,
  而上一版前者既不记账也不看脱敏清单:插件改过的东西停用时不还原(D302 白写),
  脱敏清单上的属性也能被写。两条路的规矩必须一样。
*/
func TestPlayer用command改属性也要记账与挡脱敏项(t *testing.T) {
	r, fp := playerRT(t, ``)
	fp.vals["sub-scale"] = "1.0"

	if _, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.player.command('set', 'sub-scale', '2.5')`); err != nil {
		t.Fatalf("正常属性用 command 改应当放行:%v", err)
	}
	r.RestoreProps()
	fp.mu.Lock()
	sets := append([]string(nil), fp.sets...)
	fp.mu.Unlock()
	restored := false
	for _, s := range sets {
		if strings.HasPrefix(s, "sub-scale=1.0") {
			restored = true
		}
	}
	if !restored {
		t.Errorf("用 command 改的属性停用时没还原:%v", sets)
	}

	// 脱敏清单上的属性:从 command 这条路也要挡下来
	_, err := r.Eval(context.Background(), BudgetData, "x.js",
		`__linplayer_sdk.player.command('set', 'http-header-fields', 'Authorization: 偷来的')`)
	if err == nil {
		t.Fatal("command('set','http-header-fields',…) 放行了 —— 插件能改取流请求头")
	}
	if kindOf(err) != string(KindPermission) {
		t.Errorf("挡下来的类型是 %s,应当是 permission:%v", kindOf(err), err)
	}
}
