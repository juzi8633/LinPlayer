package plugin

import (
	"encoding/json"
	"strings"
	"testing"
)

func manifestWith(t *testing.T, contributes string) *Manifest {
	t.Helper()
	raw := `{"id":"a/b","name":"x","version":"1.0.0","main":"m.js","contributes":` + contributes + `}`
	m := &Manifest{Raw: json.RawMessage(raw)}
	if err := json.Unmarshal([]byte(raw), m); err != nil {
		t.Fatal(err)
	}
	return m
}

// 安装确认上,宿主没接的贡献点必须当场标出来 —— 不标的话那一行是在替插件
// 许一个不会兑现的愿:用户点了安装,界面上什么都不多,一条错也没有(D585)。
func Test没接的贡献点在安装确认里标出来(t *testing.T) {
	m := manifestWith(t, `{"sidebar":[{"id":"a","title":"t","page":"p"}],"trayMenu":[{"title":"x","command":"c"}]}`)
	got := strings.Join(m.ContributionList(), " / ")
	if !strings.Contains(got, "侧栏入口") || strings.Contains(got, "侧栏入口(这一版") {
		t.Errorf("侧栏入口是接通了的,不该被标成不支持:%s", got)
	}
	if !strings.Contains(got, "托盘菜单(这一版还不支持)") {
		t.Errorf("托盘菜单宿主一行都没接,清单上必须说出来:%s", got)
	}
}

// `lp check` 的警告:作者在本机就该知道自己声明的东西不会生效。
func Test作者本机就能看到宿主没接(t *testing.T) {
	m := manifestWith(t, `{"virtualLibraries":[{"id":"v","title":"t"}],"pages":[{"id":"p","title":"t"}]}`)
	warn := strings.Join(m.UnsupportedContribs(), "\n")
	if !strings.Contains(warn, "virtualLibraries") || !strings.Contains(warn, "不会有任何反应") {
		t.Errorf("虚拟媒体库没接,应该警告:%q", warn)
	}
	if strings.Contains(warn, "pages") {
		t.Errorf("插件页面是接通了的,不该警告:%q", warn)
	}
}

// 只接了一半的也要说清楚接的是哪一半,否则作者会以为整块都能用。
func Test只接了一半的说清楚哪一半(t *testing.T) {
	m := manifestWith(t, `{"menus":[{"target":"card","title":"t","command":"c"}]}`)
	warn := strings.Join(m.UnsupportedContribs(), "\n")
	if !strings.Contains(warn, "数据源列表项") {
		t.Errorf("菜单项只有数据源列表项那一处接了,得说出来:%q", warn)
	}
}
