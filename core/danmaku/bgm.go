package danmaku

// 用 Bangumi 条目号(bgmid)换回作品的官方名字。
//
// ★ bgmid 不能直接拿去弹幕源查:弹幕源的 animeId 是它自己的主键,和 Bangumi 无关。
//   能借的是 Bangumi 那份**权威名字表**(原名 + 中文名)—— 媒体库刮的中文名
//   和弹幕源收录的日文名对不上时,单靠标题相似度那一路分数恒为 0。
//
// ☠ 名字解析失败**不能让搜索整条失败**:bgm.tv 打不通是常态,退回原关键词照样能搜。

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"sync"

	"linplayer/core/httpx"
)

// bgmAPIBase Bangumi 官方 API。变量而非常量:测试要把它指到本地假服务器。
var bgmAPIBase = "https://api.bgm.tv"

// SetBgmAPIBase 测试用,返回还原函数。
func SetBgmAPIBase(u string) func() {
	old := bgmAPIBase
	bgmAPIBase = u
	return func() { bgmAPIBase = old }
}

// bgmRefRe 从用户粘的东西里抠 subject id:
// `https://bgm.tv/subject/253/`、`bangumi.tv/subject/253`、`bgm:253`、`bgm253`。
//
// ★ **纯数字不算**。「253」既可能是条目号也可能是片名的一部分,
// 猜错了用户会看到一部完全不相干的作品,而且不知道为什么。
var bgmRefRe = regexp.MustCompile(`(?i)(?:bgm|bangumi)(?:\.tv|\.moe)?[/:]?(?:\s*subject/)?\s*(\d{1,8})`)

// BgmSubjectID 从一段文本里抠出 Bangumi 条目号。0 = 里面没有。
func BgmSubjectID(s string) int64 {
	m := bgmRefRe.FindStringSubmatch(strings.TrimSpace(s))
	if len(m) < 2 {
		return 0
	}
	var id int64
	if _, err := fmt.Sscan(m[1], &id); err != nil {
		return 0
	}
	return id
}

var (
	bgmMu    sync.Mutex
	bgmNames = map[int64][]string{}
)

// BgmTitles 取条目的名字表:原名在前、中文名在后。
//
// ★ 原名排前面是因为弹幕源(弹弹Play 系)收录的基本是日文原名 ——
// 它当主标题去搜命中率最高,中文名留作 AltTitle 兜底。
//
// 进程内缓存:同一部剧连看十集会问十次,而条目名字不会在这十集之间变。
func BgmTitles(ctx context.Context, id int64) []string {
	if id <= 0 {
		return nil
	}
	bgmMu.Lock()
	if v, ok := bgmNames[id]; ok {
		bgmMu.Unlock()
		return v
	}
	bgmMu.Unlock()

	names := fetchBgmTitles(ctx, id)
	bgmMu.Lock()
	bgmNames[id] = names
	bgmMu.Unlock()
	return names
}

func fetchBgmTitles(ctx context.Context, id int64) []string {
	u := fmt.Sprintf("%s/v0/subjects/%d", bgmAPIBase, id)
	b, code, err := httpx.GetJSON(ctx, httpx.Client(), u, http.Header{})
	if err != nil || code != 200 {
		return nil
	}
	var j map[string]any
	if json.Unmarshal(b, &j) != nil {
		return nil
	}
	out := []string{}
	for _, k := range []string{"name", "name_cn"} {
		if s, _ := j[k].(string); strings.TrimSpace(s) != "" {
			out = append(out, strings.TrimSpace(s))
		}
	}
	return out
}

// withBgmTitles 把 bgmid 换来的名字并进匹配输入。
//
// 名字表非空时**主标题换成原名**,原来的标题降级成别名 —— 别名只影响
// TitleScore 取最大值那一路,而主标题还决定拿什么词去上游搜(见 matchOne ③)。
func withBgmTitles(ctx context.Context, in *MatchInput) *MatchInput {
	if in.BgmID == nil || *in.BgmID <= 0 {
		return in
	}
	names := BgmTitles(ctx, *in.BgmID)
	if len(names) == 0 {
		return in
	}
	cp := *in
	cp.Title = names[0]
	alts := append([]string{}, names[1:]...)
	alts = append(alts, in.Title)
	cp.AltTitles = mergeUnique(alts, in.AltTitles)
	return &cp
}
