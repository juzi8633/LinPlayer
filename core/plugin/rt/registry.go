package rt

// 宿主注册表通道(SPEC 5.6,D237 D272 D273):推模式,写入者交给宿主落盘,读者随时读。
// 全进程一份,跨插件共享;宿主定义的通道(live.*)格式校验,插件命名通道必须以自己 id 为前缀。

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/dop251/goja"

	"linplayer/core/paths"
)

type regEntry struct {
	Writer string          `json:"writer"`
	Key    string          `json:"key"`
	Value  json.RawMessage `json:"value"`
}

var reg = struct {
	sync.Mutex
	loaded   bool
	chans    map[string]map[string]regEntry // 通道 → writer\x00key → 条目
	watchers map[string]map[int64]func()
	seq      int64
}{chans: map[string]map[string]regEntry{}, watchers: map[string]map[int64]func(){}}

// hostChannels 宿主定义的通道与必需字段。
var hostChannels = map[string][]string{
	"live.channels": {"kind", "name"},
	"live.epg":      {"kind"},
}

func regFile() string { return filepath.Join(paths.Root(), "plugins", "registry.json") }

func regLoad() {
	if reg.loaded {
		return
	}
	reg.loaded = true
	b, err := os.ReadFile(regFile())
	if err != nil {
		return
	}
	var all map[string][]regEntry
	if json.Unmarshal(b, &all) != nil {
		return
	}
	for ch, es := range all {
		m := map[string]regEntry{}
		for _, e := range es {
			m[e.Writer+"\x00"+e.Key] = e
		}
		reg.chans[ch] = m
	}
}

func regSave() {
	all := map[string][]regEntry{}
	for ch, m := range reg.chans {
		for _, e := range m {
			all[ch] = append(all[ch], e)
		}
	}
	b, _ := json.Marshal(all)
	_ = writeFileAtomic(regFile(), b)
}

func regNotify(ch string) {
	for _, f := range reg.watchers[ch] {
		go f()
	}
}

// RegistryPut 写入(宿主内部也用)。
func RegistryPut(writer, ch, key string, v json.RawMessage) error {
	if err := checkChannel(writer, ch, v); err != nil {
		return err
	}
	reg.Lock()
	defer reg.Unlock()
	regLoad()
	if reg.chans[ch] == nil {
		reg.chans[ch] = map[string]regEntry{}
	}
	reg.chans[ch][writer+"\x00"+key] = regEntry{Writer: writer, Key: key, Value: v}
	regSave()
	regNotify(ch)
	return nil
}

func registryRemove(writer, ch, key string) {
	reg.Lock()
	defer reg.Unlock()
	regLoad()
	if _, ok := reg.chans[ch][writer+"\x00"+key]; ok {
		delete(reg.chans[ch], writer+"\x00"+key)
		regSave()
		regNotify(ch)
	}
}

// RegistryList 读一个通道的全部条目(按写入者、键排序)。
func RegistryList(ch string) []regEntry {
	reg.Lock()
	defer reg.Unlock()
	regLoad()
	out := make([]regEntry, 0, len(reg.chans[ch]))
	for _, e := range reg.chans[ch] {
		out = append(out, e)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Writer != out[j].Writer {
			return out[i].Writer < out[j].Writer
		}
		return out[i].Key < out[j].Key
	})
	return out
}

// RegistryClearWriter 写入者被禁用/卸载时清掉它写的全部条目(D272)。
func RegistryClearWriter(writer string) {
	reg.Lock()
	defer reg.Unlock()
	regLoad()
	changed := false
	for ch, m := range reg.chans {
		for k, e := range m {
			if e.Writer == writer {
				delete(m, k)
				changed = true
				regNotify(ch)
			}
		}
	}
	if changed {
		regSave()
	}
}

func checkChannel(writer, ch string, v json.RawMessage) error {
	if req, ok := hostChannels[ch]; ok {
		var o map[string]any
		if json.Unmarshal(v, &o) != nil {
			return errors.New("通道 " + ch + " 的值必须是对象")
		}
		for _, f := range req {
			if _, has := o[f]; !has {
				return errors.New("通道 " + ch + " 的值缺少字段 " + f)
			}
		}
		return nil
	}
	if !strings.HasPrefix(ch, writer+"/") {
		return errors.New("插件命名通道必须以自己的 id 为前缀:" + writer + "/…")
	}
	return nil
}

func (r *Runtime) installRegistry(o *goja.Object) {
	vm := r.vm
	id := r.opt.ID
	_ = o.Set("put", func(ch, key string, v goja.Value) {
		s, err := r.stringify(v)
		if err != nil {
			r.throw(KindInvalid, "注册表的值不能序列化:"+err.Error())
		}
		if err := RegistryPut(id, ch, key, json.RawMessage(s)); err != nil {
			r.throw(KindInvalid, err.Error())
		}
	})
	_ = o.Set("remove", func(ch, key string) { registryRemove(id, ch, key) })
	_ = o.Set("list", func(ch string) goja.Value {
		es := RegistryList(ch)
		return r.jsValue(es)
	})
	_ = o.Set("watch", func(ch string, cb goja.Callable) goja.Value {
		reg.Lock()
		reg.seq++
		wid := reg.seq
		if reg.watchers[ch] == nil {
			reg.watchers[ch] = map[int64]func(){}
		}
		reg.watchers[ch][wid] = func() { r.post(func() { _, _ = cb(goja.Undefined()) }) }
		reg.Unlock()
		dispose := func() {
			reg.Lock()
			delete(reg.watchers[ch], wid)
			reg.Unlock()
		}
		r.disposes = append(r.disposes, dispose)
		d := vm.NewObject()
		_ = d.Set("dispose", dispose)
		return d
	})
}
