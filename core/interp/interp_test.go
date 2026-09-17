package interp

import (
	"archive/zip"
	"os"
	"path/filepath"
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

func TestWhyNot(t *testing.T) {
	two := Spec{Algo: "drba", Multi: 2}
	three := Spec{Algo: "drba", Multi: 3}
	cases := []struct {
		name     string
		spec     Spec
		src, hz  float64
		wantFail bool
	}{
		{"24 帧补 2 倍上 60Hz", two, 23.976, 60, false},
		{"24 帧补 3 倍上 60Hz:72 帧显示不出来", three, 23.976, 60, true},
		{"24 帧补 3 倍上 75Hz", three, 23.976, 75, false},
		{"刷新率未知不判", three, 23.976, 0, false},
		{"片源未知不判", three, 0, 60, false},
		{"60 帧片源不补", two, 59.94, 240, true},
		{"30 帧补 2 倍上 60Hz 正好", two, 30, 60, false},
	}
	for _, c := range cases {
		if got := WhyNot(c.spec, c.src, c.hz) != ""; got != c.wantFail {
			t.Errorf("%s:判「不该开」=%v,期望 %v", c.name, got, c.wantFail)
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

func TestUnzip拒绝逃出目录的条目(t *testing.T) {
	dir := t.TempDir()
	for name, wantErr := range map[string]bool{"ok/a.txt": false, "../evil.txt": true} {
		zp := filepath.Join(dir, "p.zip")
		f, _ := os.Create(zp)
		zw := zip.NewWriter(f)
		w, _ := zw.Create(name)
		w.Write([]byte("x"))
		zw.Close()
		f.Close()
		out := filepath.Join(dir, "out")
		err := unzip(zp, out)
		if (err != nil) != wantErr {
			t.Errorf("%s:err=%v,期望报错=%v", name, err, wantErr)
		}
		if _, e := os.Stat(filepath.Join(dir, "evil.txt")); e == nil {
			t.Fatalf("%s 被写到了目录外面", name)
		}
	}
}
