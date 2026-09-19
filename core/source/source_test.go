package source

import "testing"

// 插件 id 自带一个 /:必须按最后一个 / 切(SPEC 2.6,D153)。
func TestSplitPluginLastSlash(t *testing.T) {
	cases := []struct {
		in, plugin, src string
		ok              bool
	}{
		{"plugin:alice/tvbox/src1", "alice/tvbox", "src1", true},
		{"plugin:linplayer/tvbox/tvab12.cms-1", "linplayer/tvbox", "tvab12.cms-1", true},
		{"plugin:alice/tvbox/", "", "", false},
		{"emby", "", "", false},
	}
	for _, c := range cases {
		p, s, ok := SplitPlugin(Kind(c.in))
		if p != c.plugin || s != c.src || ok != c.ok {
			t.Errorf("%s → (%q, %q, %v),期望 (%q, %q, %v)", c.in, p, s, ok, c.plugin, c.src, c.ok)
		}
	}
	if k := PluginKind("alice/tvbox", "src1"); k != "plugin:alice/tvbox/src1" {
		t.Errorf("PluginKind 拼错: %s", k)
	}
}
