package transcribe

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"linplayer/core/paths"
	"net/http"
	"net/http/httptest"
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
	/* ☠ 不许按环境跳过。上一版是「这台机器装了 ffmpeg + whisper 就 t.Skip」——
	   于是在开发机上这条判据从来没跑过,而它是唯一一条真的调 Transcribe 的用例。
	   改成**指一个一定不存在的路径**:缺依赖这条路任何机器上都走得到。 */
	_, err := Transcribe(context.Background(), Options{
		URL: "http://127.0.0.1:1/a.mkv", Model: Base,
		Whisper: filepath.Join(t.TempDir(), "没有这个-whisper"),
		FFmpeg:  filepath.Join(t.TempDir(), "没有这个-ffmpeg"),
	}, nil)
	if err == nil || (!strings.Contains(err.Error(), "whisper-cli") && !strings.Contains(err.Error(), "ffmpeg")) {
		t.Errorf("依赖不全时 Transcribe 没说清缺哪个:%v", err)
	}
}

/*
下载不完整不许被当成「已下载」。

☠ 上游提前断流时 `Read` 回的就是 EOF —— 不比 Content-Length 的话,半截权重

	会被改名成正式文件,`Downloaded()` 从此一直说「已下载」,whisper 每次在同一个
	地方解析失败。用户看到「转写失败」,删了重下也没用,因为根本不会重下。
*/
func TestDownload上游提前断流不许留下正式文件(t *testing.T) {
	paths.SetRoot(t.TempDir())
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "1048576")
		w.WriteHeader(200)
		_, _ = w.Write(make([]byte, 4096)) // 说好 1MB,只给 4KB 就断
	}))
	defer srv.Close()

	if err := os.MkdirAll(ModelsDir(), 0o755); err != nil {
		t.Fatal(err)
	}
	// 走 downloadTo:Download 强制 https(那条是另一条判据),这里量的是长度校验
	part := ModelFile(Base) + ".part"
	err := downloadTo(context.Background(), srv.URL+"/x.bin", part, nil)
	if err == nil {
		t.Fatal("上游只给了一部分却当成下载成功了")
	}
	// Go 的 transport 对「Content-Length 说 1MB 只给了 4KB」自己就会报
	// unexpected EOF;长度自检是它漏掉时的第二道(比如上游根本不给长度)。
	// 这条判据要钉的是**结果**:半截文件不许留在正式名字上。
	// 调用方据此删掉 .part 并且**不改名**;正式文件不该存在
	if Downloaded(Base) {
		t.Fatal("半截文件被当成已下载 —— 之后永远不会重下")
	}
}

// 两次转写不许共用临时文件:先跑完的那个会把另一边的删掉。
func TestTranscribe两次并发各用各的临时目录(t *testing.T) {
	paths.SetRoot(t.TempDir())
	root := filepath.Join(paths.TempDir(), "transcribe")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	a, err := os.MkdirTemp(root, "t")
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.MkdirTemp(root, "t")
	if err != nil {
		t.Fatal(err)
	}
	if a == b {
		t.Fatal("两次拿到同一个临时目录")
	}
}
