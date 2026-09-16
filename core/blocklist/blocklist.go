// Package blocklist 是媒体屏蔽名单。
//
// **Rust 版是黄金实现**,
// 这里的判定逻辑一条都不许简化 —— 每条都对应过一次「用户一眼就看得见的漏网」。
package blocklist

import (
	"encoding/json"
	"os"
	"strings"
	"sync"
	"time"

	"linplayer/core/paths"
)

// Item 是被判定对象需要提供的最小信息。
// 用接口而不是直接依赖 emby.Item:屏蔽名单也要给观看记录、插件源用。
type Item interface {
	BlockID() string
	BlockName() string
	BlockSeriesID() string
	BlockSeriesName() string // 空字符串表示没有
	HasSeriesName() bool
}

// Entry 一条屏蔽记录。
//
// ★ **id 和名字都要存**:分集靠 series_id 认,跨服的同一部剧 id 不同、只有名字对得上。
type Entry struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	At   int64  `json:"at"`
}

var (
	mu   sync.RWMutex
	list []Entry
)

// List 返回当前名单的快照。
func List() []Entry {
	mu.RLock()
	defer mu.RUnlock()
	out := make([]Entry, len(list))
	copy(out, list)
	return out
}

// Set 加入或移除一条,并**立刻落盘**。
//
// ☠ 落盘这一步原来整个不存在:名单只是一个包级切片,重启就空。
// 表现是「屏蔽过的东西第二天自己回来了」,而且一声不吭 ——
// 对「屏蔽媒体库 → 卡片变灰 → 右键恢复」这条闭环更致命:灰卡下次开机自己变回来,
// 用户会以为恢复键失灵。Rust 版一直是落 blocklist.json 的,迁移时漏了这一块。
func Set(id, name string, blocked bool) {
	mu.Lock()
	changed := false
	found := false
	for i := range list {
		if list[i].ID == id {
			found = true
			if !blocked {
				list = append(list[:i], list[i+1:]...)
			} else {
				list[i].Name = name
			}
			changed = true
			break
		}
	}
	if !found && blocked {
		list = append(list, Entry{ID: id, Name: name, At: time.Now().Unix()})
		changed = true
	}
	snap := make([]Entry, len(list))
	copy(snap, list)
	mu.Unlock()
	if changed {
		save(snap)
	}
}

// File 名单落盘的位置。**唯一出口是 paths**,别的包不许自己拼路径(SPEC §10.1)。
func File() string { return paths.BlocklistFile() }

// Load 开机读一次。文件不存在 / 读坏了都当空名单 ——
// 屏蔽名单坏掉的后果只是「屏蔽失效」,不该让程序起不来。
func Load() {
	b, err := os.ReadFile(File())
	if err != nil {
		return
	}
	var v []Entry
	if json.Unmarshal(b, &v) != nil {
		return
	}
	Replace(v)
}

// save 原子写:临时文件 + rename(和配置同一个口径,SPEC §14.2)。
//
// 就地截断重写的话,断电在截断之后写入之前,留下的是 0 字节文件 ——
// 下次 Load 解析失败,整张名单静默消失。
func save(v []Entry) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	p := File()
	tmp := p + ".tmp"
	if os.WriteFile(tmp, b, 0o644) != nil {
		return
	}
	if os.Rename(tmp, p) != nil {
		_ = os.Remove(tmp)
	}
}

// Replace 整体替换(加载配置时用)。
func Replace(v []Entry) {
	mu.Lock()
	defer mu.Unlock()
	list = append(list[:0], v...)
}

// nameHit 名字命中。
//
// ★ **空名字永不命中** —— 否则一条脏数据能把整个库屏蔽掉。
func nameHit(blockedName, candidate string) bool {
	return blockedName != "" && candidate != "" &&
		strings.EqualFold(candidate, blockedName)
}

// IsBlocked 这个条目被屏蔽了吗。
//
// ★ 三条判据缺一不可:
//  1. 条目自己被屏蔽(在媒体库网格上右键屏蔽的那张卡)
//  2. 它所属的剧被屏蔽(series_id)—— 屏蔽一部剧却在「继续观看」里看见它的分集,
//     是用户第一眼就会发现的漏网
//  3. 剧名对上(series_name / name)—— 跨服的同一部剧 id 不同,只有名字对得上
func IsBlocked(it Item) bool {
	l := List()
	if len(l) == 0 {
		return false
	}
	series := it.BlockSeriesID()
	for _, b := range l {
		switch {
		case b.ID == it.BlockID():
			return true
		case series != "" && b.ID == series:
			return true
		case nameHit(b.Name, it.BlockSeriesName()):
			return true
		case !it.HasSeriesName() && nameHit(b.Name, it.BlockName()):
			return true
		}
	}
	return false
}

// IsBlockedID 这个 id 在名单里吗。
//
// ★ **给媒体库用** —— 库(CollectionFolder)没有 series_id 也不参与跨服名字比对,
// 只按 id 判就够。而且**不能按名字判**:两台服务器上都叫「电影」的库是两个不同的库,
// 按名字会一屏两台一起屏蔽。
func IsBlockedID(id string) bool {
	if id == "" {
		return false
	}
	for _, b := range List() {
		if b.ID == id {
			return true
		}
	}
	return false
}

// IsBlockedTitle 观看记录用:按标题判。记录是跨服的,没有可靠的 item id 可比。
func IsBlockedTitle(title, seriesTitle string) bool {
	for _, b := range List() {
		if nameHit(b.Name, seriesTitle) || nameHit(b.Name, title) {
			return true
		}
	}
	return false
}
