package config

// 绿色包换目录升级时,把上一份安装里的用户数据接过来。
//
// ☠☠ 用户报的是「每次更新软件就把账号覆盖了」。**根因不是我们写坏了文件,
// 是数据根跟着 exe 走**:绿色包的承诺是「数据全在 exe 同级 userdata/」,
// 而更新的实际动作往往是「把新包解压到另一个文件夹」——
// 新目录下的 userdata/ 是空的,于是服务器、进度、插件状态全不见了,而且不报错。
//
// Rust 版有过这一段(`migrate_legacy`),Go 重写时没跟过来。
//
// ★ 只在数据根**一片空白**时才做,绝不覆盖已有配置。

import (
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"linplayer/core/paths"
)

// adoptFiles 值得搬的东西:账号(含 token)、观看进度、插件启用态。
//
// ★ 缓存、日志、下载**不搬**:前两个能重建,最后一个可能有几十 GB,
// 而升级这一刻用户正在等启动。
var adoptFiles = []string{
	"config.json",
	"history.json",
	filepath.Join("plugins", "state.json"),
}

// AdoptPreviousInstall 在数据根还是空的时候,从同级目录里的上一份安装搬一次数据。
//
// 返回搬出来的源目录(没搬就是空串)和文件数。**只在桌面端调用** ——
// 安卓的数据根由系统给,升级本来就不会换地方。
func AdoptPreviousInstall() (string, int) {
	root := paths.Root()
	if _, err := os.Stat(filepath.Join(root, "config.json")); err == nil {
		return "", 0 // 已经有配置了,这不是一次全新安装
	}
	src := findPreviousRoot(root)
	if src == "" {
		return "", 0
	}
	n := 0
	for _, rel := range adoptFiles {
		if err := copyFile(filepath.Join(src, rel), filepath.Join(root, rel)); err == nil {
			n++
		}
	}
	return src, n
}

// findPreviousRoot 在「装程序的那个目录」的**同级**里找一份带账号的 userdata/。
//
// 找的是 `<安装目录>/../*/userdata/config.json`:用户把新版解压到旧版旁边
// (`LinPlayer-1.1/` 挨着 `LinPlayer-1.0/`)是最常见的升级姿势。
// 多份就取 config.json 最新的那一份 —— 那是他最后一次在用的。
//
// ★ 只找一层。往上再翻会扫到整个下载目录,而扫到别人的文件是另一类事故。
func findPreviousRoot(root string) string {
	installDir := filepath.Dir(root) // root = <安装目录>/userdata
	parent := filepath.Dir(installDir)
	if parent == installDir {
		return "" // 已经到盘根了
	}
	entries, err := os.ReadDir(parent)
	if err != nil {
		return ""
	}
	type cand struct {
		dir string
		mod int64
	}
	var found []cand
	for _, e := range entries {
		if !e.IsDir() || strings.EqualFold(filepath.Join(parent, e.Name()), installDir) {
			continue
		}
		dir := filepath.Join(parent, e.Name(), "userdata")
		fi, err := os.Stat(filepath.Join(dir, "config.json"))
		if err != nil || fi.Size() == 0 {
			continue
		}
		found = append(found, cand{dir, fi.ModTime().UnixNano()})
	}
	if len(found) == 0 {
		return ""
	}
	sort.Slice(found, func(i, j int) bool { return found[i].mod > found[j].mod })
	return found[0].dir
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	return out.Close()
}
