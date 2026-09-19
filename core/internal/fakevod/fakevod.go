// Package fakevod 是 TVBox 验收用的假资源站(D321):苹果CMS JSON/XML 接口、drpy 规则站、
// T4 接口、解析接口、各种编码的配置样本、可切换内容的订阅。
//
// 数据全是编的,地址全是本机回环;门禁(go test)与真机自检(core/cmd/fakevod)共用这一份。
package fakevod

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

// Vod 一部片。
type Vod struct {
	ID      string
	Name    string
	Year    int
	Type    string // 分类 id
	Remarks string
	Content string
	Lines   []Line
	Actor   string
}

// Line 一条线路。
type Line struct {
	Name     string
	Episodes []string // 集名;地址按 Site.playURL 生成
	Flag     string   // 要走解析时的线路代号(出现在配置 flags 里)
}

// Site 一个假站(同一台服务器上用路径前缀区分)。
type Site struct {
	Prefix string
	Vods   []Vod
}

// Server 假站服务器。
type Server struct {
	Base     string // 本机回环上的起服务地址,由调用方在起服务后填
	sites    map[string]*Site
	variant  atomic.Int32 // 订阅内容版本:0 = 初版,1 = 增删源之后(验收 ②)
	mediaDir string       // ffmpeg 生成的测试 HLS;空 = 媒体 404
	hits     sync.Map     // 路径 → 次数(测试看缓存有没有生效)
	// EngineJS 配置指定的 drpy 引擎(测试把插件内置那份喂进来,验证「用配置指定的引擎」这条路)
	EngineJS string
}

// New 造一个假站。mediaDir 为空时媒体地址回 404(门禁不播放,只验到地址)。
func New(mediaDir string) *Server {
	s := &Server{sites: map[string]*Site{}, mediaDir: mediaDir}
	s.sites["a"] = &Site{Prefix: "a", Vods: siteA()}
	s.sites["b"] = &Site{Prefix: "b", Vods: siteB()}
	return s
}

// SetVariant 切订阅内容(0 初版 / 1 删掉 XML 源、加一个新源)。
func (s *Server) SetVariant(v int) { s.variant.Store(int32(v)) }

// Hits 某路径被请求的次数。
func (s *Server) Hits(path string) int {
	v, _ := s.hits.Load(path)
	n, _ := v.(int)
	return n
}

func siteA() []Vod {
	eps := func(n int, f string) []string {
		out := make([]string, n)
		for i := range out {
			out[i] = fmt.Sprintf(f, i+1)
		}
		return out
	}
	vods := []Vod{
		{ID: "101", Name: "星际漫游", Year: 2023, Type: "1", Remarks: "HD", Content: "<p>一部讲<b>星际</b>旅行的电影。</p><p>第二段简介</p>",
			Actor: "张三,李四", Lines: []Line{{Name: "星空线路", Episodes: []string{"正片"}}, {Name: "备用线路", Episodes: []string{"正片"}}}},
		{ID: "102", Name: "山河故事", Year: 2021, Type: "2", Remarks: "更新至12集", Content: "山河之间的故事。",
			Lines: []Line{{Name: "星空线路", Episodes: eps(12, "第%d集")}, {Name: "极速线路", Episodes: eps(10, "第%02d集")},
				{Name: "官源线路", Episodes: eps(3, "第%d集"), Flag: "fakeflag"}}},
		{ID: "103", Name: "测试综艺", Year: 2025, Type: "2", Remarks: "20250110期",
			Lines: []Line{{Name: "星空线路", Episodes: []string{"20250103期", "20250110期"}}}},
	}
	for i := 0; i < 40; i++ {
		vods = append(vods, Vod{ID: strconv.Itoa(200 + i), Name: fmt.Sprintf("影片%d", i), Year: 2000 + i%20, Type: "1",
			Lines: []Line{{Name: "星空线路", Episodes: []string{"正片"}}}})
	}
	return vods
}

// siteB 另一个站:「山河故事」换了 id,供换源第 ② 层找到「同一部」。
func siteB() []Vod {
	return []Vod{
		{ID: "b-9", Name: "山河故事", Year: 2021, Type: "2", Remarks: "全12集",
			Lines: []Line{{Name: "B站线路", Episodes: []string{"第1集", "第2集", "第3集", "第4集", "第5集", "第6集", "第7集", "第8集", "第9集", "第10集", "第11集", "第12集"}}}},
		{ID: "b-10", Name: "山河故事 解说", Year: 2021, Type: "2", Lines: []Line{{Name: "B站线路", Episodes: []string{"第1集"}}}},
	}
}

// Handler 全部路由。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	count := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			v, _ := s.hits.LoadOrStore(r.URL.Path, 0)
			s.hits.Store(r.URL.Path, v.(int)+1)
			h(w, r)
		}
	}
	mux.HandleFunc("/a/api.php/provide/vod/", count(func(w http.ResponseWriter, r *http.Request) { s.cmsJSON(w, r, s.sites["a"]) }))
	mux.HandleFunc("/b/api.php/provide/vod/", count(func(w http.ResponseWriter, r *http.Request) { s.cmsJSON(w, r, s.sites["b"]) }))
	mux.HandleFunc("/xml/api.php/provide/vod/", count(func(w http.ResponseWriter, r *http.Request) { s.cmsXML(w, r, s.sites["a"]) }))
	// 「JS 写一个校验 cookie 再刷新」的防护:没带 cookie 回防护页,带了才给 JSON(真实资源站常见)
	mux.HandleFunc("/jsck/api.php/provide/vod/", count(func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie("ge_js_validator_1"); err != nil || c.Value != "ok" {
			fmt.Fprint(w, `<html><head><script>document.cookie = "ge_js_validator_1=ok; path=/; max-age=3600;"; location.reload();</script></head></html>`)
			return
		}
		s.cmsJSON(w, r, s.sites["a"])
	}))
	mux.HandleFunc("/drpy/", count(s.drpySite))
	mux.HandleFunc("/rules/fake.js", count(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, DrpyRule(s.Base)) }))
	mux.HandleFunc("/libs/drpy2.min.js", count(func(w http.ResponseWriter, r *http.Request) {
		if s.EngineJS == "" {
			http.NotFound(w, r)
			return
		}
		fmt.Fprint(w, s.EngineJS)
	}))
	mux.HandleFunc("/t4", count(s.t4))
	mux.HandleFunc("/jx/", count(s.parser))
	mux.HandleFunc("/page/", count(func(w http.ResponseWriter, r *http.Request) {
		// 官源线路的「网页地址」:本身不是媒体,要走解析;网页里的播放器会去拉 m3u8(网页嗅探抓的就是这一下)
		name := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/page/"), ".html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, `<html><body>这是一个视频网页,需要解析<script>fetch("/media/sniff-%s.m3u8")</script></body></html>`, name)
	}))
	mux.HandleFunc("/media/", s.media)
	mux.HandleFunc("/live.m3u", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "#EXTM3U\n#EXTINF:-1 group-title=\"测试\",测试台\n%s/media/live.m3u8\n", s.Base)
	})
	// 配置样本
	mux.HandleFunc("/config/plain.json", count(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(s.Config())) }))
	mux.HandleFunc("/config/json5.json", count(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(s.json5Config())) }))
	mux.HandleFunc("/config/b64.txt", count(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(Base64Wrap(s.Config()))) }))
	mux.HandleFunc("/config/cover.jpg", count(func(w http.ResponseWriter, r *http.Request) {
		// 图片尾部藏配置:真图片字节 + 8 位标记 + ** + base64
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write(append(fakeJPEG(), []byte(Base64Wrap(s.Config()))...))
	}))
	mux.HandleFunc("/config/cbc.txt", count(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(CBCWrap(s.Config(), "fakekey", "fakeiv") + "\n")) // 真实文件末尾常带换行
	}))
	mux.HandleFunc("/config/ecb.txt", count(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(ECBWrap(s.Config(), "pk-secret"))) }))
	mux.HandleFunc("/config/multi.json", count(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, `{"urls":[{"name":"仓一","url":"%[1]s/config/plain.json"},{"name":"仓二","url":"%[1]s/config/json5.json"}]}`, s.Base)
	}))
	// 配置托管在「浏览器 UA 回网页、okhttp 才回文件」的中转上(真实多仓样本就有)
	mux.HandleFunc("/config/okhttp.json", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.UserAgent(), "okhttp") {
			fmt.Fprint(w, "<!DOCTYPE html><html><body>下载页</body></html>")
			return
		}
		w.Write([]byte(s.Config()))
	})
	// 没有解析接口的配置:官源线路只能走网页嗅探(真机自检用,门禁里没有壳)
	mux.HandleFunc("/config/sniff.json", func(w http.ResponseWriter, r *http.Request) {
		var cfg map[string]any
		_ = json.Unmarshal([]byte(s.Config()), &cfg)
		delete(cfg, "parses")
		_ = json.NewEncoder(w).Encode(cfg)
	})
	mux.HandleFunc("/config/jsck.json", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, `{"sites":[{"key":"jsck","name":"带防护的站","type":1,"api":"%s/jsck/api.php/provide/vod/","searchable":1}]}`, s.Base)
	})
	mux.HandleFunc("/config/bad.txt", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("这不是配置")) })
	return mux
}

// Config 订阅配置(随 variant 变化)。
func (s *Server) Config() string {
	b := s.Base
	sites := []map[string]any{
		{"key": "cms1", "name": "假站JSON", "type": 1, "api": b + "/a/api.php/provide/vod/", "searchable": 1},
		{"key": "cmsb", "name": "假站B", "type": 1, "api": b + "/b/api.php/provide/vod/", "searchable": 1},
		{"key": "drpy1", "name": "假站drpy", "type": 3, "api": b + "/libs/drpy2.min.js", "ext": b + "/rules/fake.js", "searchable": 1},
		{"key": "t4", "name": "假站T4", "type": 4, "api": b + "/t4", "searchable": 0},
		{"key": "jar1", "name": "jar源", "type": 3, "api": "csp_Demo", "jar": b + "/spider.jar;md5;0123456789abcdef", "searchable": 1},
	}
	if s.variant.Load() == 0 {
		sites = append(sites, map[string]any{"key": "cms0", "name": "假站XML", "type": 0, "api": b + "/xml/api.php/provide/vod/", "searchable": 1})
	} else {
		// 订阅更新:删掉 XML 源、加一个新源(D230 D231)
		sites = append(sites, map[string]any{"key": "cmsnew", "name": "新加的源", "type": 1, "api": b + "/a/api.php/provide/vod/", "searchable": 0})
	}
	cfg := map[string]any{
		"sites":     sites,
		"parses":    []map[string]any{{"name": "假解析", "type": 1, "url": b + "/jx/?url="}},
		"flags":     []string{"fakeflag"},
		"lives":     []map[string]any{{"name": "假直播", "type": 0, "url": b + "/live.m3u"}},
		"rules":     []map[string]any{{"host": hostOf(b), "rule": []string{"/ad/"}}},
		"ads":       []string{"ads.invalid"},
		"doh":       []map[string]any{{"name": "忽略我"}},
		"wallpaper": "忽略",
	}
	out, _ := json.Marshal(cfg)
	return string(out)
}

func hostOf(base string) string {
	u, err := url.Parse(base)
	if err != nil {
		return ""
	}
	return u.Hostname()
}

// json5Config 带注释与尾逗号的同一份配置(D100)。
func (s *Server) json5Config() string {
	c := s.Config()
	c = strings.Replace(c, `{"sites":`, "{\n// 这是注释\n/* 块注释 */\n\"sites\":", 1)
	c = strings.Replace(c, `]}`, `,]}`, 1) // 尾逗号
	return c
}

// ---------------------------------------------------------------- 苹果CMS

func (s *Server) playURL(site *Site, v Vod, li, ei int) string {
	if v.Lines[li].Flag != "" {
		return fmt.Sprintf("%s/page/%s-%d-%d.html", s.Base, v.ID, li, ei)
	}
	return fmt.Sprintf("%s/media/%s-%s-%d-%d.m3u8", s.Base, site.Prefix, v.ID, li, ei)
}

func (s *Server) playFields(site *Site, v Vod) (from, urls string) {
	var fs, us []string
	for li, l := range v.Lines {
		name := l.Name
		if l.Flag != "" {
			name = l.Flag
		}
		fs = append(fs, name)
		var eps []string
		for ei, e := range l.Episodes {
			eps = append(eps, e+"$"+s.playURL(site, v, li, ei))
		}
		us = append(us, strings.Join(eps, "#"))
	}
	return strings.Join(fs, "$$$"), strings.Join(us, "$$$")
}

func (s *Server) vodJSON(site *Site, v Vod, detail bool) map[string]any {
	m := map[string]any{"vod_id": v.ID, "vod_name": v.Name, "vod_pic": s.Base + "/media/poster-" + v.ID + ".jpg",
		"vod_remarks": v.Remarks, "vod_year": strconv.Itoa(v.Year), "type_id": v.Type}
	if detail {
		m["vod_content"], m["vod_actor"], m["vod_area"] = v.Content, v.Actor, "中国"
		m["vod_play_from"], m["vod_play_url"] = s.playFields(site, v)
	}
	return m
}

func filter(site *Site, q map[string][]string) []Vod {
	get := func(k string) string {
		if v := q[k]; len(v) > 0 {
			return v[0]
		}
		return ""
	}
	var out []Vod
	ids := map[string]bool{}
	for _, id := range strings.Split(get("ids"), ",") {
		if id != "" {
			ids[id] = true
		}
	}
	for _, v := range site.Vods {
		switch {
		case len(ids) > 0 && !ids[v.ID]:
			continue
		case get("t") != "" && v.Type != get("t"):
			continue
		case get("wd") != "" && !strings.Contains(v.Name, get("wd")):
			continue
		}
		out = append(out, v)
	}
	return out
}

func paginate(vods []Vod, pg int, size int) ([]Vod, int) {
	pages := (len(vods) + size - 1) / size
	if pages == 0 {
		pages = 1
	}
	if pg < 1 {
		pg = 1
	}
	lo, hi := (pg-1)*size, pg*size
	if lo > len(vods) {
		lo = len(vods)
	}
	if hi > len(vods) {
		hi = len(vods)
	}
	return vods[lo:hi], pages
}

func (s *Server) cmsJSON(w http.ResponseWriter, r *http.Request, site *Site) {
	q := r.URL.Query()
	pg, _ := strconv.Atoi(q.Get("pg"))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	if q.Get("ac") == "" {
		// 首页:分类 + 最近更新
		list := []map[string]any{}
		for _, v := range site.Vods[:3] {
			list = append(list, s.vodJSON(site, v, false))
		}
		json.NewEncoder(w).Encode(map[string]any{"code": 1, "class": []map[string]any{{"type_id": "1", "type_name": "电影"}, {"type_id": "2", "type_name": "剧集"}}, "list": list})
		return
	}
	vods := filter(site, q)
	page, pages := paginate(vods, pg, 20)
	list := []map[string]any{}
	for _, v := range page {
		list = append(list, s.vodJSON(site, v, q.Get("ids") != ""))
	}
	json.NewEncoder(w).Encode(map[string]any{"code": 1, "page": max(pg, 1), "pagecount": pages, "limit": 20, "total": len(vods), "list": list})
}

func (s *Server) cmsXML(w http.ResponseWriter, r *http.Request, site *Site) {
	q := r.URL.Query()
	pg, _ := strconv.Atoi(q.Get("pg"))
	w.Header().Set("Content-Type", "text/xml; charset=utf-8")
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="utf-8"?><rss version="5.1">`)
	vods := site.Vods[:3]
	pages := 1
	if q.Get("ac") != "" {
		vods, pages = paginate(filter(site, q), pg, 20)
	} else {
		b.WriteString(`<class><ty id="1">电影</ty><ty id="2">剧集</ty></class>`)
	}
	fmt.Fprintf(&b, `<list page="%d" pagecount="%d" pagesize="20" recordcount="%d">`, max(pg, 1), pages, len(vods))
	for _, v := range vods {
		fmt.Fprintf(&b, `<video><last>2025-01-01</last><id>%s</id><tid>%s</tid><name><![CDATA[%s]]></name><type>x</type><pic>%s</pic><note><![CDATA[%s]]></note><year>%d</year>`,
			v.ID, v.Type, v.Name, s.Base+"/media/poster-"+v.ID+".jpg", v.Remarks, v.Year)
		if q.Get("ids") != "" {
			fmt.Fprintf(&b, `<des><![CDATA[%s]]></des><dl>`, v.Content)
			for li, l := range v.Lines {
				var eps []string
				for ei, e := range l.Episodes {
					eps = append(eps, e+"$"+s.playURL(site, v, li, ei))
				}
				fmt.Fprintf(&b, `<dd flag="%s"><![CDATA[%s]]></dd>`, l.Name, strings.Join(eps, "#"))
			}
			b.WriteString(`</dl>`)
		}
		b.WriteString(`</video>`)
	}
	b.WriteString(`</list></rss>`)
	w.Write([]byte(b.String()))
}

// ---------------------------------------------------------------- drpy 站(HTML)

// DrpyRule 假站的 drpy 规则。
func DrpyRule(base string) string {
	return `var rule = {
  title: '假站drpy',
  host: '` + base + `',
  url: '/drpy/list/fyclass-fypage.html',
  searchUrl: '/drpy/search?wd=**&pg=fypage',
  searchable: 2, quickSearch: 0, filterable: 0,
  class_name: '电影&剧集',
  class_url: '1&2',
  play_parse: true,
  lazy: 'js: let m = request(input).match(/player_aaaa=(.*?)<\\/script>/); input = m ? {parse: 0, url: JSON.parse(m[1]).url} : input',
  一级: '.item;a&&title;img&&data-src;.remark&&Text;a&&href',
  二级: {title: 'h1&&Text;.type&&Text', img: '.cover&&src', desc: '.remark&&Text;.year&&Text;.area&&Text;.actor&&Text;.director&&Text', content: '.content&&Text', tabs: '.tabs span', lists: '.playlist:eq(#id) a'},
  搜索: '.item;a&&title;img&&data-src;.remark&&Text;a&&href',
}`
}

func (s *Server) drpySite(w http.ResponseWriter, r *http.Request) {
	site := s.sites["a"]
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	p := strings.TrimPrefix(r.URL.Path, "/drpy/")
	item := func(v Vod) string {
		return fmt.Sprintf(`<li class="item"><a href="/drpy/vod/%s.html" title="%s"><img data-src="%s/media/poster-%s.jpg"></a><span class="remark">%s</span></li>`,
			v.ID, html.EscapeString(v.Name), s.Base, v.ID, html.EscapeString(v.Remarks))
	}
	switch {
	case strings.HasPrefix(p, "list/"):
		var tid string
		var pg int
		fmt.Sscanf(strings.TrimSuffix(strings.TrimPrefix(p, "list/"), ".html"), "%1s-%d", &tid, &pg)
		page, _ := paginate(filter(site, map[string][]string{"t": {tid}}), pg, 20)
		var b strings.Builder
		b.WriteString(`<html><body><ul class="list">`)
		for _, v := range page {
			b.WriteString(item(v))
		}
		b.WriteString(`</ul></body></html>`)
		w.Write([]byte(b.String()))
	case strings.HasPrefix(p, "search"):
		var b strings.Builder
		b.WriteString(`<html><body><ul>`)
		for _, v := range filter(site, map[string][]string{"wd": {r.URL.Query().Get("wd")}}) {
			b.WriteString(item(v))
		}
		b.WriteString(`</ul></body></html>`)
		w.Write([]byte(b.String()))
	case strings.HasPrefix(p, "vod/"):
		id := strings.TrimSuffix(strings.TrimPrefix(p, "vod/"), ".html")
		vs := filter(site, map[string][]string{"ids": {id}})
		if len(vs) == 0 {
			http.NotFound(w, r)
			return
		}
		v := vs[0]
		var b strings.Builder
		fmt.Fprintf(&b, `<html><body><h1>%s</h1><span class="type">剧情</span><img class="cover" src="%s/media/poster-%s.jpg"><span class="remark">%s</span><span class="year">%d</span><div class="content">%s</div><div class="tabs">`,
			html.EscapeString(v.Name), s.Base, v.ID, v.Remarks, v.Year, html.EscapeString(v.Content))
		for _, l := range v.Lines {
			fmt.Fprintf(&b, `<span>%s</span>`, l.Name)
		}
		b.WriteString(`</div>`)
		for li, l := range v.Lines {
			b.WriteString(`<div class="playlist">`)
			for ei, e := range l.Episodes {
				fmt.Fprintf(&b, `<a href="/drpy/play/%s-%d-%d.html">%s</a>`, v.ID, li, ei, e)
			}
			b.WriteString(`</div>`)
		}
		b.WriteString(`</body></html>`)
		w.Write([]byte(b.String()))
	case strings.HasPrefix(p, "play/"):
		var id string
		var li, ei int
		fmt.Sscanf(strings.ReplaceAll(strings.TrimSuffix(strings.TrimPrefix(p, "play/"), ".html"), "-", " "), "%s %d %d", &id, &li, &ei)
		// 苹果CMS 站典型的播放页:地址在 player_aaaa 里
		fmt.Fprintf(w, `<html><body><script>var player_aaaa={"url":"%s/media/drpy-%s-%d-%d.m3u8","from":"x"}</script></body></html>`, s.Base, id, li, ei)
	default:
		http.NotFound(w, r)
	}
}

// ---------------------------------------------------------------- T4 / 解析 / 媒体

func (s *Server) t4(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	site := s.sites["a"]
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	switch {
	case q.Get("play") != "":
		json.NewEncoder(w).Encode(map[string]any{"parse": 0, "url": q.Get("play"), "header": map[string]string{"Referer": s.Base + "/"}})
	case q.Get("ac") == "detail" || q.Get("ids") != "":
		var list []map[string]any
		for _, v := range filter(site, map[string][]string{"ids": {q.Get("ids")}}) {
			list = append(list, s.vodJSON(site, v, true))
		}
		json.NewEncoder(w).Encode(map[string]any{"list": list})
	case q.Get("wd") != "":
		var list []map[string]any
		for _, v := range filter(site, map[string][]string{"wd": {q.Get("wd")}}) {
			list = append(list, s.vodJSON(site, v, false))
		}
		json.NewEncoder(w).Encode(map[string]any{"list": list})
	case q.Get("t") != "":
		pg, _ := strconv.Atoi(q.Get("pg"))
		page, pages := paginate(filter(site, map[string][]string{"t": {q.Get("t")}}), pg, 20)
		var list []map[string]any
		for _, v := range page {
			list = append(list, s.vodJSON(site, v, false))
		}
		json.NewEncoder(w).Encode(map[string]any{"page": max(pg, 1), "pagecount": pages, "list": list})
	default:
		json.NewEncoder(w).Encode(map[string]any{"class": []map[string]any{{"type_id": "1", "type_name": "电影"}},
			"filters": map[string]any{"1": []map[string]any{{"key": "year", "name": "年份", "value": []map[string]string{{"n": "全部", "v": ""}, {"n": "2023", "v": "2023"}}}}},
			"list":    []map[string]any{s.vodJSON(site, site.Vods[0], false)}})
	}
}

// parser type 1 JSON 解析接口:网页地址 → 真实地址。
func (s *Server) parser(w http.ResponseWriter, r *http.Request) {
	page := r.URL.Query().Get("url")
	var id string
	var li, ei int
	fmt.Sscanf(strings.ReplaceAll(strings.TrimSuffix(page[strings.LastIndex(page, "/")+1:], ".html"), "-", " "), "%s %d %d", &id, &li, &ei)
	w.Header().Set("Content-Type", "application/json")
	if id == "" {
		json.NewEncoder(w).Encode(map[string]any{"code": 404, "msg": "解析失败"})
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"code": 200, "url": fmt.Sprintf("%s/media/jx-%s-%d-%d.m3u8", s.Base, id, li, ei)})
}

// media 所有播放地址都落到同一段测试视频(ffmpeg 生成);海报是一张 1x1 JPEG。
func (s *Server) media(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/media/")
	if strings.HasPrefix(name, "poster-") {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write(fakeJPEG())
		return
	}
	if s.mediaDir == "" {
		http.NotFound(w, r)
		return
	}
	if strings.HasSuffix(name, ".m3u8") {
		http.ServeFile(w, r, filepath.Join(s.mediaDir, "index.m3u8"))
		return
	}
	f := filepath.Join(s.mediaDir, filepath.Base(name))
	if _, err := os.Stat(f); err != nil {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, f)
}

// ---------------------------------------------------------------- 配置编码(照影视仓/FongMi 的解码顺序反着做)

// Base64Wrap 「8 位字母数字 + ** + base64」:图片尾部藏配置与 base64 前缀共用这一种形状。
func Base64Wrap(cfg string) string {
	return "abcd1234**" + base64.StdEncoding.EncodeToString([]byte(cfg))
}

func pad16(s string) []byte {
	b := []byte(s + "0000000000000000")
	return b[:16]
}

// CBCWrap 以 "2423" 开头的 AES-CBC 格式:hex(「$#key#$」) + hex(密文) + hex(iv 13 字节)。
func CBCWrap(cfg, key, iv string) string {
	blk, _ := aes.NewCipher(pad16(key))
	plain := pkcs7([]byte(cfg))
	out := make([]byte, len(plain))
	cipher.NewCBCEncrypter(blk, pad16(iv)).CryptBlocks(out, plain)
	ivField := (iv + "0000000000000")[:13]
	return hex.EncodeToString([]byte("$#"+key+"#$")) + hex.EncodeToString(out) + hex.EncodeToString([]byte(ivField))
}

// ECBWrap 地址里带 ;pk;密钥 的 AES-ECB 格式:hex(密文)。
func ECBWrap(cfg, key string) string {
	blk, _ := aes.NewCipher(pad16(key))
	plain := pkcs7([]byte(cfg))
	out := make([]byte, len(plain))
	for i := 0; i < len(plain); i += 16 {
		blk.Encrypt(out[i:i+16], plain[i:i+16])
	}
	return hex.EncodeToString(out)
}

func pkcs7(b []byte) []byte {
	p := 16 - len(b)%16
	for i := 0; i < p; i++ {
		b = append(b, byte(p))
	}
	return b
}

// fakeJPEG 最小的合法 JPEG 头(够图片解码器认出来,尾巴上可以再挂东西)。
func fakeJPEG() []byte {
	b, _ := base64.StdEncoding.DecodeString("/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=")
	return b
}
