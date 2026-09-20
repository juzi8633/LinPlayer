package plugin

import (
	"strings"
	"testing"
)

/*
`servers` 命名空间的那条底线(D92 D11):只给 id 与名称,**不给地址与凭据**。

☠ 这是 D11 换了个入口:`account.listAccounts` 的 Info 里带着 server(地址)、
  line_url、lines —— 原样交给插件等于把地址给了它,而地址加上用户名就是攻击面。
  做法是**挑白名单**不是过滤黑名单:挑漏一个是少给一样东西,
  过滤漏一个是把凭据送出去。这条判据盯的就是「有没有人改成过滤」。
*/
func TestServers只给id与名称(t *testing.T) {
	got := infoFromAccount(map[string]any{
		"server":      "http://内网地址:8096",
		"user_id":     "u1",
		"user_name":   "某用户",
		"name":        "客厅那台",
		"line_url":    "http://另一条线路:8096",
		"source_kind": "emby",
		"active":      true,
	})
	b := got.ID + "|" + got.Name + "|" + got.Type
	for _, leak := range []string{"内网地址", "8096", "u1", "某用户", "另一条线路"} {
		if strings.Contains(b, leak) {
			t.Fatalf("交给插件的结构里漏出了 %q:%s", leak, b)
		}
	}
	if got.Name != "客厅那台" || got.Type != "emby" || got.ID == "" {
		t.Fatalf("该给的没给:%+v", got)
	}
}

// id 要跨设备稳定(D287):同一台服两次算出来必须一样,不同服必须不一样。
func TestServers的id稳定且不撞(t *testing.T) {
	a := serverID("http://甲:8096", "u1", "emby")
	if a != serverID("http://甲:8096", "u1", "emby") {
		t.Fatal("同一台服两次算出来不一样 —— 插件按剧记住的东西会全丢")
	}
	if a == serverID("http://乙:8096", "u1", "emby") {
		t.Fatal("两台服算出同一个 id")
	}
	if a == serverID("http://甲:8096", "u2", "emby") {
		t.Fatal("同一台服的两个用户算出同一个 id")
	}
	// 数据源用开放键本身:那是公开标识,不需要哈希
	if serverID("", "", "plugin:linplayer/tvbox/x") != "plugin:linplayer/tvbox/x" {
		t.Fatal("数据源的 id 应当就是它的开放键")
	}
}
