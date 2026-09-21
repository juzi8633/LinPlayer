package plugin

// 官方市场索引的对账:plugin-repo/registry/index.json 是客户端真正要拉的那份文件
// (LP_PLUGIN_MARKET_URL 指到它)。它解析不了 / 版本不匹配 / 平台写漏,
// 症状都是「插件商店空的」而不是报错 —— 所以在这儿挡。
//
// 反向验过三次:把索引里的 minAppVersion 抬到 9.0.0、把 size 改成 0、
// 把 tvbox 的 platforms 改成 ["ios"],三次都红。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func readRepoFile(t *testing.T, rel string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", rel))
	if err != nil {
		t.Fatalf("读不到 %s:%v", rel, err)
	}
	return b
}

func TestOfficialIndexInstallableAtShippedVersion(t *testing.T) {
	var idx indexFile
	if err := json.Unmarshal(readRepoFile(t, "plugin-repo/registry/index.json"), &idx); err != nil {
		t.Fatalf("官方索引解析不了:%v", err)
	}
	if len(idx.Plugins) == 0 {
		t.Fatal("官方索引是空的 —— 用户打开插件商店会看到一片空白")
	}

	app := strings.TrimSpace(string(readRepoFile(t, "VERSION")))

	// 每个端至少要有一个插件能装。只在 windows 上验等于没验:
	// 手机/TV 专用主题的 platforms 写错时 windows 照样绿。
	for _, plat := range []string{"windows", "linux", "android", "android_tv"} {
		h := newHost()
		h.platform, h.version = plat, app
		n := 0
		for _, e := range idx.Plugins {
			best, blocked := h.bestVersion(e)
			if blocked {
				// 平台不符是正常的(手机主题不给桌面),版本不符不是
				onlyPlatform := false
				for _, v := range e.Versions {
					if CompareVersions(appCore(app), v.MinAppVersion) >= 0 {
						onlyPlatform = true
					}
				}
				if !onlyPlatform {
					t.Errorf("%s:%s 在 %s 上因为 minAppVersion 装不了(当前 %s)", plat, e.ID, plat, app)
				}
				continue
			}
			if best == nil {
				continue
			}
			n++
			if best.Size <= 0 {
				t.Errorf("%s %s 没有体积 —— 下载会拿到一个长度对不上的包", e.ID, best.Version)
			}
			if !strings.HasPrefix(best.URL, "https://") {
				t.Errorf("%s %s 的下载地址不是 https:%s", e.ID, best.Version, best.URL)
			}
			if e.Name == "" || e.Description == "" {
				t.Errorf("%s 缺名字或简介 —— 商店里是一张空卡片", e.ID)
			}
		}
		if n == 0 {
			t.Errorf("%s 上一个插件都装不了", plat)
		}
	}
}

// 索引里写的版本要和 plugins/<名字>/manifest.json 一致 —— 索引是生成的,
// 手改过 manifest 忘了重新生成时,用户点下载拿到的是另一个版本。
func TestOfficialIndexMatchesManifests(t *testing.T) {
	var idx indexFile
	if err := json.Unmarshal(readRepoFile(t, "plugin-repo/registry/index.json"), &idx); err != nil {
		t.Fatalf("官方索引解析不了:%v", err)
	}
	dirs, err := os.ReadDir(filepath.Join("..", "..", "plugins"))
	if err != nil {
		t.Fatalf("读不到 plugins/:%v", err)
	}
	inIndex := map[string]IndexEntry{}
	for _, e := range idx.Plugins {
		inIndex[e.ID] = e
	}
	seen := 0
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		b, err := os.ReadFile(filepath.Join("..", "..", "plugins", d.Name(), "manifest.json"))
		if err != nil {
			continue
		}
		var m struct {
			ID            string   `json:"id"`
			Version       string   `json:"version"`
			MinAppVersion string   `json:"minAppVersion"`
			Platforms     []string `json:"platforms"`
		}
		if err := json.Unmarshal(b, &m); err != nil {
			t.Errorf("%s/manifest.json 解析不了:%v", d.Name(), err)
			continue
		}
		seen++
		e, ok := inIndex[m.ID]
		if !ok {
			t.Errorf("%s 在 plugins/ 里有源码,官方索引里没有 —— 商店看不到它", m.ID)
			continue
		}
		if len(e.Versions) == 0 || e.Versions[0].Version != m.Version {
			t.Errorf("%s:索引写 %v,manifest 写 %s —— 索引没重新生成", m.ID, e.Versions, m.Version)
			continue
		}
		if e.Versions[0].MinAppVersion != m.MinAppVersion {
			t.Errorf("%s:索引 minAppVersion=%s,manifest=%s", m.ID, e.Versions[0].MinAppVersion, m.MinAppVersion)
		}
		if strings.Join(e.Versions[0].Platforms, ",") != strings.Join(m.Platforms, ",") {
			t.Errorf("%s:索引 platforms=%v,manifest=%v", m.ID, e.Versions[0].Platforms, m.Platforms)
		}
	}
	if seen != len(idx.Plugins) {
		t.Errorf("plugins/ 下 %d 个,索引里 %d 条", seen, len(idx.Plugins))
	}
}
