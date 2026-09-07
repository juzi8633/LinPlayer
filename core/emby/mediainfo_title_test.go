package emby

import (
	"encoding/json"
	"testing"
)

// 字幕轨要透出**轨道真名**,不是服务器拼的「语言 + 格式」。
//
// ☠ Emby 的 `DisplayTitle` 长得像名字(「Chinese - PGS」)但不是名字:
// 压制组写的「简体中文特效」在 `Title` 里。上一版只映射 DisplayTitle,
// 于是三端的字幕列表整张表都是格式标签,谁是谁分不出来(用户 2026-09-07 第二次报)。
func Test字幕轨透出的是轨道名不是格式串(t *testing.T) {
	const raw = `{"Id":"v1","Name":"版本","MediaStreams":[
		{"Type":"Subtitle","Codec":"ass","Title":"简体中文特效","DisplayTitle":"Chinese - ASS","Language":"chi"},
		{"Type":"Subtitle","Codec":"pgs","DisplayTitle":"English - PGS","Language":"eng"}]}`
	var m rawMediaSource
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatal(err)
	}
	v := versionFrom(m)
	if len(v.Streams) != 2 {
		t.Fatalf("要 2 条字幕轨,实得 %d", len(v.Streams))
	}
	if v.Streams[0].Title == nil || *v.Streams[0].Title != "简体中文特效" {
		t.Fatalf("第一条要透出 Title,实得 %v", deref(v.Streams[0].Title))
	}
	// 没有 Title 的那条要留空,**不许拿 DisplayTitle 冒充** —— 由 UI 自己决定怎么回落
	if v.Streams[1].Title != nil {
		t.Fatalf("没有 Title 的轨该是 nil,实得 %v", *v.Streams[1].Title)
	}
	if v.Streams[1].DisplayTitle == nil || *v.Streams[1].DisplayTitle != "English - PGS" {
		t.Fatalf("DisplayTitle 仍然要在,实得 %v", deref(v.Streams[1].DisplayTitle))
	}
}
