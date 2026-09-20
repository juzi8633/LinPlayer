package transcribe

import (
	"context"
	"os"
	"strings"
	"testing"

	"linplayer/core/paths"
)

func TestModelOf认不出必须报错(t *testing.T) {
	for _, k := range []string{"", "medium ", "Medium", "large-v3", "tiny2", "默认"} {
		if m, err := ModelOf(k); err == nil {
			t.Fatalf("%q 不是合法档位,却认成了 %q —— 静默回落会让用户点 medium 下到 base", k, m)
		}
	}
	for _, want := range Models() {
		got, err := ModelOf(string(want))
		if err != nil || got != want {
			t.Fatalf("ModelOf(%q) = %q, %v", want, got, err)
		}
	}
}

// 档位 → 文件名/体积是**存盘键**:改了会让已下载的模型「凭空消失」(下次开机又要下 1.5GB)。
func TestModel档位对应文件名与体积(t *testing.T) {
	want := map[Model][2]string{
		Tiny:   {"ggml-tiny.bin", "约 75 MB"},
		Base:   {"ggml-base.bin", "约 142 MB"},
		Medium: {"ggml-medium.bin", "约 1.5 GB"},
		Large:  {"ggml-large-v3.bin", "约 2.9 GB"},
	}
	if len(Models()) != len(want) {
		t.Fatalf("档位数量变了:%v", Models())
	}
	for m, w := range want {
		if got := m.FileName(); got != w[0] {
			t.Errorf("%s 的权重文件名 = %q,期望 %q", m, got, w[0])
		}
		if got := m.SizeLabel(); got != w[1] {
			t.Errorf("%s 的体积标签 = %q,期望 %q", m, got, w[1])
		}
	}
	// 下载地址必须落在各自的文件名上,镜像只换前缀。
	if u := Medium.DownloadURL("https://example.invalid/w/"); u != "https://example.invalid/w/ggml-medium.bin" {
		t.Errorf("镜像地址拼错了:%s", u)
	}
}

func TestTranscribe缺依赖要指名道姓(t *testing.T) {
	cases := []struct {
		name string
		d    Deps
		want string
	}{
		{"缺 whisper", Deps{FFmpeg: "ffmpeg"}, "whisper-cli"},
		{"缺 ffmpeg", Deps{Whisper: "whisper-cli"}, "ffmpeg"},
	}
	for _, c := range cases {
		err := depsError(c.d)
		if err == nil {
			t.Fatalf("%s:依赖不全却没报错", c.name)
		}
		if !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s:错误里没说缺的是 %s —— 用户会在两个下载按钮之间瞎点:%v",
				c.name, c.want, err)
		}
	}
	// 两个都缺时两个名字都要出现,不能只报第一个。
	both := depsError(Deps{}).Error()
	if !strings.Contains(both, "whisper-cli") || !strings.Contains(both, "ffmpeg") {
		t.Errorf("两个都缺时只说了一半:%s", both)
	}
	if err := depsError(Deps{Whisper: "a", FFmpeg: "b", Ready: true}); err != nil {
		t.Errorf("两个都在却报错:%v", err)
	}

	// Transcribe 真的走这条闸:模型摆好,依赖缺 → 错误里必须带那个名字。
	paths.SetRoot(t.TempDir())
	if err := os.MkdirAll(ModelsDir(), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(ModelFile(Base), make([]byte, 2<<20), 0o644); err != nil {
		t.Fatal(err)
	}
	if ResolveDeps("", "").Ready {
		t.Skip("这台机器上 whisper-cli 和 ffmpeg 都在,测不到缺依赖")
	}
	_, err := Transcribe(context.Background(), Options{URL: "http://127.0.0.1:1/a.mkv", Model: Base}, nil)
	if err == nil || (!strings.Contains(err.Error(), "whisper-cli") && !strings.Contains(err.Error(), "ffmpeg")) {
		t.Errorf("依赖不全时 Transcribe 没说清缺哪个:%v", err)
	}
}
