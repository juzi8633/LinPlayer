package plugin

// 安装器底线(SPEC 3.3,D421):不是权限,是防坏包。

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
)

const (
	maxUnpacked = 2 << 30 // 解压总大小上限
	maxRatio    = 100     // 整体压缩比上限
)

// Unpack 把 .lpplugin 解到 dest(dest 不能已存在)。成功返回校验过的 manifest。
//
// 大小与压缩比按**实际写出的字节**算,不信 zip 头里自报的尺寸:压缩炸弹正是靠谎报尺寸绕过检查。
func Unpack(pkg, dest string) (*Manifest, error) {
	zr, err := zip.OpenReader(pkg)
	if err != nil {
		return nil, fmt.Errorf("不是有效的插件包(zip 打不开): %v", err)
	}
	defer zr.Close()
	st, err := os.Stat(pkg)
	if err != nil {
		return nil, err
	}
	var hasManifest bool
	for _, f := range zr.File {
		name, err := entryName(f.Name)
		if err != nil {
			return nil, err
		}
		if name == "manifest.json" {
			hasManifest = true
		}
	}
	if !hasManifest {
		return nil, errors.New("插件包缺少 manifest.json")
	}
	tmp := dest + ".partial"
	_ = os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return nil, err
	}
	ok := false
	defer func() {
		if !ok {
			_ = os.RemoveAll(tmp)
		}
	}()
	limit := int64(maxUnpacked)
	if r := st.Size() * maxRatio; r < limit {
		limit = r
	}
	var written int64
	for _, f := range zr.File {
		name, _ := entryName(f.Name)
		if name == "" {
			continue
		}
		target := filepath.Join(tmp, filepath.FromSlash(name))
		if f.FileInfo().IsDir() || strings.HasSuffix(f.Name, "/") {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return nil, err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return nil, err
		}
		n, err := extract(f, target, limit-written)
		written += n
		if err != nil {
			return nil, err
		}
	}
	mb, err := os.ReadFile(filepath.Join(tmp, "manifest.json"))
	if err != nil {
		return nil, err
	}
	m, err := ParseManifest(mb)
	if err != nil {
		return nil, err
	}
	if _, err := os.Stat(filepath.Join(tmp, filepath.FromSlash(m.Main))); err != nil && !isThemeOnly(m) {
		return nil, fmt.Errorf("插件包缺少入口文件 %s", m.Main)
	}
	_ = os.RemoveAll(dest)
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, dest); err != nil {
		return nil, err
	}
	ok = true
	return m, nil
}

// isThemeOnly 纯主题包可以没有 main.js(D206)。
func isThemeOnly(m *Manifest) bool {
	var raw struct {
		Contributes map[string]any `json:"contributes"`
	}
	_ = jsonUnmarshal(m.Raw, &raw)
	_, theme := raw.Contributes["theme"]
	return theme && len(raw.Contributes) == 1
}

// entryName 规范化 zip 条目名;含 `..` 或绝对路径的整包拒装。
func entryName(n string) (string, error) {
	n = strings.ReplaceAll(n, "\\", "/")
	if strings.HasPrefix(n, "/") || (len(n) >= 2 && n[1] == ':') {
		return "", fmt.Errorf("插件包含绝对路径条目 %q,拒绝安装", n)
	}
	for _, seg := range strings.Split(n, "/") {
		if seg == ".." {
			return "", fmt.Errorf("插件包含路径穿越条目 %q,拒绝安装", n)
		}
	}
	return strings.TrimPrefix(path.Clean("/"+n), "/"), nil
}

func extract(f *zip.File, target string, budget int64) (int64, error) {
	rc, err := f.Open()
	if err != nil {
		return 0, err
	}
	defer rc.Close()
	out, err := os.Create(target)
	if err != nil {
		return 0, err
	}
	defer out.Close()
	n, err := io.Copy(out, io.LimitReader(rc, budget+1))
	if err != nil {
		return n, err
	}
	if n > budget {
		return n, errors.New("插件包解压后过大或压缩比异常(超过 2GB 或 100 倍),拒绝安装")
	}
	return n, nil
}
