package player

// 安卓 mpv 的默认字幕字体。
//
// 那颗 libmpv(0.36)没有 fontconfig:libass 只认 sub-fonts-dir 里读进来的字体,按**族名**找。
// mpv 默认 sub-font=sans-serif,而 /system/fonts 里没有一个字体叫这个名字 ——
// SRT 的样式字体就是它,ASS 指名的字体不在系统里时也回落到它,找不到就一个字都不画。
// 所以挑一个系统里真有的中日韩字体,把**它文件里写的族名**交给 sub-font(猜名字会猜错)。

import (
	"encoding/binary"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf16"
)

// cjkFontFiles 按优先级:AOSP 的 Noto CJK → 老机型的 Droid 回退 → 厂商自带。
var cjkFontFiles = []string{
	"NotoSansCJK-Regular.ttc", "NotoSansSC-Regular.otf", "NotoSansHans-Regular.otf",
	"DroidSansFallbackFull.ttf", "DroidSansFallback.ttf", "MiSansVF.ttf",
}

// systemSubFont 在 dir 里挑一个中日韩字体,返回族名。一个都没有回空串(那就不设,维持 mpv 默认)。
func systemSubFont(dir string) string {
	for _, f := range cjkFontFiles {
		fh, err := os.Open(filepath.Join(dir, f))
		if err != nil {
			continue
		}
		// 按偏移读,不整个读进来:Noto CJK 一个文件就 20MB,而要的只是几百字节
		name := preferSC(fontFamilies(fh))
		fh.Close()
		if name != "" {
			return name
		}
	}
	return ""
}

// preferSC 集合字体(.ttc)一个文件里有 JP/KR/SC/TC 好几张脸,中文用户要 SC 那张。
func preferSC(names []string) string {
	for _, n := range names {
		if strings.HasSuffix(n, " SC") {
			return n
		}
	}
	if len(names) > 0 {
		return names[0]
	}
	return ""
}

// at 读 [off, off+n)。越界或读不满回 nil —— 系统字体目录里什么文件都可能有,坏数据不许 panic。
func at(r io.ReaderAt, off int64, n int) []byte {
	b := make([]byte, n)
	if _, err := r.ReadAt(b, off); err != nil {
		return nil
	}
	return b
}

// fontFamilies 读 TTF/OTF/TTC 每张脸 name 表里的族名(nameID 1,Windows 平台英文那条)。
func fontFamilies(r io.ReaderAt) []string {
	be := binary.BigEndian
	offsets := []int64{0}
	if h := at(r, 0, 12); h != nil && string(h[:4]) == "ttcf" {
		n := be.Uint32(h[8:])
		offsets = nil
		for i := int64(0); i < int64(n) && i < 64; i++ {
			if b := at(r, 12+4*i, 4); b != nil {
				offsets = append(offsets, int64(be.Uint32(b)))
			}
		}
	}
	var out []string
	for _, off := range offsets {
		if name := familyAt(r, off); name != "" {
			out = append(out, name)
		}
	}
	return out
}

func familyAt(r io.ReaderAt, off int64) string {
	be := binary.BigEndian
	h := at(r, off, 12)
	if h == nil {
		return ""
	}
	for i := int64(0); i < int64(be.Uint16(h[4:])); i++ {
		rec := at(r, off+12+16*i, 16)
		if rec == nil {
			return ""
		}
		if string(rec[:4]) != "name" {
			continue
		}
		t := int64(be.Uint32(rec[8:]))
		nh := at(r, t, 6)
		if nh == nil {
			return ""
		}
		strOff := t + int64(be.Uint16(nh[4:]))
		for j := int64(0); j < int64(be.Uint16(nh[2:])); j++ {
			nr := at(r, t+6+12*j, 12)
			if nr == nil {
				return ""
			}
			if be.Uint16(nr) != 3 || be.Uint16(nr[4:]) != 0x409 || be.Uint16(nr[6:]) != 1 {
				continue
			}
			raw := at(r, strOff+int64(be.Uint16(nr[10:])), int(be.Uint16(nr[8:])))
			if raw == nil || len(raw)%2 != 0 {
				return ""
			}
			u := make([]uint16, len(raw)/2)
			for k := range u {
				u[k] = be.Uint16(raw[2*k:])
			}
			return string(utf16.Decode(u))
		}
	}
	return ""
}
