# 外部数据源类插件：协议与规则格式横评

> 调研时间：2026-09-19
> 范围：8 个中文圈与开源圈真实生态的数据源插件系统
> 目标：为 LinPlayer 全新插件系统设计统一的数据源接口

---

## 1. TVBox / FongMi / 影视仓

**项目代表**
- [jiusanzhou/tvbox](https://github.com/jiusanzhou/tvbox) —— 配置聚合仓库，自动合并多个源
- 原始格式：FongMi 是 TVBox 的 Rust 重写，配置兼容

### 1.1 接口的"动词"

TVBox 的 Spider 必须实现 **9 个必填方法**（由 CI 自动验证）：
- `init(params)` —— 初始化
- `home()` —— 首页分类列表
- `homeVod()` —— 首页推荐视频
- `category(tid, pg)` —— 分类浏览（分页）
- `detail(id)` —— 获取视频详情
- `play(flag, id)` —— 获取播放地址
- `search(wd, pg)` —— 搜索
- `isVideoFormat(url)` —— 判断是否视频地址
- `manualVideoCheck(url)` —— 手动验证视频地址

### 1.2 代码型 vs 规则型

**代码型**（纯代码实现）
- 使用 **JavaScript** 编写 Spider（`spider.js`）
- 宿主提供 JS 运行时（通常是嵌入式 V8 或类似）
- 维护成本：中等。需要修改 JS 代码，但门槛低于 Java/Kotlin
- 表达力：强。可调用任意 JS 库、执行复杂逻辑

### 1.3 反爬应对

Spider 可以：
- 设置 **HTTP Header**：UA、Referer、Cookie
- 使用 `http.get()` / `http.post()` 发送请求
- 执行 **正则表达式** 提取数据
- **HTML 解析**：通过 `cheerio` 或内置 DOM API（需宿主支持）
- **JS 加密**：理论上可调用 JS 库（如 crypto），但具体能力取决于运行时导出
- **Cloudflare / 验证码**：开源生态中常用 `puppeteer` 或类似，但需宿主 WebView 支持

**宿主需提供**：
- HTTP 客户端（可设置 headers、timeout）
- 正则和基础 HTML/XML 解析
- 可选：WebView 容器过验证码、JS 加密库

### 1.4 播放地址的特殊需求

- **多线路**：`detail()` 返回 `playList[]`，每个线路对应不同源
- **自定义 Header**：播放 URL 可附带 Header（由宿主播放器使用）
- **M3U8 代理**：部分源返回 m3u8，需宿主支持 HLS 解析和分段下载
- **跳转追踪**：某些源用 302/301 做防盗链，宿主播放器需支持追踪一次跳转

### 1.5 分发与订阅

- **主配置**：`tvbox.json`（订阅 URL）
- 内含 `sites[]` 数组，每个 site 对象：
  ```json
  {
    "key": "site_key_unique",
    "name": "📺 Display Name",
    "type": 3,
    "spider": "https://example.com/spiders/xxx.jar",
    "searchable": 1,
    "filterable": 1,
    "probes": ["https://status-check-url"]
  }
  ```
- 用户在 FongMi/TVBox 中粘贴 `tvbox.json` URL 自动加载
- **自动更新**：客户端周期性检查 JSON，更新 spider.jar

### 1.6 法律与合规

- **框架只提供运行时和宿主能力**，不提供源
- 索引仓库（如 `jiusanzhou/tvbox`）多为聚合性质，指向社区提供的 Spider
- **法律风险主要在源方**：如果 Spider 爬的网站违规，责任在编写者
- **规避做法**：官方仓库不内置源 URL，只提供框架；用户自行添加订阅

### 1.7 对 LinPlayer 的启示

✓ 使用 **JSON 配置** + **动态脚本**（JS/Lua）的组合最灵活  
✓ 将接口拆成 9～12 个"小"方法比一个大 API 更易维护  
✓ 支持 **多线路选择** 和 **自定义请求头** 是刚需  
✓ 框架只提供运行时，源由用户订阅添加

---

## 2. Kazumi —— 基于 XPath 规则的番剧采集

**项目代表**  
- [wizos/Kazumi](https://github.com/wizos/Kazumi) —— Flutter 多平台番剧播放器
- 核心创新：**规则型** 而非代码型

### 2.1 接口的"动词"

Kazumi 采用 **规则型**，不含硬代码。每条规则是 XPath 表达式，最多 **5 行**。
- 提交 anime **搜索关键词**
- 从网站 HTML 中用 XPath **提取** 剧集列表
- 从剧集页用 XPath 提取**播放地址**
- 外部集成：**Bangumi** metadata、**DanDanPlay** danmaku、**trace.moe** 反向识别

### 2.2 代码型 vs 规则型

**规则型**（纯 XPath 配置）
- **维护成本低**：无需编译，文本编辑即可改规则
- **学习曲线陡峭**：用户需学 XPath 语法
- **表达力有限**：只能提取，无法执行复杂逻辑（如 JS 解密、多步请求）
- **官方限制**：目前 Kazumi 仅支持 `//` 开头的选择器，XPath 支持**不完整**
- **共同体维护**：用户编写规则后可在 Kazumi 官方仓库共享

### 2.3 反爬应对

**规则层面**：
- 单条规则无法处理 JS 挑战、Cloudflare、验证码
- 只能提取静态 HTML

**宿主支持**：
- Kazumi 应用层可设置 **UA、Referer、Cookie** 全局
- 无法处理动态加载或 HTTPS 双向认证

**实际做法**：
- 大多数规则只针对 **无防爬** 的源（如镜像站）
- 需要复杂验证的源无法用规则适配

### 2.4 播放地址的特殊需求

- XPath 提取后直接作为 **URL 使用**
- 无法附加自定义 Header
- 无法处理跳转或代理需求

**局限**：如果源用防盗链需要特定 Referer，规则方案无法应对

### 2.5 分发与订阅

- 规则存储为 **JSON 或 YAML 文本**，例：
  ```json
  {
    "id": "rule_key",
    "name": "Rule Name",
    "xpath_search": "//div[@class='item']/a/@href",
    "xpath_title": "//h1/text()"
  }
  ```
- 用户通过 URL 导入规则集
- **社区维护**：规则集被 fork、改进后分享

### 2.6 法律与合规

- 规则本身无代码，只是选择器，法律地位不同于爬虫脚本
- 但使用规则爬取的内容仍需遵守网站 ToS
- **规避做法**：官方不提供针对版权站点的规则，用户自研

### 2.7 对 LinPlayer 的启示

✓ **规则型** 适合"快速适配+社区维护"的场景  
✗ **不适合** 需要复杂反爬、加密解析、多步流程的源  
✓ 若采用规则，需完整的 XPath + CSS 支持（Kazumi 目前支持不完整）  
✓ 可作为**轻量级补充**（搭配代码型方案）

---

## 3. Mihon / Aniyomi —— Kotlin 扩展（APK）

**项目代表**  
- [keiyoushi/extensions](https://github.com/keiyoushi/extensions) —— Mihon 官方扩展仓库
- 分发方式：APK 打包的 Kotlin 源，自动编译、签名、发布

### 3.1 接口的"动词"

所有源都继承 `Source` 或 `HttpSource` / `ParsedHttpSource`，实现核心方法：
- `searchManga(query, page)` —— 搜索
- `getMangaDetails(manga)` —— 获取漫画详情、章节列表
- `getChapterContent(chapter)` —— 获取章节内容（图片 URL）
- `getPageList(chapter)` —— 获取页面列表
- `imageRequest(page)` —— 获取图片请求对象（自定义 Header）
- `Popular/Latest/Trending`  —— 热门/最新/趋势列表

### 3.2 代码型 vs 规则型

**代码型**（Kotlin APK）
- **语言**：Kotlin（JVM）
- **维护**：官方提供编译链，自动化打包
- **成本**：高。需要 Android 开发环境、Kotlin 知识
- **表达力**：极强。无限制的代码能力

### 3.3 反爬应对

Mihon 源可以：
- 调用 `HTTP 客户端库`（OkHttp），自由设置 headers、timeout、重试
- **正则 + DOM 解析**（JSoup）提取数据
- **加密库**：可引入任意 Kotlin/Java 库（如 crypto、base64）
- **WebView**：部分源通过 WebView 过 JS 挑战（需主应用支持）
- **Cookie 持久化**：通过 CookieJar 管理
- **代理**：可配置 HTTP 代理

**宿主需提供**：
- HTTP 客户端框架（OkHttp）
- DOM 解析库（JSoup）
- Cookie 管理器
- 可选：WebView 容器

### 3.4 播放地址的特殊需求

漫画和视频不同，但同样支持：
- **自定义 Header**：`imageRequest()` 返回带有 Referer、Cookie 的请求对象
- **图片代理**：源可返回代理 URL
- **跳转追踪**：OkHttp 自动处理 30x

### 3.5 分发与订阅

**方案 A**：官方扩展仓库 + 自动编译
- 源代码在 GitHub
- CI 自动编译为 APK、生成 `index.pb` 索引
- 用户通过仓库 URL 订阅：添加 `https://raw.githubusercontent.com/keiyoushi/extensions/repo/...`
- 客户端自动下载、安装、更新 APK

**方案 B**：第三方仓库
- 任何人可托管 `index.pb` + APK 文件
- 用户订阅 URL 后自动检查更新

**索引文件**：`index.pb`（protobuf 格式）或 `index.json`
```json
{
  "name": "Extension Name",
  "pkgName": "com.example.manga",
  "versionCode": 1,
  "versionName": "1.0",
  "jarFile": "extension_1_0.apk",
  "icon": "extension_icon.png"
}
```

### 3.6 法律与合规

- 官方仓库拒绝内置非法源
- **版权政策严格**：GitHub 会接收 DMCA 投诉
- **规避做法**：仓库只包含合法源；社区 fork 可自行添加
- 用户自建仓库的法律责任由自己承担

### 3.7 对 LinPlayer 的启示

✗ **不适合跨平台**：APK 只能用于 Android，需要全平台适配  
✓ **接口清晰**：Source 接口很适合直接参考  
✓ **分发方案成熟**：APK 签名 + 索引 + 自动更新是现成的  
✓ 若改为**动态 DLL/SO + 索引**的形式，可移植到 Windows/Linux/TV

---

## 4. Stremio —— 开放的 Addon 协议

**项目代表**  
- [Stremio/stremio-addon-sdk](https://github.com/Stremio/stremio-addon-sdk) —— 官方 SDK
- 特点：**HTTP + JSON**，语言无关，不限平台

### 4.1 接口的"动词"

Stremio Addon 暴露 HTTP 端点，实现以下资源：
- `/manifest.json` —— Addon 元数据（必填）
- `/catalog/{type}/{id}.json` —— 媒体列表（搜索、分类、推荐）
- `/meta/{type}/{id}.json` —— 单个媒体详情
- `/stream/{type}/{videoId}.json` —— 播放源
- `/subtitles/{type}/{id}.json` —— 字幕轨道

**请求方式**：HTTP GET，URL pattern 为 `/{resource}/{type}/{id}/{extraParams}.json`

### 4.2 代码型 vs 规则型

**代码型**（HTTP 服务）
- **语言中立**：Node.js、Python、Go、任何能开 HTTP 服务的语言都行
- **运行模式**：Addon 是独立进程或 serverless 函数
- **维护成本**：中等。需要运维服务，但不限制开发语言
- **表达力**：完全自由

### 4.3 反爬应对

Addon 作为**独立服务**，可以：
- 任意使用 **HTTP 库、HTML 解析库、加密库**
- 缓存、数据库、会话管理完全由 Addon 自己控制
- **WebDriver / 浏览器自动化**：Addon 可内部使用 Puppeteer
- **反向代理**：Addon 可为用户代理请求，绕过 CORS、地域限制

**宿主需提供**：
- HTTP 客户端（调用 Addon 的端点）
- CORS 支持（浏览器端调用 Addon）
- 可选：代理配置

### 4.4 播放地址的特殊需求

`/stream/{type}/{videoId}.json` 返回：
```json
{
  "streams": [
    {
      "name": "HD",
      "url": "https://example.com/video.mp4",
      "externalUrl": "https://www.example.com",
      "headers": {
        "User-Agent": "...",
        "Referer": "..."
      }
    }
  ]
}
```

支持：
- **自定义 Header**：streams 数组中每个元素可带 `headers` 对象
- **代理 URL**：Addon 可返回自己的代理 URL，实现 m3u8 转发、分段下载代理
- **跳转追踪**：由 Addon 内部处理，宿主无需关心

### 4.5 分发与订阅

- **独立 URL**：每个 Addon 有 `/manifest.json` 端点
- **Addon 目录**：[stremio-addons.net](https://stremio-addons.net) 为官方索引
  - 用户粘贴 Addon URL 自动加载
  - 目录支持搜索、分类浏览
- **自托管**：任何人可在自己的服务器运行 Addon，提供 URL 给用户

### 4.6 法律与合规

- Stremio 应用本身是合法的 —— 不提供源
- **Addon 责任由各自承担**：官方目录会审核，拒绝明显侵权的 Addon
- **规避做法**：
  - Stremio 应用和官方 Addon SDK 完全中立
  - 用户可自建 Addon，后果自负
  - 第三方 Addon 分发渠道（如 GitHub）需自行负责

### 4.7 对 LinPlayer 的启示

✓ **最灵活的方案**：HTTP + JSON 意味着语言无关、可远程或本地  
✓ **请求/响应清晰**：接口定义明确，易实现客户端  
✓ **适合插件市场**：用户可发现、订阅、更新 Addon  
✗ **高运维成本**：需要自己运维服务器或无服务器平台  
✓ **可离线 + 在线混合**：Addon 可以是本地进程（如内嵌 Python）或远程 API

---

## 5. Lampa —— TV 端 Web 播放器的插件

**项目代表**  
- [lampa-app/LAMPA](https://github.com/lampa-app/LAMPA) —— 俄罗斯开源电视应用
- 运行环境：**电视端浏览器**（通常是 Electron 或嵌入式 Chromium）

### 5.1 接口的"动词"

Lampa 插件通过 **全局 `window.Lampa` 对象** 的方法与宿主交互：
- `Lampa.Reguest` —— HTTP 请求（超时、错误处理）
- `Lampa.Component` —— UI 组件注册
- `Lampa.Controller` —— 焦点控制（TV 遥控导航）
- `Lampa.Timeline` —— 播放进度追踪
- `Lampa.Player` —— 播放器控制、列表管理
- 插件可自定义的动词：`search()`, `browse()`, `getDetails()`, `getPlayUrl()` 等

### 5.2 代码型 vs 规则型

**代码型**（JavaScript）
- **语言**：JavaScript（浏览器）
- **运行环境**：受限于浏览器沙箱 + Lampa 开放的 API
- **维护**：中等。需要 Web 开发知识
- **表达力**：受 Lampa API 限制，但足以完成数据爬取

### 5.3 反爬应对

插件可以：
- 调用 `Lampa.Reguest`（支持超时、重试）
- 在浏览器环境中使用 **正则、DOM 解析**
- **跨域问题**：浏览器 CORS 限制，Lampa 可能提供代理或 JSONP
- **验证码 / JS 挑战**：无法处理，需要目标网站有公开 API 或 Lampa 应用层支持

**宿主需提供**：
- `Lampa.Reguest` HTTP 客户端（CORS 代理）
- DOM API（解析 HTML）
- 可选：代理、Cookie jar

### 5.4 播放地址的特殊需求

- 插件返回播放 URL 给 Lampa.Player
- Player 支持 **HTTP Header**：插件可在返回对象中指定
- **M3U8 代理**：Lampa 需内置或插件自建

### 5.5 分发与订阅

- 插件是 **JavaScript 文件** + `manifest.json`
- 用户将插件 URL 粘贴到 Lampa 设置
- Lampa 自动下载、注册、启用

**manifest.json** 示例：
```json
{
  "id": "plugin_id",
  "name": "Plugin Name",
  "version": "1.0",
  "main": "plugin.js",
  "requires": ["Lampa"]
}
```

### 5.6 法律与合规

- Lampa 本体中立，不包含源
- 社区插件的法律风险由插件编写者承担
- 官方插件目录会进行审核

### 5.7 对 LinPlayer 的启示

✓ **电视端友好**：焦点导航、遥控支持很完善  
✓ **JavaScript 易上手**：社区开发者多  
✗ **浏览器限制**：无法处理复杂反爬、跨域绕过困难  
✓ **适合轻量级源**：优先级排在 TVBox / Mihon 之后

---

## 6. MoviePilot —— Python 自动化下载器

**项目代表**  
- [jxxghp/MoviePilot](https://github.com/jxxghp/MoviePilot) —— 媒体库自动化管理
- 特点：**订阅 + 下载 + 刮削** 全链条自动化

### 6.1 接口的"动词"

MoviePilot 插件继承 `_PluginBase`，实现钩子方法：
- `init()` —— 初始化
- `recognize(name)` —— 识别媒体标题、年份、类型等
- `search_all()` —— 搜索所有索引器、聚合结果
- `get_resource()` —— 获取下载资源（种子/链接）
- `subscribe(title, type, tmdbId)` —— 订阅（保存到数据库）
- `on_event()` —— 监听 Sonarr、Radarr、Qbittorrent webhook 事件
- `stop()` —— 清理（定时器、连接等）

### 6.2 代码型 vs 规则型

**代码型**（Python）
- **语言**：Python
- **运行模式**：在宿主进程内作为模块加载，或独立线程
- **维护**：中等。Python 易学，但需理解 MoviePilot 架构
- **表达力**：完全自由；可调用任意 Python 库

### 6.3 反爬应对

插件可以：
- 使用 **requests、aiohttp、Selenium** 等任意 HTTP 库
- **HTML 解析**：BeautifulSoup、lxml
- **加密库**：内置 Python crypto 库
- **WebDriver**：Selenium、Playwright 自动化 JS 渲染
- **会话管理**：持久化 Cookie、代理配置

**宿主需提供**：
- Python 运行时 + 包管理（pip）
- 数据库连接（SQLAlchemy ORM，支持 SQLite/PostgreSQL）
- 事件系统（APScheduler 定时任务）

### 6.4 播放地址的特殊需求

MoviePilot 主要处理**下载**而非流媒体播放，但支持：
- 返回 **种子文件 / 磁力链接**
- 返回 **HTTP 下载链接**（带 Header）
- **多文件分段**：支持 BT 多文件选择

### 6.5 分发与订阅

- 插件是 **Python 文件**，存储在 MoviePilot 指定目录
- 用户通过 GitHub 仓库或本地路径加载
- **插件仓库**：[MoviePilot-Plugins](https://github.com/HankunYu/MoviePilot-Plugins) 聚合社区插件
- 支持自动更新（拉取最新版本）

### 6.6 法律与合规

- MoviePilot 应用本身中立 —— 不提供源
- 大量索引器（BT 站点、资源聚合）的合法性各异
- **规避做法**：官方仓库倾向于合法资源（开放字幕、官方 API）；用户自行添加风险源

### 6.7 对 LinPlayer 的启示

✗ **不适合流媒体播放**：设计重点是下载 + 刮削  
✓ **事件驱动架构** 值得参考：webhook + 定时任务  
✓ **元数据识别** 和 **多源聚合** 的做法有借鉴意义  
✓ 若 LinPlayer 未来加下载功能，可参考 MoviePilot 的插件接口

---

## 7. Kodi / Jellyfin —— 媒体服务器的元数据与刮削插件

**项目代表**  
- Kodi Wiki: Scrapers —— XML + Python 混合
- Jellyfin MetadataProvider —— .NET 接口

### 7.1 接口的"动词"

**Kodi（已弃用 XML，重心转向 Python）**  
- 历史：XML Scraper（正则驱动，已废弃）
- 现代：Python Scraper，实现 getSearchResults(), getMetadata()

**Jellyfin**  
- IRemoteMetadataProvider<T, TInfo> 接口：
  - GetSearchResults(searchInfo) —— 搜索
  - GetMetadata(info) —— 获取元数据并返回 MetadataResult<T>
  - HttpClientFactory —— HTTP 请求
- 支持的类型：Movie、Series、Season、Episode、MusicArtist、Album

### 7.2 代码型 vs 规则型

**Kodi**
- **旧方案**（XML）：规则型，已弃用；正则驱动，难以适应网站变化
- **新方案**（Python）：代码型；表达力强，与现代爬虫相同

**Jellyfin**
- **纯代码型**（.NET / C#）；需要 .NET 开发环境

### 7.3 反爬应对

**Kodi（Python）**可使用任意 Python 库：requests、BeautifulSoup、Selenium；支持 Session、Cookie 持久化

**Jellyfin**：宿主提供 IHttpClient（.NET Framework 内置），可自定义 Header

**宿主需提供**：HTTP 客户端框架、基础 HTML/JSON 解析、缓存层（可选）

### 7.4 播放地址的特殊需求

Kodi/Jellyfin 是**媒体服务器**，不负责播放——交由客户端处理。元数据中可包含 Item 路径、Artwork URL、导演、演员、简介、评分等。无需处理播放地址；播放由客户端负责。

### 7.5 分发与订阅

**Kodi**：插件是目录结构 + addon.xml + Python 脚本；用户通过官方仓库或第三方 zip 包安装

**Jellyfin**：插件是编译的 .DLL 文件；放在服务器的 plugins 目录，启动时自动加载

### 7.6 法律与合规

官方仓库严格审核，拒绝非法源；元数据来源限制：通常只接受公开 API（TMDB、TVDB、IMDb）

### 7.7 对 LinPlayer 的启示

✗ **不直接适用**：重点是**元数据 + 库管理**，而非播放源  
✓ **元数据查询接口**可参考（搜索→获取详情）  
✓ **插件分发方案**成熟（zip + 自动更新）

---

## 8. 字幕与弹幕聚合源

### 8.1 OpenSubtitles —— 字幕聚合

**项目代表**：opensubtitles.com API、Python 包 opensubtitles-com

**接口的"动词"**：
- SearchSubtitles(imdbId, season, episode, lang) —— 搜索字幕
- DownloadSubtitle(fileId) —— 下载
- GetSubtitleFormats() —— 可用格式列表

**反爬**：需要 API Key 认证（OAuth）；支持多语言（中英日等）；支持多格式（SRT、SSA、WebVTT）

### 8.2 DanDanPlay —— 弹幕聚合

**项目代表**：doc.dandanplay.com/open、danmu_api、misaka_danmu_server

**接口的"动词"**：
- POST /api/v2/match(keyword, type) —— 匹配番剧（获取 episodeId）
- GET /api/v2/comment/{episodeId}(format=json, duration=true) —— 获取弹幕
- GET /api/v2/search/episodes(keyword) —— 搜索剧集信息

**认证**：X-AppId + X-AppSecret headers 或签名认证（HMAC）

**反爬**：API 有请求频率限制（QPS）；密钥内嵌在发行包中，易被提取（需定期轮换）

**特殊需求**：弹幕按时间戳排序实时显示；支持多弹幕源（官方+社区上报）；需要 HTTPS+证书验证

### 8.3 开源聚合方案

**Danmu_Api**：自建 JS 弹幕服务，兼容 DanDanPlay 接口规范；支持爱优腾芒 B 等多个视频平台

**Misaka Danmu Server**：功能丰富的自托管服务；支持元数据集成（TMDB、Bangumi、豆瓣）；Webhook 支持

### 8.4 宿主需提供的能力

- HTTP 客户端（HTTPS、认证头）
- 弹幕渲染引擎（时间戳、样式、动效）
- 字幕加载器（格式转换、编码处理）
- 可选：字幕搜索 UI（关键词搜索、语言选择）

### 8.5 对 LinPlayer 的启示

✓ **字幕源**可接入官方 API 或自建聚合  
✓ **弹幕源**需内置 DanDanPlay API 调用+聚合兼容接口  
✓ **认证管理**需谨慎（密钥不进版本控制，用环境变量）  
✓ 考虑**多源备份**（主源不可用时自动切换）

---

## 9. 动词对照表

| 通用功能 | TVBox | Kazumi | Mihon | Stremio | Lampa | MoviePilot | Kodi | Jellyfin | OpenSub | DanDan |
|---------|-------|--------|-------|---------|-------|------------|------|----------|---------|--------|
| **搜索** | search | XPath规则 | searchManga | catalog | search() | search_all | search | SearchResults | Search | match |
| **分类** | category | N/A | popular/latest | catalog | browse() | N/A | browse | N/A | N/A | N/A |
| **详情** | detail | XPath规则 | getMangaDetails | meta | getDetails() | recognize | getMetadata | GetMetadata | (同搜索) | (同match) |
| **播放/下载** | play | XPath规则 | getChapterContent | stream | getPlayUrl() | get_resource | (N/A) | (N/A) | download | comment |
| **推荐** | homeVod | N/A | N/A | catalog(id) | N/A | N/A | browse | N/A | N/A | N/A |
| **认证** | (Header) | (全局UA) | cookies | (headers) | (代理) | 自定义 | HttpRequest | IHttpClient | OAuth | X-AppId |
| **多线路** | playList[] | N/A | N/A | streams[] | balancer | 聚合 | N/A | N/A | 多语言 | 多源 |

---

## 10. 宿主需提供的能力清单

| 能力 | 优先级 | 说明 | 关键生态 |
|------|--------|------|---------|
| **HTTP 客户端** | 必填 | 请求、超时、重试 | 全部 |
| **Header 自定义** | 必填 | UA、Referer、Cookie、认证 | TVBox, Mihon, Stremio, Lampa |
| **HTML/XML/JSON 解析** | 必填 | DOM/XPath/正则 | TVBox, Kazumi, Kodi |
| **播放地址代理** | 推荐 | m3u8转发、防盗链处理 | TVBox, Stremio |
| **Cookie jar/会话** | 推荐 | 登录后保存状态 | TVBox, Mihon, Lampa |
| **脚本运行时** | 推荐 | JS/Lua/Python执行 | TVBox(JS), Lampa(JS), MoviePilot(Python) |
| **WebView 容器** | 可选 | 过验证码、JS渲染 | TVBox(可选), 其他不支持 |
| **代理配置** | 可选 | 全局HTTP/SOCKS代理 | 全部（可选） |
| **缓存层** | 可选 | 元数据、列表缓存 | Kodi, Jellyfin |
| **弹幕渲染引擎** | 推荐 | 时间戳显示、样式 | DanDanPlay集成 |
| **字幕加载器** | 推荐 | 格式转换、编码处理 | OpenSubtitles集成 |
| **事件系统** | 可选 | webhook、定时任务 | MoviePilot(APScheduler) |
| **数据库ORM** | 可选 | 订阅、历史持久化 | MoviePilot(SQLAlchemy) |

---

## 11. 合规与风险规避汇总

### 11.1 各生态的法律地位

| 生态 | 官方态度 | 用户风险 | 规避方式 |
|------|---------|---------|---------|
| TVBox/FongMi | 框架中立，源由用户添加 | 源网站违规 | 官方不内置源URL |
| Kazumi | 规则合法，应用合法 | 同上 | 同上 |
| Mihon | 官方仓库严审查 | fork自建有风险 | 审核制+社区自担 |
| Stremio | 应用SDK中立 | 同上 | 同上 |
| Lampa | 应用中立 | 同上 | 同上 |
| MoviePilot | 应用中立 | BT/下载协议 | 官方不提供源 |
| Kodi/Jellyfin | 官方严格审核 | 同上 | 审核制 |
| OpenSubtitles | 合法聚合，有ToS | 密钥滥用 | 及时轮换密钥 |
| DanDanPlay | 合法弹幕网络 | 弹幕内容合规 | 自托管用户自担 |

### 11.2 代码外的合规措施

1. **不内置源URL** —— 官方应用不预置任何资源站
2. **用户自担责任** —— 明确告知添加第三方源的法律后果
3. **密钥管理** —— API密钥存环境变量，不进版本控制，定期轮换
4. **协议审查** —— 定期检查官方API的ToS变化
5. **社区审核** —— 若开放插件仓库，审核新提交的插件
6. **用户教育** —— 文档说明什么是合法源、法律风险提示

---

## 12. 对 LinPlayer 新插件系统的建议架构

### 12.1 核心接口（参考 TVBox + Stremio + Mihon）

所有数据源插件应实现统一接口，支持：
- Init(context) —— 初始化
- Search(query, page) —— 搜索
- Browse(category, page) —— 分类浏览
- GetDetail(itemId) —— 获取详情
- GetPlayUrl(itemId, lineId) —— 获取播放地址
- GetSubtitles(itemId) —— 获取字幕列表
- GetDanmaku(episodeId) —— 获取弹幕

PlayUrl 响应体应包含：url、name（线路名）、headers（自定义请求头）、lines（多线路嵌套）

### 12.2 插件分发方案（混合）

**方案一：动态脚本（JavaScript/Lua）**：类似 TVBox spider.js，易于快速迭代；内置 js 运行时（V8 或类似）；用户订阅 JSON 索引，自动下载脚本

**方案二：编译扩展（Go plugin/.so）**：高性能，安全性好；需要为各平台编译（Win/Linux/Android/TV）；用户订阅 protobuf 索引+二进制

**推荐**：方案一为主+方案二作为可选优化路径

### 12.3 宿主能力投资顺序

**Tier 1（上线必需）**：HTTP 客户端、Header 自定义、HTML 解析、播放代理

**Tier 2（一月内）**：Cookie jar/会话、脚本运行时（JS）、多线路支持

**Tier 3（三月内）**：DanDanPlay 弹幕、OpenSubtitles 字幕、缓存层、插件市场

**Tier 4（可选高阶）**：WebView 容器、反向代理、事件系统

---

## 13. 关键技术选型

| 选项 | 优势 | 劣势 | 推荐度 |
|------|------|------|--------|
| **HTTP:Stremio模式** | 最灵活，宿主端简单 | 需外部服务/本地进程管理 | ★★★★☆ |
| **JavaScript:TVBox模式** | 社区大，易维护 | 运行时开销 | ★★★★★ |
| **Lua:轻量脚本** | 开销小，性能好 | 社区小 | ★★★☆☆ |
| **Go plugin:编译型** | 性能最佳 | 跨平台编译复杂 | ★★★☆☆ |
| **WASM:沙箱** | 安全跨平台 | 还不够成熟 | ★★☆☆☆ |

**推荐**：JavaScript 脚本+可选 Go plugin 加速路径

---

## 参考资源

- TVBox/FongMi：https://github.com/jiusanzhou/tvbox
- Kazumi：https://github.com/wizos/Kazumi
- Mihon Extensions：https://github.com/keiyoushi/extensions
- Stremio Addon SDK：https://github.com/Stremio/stremio-addon-sdk
- Lampa：https://github.com/lampa-app/LAMPA
- MoviePilot：https://github.com/jxxghp/MoviePilot
- Kodi Wiki: Scrapers：https://kodi.wiki/view/Scrapers
- Jellyfin Metadata：https://jellyfin.org/docs/general/server/metadata/
- OpenSubtitles API：https://forum.opensubtitles.com/c/opensubtitles-api/7
- DanDanPlay Open API：https://doc.dandanplay.com/open/
