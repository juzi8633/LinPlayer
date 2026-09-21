package emby

import "testing"

// 漏一个斜杠不该变成一句英文报错。
//
// 2026-09-21 报障:用户填 `https:host`(少了 `//`),壳看它不以 `https://` 开头,
// 又在前面拼了个 `http://` —— 发出去的是 `http://https:host/Users/AuthenticateByName`,
// 界面上显示「请求构造失败:parse …: invalid port ":host" after host」。
// 普通 Emby 就这么连不上了,而用户只是漏按了一下斜杠。
func TestNormServer(t *testing.T) {
	ok := []struct{ in, want string }{
		{"  https://a.test  ", "https://a.test"},
		{"https://a.test/", "https://a.test"},
		{"a.test:8096", "http://a.test:8096"},     // 没写协议 → 补 http
		{"https:/a.test", "https://a.test"},       // 少一个斜杠
		{"https:a.test", "https://a.test"},        // 两个斜杠都没写
		{"HTTPS://a.test", "https://a.test"},      // 协议大写
		{"http://https:a.test", "https://a.test"}, // 壳补过一层:以里面那个为准
		{"http://https://a.test", "https://a.test"},
		{"https://a.test/emby/", "https://a.test/emby"},
		{"https://a.test/https:x", "https://a.test/https:x"}, // 只剥开头,路径里的不动
	}
	for _, c := range ok {
		got, err := NormServer(c.in)
		if err != nil || got != c.want {
			t.Errorf("NormServer(%q) = %q, %v;想要 %q, nil", c.in, got, err, c.want)
		}
	}

	// 修不回来的要**报中文**,别让它拼成 URL 再由 url.Parse 吐英文
	for _, in := range []string{"", "   ", "https://", "http://a.test:端口"} {
		if got, err := NormServer(in); err == nil {
			t.Errorf("NormServer(%q) = %q,应该报错", in, got)
		}
	}
}
