package player

// prefs.applyPrefs —— 把选轨偏好应用到**当前正在播的这个文件**。
//
// ★★ 它和 `prefs.setPrefs` 是两件事:后者只是把偏好存起来,而这条是「现在就按偏好
// 重选一遍音轨字幕」。用户在播放中改了字幕语言,不调这条的话要退出重进才生效。
//
// ★★ 判据是 **mpv 真的切了轨**,不是「我们算出了一个 id」——
// 「设了没反应」那一族 bug 全长在这中间:算对了但没设下去,或者设下去了但 mpv 拒了。

import (
	"context"
	"strconv"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/media"
)

// registerApplyPrefs 注册 prefs.applyPrefs。
//
// ★ 放在 player 包而不是 prefs 包:它要读 mpv 的 track-list 并下 sid/aid ——
// 而 prefs 包不该知道 mpv 的存在。
func registerApplyPrefs() {
	bus.Register("prefs.applyPrefs", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		if !applyTrackPrefs() {
			return nil, bus.NewErr(bus.EInvalid, "现在没有正在播放的文件")
		}
		// ★★ 回读 mpv 真正生效的值,而不是回我们刚算的那个。
		//   两者不一致正是「设了没反应」的现场 —— 不回读就永远看不见。
		return map[string]any{
			"audio": Prop("aid"),
			"sub":   Prop("sid"),
		}, nil
	})
}

/*
applyTrackPrefs 按偏好选音轨字幕。没有轨道(没在播)回 false。

	★ 起播时由 onFileLoaded 调一次 —— 以前只有 prefs.applyPrefs 这条命令调,而它没有调用方,
	  于是 mpv 内核下「关闭字幕」「字幕语言」从来没生效过(2026-09-17 安卓报字幕问题时查出)。
	  详情页显式选过的轨由壳在这之后再设,会盖掉这里的结果,这是对的。
*/
func applyTrackPrefs() bool {
	tracks := parseTracks(Prop("track-list"))
	if len(tracks) == 0 {
		return false
	}
	sid, aid := chooseTracks(tracks, config.Current().PrefsOf())
	if sid != "" {
		setProp("sid", sid)
	}
	if aid != "" {
		setProp("aid", aid)
	}
	return true
}

// chooseTracks 算该设成哪条。空串 = 不动它。拆出来是为了不起 mpv 也能测。
func chooseTracks(tracks []Track, p config.Prefs) (sid, aid string) {
	// ★ 字幕关掉时要显式设 `no`,不是「不管它」——
	//   不管的话上一片选中的字幕轨会一路留着(mpv 的属性是实例级的)。
	switch {
	case !p.SubEnabled:
		sid = "no"
	default:
		if sid = pickTrack(tracks, "sub", p.SubLang, p.SubRegex); sid == "" {
			sid = fallbackSub(tracks)
		}
	}
	return sid, pickTrack(tracks, "audio", p.AudioLang, p.AudioRegex)
}

/*
fallbackSub 字幕开着、偏好又挑不出来、mpv 自己也没选时,挑一条。

	mpv 的 sid=auto 只选带 default 标记的轨,而外挂字幕是 FILE_LOADED 之后才 sub-add 的,
	永远不会被它选上 —— 表现是「设置里开着字幕,画面上没有,得自己去面板点」。
	已经有选中的就不动:那是容器 default 标记或 mpv.conf 里 slang 的结果。
*/
func fallbackSub(tracks []Track) string {
	first := ""
	for _, t := range tracks {
		if t.Kind != "sub" {
			continue
		}
		if t.Selected {
			return ""
		}
		if first == "" {
			first = t.ID
		}
	}
	return first
}

// pickTrack 按语言 + 正则挑一条轨。返回 mpv 的轨道 id;挑不出返回空串。
//
// ★ 顺序是**正则优先于语言**:正则是用户明确写下的规则,语言只是个粗筛。
// 反过来的话用户写的正则永远轮不到生效 —— 那正是「设了正则没反应」的一种。
func pickTrack(tracks []Track, kind string, lang *string, pattern string) string {
	var (
		texts []string
		ids   []string
	)
	for _, t := range tracks {
		if t.Kind != kind {
			continue
		}
		// ★ 正则匹配的是「标题 + 语言」拼起来的串:用户写 `简体|中文` 时,
		//   有的源把它放在标题里,有的放在 lang 里
		texts = append(texts, strings.TrimSpace(t.Title+" "+t.Lang))
		ids = append(ids, t.ID)
	}
	if len(ids) == 0 {
		return ""
	}
	if strings.TrimSpace(pattern) != "" {
		if i := media.PickIndex(texts, pattern); i >= 0 {
			return ids[i]
		}
	}
	if lang != nil && strings.TrimSpace(*lang) != "" {
		want := strings.ToLower(strings.TrimSpace(*lang))
		for i, t := range tracks {
			_ = i
			if t.Kind == kind && strings.ToLower(t.Lang) == want {
				return t.ID
			}
		}
	}
	return ""
}

// PlayLocal 播放一个**已经下载好**的条目。
//
// ★ 索引说完成了不代表文件还在(用户可能手动删了 / 挪走了)——
// 放给 mpv 之前先确认,否则表现是「点了播放,黑屏,什么都不说」。
func PlayLocal(path string, resumeSecs float64) (map[string]any, error) {
	setShaderScope("")
	if err := loadWith(path, resumeSecs, nil, ""); err != nil {
		return nil, err
	}
	// ★ 本地文件**不走 Emby 上报**,也没有观看记录上下文 —— 清掉,
	//   否则会把本地文件的进度记到上一部 Emby 片上。
	currentMu.Lock()
	current = nil
	currentCtx = nil
	pendingSubs = nil
	currentMu.Unlock()
	return map[string]any{"resume_secs": resumeSecs}, nil
}

var _ = strconv.Itoa
