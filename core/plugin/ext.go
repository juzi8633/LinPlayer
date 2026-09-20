package plugin

// `ext` 命名空间的宿主侧(SPEC 18.5,D380~D382)与 `player.transcribe`(D488)。
//
// 六个组件三种命运:
//   · interp / ffmpeg / whisper 权重 —— 核心层自己下(已有下载代码,这里只是换个入口);
//   · jar-runtime / python —— 壳里的事,能力由壳报上来,下载也在壳那边;
//   · geckoview —— D560 已经否掉,这一版不做。报 false 而不是装作能装。
//
// ☠ `status` 会 spawn 子进程探测(`runsOk`),不能在每次读 `app.capabilities` 时跑一遍:
//   探测结果在 `core/transcribe` 里按 exe 名缓存,这里不再加第二层缓存 ——
//   加了之后「用户刚装完 ffmpeg」要清两处。

import (
	"context"
	"fmt"

	"linplayer/core/interp"
	"linplayer/core/plugin/rt"
	"linplayer/core/transcribe"
)

func extHooks() *rt.ExtHooks {
	return &rt.ExtHooks{Status: extStatus, Ensure: extEnsure}
}

// defaultWhisperModel 没指定档位时用哪一档。Base 是「快速,日常够用」那一档。
const defaultWhisperModel = transcribe.Base

// installedWhisperModel 已经下好的那一档,一个都没有时回 Base。
func installedWhisperModel() transcribe.Model {
	for _, m := range transcribe.Models() {
		if transcribe.Downloaded(m) {
			return m
		}
	}
	return defaultWhisperModel
}

func extStatus(component string) (rt.ExtStatus, error) {
	switch component {
	case "interp":
		return rt.ExtStatus{Installed: interp.Installed(), Version: interp.RuntimeVersion, Size: interp.RuntimeSize}, nil

	case "ffmpeg":
		if transcribe.ResolveFFmpeg("") != "" {
			return rt.ExtStatus{Installed: true}, nil
		}
		return rt.ExtStatus{Size: ffmpegApproxSize}, nil

	case "whisper":
		// 两件东西缺一不可:可执行文件 + 至少一档权重。
		// 只看权重的话「下完 142MB 还是不能用」,只看 exe 的话一样。
		m := installedWhisperModel()
		if transcribe.ResolveWhisper("") != "" && transcribe.Downloaded(m) {
			return rt.ExtStatus{Installed: true, Version: string(m)}, nil
		}
		return rt.ExtStatus{Size: m.ApproxSize()}, nil

	case "jar-runtime":
		return rt.ExtStatus{Installed: rt.Caps().SpiderJar}, nil
	case "python":
		return rt.ExtStatus{Installed: rt.Caps().SpiderPy}, nil
	case "geckoview":
		return rt.ExtStatus{}, nil
	}
	return rt.ExtStatus{}, fmt.Errorf("没有这个扩展组件:%s", component)
}

// ffmpegApproxSize 官方 essentials 构建的下载体积,给确认框说「要下多少」。
// 上游不给 Content-Length 以外的清单,这个数是量出来的近似值。
const ffmpegApproxSize = 30 * 1024 * 1024

func extEnsure(component string) error {
	ctx := context.Background()
	switch component {
	case "interp":
		return interp.Install(ctx, nil)

	case "ffmpeg":
		_, err := transcribe.DownloadFFmpeg(ctx, nil)
		return err

	case "whisper":
		// 只下权重。whisper-cli 本身官方不发布可直接下载的构建,
		// 报 unsupported 而不是下一半再失败 —— 那样用户会以为是网断了。
		if transcribe.ResolveWhisper("") == "" {
			return &rt.Error{Kind: rt.KindUnsupported,
				Message: "找不到 whisper-cli,应用内下不了:把它放进 PATH 或应用的 bin 目录"}
		}
		_, err := transcribe.Download(ctx, installedWhisperModel(), "", nil)
		return err

	case "jar-runtime", "python":
		return &rt.Error{Kind: rt.KindUnsupported,
			Message: "「" + component + "」在设置的「扩展组件」页里装,插件装不了"}

	case "geckoview":
		return &rt.Error{Kind: rt.KindUnsupported, Message: "这一版不做 GeckoView(D560)"}
	}
	return fmt.Errorf("没有这个扩展组件:%s", component)
}

/*
transcribeCurrent 转写**正在播的那一条**(D488)。

☠ 地址取自 mpv 的 `path`,不是让插件传:带 token 的取流地址只有宿主拿得到(D11)。
  开了预取代理时 `path` 是本地那一条,ffmpeg 照样读得了,而且不用再带鉴权头。
*/
func transcribeCurrent(opts map[string]any, onProgress func(float64)) (any, error) {
	url, _ := mpvProp("path").(string)
	if url == "" {
		return nil, fmt.Errorf("现在没有在播的东西,没法转写")
	}
	model := installedWhisperModel()
	if s, _ := opts["model"].(string); s != "" {
		m, err := transcribe.ModelOf(s)
		if err != nil {
			return nil, err
		}
		model = m
	}
	lang, _ := opts["lang"].(string)
	text, err := transcribe.Transcribe(context.Background(), transcribe.Options{
		URL: url, Headers: mpvHeaders(), Lang: lang, Model: model,
	}, onProgress)
	if err != nil {
		return nil, err
	}
	return map[string]any{"format": "srt", "text": text}, nil
}

func mpvProp(name string) any {
	m, _ := invokeOr("player.mpvGet", map[string]any{"name": name}).(map[string]any)
	return m["value"]
}

/*
mpvHeaders 取流要带的请求头。

mpv 把它们存成 `字段: 值` 的一串。取不到就算了 —— 预取代理那条路本来就不带头,
而直连 Emby 的地址把 token 带在查询串里。
*/
func mpvHeaders() map[string]string {
	out := map[string]string{}
	switch v := mpvProp("http-header-fields").(type) {
	case string:
		addHeaderLine(out, v)
	case []any:
		for _, it := range v {
			if s, ok := it.(string); ok {
				addHeaderLine(out, s)
			}
		}
	}
	return out
}

func addHeaderLine(dst map[string]string, line string) {
	for i := 0; i < len(line); i++ {
		if line[i] == ':' {
			k, v := line[:i], line[i+1:]
			for len(v) > 0 && v[0] == ' ' {
				v = v[1:]
			}
			if k != "" && v != "" {
				dst[k] = v
			}
			return
		}
	}
}
