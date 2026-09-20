package plugin

// 开发版:桌面「加载本地目录」(D42 D292 D451)。与正式版同 id 时开发版优先、数据共用;改文件即热重载。

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"linplayer/core/bus"
)

type devPlugin struct {
	dir  string
	m    *Manifest
	code []byte
	smap []byte
	quit chan struct{}
}

func (d *devPlugin) stop() {
	select {
	case <-d.quit:
	default:
		close(d.quit)
	}
}

// devCompile 读 manifest 并出 main.js + sourcemap:有 src/ 就现编 TS,没有就用目录里已打好的。
//
// sourcemap 一起带出来:开发模式正是最需要「报错栈映射回 TS 行号」的时候(SPEC 16.5)。
func devCompile(dir string) (*Manifest, []byte, []byte, error) {
	mb, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return nil, nil, nil, errors.New("目录里没有 manifest.json")
	}
	m, err := ParseManifest(mb)
	if err != nil {
		return nil, nil, nil, err
	}
	if _, err := EntryOf(dir); err == nil {
		res, err := Build(dir)
		if err != nil {
			return nil, nil, nil, err
		}
		return m, res.JS, res.SourceMap, nil
	}
	code, err := os.ReadFile(filepath.Join(dir, filepath.FromSlash(m.Main)))
	if err != nil {
		return nil, nil, nil, errors.New("目录里既没有 src/ 源码也没有 " + m.Main)
	}
	smap, _ := os.ReadFile(filepath.Join(dir, filepath.FromSlash(m.Main)) + ".map")
	return m, code, smap, nil
}

// DevLoad 加载本地目录为开发版插件,并开始监视改动。
func (h *Host) DevLoad(dir string) (*Manifest, error) {
	dir, _ = filepath.Abs(dir)
	m, code, smap, err := devCompile(dir)
	if err != nil {
		return nil, err
	}
	d := &devPlugin{dir: dir, m: m, code: code, smap: smap, quit: make(chan struct{})}
	h.mu.Lock()
	if old := h.dev[m.ID]; old != nil {
		old.stop()
	}
	h.dev[m.ID] = d
	h.mu.Unlock()
	h.unload(m.ID) // 正式版挂起(同 id 开发版优先)
	go h.devWatch(d)
	return m, nil
}

// DevUnload 移除开发版,恢复正式版(若有)。
func (h *Host) DevUnload(id string) {
	h.mu.Lock()
	d := h.dev[id]
	delete(h.dev, id)
	h.mu.Unlock()
	if d != nil {
		d.stop()
		h.unload(id)
	}
}

// DevList 已加载的开发版目录。
func (h *Host) DevList() map[string]string {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := map[string]string{}
	for id, d := range h.dev {
		out[id] = d.dir
	}
	return out
}

// devWatch 轮询 mtime:改了就重编并热重载(KV 不动)。
// ponytail: 1 秒轮询代替文件系统通知,少一个依赖;几百个文件以内开销可以忽略。
func (h *Host) devWatch(d *devPlugin) {
	last := stamp(d.dir)
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		select {
		case <-d.quit:
			return
		case <-t.C:
		}
		s := stamp(d.dir)
		if s == last {
			continue
		}
		last = s
		m, code, smap, err := devCompile(d.dir)
		if err != nil {
			bus.Emit("plugin.devError", map[string]string{"id": d.m.ID, "error": err.Error()}, "")
			continue
		}
		h.mu.Lock()
		d.m, d.code, d.smap = m, code, smap
		h.mu.Unlock()
		h.unload(m.ID)
		if _, err := h.get(m.ID, "lazy"); err != nil {
			bus.Emit("plugin.devError", map[string]string{"id": m.ID, "error": err.Error()}, "")
			continue
		}
		bus.Emit("plugin.devReloaded", map[string]string{"id": m.ID}, "")
	}
}

// stamp 目录下源码文件的 mtime 与大小指纹(跳过 dist/ node_modules/)。
func stamp(dir string) string {
	var b strings.Builder
	_ = filepath.WalkDir(dir, func(p string, e fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if e.IsDir() && (e.Name() == "node_modules" || e.Name() == "dist" || strings.HasPrefix(e.Name(), ".")) {
			return filepath.SkipDir
		}
		if info, err := e.Info(); err == nil && !e.IsDir() {
			b.WriteString(p)
			b.WriteString(info.ModTime().String())
		}
		return nil
	})
	return b.String()
}
