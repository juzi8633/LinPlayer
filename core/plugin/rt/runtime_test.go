package rt

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"linplayer/core/paths"
)

func newRT(t *testing.T, code string, mod ...func(*Options)) *Runtime {
	t.Helper()
	paths.SetRoot(t.TempDir())
	opt := Options{ID: "test/plugin", Version: "1.0.0", PkgDir: t.TempDir(), DataDir: t.TempDir()}
	for _, m := range mod {
		m(&opt)
	}
	r, err := New(opt)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(r.Close)
	src := "const {definePlugin, PluginError, storage, html, crypt, js, registry} = " + SDKGlobal + ";\n" + code
	if _, err := r.Eval(context.Background(), BudgetData, "main.js", src); err != nil {
		t.Fatalf("加载失败: %v", err)
	}
	return r
}

func invoke(t *testing.T, r *Runtime, budget time.Duration, path string, args ...any) (json.RawMessage, error) {
	t.Helper()
	return r.Invoke(context.Background(), budget, path, args, map[string]any{})
}

func kindOf(err error) string {
	var e *Error
	if errors.As(err, &e) {
		return e.Kind
	}
	return ""
}

// D322 ④ 防崩三件套之一:死循环被预算打断。
func TestDeadLoopInterrupted(t *testing.T) {
	var reported []*Error
	r := newRT(t, `definePlugin({ dataSource: { home() { for(;;){} }, detail(){ return {id:'1',kind:'movie',title:'x'} } } })`,
		func(o *Options) { o.Host.OnError = func(e *Error) { reported = append(reported, e) } })
	done := make(chan error, 1)
	start := time.Now()
	go func() { _, err := invoke(t, r, 200*time.Millisecond, "dataSource.home"); done <- err }()
	select {
	case err := <-done:
		if kindOf(err) != KindTimeout {
			t.Fatalf("期望 timeout,得到 %v", err)
		}
		if el := time.Since(start); el > 2*time.Second {
			t.Fatalf("打断太慢: %v", el)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("死循环 5 秒内没有被打断")
	}
	if len(reported) == 0 {
		t.Fatal("超时没有报给宿主计连错")
	}
	// 打断后运行时还能用
	out, err := invoke(t, r, time.Second, "dataSource.detail")
	if err != nil || !strings.Contains(string(out), `"title":"x"`) {
		t.Fatalf("打断后运行时不可用: %s %v", out, err)
	}
}

// 回溯类正则落到 regexp2 时也得打得断(spike 09 的发现)。
func TestCatastrophicRegexInterrupted(t *testing.T) {
	r := newRT(t, `definePlugin({ hooks: { listTransform() { /(?=a)(a|aa)+$/.test("a".repeat(45)+"b"); return [] } } })`)
	done := make(chan error, 1)
	go func() { _, err := invoke(t, r, 300*time.Millisecond, "hooks.listTransform"); done <- err }()
	select {
	case err := <-done:
		if kindOf(err) != KindTimeout {
			t.Fatalf("期望 timeout,得到 %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("灾难回溯正则 5 秒内没有被打断")
	}
}

// 等网络不计入预算(D53)。
func TestAwaitDoesNotCount(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(400 * time.Millisecond)
		w.Write([]byte(`{"ok":1}`))
	}))
	defer srv.Close()
	r := newRT(t, `definePlugin({ dataSource: { async home(ctx) { const r = await fetch(`+"`"+srv.URL+"`"+`); return await r.json() } } })`,
		func(o *Options) { o.LAN = true })
	out, err := invoke(t, r, 100*time.Millisecond, "dataSource.home")
	if err != nil || string(out) != `{"ok":1}` {
		t.Fatalf("得到 %s %v", out, err)
	}
}

func TestPluginErrorKinds(t *testing.T) {
	r := newRT(t, `definePlugin({ dataSource: {
		async search() { throw new PluginError({kind:'rateLimited', message:'太频繁', retryAfter: 12}) },
		async home() { null.x },
	} })`)
	_, err := invoke(t, r, time.Second, "dataSource.search")
	var e *Error
	if !errors.As(err, &e) || e.Kind != KindRateLimited || e.RetryAfter != 12 || e.Message != "太频繁" {
		t.Fatalf("限流错误没透出: %#v", err)
	}
	_, err = invoke(t, r, time.Second, "dataSource.home")
	if kindOf(err) != KindInternal {
		t.Fatalf("未捕获异常应归 internal: %v", err)
	}
	if _, err := invoke(t, r, time.Second, "dataSource.person"); kindOf(err) != KindUnsupported {
		t.Fatalf("没实现的动词应是 unsupported: %v", err)
	}
	if r.Has("dataSource.person") || !r.Has("dataSource.home") {
		t.Fatal("Has 判断不对")
	}
}

// L1:没声明 lan 访问私网地址被拦,声明了放行。
func TestLANBlocked(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("hi")) }))
	defer srv.Close()
	code := `definePlugin({ dataSource: { async home() { return await (await fetch(` + "`" + srv.URL + "`" + `)).text() } } })`
	r := newRT(t, code)
	_, err := invoke(t, r, time.Second, "dataSource.home")
	if kindOf(err) != KindPermission || !strings.Contains(err.Error(), "lan") {
		t.Fatalf("未声明 lan 应被拦: %v", err)
	}
	r2 := newRT(t, code, func(o *Options) { o.LAN = true })
	out, err := invoke(t, r2, time.Second, "dataSource.home")
	if err != nil || string(out) != `"hi"` {
		t.Fatalf("声明 lan 后应放行: %s %v", out, err)
	}
}

func TestManualRedirectAndHeaders(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, q *http.Request) {
		if q.URL.Path == "/r" {
			http.Redirect(w, q, "/final", http.StatusFound)
			return
		}
		w.Write([]byte(q.Header.Get("Referer") + "|" + q.Header.Get("User-Agent")))
	}))
	defer srv.Close()
	r := newRT(t, `definePlugin({ dataSource: {
		async home() { const r = await fetch(`+"`"+srv.URL+"/r`"+`, {redirect:'manual'}); return [r.status, r.headers.get('location')] },
		async search() { const r = await fetch(`+"`"+srv.URL+"/r`"+`, {headers:{Referer:'https://ref.example/'}}); return [r.status, await r.text(), r.redirected] },
	} })`, func(o *Options) { o.LAN = true })
	out, err := invoke(t, r, time.Second, "dataSource.home")
	if err != nil || string(out) != `[302,"/final"]` {
		t.Fatalf("manual 重定向: %s %v", out, err)
	}
	out, err = invoke(t, r, time.Second, "dataSource.search")
	if err != nil || !strings.Contains(string(out), `"https://ref.example/|Mozilla/5.0`) || !strings.HasSuffix(string(out), "true]") {
		t.Fatalf("默认跟随 + 头 + 补 UA: %s %v", out, err)
	}
}

func TestStorageSyncAndPersist(t *testing.T) {
	dir := t.TempDir()
	code := `definePlugin({ commands: { put() { storage.set('a', {n: 1}); return storage.get('a').n + storage.keys().length } } })`
	r := newRT(t, code, func(o *Options) { o.DataDir = dir })
	out, err := invoke(t, r, time.Second, "commands.put")
	if err != nil || string(out) != "2" {
		t.Fatalf("同步读写: %s %v", out, err)
	}
	r.Close()
	r2 := newRT(t, `definePlugin({ commands: { get() { return storage.get('a') } } })`, func(o *Options) { o.DataDir = dir })
	out, err = invoke(t, r2, time.Second, "commands.get")
	if err != nil || string(out) != `{"n":1}` {
		t.Fatalf("重启后 KV 丢了: %s %v", out, err)
	}
}

func TestSubRuntimeSyncHost(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`<ul><li class="it"><a href="/v/1" title="甲">甲</a></li><li class="it"><a href="/v/2" title="乙">乙</a></li></ul>`))
	}))
	defer srv.Close()
	r := newRT(t, `definePlugin({ dataSource: { async home() {
		const c = await js.createContext({ withSyncHost: true, globals: { HOST: `+"`"+srv.URL+"`"+`, twice: (x) => x * 2 } });
		await c.run('function list(){ const h = req(HOST + "/").content; return pdfa(h, ".it").map(x => [pdfh(x, "a&&title"), pd(x, "a&&href", HOST)]) }');
		const out = await c.call('list');
		const t = await c.run('twice(21)');
		c.dispose();
		return [out, t];
	} } })`, func(o *Options) { o.LAN = true })
	out, err := invoke(t, r, 5*time.Second, "dataSource.home")
	want := `[[["甲","` + srv.URL + `/v/1"],["乙","` + srv.URL + `/v/2"]],42]`
	if err != nil || string(out) != want {
		t.Fatalf("子运行时:\n得到 %s %v\n期望 %s", out, err, want)
	}
}

func TestSubRuntimeDeadLoop(t *testing.T) {
	r := newRT(t, `definePlugin({ dataSource: { async home() {
		const c = await js.createContext({});
		return await c.run('for(;;){}');
	} } })`)
	// 子运行时的预算是固定 30 秒;这里只验证它确实会被打断并以 timeout 返回。
	if testing.Short() {
		t.Skip("要等 30 秒")
	}
	_, err := invoke(t, r, time.Minute, "dataSource.home")
	if kindOf(err) != KindTimeout {
		t.Fatalf("子运行时死循环应超时: %v", err)
	}
}

func TestBundleAssetsScheme(t *testing.T) {
	r := newRT(t, `const files = {
		'assets://libs/main.js': "import x from './util.js'; import y from '../js/tpl.js'; globalThis.out = x + y;",
		'assets://libs/util.js': "export default 40",
		'assets://js/tpl.js': "module.exports = 2",
	};
	definePlugin({ dataSource: { async home() {
		const code = await js.bundle('assets://libs/main.js', { resolve: async (p) => files[p] ?? null });
		const c = await js.createContext({});
		await c.run(code);
		return await c.run('out');
	} } })`)
	out, err := invoke(t, r, 10*time.Second, "dataSource.home")
	if err != nil || string(out) != "42" {
		t.Fatalf("打包: %s %v", out, err)
	}
}

func TestCryptCryptoJSCompatible(t *testing.T) {
	// 期望密文是用真 crypto-js(drpy 自带那份)在 node 里算的:
	// AES.encrypt('hello', Utf8.parse('0123456789abcdef'), {iv: 同 key}) 默认 CBC / ECB
	r := newRT(t, `definePlugin({ commands: { go() {
		const opts = { key: '0123456789abcdef', iv: '0123456789abcdef', mode: 'cbc' };
		const enc = crypt.aesEncrypt('hello', opts);
		return [enc, crypt.aesDecrypt(enc, opts), crypt.md5('abc'), crypt.base64Decode('5L2g5aW9'), crypt.gbkDecode(crypt.gbkEncode('中文')),
			crypt.aesEncrypt('hello', { key: '0123456789abcdef', mode: 'ecb' })];
	} } })`)
	out, err := invoke(t, r, time.Second, "commands.go")
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	_ = json.Unmarshal(out, &got)
	if got[1] != "hello" || got[2] != "900150983cd24fb0d6963f7d28e17f72" || got[3] != "你好" || got[4] != "中文" {
		t.Fatalf("加解密: %v", got)
	}
	if got[0] != "MOfLtxzZ0YgS4+5cPylFYw==" || got[5] != "Z0x+8454yr2c7JwSWCOmOQ==" {
		t.Fatalf("与 CryptoJS 密文不一致: cbc=%s ecb=%s", got[0], got[5])
	}
}

func TestWebGlobals(t *testing.T) {
	r := newRT(t, `definePlugin({ commands: { go() {
		const u = new URL('../b?x=1#h', 'https://a.example/p/q');
		u.searchParams.set('y', '中');
		const sp = new URLSearchParams('a=1&b=%E4%B8%AD');
		return [u.href, u.hostname, sp.get('b'), atob(btoa('ab')), new TextDecoder().decode(new TextEncoder().encode('字')), typeof AbortController, typeof setTimeout];
	} } })`)
	out, err := invoke(t, r, time.Second, "commands.go")
	want := `["https://a.example/b?x=1&y=%E4%B8%AD#h","a.example","中","ab","字","function","function"]`
	if err != nil || string(out) != want {
		t.Fatalf("Web 全局:\n得到 %s %v\n期望 %s", out, err, want)
	}
}

func TestTimersAndCancel(t *testing.T) {
	r := newRT(t, `definePlugin({ dataSource: {
		home() { return new Promise(res => setTimeout(() => res('late'), 50)) },
		search(req, ctx) { return new Promise((res, rej) => { ctx.signal.addEventListener('abort', () => rej(new PluginError({kind:'timeout', message:'取消'}))) }) },
	} })`)
	out, err := invoke(t, r, time.Second, "dataSource.home")
	if err != nil || string(out) != `"late"` {
		t.Fatalf("setTimeout: %s %v", out, err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if _, err := r.Invoke(ctx, time.Second, "dataSource.search", []any{map[string]any{}}, map[string]any{}); err == nil {
		t.Fatal("ctx 取消后应返回错误")
	}
}

func TestRegistryPrefixAndHostChannel(t *testing.T) {
	r := newRT(t, `definePlugin({ commands: {
		ok() { registry.put('test/plugin/x', 'k', {a:1}); registry.put('live.channels', 'c', {kind:'url', name:'n', url:'u'}); return registry.list('test/plugin/x') },
		bad() { registry.put('other/p/x', 'k', 1) },
		bad2() { registry.put('live.channels', 'k', {name:'缺 kind'}) },
	} })`)
	out, err := invoke(t, r, time.Second, "commands.ok")
	if err != nil || !strings.Contains(string(out), `"writer":"test/plugin"`) {
		t.Fatalf("注册表: %s %v", out, err)
	}
	if _, err := invoke(t, r, time.Second, "commands.bad"); kindOf(err) != KindInvalid {
		t.Fatalf("别人前缀应被拒: %v", err)
	}
	if _, err := invoke(t, r, time.Second, "commands.bad2"); kindOf(err) != KindInvalid {
		t.Fatalf("宿主通道缺字段应被拒: %v", err)
	}
	RegistryClearWriter("test/plugin")
	if len(RegistryList("test/plugin/x")) != 0 {
		t.Fatal("卸载后条目没清")
	}
}
