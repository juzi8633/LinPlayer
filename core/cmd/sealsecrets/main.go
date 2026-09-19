// sealsecrets 把明文凭据封成密文,打印成 `go build -ldflags` 的 -X 片段。
//
// Go 没有 build.rs,所以这一步由构建脚本显式调用(见 scripts/build-core.sh)。
//
//	DANDANPLAY_APP_ID     明文注入(公开标识符)
//	DANDANPLAY_APP_SECRET 密文注入
//
// ★★ 只输出**密文**。明文进 ldflags 会原样出现在构建日志和进程命令行里 ——
// CI 的日志是公开的,那等于把密钥贴了出去。
//
// ★ 一个都没配时输出空串:本地构建不该因此失败(对应功能会明说「此构建没有凭据」)。
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"fmt"
	"os"
	"strings"
)

// 与 core/secrets 的 obfKey 必须逐字节一致。改了这里而不改那里 =
// CI 注入的密钥全部解不开,**而且不会报错** —— 只是所有相关功能悄悄变成「未配置」。
const obfKey = "LinPlayer-tmdb-ranking-key-v1!!!"

func seal(plain string) string {
	plain = strings.TrimSpace(plain)
	if plain == "" {
		return ""
	}
	block, err := aes.NewCipher([]byte(obfKey))
	if err != nil {
		panic(err)
	}
	b := []byte(plain)
	n := aes.BlockSize - len(b)%aes.BlockSize
	for i := 0; i < n; i++ {
		b = append(b, byte(n))
	}
	out := make([]byte, len(b))
	cipher.NewCBCEncrypter(block, []byte(obfKey[:aes.BlockSize])).CryptBlocks(out, b)
	return base64.StdEncoding.EncodeToString(out)
}

func main() {
	const pkg = "linplayer/core/secrets"
	var parts []string
	add := func(name, val string) {
		if val != "" {
			parts = append(parts, fmt.Sprintf("-X %s.%s=%s", pkg, name, val))
		}
	}
	add("dandanAppID", strings.TrimSpace(os.Getenv("DANDANPLAY_APP_ID")))
	add("dandanSecretEnc", seal(os.Getenv("DANDANPLAY_APP_SECRET")))

	/* 自建代理(问题反馈 / 崩溃报告走它)。★ 这两个在 Rust 版里是**明文常量**并且已经进了版本库 ——
	   LP_SYNC_PROXY_KEY 是访问代理的共享密钥,拿到源码就能拿它刷代理。Go 侧一律走注入,源码不留。
	   现网那条明文密钥仍在 git 历史里,换语言不会让它消失:要轮换 + 改写历史。
	   变量名沿用 LP_SYNC_*:CI 里的 Secret 就叫这个名字,改名 = 静默断掉反馈。 */
	addTo := func(pkg, name, val string) {
		if val != "" {
			parts = append(parts, fmt.Sprintf("-X %s.%s=%s", pkg, name, val))
		}
	}
	addTo("linplayer/core/system", "proxyBase", strings.TrimSpace(os.Getenv("LP_SYNC_PROXY_BASE")))
	addTo("linplayer/core/system", "proxyKey", strings.TrimSpace(os.Getenv("LP_SYNC_PROXY_KEY")))
	// ★ 图标库的聚合源地址(逗号分隔)。它们是**别人的域名**,同样不进提交 ——
	//   黄金实现把四条硬编在 icon_library.rs 里,那是既有的红线欠账。
	addTo("linplayer/core/prefs", "iconSources", strings.TrimSpace(os.Getenv("LP_ICON_LIBRARY_SOURCES")))
	// ★ CF 优选的测速文件地址,同样是具体域名 —— 不注入时下载测速阶段跳过,
	//   排序退化成纯按延迟,功能仍然可用。
	addTo("linplayer/core/net/cf", "defaultTestURL", strings.TrimSpace(os.Getenv("LP_CF_TEST_URL")))
	fmt.Print(strings.Join(parts, " "))
}
