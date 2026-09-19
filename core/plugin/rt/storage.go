package rt

// 插件存储:KV(同步 API,D284)、密钥区(D38 D490)、Cookie 罐(D60)、私有文件(D144 D245)。

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/publicsuffix"

	"linplayer/core/paths"
)

// ---------------------------------------------------------------- KV

// kvStore 每插件一张表:读直接返回,写入内存立即生效,后台批量落盘。
type kvStore struct {
	mu    sync.Mutex
	path  string
	m     map[string]json.RawMessage
	dirty bool
	t     *time.Timer
}

func openKV(dir string) (*kvStore, error) {
	s := &kvStore{path: filepath.Join(dir, "kv.json"), m: map[string]json.RawMessage{}}
	b, err := os.ReadFile(s.path)
	if err == nil {
		if json.Unmarshal(b, &s.m) != nil {
			// 坏文件不能挡住插件启动:备份一份再从空表开始
			_ = os.Rename(s.path, s.path+".bad")
			s.m = map[string]json.RawMessage{}
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	return s, nil
}

// Get 取原始 JSON;没有返回 nil。
func (s *kvStore) Get(k string) json.RawMessage {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.m[k]
}

// Set 写一个值(nil = 删除)。
func (s *kvStore) Set(k string, v json.RawMessage) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if v == nil {
		delete(s.m, k)
	} else {
		s.m[k] = v
	}
	s.dirty = true
	if s.t == nil {
		s.t = time.AfterFunc(500*time.Millisecond, s.flush)
	}
}

// Keys 按前缀列键(已排序)。
func (s *kvStore) Keys(prefix string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []string{}
	for k := range s.m {
		if strings.HasPrefix(k, prefix) {
			out = append(out, k)
		}
	}
	sort.Strings(out)
	return out
}

// All 全表快照(备份、调试面板)。
func (s *kvStore) All() map[string]json.RawMessage {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]json.RawMessage, len(s.m))
	for k, v := range s.m {
		out[k] = v
	}
	return out
}

func (s *kvStore) flush() {
	s.mu.Lock()
	s.t = nil
	if !s.dirty {
		s.mu.Unlock()
		return
	}
	b, err := json.Marshal(s.m)
	s.dirty = false
	s.mu.Unlock()
	if err == nil {
		_ = writeFileAtomic(s.path, b)
	}
}

func writeFileAtomic(p string, b []byte) error {
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

// ---------------------------------------------------------------- 密钥区

// secretStore 每插件密钥区:AES-GCM 加密文件,主密钥由系统密钥库保护(见 protect*)。
type secretStore struct {
	mu   sync.Mutex
	path string
	m    map[string]string
}

// secCache 同一目录只开一份:宿主写插件的密码类设置项、运行时读写密钥区,两份内存副本会互相覆盖。
var secCache sync.Map

// OpenSecrets 打开插件密钥区(宿主存密码类设置项也用它)。
func OpenSecrets(dir string) (*secretStore, error) { return openSecrets(dir) }

func openSecrets(dir string) (*secretStore, error) {
	p := filepath.Join(dir, "secrets.bin")
	if v, ok := secCache.Load(p); ok {
		return v.(*secretStore), nil
	}
	s, err := loadSecrets(p)
	if err != nil {
		return nil, err
	}
	v, _ := secCache.LoadOrStore(p, s)
	return v.(*secretStore), nil
}

func loadSecrets(p string) (*secretStore, error) {
	s := &secretStore{path: p, m: map[string]string{}}
	b, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	plain, err := unseal(b)
	if err != nil || json.Unmarshal(plain, &s.m) != nil {
		// 主密钥变了(换机器拷数据)时解不开:当作空,不挡插件启动
		s.m = map[string]string{}
	}
	return s, nil
}

func (s *secretStore) Get(k string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.m[k]
	return v, ok
}

func (s *secretStore) Set(k, v string) error {
	s.mu.Lock()
	s.m[k] = v
	b, _ := json.Marshal(s.m)
	s.mu.Unlock()
	return s.save(b)
}

func (s *secretStore) Remove(k string) error {
	s.mu.Lock()
	delete(s.m, k)
	b, _ := json.Marshal(s.m)
	s.mu.Unlock()
	return s.save(b)
}

func (s *secretStore) save(plain []byte) error {
	sealed, err := seal(plain)
	if err != nil {
		return err
	}
	return writeFileAtomic(s.path, sealed)
}

var (
	keyOnce sync.Once
	keyVal  []byte
	keyErr  error
)

// masterKey 全部插件共用的一把主密钥,放 <数据根>/plugins/.key,落盘前经系统密钥库保护。
func masterKey() ([]byte, error) {
	keyOnce.Do(func() {
		p := filepath.Join(paths.Root(), "plugins", ".key")
		if b, err := os.ReadFile(p); err == nil {
			keyVal, keyErr = unprotect(b)
			if keyErr == nil && len(keyVal) == 32 {
				return
			}
		}
		keyVal = make([]byte, 32)
		if _, keyErr = rand.Read(keyVal); keyErr != nil {
			return
		}
		var wrapped []byte
		if wrapped, keyErr = protect(keyVal); keyErr == nil {
			keyErr = writeFileAtomic(p, wrapped)
		}
	})
	return keyVal, keyErr
}

func seal(plain []byte) ([]byte, error) {
	k, err := masterKey()
	if err != nil {
		return nil, err
	}
	blk, _ := aes.NewCipher(k)
	g, _ := cipher.NewGCM(blk)
	nonce := make([]byte, g.NonceSize())
	_, _ = rand.Read(nonce)
	return g.Seal(nonce, nonce, plain, nil), nil
}

func unseal(b []byte) ([]byte, error) {
	k, err := masterKey()
	if err != nil {
		return nil, err
	}
	blk, _ := aes.NewCipher(k)
	g, _ := cipher.NewGCM(blk)
	if len(b) < g.NonceSize() {
		return nil, errors.New("密文太短")
	}
	return g.Open(nil, b[:g.NonceSize()], b[g.NonceSize():], nil)
}

// ---------------------------------------------------------------- Cookie 罐

// jarSet 每数据源/插件一个 Cookie 罐,fetch 与 WebView 共用,存密钥区(D60)。
//
// 标准库 cookiejar 不能序列化,所以另记一份「这个地址收到过哪些 Set-Cookie」,启动时重放进去。
type jarSet struct {
	mu   sync.Mutex
	sec  *secretStore
	jars map[string]*recJar
}

type recJar struct {
	*cookiejar.Jar
	mu  sync.Mutex
	rec map[string][]string // url → Set-Cookie 原文(按 名字 去重)
}

func newJarSet(sec *secretStore) *jarSet { return &jarSet{sec: sec, jars: map[string]*recJar{}} }

func (j *jarSet) get(name string) *recJar {
	j.mu.Lock()
	defer j.mu.Unlock()
	if rj, ok := j.jars[name]; ok {
		return rj
	}
	cj, _ := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	rj := &recJar{Jar: cj, rec: map[string][]string{}}
	if raw, ok := j.sec.Get("cookiejar:" + name); ok {
		_ = json.Unmarshal([]byte(raw), &rj.rec)
		for u, lines := range rj.rec {
			pu, err := url.Parse(u)
			if err != nil {
				continue
			}
			rj.Jar.SetCookies(pu, parseSetCookies(lines))
		}
	}
	j.jars[name] = rj
	return rj
}

// SetCookies 记下原文再交给标准库罐。
func (rj *recJar) SetCookies(u *url.URL, cs []*http.Cookie) {
	rj.Jar.SetCookies(u, cs)
	key := u.Scheme + "://" + u.Host + "/"
	rj.mu.Lock()
	defer rj.mu.Unlock()
	lines := rj.rec[key]
	for _, c := range cs {
		kept := lines[:0]
		for _, l := range lines {
			if !strings.HasPrefix(l, c.Name+"=") {
				kept = append(kept, l)
			}
		}
		lines = append(kept, c.String())
	}
	rj.rec[key] = lines
}

func (j *jarSet) persist(name string) {
	j.mu.Lock()
	rj := j.jars[name]
	j.mu.Unlock()
	if rj == nil {
		return
	}
	rj.mu.Lock()
	b, _ := json.Marshal(rj.rec)
	rj.mu.Unlock()
	_ = j.sec.Set("cookiejar:"+name, string(b))
}

func (j *jarSet) clear(name string) {
	j.mu.Lock()
	delete(j.jars, name)
	j.mu.Unlock()
	_ = j.sec.Remove("cookiejar:" + name)
}

func parseSetCookies(lines []string) []*http.Cookie {
	h := http.Header{}
	for _, l := range lines {
		h.Add("Set-Cookie", l)
	}
	return (&http.Response{Header: h}).Cookies()
}
