package danmaku

// 集号接力:同一部剧看下一集时,直接把上一集的 episodeId 加一,不再搜一轮。
//
// ☠ 连看一部 24 集的番,旧路径要跑 24 轮三路召回 —— 每轮三次上游请求 × 每个源。
//   官方源那边是**有配额的**(见 dandan-quota-drain 那笔账),而这 24 轮问的
//   其实是同一部作品。
//
// ★ 判据不是「算得出来」,是「**算出来的那个 id 真取到了弹幕**」:
//   接力点写死在内存里,而上游的集表可能中间插了 SP、可能整部重编号。
//   取回空表就当没猜过,老老实实再搜一遍。

import (
	"context"
	"strconv"
	"strings"
	"sync"
)

// relayMaxGap 接力允许跨多少集。
//
// 跨太远说明用户是跳着看的,而中间插过 SP 的作品每跳一集就多错一位 ——
// 与其猜一个越跳越偏的 id,不如老实搜。
const relayMaxGap = 26

type relayPoint struct {
	sourceID  string
	animeID   string
	title     string
	episodeID string
	episodeNo int64
}

var (
	relayMu  sync.Mutex
	relayTab = map[string]relayPoint{}
)

// NextEpisodeID 把 episodeId 往后推 delta 集。
//
// ★ 只认**纯数字** id:弹弹Play 系的 episodeId 是 `animeId` 拼四位集号,连续可加;
// 自建源如果发的是别的形状(uuid、带前缀),加一就是一个不存在的 id ——
// 那时候宁可不猜。
func NextEpisodeID(base string, delta int64) (string, bool) {
	base = strings.TrimSpace(base)
	if base == "" || delta == 0 || delta > relayMaxGap || delta < -relayMaxGap {
		return "", false
	}
	n, err := strconv.ParseInt(base, 10, 64)
	if err != nil || n <= 0 {
		return "", false
	}
	next := n + delta
	if next <= 0 {
		return "", false
	}
	return strconv.FormatInt(next, 10), true
}

// relayKeyOf 一部作品的接力键。bgmid 优先 —— 它是稳定主键,标题会随刮削源变。
func relayKeyOf(in *MatchInput) string {
	if in.BgmID != nil && *in.BgmID > 0 {
		return "bgm:" + strconv.FormatInt(*in.BgmID, 10)
	}
	name := strings.ToLower(strings.TrimSpace(CoreName(in.Title)))
	if name == "" {
		return ""
	}
	season := int64(0)
	if in.SeasonNo != nil {
		season = *in.SeasonNo
	}
	return name + "|s" + strconv.FormatInt(season, 10)
}

// relayRemember 记下这一集用的是哪个源的哪个 id。
func relayRemember(in *MatchInput, c *MatchCandidate) {
	key := relayKeyOf(in)
	if key == "" || in.EpisodeNo == nil {
		return
	}
	if _, ok := NextEpisodeID(c.EpisodeID, 1); !ok {
		return // 这个源的 id 加不了,记了也没用
	}
	relayMu.Lock()
	relayTab[key] = relayPoint{
		sourceID: c.SourceID, animeID: c.AnimeID, title: c.AnimeTitle,
		episodeID: c.EpisodeID, episodeNo: *in.EpisodeNo,
	}
	relayMu.Unlock()
}

// relayGuess 猜这一集的 episodeId。第二个返回值 = 有没有猜出来。
func relayGuess(cfgs []SourceConfig, in *MatchInput) (MatchCandidate, bool) {
	key := relayKeyOf(in)
	if key == "" || in.EpisodeNo == nil {
		return MatchCandidate{}, false
	}
	relayMu.Lock()
	p, ok := relayTab[key]
	relayMu.Unlock()
	if !ok {
		return MatchCandidate{}, false
	}
	cfg := findSourceIn(cfgs, p.sourceID)
	if cfg == nil {
		return MatchCandidate{}, false // 那个源被删了 / 这次不参与
	}
	next, ok := NextEpisodeID(p.episodeID, *in.EpisodeNo-p.episodeNo)
	if !ok {
		return MatchCandidate{}, false
	}
	return MatchCandidate{
		SourceID: cfg.ID, SourceName: cfg.Name,
		AnimeID: p.animeID, AnimeTitle: p.title,
		EpisodeID: next, Score: 1.6,
	}, true
}

// relayLoad 走接力这条路取弹幕。取到空表 = 猜错了,返回 false 让调用方回去搜。
func relayLoad(ctx context.Context, cfgs []SourceConfig, in *MatchInput, chConvert int) ([]Comment, bool) {
	c, ok := relayGuess(cfgs, in)
	if !ok {
		return nil, false
	}
	cfg := findSourceIn(cfgs, c.SourceID)
	if cfg == nil {
		return nil, false
	}
	items, err := getCommentsCached(ctx, cfg, c.EpisodeID, chConvert)
	if err != nil || len(items) == 0 {
		return nil, false
	}
	relayRemember(in, &c)
	return items, true
}

// relayReset 测试用:清空接力表。
func relayReset() {
	relayMu.Lock()
	relayTab = map[string]relayPoint{}
	relayMu.Unlock()
}
