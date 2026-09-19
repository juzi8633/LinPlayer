package plugin

// 市场与仓库订阅(SPEC 14.2~14.3,D28 D118 D119 D370 D371 D450)。
//
// 一个仓库 = 任何静态托管上的一个 index.json;官方市场是预装、不可删的默认订阅。
// 客户端不直连 GitHub API(未认证 60 次/小时/IP),只拉静态文件。

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"linplayer/core/httpx"
)

// officialMarket 官方市场 index.json 地址,编译期注入(-X linplayer/core/plugin.officialMarket=…,
// 见 core/cmd/sealsecrets)。地址不进仓库;没注入 = 这个构建没有官方市场,只有用户订阅的仓库。
var officialMarket string

// OfficialMarketURL 官方市场地址(可能为空)。
func OfficialMarketURL() string { return strings.TrimSpace(officialMarket) }

// IndexVersion index.json 里一个版本。
type IndexVersion struct {
	Version       string   `json:"version"`
	URL           string   `json:"url"`
	Size          int64    `json:"size"`
	MinAppVersion string   `json:"minAppVersion"`
	Platforms     []string `json:"platforms,omitempty"`
	Released      string   `json:"released"`
	Changelog     string   `json:"changelog,omitempty"`
	ContribDiff   []string `json:"contributesDiff,omitempty"`
}

// IndexEntry index.json 里一个插件。
type IndexEntry struct {
	ID          string          `json:"id"`
	Name        string          `json:"name"`
	Description string          `json:"description"`
	I18n        json.RawMessage `json:"i18n,omitempty"`
	Author      string          `json:"author,omitempty"`
	Repository  string          `json:"repository,omitempty"`
	Icon        string          `json:"icon,omitempty"`
	IconMono    string          `json:"iconMono,omitempty"`
	Screenshots []string        `json:"screenshots,omitempty"`
	Readme      string          `json:"readme,omitempty"`
	Paid        bool            `json:"paid,omitempty"`
	Requires    json.RawMessage `json:"requires,omitempty"`
	LAN         bool            `json:"lan,omitempty"`
	Contributes []string        `json:"contributes,omitempty"`
	Categories  []string        `json:"categories,omitempty"`
	Stars       *int            `json:"stars,omitempty"`
	Downloads   *int            `json:"downloads,omitempty"`
	Official    bool            `json:"official,omitempty"`
	AddedAt     string          `json:"addedAt,omitempty"`
	Versions    []IndexVersion  `json:"versions"`
}

type indexFile struct {
	Name    string       `json:"name"`
	Updated string       `json:"updated"`
	Plugins []IndexEntry `json:"plugins"`
}

// MarketEntry 合并后的市场条目:多个仓库里同 id 合成一条(D118)。
type MarketEntry struct {
	IndexEntry
	Repo          string         `json:"repo"`     // 取用的来源仓库
	RepoName      string         `json:"repoName"` //
	Sources       []MarketSource `json:"sources"`  // 各来源(展开看)
	Best          *IndexVersion  `json:"best"`     // 本机能装的最高版本(D450)
	NeedsUpgrade  bool           `json:"needsUpgrade"`
	Installed     string         `json:"installed,omitempty"`
	InstalledFrom string         `json:"installedFrom,omitempty"`
	OtherSource   bool           `json:"otherSource"` // 已从别的来源装过(D48)
	OfficialMark  bool           `json:"officialMark"`
}

// MarketSource 一个来源仓库里这个插件的最新版。
type MarketSource struct {
	Repo     string `json:"repo"`
	RepoName string `json:"repoName"`
	Latest   string `json:"latest"`
}

// Market 市场快照。
type Market struct {
	Entries []MarketEntry     `json:"entries"`
	Stale   map[string]int    `json:"stale"`  // 拉失败、用的是缓存的仓库 → 缓存几天前的(D371)
	Errors  map[string]string `json:"errors"` // 连缓存都没有的仓库 → 原因
	Repos   []RepoInfo        `json:"repos"`
}

// RepoInfo 仓库订阅一行。
type RepoInfo struct {
	URL      string `json:"url"`
	Name     string `json:"name"`
	Official bool   `json:"official"`
}

type cachedIndex struct {
	At    int64     `json:"at"`
	Index indexFile `json:"index"`
}

func indexCachePath(repo string) string {
	s := sha1.Sum([]byte(repo))
	return filepath.Join(root(), "index-cache", hex.EncodeToString(s[:8])+".json")
}

// Repos 全部订阅(官方在前,不可删)。
func (h *Host) Repos() []RepoInfo {
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []RepoInfo
	if u := OfficialMarketURL(); u != "" {
		out = append(out, RepoInfo{URL: u, Name: "官方市场", Official: true})
	}
	for _, u := range h.st.Repos {
		name := u
		var c cachedIndex
		if readJSON(indexCachePath(u), &c) == nil && c.Index.Name != "" {
			name = c.Index.Name
		}
		out = append(out, RepoInfo{URL: u, Name: name})
	}
	return out
}

// AddRepo 订阅一个仓库:先拉一次、校验通过才记下。
func (h *Host) AddRepo(ctx context.Context, u string) (*RepoInfo, error) {
	u = strings.TrimSpace(u)
	if !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
		return nil, errors.New("仓库地址必须是 http(s) 开头的 index.json 地址")
	}
	if u == OfficialMarketURL() {
		return nil, errors.New("官方市场已经在列表里了")
	}
	idx, err := h.fetchIndex(ctx, u)
	if err != nil {
		return nil, err
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, r := range h.st.Repos {
		if r == u {
			return nil, errors.New("已经订阅过这个仓库")
		}
	}
	h.st.Repos = append(h.st.Repos, u)
	h.saveLocked()
	return &RepoInfo{URL: u, Name: idx.Name}, nil
}

// RemoveRepo 取消订阅。官方市场不可删(D118)。已装插件照常用。
func (h *Host) RemoveRepo(u string) error {
	if u == OfficialMarketURL() {
		return errors.New("官方市场不能删除")
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	kept := h.st.Repos[:0]
	for _, r := range h.st.Repos {
		if r != u {
			kept = append(kept, r)
		}
	}
	h.st.Repos = kept
	h.saveLocked()
	_ = os.Remove(indexCachePath(u))
	return nil
}

// SetGithubPrefix / SetAutoUpdate 仓库标签页的两个设置(D370 D49)。
func (h *Host) SetGithubPrefix(p string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.st.GithubPrefix = strings.TrimSpace(p)
	h.saveLocked()
}

func (h *Host) SetAutoUpdate(on bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.st.AutoUpdate = on
	h.saveLocked()
}

// RepoSettings 仓库页的设置当前值。
func (h *Host) RepoSettings() (prefix string, autoUpdate bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.st.GithubPrefix, h.st.AutoUpdate
}

// get 拉一个文件:直连优先,失败时套用户填的 GitHub 加速前缀再试(D370)。不内置任何镜像。
func (h *Host) download(ctx context.Context, u string, limit int64) ([]byte, error) {
	b, err := httpGet(ctx, u, limit)
	if err == nil {
		return b, nil
	}
	h.mu.Lock()
	prefix := h.st.GithubPrefix
	h.mu.Unlock()
	if prefix != "" && isGithubURL(u) {
		if b, err2 := httpGet(ctx, strings.TrimRight(prefix, "/")+"/"+u, limit); err2 == nil {
			return b, nil
		}
	}
	return nil, err
}

func isGithubURL(u string) bool {
	u = strings.ToLower(u)
	return strings.Contains(u, "github.com/") || strings.Contains(u, "githubusercontent.com/")
}

func httpGet(ctx context.Context, u string, limit int64) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := httpx.Client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("连不上 %s: %v", u, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s 返回 %d", u, resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > limit {
		return nil, errors.New("文件太大")
	}
	return b, nil
}

func (h *Host) fetchIndex(ctx context.Context, u string) (*indexFile, error) {
	b, err := h.download(ctx, u, 32<<20)
	if err != nil {
		return nil, err
	}
	if err := ValidateIndex(b); err != nil {
		return nil, err
	}
	var idx indexFile
	if err := json.Unmarshal(b, &idx); err != nil {
		return nil, err
	}
	_ = writeJSON(indexCachePath(u), cachedIndex{At: time.Now().Unix(), Index: idx})
	return &idx, nil
}

// indexes 拉全部仓库;拉失败用缓存并标「N 天前的」(D371)。refresh=false 时有缓存就用缓存。
func (h *Host) indexes(ctx context.Context, refresh bool) (map[string]*indexFile, map[string]int, map[string]string) {
	repos := h.Repos()
	out := map[string]*indexFile{}
	stale := map[string]int{}
	errs := map[string]string{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	for _, r := range repos {
		wg.Add(1)
		go func(u string) {
			defer wg.Done()
			var c cachedIndex
			hasCache := readJSON(indexCachePath(u), &c) == nil
			if hasCache && !refresh && time.Since(time.Unix(c.At, 0)) < 6*time.Hour {
				mu.Lock()
				out[u] = &c.Index
				mu.Unlock()
				return
			}
			idx, err := h.fetchIndex(ctx, u)
			mu.Lock()
			defer mu.Unlock()
			switch {
			case err == nil:
				out[u] = idx
			case hasCache:
				out[u] = &c.Index
				stale[u] = int(time.Since(time.Unix(c.At, 0)).Hours() / 24)
			default:
				errs[u] = err.Error()
			}
		}(r.URL)
	}
	wg.Wait()
	return out, stale, errs
}

// bestVersion 本机能装的最高版本(minAppVersion 与平台都满足)。
func (h *Host) bestVersion(e IndexEntry) (*IndexVersion, bool) {
	var best *IndexVersion
	anyVer := len(e.Versions) > 0
	for i := range e.Versions {
		v := &e.Versions[i]
		if h.version != "" && CompareVersions(appCore(h.version), v.MinAppVersion) < 0 {
			continue
		}
		if len(v.Platforms) > 0 && h.platform != "" && !contains(v.Platforms, h.platform) {
			continue
		}
		if best == nil || CompareVersions(v.Version, best.Version) > 0 {
			best = v
		}
	}
	return best, anyVer && best == nil
}

func contains(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

// Market 合并全部仓库(显示最高版本、官方来源优先),按当前平台过滤(D118 D138)。
func (h *Host) Market(ctx context.Context, refresh bool) *Market {
	idx, stale, errs := h.indexes(ctx, refresh)
	repos := h.Repos()
	names := map[string]string{}
	for _, r := range repos {
		names[r.URL] = r.Name
		if i := idx[r.URL]; i != nil && i.Name != "" && !r.Official {
			names[r.URL] = i.Name
		}
	}
	official := OfficialMarketURL()
	byID := map[string]*MarketEntry{}
	var order []string
	for _, r := range repos { // repos 官方在前:同 id 官方来源优先
		i := idx[r.URL]
		if i == nil {
			continue
		}
		for _, e := range i.Plugins {
			if !platformOK(e, h.platform) {
				continue
			}
			latest := ""
			for _, v := range e.Versions {
				if latest == "" || CompareVersions(v.Version, latest) > 0 {
					latest = v.Version
				}
			}
			src := MarketSource{Repo: r.URL, RepoName: names[r.URL], Latest: latest}
			if cur := byID[e.ID]; cur != nil {
				cur.Sources = append(cur.Sources, src)
				continue
			}
			me := &MarketEntry{IndexEntry: e, Repo: r.URL, RepoName: names[r.URL], Sources: []MarketSource{src}}
			me.Best, me.NeedsUpgrade = h.bestVersion(e)
			me.OfficialMark = r.URL == official && strings.HasPrefix(e.ID, OfficialPrefix)
			byID[e.ID] = me
			order = append(order, e.ID)
		}
	}
	h.mu.Lock()
	for _, id := range order {
		if rec := h.record(id); rec != nil {
			me := byID[id]
			me.Installed, me.InstalledFrom = rec.Version, rec.Source
			me.OtherSource = rec.Source != me.Repo
		}
	}
	h.mu.Unlock()
	out := &Market{Entries: []MarketEntry{}, Stale: stale, Errors: errs, Repos: repos}
	for _, id := range order {
		out.Entries = append(out.Entries, *byID[id])
	}
	sort.SliceStable(out.Entries, func(i, j int) bool { return out.Entries[i].Name < out.Entries[j].Name })
	return out
}

func platformOK(e IndexEntry, platform string) bool {
	if platform == "" {
		return true
	}
	for _, v := range e.Versions {
		if len(v.Platforms) == 0 || contains(v.Platforms, platform) {
			return true
		}
	}
	return false
}

// InstallFromRepo 从某个仓库装某个插件(version 空 = 能装的最高版)。
func (h *Host) InstallFromRepo(ctx context.Context, repo, id, version string) (*Manifest, error) {
	idx, _, errs := h.indexes(ctx, false)
	i := idx[repo]
	if i == nil {
		if e := errs[repo]; e != "" {
			return nil, errors.New(e)
		}
		return nil, errors.New("没有订阅这个仓库")
	}
	var entry *IndexEntry
	for k := range i.Plugins {
		if i.Plugins[k].ID == id {
			entry = &i.Plugins[k]
		}
	}
	if entry == nil {
		return nil, errors.New("仓库里没有这个插件")
	}
	var v *IndexVersion
	if version == "" {
		var up bool
		if v, up = h.bestVersion(*entry); up {
			return nil, errors.New("需要升级 LinPlayer 才能安装这个插件")
		}
	} else {
		for k := range entry.Versions {
			if entry.Versions[k].Version == version {
				v = &entry.Versions[k]
			}
		}
	}
	if v == nil {
		return nil, errors.New("没有可安装的版本")
	}
	b, err := h.download(ctx, v.URL, 2<<30)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(root(), 0o755); err != nil {
		return nil, err
	}
	f, err := os.CreateTemp(root(), ".dl-*.lpplugin")
	if err != nil {
		return nil, err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if _, err := f.Write(b); err != nil {
		f.Close()
		return nil, err
	}
	f.Close()
	m, err := h.Install(tmp, repo)
	if err != nil {
		return nil, err
	}
	if m.ID != id {
		return nil, fmt.Errorf("仓库里的包 id 是 %s,和条目 %s 对不上", m.ID, id)
	}
	if version != "" {
		// 装了指定的旧版本:锁定不自动更新,直到用户解锁(D448)
		if best, _ := h.bestVersion(*entry); best != nil && CompareVersions(version, best.Version) < 0 {
			h.SetLocked(id, true)
		}
	}
	return m, nil
}

// Updates 已装插件在其安装来源里的可更新版本(D48 D450)。
func (h *Host) Updates(ctx context.Context, refresh bool) map[string]string {
	idx, _, _ := h.indexes(ctx, refresh)
	out := map[string]string{}
	h.mu.Lock()
	recs := append([]*Record(nil), h.st.Plugins...)
	h.mu.Unlock()
	for _, r := range recs {
		i := idx[r.Source]
		if i == nil {
			continue
		}
		for _, e := range i.Plugins {
			if e.ID != r.ID {
				continue
			}
			if best, _ := h.bestVersion(e); best != nil && CompareVersions(best.Version, r.Version) > 0 {
				out[r.ID] = best.Version
			}
		}
	}
	return out
}

// UpdateAll 并发下载全部可更新的插件;返回装好的与失败的(失败单独列出,SPEC 14.4)。
func (h *Host) UpdateAll(ctx context.Context, only []string) (ok []string, failed map[string]string) {
	ups := h.Updates(ctx, true)
	failed = map[string]string{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	for id, ver := range ups {
		if len(only) > 0 && !contains(only, id) {
			continue
		}
		h.mu.Lock()
		r := h.record(id)
		skip := r == nil || r.Locked || r.Skip == ver
		src := ""
		if r != nil {
			src = r.Source
		}
		h.mu.Unlock()
		if skip {
			continue
		}
		wg.Add(1)
		go func(id, ver, src string) {
			defer wg.Done()
			_, err := h.InstallFromRepo(ctx, src, id, ver)
			mu.Lock()
			defer mu.Unlock()
			if err != nil {
				failed[id] = err.Error()
			} else {
				ok = append(ok, id)
			}
		}(id, ver, src)
	}
	wg.Wait()
	sort.Strings(ok)
	return ok, failed
}

// AutoUpdateOnStart 自动更新打开时:启动时检查并静默下载,下次启动生效(D540)。
// 新版带来新接管位或新声明 lan 时暂停,等用户确认(D446)。
func (h *Host) AutoUpdateOnStart(ctx context.Context) {
	_, auto := h.RepoSettings()
	if !auto {
		return
	}
	idx, _, _ := h.indexes(ctx, true)
	var ids []string
	h.mu.Lock()
	for _, r := range h.st.Plugins {
		i := idx[r.Source]
		if i == nil || r.Locked {
			continue
		}
		for _, e := range i.Plugins {
			if e.ID != r.ID {
				continue
			}
			best, _ := h.bestVersion(e)
			if best == nil || CompareVersions(best.Version, r.Version) <= 0 || best.Version == r.Skip {
				continue
			}
			if len(best.ContribDiff) > 0 {
				continue // 有新增贡献点的更新要用户看过再装
			}
			ids = append(ids, r.ID)
		}
	}
	h.mu.Unlock()
	if len(ids) > 0 {
		h.UpdateAll(ctx, ids)
	}
}
