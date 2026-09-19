# JS 运行时实测:goja + esbuild vs fastschema/qjs

> 2026-09-19 实测。机器:本机 Windows 11 x64,Go 1.27.0,`CGO_ENABLED=0`。
> 版本:goja `v0.0.0-20260917113740-793a2a65c13b`、esbuild `v0.28.2`、qjs `v0.0.6`(内含 wazero `v1.9.0`)、goquery `v1.13.0`。
> **失效条件**:任一引擎大版本升级、或 qjs 换掉每运行时编译 wasm 的做法后,重测。

## 1. 测什么

真实 drpy2(`hjdhnx/dr_py` 仓库 `libs/drpy2.js`,版本号 `3.9.49beta40`)连同它 import 的
cheerio.min.js / crypto-js.js / jsencrypt.js / gbk.js / 模板.js,原样拿来跑。

- **打包**:esbuild 把 drpy2 的 ESM(含 `assets://` 自定义 scheme)打成单文件 IIFE,target ES2017。
  两个引擎吃**同一份**产物(1021 KB),差异只在引擎本身。
- **宿主桥**:Go 注入 `req`(同步 HTTP)、`pdfh/pdfa/pd`(goquery 实现 drpy 选择器语法,含 `:eq(n)`)、
  `joinUrl`、`local`。Go 侧只收发字符串,JS 侧一层 prelude 包装 —— 两个引擎走完全相同的桥。
- **负载**:本地 httptest 假站,分类页 40 条(约 30 KB);规则走 drpy2 的 `一级` 字符串语法。
  `category('1',1)` 结果校验包含 `影片39`,不对就判失败。
- **纯 JS CPU**:2000 次 `CryptoJS.AES.encrypt` + `CryptoJS.MD5`,不经过宿主桥。
- console 两边都换成空函数(qjs 自带 console 会刷 stdout,拖慢计时)。

## 2. 结果(各跑 3 轮,取范围)

| 指标 | goja + esbuild | qjs(QuickJS→WASM→wazero) |
|---|---|---|
| 新建一个运行时 | ≈0 ms | **399~430 ms** |
| 加载 drpy2 产物 | 53~59 ms | 146~161 ms |
| `init(rule)` | 0~1.6 ms | 1.5~2.5 ms |
| `category` 单次均值(n=50) | **3.40~3.75 ms** | 7.80~8.70 ms |
| 2000×AES+MD5(纯 JS) | 608~623 ms | **514~569 ms** |
| 跑完后进程 `Sys` 增量 | **+30~34 MB** | +93~105 MB |
| esbuild 打包(热) | 44~52 ms | 同左(共用) |

体积(`-trimpath -ldflags="-s -w"`,空 main 为基线):

| | windows/amd64 | android/arm64 |
|---|---|---|
| 基线 | 1612 KB | 1728 KB |
| + goja | 10476 KB(+8.7 MB) | 10560 KB(+8.6 MB) |
| + esbuild | 8121 KB(+6.4 MB) | 8064 KB(+6.2 MB) |
| + goja + esbuild | 16429 KB(+14.5 MB) | 16384 KB(+14.3 MB) |
| + qjs | 6251 KB(+4.5 MB) | 6528 KB(+4.7 MB) |

体积是**上限**:lpcore 已经链了 `golang.org/x/text` 等,真实增量会更小,未实测。

## 3. 读数

- **qjs 新建运行时 ≈400 ms** —— 每个 `qjs.New()` 都要编译内嵌的 1 MB wasm。按插件隔离(一个插件一个运行时),
  装 10 个插件冷启动就是 4 秒。goja 新建是 0。
- **过宿主桥的负载 qjs 慢一倍**(category 8 ms vs 3.5 ms):每次跨 wasm 边界都要拷字符串进出线性内存。
  数据源插件恰恰是桥密集型(req → pdfa → 40×pdfh)。
- **纯 JS 计算 qjs 快 10~15%**,不足以抵消上面两条。
- **内存 qjs 约 3 倍**:wasm 线性内存按页增长且不归还。
- qjs 是 `v0.0.6`,goja 是 k6 生产在用。
- drpy2 的 ESM 在 goja 上靠 esbuild 预打包**实测跑通**,不需要原生 ESM。

## 4. 结论

**选 goja + esbuild。** 代价是 lpcore 最多 +14.5 MB,其中 esbuild 占 6.4 MB ——
它换来三件事:TVBox 的 ESM spider 运行时打包、插件 TS 在应用内直接编(开发模式热重载)、语法降级到 goja 能跑的版本。

未测、上线前要补的:Android 真机上的同一组数字;goja `Interrupt` 打断死循环的实际延迟(D12 超时中断依赖它)。
