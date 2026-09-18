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
	ud := "algo=rife;x=2;gpu=3;h=900;be=dml"
	if !strings.HasSuffix(got, ":user-data=%"+strconv.Itoa(len(ud))+"%"+ud) {
		t.Fatalf("user-data 不对:%s", got)
	}
}

// TensorRT 的引擎只有 1080p / 720p 两档,降到两档之间只会被补黑边补回去:
// 画质丢了、算力没省。所以 TRT 下 RIFE 2 倍不降(1080),4 倍降到 720 而不是 540。
func TestTRT下高度贴着引擎档(t *testing.T) {
	for m, want := range map[int]int{2: 1080, 3: 720, 4: 720} {
		s, _ := SpecOf("rife_" + strconv.Itoa(m))
		s.Backend = "trt"
		if ud := s.UserData(0); !strings.Contains(ud, ";h="+strconv.Itoa(want)+";") {
			t.Errorf("rife %d 倍 TRT:%s,要 h=%d", m, ud, want)
		}
	}
	// DirectML 没有引擎档,照旧按实测降
	if s, _ := SpecOf("rife_2"); !strings.Contains(s.UserData(0), ";h=900;") {
		t.Errorf("DirectML 下 RIFE 2 倍应当还是 900:%s", s.UserData(0))
	}
}

func TestParseNvidiaSMI(t *testing.T) {
	nv := parseNvidiaSMI("NVIDIA GeForce RTX 5060 Laptop GPU, 12.0, 610.62\r\n")
	if nv.Name != "NVIDIA GeForce RTX 5060 Laptop GPU" || nv.ComputeCap != "12.0" || nv.Driver != 610.62 {
		t.Fatalf("解析错:%+v", nv)
	}
	if parseNvidiaSMI("").Name != "" {
		t.Fatal("空输出(没 N 卡)应当解析成空")
	}
}

// 按架构选引擎资源;不支持的架构、太旧的驱动要说原因;没 N 卡什么都不说(A 卡界面上不提)。
func TestTRTOffer(t *testing.T) {
	arch, size, why := TRTOffer(Nvidia{Name: "RTX 5060", ComputeCap: "12.0", Driver: 610})
	if arch != "sm120" || size != trtDownloadSize() || why != "" {
		t.Fatalf("50 系选错:%q %d %q", arch, size, why)
	}
	if a, _, why := TRTOffer(Nvidia{Name: "GTX 1080", ComputeCap: "6.1", Driver: 610}); a != "" || why == "" {
		t.Fatal("Pascal 不该给,要说原因")
	}
	if a, _, why := TRTOffer(Nvidia{Name: "RTX 3060", ComputeCap: "8.6", Driver: 560}); a != "" || !strings.Contains(why, "驱动") {
		t.Fatalf("旧驱动要拦:%q", why)
	}
	if a, _, why := TRTOffer(Nvidia{}); a != "" || why != "" {
		t.Fatal("没 N 卡应当什么都不说")
	}
}
