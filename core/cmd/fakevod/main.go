// fakevod 把 TVBox 验收用的假资源站单独起成一个进程,给真机自检用(门禁里是 httptest 起同一份)。
//
//	fakevod -addr 本机回环:端口 [-media 测试 HLS 目录] [-engine drpy2.js]
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"

	"linplayer/core/internal/fakevod"
)

func main() {
	addr := flag.String("addr", "127.0.0.1:0", "监听地址")
	media := flag.String("media", "", "测试 HLS 目录(index.m3u8 + 分片);空 = 媒体 404")
	engine := flag.String("engine", "plugins/tvbox/assets/drpy/drpy2.js", "配置指定的 drpy 引擎")
	base := flag.String("base", "", "写进配置的对外地址(模拟器访问宿主机要用它);空 = 监听地址")
	jar := flag.String("jar", "", "TVBox jar 源(scripts/build-spider-demo.sh 的产物)")
	flag.Parse()

	fv := fakevod.New(*media)
	if b, err := os.ReadFile(*engine); err == nil {
		fv.EngineJS = string(b)
	} else {
		log.Printf("drpy 引擎读不到(drpy 源会失败):%v", err)
	}
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatal(err)
	}
	fv.Base = "http://" + ln.Addr().String()
	if *base != "" {
		fv.Base = *base
	}
	fv.SpiderJar = *jar
	fmt.Println(fv.Base)
	h := fv.Handler()
	log.Fatal(http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("%s %s", r.Method, r.URL.RequestURI())
		h.ServeHTTP(w, r)
	})))
}
