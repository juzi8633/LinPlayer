package rt

// HTML 解析(D98,Go 实现):drpy 选择器 pdfh/pdfa/pd、CSS 选择器 DOM、XPath。

import (
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/PuerkitoBio/goquery"
	"github.com/antchfx/htmlquery"
	"github.com/dop251/goja"
	"golang.org/x/net/html"
)

var eqRe = regexp.MustCompile(`:eq\((-?\d+)\)`)

func parseDoc(src string) *goquery.Selection {
	d, err := goquery.NewDocumentFromReader(strings.NewReader(src))
	if err != nil {
		return &goquery.Selection{}
	}
	return d.Selection
}

// selectSeg 一段 drpy 选择器:jQuery 式 `:eq(n)` 可出现在段内任意位置(负数从尾数),其余交给 CSS。
func selectSeg(s *goquery.Selection, seg string) *goquery.Selection {
	seg = strings.TrimSpace(seg)
	for seg != "" {
		loc := eqRe.FindStringSubmatchIndex(seg)
		if loc == nil {
			return s.Find(seg)
		}
		if pre := strings.TrimSpace(seg[:loc[0]]); pre != "" {
			s = s.Find(pre)
		}
		n, _ := strconv.Atoi(seg[loc[2]:loc[3]])
		if n < 0 {
			n += s.Length()
		}
		s = s.Eq(n)
		seg = strings.TrimSpace(seg[loc[1]:])
	}
	return s
}

func selectPath(s *goquery.Selection, segs []string) *goquery.Selection {
	for _, seg := range segs {
		if strings.TrimSpace(seg) == "" {
			continue
		}
		s = selectSeg(s, seg)
	}
	return s
}

// Pdfa drpy 取列表:返回匹配元素的外层 HTML。
func Pdfa(src, rule string) []string {
	s := selectPath(parseDoc(src), strings.Split(rule, "&&"))
	out := make([]string, 0, s.Length())
	s.Each(func(_ int, e *goquery.Selection) {
		h, _ := goquery.OuterHtml(e)
		out = append(out, h)
	})
	return out
}

// Pdfh drpy 取单值:最后一段是取值项(Text / Html / outerHtml / 属性名),可 `||` 备选、`--` 排除子元素。
func Pdfh(src, rule, base string) string {
	segs := strings.Split(rule, "&&")
	opt := strings.TrimSpace(segs[len(segs)-1])
	var s *goquery.Selection
	if len(segs) == 1 {
		// 只有一段:整段当取值项还是选择器?drpy 约定 "body&&Text" 才算取值,单段按选择器取文本
		s = selectSeg(parseDoc(src), opt)
		opt = "Text"
	} else {
		s = selectPath(parseDoc(src), segs[:len(segs)-1]).First()
	}
	if s.Length() == 0 {
		return ""
	}
	excl := strings.Split(opt, "--")
	opt = excl[0]
	if len(excl) > 1 {
		s = s.Clone()
		for _, x := range excl[1:] {
			s.Find(x).Remove()
		}
	}
	for _, o := range strings.Split(opt, "||") {
		v := pick(s, strings.TrimSpace(o))
		if v != "" {
			if base != "" && isURLAttr(o) {
				return JoinURL(base, v)
			}
			return v
		}
	}
	return ""
}

func pick(s *goquery.Selection, opt string) string {
	switch opt {
	case "Text", "text":
		return strings.Join(strings.Fields(s.Text()), " ")
	case "Html", "html":
		h, _ := s.Html()
		return h
	case "outerHtml":
		h, _ := goquery.OuterHtml(s)
		return h
	}
	v, _ := s.Attr(opt)
	return strings.TrimSpace(v)
}

func isURLAttr(o string) bool {
	o = strings.ToLower(o)
	return strings.Contains(o, "href") || strings.Contains(o, "src") || strings.HasPrefix(o, "data-") || o == "url"
}

// Pd 取链接并补全为绝对地址。
func Pd(src, rule, base string) string {
	v := Pdfh(src, rule, "")
	if v == "" {
		return ""
	}
	return JoinURL(base, v)
}

// JoinURL 相对地址补全。
func JoinURL(base, ref string) string {
	b, err := url.Parse(strings.TrimSpace(base))
	if err != nil {
		return ref
	}
	r, err := url.Parse(strings.TrimSpace(ref))
	if err != nil {
		return ref
	}
	return b.ResolveReference(r).String()
}

// XPath 返回每个匹配节点的字符串值(属性取值、元素取文本)。
func XPath(src, expr string) ([]string, error) {
	doc, err := htmlquery.Parse(strings.NewReader(src))
	if err != nil {
		return nil, err
	}
	nodes, err := htmlquery.QueryAll(doc, expr)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(nodes))
	for _, n := range nodes {
		if n.Type == html.TextNode {
			out = append(out, n.Data)
			continue
		}
		out = append(out, strings.TrimSpace(htmlquery.InnerText(n)))
	}
	return out, nil
}

func (r *Runtime) installHTML(o *goja.Object) {
	vm := r.vm
	_ = o.Set("pdfh", func(src, rule string, base goja.Value) string {
		b := ""
		if !absent(base) {
			b = base.String()
		}
		return Pdfh(src, rule, b)
	})
	_ = o.Set("pdfa", func(src, rule string) []string { return Pdfa(src, rule) })
	_ = o.Set("pd", func(src, rule, base string) string { return Pd(src, rule, base) })
	_ = o.Set("xpath", func(src, expr string) []string {
		out, err := XPath(src, expr)
		if err != nil {
			r.throw(KindInvalid, "XPath 表达式不对:"+err.Error())
		}
		return out
	})
	_ = o.Set("parse", func(src string) goja.Value { return r.htmlNode(parseDoc(src)) })
	_ = vm
}

// htmlNode CSS 选择器 DOM 的节点包装。
func (r *Runtime) htmlNode(s *goquery.Selection) goja.Value {
	vm := r.vm
	o := vm.NewObject()
	_ = o.Set("text", func() string { return s.Text() })
	_ = o.Set("html", func() string { h, _ := s.Html(); return h })
	_ = o.Set("outerHtml", func() string { h, _ := goquery.OuterHtml(s); return h })
	_ = o.Set("attr", func(name string) goja.Value {
		if v, ok := s.Attr(name); ok {
			return vm.ToValue(v)
		}
		return goja.Undefined()
	})
	_ = o.Set("querySelector", func(sel string) goja.Value {
		f := s.Find(sel).First()
		if f.Length() == 0 {
			return goja.Null()
		}
		return r.htmlNode(f)
	})
	_ = o.Set("querySelectorAll", func(sel string) goja.Value {
		var out []any
		s.Find(sel).Each(func(_ int, e *goquery.Selection) { out = append(out, r.htmlNode(e)) })
		return vm.ToValue(out)
	})
	return o
}
