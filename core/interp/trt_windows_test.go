//go:build windows

package interp

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func TestTRTExcludes只留本机那一代(t *testing.T) {
	ex := trtExcludes("sm120")
	has := func(a string) bool {
		return slices.Contains(ex, "vs-plugins/vsmlrt-cuda/nvinfer_builder_resource_"+a+"_10.dll")
	}
	if has("sm120") {
		t.Fatal("把本机要用的构建资源剔掉了")
	}
	for _, a := range []string{"ptx", "sm75", "sm86", "sm89", "sm90", "sm100"} {
		if !has(a) {
			t.Errorf("没剔掉 %s", a)
		}
	}
}

// 拿真包跑一遍解压(2.9GB、约 100 秒,CI 上没有包,只在本机跑):
//
//	LP_VSNV=D:\...\vsNV.7z go test ./core/interp -run 真包 -timeout 10m
//
// 钉的是 bsdtar 的行为 —— exclude 压过 include 那种坑只有真跑才看得见。
func TestExtract7z真包(t *testing.T) {
	pkg := os.Getenv("LP_VSNV")
	if pkg == "" {
		t.Skip("没给 LP_VSNV")
	}
	dir := t.TempDir()
	if err := extract7z(context.Background(), pkg, trtMembers, trtExcludes("sm120"), dir); err != nil {
		t.Fatal(err)
	}
	cuda := filepath.Join(dir, "vs-plugins", "vsmlrt-cuda")
	for _, f := range []string{"nvinfer_10.dll", "nvinfer_plugin_10.dll", "trtexec.exe", "nvinfer_builder_resource_sm120_10.dll"} {
		if _, err := os.Stat(filepath.Join(cuda, f)); err != nil {
			t.Errorf("缺 %s", f)
		}
	}
	m, _ := filepath.Glob(filepath.Join(cuda, "nvinfer_builder_resource_*"))
	if len(m) != 1 {
		t.Errorf("构建资源应该只有本机那一份,实际 %d 份:%v", len(m), m)
	}
	for _, f := range []string{"vs-plugins/vstrt.dll", "vs-plugins/models/rife_v2/rife_v4.26.onnx", "vs-plugins/models/drba/distilDRBA_v2_lite_scale_ap.onnx"} {
		if _, err := os.Stat(filepath.Join(dir, filepath.FromSlash(f))); err != nil {
			t.Errorf("缺 %s", f)
		}
	}
}
