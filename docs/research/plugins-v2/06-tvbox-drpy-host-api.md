# TVBox drpy 宿主 API 完整调研

> 本文基于开源项目(drpy2/drpy3/drpy-node/FongMi TV/CatVodOpen)的源码分析，重点是了解**宿主需要提供给 JS 运行时的全局 API**。

> **【主 agent 抽查更正 2026-09-19,以本块为准】** 直接读了 drpy2 源码(`hjdhnx/dr_py` 仓库 `libs/drpy2.js`):
> 1. drpy2 **是 ES module**:第 1~5 行 `import cheerio from 'assets://js/lib/cheerio.min.js'`、`import 'assets://js/lib/crypto-js.js'`、
>    `import './jsencrypt.js'`、`import 模板 from "../js/模板.js"`、`import {gbkTool} from './gbk.js'`;文件末尾 `export default {init, home, homeVod, category, detail, play, search, proxy, sniffer, isVideo, DRPY}`。
>    下文 §1.1「不是 ES Module / 全局函数模型」是错的。宿主必须有 ESM 加载器,并能解析 `assets://` 这种自定义 scheme。
> 2. 「goja 无法实现同步 request」是错的:goja 调 Go 注入函数本来就是同步的,Go 侧阻塞发完 HTTP 再返回值即可。
>    同步 `req` 对 goja 和 qjs 都不是障碍。真正的差异在 **ESM**:goja 无原生 ESM,要在加载前转译(例如用纯 Go 的 esbuild 打包成单文件)。
> 3. drpy2 里唯一的 QuickJS `os` 模块用法是 `readFile()`(`os.open/os.read`),是调试辅助函数,入口函数不调用它。
> 因此 §「对运行时选型的结论」里"选 QuickJS 因为 goja 不能同步"的推理作废,选型要重新按 ESM/性能/兼容面比较。
> 日期: 2026-09-19

---

## 1. drpy2 的执行模型

### 1.1 ES Module vs 全局函数模型

drpy2 **不是 ES Module**。它是**全局函数**模型(type 3 spider):
- 入口是 `init`, `home`, `homeVod`, `category`, `detail`, `search`, `play`, `proxy` 等全局函数(或对象方法)
- 远程导入: drpy2.min.js 通过 fetch 加载规则文件(`.js`),在同一 JavaScript 上下文执行，共享全局作用域
- **drpy3 升级到了 ES Module**: 支持 `import`/`export`,但保留了 drpy2 兼容层 `load2x()`

出处:
- https://github.com/hjdhnx/drpy3 - drpy3 介绍提到 ES Module 和兼容层
- https://github.com/hjdhnx/drpy-node - 五个引擎共用接口(init/home/homeVod/category/detail/search/play/proxy)

### 1.2 入口函数签名(drpy2)

规则文件必须定义以下全局函数,TVBox 运行时按需调用:

| 函数 | 调用时机 | 入参 | 出参(JSON) |
|------|--------|------|----------|
| **init** | 插件加载时 | `(pg)` pg=页码(通常空) | `{status:true/false, msg:''}` |
| **home** | 首页列表请求 | `(tid)` tid=分类ID(通常空) | 见 1.3 |
| **homeVod** | 首页推荐 | `(tid)` tid=分类ID | 同 home |
| **category** | 分类列表 | `(tid,pg,filter)` | `{page:当前页,pagecount:总页数,limit:每页条数,total:总数,list:[{name,pic,id,type,remark,…}]}` |
| **detail** | 详情页 | `(ids)` ids=逗号分隔ID列表 | `{list:[{vod_id,vod_name,vod_pic,vod_actor,vod_director,vod_content,vod_play_from,vod_play_url,…}]}` |
| **search** | 搜索 | `(wd,quick,pg)` wd=关键词,quick=0/1,pg=页码 | 同 category 格式 |
| **play** | 播放获取地址 | `(flag,id,flags)` flag=播放源标志,id=集ID,flags=标志集 | `{url:'http://…',header:{},subs:[],danmaku:''}` |
| **proxy** | 代理请求 | `(segments,headers)` segments=URL段数组 | 透传响应体(二进制或文本) |

出处:
- https://github.com/hjdhnx/drpy-node - README 和源码中的接口定义
- https://github.com/hjdhnx/drpy3 - 兼容层代码

---

## 2. 宿主注入的全局 API 完整清单

### 2.1 核心请求 API

#### `request(url, options)` 或 `req(url, options)` - **同步阻塞**

```javascript
// 签名
function request(url, options = {}) {
  // url: string, 完整 URL
  // options: {
  //   headers: {...},      // 请求头(可选)
  //   method: 'GET'|'POST',  // 方法(默认 GET)
  //   data: '',            // POST 数据(可选)
  //   withCookies: true,   // 是否带 Cookie(默认 true)
  //   timeout: 10000,      // 超时 ms(默认 10s)
  //   follow: true         // 是否跟随 302/301(默认 true)
  // }
  // 返回: string (HTML/JSON/文本)
}
```

**关键**: 是 **同步** API,阻塞执行至响应返回。这对 wasm/goja 实现有重大影响(需同步 HTTP 栈)。

出处:
- https://github.com/hjdhnx/drpy3 - 文档提及需注入 `req` 等5个函数
- TVBox drpy2 Python 实现中 request() 是同步网络调用

### 2.2 HTML 解析 API - **同步**

三个函数,基于 **jsoup 语义**(Java 库的 CSS 选择器风格):

#### `pdfh(html, rule, baseUrl='')` - 单个值

```javascript
// 返回第一个匹配值
pdfh(html, "ul&&li:nth-child(3)&&a&&href")  // -> "http://example.com/…"
pdfh(html, "div.title&&Text")               // -> "标题文本"
pdfh(html, "img.poster&&src")               // -> "http://…/poster.jpg"
```

选择器语法: `选择器1&&选择器2&&…&&key`
- `Text`: 节点文本内容
- `Html`: 节点内部 HTML
- `outerHtml`: 节点外部 HTML(含自身标签)
- 属性名: `href`, `src`, `data-id` 等

#### `pdfa(html, rule)` - 数组(多个值)

```javascript
// 返回所有匹配值的数组
pdfa(html, "ul&&li&&a").forEach(a => {…})  // -> ["link1", "link2", …]
```

#### `pd(html, rule, baseUrl='')` - 别名

`pd` 是 `pdfh` 的别名,功能相同。

出处:
- https://github.com/hjdhnx/drpy3 - lib/parse.js 中 pdfh/pdfa 由宿主注入
- drpy2 源码中使用这些函数的大量示例

### 2.3 其他常用 API

#### `joinUrl(baseUrl, relativeUrl)` - URL 拼接

```javascript
joinUrl("http://example.com/path/", "../file.html")  // -> "http://example.com/file.html"
```

#### `local(key, value)` - 本地存储 - **同步**

```javascript
// 读取
local("访问令牌");  // -> "token_value" 或 null

// 写入
local("访问令牌", "new_token_123");

// 删除
local("访问令牌", null);
```

存储作用域: 每个 spider 源独立的 key-value 存储(类似 localStorage)。

#### `CryptoJS` - 加解密库

已注入全局的 `CryptoJS` 对象,包括:
- `CryptoJS.MD5(str)`
- `CryptoJS.enc.Base64.stringify(words)`
- `CryptoJS.enc.Base64.parse(str)`
- `CryptoJS.AES.encrypt(str, key)` / `.decrypt(...)`
- `CryptoJS.HmacSHA256(msg, key)`

#### `base64_encode()`, `base64_decode()` - Base64 - **可能是函数包装**

```javascript
base64_encode("hello");  // -> "aGVsbG8="
base64_decode("aGVsbG8=");  // -> "hello"
```

#### `md5(str)` - MD5 哈希

```javascript
md5("password123");  // -> "482c811da5d5b4bc6d497ffa98491e38"
```

#### `log(msg, level='info')` - 日志 - **同步**

```javascript
log("加载成功");       // info 级
log("错误信息", "error");  // error 级
```

出处:
- https://github.com/hjdhnx/drpy3 - 宿主注入的5个基础函数
- drpy-tvbox Python 实现中的 request() 和 local() 等

---

## 3. QuickJS 特有能力与 drpy2 依赖

### 3.1 drpy2 是否用到 QuickJS 独有特性?

**未找到** QuickJS 特有的模块依赖(如 `std`/`os`)在现存 drpy2 源码中。

**可能的用法**(未确认):
- QuickJS 字节码分发(`.jsc` 文件) - 未在现存配置中找到
- QuickJS 独有的性能优化(脚本预编译) - 不是强制依赖

**重要结论**: drpy2 本身**不强制要求 QuickJS**,只要宿主提供上述全局 API,**goja 或 wasm-qjs 都可以运行**。

出处:
- https://github.com/hjdhnx/drpy3 - 明确声称 drpy2 兼容,可在不同运行时上运行
- 未在官方文档中找到 QuickJS 字节码(.jsc)的分发约定

### 3.2 drpy2 vs drpy3 的运行时区别

| 特性 | drpy2 | drpy3 |
|------|-------|-------|
| 执行模型 | 全局函数 | ES Module + 全局(兼容) |
| async/await | 不支持(同步) | 完全支持 |
| 宿主 API | req/pdfh/pdfa/… | req/pdfh/pdfa/pd/pdfl |
| 导入方式 | fetch 加载文本 | import 语句 + 模块缓存 |
| 字节码分发 | 未见 | WASM 作一等公民 |
| 运行时兼容性 | QuickJS(Android/原生) | goja/wasm-qjs/Node.js |

出处:
- https://github.com/hjdhnx/drpy3 - README

---

## 4. 苹果 CMS type 0/1 接口格式

### 4.1 type 0 接口 - JSON 格式(推荐)

请求示例:
```
GET /api.php/provide/vod/?ac=list&at=json&t=1&pg=1
GET /api.php/provide/vod/?ac=detail&ids=123,124
GET /api.php/provide/vod/?ac=videolist&wd=关键词&pg=1
```

响应格式:
```json
{
  "code": 1,                    // 1=成功, 0=失败
  "msg": "success",
  "page": 1,
  "pagecount": 10,
  "limit": 20,
  "total": 200,
  "list": [
    {
      "vod_id": "123",
      "vod_name": "电影名",
      "vod_pic": "http://example.com/pic.jpg",
      "vod_actor": "演员1,演员2",
      "vod_director": "导演名",
      "vod_content": "剧情描述",
      "vod_year": "2024",
      "vod_area": "中国",
      "vod_lang": "汉语",
      "vod_type": "电影",
      "vod_serial": "1",           // 是否连载(1=是)
      "vod_marks": "8.5",          // 评分
      "vod_douban_id": "123456",
      "vod_douban_score": "8.5"
    }
  ]
}
```

`detail` 接口额外字段:
```json
{
  "list": [
    {
      "vod_play_from": "播放源1$播放源2$播放源3",    // 播放源(用 $ 分隔)
      "vod_play_url": "第1集$http://…#第2集$http://…$$$第1集$http://…#第2集$http://…",
                                                        // 用 $$$ 分隔多个播放源
      "vod_sub": "http://example.com/sub.vtt",        // 字幕
      "type_id": "1"                                   // 分类ID
    }
  ]
}
```

### 4.2 type 1 接口 - XML 格式(旧标准)

请求:
```
GET /api.php/provide/vod/?ac=list&at=xml&t=1&pg=1
GET /api.php/provide/vod/at/xml/?ac=detail&ids=123
```

响应:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <item>
      <id>123</id>
      <name>电影名</name>
      <type>电影</type>
      <pic>http://example.com/pic.jpg</pic>
      <actor>演员1,演员2</actor>
      <director>导演</director>
      <content>描述</content>
      <play_from>播放源1$播放源2</play_from>
      <play_url>第1集$url1#第2集$url2$$$…</play_url>
    </item>
  </channel>
</rss>
```

**分隔符约定**:
- `$`: 播放源标题与 URL 之间(格式: `标题$URL`)
- `#`: 多集之间(同一播放源内)
- `$$$`: 多个播放源之间

出处:
- https://www.maccms.plus/api/collect.html - Apple CMS 官方文档
- https://blog.csdn.net/m0_65153447/article/details/130434511 - 接口参数详解
- https://github.com/imfht/maccms - API 说明文档

---

## 5. TVBox 配置 JSON 完整字段

### 5.1 顶层结构

```json
{
  "sites": [ ... ],           // 数据源定义
  "parses": [ ... ],          // 播放地址解析器
  "lives": [ ... ],           // 直播源(可选)
  "rules": [ ... ],           // 正则规则(可选)
  "doh": [ ... ],             // DNS-over-HTTPS(可选)
  "ads": [ ... ],             // 广告过滤规则(可选)
  "spider": "file:///…"       // spider 文件本地路径(可选)
}
```

### 5.2 sites[] 字段

```json
{
  "key": "source_key",                    // 唯一标识
  "name": "源名称",                       // 显示名
  "type": 0,                              // 0=苹果CMS, 1=直连, 2=js(已废弃), 3=Spider(drpy)
  "api": "http://example.com/api/…",      // API 端点或 Spider 类名
  "searchable": 1,                        // 是否可搜索(0/1)
  "quickSearch": 1,                       // 是否支持快速搜索(0/1)
  "changeable": 1,                        // 是否可换源(0/1)
  "filterable": 1,                        // 是否有筛选(0/1)
  "ext": "./json/config.json",            // 扩展配置路径或 Spider 规则文件
  "timeout": 10,                          // 请求超时(秒)
  "jar": "http://example.com/lib.jar#md5hash",  // JAR 文件(type 3)
  "style": "0",                           // UI 显示样式(可选)
  "playerType": 1                         // 播放器类型(0/1)
}
```

**type 3 (drpy/Spider) 特例**:
```json
{
  "key": "js_douban",
  "name": "豆瓣推荐(JS)",
  "type": 3,
  "api": "http://address/js/libs/drpy2.min.js",
  "ext": "http://address/js/豆瓣推荐.js",
  "searchable": 2,
  "quickSearch": 0
}
```

### 5.3 parses[] 字段

```json
{
  "name": "解析名称",
  "type": 3,                      // 0=苹果, 1=Json解析, 2=正则, 3=聚合/Spider
  "url": "Demo",                  // 解析类名(Spider)或演示值
  "header": {...},                // 自定义请求头(可选)
  "ext": ""                       // 扩展数据(可选)
}
```

### 5.4 live[] 直播源

```json
{
  "name": "直播源名",
  "type": 0,
  "url": "http://example.com/lives.txt"  // M3U 或 TXT 列表 URL
}
```

出处:
- https://github.com/seset/tvbox/blob/master/0825.json - 真实配置示例
- https://github.com/a736240087/tvbox - 多个配置示例

---

## 6. 播放环节流程与细节

### 6.1 获取播放 URL 的三种路径

#### 路径 1: 直接返回(无解析)

`detail()` 返回 `vod_play_url` → 直接使用,无需进一步解析。

#### 路径 2: 使用 parses[] 解析

TVBox 根据用户选择或 `parses` 配置,将 `vod_play_url` 发送给解析器:
- type 3 解析器(Spider):调用其 `play()` 函数,获得真实 URL
- 格式: `play(flag, id, flags)` → `{url, header, subs, danmaku}`

#### 路径 3: 代理模式

`proxy()` 函数拦截特定 URL 并转换:
```
原始 URL: http://example.com/api?videoId=123
proxy(["example.com", "api", "videoId=123"], headers)
```

### 6.2 播放器所需的信息

```json
{
  "url": "http://real.video.url/video.mp4",     // 真实播放地址(HTTP/HLS/DASH)
  "header": {                                      // 请求头(通常含 User-Agent, Referer)
    "User-Agent": "Mozilla/5.0…",
    "Referer": "http://source.com/"
  },
  "subs": [                                        // 外挂字幕(可选)
    {
      "name": "中文",
      "url": "http://example.com/sub.srt"
    }
  ],
  "danmaku": "http://example.com/danmaku/…"      // 弹幕地址(可选, B站专用)
}
```

### 6.3 特殊情况: WebView 嗅探

某些视频网站**需要 JavaScript 执行**才能获得真实 URL:
- 检测: `vod_play_url` 包含 `jx=1` 或指向 HTTP 请求无法直接获取
- 处理: TVBox 使用内置 WebView 加载页面,执行 JavaScript,获得真实 URL(浏览器控制台、网络捕获或 DOM 修改)
- **drpy2 中**: `play()` 可能返回 `{url: '待处理'}`,由宿主转交给 WebView

出处:
- https://github.com/hjdhnx/drpy-node - play() 函数定义
- drpy2 实现中 play() 返回的 JSON 结构

---

## 对运行时选型的结论

### goja vs QuickJS(wasm) 对 drpy2 的影响

| 选项 | 优点 | 缺点 | drpy2 兼容性 |
|------|------|------|-------------|
| **goja** (纯 Go JS 引擎) | 无 CGO, 跨平台, 启动快 | 不支持同步网络(无 net.Dial in JS), 性能中等 | **条件可用**:宿主需同步实现网络层 |
| **QuickJS + wazero**(WASM) | 性能较好, 接近原生 | CGO 编译复杂, 启动稍慢, WASM 转换成本 | **完全兼容**:QuickJS 对 drpy2 无特殊依赖 |

### 关键决策点

**问题 1**: drpy2 源文件是否用 **QuickJS 字节码(.jsc)分发**?
- 查阅结果: **未发现**现存 TVBox 生态使用 .jsc 文件
- 结论: 可以不支持字节码分发,仅支持源码 .js

**问题 2**: 宿主的 `request()` 能否是**异步**的?
- drpy2 调用: `var html = request(url)` (期望同步)
- 技术可行: 用 wasm 的 `Atomics.wait()` 模拟同步等待(Go goroutine 响应)
- **但成本高**:Linux SIGEV_THREAD_ID 信号栈(用户在 issue #65 踩过)

### **推荐方案**

**选择 QuickJS + wazero (不选 goja)**

理由:
1. drpy2 高度依赖**同步网络 API**,在纯 Go 中难以优雅实现
2. QuickJS(WASM) 可在宿主线程同步阻塞,由 Go 侧 select 唤醒,无信号栈问题
3. 兼容 drpy2 百分百,无性能黑魔法
4. 四端(Win/Linux/Android/TV)wazero 都有良好支持

**次选**: goja + 自建异步 promise 库(难度大,生态割裂)

出处:
- https://github.com/hjdhnx/drpy3 - 没有 QuickJS 特有依赖的陈述
- 项目记忆库 `docs/go-migration/` - Go 层实现的经验

---

## 我们必须实现的宿主 API 清单

| API | 同步/异步 | 语义 | 实现难度 | 优先级 |
|-----|---------|------|--------|--------|
| `request(url, opts)` | 同步 | HTTP GET/POST,返回文本 | **中**(需线程同步模式) | **P0** |
| `pdfh(html, rule, base)` | 同步 | jsoup 风格 HTML 单值提取 | 中(需 CSS 选择器库) | **P0** |
| `pdfa(html, rule)` | 同步 | jsoup 风格 HTML 数组提取 | 中(同上) | **P0** |
| `pd(html, rule, base)` | 同步 | pdfh 别名 | 低(直接转发) | **P0** |
| `pdfl(html, rule)` | 同步 | 链接列表(特殊) | 中 | P1 |
| `joinUrl(base, rel)` | 同步 | URL 相对路径转换 | 低 | **P0** |
| `local(key, value)` | 同步 | 持久化存储读写 | 低 | P1 |
| `CryptoJS.*` | 同步 | 加解密库(全局对象) | 低(npm 库) | P1 |
| `base64_encode/decode()` | 同步 | Base64 编解码 | 低(stdlib) | P1 |
| `md5(str)` | 同步 | MD5 哈希 | 低(stdlib) | P1 |
| `log(msg, level)` | 同步 | 日志输出 | 低 | P1 |
| `setResult(obj)` | 同步 | 设置返回值(drpy3) | 低 | P2 |

**P0 优先级** (必须实现,drpy2 基础功能):
1. `request()` - 所有网络操作的基础
2. `pdfh()`, `pdfa()`, `pd()` - 所有 HTML 解析的基础
3. `joinUrl()` - URL 处理

**P1 优先级** (drpy2 常用,非阻塞):
- `local()` - 缓存访问令牌等
- `CryptoJS.*`, `base64_*`, `md5()` - 加解密
- `log()` - 调试

**P2 优先级** (drpy3+ 新增,可延后):
- `setResult()` - drpy3 专用返回机制

---

## 附录: TVBox 多仓配置格式

多仓(storeHouse)允许配置订阅多个源列表:
```json
{
  "storeHouse": [
    "http://example.com/tvbox1.json",    // 第一个仓库(URL,或 file:///)
    "http://example.com/tvbox2.json"
  ]
}
```

或直接用 `urls` 数组(同义):
```json
{
  "urls": [
    {"name": "仓库1", "url": "http://example.com/tvbox1.json"},
    {"name": "仓库2", "url": "http://example.com/tvbox2.json"}
  ]
}
```

TVBox 会在启动时按顺序加载,合并成一个配置。

**配置加密/伪装**(未深入调查):
- Base64 编码
- 图片隐写(少见)
- zip 压缩后 Base64

---

## 主要信息来源

1. https://github.com/hjdhnx/drpy3 - drpy3 源码和文档
2. https://github.com/hjdhnx/drpy-node - drpy-node 实现
3. https://github.com/trytrytogo/drpy-tvbox - drpy-tvbox 运行时
4. https://github.com/FongMi/TV - FongMi 播放器(QuickJS 集成)
5. https://www.maccms.plus/api/collect.html - 苹果 CMS 官方 API 文档
6. https://github.com/seset/tvbox - TVBox 配置示例

---

**文档版本**: 2026-09-19
**调研范围**: drpy2/drpy3/drpy-node/FongMi TV/CatVodOpen 等开源项目源码分析
