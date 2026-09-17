package aggregate

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"linplayer/core/config"
)

// film 假服上的一部片。
type film struct {
	id, name, typ, tmdb string
}

func (f film) json() string {
	b, _ := json.Marshal(map[string]any{
		"Id": f.id, "Name": f.name, "Type": f.typ,
		"ProviderIds": map[string]string{"Tmdb": f.tmdb},
	})
	return string(b)
}

/*
newBackend 照 2026-09-17 实测造一台假服:

  - SearchTerm 只按**词前缀**匹配 Name(4.10 那台:「V字仇杀队」搜「仇杀队」= 0 条);
  - AnyProviderIdEquals 真过滤(两台真服都是);ignoreProvider=true 模拟不认这个参数、回整个库的服。
*/
func newBackend(t *testing.T, films []film, ignoreProvider bool) *httptest.Server {
	t.Helper()
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		var out []string
		switch {
		case strings.HasSuffix(r.URL.Path, "/PlaybackInfo"):
			id := strings.Split(r.URL.Path, "/")[2]
			_, _ = w.Write([]byte(`{"MediaSources":[{"Id":"ms-` + id + `","Name":"1080p","MediaStreams":[]}]}`))
			return
		case q.Get("SearchTerm") != "":
			for _, f := range films {
				if strings.HasPrefix(f.name, q.Get("SearchTerm")) {
					out = append(out, f.json())
				}
			}
		case q.Get("AnyProviderIdEquals") != "":
			keys := strings.Split(q.Get("AnyProviderIdEquals"), ",")
			for _, f := range films {
				hit := ignoreProvider
				for _, k := range keys {
					hit = hit || k == "tmdb."+f.tmdb
				}
				if hit && strings.Contains(q.Get("IncludeItemTypes"), f.typ) {
					out = append(out, f.json())
				}
			}
		default: // 单条(ItemForHistory)
			id := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
			for _, f := range films {
				if f.id == id {
					_, _ = w.Write([]byte(f.json()))
					return
				}
			}
			w.WriteHeader(404)
			return
		}
		_, _ = w.Write([]byte(`{"Items":[` + strings.Join(out, ",") + `],"TotalRecordCount":` +
			strconv.Itoa(len(out)) + `}`))
	}))
	t.Cleanup(s.Close)
	return s
}

func addServer(t *testing.T, c *config.AppConfig, url, name string) {
	t.Helper()
	c.Upsert(config.Account{Server: url, Token: "t", UserID: "u", Name: name})
	if err := c.Save(); err != nil {
		t.Fatal(err)
	}
}

func idsByServer(groups []ServerGroup) map[string]string {
	out := map[string]string{}
	for _, g := range groups {
		var ids []string
		for _, it := range g.Items {
			ids = append(ids, it.ID)
		}
		out[g.ServerName] = strings.Join(ids, ",")
	}
	return out
}

// 用户 2026-09-17:「使用聚合视界的时候会聚合不到别的剧集」。
// 同一部片两台服译名不同:只靠各服自己搜,乙台永远出不来。
func TestAggregateSearch译名不同靠TMDB补(t *testing.T) {
	c := setup(t)
	a := newBackend(t, []film{{"a1", "V字仇杀队", "Movie", "752"}, {"a2", "无关的片", "Movie", "1"}}, false)
	b := newBackend(t, []film{
		{"b1", "V煞", "Movie", "752"},    // 另一个译名
		{"b2", "某剧", "Series", "752"},  // 剧的 TMDB 和电影同号 —— 两套编号,不算
		{"b3", "V字头的剧", "Series", "9"}, // 乙台自己按名字搜到的照出
	}, false)
	addServer(t, c, a.URL, "甲")
	addServer(t, c, b.URL, "乙")

	var groups []ServerGroup
	call(t, 301, "emby.aggregateSearch", map[string]any{"query": "V字"}, &groups)
	got := idsByServer(groups)
	if got["甲"] != "a1" || got["乙"] != "b3,b1" {
		t.Fatalf("甲该是 a1,乙该是 b3(自己搜到)+ b1(按 TMDB 补),实得 %v", got)
	}
}

// 不认 AnyProviderIdEquals 的服会回整个库:不在本地再滤一遍,那台服的全部片子都成了「同一部」。
func TestAggregateSearch不认TMDB参数的服不许灌进来(t *testing.T) {
	c := setup(t)
	a := newBackend(t, []film{{"a1", "V字仇杀队", "Movie", "752"}}, false)
	b := newBackend(t, []film{{"b1", "V煞", "Movie", "752"}, {"b2", "别的电影", "Movie", "5"}}, true)
	addServer(t, c, a.URL, "甲")
	addServer(t, c, b.URL, "乙")

	var groups []ServerGroup
	call(t, 302, "emby.aggregateSearch", map[string]any{"query": "V字"}, &groups)
	if got := idsByServer(groups); got["乙"] != "b1" {
		t.Fatalf("乙台只该补 b1,实得 %q", got["乙"])
	}
}

// 跨服版本:电影在两台服上译名不同、TMDB 相同,要认成同一部。
func TestAggregateVersions电影译名不同靠TMDB(t *testing.T) {
	c := setup(t)
	a := newBackend(t, []film{{"a1", "V字仇杀队", "Movie", "752"}}, false)
	b := newBackend(t, []film{{"b1", "V煞", "Movie", "752"}}, false)
	addServer(t, c, a.URL, "甲")
	addServer(t, c, b.URL, "乙")

	var groups []VersionGroup
	call(t, 303, "emby.aggregateVersions", map[string]any{"item_id": "a1", "server_id": a.URL}, &groups)
	if len(groups) != 2 || groups[1].ItemID != "b1" {
		t.Fatalf("乙台的 V煞 该认成同一部,实得 %+v", groups)
	}
}
