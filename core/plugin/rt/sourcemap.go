package rt

// 报错栈映射回 TS 行号(SPEC 16.5,D81)。
//
// esbuild 把整个插件打成一个 `main.js`,所以栈里全是
// `main.js:1:2931` 这种位置 —— 对作者毫无用处,他写的是 `src/panel.tsx:88`。
// sourcemap 一直产出来、也打进包,但**没有任何消费方**:产物在、功能不在。

import (
	"encoding/json"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

// sourceMap 一份解开的 sourcemap:按生成行存好的段。
type sourceMap struct {
	sources []string
	// lines[生成行] = 该行上的段,按生成列升序
	lines [][]smSeg
}

type smSeg struct {
	genCol int
	srcIdx int
	srcLn  int
	srcCol int
}

// vlqB64 base64 变长量的字符表。
const vlqChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

var vlqIndex = func() map[byte]int {
	m := map[byte]int{}
	for i := 0; i < len(vlqChars); i++ {
		m[vlqChars[i]] = i
	}
	return m
}()

// decodeVLQ 解一段 base64 VLQ。返回解出来的整数与消耗的字节数。
func decodeVLQ(s string) ([]int, bool) {
	out := []int{}
	shift, value := 0, 0
	for i := 0; i < len(s); i++ {
		d, ok := vlqIndex[s[i]]
		if !ok {
			return nil, false
		}
		cont := d&32 != 0
		value += (d & 31) << shift
		if cont {
			shift += 5
			continue
		}
		neg := value&1 == 1
		v := value >> 1
		if neg {
			v = -v
		}
		out = append(out, v)
		shift, value = 0, 0
	}
	return out, true
}

// parseSourceMap 解一份 sourcemap 的 mappings。
//
// 只解「生成行列 → 源文件行列」四元组,names 那一位不要:
// 报错栈要的是位置,函数名栈里本来就有。
func parseSourceMap(raw []byte) *sourceMap {
	var j struct {
		Sources  []string `json:"sources"`
		Mappings string   `json:"mappings"`
	}
	if json.Unmarshal(raw, &j) != nil || j.Mappings == "" {
		return nil
	}
	sm := &sourceMap{sources: j.Sources}
	srcIdx, srcLn, srcCol := 0, 0, 0
	for _, line := range strings.Split(j.Mappings, ";") {
		var segs []smSeg
		genCol := 0
		for _, seg := range strings.Split(line, ",") {
			if seg == "" {
				continue
			}
			v, ok := decodeVLQ(seg)
			if !ok || len(v) == 0 {
				continue
			}
			genCol += v[0]
			if len(v) >= 4 {
				srcIdx += v[1]
				srcLn += v[2]
				srcCol += v[3]
				segs = append(segs, smSeg{genCol: genCol, srcIdx: srcIdx, srcLn: srcLn, srcCol: srcCol})
			}
		}
		sm.lines = append(sm.lines, segs)
	}
	return sm
}

// lookup 生成位置(1 基行、1 基列)→ 源位置。找不到返回空串。
func (m *sourceMap) lookup(line, col int) (string, int, int) {
	if m == nil || line < 1 || line > len(m.lines) {
		return "", 0, 0
	}
	segs := m.lines[line-1]
	if len(segs) == 0 {
		return "", 0, 0
	}
	// 取**不超过**目标列的最后一段:段记的是「从这一列开始」
	best := -1
	for i, s := range segs {
		if s.genCol <= col-1 {
			best = i
		}
	}
	if best < 0 {
		best = 0
	}
	s := segs[best]
	if s.srcIdx < 0 || s.srcIdx >= len(m.sources) {
		return "", 0, 0
	}
	return m.sources[s.srcIdx], s.srcLn + 1, s.srcCol + 1
}

// stackPos 匹配栈里的 `文件:行:列`。goja 的栈形如 `at foo (main.js:1:2931)`。
var stackPos = regexp.MustCompile(`([\w./\\-]+\.js):(\d+):(\d+)`)

// MapStack 把栈里的生成位置换成源位置。没有 sourcemap 就原样返回。
//
// ☠ 换掉而不是追加:两份位置并排的话作者第一眼看到的还是 `main.js:1:2931`,
//   而那正是这件事要消除的东西。源文件名留在括号里,对得上包里的路径。
func (r *Runtime) MapStack(stack string) string {
	sm := r.sourceMap()
	if sm == nil || stack == "" {
		return stack
	}
	return stackPos.ReplaceAllStringFunc(stack, func(m string) string {
		p := stackPos.FindStringSubmatch(m)
		ln, _ := strconv.Atoi(p[2])
		col, _ := strconv.Atoi(p[3])
		src, sl, sc := sm.lookup(ln, col)
		if src == "" {
			return m
		}
		return strings.TrimPrefix(src, "../") + ":" + strconv.Itoa(sl) + ":" + strconv.Itoa(sc)
	})
}

var smOnce sync.Map // 运行时 → *sourceMap(解一次就够,mappings 上万段)

func (r *Runtime) sourceMap() *sourceMap {
	if v, ok := smOnce.Load(r); ok {
		sm, _ := v.(*sourceMap)
		return sm
	}
	var sm *sourceMap
	if len(r.opt.SourceMap) > 0 {
		sm = parseSourceMap(r.opt.SourceMap)
	}
	smOnce.Store(r, sm)
	return sm
}
