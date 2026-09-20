package player

// 字幕全文与字幕缓存(SPEC 9.7,D164 D188 D487)。
//
// 为什么这件事只能宿主做:字幕轨的地址带着 api_key(内封字幕更要先让服务端导出),
// 而 D11 说插件拿不到带凭据的地址。所以插件给轨道号,宿主把**文本**交回去。

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/httpx"
	"linplayer/core/paths"
)

func str(a map[string]any, k string) string {
	v, _ := a[k].(string)
	return strings.TrimSpace(v)
}

// subCacheDir 插件挂上来的字幕落在这儿。放 cache 下:清缓存可以直接删,
// 丢了最多是重翻一次,不是丢数据。
func subCacheDir() string { return filepath.Join(paths.CacheDir(), "subs") }

// cacheSubtitle 把字幕文本落盘,返回文件路径。
//
// 文件名按内容哈希:同一份字幕重复挂不会堆出一堆文件,
// 而 mpv 认的是路径 —— 名字一样它会复用已加载的那一轨。
func cacheSubtitle(text, format string) (string, error) {
	f := strings.ToLower(strings.TrimSpace(format))
	if f != "ass" && f != "vtt" {
		f = "srt"
	}
	if err := os.MkdirAll(subCacheDir(), 0o755); err != nil {
		return "", fmt.Errorf("建字幕缓存目录失败: %w", err)
	}
	sum := sha1.Sum([]byte(text))
	p := filepath.Join(subCacheDir(), hex.EncodeToString(sum[:10])+"."+f)
	if _, err := os.Stat(p); err == nil {
		return p, nil
	}
	if err := os.WriteFile(p, []byte(text), 0o644); err != nil {
		return "", fmt.Errorf("写字幕缓存失败: %w", err)
	}
	return p, nil
}

// subIDOf 挂完之后当前选中的字幕轨号。挂上就选中是常态,所以直接读 sid。
func subIDOf(string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(Prop("sid")))
	return n
}

/*
subtitleText 取一条字幕轨的全文。

三种来源,按 mpv 那边的轨道类型分:
  - 外挂轨(`external-filename` 有值):直接读那个文件 / 拉那个地址;
  - 内封轨:mpv 没有「把整轨导出成文本」的属性,只能让服务端导出 ——
    走 Emby 的字幕导出地址(宿主带 token);
  - 取不到就明说,不返回空文本。

☠ 空文本**不能**当成「这一轨没字幕」交出去:字幕翻译插件收到空文本会
  安安静静地产出一份空字幕并挂上,用户看到的是「翻译完了,但一句都没有」。
*/
func subtitleText(ctx context.Context, trackID int) (any, error) {
	if trackID < 0 {
		n, err := strconv.Atoi(strings.TrimSpace(Prop("sid")))
		if err != nil || n <= 0 {
			return nil, bus.NewErr(bus.ENotFound, "当前没有选中的字幕轨")
		}
		trackID = n
	}
	src := trackSource(trackID)
	if src == "" {
		return nil, bus.NewErr(bus.ENotFound, "第 %d 轨没有可导出的字幕(内封轨要服务端导出,这一版还没接)", trackID)
	}
	text, err := readSubtitleSource(ctx, src)
	if err != nil {
		return nil, bus.NewErr(bus.EUpstream, "取字幕全文失败: %v", err)
	}
	if strings.TrimSpace(text) == "" {
		return nil, bus.NewErr(bus.ENotFound, "第 %d 轨取回来是空的", trackID)
	}
	return map[string]any{"format": formatOf(src, text), "text": text}, nil
}

// trackSource 这一轨的外挂来源(文件路径或地址)。内封轨返回空串。
func trackSource(id int) string {
	n, _ := strconv.Atoi(strings.TrimSpace(Prop("track-list/count")))
	for i := 0; i < n; i++ {
		p := "track-list/" + strconv.Itoa(i) + "/"
		if strings.TrimSpace(Prop(p+"type")) != "sub" {
			continue
		}
		if strings.TrimSpace(Prop(p+"id")) != strconv.Itoa(id) {
			continue
		}
		return strings.TrimSpace(Prop(p + "external-filename"))
	}
	return ""
}

func readSubtitleSource(ctx context.Context, src string) (string, error) {
	if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
		b, _, err := httpx.GetJSON(ctx, httpx.Client(), src, nil)
		if err != nil {
			return "", err
		}
		return string(b), nil
	}
	b, err := os.ReadFile(src)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// GetJSON 名字叫 JSON,实际只是「GET 回字节」—— 字幕不是 JSON,但这条路的
// 超时、UA、代理规矩和别处一致,自己再 new 一个 http.Client 反而会绕开它们。

// formatOf 先按扩展名判,判不出来再看内容 —— 反代给的地址常常没有扩展名。
func formatOf(src, text string) string {
	switch strings.ToLower(filepath.Ext(strings.SplitN(src, "?", 2)[0])) {
	case ".ass", ".ssa":
		return "ass"
	case ".vtt":
		return "vtt"
	case ".srt":
		return "srt"
	}
	head := text
	if len(head) > 200 {
		head = head[:200]
	}
	switch {
	case strings.Contains(head, "[Script Info]"):
		return "ass"
	case strings.HasPrefix(strings.TrimSpace(head), "WEBVTT"):
		return "vtt"
	}
	return "srt"
}
