package plugin

// 搜索页的快捷动作(SPEC 6.2,D242)。
//
// 本文件注册的 plugin.* 命令,**三个壳都得真去调**(门禁:三端都要调) ——
// 不调的表现是「插件声明了,界面上一个都看不见」,而四边全绿。
//
// 插件拿到用户输的那串字,回几个「在豆瓣查看」「导入这个订阅」这样的按钮;
// 结果列表本身不动。
//
// 点了跑哪条命令,走的是**已有的** `source.runCommand`(它本来就是给菜单项和设置按钮
// 跑插件命令用的,不限于数据源)—— 再开一条同样的命令只会让人猜该用哪个。

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"linplayer/core/bus"
)

// SearchActionInfo 搜索页上的一颗按钮。
type SearchActionInfo struct {
	PluginID string          `json:"plugin_id"`
	Name     string          `json:"name"`
	Title    string          `json:"title"`
	Icon     string          `json:"icon,omitempty"`
	Command  string          `json:"command"`
	Args     json.RawMessage `json:"args,omitempty"`
}

// searchActionBudget 一次输入的等待上限。
//
// 搜索框是边打边出的,慢一步就等于没有:2 秒之后用户早又敲了一个字。
// 超了就当这个插件没给 —— 不报错,也不拖住官方结果。
const searchActionBudget = 2 * time.Second

// SearchActions 问所有声明了 searchActions 的插件要按钮。
//
// 并发问,各问各的:串起来的话一个慢插件把后面所有的都拖住,
// 而这一串的总时长直接摊在用户每敲一个字上。
func (h *Host) SearchActions(ctx context.Context, q string) []SearchActionInfo {
	type slot struct {
		id, name string
		out      []SearchActionInfo
	}
	var slots []*slot
	for _, i := range h.List(nil) {
		if !i.Enabled {
			continue
		}
		m, err := h.Manifest(i.ID)
		if err != nil || !m.Contributes.SearchActions {
			continue
		}
		slots = append(slots, &slot{id: i.ID, name: i.Name})
	}

	var wg sync.WaitGroup
	for _, s := range slots {
		wg.Add(1)
		go func(s *slot) {
			defer wg.Done()
			raw, err := h.Call(ctx, s.id, searchActionBudget, "searchActions", []any{q}, map[string]any{})
			if err != nil {
				return
			}
			var got []struct {
				Title   string          `json:"title"`
				Icon    string          `json:"icon"`
				Command string          `json:"command"`
				Args    json.RawMessage `json:"args"`
			}
			if json.Unmarshal(raw, &got) != nil {
				return
			}
			for _, a := range got {
				// 标题或命令缺一个,这颗按钮就是点了没反应 —— 宁可不画
				if a.Title == "" || a.Command == "" {
					continue
				}
				s.out = append(s.out, SearchActionInfo{
					PluginID: s.id, Name: s.name,
					Title: a.Title, Icon: a.Icon, Command: a.Command, Args: a.Args,
				})
			}
		}(s)
	}
	wg.Wait()

	// 按安装顺序拼(slots 已经是那个顺序),同一个插件给的几颗挨着
	out := []SearchActionInfo{}
	for _, s := range slots {
		out = append(out, s.out...)
	}
	return out
}

func registerSearchActions() {
	bus.Register("plugin.searchActions", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		q, _ := a["q"].(string)
		if q == "" {
			return []SearchActionInfo{}, nil
		}
		return Default().SearchActions(ctx, q), nil
	})
}
