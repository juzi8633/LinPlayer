//go:build realsites

package datasource

// 真实站点本地验收(第 ① 阶段):地址只从被忽略的 docs/plugin-system/tvbox-test-sites.local 读,
// 文件不在就跳过;日志里的地址一律抹掉。门禁不跑它(要外网、站点会变):
//
//	go test -tags realsites -run Real -v ./datasource/

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"linplayer/core/bus"
)

// siteKind 源的开放键 → 站点类型(日志里分类用,不带地址)
var siteKind = map[string]string{}

var hostish = regexp.MustCompile(`(?i)(https?://)?[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?(/[^\s"']*)?`)

// redact 日志里的地址换成占位符:本机跑也不留真实域名在输出里。
func redact(s string) string { return hostish.ReplaceAllString(s, "<地址>") }

type realCfg struct {
	cms    []string // 资源站主域名
	parses []string // m3u8 解析
	repo   string   // 多仓配置样本
}

func loadReal(t *testing.T) realCfg {
	f, err := os.Open(filepath.Join("..", "..", "docs", "plugin-system", "tvbox-test-sites.local"))
	if err != nil {
		t.Skip("没有 tvbox-test-sites.local,跳过真实站点测试")
	}
	defer f.Close()
	var c realCfg
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), ":")
		v = strings.TrimSpace(v)
		if !ok || v == "" {
			continue
		}
		switch strings.TrimSpace(k) {
		case "资源站主域名":
			c.cms = append(c.cms, v)
		case "m3u8 解析", "m3u8 解析备用":
			c.parses = append(c.parses, v)
		case "配置样本仓库":
			// 记的是仓库主页;样本是仓库根目录的 tvboxmuti.json(多仓格式)
			c.repo = strings.Replace(strings.TrimRight(v, "/"), "://github.com/", "://raw.githubusercontent.com/", 1) + "/HEAD/tvboxmuti.json"
		}
	}
	return c
}

// subscribe 把一份配置挂成订阅地址,全部源加进来,返回源的开放键。
func (e *env) subscribeAll(url string, repos bool) []string {
	var cs struct {
		Sources []map[string]any `json:"sources"`
		Repos   []struct {
			ID string `json:"id"`
		} `json:"repos"`
	}
	json.Unmarshal(e.call("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": url}}), &cs)
	if len(cs.Repos) > 0 {
		e.t.Logf("多仓:%d 个仓", len(cs.Repos))
		if !repos {
			e.t.Fatal("没想到这是多仓配置")
		}
		// 逐仓试一遍报出哪几个读不到;订能读的前三个(够看跑通率,全订要几分钟)
		ids := []any{}
		for i, r := range cs.Repos {
			raw, err := e.try("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": url}, "repos": []any{r.ID}})
			if err != nil {
				detail := err.Error()
				if be, ok := err.(*bus.Err); ok {
					detail += " | " + be.Detail
				}
				e.t.Logf("仓 %d 读不到:%s", i+1, redact(detail))
				continue
			}
			var one struct {
				Sources []any `json:"sources"`
			}
			json.Unmarshal(raw, &one)
			e.t.Logf("仓 %d:%d 个源", i+1, len(one.Sources))
			if len(ids) < 3 {
				ids = append(ids, r.ID)
			}
		}
		json.Unmarshal(e.call("source.createSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "form": map[string]any{"url": url}, "repos": ids}), &cs)
	}
	var avail []any
	for _, s := range cs.Sources {
		if r, _ := s["unavailableReason"].(string); r == "" {
			avail = append(avail, s)
		}
	}
	if len(avail) == 0 {
		e.t.Fatalf("配置里没有可用的源(共 %d 个)", len(cs.Sources))
	}
	for _, s := range cs.Sources {
		if cfg, ok := s["config"].(map[string]any); ok {
			if site, ok := cfg["site"].(map[string]any); ok {
				kind := fmt.Sprintf("type%v", site["type"])
				if api, _ := site["api"].(string); strings.HasPrefix(api, "csp_") {
					kind += "/jar"
				} else if strings.Contains(api, "drpy") {
					kind += "/drpy"
				}
				siteKind["plugin:linplayer/tvbox/"+fmt.Sprint(s["id"])] = kind
			}
		}
	}
	var added struct {
		Added []string `json:"added"`
	}
	json.Unmarshal(e.call("source.addSources", map[string]any{"plugin_id": "linplayer/tvbox", "type_id": "subscription", "sources": avail}), &added)
	return added.Added
}

// walk 一个源:首页 → 分类 → 搜索 → 详情 → 起播地址。返回走到哪一步、失败原因(已抹地址)。
func (e *env) walk(key string) (string, error) {
	var home struct {
		Categories []struct {
			ID string `json:"id"`
		} `json:"categories"`
		Recommended []map[string]any `json:"recommended"`
	}
	raw, err := e.try("source.home", map[string]any{"server_id": key})
	if err != nil {
		return "首页", err
	}
	json.Unmarshal(raw, &home)
	items := home.Recommended
	if len(home.Categories) > 0 {
		var page struct {
			Items []map[string]any `json:"items"`
		}
		raw, err = e.try("source.category", map[string]any{"server_id": key, "category_id": home.Categories[0].ID})
		if err != nil {
			return "分类", err
		}
		json.Unmarshal(raw, &page)
		if len(items) == 0 {
			items = page.Items
		}
	}
	if len(items) == 0 {
		return "首页", fmt.Errorf("首页和第一个分类都是空的")
	}
	title, _ := items[0]["title"].(string)
	if _, err := e.try("source.searchItems", map[string]any{"server_id": key, "keyword": title}); err != nil {
		return "搜索", err
	}
	id, _ := items[0]["id"].(string)
	var d map[string]any
	raw, err = e.try("source.detail", map[string]any{"server_id": key, "item_id": id})
	if err != nil {
		return "详情", err
	}
	json.Unmarshal(raw, &d)
	lines, _ := d["lines"].([]any)
	if len(lines) == 0 {
		return "详情", fmt.Errorf("没有线路")
	}
	l := lines[0].(map[string]any)
	eps, _ := l["episodes"].([]any)
	if len(eps) == 0 {
		return "详情", fmt.Errorf("线路没有集")
	}
	e.last = nil
	if _, err := e.try("source.playItem", map[string]any{"server_id": key, "item": d, "line_id": l["id"], "episode_id": eps[0].(map[string]any)["id"]}); err != nil {
		return "起播", err
	}
	if e.last == nil || e.last.url == "" {
		return "起播", fmt.Errorf("没拿到播放地址")
	}
	return "完成", nil
}

// 苹果CMS 资源站:本地拼一份只有这个站的配置当订阅,走完整条链。
func TestRealCMS(t *testing.T) {
	c := loadReal(t)
	e := newEnv(t)
	var parses []map[string]any
	for i, p := range c.parses {
		parses = append(parses, map[string]any{"name": fmt.Sprintf("解析%d", i+1), "type": 1, "url": p})
	}
	var sites []map[string]any
	for i, d := range c.cms {
		sites = append(sites, map[string]any{"key": fmt.Sprintf("real%d", i+1), "name": fmt.Sprintf("真站%d", i+1), "type": 1,
			"api": "https://" + d + "/api.php/provide/vod/", "searchable": 1})
	}
	cfg, _ := json.Marshal(map[string]any{"sites": sites, "parses": parses})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(cfg) }))
	defer srv.Close()
	keys := e.subscribeAll(srv.URL+"/real.json", false)
	ok := 0
	for i, k := range keys {
		step, err := e.walk(k)
		if err != nil {
			t.Logf("真站%d:停在「%s」:%s", i+1, step, redact(err.Error()))
			continue
		}
		ok++
		t.Logf("真站%d:首页 → 分类 → 搜索 → 详情 → 起播地址,全通", i+1)
	}
	if ok == 0 {
		t.Fatalf("%d 个资源站一个都没走通", len(keys))
	}
}

// 多仓配置样本:列仓 → 订前三个仓 → 每个源走一遍,报跑通率(真实源本来就有挂的,不要求全通)。
func TestRealMultiRepo(t *testing.T) {
	c := loadReal(t)
	if c.repo == "" {
		t.Skip("没有配置样本仓库")
	}
	e := newEnv(t)
	keys := e.subscribeAll(c.repo, true)
	steps := map[string]int{}
	ok := 0
	for i, k := range keys {
		if i >= 30 {
			break // 30 个够看跑通率
		}
		step, err := e.walk(k)
		steps[step]++
		if err == nil {
			ok++
		} else {
			t.Logf("源 %d(%s)停在「%s」:%s", i+1, siteKind[k], step, redact(err.Error()))
			if os.Getenv("REAL_DEBUG") != "" {
				var logs struct {
					Logs []struct {
						Msg string `json:"msg"`
					} `json:"logs"`
				}
				if raw, err := e.try("plugin.errorDetail", map[string]any{"id": "linplayer/tvbox"}); err == nil {
					json.Unmarshal(raw, &logs)
				}
				if f := os.Getenv("REAL_DUMP"); f != "" { // 原始地址只写进本机文件,不进输出
					if fh, err := os.OpenFile(f, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
						for _, l := range logs.Logs {
							if strings.Contains(l.Msg, "req 失败") {
								fmt.Fprintln(fh, l.Msg)
							}
						}
						fh.Close()
					}
				}
				for j := max(0, len(logs.Logs)-4); j < len(logs.Logs); j++ {
					t.Logf("    日志:%s", redact(logs.Logs[j].Msg))
				}
			}
		}
	}
	n := min(len(keys), 30)
	t.Logf("多仓样本:试了 %d 个源,全链走通 %d 个;停在各步的:%v", n, ok, steps)
	if ok == 0 {
		t.Fatalf("试了 %d 个源一个都没走通", n)
	}
}
