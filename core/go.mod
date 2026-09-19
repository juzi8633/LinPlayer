// LinPlayer 核心层。各端共用,编译成 C ABI 库被 Kotlin/JNI、C#/P-Invoke、Swift 加载。
//
// **零第三方依赖是刻意的**。
// 依赖越少,三端交叉编译时能出问题的地方就越少。
module linplayer/core

go 1.27

require (
	github.com/PuerkitoBio/goquery v1.13.0
	github.com/antchfx/htmlquery v1.3.6
	github.com/dlclark/regexp2/v2 v2.5.2
	github.com/dop251/goja v0.0.0-20260917113740-793a2a65c13b
	github.com/evanw/esbuild v0.28.2
	github.com/santhosh-tekuri/jsonschema/v6 v6.0.3
	golang.org/x/net v0.58.0
	golang.org/x/text v0.41.0
)

require (
	github.com/andybalholm/cascadia v1.3.4 // indirect
	github.com/antchfx/xpath v1.3.6 // indirect
	github.com/go-sourcemap/sourcemap v2.1.3+incompatible // indirect
	github.com/golang/groupcache v0.0.0-20210331224755-41bb18bfe9da // indirect
	github.com/google/pprof v0.0.0-20230207041349-798e818bf904 // indirect
	golang.org/x/sys v0.47.0 // indirect
)
