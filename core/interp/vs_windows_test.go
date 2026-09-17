//go:build windows

package interp

import (
	"strconv"
	"strings"
	"testing"
)

func TestSpecOf认得每一档且不回落(t *testing.T) {
	for _, l := range Levels() {
		if l.ID == "off" {
			continue
		}
		s, err := SpecOf(l.ID)
		if err != nil || s.Multi != l.Multi || s.Height <= 0 {
			t.Errorf("%s → %+v, %v", l.ID, s, err)
		}
	}
	for _, bad := range []string{"", "off", "drba", "drba_5", "drba_x", "svp_2"} {
		if _, err := SpecOf(bad); err == nil {
			t.Errorf("%q 应当报错,不许回落到某一档", bad)
		}
	}
}

// 用户的数据目录可能带空格、逗号、冒号。mpv 的滤镜参数按 `:` `,` 切,
// 不做定长引用的话路径会被切成两半,滤镜创建失败。
func TestFilterString定长引用(t *testing.T) {
	p := `D:\新建 文件夹,1\a:b\linplayer-interp.vpy`
	got := FilterString(p, Spec{Algo: "rife", Multi: 2, Height: 900}, 3)
	want := "%" + strconv.Itoa(len(p)) + "%" + p
	if !strings.Contains(got, "file="+want+":") {
		t.Fatalf("路径没按字节数引用:%s", got)
	}
	ud := "algo=rife;x=2;gpu=3;h=900"
	if !strings.HasSuffix(got, ":user-data=%"+strconv.Itoa(len(ud))+"%"+ud) {
		t.Fatalf("user-data 不对:%s", got)
	}
}
