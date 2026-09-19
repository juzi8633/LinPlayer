package plugin

// lp build / lp pack:源码目录 → main.js(+ sourcemap)→ .lpplugin(SPEC 4.1,D145 D411)。

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// packFiles 进包的文件(除打包产物外):说明、图标、资源、主题。
var packFiles = []string{"README.md", "CHANGELOG.md", "LICENSE", "icon.png", "icon.svg", "icon.webp", "icon-mono.svg"}
var packDirs = []string{"assets", "theme"}

// FillMinAppVersion 把 manifest 的 minAppVersion 设为 SDK 版本(= 应用版本,D411),保留其余字段与顺序。
func FillMinAppVersion(manifest []byte, sdkVersion string) ([]byte, error) {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(manifest, &m); err != nil {
		return nil, err
	}
	v, _ := json.Marshal(appCore(sdkVersion))
	m["minAppVersion"] = v
	// 保序:在原文里替换或插入这一个键
	s := string(manifest)
	if i := strings.Index(s, `"minAppVersion"`); i >= 0 {
		j := strings.IndexAny(s[i+len(`"minAppVersion"`):], ",}")
		if j >= 0 {
			end := i + len(`"minAppVersion"`) + j
			return []byte(s[:i] + `"minAppVersion": ` + string(v) + s[end:]), nil
		}
	}
	return json.MarshalIndent(m, "", "  ")
}

// Pack 编译并打包。返回 zip 字节与 manifest。
func Pack(dir, sdkVersion string) ([]byte, *Manifest, error) {
	mb, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return nil, nil, errors.New("目录里没有 manifest.json")
	}
	if sdkVersion != "" {
		if mb, err = FillMinAppVersion(mb, sdkVersion); err != nil {
			return nil, nil, err
		}
	}
	m, err := ParseManifest(mb)
	if err != nil {
		return nil, nil, err
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	add := func(name string, b []byte) error {
		w, err := zw.Create(name)
		if err != nil {
			return err
		}
		_, err = w.Write(b)
		return err
	}
	if err := add("manifest.json", mb); err != nil {
		return nil, nil, err
	}
	if _, err := EntryOf(dir); err == nil {
		res, err := Build(dir)
		if err != nil {
			return nil, nil, err
		}
		if err := add(m.Main, res.JS); err != nil {
			return nil, nil, err
		}
		if len(res.SourceMap) > 0 {
			if err := add(m.Main+".map", res.SourceMap); err != nil {
				return nil, nil, err
			}
		}
	} else if b, err := os.ReadFile(filepath.Join(dir, filepath.FromSlash(m.Main))); err == nil {
		if err := add(m.Main, b); err != nil {
			return nil, nil, err
		}
	}
	for _, f := range packFiles {
		if b, err := os.ReadFile(filepath.Join(dir, f)); err == nil {
			if err := add(f, b); err != nil {
				return nil, nil, err
			}
		}
	}
	for _, d := range packDirs {
		root := filepath.Join(dir, d)
		err := filepath.WalkDir(root, func(p string, e fs.DirEntry, err error) error {
			if err != nil || e.IsDir() {
				return nil
			}
			rel, _ := filepath.Rel(dir, p)
			b, err := os.ReadFile(p)
			if err != nil {
				return err
			}
			return add(filepath.ToSlash(rel), b)
		})
		if err != nil {
			return nil, nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, nil, err
	}
	return buf.Bytes(), m, nil
}
