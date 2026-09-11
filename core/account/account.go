// Package account 是 `account.*` 命令:服务器账号的增删改、线路、活跃切换。
//
// 数据都在 core/config,这一层只负责「命令参数 → 配置操作 → 落盘 → 该同步的同步」。
//
// ★ 每一条改账号的路径,末尾都必须做同样三件事:
//
//	落盘(Save)                —— 漏了 = 重启回到改之前
//	刷图片白名单(localserve)   —— 漏了 = 那台服的封面全空 / 删了账号还能取图
//	刷自签名白名单             —— 漏了 = 勾了「允许自签名」却连不上,或取消了还在放行
//
// 所以它们收在 commit() 一处,而不是各写各的。**新增改账号的命令必须走 commit()。**
package account

import (
	"context"
	"fmt"
	"net/http"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/net/localserve"
	"linplayer/core/net/tlspolicy"
)

// Info 交给 UI 的账号视图。
//
// ★ **不含 token / password**:这份东西会进事件队列、进日志、进诊断包。
// Rust 版的 AccountInfo 同样不带 —— 别在移植时「顺手补全字段」。
type Info struct {
	Server     string `json:"server"`
	UserID     string `json:"user_id"`
	UserName   string `json:"user_name"`
	Name       string `json:"name"`
	Remark     string `json:"remark"`
	IconURL    string `json:"icon_url"`
	SourceKind string `json:"source_kind"`
	// LineURL 当前生效的线路地址(用户在设置页看到的就是它)
	LineURL          string              `json:"line_url"`
	Lines            []config.ServerLine `json:"lines"`
	ActiveLine       int                 `json:"active_line"`
	AllowInsecureTLS bool                `json:"allow_insecure_tls"`
	Active           bool                `json:"active"`
}

func infoOf(a config.Account, active bool) Info {
	str := func(p *string) string {
		if p == nil {
			return ""
		}
		return *p
	}
	lines := a.Lines
	if lines == nil {
		lines = []config.ServerLine{} // 空切片不是 nil:前端 .map() 拿到 null 会抛错
	}
	return Info{
		Server: a.Server, UserID: a.UserID, UserName: a.UserName,
		Name: a.DisplayName(), Remark: str(a.Remark), IconURL: str(a.IconURL),
		SourceKind: a.SourceKind(), LineURL: a.ActiveLineURL(),
		Lines: lines, ActiveLine: a.ActiveLine,
		AllowInsecureTLS: a.AllowInsecureTLS, Active: active,
	}
}

func listOf(c *config.AppConfig) []Info {
	act := c.ActiveAccount()
	out := make([]Info, 0, len(c.AccountList))
	for i := range c.AccountList {
		a := c.AccountList[i]
		out = append(out, infoOf(a, act != nil && act.Server == a.Server))
	}
	return out
}

// commit 落盘 + 刷两张白名单。**每条改账号的命令都要走这里。**
func commit(c *config.AppConfig) error {
	if err := c.Save(); err != nil {
		return bus.NewErr(bus.EInternal, "配置保存失败: %v", err)
	}
	syncImageAllowlist(c)
	// ★ 自签名白名单和图片白名单一样,**每条改账号的路径末尾都要刷** ——
	//   漏一条的表现是「我勾了允许自签名,重启之后才生效」。
	tlspolicy.Set(c.InsecureHosts())
	return nil
}

// SyncImageAllowlist 由 lp_init 在配置加载完之后调一次。
//
// ★★ 没有这一步的话:**冷启动(账号早就存在配置里)时白名单是空的,一张封面都没有**,
// 而命令全都正常。只有「这次会话里登录过 / 改过账号」才会被登记 ——
// 那正好是开发时最常走的路径,所以这个洞在开发机上极难发现。
func SyncImageAllowlist() {
	c := config.Current()
	syncImageAllowlist(c)
	// ★ 自签名白名单同理:冷启动时账号早就在配置里,不同步的话勾了也不生效
	tlspolicy.Set(c.InsecureHosts())
}

// syncImageAllowlist 让图片通道的白名单和账号表**完全一致**。
//
// ★ 全量重建而不是增量加:删账号 / 换线路时只加不删的话,
// 那个 origin 会永久留在白名单里 —— 就是一个长期存在的 SSRF 出口。
func syncImageAllowlist(c *config.AppConfig) {
	s := localserve.Default()
	if s == nil {
		return
	}
	s.ReplaceAllowlist(func(add func(string, http.Header)) {
		for _, a := range c.AccountList {
			h := http.Header{}
			if a.Token != "" {
				h.Set("X-Emby-Token", a.Token)
			}
			// 服务器本体 + 每一条线路都要放行:换条线路看封面是很常见的操作,
			// 只放行 server 的话切线之后图全空。
			add(a.Server, h)
			for _, l := range a.Lines {
				add(l.URL, h)
			}
		}
	})
}

var client *emby.Client

// RegisterCommands 由 lp_init 调用。
func RegisterCommands(version string) {
	registerMiscCommands()
	registerBatchCommands()
	registerIconCommands()
	client = emby.NewClient(version)
	registerProbeCommands()

	bus.Register("account.listAccounts", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return listOf(config.Current()), nil
	})

	bus.Register("account.setActiveServer", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		c := config.Current()
		id := str(a, "server_id")
		for i := range c.AccountList {
			if c.AccountList[i].Server == id {
				c.SetActive(i)
				return listOf(c), commit(c)
			}
		}
		return nil, bus.NewErr(bus.ENotFound, "没有这个服务器: %s", id)
	})

	bus.Register("account.removeAccount", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		c := config.Current()
		id := str(a, "server_id")
		if !c.Remove(id) {
			return nil, bus.NewErr(bus.ENotFound, "没有这个服务器: %s", id)
		}
		return listOf(c), commit(c)
	})

	bus.Register("account.reorderAccounts", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		c := config.Current()
		if err := c.Reorder(intArg(a, "from", -1), intArg(a, "to", -1)); err != nil {
			return nil, bus.NewErr(bus.EInvalid, "%v", err)
		}
		return listOf(c), commit(c)
	})

	// updateAccount 改的全是**用户侧编辑**(名称 / 备注 / 图标 / TLS / 密码)。
	// ★ 每个字段都是「没传就不动」,不是「没传就清空」——
	//   前端各页面传的字段子集不同,清空语义会让「只改个备注」把图标一起抹掉。
	bus.Register("account.updateAccount", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		c := config.Current()
		acc := c.Find(str(a, "server_id"))
		if acc == nil {
			return nil, bus.NewErr(bus.ENotFound, "没有这个服务器: %s", str(a, "server_id"))
		}
		if v, ok := a["name"].(string); ok {
			acc.Name = v
		}
		if v, ok := a["remark"].(string); ok {
			acc.Remark = &v
		}
		if v, ok := a["icon_url"].(string); ok {
			acc.IconURL = &v
		}
		if v, ok := a["password"].(string); ok {
			acc.Password = &v
		}
		if v, ok := a["allow_insecure_tls"].(bool); ok {
			acc.AllowInsecureTLS = v
		}
		return listOf(c), commit(c)
	})

	bus.Register("account.setActiveLine", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		c := config.Current()
		acc := c.Find(str(a, "server_id"))
		if acc == nil {
			return nil, bus.NewErr(bus.ENotFound, "没有这个服务器")
		}
		i := intArg(a, "index", -1)
		if i < 0 || i >= len(acc.Lines) {
			return nil, bus.NewErr(bus.EInvalid, "线路下标越界: %d(共 %d 条)", i, len(acc.Lines))
		}
		acc.ActiveLine = i
		return listOf(c), commit(c)
	})

	// setLines 整表替换(设置页里手工编辑线路表)。
	// ★ 替换后按 url 找回生效线路 —— 按下标的话,用户删掉列表中间一条,
	//   正在用的线路会**静默地**换成另一条。
	bus.Register("account.setLines", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		c := config.Current()
		acc := c.Find(str(a, "server_id"))
		if acc == nil {
			return nil, bus.NewErr(bus.ENotFound, "没有这个服务器")
		}
		lines, err := parseLines(a["lines"])
		if err != nil {
			return nil, err
		}
		before := config.NormLineURL(acc.DirectLineURL())
		acc.Lines = lines
		acc.ActiveLine = 0
		for i, l := range lines {
			if config.NormLineURL(l.URL) == before {
				acc.ActiveLine = i
				break
			}
		}
		return listOf(c), commit(c)
	})

	// syncLines 把服主下发的线路并进来。**只增不删**,见 config.MergeLines。
	bus.Register("account.syncLines", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		c := config.Current()
		acc := c.Find(str(a, "server_id"))
		if acc == nil {
			return nil, bus.NewErr(bus.ENotFound, "没有这个服务器")
		}
		s := &emby.Session{Server: acc.Server, Token: acc.Token, UserID: acc.UserID, DeviceID: c.DeviceID}
		remote, err := client.ExtDomains(ctx, s)
		if err != nil {
			// 只有 401 会走到这:token 失效,用户能采取行动,该报出来
			return nil, &bus.Err{Code: bus.EAuth, Msg: err.Error()}
		}
		rl := make([]config.RemoteLine, 0, len(remote))
		for _, d := range remote {
			rl = append(rl, config.RemoteLine{Name: d.Name, URL: d.URL})
		}
		added := config.MergeLines(acc, rl)
		if err := commit(c); err != nil {
			return nil, err
		}
		// ★ added=0 是**正常结果**,不是失败:绝大多数服务器没部署那个端点。
		//   UI 据此说「这台服务器没提供线路表」,而不是弹红字。
		return map[string]any{"added": added, "accounts": listOf(c)}, nil
	})

	/* testConnection 登录**前**用的,不走会话。
	   ★ 给了用户名就**真登一次**,不是只探一下 `/System/Info/Public`。
	     只探公开信息的话,地址写对而密码写错的服务器照样报「连上了」——
	     用户点「登录」才发现不行,而那时候的报错和「测试通过」自相矛盾。
	     登进去立刻登出,不落库、不切活跃会话(account_test.go 钉着这条)。 */
	bus.Register("account.testConnection", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		server := str(a, "server")
		if server == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 server")
		}
		info, err := client.ProbeServer(ctx, server)
		if err != nil {
			return nil, loginErr(err, "探测服务器")
		}
		user := str(a, "username")
		if user == "" {
			return TestResult{Name: info.Name, Version: info.Version, ID: info.ID}, nil
		}
		s, lr, err := client.Login(ctx, server, user, str(a, "password"), deviceIDOf(a))
		if err != nil {
			return nil, loginErr(err, "登录")
		}
		_ = client.Logout(ctx, s) // 测试用的会话不留在服务器的设备列表里
		return TestResult{
			Name: info.Name, Version: info.Version, ID: info.ID,
			UserName: lr.UserName, LoggedIn: true,
		}, nil
	})
}

// TestResult 「测试连接」的结果。
//
// ★ 不是直接回 emby.ServerInfo:真登一次之后多了「登进去的是谁」这件事,
// 而那正是用户要确认的 —— 界面上写「已用 xxx 登录成功」比「连上了」有用得多。
type TestResult struct {
	Name    string `json:"name"`
	Version string `json:"version"`
	ID      string `json:"id"`
	// UserName 真登进去的那个用户名。空串 = 没给账号,只探了地址通不通。
	UserName string `json:"user_name"`
	LoggedIn bool   `json:"logged_in"`
}

func deviceIDOf(a map[string]any) string {
	if d := str(a, "device_id"); d != "" {
		return d
	}
	return config.Current().DeviceID
}

// loginErr 把出网错误翻成**用户照着能做下一步**的一句话。
//
// ★ 错误码照旧由状态码定(401 不可重试,否则界面会劝人一直点)。
// 人话放在 Msg 里,真实原因降级进 Detail —— 两端都直接把 Msg 弹出来给用户看。
func loginErr(err error, what string) *bus.Err {
	switch code := emby.StatusOf(err); {
	case code == 401 || code == 403:
		return &bus.Err{Code: bus.EAuth, Detail: err.Error(),
			Msg: "账号或密码不对,请重新输入账号密码。"}
	case code == 404:
		return &bus.Err{Code: bus.ENotFound, Detail: err.Error(),
			Msg: "这个地址上没有 Emby 接口,检查一下服务器地址(常见是少了 /emby 或端口写错)。"}
	case code >= 500:
		return &bus.Err{Code: bus.EInternal, Detail: err.Error(), Retryable: true,
			Msg: fmt.Sprintf("服务器自己出错了(HTTP %d),过一会儿再试。", code)}
	case code != 0:
		return &bus.Err{Code: bus.EUpstream, Detail: err.Error(),
			Msg: fmt.Sprintf("服务器拒绝了这次%s(HTTP %d)。", what, code)}
	}
	/* 连不上这一类要把真实原因带出来:自签名证书、DNS 解析不了、端口不通
	   是三件完全不同的事,而它们都长成「连不上」。 */
	return &bus.Err{Code: bus.ENetwork, Retryable: true, Detail: err.Error(),
		Msg: "连不上这台服务器:" + err.Error()}
}

func parseLines(v any) ([]config.ServerLine, error) {
	arr, ok := v.([]any)
	if !ok {
		return nil, bus.NewErr(bus.EInvalid, "lines 不是数组")
	}
	out := make([]config.ServerLine, 0, len(arr))
	for _, it := range arr {
		m, ok := it.(map[string]any)
		if !ok {
			continue
		}
		u := config.NormLineURL(str(m, "url"))
		if u == "" {
			continue // 空 url 的线路留着只会在切过去的时候连不上
		}
		l := config.ServerLine{ID: str(m, "id"), Name: str(m, "name"), URL: str(m, "url")}
		if l.ID == "" {
			l.ID = u // id 用 url:序号会随表变动,url 是这条线路的天然身份
		}
		if r, ok := m["remark"].(string); ok {
			l.Remark = &r
		}
		out = append(out, l)
	}
	return out, nil
}

func str(a map[string]any, k string) string {
	v, _ := a[k].(string)
	return v
}

func intArg(a map[string]any, k string, def int) int {
	if v, ok := a[k].(float64); ok {
		return int(v)
	}
	return def
}
