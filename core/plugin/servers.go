package plugin

// `servers` 命名空间(D92):插件看得到有哪几台服务器,**看不到地址与凭据**。
//
// ☠ 这是 D11 的同一条底线换了个入口:`account.listAccounts` 的 Info 里带着
//   `server`(地址)、`line_url`、`lines` —— 原样交给插件等于把地址给了它,
//   而地址加上用户名就是攻击面。所以这里**只挑三个字段出来**,不是过滤几个危险的:
//   挑白名单漏一个是少给一样东西,过滤黑名单漏一个是把凭据送出去。

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"

	"linplayer/core/bus"
	"linplayer/core/plugin/rt"
)

// serverInfo 交给插件的那三样(SPEC 的 ServerInfo)。
type serverInfo struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Type string `json:"type"` // emby | source
}

/*
serverID 跨设备稳定的 id(D287)。

Emby 用 `服务器地址 + 用户 id` 的哈希:直接用地址的话插件拿到的就是地址,
而 id 的用处只是「同一台服前后两次是同一个」,不需要可逆。
数据源用它的开放键(那本来就是公开标识)。
*/
func serverID(server, userID, sourceKind string) string {
	if sourceKind != "" && sourceKind != "emby" {
		return sourceKind
	}
	sum := sha1.Sum([]byte(server + "\x00" + userID))
	return hex.EncodeToString(sum[:10])
}

func serversList() any {
	out := []serverInfo{}
	raw, err := bus.Invoke(context.Background(), "account.listAccounts", nil)
	if err != nil {
		return out
	}
	for _, a := range asMaps(raw) {
		out = append(out, infoFromAccount(a))
	}
	return out
}

func serversCurrent() any {
	raw, err := bus.Invoke(context.Background(), "account.listAccounts", nil)
	if err != nil {
		return nil
	}
	for _, a := range asMaps(raw) {
		if b, _ := a["active"].(bool); b {
			i := infoFromAccount(a)
			return &i
		}
	}
	return nil
}

func infoFromAccount(a map[string]any) serverInfo {
	s, _ := a["server"].(string)
	uid, _ := a["user_id"].(string)
	kind, _ := a["source_kind"].(string)
	name, _ := a["name"].(string)
	if name == "" {
		name, _ = a["remark"].(string)
	}
	typ := "emby"
	if kind != "" && kind != "emby" {
		typ = "source"
	}
	return serverInfo{ID: serverID(s, uid, kind), Name: name, Type: typ}
}

// asMaps 把命令回的 any 摊成 []map。回的是具体结构体时走一次 JSON 往返。
func asMaps(v any) []map[string]any {
	if arr, ok := v.([]map[string]any); ok {
		return arr
	}
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var out []map[string]any
	if json.Unmarshal(b, &out) != nil {
		return nil
	}
	return out
}

func serversHooks() *rt.ServersHooks {
	return &rt.ServersHooks{List: serversList, Current: serversCurrent}
}
