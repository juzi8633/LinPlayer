package ranking

// Bangumi 榜(D362):公开浏览接口按排名排序,不需要密钥。
// 图片走 sync 那边同一个反代:官方图床 lain.bgm.tv 国内常不通(见 core/sync 的注释)。

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"time"

	"linplayer/core/httpx"
	lpsync "linplayer/core/sync"
)

type bgmSubject struct {
	ID     int64  `json:"id"`
	Name   string `json:"name"`
	NameCN string `json:"name_cn"`
	Date   string `json:"date"`
	Eps    int    `json:"eps"`
	Images struct {
		Common string `json:"common"`
		Large  string `json:"large"`
	} `json:"images"`
	Rating struct {
		Rank  int     `json:"rank"`
		Score float64 `json:"score"`
	} `json:"rating"`
}

// seasonStart 本季开播月:1/4/7/10(日本动画按季度开播)。
func seasonStart(now time.Time) (int, int) {
	m := int(now.Month())
	return now.Year(), (m-1)/3*3 + 1
}

func fetchBangumi(ctx context.Context, cat *Category, now time.Time) ([]Entry, error) {
	q := url.Values{"type": {"2"}, "sort": {"rank"}, "limit": {"30"}}
	if cat.BangumiSeason {
		y, m := seasonStart(now)
		q.Set("year", strconv.Itoa(y))
		q.Set("month", strconv.Itoa(m))
	}
	b, code, err := httpx.GetJSON(ctx, httpx.Client(), lpsync.BangumiAPIOfficial+"/v0/subjects?"+q.Encode(), nil)
	if err != nil {
		return nil, fmt.Errorf("Bangumi 连不上: %w", err)
	}
	if code != 200 {
		return nil, fmt.Errorf("Bangumi 返回 %d", code)
	}
	var r struct {
		Data []bgmSubject `json:"data"`
	}
	if err := json.Unmarshal(b, &r); err != nil {
		return nil, fmt.Errorf("Bangumi 返回的不是预期的 JSON: %w", err)
	}
	out := make([]Entry, 0, len(r.Data))
	for i, s := range r.Data {
		title := s.NameCN
		if title == "" {
			title = s.Name
		}
		e := Entry{Source: SourceBangumi, ID: strconv.FormatInt(s.ID, 10), Title: title, Rank: i + 1}
		if s.Rating.Score > 0 {
			sc := s.Rating.Score
			e.Rating = &sc
		}
		img := s.Images.Common
		if img == "" {
			img = s.Images.Large
		}
		e.ImageURL = lpsync.MirrorImage(img)
		if s.Date != "" {
			sub := s.Date
			if s.Eps > 0 {
				sub += fmt.Sprintf(" · %d 话", s.Eps)
			}
			e.Subtitle = &sub
		}
		out = append(out, e)
	}
	return out, nil
}
