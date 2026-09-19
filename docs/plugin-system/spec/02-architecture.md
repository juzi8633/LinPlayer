# 2. 架构

## 2.1 放在哪一层

插件宿主整个在 **Go 核心层**(`lpcore`),与 emby / player / danmaku 并列。两个 UI 壳只做三件事:
按核心层发来的 UI 树原生渲染、把用户交互回传、提供平台独有能力(WebView、系统通知、Android 系统入口、jar 加载)。

```
┌──────────────────────── UI 壳(每平台一份)────────────────────────┐
│  桌面 C# Avalonia                  Android Kotlin Compose(手机/TV) │
│  - 插件 UI 渲染器(树 + patch → 原生控件)                          │
│  - 平台能力:WebView2 / WebKitGTK / Android WebView / GeckoView    │
│  - 系统入口:托盘、子窗口 / 通知、快捷方式、小组件、Watch Next      │
│  - Android 独有:jar 加载(DexClassLoader,:spider 进程)           │
└──────┬─────────────────────┬─────────────────────┬────────────────┘
       │① 控制通道(C ABI/JSON)│② 数据通道(127.0.0.1)│③ 视频通道
┌──────▼─────────────────────▼─────────────────────▼────────────────┐
│  Go 核心层                                                          │
│  core/plugin/      插件宿主:安装、manifest、启停、贡献点注册表        │
│  core/plugin/rt/   运行时:goja + 事件循环 + 超时 + 子运行时          │
│  core/plugin/api/  宿主 API 实现(按 .d.ts 命名空间分文件,骨架生成)  │
│  core/plugin/ui/   UI 树中转(Preact 渲染结果 → 事件;交互 → 回调)    │
│  core/plugin/market/  仓库订阅、index.json、下载、更新               │
│  core/plugin/bundle/  esbuild 打包(TS 编译、ESM→IIFE、assets://)   │
│  core/plugin/ext/     扩展组件下载管理(与补帧组件共用,D380)        │
│  core/source/      数据源开放键 plugin:作者/名字/源id(D153)         │
│  core/emby core/player core/danmaku core/net/prefetch …(已有)       │
└────────────────────────────────────────────────────────────────────┘
```

`core/plugin` 等名字在 2026-09-19 删旧系统后已空出,新系统直接用;旧代码不参考(D152)。

## 2.2 三条通道各搬什么

沿用核心层既有的三通道模型(`docs/go-migration/SPEC.md` §2),插件只是新增了几类载荷:

| 通道 | 插件相关载荷 |
|---|---|
| ① 控制 | 插件管理命令(`plugin.*`)、UI 树首帧与 patch(事件 `plugin.ui`)、UI 交互回传(命令 `plugin.ui.event`)、数据源动词结果、钩子询问 |
| ② 数据 | 插件包内资源 `/p/<token>/<插件>/<路径>`、插件图片走现有 `/img`(可带请求头与 Cookie 罐,D87)、插件代理路由 `/pp/<token>/<插件>/<路由>`(D498 D499)、数据源播放走现有预取代理(D173) |
| ③ 视频 | 不变。`<Player>` 组件只是让壳把视频层定位到某块区域(D268) |

鉴权沿用现有规则:给 mpv 吃的路由 token 在 URL 路径段里,给图片加载器的在请求头里。插件代理路由的 token 每次启动重生成,只绑 `127.0.0.1`(D499)。

## 2.3 运行时模型

- **引擎**:goja(纯 Go ES5.1 + 大部分 ES2015+,有 Promise/async)+ esbuild(纯 Go)(D18)。实测依据:新建运行时 ≈0ms、drpy 分类调用 3.4~3.75ms、进程增量 +30~34MB;qjs 新建一个运行时要 400ms、内存 3 倍(`08-runtime-benchmark.md`)。
- **每插件一个主运行时 + 一个事件循环**,单线程、异步(D52)。宿主对插件的一切调用都投递进它的循环串行执行;耗时宿主 API 返回 Promise,在 Go 侧另起 goroutine 干活,完成后把 resolve 投回循环。
- **子运行时**(D127):插件用 `js.createContext()` 建隔离 goja,跑在自己的 goroutine,允许同步阻塞的 `req()`(drpy 靠它)。子运行时同受超时与防崩管控。TVBox 插件每源一个子运行时,按需建、闲置回收,TV 最多同时 4 个、桌面 16 个(D316)。
- **打包**:插件发布前已经由 `lp build` 打成单文件 `main.js`(ES2017 IIFE);应用内 esbuild 只用在两处:开发模式指向本地目录时现编 TS(D42),以及插件在运行时打包第三方 ESM(如 TVBox 的 drpy2,`js.bundle()`)。
- **SDK 注入**:插件 `import { player } from '@linplayer/plugin-sdk'`,esbuild 把这个包映射到宿主注入的全局对象,不打进插件包(D207)。
- **Web 全局**:`URL` `URLSearchParams` `TextEncoder/Decoder` `atob/btoa` `crypto.getRandomValues` `AbortController` `setTimeout/setInterval/queueMicrotask` `fetch` `console`(D209);没有 DOM、没有 Node 内置模块(D148)。

### 2.3.1 超时怎么计(D53)

「只计 JS 占用 CPU 的时间」落成:**事件循环里一次连续同步执行的时长**。宿主在每次把任务交给循环时启动计时,任务同步部分结束(遇到 await 或返回)时停表并累加到这次调用的预算上;等待网络、定时器期间不计。超预算就 `runtime.Interrupt()` 打断,本次调用以超时错误结束。

| 调用类别 | 预算 |
|---|---|
| UI 渲染 / 事件回调 | 1 秒 |
| 数据源动词、提供者调用 | 30 秒 |
| 钩子(导航拦截、列表变换、角标、命令补全、m3u8 过滤器) | 300ms 墙钟,超了**跳过**而不是报错给用户(D281) |
| 后台定时唤醒 | 单次 60 秒墙钟(D509) |
| jar/py 源 | 墙钟 30 秒(子进程,CPU 计时管不到,D355) |

未实测的风险:goja `Interrupt` 打断死循环的实际延迟(`08-runtime-benchmark.md` 末尾已列为上线前必测)。

### 2.3.2 内存(D141 D438)

goja 不能按运行时限内存,宿主做**全局水位看门狗**:进程 Go 堆超阈值(TV 512MB / 桌面 1.5GB,首轮实测后校准)时,按「每次调用前后采样的堆增量」挑出嫌疑插件,销毁其运行时,计一次报错(走 D53 连错禁用),Toast「xx 插件内存占用过高,已暂停」。下次用到自动重建;带界面的区块显示错误边界的「重试」。定位是启发式的,不保证精确。

## 2.4 线程与并发

| 执行体 | 跑在哪 | 注意 |
|---|---|---|
| 插件主运行时 | 专属 goroutine(事件循环) | 同一插件内严格串行;不同插件并行 |
| 子运行时 | 各自 goroutine | 同步 `req()` 只在这里给 |
| 宿主 API 的 IO | 临时 goroutine | 结果投回插件循环 |
| UI 渲染器 | 壳的 UI 线程 | 收到 patch 按帧合批应用(D318) |
| WebView | 壳的 UI 线程 | 隐藏 WebView 全局最多 3 个,超出排队(D497) |
| jar spider | Android `:spider` 进程 / 桌面 JVM 子进程 | 崩了只重启子进程(D354) |

宿主**不对插件的网络请求限频**(D252),也不给每插件并发上限;防封由插件自己管。宿主自己发起的批量调用有上限:换源、一键检测源都是并发 8(D234 D385)。

## 2.5 一次调用的完整路径(以「打开数据源分类页」为例)

1. 壳打开官方分类页,调 `source.category`(开放键 `plugin:linplayer/tvbox/xxx`)。
2. 核心层按开放键找到插件;插件未加载则按 manifest 懒加载(D51),首帧期间壳显示骨架屏。
3. 调用投递进插件循环,插件的 `dataSource.category(ctx)` 执行;TVBox 插件在其中把请求转给该源的 drpy 子运行时。
4. 返回统一结构 `{items, next}`(D254 D255);核心层过列表变换钩子(D278,每个 300ms 预算)→ 宿主屏蔽规则(D515)→ 返回壳渲染。
5. 出错时插件抛类型化错误(D253),核心层映射成 `bus.Err` 并带上动作(D323),壳显示对应文案与按钮。

## 2.6 与现有核心层的接缝

| 已有模块 | 插件系统怎么用 |
|---|---|
| `core/source` | 开放键机制保留;`SplitPlugin` 要改成按**最后一个** `/` 切,因为插件 id 自带一个 `/`(`plugin:alice/tvbox/src1` → 插件 `alice/tvbox`、源 `src1`) |
| `core/net/prefetch` | 数据源直链播放照走预取;插件可对自己的源声明禁用预取(D173) |
| `core/net/localserve` | 新增 `/p/` 资源路由与 `/pp/` 插件代理路由 |
| `core/imgcache` | 插件图片、数据源海报共用缓存与总上限(D87 D422) |
| `core/secrets` | 只管编译期密钥;插件密钥区是另一回事(见第 13 章) |
| `core/config/backup.go` | 备份新增插件段;密钥区随「带账号」档位(D490) |
| `core/blocklist` | 屏蔽规则在列表钩子之后执行,且覆盖数据源条目(D515 D516) |
| `core/player` | mpv 属性透传 + 脱敏 + 还原记账(第 9 章) |
| `core/bus` | 错误码沿用,新增插件错误到 `bus.Err` 的映射(附录 20.2) |
| `core/companion` | 手机扫码遥控加插件按钮、TV 输入框扫码输入、推送安装到 TV(D491 D315 D403) |
