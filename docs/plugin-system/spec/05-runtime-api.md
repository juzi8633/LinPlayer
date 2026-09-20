# 5. 运行时与宿主 API

本章讲每组 API **做什么、边界在哪、失败怎么表现**;精确签名在 `api/plugin-sdk.d.ts`,两者同名。

## 5.1 导入方式(D207)

```ts
import { player, nav, storage, media, registry } from '@linplayer/plugin-sdk'
```

`@linplayer/plugin-sdk` 在构建期被 esbuild 映射到宿主注入的实现,不打进包。`fetch` `console` `setTimeout` 等 Web 全局直接可用(D209)。

## 5.2 命名空间一览

| 命名空间 | 用途 | 关键规则 | 出处 |
|---|---|---|---|
| `app` | 版本、平台、能力探测、语言、减少动态效果、开发者模式、应用设置读写 | 账号/服务器/代理类设置不可写 | D179 D93 D427 D428 |
| 全局 `fetch` / `WebSocket` | 网络 | 见 5.3 | D97 D208 |
| `storage` | 每插件 KV,**同步** API | 见 5.4 | D284 |
| `secrets` | 每插件密钥区 | 系统密钥库加密 | D38 D490 |
| `files` | 私有目录 `data/` `cache/`、系统文件选择器、保存图片 | 不能读写任意路径 | D144 D245 D536 |
| `assets` | 包内资源地址 | — | 4.1 |
| `cookies` | 每数据源/插件一个 Cookie 罐,WebView 与 fetch 共用 | 存密钥区 | D60 |
| `registry` | 宿主注册表通道(推模式) | 见 5.6 | D237 D272 D273 |
| `js` | 子运行时、运行时打包 | 同步请求只在子运行时 | D127 |
| `html` | drpy 选择器、CSS 选择器 DOM、XPath(Go 实现) | — | D98 |
| `crypt` | AES/DES/RSA/MD5/SHA/HMAC/Base64/GBK(Go 实现) | CryptoJS 兼容层只给 drpy 子运行时 | D99 |
| `webview` | 隐藏嗅探、执行 JS、过盾、可见验证页 | 全局隐藏 WebView 最多 3 个 | D59 D62 D496 D497 |
| `proxy` | 宿主本地代理上的插件路由 | 完整 HTTP,地址带 token | D498 D499 |
| `spider` | TVBox jar / Python spider 宿主能力 | 子进程,墙钟 30 秒 | D349 D350 D355 |
| `ui` | Toast、对话框、系统通知、页面选项 | 通知每插件每小时 5 条 | D86 D547 |
| `nav` | 导航栈、角标、返回拦截 | 不能去凭据页改路 | D84 D85 D305 D407 |
| `player` | 播放器属性/命令/事件、播放、截图、着色器、字幕全文、转写、抽帧 | 脱敏与还原记账 | 第 9 章 |
| `media` | 媒体库有限查询、观看记录、收藏、标记已看/进度 | 统一结构,图片不带 api_key | D92 D93 |
| `emby` | Emby 只读 GET 代发 | 脱敏、拒绝取流类端点 | D472 |
| `servers` | 服务器 id/显示名/类型、当前服务器 | 不给地址与用户名 | D287 D288 |
| `download` | 让官方下载器下 Emby 片 | 同官方下载权限门 | D309 |
| `cast` | 弹官方投屏设备选择 | 拿不到设备与地址 | D311 |
| `oauth` | OAuth 助手(浏览器跳转 / TV 设备码),走自建中转 | client secret 只在服务端 | D199 D203 D204 |
| `trakt` `bangumi` | 借用宿主已登录账号代发 | 看不到 token | D365 |
| `system` | 系统浏览器、调起其它 App、剪贴板读写、系统分享(含图片) | — | D196 D197 D536 |
| `ext` | 查询/确保宿主扩展组件已装 | 用到时才弹下载确认 | D381 |
| `events` | 播放、导航、切服、前后台、画中画事件订阅 | — | D94 D458 |
| `debug` | 读任意插件日志/请求/UI 树/KV | 只在开发者模式存在 | D128 |
| `canvas` | 离屏绘制导出 PNG | 屏上绘制走 `<Canvas>` 组件 | D104 D536 |
| `sources` | 运行中增删自己提供的数据源 | 删掉的源记录保留 | D132 D230 |
| `settings` | 读写本插件 manifest 声明的设置项 | 密码类存密钥区 | D35 D267 |
| `wallpaper` | 壁纸插件动态切换壁纸 | 只有当前选中的壁纸插件生效 | D441 D442 |
| `desktop` | 任务栏进度/角标、窗口标题 | 仅桌面 | D501 |
| `widgets` `tvChannels` | 推 Android 桌面小组件数据、TV 首页推荐行 | 仅 Android | D505 D506 |

## 5.3 网络:`fetch`

- 语义兼容 Web 标准:返回 `Response`,有 `.text()` `.json()` `.arrayBuffer()` 与流式 `body`(D208)。另支持 `WebSocket`(D97)。
- 扩展字段放在 `init.lp` 里(D208):`insecure`(忽略证书错误,D97)、`direct`(不走应用代理,D96)、`cookieJar`(用哪个 Cookie 罐,D60)、`timeout`(见下)。
- **默认超时**:连接 10 秒 + 空闲 30 秒,不设整体超时;可传 `lp.timeout` 或 `AbortSignal` 覆盖(D454)。
- **重定向**:默认跟随;`redirect: 'manual'` 时拿得到 3xx 状态与 `Location`(D455)。
- **请求头**:任意可写,含 `Cookie` `Referer` `Host`(D455)。没设 UA 时宿主补一个常见浏览器 UA(不发 UA 会吃 403),插件请求走 UA 三道里的「其它」道(D63)。
- **代理**:跟随应用代理设置(D96)。
- **局域网**:目标是私网地址而 manifest 没声明 `lan` → 抛 `PluginError(kind:'permission')`,调试面板里写明「未声明 lan」(D247)。
- **不限频**(D252)。
- **记录**:每插件内存里保留最近 200 条请求摘要,随错误详情带出;开发者模式下调试面板看完整头与响应体(D456 D81)。

## 5.4 存储

| 类别 | API | 位置 | 备份 | 跨设备同步 |
|---|---|---|---|---|
| KV | `storage.get/set/remove/keys`(同步) | 每插件一张表 | 进 | 进,按键后写覆盖(D140) |
| 文件 `data/` | `files.data.*` | 私有目录 | 进 | 进 |
| 文件 `cache/` | `files.cache.*` | 私有目录 | 不进 | 不进 |
| 密钥区 | `secrets.get/set/remove` | 系统密钥库加密 | 随「带账号」档 | 不进 |

- KV **同步 API**:读直接返回;写入内存立即生效,宿主后台批量落盘(D284)。值为可 JSON 序列化的值。
- 不设配额;占用在插件详情显示,可一键清缓存/清数据(D143 D224)。
- 数据全局一份,不按 Emby 账号隔离(D417)。
- 细节(同步冲突、备份档位、卸载)见第 13 章。

## 5.5 文件(D245 D536)

- 插件只能读写自己的 `data/` `cache/`。
- 用户文件只能经系统选择器:`files.pick()` 拿到用户选的那一个文件的内容,`files.save()` 把内容交给用户选的位置。
- `files.saveImage(png)`:手机存相册,桌面弹保存框。

## 5.6 注册表(D237 D272 D273 D275)

插件之间不 import、不直接调用(D17),互通只走注册表通道:

- **推模式**:写入者 `registry.put(通道, 条目键, 值)` 交给宿主落盘;读者随时 `registry.list(通道)`,可 `registry.watch` 订阅变化;写入者不会被唤醒。
- **宿主定义的通道**有固定格式并校验:`live.channels`(直播频道源)、`live.epg`(EPG 源)。m3u8 过滤器、弹幕源等不是注册表通道,它们是 manifest 贡献点(第 6 章)。
- **插件命名通道**:以自己 id 为前缀(`alice/xxx/...`),任何插件可读写,格式宿主不管。
- 写入者被禁用/卸载时,宿主清掉它写的条目。
- **推荐读者**:manifest 可声明「这个通道推荐由某插件读」;通道有数据而推荐读者没装时,宿主提示一次(典型:TVBox 订阅带直播源而没装直播插件,「这份配置包含 N 个直播源,安装官方直播插件?」)。

## 5.7 子运行时(D127 D316)

- `js.createContext({ globals, withSyncHost })` 建隔离 goja,注入插件给的全局(函数、对象);`withSyncHost: true` 时再注入 drpy 需要的同步宿主函数(`req` `pdfh` `pdfa` `pd` `joinUrl` `local`)。同步 API 只在子运行时可用(它在自己的 goroutine,阻塞不影响主循环)。
- `js.bundle(entry, { resolve })` 用 esbuild 把 ESM(含自定义 scheme 如 `assets://`)打成 IIFE,供子运行时执行(drpy2 已实测可行)。
- 子运行时受同样的超时(30 秒)与防崩管控;插件自行决定建多少、何时销毁(TVBox 插件的上限见 D316)。

## 5.8 WebView(D59 D62 D177 D178 D374 D375 D496 D497)

- **对所有插件开放**:隐藏加载页面并拦截请求抓视频地址(嗅探)、在页面里执行 JS 取值、过盾后拿 Cookie、可见的验证页。
- 选项:自定义 UA 与请求头、按规则拦截/屏蔽请求(广告域名、图片)、document-start 注入脚本、读写 Cookie 与 localStorage。
- **可见验证**:嗅探超时(默认 15 秒,可由插件改)后,需要用户手动过验证时跳一个**整页** WebView,返回后继续;TV 上可见 WebView 开虚拟鼠标(方向键移光标、确认键点击)。
- 隐藏 WebView **全局最多 3 个**,超出排队,排队时间计入调用超时。
- 各平台内核:Windows 用系统 WebView2(不自带运行时)、Linux 用 WebKitGTK(没装就提示用包管理器安装)、Android 用系统 WebView,**缺失或过旧时不做内核兜底**(D560:GeckoView 动态加载已评估,不做)——
界面说清「这台设备没有可用的系统 WebView」并指路去装 / 更新它。不可用时 `app.capabilities.webview === false`,调用抛 `unsupported`,插件自行降级。
- 插件页里要嵌网页用 `<WebView>` 组件(第 7 章)。

## 5.9 代理路由(D37 D246 D498 D499)

插件不能监听端口。要给播放器喂改写过的 m3u8、解密过的分片,就注册代理路由:

```ts
proxy.route('seg', async (req) => new Response(decrypt(await fetch(req.query.u))))
const url = proxy.url('seg', { u: realUrl })   // → 127.0.0.1 上带会话 token 的地址
```

- handler 拿到 Request、返回 Response,可流式(转发 fetch 的 body,或边解密边写)、任意状态码与头。
- **Range 与 seek 由宿主负责**:宿主在代理层处理 Range 请求与连接复用,别重踩「预取代理没回 `Connection: close` 吞 seek」的坑。
- 地址 = 本机回环 + 插件路径 + 随机会话 token(每次启动重生成)。

## 5.10 TVBox spider 宿主能力(D348~D355 D534)

`spider` 命名空间是给 TVBox 插件用的宿主能力(任何插件都能用,但只有 TVBox 生态需要):

- `spider.supported(kind)` 先查本设备能不能跑;`spider.load({ kind: 'jar' | 'py', url, md5, api, ext, sourceKey })` 加载,返回句柄;`句柄.call('categoryContent', …)` 调 catvod Spider 的方法(`homeContent` `categoryContent` `detailContent` `searchContent` `playerContent` `liveContent` `proxy` …),参数与返回沿用 catvod 的 JSON 字符串约定,由 TVBox 插件转成统一结构。
- **Android**:jar 用 `DexClassLoader` 在独立 `:spider` 进程加载,Kotlin 壳实现 catvod Spider 宿主接口;Python 解释器是扩展组件,按需下载。
- **桌面**:jar/py 运行环境(JVM + dex 转换 + 安卓 API 桩 + Python)是扩展组件,宿主起子进程(先 spike 验证跑通率,D351)。
- jar 按 md5 缓存在插件 `cache/`,订阅刷新时检查(D352)。
- jar 的 `proxy` 走宿主兼容入口:启动时优先占用 TVBox 约定的固定本地端口,占不到用动态端口,只监听回环(D353)。
- 墙钟超时 30 秒;超时或崩溃计入**该源**的错误数,连错只禁用那个源(D355)。
- 环境不可用时抛 `unsupported`,TVBox 插件把源灰显并写原因。

## 5.11 定时与后台(D129 D502~D509)

- 运行时内有标准 `setTimeout/setInterval`(插件被回收时一并清掉)。
- **周期任务**:manifest `contributes.background.tasks` 声明「每 N 分钟跑一次 xxx」(最短 15 分钟),宿主记上次运行时间,到点唤起插件执行对应 handler。应用在前台时直接跑;应用不在前台时属于**后台**,受 5.12 约束。
- **常驻服务**:`contributes.background.service` 声明后,插件可在后台常驻(Android 前台服务 + 常驻通知)。

## 5.12 后台运行(D16 D502~D509)

- 后台只用于三种用途:刷新桌面小组件 / TV 首页推荐行、追更/开播提醒通知、下载后端轮询(D504)。订阅与 EPG 预拉取不走后台。
- 定时唤醒单次最多 60 秒墙钟,超时杀掉;连续超时计入 D53,自动禁用该插件的后台任务(常驻服务不受 60 秒限制)。
- 运行条件:manifest 可声明仅 Wi-Fi / 仅充电,交给系统调度器(Android WorkManager);默认不限。
- 开关:设置里「允许后台运行」应用级总开关(默认开)+ 插件详情里每插件开关;Android 第一次需要时引导关闭电池优化。
- 桌面:应用关窗口时若有插件后台任务,弹「退到后台 / 完全退出」+「记住我的选择」;托盘右键始终有「完全退出」(D507)。

## 5.13 调试 API(D128)

只在开发者模式打开时存在(`debug` 为 `undefined` 否则):读任意插件的日志、网络请求、UI 树、KV(可编辑)、每次调用耗时与内存增量。调试面板插件靠它。
