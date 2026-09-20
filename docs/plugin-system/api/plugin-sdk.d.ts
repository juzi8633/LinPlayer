/**
 * @linplayer/plugin-sdk —— 宿主 API 的单一定义源(D146 D514)。
 *
 * 状态:草稿(2026-09-20),与 docs/plugin-system/SPEC.md 同步维护。
 * - 本文件手写;生成器用 TypeScript 编译器 API 读它产出 Go 注册骨架,门禁比对两边。
 * - 每个符号的 `@see Dnnn` 指向 docs/plugin-system/DECISIONS.md 的决定编号。
 * - 构建期 esbuild 把 `@linplayer/plugin-sdk` 映射到宿主注入的实现,不打进插件包(D207)。
 * - 不承诺兼容;改动写进 SDK CHANGELOG(D36 D526)。SDK 版本号 = 应用版本号(D411)。
 * - 并发模型:每插件一个 goja 运行时 + 一个事件循环(单线程),耗时 API 一律返回 Promise;同步请求只在子运行时(D52 D127)。
 * - Web 全局:URL / URLSearchParams / TextEncoder / TextDecoder / atob / btoa / crypto.getRandomValues /
 *   AbortController / setTimeout / setInterval / queueMicrotask / fetch / WebSocket / console(D209)。
 *   没有 DOM,没有 Node 内置模块:依赖它们的库跑不起来(D148)。生态统一用 pnpm(D147)。
 * - console.* 每插件内存保留最近 500 条,随「复制错误详情」带出(D285)。
 */

// ════════════════════════════════════════════════════════════════════
// 0. 基础类型
// ════════════════════════════════════════════════════════════════════

/** 插件 id:`作者/名字`,两段都只许小写字母、数字、连字符。@see D47 D265 */
export type PluginId = `${string}/${string}`

/** 数据源开放键:`plugin:作者/名字/源id`;Emby 服务器为 `emby:<服务器id>`。@see D153 */
export type SourceKey = `plugin:${string}` | `emby:${string}`

/** 平台。@see D138 */
export type Platform = 'windows' | 'linux' | 'android' | 'android_tv'

/** 形态:决定官方断点、焦点、安全区的行为。@see D217 */
export type FormFactor = 'desktop' | 'phone' | 'tv'

/** 可 JSON 序列化的值(KV、注册表、设置的值域)。@see D284 */
export type Json = null | boolean | number | string | Json[] | { [k: string]: Json }

export type Disposable = { dispose(): void }

/** 请求头。 */
export type Headers = Record<string, string>

/** 本地化文本:插件写什么显示什么,界面不做多语言。@see D39 */
export type Text = string

// ════════════════════════════════════════════════════════════════════
// 1. 错误 @see D253 D323,附录 20.2
// ════════════════════════════════════════════════════════════════════

export type PluginErrorKind =
  | 'rateLimited' // 限流(429),可带 retryAfter —— 必须与 notFound 分开
  | 'needVerify'  // 需要过验证/盾:宿主给 [去验证]
  | 'needLogin'   // 需要登录:宿主给 [去登录]
  | 'siteDown'    // 站点不可用:宿主给 [换源]
  | 'notFound'
  | 'parseFailed'
  | 'timeout'     // 含预算打断(D53)
  | 'network'
  | 'unsupported' // 本设备/本插件不支持(WebView 不可用、组件未装…)
  | 'permission'  // 没下载权限、未声明 lan、凭据保护
  | 'invalid'
  | 'internal'

export interface PluginErrorInit {
  kind: PluginErrorKind
  /** 中文,用户会原样看到。 */
  message: string
  /** rateLimited:多少秒后可重试,宿主倒计时。@see D323 */
  retryAfter?: number
  /** needVerify:要在整页 WebView 里打开的地址;不给则用出错请求的地址。@see D323 D374 */
  verifyUrl?: string
  /** needLogin:插件的登录页 id 或设置页;不给则打开插件设置。@see D323 */
  loginPage?: string
  /** 给开发者看的细节(进「复制错误详情」)。@see D172 */
  detail?: string
}

/** 插件抛它,宿主按 kind 出文案与动作;宿主 API 也以它拒绝。 */
export declare class PluginError extends Error {
  constructor(init: PluginErrorInit)
  readonly kind: PluginErrorKind
  readonly retryAfter?: number
  readonly verifyUrl?: string
  readonly loginPage?: string
  readonly detail?: string
}

// ════════════════════════════════════════════════════════════════════
// 2. 插件定义与上下文 @see D206 D34 D51
// ════════════════════════════════════════════════════════════════════

export interface ActivateContext {
  /** 本插件 id。 */
  readonly id: PluginId
  /** 本插件版本(semver)。@see D180 */
  readonly version: string
  /** 是否开发版(本地目录/推送)。@see D292 */
  readonly dev: boolean
  /** 被什么唤起:用户动作、启动即加载、后台任务。@see D51 D502 */
  readonly reason: 'startup' | 'lazy' | 'background'
  /** 插件停用时自动 dispose 的资源袋。 */
  readonly subscriptions: Disposable[]
}

/**
 * `export default definePlugin({...})`:一个对象导出全部贡献点实现,
 * 键与 manifest `contributes` 一一对应(lp check 与市场 CI 校验)。@see D206
 */
export interface PluginDefinition {
  activate?(ctx: ActivateContext): void | Promise<void>
  deactivate?(): void | Promise<void>

  /** 插件页面(pages[] / pageTakeovers[] / launchTargets[] 指向这里)。@see D29 D84 D130 */
  pages?: Record<string, PageComponent>
  /** 各种 UI 区块:锚点、首页栏目(custom)、设置节、覆盖层、侧栏标签、OSD、子窗口。@see D155 D303 D286 D14 D279 D300 D297 D220 */
  blocks?: Record<string, BlockComponent>
  /** 命令实现(commands[] / menus[] / keybindings[] / 系统入口都指向命令)。@see D243 D157 D64 */
  commands?: Record<string, CommandHandler>
  /** 数据源:享受官方观看记录/续播、收藏、弹幕匹配,不接官方下载器。@see D10 D20 D131 */

  dataSource?: DataSourceProvider
  /** 首页栏目(kind=items)的数据。@see D303 */
  homeSections?: Record<string, (ctx: CallContext) => Promise<MediaItem[]>>
  /** 虚拟媒体库。@see D473 */
  virtualLibraries?: Record<string, (ctx: CallContext & { cursor?: string }) => Promise<Page<MediaItem>>>
  /** 搜索建议/快捷动作。@see D242 */
  searchActions?: (query: string, ctx: CallContext) => Promise<SearchAction[]>
  /** 提供者。@see 第 10 章 */
  providers?: Providers
  /** 钩子。@see D277 D278 D474 */
  hooks?: Hooks
  /** m3u8 过滤器(manifest m3u8Filters[] 的 id → 实现)。@see D102 D493 */
  m3u8Filters?: Record<string, M3u8Filter>
  /** 下一个播什么(接管位)。@see D244 */
  nextUp?: (ended: NowPlaying, ctx: CallContext) => Promise<MediaItem | null>
  /** 深链处理:linplayer://p/作者/名字/<path>。@see D154 */
  deepLinks?: Record<string, (params: Record<string, string>) => void | Promise<void>>
  /** 外部输入处理(分享/拖放/文件关联/剪贴板)。@see D304 */
  externalInputs?: Record<string, (input: ExternalInput) => void | Promise<void>>
  /** 后台定时任务与常驻服务(插件可以后台运行,用途受限)。@see D16 D502 D504 */
  background?: {
    tasks?: Record<string, (ctx: BackgroundContext) => Promise<void>>
    service?: (ctx: BackgroundContext) => Promise<void>
  }
  /** 设置按钮项(type: button)触发的动作。@see D267 */
  /** 壁纸(接管位):给出初始壁纸;运行中用 wallpaper.set 动态切换。@see D441 D442 */
  wallpaper?: (ctx: CallContext) => Promise<WallpaperContent>
  settingActions?: Record<string, () => void | Promise<void>>
}

export declare function definePlugin(def: PluginDefinition): PluginDefinition

/** 所有宿主→插件调用都带它。@see D166 */
export interface CallContext {
  /** 宿主取消(离页、改关键词、超时)时触发。@see D166 D53 */
  readonly signal: AbortSignal
}

/** 返回值给宿主界面用(如「重新勾选源」把候选列表交给宿主的勾选框);没有就不返回。 */
export type CommandHandler = (args: Record<string, Json>, ctx: CallContext) => void | Json | Promise<void | Json>

// ════════════════════════════════════════════════════════════════════
// 3. app —— 版本、平台、能力、应用设置 @see D179 D93 D427 D428
// ════════════════════════════════════════════════════════════════════

export interface Capabilities {
  webview: boolean
  canvas: boolean
  shaders: boolean
  touch: boolean
  tv: boolean
  /** 桌面子窗口:置顶/穿透等在 Wayland 下可能为 false。@see D500 */
  windowTopmost: boolean
  windowClickThrough: boolean
  background: boolean
  /** 各扩展组件是否已装。@see D380 */
  components: Record<ExtComponent, boolean>
}

export declare namespace app {
  const version: string
  const platform: Platform
  const formFactor: FormFactor
  const capabilities: Capabilities
  /** 应用界面语言(BCP 47)。 */
  const locale: string
  /** 系统「减少动态效果」。@see D428 */
  const reducedMotion: boolean
  /** 开发者模式是否开着。@see D80 */
  const devMode: boolean
  /** 读应用设置(键见 SDK 设置清单)。@see D92 */
  function getSetting(key: string): Json
  /**
   * 写应用设置。账号 / 服务器 / 网络代理类键一律拒绝(permission)。
   * 插件改设置不标注、不还原。@see D93 D427
   */
  function setSetting(key: string, value: Json): void
}

// ════════════════════════════════════════════════════════════════════
// 4. 网络:fetch 扩展 @see D63 D96 D97 D208 D247 D252 D454 D455 D456
// ════════════════════════════════════════════════════════════════════

/** 通过 `fetch(url, { lp: {...} })` 传入的扩展字段。 */
export interface LpFetchOptions {
  /** 忽略证书错误。@see D97 */
  insecure?: boolean
  /** 不走应用代理设置。@see D96 */
  direct?: boolean
  /** 用哪个 Cookie 罐;默认本插件的罐,数据源调用里默认该源的罐。@see D60 */
  cookieJar?: string
  /** 覆盖默认超时(毫秒):连接 10 000、空闲 30 000,不设整体超时。@see D454 */
  timeout?: { connect?: number; idle?: number; total?: number }
}

declare global {
  interface RequestInit {
    /** LinPlayer 扩展字段。@see D208 */
    lp?: LpFetchOptions
  }
}

// ════════════════════════════════════════════════════════════════════
// 5. 存储 @see D38 D143 D144 D284 D417 D490
// ════════════════════════════════════════════════════════════════════

/**
 * 每插件 KV,同步 API;写入内存立即生效,宿主后台批量落盘;进备份与同步(后写覆盖)。@see D284 D140
 * 值必须可 JSON 序列化(含 MediaItem 这类接口对象);不可序列化的值(函数、循环引用)抛 invalid。
 */
export declare namespace storage {
  function get<T = unknown>(key: string): T | undefined
  function set(key: string, value: unknown): void
  function remove(key: string): void
  function keys(prefix?: string): string[]
}

/** 每插件密钥区:系统密钥库加密;不跨设备同步;随「带账号」档备份。@see D38 D490 */
export declare namespace secrets {
  function get(key: string): string | undefined
  function set(key: string, value: string): void
  function remove(key: string): void
}

export interface PluginDir {
  readText(path: string): Promise<string>
  readBytes(path: string): Promise<ArrayBuffer>
  write(path: string, data: string | ArrayBuffer): Promise<void>
  /** 流式写(EPG 这类几十 MB 的数据)。@see D274 */
  openWrite(path: string): Promise<WritableStream<Uint8Array>>
  exists(path: string): Promise<boolean>
  remove(path: string): Promise<void>
  list(dir?: string): Promise<{ name: string; size: number; dir: boolean }[]>
}

/** 文件:只有私有目录与系统选择器。@see D144 D245 D536 */
export declare namespace files {
  /** `data/`:进备份与同步。 */
  const data: PluginDir
  /** `cache/`:可一键清理,不进备份与同步。 */
  const cache: PluginDir
  /** 系统文件选择器,返回用户选的那一个文件;取消返回 null。 */
  function pick(opts?: { accept?: string[] }): Promise<{ name: string; bytes: ArrayBuffer } | null>
  /** 把内容交给用户选的位置。 */
  function save(name: string, data: string | ArrayBuffer): Promise<boolean>
  /** 手机存相册,桌面弹保存框。 */
  function saveImage(png: ArrayBuffer, name?: string): Promise<boolean>
}

/** 包内资源。@see 4.1 */
export declare namespace assets {
  /** 数据通道上的地址(给 <Image>、mpv、WebView 用)。 */
  function url(path: string): string
  function readText(path: string): Promise<string>
  function readBytes(path: string): Promise<ArrayBuffer>
}

/** Cookie 罐:每数据源/插件一个,WebView 与 fetch 共用,存密钥区。@see D60 */
export declare namespace cookies {
  function get(jar: string, url: string): Promise<Record<string, string>>
  function set(jar: string, url: string, cookie: string): Promise<void>
  function clear(jar: string): Promise<void>
}

// ════════════════════════════════════════════════════════════════════
// 6. 注册表 @see D17 D237 D272 D273 D275
// ════════════════════════════════════════════════════════════════════

/** 宿主定义的通道与其条目格式(宿主校验)。 */
export interface HostChannels {
  'live.channels': LiveChannelSource
  'live.epg': EpgSource
}

export type ChannelName = keyof HostChannels | `${string}/${string}/${string}`
type ChannelValue<C> = C extends keyof HostChannels ? HostChannels[C] : unknown

/**
 * 推模式:写入者把数据交给宿主落盘,读者随时读,写入者不会被唤醒。
 * 插件命名通道必须以自己 id 为前缀;写入者被禁用/卸载时宿主清掉它写的条目。
 */
export declare namespace registry {
  function put<C extends ChannelName>(channel: C, key: string, value: ChannelValue<C>): void
  function remove(channel: ChannelName, key: string): void
  function list<C extends ChannelName>(channel: C): { writer: PluginId; key: string; value: ChannelValue<C> }[]
  function watch<C extends ChannelName>(channel: C, cb: () => void): Disposable
}

// ════════════════════════════════════════════════════════════════════
// 7. 子运行时与打包 @see D127 D316
// ════════════════════════════════════════════════════════════════════

export interface JsContext extends Disposable {
  /** 执行一段脚本(通常是 js.bundle 的产物),返回最后一个表达式的值。 */
  run(code: string, filename?: string): Promise<Json>
  /** 调用子运行时里的全局函数。受 30 秒预算管控。 */
  call(fn: string, ...args: Json[]): Promise<Json>
}

export declare namespace js {
  /**
   * 建隔离 goja。子运行时在自己的 goroutine,允许同步阻塞 API:
   * 注入 `withSyncHost: true` 时得到 `req()`(同步 HTTP)、`pdfh/pdfa/pd`、`joinUrl`、`local` 等 drpy 所需宿主函数。
   */
  function createContext(opts?: { globals?: Record<string, unknown>; withSyncHost?: boolean; cookieJar?: string }): Promise<JsContext>
  /** esbuild 把 ESM(含自定义 scheme 如 assets://)打成 ES2017 IIFE。 */
  function bundle(entry: string, opts: { resolve: (path: string, importer: string) => Promise<string | null>; format?: 'iife'; globalName?: string }): Promise<string>
}

// ════════════════════════════════════════════════════════════════════
// 8. HTML 解析与加解密(Go 实现)@see D98 D99
// ════════════════════════════════════════════════════════════════════

export interface HtmlNode {
  text(): string
  html(): string
  attr(name: string): string | undefined
  querySelector(sel: string): HtmlNode | null
  querySelectorAll(sel: string): HtmlNode[]
}

export declare namespace html {
  /** drpy 选择器语法取单值(支持 `:eq(n)` `&&` `||`)。 */
  function pdfh(html: string, rule: string, baseUrl?: string): string
  /** drpy 选择器取列表(外层 HTML 片段)。 */
  function pdfa(html: string, rule: string): string[]
  /** drpy 选择器取链接并补全为绝对地址。 */
  function pd(html: string, rule: string, baseUrl: string): string
  /** CSS 选择器 DOM。 */
  function parse(html: string): HtmlNode
  /** XPath,返回字符串结果。 */
  function xpath(html: string, expr: string): string[]
}

export type Bytes = string | ArrayBuffer
export type TextEncoding = 'utf8' | 'base64' | 'hex' | 'gbk'

export declare namespace crypt {
  function md5(data: Bytes): string
  function sha1(data: Bytes): string
  function sha256(data: Bytes): string
  function hmac(algo: 'md5' | 'sha1' | 'sha256', key: Bytes, data: Bytes): string
  function aesEncrypt(data: Bytes, opts: { key: Bytes; iv?: Bytes; mode: 'cbc' | 'ecb' | 'ctr' | 'gcm'; padding?: 'pkcs7' | 'none'; out?: 'base64' | 'hex' }): string
  function aesDecrypt(data: Bytes, opts: { key: Bytes; iv?: Bytes; mode: 'cbc' | 'ecb' | 'ctr' | 'gcm'; padding?: 'pkcs7' | 'none'; in?: 'base64' | 'hex'; out?: TextEncoding }): string
  function desEncrypt(data: Bytes, opts: { key: Bytes; iv?: Bytes; mode: 'cbc' | 'ecb'; triple?: boolean }): string
  function desDecrypt(data: Bytes, opts: { key: Bytes; iv?: Bytes; mode: 'cbc' | 'ecb'; triple?: boolean }): string
  function rsaEncrypt(data: Bytes, publicKeyPem: string, opts?: { padding?: 'pkcs1' | 'oaep' }): string
  function rsaDecrypt(data: Bytes, privateKeyPem: string, opts?: { padding?: 'pkcs1' | 'oaep' }): string
  function base64Encode(data: Bytes): string
  function base64Decode(data: string, out?: TextEncoding): string
  /** GBK ↔ UTF-8。 */
  function gbkEncode(text: string): ArrayBuffer
  function gbkDecode(data: ArrayBuffer): string
}

// ════════════════════════════════════════════════════════════════════
// 9. WebView @see D59 D62 D177 D178 D374 D375 D496 D497
// ════════════════════════════════════════════════════════════════════

export interface WebViewOptions {
  userAgent?: string
  headers?: Headers
  /** 与 fetch 共用的 Cookie 罐。@see D60 */
  cookieJar?: string
  /** 屏蔽这些请求(广告域名、图片)加快嗅探。@see D496 */
  block?: { urlPatterns?: string[]; resourceTypes?: ('image' | 'font' | 'media' | 'stylesheet')[] }
  /** document-start 注入的脚本。@see D496 */
  injectScript?: string
}

export interface SniffResult {
  url: string
  headers: Headers
}

/** 隐藏 WebView 全局最多 3 个,超出排队,排队时间计入调用超时。不可用时抛 unsupported。 */
export declare namespace webview {
  /**
   * 嗅探:隐藏加载页面、拦截请求抓视频地址(默认匹配 m3u8/mp4/flv)。
   * 超过 `visibleAfter`(默认 15 秒)仍没抓到且 `allowVisible` 时,跳整页可见 WebView 让用户过验证/点播放;
   * TV 上开虚拟鼠标。@see D59 D374
   */
  function sniff(url: string, opts?: WebViewOptions & { match?: string[]; visibleAfter?: number; allowVisible?: boolean; timeout?: number }): Promise<SniffResult>
  /** 加载页面后执行 JS 取值。@see D62 */
  function evaluate(url: string, script: string, opts?: WebViewOptions & { waitFor?: string; timeout?: number }): Promise<Json>
  /** 打开整页可见 WebView(过盾、登录),用户关闭或 `until` 条件满足时返回 Cookie 与 localStorage。@see D62 D68 D374 */
  function open(url: string, opts?: WebViewOptions & { title?: string; until?: { urlMatches?: string; cookieName?: string } }): Promise<{ cookies: Record<string, string>; localStorage: Record<string, string>; finalUrl: string }>
}

// ════════════════════════════════════════════════════════════════════
// 10. 代理路由 @see D37 D246 D498 D499
// ════════════════════════════════════════════════════════════════════

export declare namespace proxy {
  /**
   * 注册插件代理路由。handler 可返回流式 body;Range 与 seek 由宿主处理(宿主按 Range 截取或把 Range 头转给 handler)。
   */
  function route(name: string, handler: (req: Request & { query: Record<string, string> }) => Response | Promise<Response>): Disposable
  /** 本机回环 + 插件路径 + 随机会话 token 的地址,给 mpv / 图片加载器用。 */
  function url(name: string, query?: Record<string, string>): string
}

// ════════════════════════════════════════════════════════════════════
// 11. TVBox spider 宿主能力 @see D348~D355 D534
// ════════════════════════════════════════════════════════════════════

export interface SpiderHandle extends Disposable {
  /** 方法名与参数沿用 catvod Spider 约定,返回 catvod 的 JSON 字符串。墙钟 30 秒。@see D355 */
  call(method: 'init' | 'homeContent' | 'homeVideoContent' | 'categoryContent' | 'detailContent' | 'searchContent' | 'playerContent' | 'liveContent' | 'proxy' | 'action', ...args: Json[]): Promise<string>
}

export declare namespace spider {
  /** 本设备能否跑这种 spider(Android jar 原生;桌面 jar 需组件且 spike 通过;py 需组件)。 */
  function supported(kind: 'jar' | 'py'): Promise<{ ok: boolean; reason?: string; component?: ExtComponent }>
  /** 加载;jar 按 md5 缓存在插件 cache/。@see D352 */
  function load(opts: { kind: 'jar' | 'py'; url: string; md5?: string; api: string; ext?: string; sourceKey: string }): Promise<SpiderHandle>
  /** jar 兼容 proxy 入口的实际端口(占不到 TVBox 约定端口时是动态端口)。@see D353 */
  function compatProxyPort(): number
}

// ════════════════════════════════════════════════════════════════════
// 12. 扩展组件 @see D380 D381 D382
// ════════════════════════════════════════════════════════════════════

export type ExtComponent = 'interp' | 'jar-runtime' | 'python' | 'geckoview' | 'whisper' | 'ffmpeg'

export declare namespace ext {
  function status(c: ExtComponent): Promise<{ installed: boolean; version?: string; size?: number }>
  /** 未装时弹下载确认;用户拒绝抛 unsupported。 */
  function ensure(c: ExtComponent): Promise<void>
}

// ════════════════════════════════════════════════════════════════════
// 13. 系统能力 @see D196 D197 D536 D444
// ════════════════════════════════════════════════════════════════════

export declare namespace system {
  function openUrl(url: string): Promise<void>
  /** 深链或 Android Intent。 */
  function openApp(target: string | { action: string; data?: string; package?: string; extras?: Record<string, string> }): Promise<boolean>
  const clipboard: {
    read(): Promise<string>
    write(text: string): Promise<void>
  }
  function share(content: { text?: string; url?: string; image?: ArrayBuffer; title?: string }): Promise<void>
}

// ════════════════════════════════════════════════════════════════════
// 14. 事件 @see D94 D458
// ════════════════════════════════════════════════════════════════════

export interface AppEvents {
  'player.start': NowPlaying
  'player.pause': NowPlaying
  'player.resume': NowPlaying
  'player.buffering': { buffering: boolean }
  'player.end': NowPlaying & { reason: 'eof' | 'stop' | 'error' }
  'player.episodeChange': NowPlaying
  'player.tracks': PlayerTracks
  'player.osdVisible': { visible: boolean }
  'player.pip': { active: boolean }
  'nav.page': { route: string; params: Record<string, Json> }
  'nav.detail': { item: MediaItem }
  'server.change': { server: ServerInfo }
  'app.foreground': {}
  'app.background': {}
}

export declare namespace events {
  function on<E extends keyof AppEvents>(name: E, cb: (data: AppEvents[E]) => void): Disposable
}

// ════════════════════════════════════════════════════════════════════
// 15. 调试 API(只在开发者模式存在)@see D128
// ════════════════════════════════════════════════════════════════════

/** 调试面板四块(日志/网络/UI 树/性能与存储)靠它。@see D81 */
export interface DebugApi {
  plugins(): { id: PluginId; version: string; state: string }[]
  logs(id: PluginId, opts?: { level?: 'debug' | 'info' | 'warn' | 'error'; since?: number }): { ts: number; level: string; msg: string; stack?: string }[]
  requests(id: PluginId): { ts: number; method: string; url: string; status: number; ms: number; reqHeaders: Headers; resHeaders: Headers; body?: string }[]
  uiTree(surfaceId: string): Json
  surfaces(id?: PluginId): { id: string; plugin: PluginId; kind: string }[]
  kvGet(id: PluginId): Record<string, Json>
  kvSet(id: PluginId, key: string, value: Json): void
  perf(id: PluginId): { call: string; ms: number; heapDelta: number; ts: number }[]
  onLog(cb: (e: { plugin: PluginId; level: string; msg: string }) => void): Disposable
}

/** 开发者模式关着时为 undefined。 */
export declare const debug: DebugApi | undefined

// ════════════════════════════════════════════════════════════════════
// 16. 离屏 Canvas @see D104 D536
// ════════════════════════════════════════════════════════════════════

export declare namespace canvas {
  /** 离屏绘制,导出 PNG。ctx 是 Canvas 2D 子集。 */
  function renderToPng(width: number, height: number, draw: (ctx: Canvas2D) => void | Promise<void>): Promise<ArrayBuffer>
}

// ════════════════════════════════════════════════════════════════════
// 17. 桌面与 Android 系统入口 @see D501 D505 D506 D510
// ════════════════════════════════════════════════════════════════════

export declare namespace desktop {
  /** 0~1;null 恢复。 */
  function setTaskbarProgress(value: number | null): void
  function setTaskbarBadge(text: string | null): void
  function setWindowTitle(text: string | null): void
}

export declare namespace widgets {
  /** 推小组件数据(模板在 manifest 声明:posterRow / list / card)。@see D506 */
  function update(id: string, data: { title?: string; items: { title: string; subtitle?: string; image?: string; command?: string; args?: Record<string, Json> }[] }): Promise<void>
}

/** 壁纸内容。用户对任何壁纸都能调模糊度与压暗(宿主设置)。@see D212 D441 D442 D443 */
export type WallpaperContent =
  | { kind: 'image'; image: ImageRef }
  /** 视频壁纸:低分辨率,进播放页/后台/省电时宿主自动暂停。@see D443 */
  | { kind: 'video'; url: string; headers?: Headers }
  /** 程序生成的动态壁纸:插件的 Canvas 区块。 */
  | { kind: 'canvas'; block: string }
  /** 程序生成的动态壁纸:包内 GLSL 着色器。 */
  | { kind: 'shader'; file: string }

export declare namespace wallpaper {
  /** 动态切换(按时间、按页面、随正在看的片);只有当前选中的壁纸插件调用才生效。@see D442 */
  function set(content: WallpaperContent): Promise<void>
}

export declare namespace tvChannels {
  /** 发布 Android TV 首页推荐行内容。@see D505 */
  function publish(id: string, items: MediaItem[]): Promise<void>
}

// ════════════════════════════════════════════════════════════════════
// 18. 统一数据结构 @see D92 D254 D160 D330 D331 D335 D336 D435
// ════════════════════════════════════════════════════════════════════

export type MediaKind = 'movie' | 'series' | 'season' | 'episode' | 'person' | 'live' | 'folder' | 'other'

export interface ImageRef {
  url: string
  /** 图片请求头(Referer/Cookie…),走官方图片缓存(与 Emby 图片共用磁盘总上限)。@see D87 D422 */
  headers?: Headers
  /** 用该源的 Cookie 罐。@see D60 D87 */
  cookieJar?: string
}

export interface Rating {
  /** 来源名:豆瓣 / IMDb / TMDB / Bangumi…;各家并排显示。@see D435 */
  source: string
  value: number
  max?: number
  url?: string
}

export interface ExternalIds {
  tmdb?: string
  imdb?: string
  douban?: string
  bangumi?: string
  [k: string]: string | undefined
}

/** Emby 与数据源共用的条目结构。图片地址永远不带 api_key。@see D11 D92 D254 */
export interface MediaItem {
  /** 源内 id(不透明)。 */
  id: string
  /** 来源:数据源开放键或 `emby:<服务器id>`。插入别处列表时必须带。@see D282 */
  source?: SourceKey
  kind: MediaKind
  title: string
  originalTitle?: string
  year?: number
  poster?: ImageRef
  backdrop?: ImageRef
  /** 宿主显示前统一去 HTML 标签转纯文本。@see D462 */
  overview?: string
  /** 海报角标(TVBox vod_remarks:「更新至 12 集」「HD」)。@see D330 */
  remarks?: string
  ratings?: Rating[]
  externalIds?: ExternalIds
  genres?: string[]
  countries?: string[]
  directors?: string[]
  actors?: string[]
  episodeCount?: number
  /** 秒。 */
  runtime?: number
  /** Emby 专有(只读):用户数据。 */
  userData?: { played?: boolean; favorite?: boolean; progress?: number; unplayedCount?: number }
}

export interface Episode {
  /** 不透明,原样交回 play()。 */
  id: string
  /** 源给的名字;宿主统一显示成「第 N 集」,日期型与无序号的原样显示。@see D461 */
  name: string
  /** 集序号;不给宿主从 name 解析。用于换源/切线路定位与弹幕匹配。@see D464 D55 */
  index?: number
}

export interface Line {
  id: string
  /** 站点给用户看的线路名(「星空线路」),取不到才用内部代号。@see D331 */
  name: string
  episodes: Episode[]
}

export interface Season {
  id: string
  name: string
  index?: number
  lines: Line[]
}

/** 详情:给 lines 或 seasons 之一;只有一集时详情页不显示选集格。@see D54 D165 D463 */
export interface MediaDetail extends MediaItem {
  lines?: Line[]
  seasons?: Season[]
}

/** 不透明游标翻页:没有 next = 到底。@see D255 */
export interface Page<T> {
  items: T[]
  next?: string
}

export type PosterShape = 'portrait' | 'landscape' | 'square'

export interface FilterDimension {
  key: string
  name: string
  /** 单选/多选(TVBox 全是单选)。@see D334 */
  multi?: boolean
  options: { name: string; value: string }[]
}

export interface Category {
  id: string
  name: string
  /** 海报比例,默认 portrait(2:3)。@see D336 */
  posterShape?: PosterShape
  filters?: FilterDimension[]
}

export interface SubtitleRef {
  url: string
  lang?: string
  title?: string
  headers?: Headers
}

/** play() 的返回:最终可播地址。解析/嗅探由插件自己做。@see D256 D257 D37 */
export interface PlayResult {
  url: string
  headers?: Headers
  /** m3u8 子请求也要带头时,让宿主本地代理转发。@see D37 */
  useProxy?: boolean
  /** 多清晰度,可切,切换保持进度。 */
  qualities?: { name: string; url: string; headers?: Headers }[]
  subtitles?: SubtitleRef[]
  /** 片头片尾(秒)。@see D342 */
  skip?: { introStart?: number; introEnd?: number; outroStart?: number }
  /** 源自带弹幕,作为这一集的一个弹幕源,默认开。@see D433 */
  danmaku?: { url: string; format?: 'xml' | 'json'; headers?: Headers } | { items: DanmakuItem[] }
  /** 进度条预览图。@see D163 */
  thumbnails?: ThumbnailSet
  /** 播放页侧栏「解析」标签要显示的当前解析名(TVBox)。@see D377 */
  parser?: string
}

// ════════════════════════════════════════════════════════════════════
// 19. 数据源 @see D10 D40 D131 D132 D166 D168 D173 D251 D260
// ════════════════════════════════════════════════════════════════════

export interface SourceInfo {
  /** 开放键。 */
  key: SourceKey
  /** 源 id(插件内,不含 /)。@see D153 */
  id: string
  name: string
  /** 插件为这个源存的任意配置(宿主保存、备份、同步)。@see D325 */
  config: Json
  /** 用户在服务器列表手改的 host。@see D176 */
  hostOverride?: string
  /** 属于哪个服务器类型(订阅)。 */
  serverType?: string
  /** 所属分组(一个订阅一个分组)。@see D123 D346 */
  group?: string
}

export interface SourceCallContext extends CallContext {
  readonly source: SourceInfo
}

export interface SourceDraft {
  id: string
  name: string
  config: Json
  group?: string
  /** 分组显示名(一个订阅一组,服务器列表按它折叠)。@see D123 D346 */
  groupName?: string
  /** 「允许聚合」默认值;TVBox 跟随 searchable。@see D235 */
  aggregateDefault?: boolean
  /** 本设备不可用时灰显并写原因。@see D351 */
  unavailableReason?: string
  icon?: string
  /** 禁用预取(有的站多连接会封)。@see D173 */
  disablePrefetch?: boolean
}

export interface DataSourceProvider {
  /**
   * 用户在「添加服务器」提交某服务器类型的表单 → 返回一个或多个源(多个时宿主让用户勾选)。@see D131 D45
   * 多仓配置可返回 `repos`,宿主先让用户勾仓(每个仓一个订阅/分组)再回调;
   * 回调时 `form.$repos` 是用户勾选的仓 id 数组。@see D346
   */
  createSources?(serverType: string, form: Record<string, Json>, ctx: CallContext): Promise<{ sources: SourceDraft[]; repos?: { id: string; name: string }[] }>
  /** 必需。@see D260 */
  detail(id: string, ctx: SourceCallContext): Promise<MediaDetail>
  /** 必需。@see D260 */
  play(req: { item: MediaDetail; lineId: string; episodeId: string }, ctx: SourceCallContext): Promise<PlayResult>
  /** 可选:首页分类与推荐行。@see D167 D344 */
  home?(ctx: SourceCallContext): Promise<{ categories: Category[]; recommended?: MediaItem[] }>
  /** 可选。filters 为用户选中的 {维度 key: 值[]}。@see D57 D255 */
  category?(req: { categoryId: string; filters: Record<string, string[]>; cursor?: string }, ctx: SourceCallContext): Promise<Page<MediaItem>>
  /** 可选;聚合搜索与源内搜索是同一个动词。@see D258 */
  search?(req: { keyword: string; cursor?: string }, ctx: SourceCallContext): Promise<Page<MediaItem>>
  /** 可选;没实现时点演员名在当前源搜。@see D168 D340 */
  person?(req: { name: string; id?: string; cursor?: string }, ctx: SourceCallContext): Promise<Page<MediaItem>>
}

/** 插件运行中增删源(订阅刷新)。@see D132 D230 D231 */
export declare namespace sources {
  function list(): SourceInfo[]
  function add(draft: SourceDraft, serverType?: string): Promise<void>
  function update(id: string, patch: Partial<SourceDraft>): Promise<void>
  /** 删掉源:它的收藏与观看记录保留。@see D230 D333 */
  function remove(id: string): Promise<void>
}

// ════════════════════════════════════════════════════════════════════
// 20. 提供者 @see 第 10 章
// ════════════════════════════════════════════════════════════════════

export interface MatchClues {
  title: string
  year?: number
  season?: number
  episode?: number
  externalIds?: ExternalIds
  fileName?: string
  /** 秒。 */
  duration?: number
  fileHash?: string
  /** 字幕语言偏好(字幕提供者用)。@see D187 */
  preferredLangs?: string[]
}

export interface DanmakuCandidate {
  id: string
  title: string
  episodeTitle?: string
  count?: number
}

export interface DanmakuItem {
  /** 秒。 */
  time: number
  text: string
  mode?: 'scroll' | 'top' | 'bottom'
  color?: number
  user?: string
}

/** 弹幕源。@see D66 D184 D185 D186 D248 */
export interface DanmakuProvider {
  /** 宿主按片名+集数自动选最佳候选;用户手动纠正的结果按整部剧记住。@see D56 */
  match(clues: MatchClues, ctx: CallContext): Promise<DanmakuCandidate[]>
  /** 按时间窗交付;每批条数插件控制(建议 ≤3000)。@see D186 */
  load(candidate: DanmakuCandidate, window: { from: number; to: number }, ctx: CallContext): Promise<DanmakuItem[]>
  /** 可选:代发弹幕;TV 上不显示发送框。@see D248 D249 D250 */
  send?(candidate: DanmakuCandidate, item: DanmakuItem, ctx: CallContext): Promise<void>
}

export interface SubtitleCandidate {
  id: string
  name: string
  lang: string
  format: 'srt' | 'ass' | 'vtt' | string
  score?: number
}

/** 字幕。@see D73 D187 D188 */
export interface SubtitleProvider {
  search(clues: MatchClues, ctx: CallContext): Promise<SubtitleCandidate[]>
  download(c: SubtitleCandidate, ctx: CallContext): Promise<{ name: string; content: string | ArrayBuffer }>
}

/** 元数据:模式由插件在 manifest 声明(supplement/override/complete)。@see D74 D76 D192 D193 D436 */
export interface MetadataProvider {
  lookup(item: MediaItem, ctx: CallContext): Promise<Partial<Pick<MediaItem, 'title' | 'originalTitle' | 'year' | 'overview' | 'poster' | 'backdrop' | 'ratings' | 'genres' | 'countries' | 'directors' | 'actors' | 'externalIds'>>>
  /** 搜索别名:中文名 → Emby 条目,宿主存本地并在搜索时匹配。@see D193 */
  aliases?(item: MediaItem, ctx: CallContext): Promise<string[]>
}

export interface DownloadStatus {
  state: 'queued' | 'running' | 'paused' | 'done' | 'failed'
  /** 0~1。 */
  progress: number
  bytesPerSecond?: number
  error?: string
  /** 完成后可播地址,进官方「已下载」列表。@see D78 */
  playableUrl?: string
}

/** 下载后端(只用于非 Emby 来源)。@see D25 D77 D189 D190 D191 */
export interface DownloadProvider {
  submit(req: { url: string; headers?: Headers; fileName: string; item?: MediaItem }, ctx: CallContext): Promise<string>
  status(taskId: string, ctx: CallContext): Promise<DownloadStatus>
  pause(taskId: string): Promise<void>
  resume(taskId: string): Promise<void>
  remove(taskId: string): Promise<void>
}

/** 片头片尾(对 Emby 片也生效)。@see D537 */
export interface SkipSegmentsProvider {
  segments(item: MediaItem & { episodeIndex?: number }, ctx: CallContext): Promise<{ introStart?: number; introEnd?: number; outroStart?: number } | null>
}

export interface ThumbnailSet {
  /** 秒。 */
  interval: number
  /** 雪碧图或单张图列表。 */
  tiles: { url: string; columns?: number; rows?: number; width: number; height: number }[]
}

/** 进度条预览图(对 Emby 片也生效;Emby 有 trickplay 时优先 Emby)。@see D163 D538 */
export interface ThumbnailProvider {
  thumbnails(item: MediaItem, ctx: CallContext): Promise<ThumbnailSet | null>
}

export interface Providers {
  danmaku?: DanmakuProvider
  subtitles?: SubtitleProvider
  metadata?: MetadataProvider
  download?: DownloadProvider
  skipSegments?: SkipSegmentsProvider
  thumbnails?: ThumbnailProvider
}

/** 直播频道源(注册表 live.channels 的条目):频道、分组、换台、EPG、回看全套由直播插件实现。@see D43 D112 D113 D468 D111 */
export type LiveChannelSource =
  | { kind: 'url'; name: string; url: string; headers?: Headers; format?: 'm3u' | 'txt'; epg?: string; catchup?: CatchupTemplate }
  | { kind: 'list'; name: string; groups: { name: string; channels: LiveChannel[] }[]; epg?: string }
  | { kind: 'proxy'; name: string; proxyUrl: string } // TVBox 代理直播,走 jar。@see D534

export interface LiveChannel {
  name: string
  tvgId?: string
  logo?: string
  urls: { url: string; headers?: Headers }[]
  catchup?: CatchupTemplate
}

/** 回看/时移:append / shift / default 模板 / flussonic。@see D111 */
export interface CatchupTemplate {
  type: 'append' | 'shift' | 'default' | 'flussonic'
  source?: string
  days?: number
}

/** EPG 源(注册表 live.epg 的条目)。@see D108 D495 */
export type EpgSource =
  | { kind: 'xmltv'; url: string }
  | { kind: 'diyp'; url: string }
  | { kind: 'data'; programs: { channel: string; start: number; end: number; title: string; desc?: string }[] }

// ════════════════════════════════════════════════════════════════════
// 21. 钩子 @see D277 D278 D280 D281 D282 D474 D515
// ════════════════════════════════════════════════════════════════════

export type Route = string

export interface Hooks {
  /** 导航拦截:返回 null 放行,返回新路由改跳;第一个改跳的生效。凭据页不经过拦截器。@see D277 D407 */
  navigate?(target: { route: Route; params: Record<string, Json> }, ctx: CallContext): { route: Route; params?: Record<string, Json> } | null | Promise<{ route: Route; params?: Record<string, Json> } | null>
  /** 列表变换:按用户顺序串联;可增删改排序,插入别处条目须带 source。@see D278 D282 */
  listTransform?(list: { name: string; items: MediaItem[] }, ctx: CallContext): MediaItem[] | Promise<MediaItem[]>
  /** 卡片角标:必须同步返回,只用已缓存数据。@see D474 */
  cardBadge?(item: MediaItem): { text: string; tone?: 'neutral' | 'accent' | 'ok' | 'warn' } | null
}

/** m3u8 过滤器:串联,单个出错/超时跳过;删掉超总时长 30% 整体放弃。@see D102 D493 D494 */
export type M3u8Filter = (m3u8: { url: string; text: string; source?: SourceKey }, ctx: CallContext) => string | Promise<string>

export interface SearchAction {
  title: string
  icon?: string
  command: string
  args?: Record<string, Json>
}

export interface ExternalInput {
  kind: 'share' | 'drop' | 'file' | 'clipboard'
  text?: string
  url?: string
  file?: { name: string; bytes: ArrayBuffer }
}

export interface BackgroundContext extends CallContext {
  /** 定时任务 60 秒墙钟预算;常驻服务不受限。@see D509 */
  readonly deadline?: number
}

// ════════════════════════════════════════════════════════════════════
// 22. 媒体库、Emby、服务器 @see D92 D93 D287 D288 D472
// ════════════════════════════════════════════════════════════════════

export interface ServerInfo {
  /** 跨设备稳定:Emby 为 ServerId+UserId 的哈希,数据源为开放键。@see D287 */
  id: string
  /** 用户起的显示名。不给地址与用户名。 */
  name: string
  type: 'emby' | 'source'
}

export declare namespace servers {
  function list(): ServerInfo[]
  function current(): ServerInfo | null
}

export interface HistoryEntry {
  item: MediaItem
  /** 0~1。 */
  progress: number
  positionSec: number
  updatedAt: number
  /** 来源已移除(卸载插件/删订阅)。@see D333 */
  sourceRemoved?: boolean
}

export declare namespace media {
  /** 有限查询,返回统一结构;数据源服务器共用。@see D92 */
  function search(keyword: string, opts?: { server?: string; limit?: number }): Promise<MediaItem[]>
  function getItem(id: string, opts?: { server?: string }): Promise<MediaDetail>
  function getChildren(id: string, opts?: { server?: string; cursor?: string }): Promise<Page<MediaItem>>
  function getLatest(opts?: { server?: string; limit?: number }): Promise<MediaItem[]>
  /** 观看记录与收藏(跨服、含数据源)。 */
  function history(opts?: { server?: string; cursor?: string }): Promise<Page<HistoryEntry>>
  function favorites(opts?: { server?: string; cursor?: string }): Promise<Page<MediaItem>>
  /** 写:宿主代发到 Emby 或写本地。@see D93 */
  function setPlayed(item: MediaItem, played: boolean): Promise<void>
  function setFavorite(item: MediaItem, favorite: boolean): Promise<void>
  function setProgress(item: MediaItem, positionSec: number): Promise<void>
}

export declare namespace emby {
  /**
   * 只读 GET 代发:宿主带 token 发,插件看不到 token;响应里 token / api_key 地址 / 流地址 /
   * 服务器地址类字段脱敏;取流/下载/转码类端点拒绝(permission)。@see D472
   */
  function request(path: string, params?: Record<string, string | number | boolean>, opts?: { server?: string }): Promise<Json>
}

/**
 * Trakt / Bangumi 代发(SPEC 17.3 18.2,D365)。
 *
 * 账号连接与 token 刷新在宿主,用户只登一次;插件借宿主的 token 发请求,
 * **拿不到 token 本身**。没连账号时抛 `auth`,不是回空 —— 回空的话插件会把
 * 「没登录」和「这个条目没有记录」当成同一件事。
 */
export declare namespace trakt {
  function request(method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: Json): Promise<Json>
}

export declare namespace bangumi {
  function request(method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: Json): Promise<Json>
}

export declare namespace download {
  /** 让官方下载器下 Emby 片;与官方下载同一道权限门,无权限抛 permission。@see D309 D190 */
  function enqueue(itemId: string, opts?: { server?: string }): Promise<void>
}

export declare namespace cast {
  /** 弹官方投屏设备选择,投当前正在播的。@see D311 */
  function open(): Promise<void>
}

// ════════════════════════════════════════════════════════════════════
// 23. OAuth 与借用账号 @see D199 D203 D204 D365
// ════════════════════════════════════════════════════════════════════

export declare namespace oauth {
  /**
   * 走自建中转:桌面/手机浏览器跳转 + linplayer:// 回调;TV 设备码(二维码 + 短码)。
   * client secret 只在中转服务端。返回的 token 由插件存密钥区。
   */
  function authorize(provider: string, opts?: { scopes?: string[]; deviceCode?: boolean }): Promise<{ accessToken: string; refreshToken?: string; expiresAt?: number }>
  function refresh(provider: string, refreshToken: string): Promise<{ accessToken: string; refreshToken?: string; expiresAt?: number }>
}

interface BorrowedAccount {
  /** 宿主已登录的账号;未登录为 false。 */
  connected(): Promise<boolean>
  /** 宿主带 token 代发,插件看不到 token。 */
  request(method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: Json): Promise<Json>
  /** 打开宿主的账号连接页。 */
  connect(): Promise<void>
}

export declare const trakt: BorrowedAccount
export declare const bangumi: BorrowedAccount

// ════════════════════════════════════════════════════════════════════
// 24. 播放器 @see D32 D33 D95 D106 D161 D164 D298 D299 D301 D302 D310 D487 D488 D538
// ════════════════════════════════════════════════════════════════════

export interface NowPlaying {
  item: MediaItem
  source?: SourceKey
  lineId?: string
  episodeId?: string
  positionSec: number
  durationSec: number
}

export interface PlayerTracks {
  audio: { id: number; title?: string; lang?: string; selected: boolean }[]
  subtitle: { id: number; title?: string; lang?: string; external: boolean; selected: boolean }[]
}

export type PlayerPanel = 'subtitles' | 'audio' | 'speed' | 'enhance' | 'danmaku' | 'episodes' | 'lines' | 'more'

export declare namespace player {
  /**
   * 任意 mpv 属性读。脱敏清单(path、stream-open-filename、http-header-fields、playlist* 等)返回脱敏值。@see D32
   */
  function get(prop: string): Promise<Json>
  /**
   * 任意 mpv 属性写;宿主记账「改之前的值」,插件停用时还原;多方改后写生效。@see D33 D299 D301 D302
   * 改 glsl-shaders 视为选中本插件的着色器链,走互斥提示。@see D298
   */
  function set(prop: string, value: Json): Promise<void>
  /** mpv 命令透传;loadfile/loadlist/playlist-* 不开放。@see D32 */
  function command(name: string, ...args: Json[]): Promise<Json>
  /** 订阅属性;time-pos 这类高频值默认 4Hz、上限 10Hz。@see D161 */
  function observe(prop: string, cb: (value: Json) => void, opts?: { hz?: number }): Disposable
  function state(): NowPlaying | null
  function tracks(): PlayerTracks
  /** 播 Emby 条目或数据源条目,宿主取流。@see D95 */
  function play(item: MediaItem | string, opts?: { server?: string; startSec?: number; lineId?: string; episodeId?: string }): Promise<void>
  /** 播任意地址,进官方播放页;给 recordKey 才记进度与历史。@see D310 */
  function playUrl(url: string, headers?: Headers, meta?: { title?: string; poster?: ImageRef; recordKey?: string; subtitles?: SubtitleRef[] }): Promise<void>
  /** 单帧截图(PNG)。不能流式逐帧取。@see D106 */
  function screenshot(): Promise<ArrayBuffer>
  /** 打开官方子面板(插件 OSD 放按钮即可)。@see D67 */
  function openPanel(panel: PlayerPanel): void
  /** 插件自管 OSD 显隐时使用。@see D162 */
  function setOsdVisible(visible: boolean): void
  /** 字幕轨全文:宿主带 token 拉取(含 Emby 内封字幕导出)。@see D487 */
  function getSubtitleText(trackId?: number): Promise<{ format: 'srt' | 'ass' | 'vtt'; text: string }>
  /** 挂字幕:存宿主字幕缓存再 sub-add;按条目记住。@see D164 D188 */
  function addSubtitle(content: string | ArrayBuffer, opts: { format: 'srt' | 'ass' | 'vtt'; lang?: string; title?: string; select?: boolean }): Promise<number>
  /** Whisper 本地转写当前条目(需 whisper + ffmpeg 组件)。@see D488 */
  function transcribe(opts?: { lang?: string; model?: string; onProgress?: (p: number) => void }): Promise<{ format: 'srt'; text: string }>
  /** 抽帧(需 ffmpeg 组件,宿主带 token 取流)。@see D538 */
  function extractFrames(item: MediaItem | string, times: number[], opts?: { width?: number; server?: string }): Promise<ArrayBuffer[]>
}

// ════════════════════════════════════════════════════════════════════
// 25. 导航与页面 @see D84 D85 D133 D219 D220 D305 D407 D500
// ════════════════════════════════════════════════════════════════════

export interface PageOptions {
  title?: string
  /** 全屏/沉浸:隐藏侧栏与状态栏。 */
  immersive?: boolean
  orientation?: 'auto' | 'portrait' | 'landscape'
  keepAwake?: boolean
  /** TV/手机铺满,不加安全边距。@see D425 D426 */
  edgeToEdge?: boolean
}

export declare namespace nav {
  /**
   * 路由是官方路由名(附录 20.3)或 `作者/名字:页面id`。可以去任何官方页(含凭据页);
   * 预填只允许插件自己服务器类型的字段。@see D84 D407
   */
  function push(route: Route, params?: Record<string, Json>): void
  function replace(route: Route, params?: Record<string, Json>): void
  function back(): void
  /** 设置当前插件页的页面选项;离开页面自动恢复。@see D219 */
  function setPageOptions(opts: PageOptions): void
  /** 拦截返回键:只对插件自己的页面生效;回调返回 false 阻止返回。@see D85 */
  function onBack(cb: () => boolean | Promise<boolean>): Disposable
  /** 侧栏入口角标:数字 / 红点 / 清除;收进「更多」时冒泡。@see D305 */
  function setBadge(sidebarId: string, badge: number | 'dot' | null): void
}

// ════════════════════════════════════════════════════════════════════
// 26. 反馈与窗口 @see D86 D195 D220 D500 D547
// ════════════════════════════════════════════════════════════════════

export declare namespace ui {
  function toast(text: string, opts?: { tone?: 'info' | 'ok' | 'warn' | 'error'; durationMs?: number }): void
  function confirm(opts: { title: string; message?: string; ok?: string; cancel?: string; danger?: boolean }): Promise<boolean>
  function prompt(opts: { title: string; message?: string; placeholder?: string; value?: string; secret?: boolean }): Promise<string | null>
  function select<T extends string>(opts: { title: string; options: { value: T; label: string }[]; multi?: false }): Promise<T | null>
  /**
   * 系统通知:每插件每小时最多 5 条,超出折叠;Android 13+ 首次由宿主申请权限,被拒降级 Toast。
   * 后台只能在 D504 的用途里发。@see D86 D195 D547 D505
   */
  function notify(opts: { title: string; body?: string; image?: ImageRef; command?: string; args?: Record<string, Json>; actions?: { title: string; command: string; args?: Record<string, Json> }[] }): Promise<void>
  /**
   * 桌面子窗口;手机/TV 降级为整页。@see D220 D500
   */
  function openWindow(blockId: string, opts?: { title?: string; width?: number; height?: number; topmost?: boolean; frameless?: boolean; transparent?: boolean; clickThrough?: boolean; rememberBounds?: boolean }): Promise<{ close(): void }>
}

// ════════════════════════════════════════════════════════════════════
// 27. UI:元素、样式、组件 @see D23 D41 D44 D88~D91 D104 D134 D135 D218 D268 D269 D445
// ════════════════════════════════════════════════════════════════════

/** JSX 产物(Preact VNode)。 */
export interface LpElement { readonly __lp: true }
export type Child = LpElement | string | number | boolean | null | undefined | Child[]

export interface PageProps { params: Record<string, Json> }
export type PageComponent = (props: PageProps) => Child
export interface BlockProps {
  /** 锚点/详情区块拿到当前条目的完整统一数据。@see D160 */
  item?: MediaItem
  /** 覆盖层/OSD 拿到播放状态。 */
  nowPlaying?: NowPlaying
  params?: Record<string, Json>
}
/** 每个区块是一个错误边界:崩了只在那块显示「出错 [重试] [禁用]」。@see D136 */
export type BlockComponent = (props: BlockProps) => Child

/** 主题 token 引用:'token:radius.card'。@see D89 */
export type TokenRef = `token:${string}`
export type Color = string | TokenRef
export type Length = number | TokenRef | `${number}%` | 'auto'
export type Easing = 'standard' | 'decelerate' | 'accelerate' | 'linear' | [number, number, number, number]

export interface Transition { props: (keyof Style)[]; duration: number; easing?: Easing; delay?: number }
export interface Animation { keyframes: Partial<Style>[]; duration: number; iterations?: number | 'infinite'; easing?: Easing }

/** 我们定义的样式子集,两端都能映射;可写任意值。@see D89 D90 D91 D88 */
export interface Style {
  direction?: 'row' | 'column'
  justify?: 'start' | 'center' | 'end' | 'between' | 'around' | 'evenly'
  align?: 'start' | 'center' | 'end' | 'stretch'
  gap?: Length
  grow?: number
  shrink?: number
  basis?: Length
  wrap?: boolean
  width?: Length; height?: Length
  minWidth?: Length; maxWidth?: Length; minHeight?: Length; maxHeight?: Length
  padding?: Length; paddingX?: Length; paddingY?: Length; paddingTop?: Length; paddingRight?: Length; paddingBottom?: Length; paddingLeft?: Length
  margin?: Length; marginX?: Length; marginY?: Length; marginTop?: Length; marginRight?: Length; marginBottom?: Length; marginLeft?: Length
  position?: 'relative' | 'absolute'
  inset?: Length; top?: Length; right?: Length; bottom?: Length; left?: Length
  zIndex?: number
  aspectRatio?: number
  background?: Color | { gradient: 'linear' | 'radial'; stops: [number, Color][]; angle?: number }
  opacity?: number
  radius?: Length
  borderWidth?: number
  borderColor?: Color
  shadow?: { x: number; y: number; blur: number; color: Color }
  /** 玻璃。 */
  backdropBlur?: number
  overflow?: 'visible' | 'hidden'
  color?: Color
  fontSize?: Length
  fontWeight?: 'regular' | 'medium' | 'semibold' | 'bold' | number
  fontFamily?: string
  lineHeight?: number
  textAlign?: 'start' | 'center' | 'end'
  maxLines?: number
  letterSpacing?: number
  fit?: 'cover' | 'contain' | 'fill'
  tint?: Color
  translateX?: number; translateY?: number; scale?: number; rotate?: number
  transition?: Transition
  animation?: Animation
}

/** 焦点与无障碍,所有可交互组件共有。@see D31 D424 D445 D529 */
export interface FocusProps {
  key?: string | number
  focusable?: boolean
  autoFocus?: boolean
  focusGroup?: string
  nextFocusUp?: string; nextFocusDown?: string; nextFocusLeft?: string; nextFocusRight?: string
  a11yLabel?: string
}

export interface BaseProps extends FocusProps {
  style?: Style
  children?: Child
}

export interface PressProps { onPress?: () => void; onLongPress?: () => void; onFocus?: () => void; onBlur?: () => void }

// —— 原语 ——
export declare function View(p: BaseProps): LpElement
export declare function Row(p: BaseProps): LpElement
export declare function Column(p: BaseProps): LpElement
/** 叠层。 */
export declare function Stack(p: BaseProps): LpElement
/** 滚动事件每帧最多一条。@see D135 */
export declare function ScrollView(p: BaseProps & { horizontal?: boolean; onScroll?: (e: { x: number; y: number }) => void; onEndReached?: () => void }): LpElement
export declare function Text(p: BaseProps & { selectable?: boolean }): LpElement
export declare function Image(p: BaseProps & { src: string | ImageRef; placeholder?: 'poster' | 'none' }): LpElement
/** 官方图标稳定名或包内 SVG;主题可替换同名官方图标。@see D211 D549 */
export declare function Icon(p: BaseProps & { name?: string; src?: string; size?: number; color?: Color }): LpElement
export declare function Pressable(p: BaseProps & PressProps): LpElement
export declare function Button(p: BaseProps & PressProps & { title: string; variant?: 'primary' | 'secondary' | 'ghost' | 'danger'; icon?: string; disabled?: boolean }): LpElement
/** 默认非受控:只在防抖后 onChangeText 与 onSubmit 通知;要逐字响应声明 live。TV 旁显示扫码输入二维码。@see D135 D315 */
export declare function TextInput(p: BaseProps & { defaultValue?: string; placeholder?: string; secret?: boolean; multiline?: boolean; live?: boolean; onChangeText?: (text: string) => void; onSubmit?: (text: string) => void }): LpElement
export declare function Switch(p: BaseProps & { value: boolean; onChange: (v: boolean) => void; label?: string }): LpElement
export declare function Checkbox(p: BaseProps & { value: boolean; onChange: (v: boolean) => void; label?: string }): LpElement
export declare function Slider(p: BaseProps & { value: number; min: number; max: number; step?: number; onChange: (v: number) => void }): LpElement
export declare function Select(p: BaseProps & { value: string | string[]; options: { value: string; label: string }[]; multi?: boolean; onChange: (v: string | string[]) => void }): LpElement
export declare function Divider(p: BaseProps): LpElement
export declare function Spinner(p: BaseProps & { size?: number }): LpElement
export declare function Skeleton(p: BaseProps & { shape?: 'line' | 'poster' | 'card' }): LpElement
/** 原生端只向 JS 要可见范围的项。@see D134 */
export declare function VirtualList(p: BaseProps & { itemCount: number; renderItem: (index: number) => Child; itemHeight?: number; onEndReached?: () => void; horizontal?: boolean }): LpElement
export declare function VirtualGrid(p: BaseProps & { itemCount: number; renderItem: (index: number) => Child; columns?: number | { compact: number; medium: number; expanded: number }; onEndReached?: () => void }): LpElement
/** 仿 HTML5 Canvas 2D,指令流一次性发给原生;rAF 限 30/60fps。@see D19 D104 D105 */
export declare function Canvas(p: BaseProps & { draw: (ctx: Canvas2D, frame: { time: number; dt: number }) => void; animate?: boolean }): LpElement
/** 内嵌播放器:全局一个 mpv,视频层定位到此区域;全屏进官方播放页不断播;离页停播。@see D268 D276 */
export declare function Player(p: BaseProps & { item?: MediaItem | string; url?: string; headers?: Headers; autoplay?: boolean; recordKey?: string }): LpElement
/** 内嵌网页;不可用时显示「不可用」。@see D269 */
export declare function WebView(p: BaseProps & { src: string; options?: WebViewOptions; onMessage?: (data: Json) => void; injectScript?: string; ref?: (h: { postMessage(data: Json): void; evaluate(js: string): Promise<Json> } | null) => void }): LpElement
export declare function Markdown(p: BaseProps & { source: string }): LpElement

// —— 业务组件(与官方页同一份原生实现,主题改它们插件里也变)@see D21 D41 D44 ——
export declare function PosterCard(p: BaseProps & PressProps & { item: MediaItem; shape?: PosterShape; showRemarks?: boolean; progress?: number }): LpElement
export declare function PosterRow(p: BaseProps & { title?: string; items: MediaItem[]; shape?: PosterShape; onItemPress?: (item: MediaItem) => void; onEndReached?: () => void }): LpElement
export declare function PosterGrid(p: BaseProps & { items: MediaItem[]; shape?: PosterShape; onItemPress?: (item: MediaItem) => void; onEndReached?: () => void }): LpElement
export declare function EpisodeGrid(p: BaseProps & { episodes: Episode[]; current?: string; reversed?: boolean; onSelect: (ep: Episode) => void }): LpElement
export declare function LineTabs(p: BaseProps & { lines: Line[]; current: string; onChange: (lineId: string) => void }): LpElement
export declare function DetailHeader(p: BaseProps & { item: MediaItem; actions?: Child }): LpElement
export declare function FilterPanel(p: BaseProps & { dimensions: FilterDimension[]; value: Record<string, string[]>; onChange: (v: Record<string, string[]>) => void }): LpElement
/** 原生绑定 mpv 进度,不过 JS;自带预览缩略图。@see D161 D163 */
/**
 * 官方进度条(D161 D163)。不给 `value` 时绑当前播放进度,原生直接读 mpv、不过 JS;
 * 给了 `value`(0~1)就画插件自己的进度(下载、任务这类)。
 * `seekable` 只对绑 mpv 的那一种有意义 —— 插件自己的进度条拖到哪儿宿主也不知道该做什么。
 */
export declare function ProgressBar(p: BaseProps & { value?: number; seekable?: boolean }): LpElement
export declare function ServerCard(p: BaseProps & PressProps & { server: ServerInfo }): LpElement
export declare function Chip(p: BaseProps & PressProps & { label: string; selected?: boolean }): LpElement
export declare function ChipGroup(p: BaseProps & { options: { value: string; label: string }[]; value: string[]; multi?: boolean; onChange: (v: string[]) => void }): LpElement
export declare function Tabs(p: BaseProps & { tabs: { id: string; title: string; icon?: string }[]; current: string; onChange: (id: string) => void }): LpElement
export declare function SettingsGroup(p: BaseProps & { title?: string }): LpElement
export declare function SettingsRow(p: BaseProps & PressProps & { title: string; description?: string; trailing?: Child }): LpElement
/** 一句话 + 一个去处按钮,不配插画。@see D416 */
export declare function EmptyState(p: BaseProps & { text: string; action?: { title: string; onPress: () => void } }): LpElement
export declare function Badge(p: BaseProps & { text: string; tone?: 'neutral' | 'accent' | 'ok' | 'warn' }): LpElement
export declare function RatingList(p: BaseProps & { ratings: Rating[] }): LpElement

/** Canvas 2D 子集。@see D104 */
export interface Canvas2D {
  readonly width: number
  readonly height: number
  fillStyle: string | CanvasGradientLike
  strokeStyle: string | CanvasGradientLike
  lineWidth: number
  font: string
  textAlign: 'left' | 'center' | 'right'
  textBaseline: 'top' | 'middle' | 'alphabetic' | 'bottom'
  globalAlpha: number
  fillRect(x: number, y: number, w: number, h: number): void
  strokeRect(x: number, y: number, w: number, h: number): void
  clearRect(x: number, y: number, w: number, h: number): void
  fillText(text: string, x: number, y: number, maxWidth?: number): void
  strokeText(text: string, x: number, y: number, maxWidth?: number): void
  measureText(text: string): { width: number }
  beginPath(): void
  closePath(): void
  moveTo(x: number, y: number): void
  lineTo(x: number, y: number): void
  arc(x: number, y: number, r: number, a0: number, a1: number, ccw?: boolean): void
  quadraticCurveTo(cpx: number, cpy: number, x: number, y: number): void
  bezierCurveTo(c1x: number, c1y: number, c2x: number, c2y: number, x: number, y: number): void
  rect(x: number, y: number, w: number, h: number): void
  fill(): void
  stroke(): void
  clip(): void
  drawImage(src: string | ImageRef, dx: number, dy: number, dw?: number, dh?: number): void
  save(): void
  restore(): void
  translate(x: number, y: number): void
  rotate(rad: number): void
  scale(x: number, y: number): void
  setTransform(a: number, b: number, c: number, d: number, e: number, f: number): void
  createLinearGradient(x0: number, y0: number, x1: number, y1: number): CanvasGradientLike
  createRadialGradient(x0: number, y0: number, r0: number, x1: number, y1: number, r1: number): CanvasGradientLike
}
export interface CanvasGradientLike { addColorStop(offset: number, color: string): void }

// ════════════════════════════════════════════════════════════════════
// 28. UI Hooks @see D161 D217 D426 D428
// ════════════════════════════════════════════════════════════════════

export interface Viewport {
  width: number
  height: number
  /** 官方断点,与官方页同一套。@see D217 */
  breakpoint: 'compact' | 'medium' | 'expanded'
  formFactor: FormFactor
  /** 铺满时自己要让开的边距。@see D426 */
  insets: { top: number; right: number; bottom: number; left: number }
}

/** 宽高/断点变化时重渲染(节流到帧)。 */
export declare function useViewport(): Viewport
/** 当前主题 token 值与明暗。 */
export declare function useTheme(): { mode: 'light' | 'dark'; token(name: string): string | number }
/** 播放状态推送(OSD/覆盖层用);time-pos 限频。@see D161 */
export declare function usePlayerState(opts?: { hz?: number }): (NowPlaying & { paused: boolean; buffering: boolean; osdVisible: boolean }) | null
/** 插件设置值(manifest 声明的设置项)。 */
export declare function useSetting<T extends Json = Json>(key: string): [T, (v: T) => void]
/** KV 的响应式包装。 */
export declare function useStorage<T>(key: string, initial: T): [T, (v: T) => void]
export declare function useReducedMotion(): boolean

/**
 * 调试 API:**只在开发者模式存在**(D80 D128),对所有插件开放。
 *
 * 官方调试面板(`linplayer/devtools`,16.5)就是靠它画那四块。
 * 开发者模式没开时整个命名空间不挂 —— 挂一个永远返回空表的版本,
 * 面板会把「这一版不收」显示成「这个插件没日志」。
 */
export declare namespace debug {
  /** 装着的插件(含自己)。 */
  function plugins(): Promise<{ id: string; name: string; version: string; enabled: boolean }[]>
  /** 最近的日志(内存里,不落盘,D172)。 */
  function logs(pluginId: string): Promise<DebugLog[]>
  /** 最近的网络请求。 */
  function requests(pluginId: string): Promise<DebugRequest[]>
  /** 当前挂着的 surface。 */
  function surfaces(): Promise<DebugSurface[]>
  /** 某个 surface 的组件树与属性;函数属性折成字符串 `'fn'`。 */
  function uiTree(surfaceId: string): Promise<DebugTreeNode | null>
  /** 某个插件的 KV 快照。 */
  function storage(pluginId: string): Promise<Record<string, Json>>
  /** 改一个键;`value` 为 `null` = 删。 */
  function setStorage(pluginId: string, key: string, value: Json | null): Promise<void>
  /** 调用耗时与内存。`heap_delta` 是**进程级**采样的近似,不是这个插件真占了多少。 */
  function stats(pluginId: string): Promise<DebugStats>
}

export interface DebugLog { ts: number; level: 'debug' | 'info' | 'warn' | 'error'; msg: string }
export interface DebugRequest { ts: number; method: string; url: string; status: number; ms: number; err?: string }
export interface DebugSurface { surface: string; plugin: string; kind: string; target: string }
export interface DebugTreeNode { id: number; type: string; text?: string; props: Record<string, Json>; children: DebugTreeNode[] }
export interface DebugStats { calls: number; total_ms: number; max_ms: number; timeouts: number; heap_delta: number }

/** 插件设置值(非 hook)。 */
export declare namespace settings {
  function get<T extends Json = Json>(key: string): T | undefined
  function set(key: string, value: Json): void
  function onChange(key: string, cb: (v: Json) => void): Disposable
}

// ════════════════════════════════════════════════════════════════════
// 29. JSX 工厂 @see D23 D270 D318 D319
// 不做「整页 HTML/CSS」;渲染变更按帧(16ms)合批发给原生;老宿主遇未知组件画占位、忽略未知属性。
// lp build 用 classic JSX(jsxFactory = h,jsxFragmentFactory = Fragment);
// SDK 内部把 h 转交 Preact,Hooks / Context / key 语义与 Preact 一致。
// ════════════════════════════════════════════════════════════════════

export declare function h(type: unknown, props: Record<string, unknown> | null, ...children: Child[]): LpElement
export declare namespace h {
  namespace JSX {
    type Element = LpElement
    interface IntrinsicElements {}
    interface ElementChildrenAttribute { children: {} }
    interface IntrinsicAttributes { key?: string | number }
  }
}
export declare function Fragment(p: { children?: Child }): LpElement
/** Preact hooks 原样转出。 */
export declare function useState<T>(initial: T | (() => T)): [T, (v: T | ((prev: T) => T)) => void]
export declare function useEffect(fn: () => void | (() => void), deps?: unknown[]): void
export declare function useMemo<T>(fn: () => T, deps: unknown[]): T
export declare function useCallback<T extends (...a: never[]) => unknown>(fn: T, deps: unknown[]): T
export declare function useRef<T>(initial: T): { current: T }
export declare function useContext<T>(ctx: Context<T>): T
export declare function useReducer<S, A>(reducer: (s: S, a: A) => S, initial: S): [S, (a: A) => void]
/** 这一块崩了只让这一块显示出错(SPEC 7.11 D136)。 */
export declare function useErrorBoundary(cb?: (err: unknown) => void): [unknown, () => void]
export interface Context<T> { Provider(p: { value: T; children?: Child }): LpElement }
export declare function createContext<T>(initial: T): Context<T>
