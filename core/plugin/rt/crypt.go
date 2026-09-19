package rt

// 加解密与编码(D99,Go 实现)。字符串参数一律按 UTF-8 取字节;ArrayBuffer 原样。

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/des"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"hash"
	"strings"

	"github.com/dop251/goja"
	"golang.org/x/text/encoding/simplifiedchinese"
)

type cryptOpts struct {
	Key, IV       goja.Value
	Mode, Padding string
	In, Out       string
	Triple        bool
}

func (r *Runtime) installCrypt(o *goja.Object) {
	vm := r.vm
	bs := func(v goja.Value) []byte {
		b, ok := r.bytesOf(v)
		if !ok {
			r.throw(KindInvalid, "需要字符串或 ArrayBuffer")
		}
		return b
	}
	opts := func(v goja.Value) cryptOpts {
		var c cryptOpts
		if ob, ok := v.(*goja.Object); ok {
			c.Key, c.IV = ob.Get("key"), ob.Get("iv")
			get := func(k string) string {
				x := ob.Get(k)
				if x == nil || goja.IsUndefined(x) {
					return ""
				}
				return x.String()
			}
			c.Mode, c.Padding, c.In, c.Out = get("mode"), get("padding"), get("in"), get("out")
			if t := ob.Get("triple"); t != nil {
				c.Triple = t.ToBoolean()
			}
		}
		return c
	}
	fail := func(err error) {
		if err != nil {
			r.throw(KindInvalid, "加解密失败:"+err.Error())
		}
	}
	digest := func(h func() hash.Hash) func(goja.Value) string {
		return func(v goja.Value) string {
			x := h()
			x.Write(bs(v))
			return hex.EncodeToString(x.Sum(nil))
		}
	}
	_ = o.Set("md5", digest(md5.New))
	_ = o.Set("sha1", digest(sha1.New))
	_ = o.Set("sha256", digest(sha256.New))
	_ = o.Set("hmac", func(algo string, key, data goja.Value) string {
		h := map[string]func() hash.Hash{"md5": md5.New, "sha1": sha1.New, "sha256": sha256.New}[algo]
		if h == nil {
			r.throw(KindInvalid, "不支持的 HMAC 算法:"+algo)
		}
		m := hmac.New(h, bs(key))
		m.Write(bs(data))
		return hex.EncodeToString(m.Sum(nil))
	})
	_ = o.Set("aesEncrypt", func(data goja.Value, ov goja.Value) string {
		c := opts(ov)
		out, err := aesCrypt(true, bs(data), bs(c.Key), optBytes(r, c.IV), c.Mode, c.Padding)
		fail(err)
		return encodeOut(out, orDefault(c.Out, "base64"))
	})
	_ = o.Set("aesDecrypt", func(data goja.Value, ov goja.Value) string {
		c := opts(ov)
		in, err := decodeIn(bs(data), orDefault(c.In, "base64"))
		fail(err)
		out, err := aesCrypt(false, in, bs(c.Key), optBytes(r, c.IV), c.Mode, c.Padding)
		fail(err)
		return encodeOut(out, orDefault(c.Out, "utf8"))
	})
	_ = o.Set("desEncrypt", func(data goja.Value, ov goja.Value) string {
		c := opts(ov)
		out, err := desCrypt(true, bs(data), bs(c.Key), optBytes(r, c.IV), c.Mode, c.Triple)
		fail(err)
		return base64.StdEncoding.EncodeToString(out)
	})
	_ = o.Set("desDecrypt", func(data goja.Value, ov goja.Value) string {
		c := opts(ov)
		in, err := base64.StdEncoding.DecodeString(string(bs(data)))
		fail(err)
		out, err := desCrypt(false, in, bs(c.Key), optBytes(r, c.IV), c.Mode, c.Triple)
		fail(err)
		return string(out)
	})
	_ = o.Set("rsaEncrypt", func(data goja.Value, pemKey string, ov goja.Value) string {
		pub, err := parsePub(pemKey)
		fail(err)
		var out []byte
		if opts(ov).Padding == "oaep" {
			out, err = rsa.EncryptOAEP(sha1.New(), rand.Reader, pub, bs(data), nil)
		} else {
			out, err = rsa.EncryptPKCS1v15(rand.Reader, pub, bs(data))
		}
		fail(err)
		return base64.StdEncoding.EncodeToString(out)
	})
	_ = o.Set("rsaDecrypt", func(data goja.Value, pemKey string, ov goja.Value) string {
		priv, err := parsePriv(pemKey)
		fail(err)
		in, err := base64.StdEncoding.DecodeString(string(bs(data)))
		fail(err)
		var out []byte
		if opts(ov).Padding == "oaep" {
			out, err = rsa.DecryptOAEP(sha1.New(), rand.Reader, priv, in, nil)
		} else {
			out, err = rsa.DecryptPKCS1v15(rand.Reader, priv, in)
		}
		fail(err)
		return string(out)
	})
	_ = o.Set("base64Encode", func(v goja.Value) string { return base64.StdEncoding.EncodeToString(bs(v)) })
	_ = o.Set("base64Decode", func(s string, out goja.Value) string {
		b, err := decodeBase64Loose(s)
		fail(err)
		enc := "utf8"
		if !absent(out) {
			enc = out.String()
		}
		return encodeOut(b, enc)
	})
	_ = o.Set("gbkEncode", func(s string) goja.ArrayBuffer {
		b, err := simplifiedchinese.GBK.NewEncoder().Bytes([]byte(s))
		fail(err)
		return vm.NewArrayBuffer(b)
	})
	_ = o.Set("gbkDecode", func(v goja.Value) string { return decodeBytes(bs(v), "gbk") })
}

func optBytes(r *Runtime, v goja.Value) []byte {
	b, _ := r.bytesOf(v)
	return b
}

func orDefault(s, d string) string {
	if s == "" {
		return d
	}
	return s
}

func encodeOut(b []byte, enc string) string {
	switch enc {
	case "base64":
		return base64.StdEncoding.EncodeToString(b)
	case "hex":
		return hex.EncodeToString(b)
	case "gbk":
		return decodeBytes(b, "gbk")
	}
	return string(b)
}

func decodeIn(b []byte, enc string) ([]byte, error) {
	switch enc {
	case "hex":
		return hex.DecodeString(strings.TrimSpace(string(b)))
	case "base64":
		return decodeBase64Loose(string(b))
	}
	return b, nil
}

// decodeBase64Loose 容忍空白、缺填充、URL 安全字母表(配置解码里常见)。
func decodeBase64Loose(s string) ([]byte, error) {
	s = strings.Map(dropSpace, s)
	s = strings.NewReplacer("-", "+", "_", "/").Replace(strings.TrimRight(s, "="))
	return base64.RawStdEncoding.DecodeString(s)
}

func pkcs7Pad(b []byte, n int) []byte {
	p := n - len(b)%n
	return append(b, bytes.Repeat([]byte{byte(p)}, p)...)
}

func pkcs7Unpad(b []byte, n int) ([]byte, error) {
	if len(b) == 0 || len(b)%n != 0 {
		return nil, errors.New("密文长度不对")
	}
	p := int(b[len(b)-1])
	if p == 0 || p > n || p > len(b) {
		return nil, errors.New("填充不对(密钥或模式可能错了)")
	}
	return b[:len(b)-p], nil
}

// aesKey AES 密钥长度不是 16/24/32 时按 CryptoJS 的习惯零填充/截断到最近的合法长度。
func aesKey(k []byte) []byte {
	switch {
	case len(k) <= 16:
		return append(append([]byte(nil), k...), make([]byte, 16-len(k))...)
	case len(k) <= 24:
		return append(append([]byte(nil), k...), make([]byte, 24-len(k))...)
	case len(k) <= 32:
		return append(append([]byte(nil), k...), make([]byte, 32-len(k))...)
	}
	return k[:32]
}

func fitIV(iv []byte, n int) []byte {
	out := make([]byte, n)
	copy(out, iv)
	return out
}

func aesCrypt(enc bool, data, key, iv []byte, mode, padding string) ([]byte, error) {
	blk, err := aes.NewCipher(aesKey(key))
	if err != nil {
		return nil, err
	}
	return blockCrypt(enc, blk, data, iv, orDefault(mode, "cbc"), padding != "none")
}

func desCrypt(enc bool, data, key, iv []byte, mode string, triple bool) ([]byte, error) {
	var blk cipher.Block
	var err error
	if triple {
		k := fitIV(key, 24)
		blk, err = des.NewTripleDESCipher(k)
	} else {
		blk, err = des.NewCipher(fitIV(key, 8))
	}
	if err != nil {
		return nil, err
	}
	return blockCrypt(enc, blk, data, iv, orDefault(mode, "cbc"), true)
}

func blockCrypt(enc bool, blk cipher.Block, data, iv []byte, mode string, pad bool) ([]byte, error) {
	n := blk.BlockSize()
	switch mode {
	case "gcm":
		g, err := cipher.NewGCMWithNonceSize(blk, max(len(iv), 12))
		if err != nil {
			return nil, err
		}
		nonce := fitIV(iv, max(len(iv), 12))
		if enc {
			return g.Seal(nil, nonce, data, nil), nil
		}
		return g.Open(nil, nonce, data, nil)
	case "ctr":
		out := make([]byte, len(data))
		cipher.NewCTR(blk, fitIV(iv, n)).XORKeyStream(out, data)
		return out, nil
	case "ecb", "cbc":
	default:
		return nil, errors.New("不支持的模式:" + mode)
	}
	if enc {
		if pad {
			data = pkcs7Pad(append([]byte(nil), data...), n)
		} else if len(data)%n != 0 {
			return nil, errors.New("不填充时明文长度必须是块大小的整数倍")
		}
	} else if len(data)%n != 0 {
		return nil, errors.New("密文长度不对")
	}
	out := make([]byte, len(data))
	if mode == "cbc" {
		if enc {
			cipher.NewCBCEncrypter(blk, fitIV(iv, n)).CryptBlocks(out, data)
		} else {
			cipher.NewCBCDecrypter(blk, fitIV(iv, n)).CryptBlocks(out, data)
		}
	} else {
		for i := 0; i < len(data); i += n {
			if enc {
				blk.Encrypt(out[i:i+n], data[i:i+n])
			} else {
				blk.Decrypt(out[i:i+n], data[i:i+n])
			}
		}
	}
	if !enc && pad {
		return pkcs7Unpad(out, n)
	}
	return out, nil
}

func pemBlock(s string, kind string) []byte {
	s = strings.TrimSpace(s)
	if !strings.Contains(s, "-----BEGIN") {
		s = "-----BEGIN " + kind + "-----\n" + s + "\n-----END " + kind + "-----"
	}
	b, _ := pem.Decode([]byte(s))
	if b == nil {
		return nil
	}
	return b.Bytes
}

func parsePub(s string) (*rsa.PublicKey, error) {
	der := pemBlock(s, "PUBLIC KEY")
	if der == nil {
		return nil, errors.New("公钥格式不对")
	}
	if k, err := x509.ParsePKIXPublicKey(der); err == nil {
		if pk, ok := k.(*rsa.PublicKey); ok {
			return pk, nil
		}
	}
	return x509.ParsePKCS1PublicKey(der)
}

func parsePriv(s string) (*rsa.PrivateKey, error) {
	der := pemBlock(s, "PRIVATE KEY")
	if der == nil {
		return nil, errors.New("私钥格式不对")
	}
	if k, err := x509.ParsePKCS8PrivateKey(der); err == nil {
		if pk, ok := k.(*rsa.PrivateKey); ok {
			return pk, nil
		}
	}
	return x509.ParsePKCS1PrivateKey(der)
}
