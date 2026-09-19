# 20. 附录

## 20.1 数字总表

改数字时本表与出处章节同改。

| 数字 | 用途 | 出处 |
|---|---|---|
| 1 秒 | UI 渲染/事件回调预算(JS 连续执行时间) | D53 |
| 30 秒 | 数据源/提供者调用预算;jar/py 墙钟超时 | D53 D355 |
| 300ms | 钩子、补全、m3u8 过滤器预算(超了跳过) | D281 D520 D493 |
| 60 秒 | 后台定时唤醒单次墙钟 | D509 |
| 5 次 / 10 分钟 | 连续报错自动禁用 | D53 |
| 2 次 | 连崩进安全模式 | D12 |
| 30 秒 | 启动稳定判定(清「启动中」标记) | 4.7 |
| 5 分钟 | `idle` 插件闲置回收 | 4.5 |
| 3 秒 | 接管首页的插件首页等待上限 | D439 |
| 500ms | 插件启动耗时标黄线 | D437 |
| TV 512MB / 桌面 1.5GB | Go 堆水位看门狗阈值(待实测校准) | D141 |
| TV 4 / 桌面 16 | drpy 子运行时同时保留上限 | D316 |
| 3 | 隐藏 WebView 全局上限 | D497 |
| 15 秒 | 嗅探转可见 WebView 的默认超时 | D59 |
| 8 秒 | 直播频道卡顿/失败自动切源 | D61 |
| 8 / 8 秒 | 换源并发上限 / 单源超时 | D234 |
| 8 | 一键检测全部源的并发 | D385 |
| 8 | 聚合搜索每源展示条数 | D258 |
| 500ms | 源内搜索停顿自动搜 | D338 |
| 50 条 | 搜索历史上限 | D339 |
| 5 个 | 服务器切换器「最近使用」 | D383 |
| 2 个 | 侧栏插件入口超过即收进「更多」 | D158 |
| 2 个 | 每张卡片插件角标上限 | D474 |
| 1~2 个 | 媒体通知自定义动作 | D511 |
| 5 条 / 小时 | 每插件通知上限 | D547 |
| 15 分钟 | 后台定时任务最短间隔 | D502 |
| 10 秒 / 30 秒 | fetch 连接 / 空闲超时 | D454 |
| 500 条 | 每插件内存日志 | D285 |
| 200 条 | 每插件内存网络请求记录 | D456 |
| 2GB / 100 倍 | 包解压总大小 / 压缩比上限 | D421 |
| 30% | m3u8 过滤删除超过总时长即放弃 | D494 |
| 7 天 | 元数据按条目缓存 | D192 |
| 每次启动 + 24 小时 | TVBox 订阅默认刷新 | D46 |
| 每次启动 + 6 小时 / 3 天 | EPG 刷新 / 缓存 | D465 |
| 3 秒 | 换台信息条 | D467 |
| 2 秒 | 直播「再按一次退出」窗口;Pages 安装深链无反应提示 | D471 D404 |
| 6 位 | `lp dev` 配对码 | D291 |
| 7 次 | 连点版本号开开发者模式 | D420 |
| 6 小时 | 官方仓库 CI 刷新 star/下载量/README | D482 |
| 30/60 fps | Canvas `requestAnimationFrame` 上限 | D105 |
| 16ms | UI patch 合批窗口 | D318 |
| ≥50fps / <300ms | TV 大列表 / 插件页首帧(② 验收) | D543 |
| 4Hz | `time-pos` 订阅默认频率 | D161 |
| 256×256 | 建议图标尺寸 | D183 |

## 20.2 错误类型

插件抛 `PluginError({ kind, message, retryAfter?, verifyUrl?, loginPage? })`;宿主 API 也以同一类型拒绝。与核心层 `bus.Err` 的映射:

| kind | 含义 | bus 码 | 宿主动作 | 出处 |
|---|---|---|---|---|
| `rateLimited` | 限流(429) | `E_UPSTREAM`(retryable) | [N 秒后重试] | D253 D323 |
| `needVerify` | 需要过验证/盾 | `E_UPSTREAM` | [去验证] | D323 |
| `needLogin` | 需要登录 | `E_AUTH` | [去登录] | D323 |
| `siteDown` | 站点不可用 | `E_NETWORK` | [换源] | D323 |
| `notFound` | 没找到 | `E_NOTFOUND` | — | D253 |
| `parseFailed` | 解析失败 | `E_UPSTREAM` | — | D253 |
| `timeout` | 超时(含预算打断) | `E_NETWORK` | — | D53 |
| `network` | 网络错误 | `E_NETWORK` | — | — |
| `unsupported` | 本设备/本插件不支持(WebView 不可用、组件未装…) | `E_UNSUPPORTED` | 按情况[去下载组件] | D177 D381 |
| `permission` | 权限不足(没下载权限、未声明 lan、凭据保护) | `E_PERMISSION` | — | D309 D247 |
| `invalid` | 参数不对 | `E_INVALID` | — | — |
| `internal` | 插件内部错误(未捕获异常归到这里) | `E_INTERNAL` | [复制错误详情] [反馈] | D172 |

「未找到」与「限流」必须分开显示。错误 `message` 写中文,用户会原样看到。

## 20.3 路由名与锚点(初版)

**这是公开契约**:改名算破坏性变更。实现时各页面在代码里登记,门禁比对本表(以 SDK 发布的清单为准,本表是初版)。

### 官方路由名

| 路由 | 页面 | 可整页接管 | 可被拦截 |
|---|---|---|---|
| `home` | 首页 | 是 | 是 |
| `aggregate` | 聚合视界 | 是 | 是 |
| `library` | 媒体库(含虚拟媒体库) | 是 | 是 |
| `detail` | 详情页 | 是 | 是 |
| `person` | 演员页 | 是 | 是 |
| `search` | 搜索页 | 是 | 是 |
| `history` | 观看历史 | 是 | 是 |
| `favorites` | 全部收藏 | 是 | 是 |
| `downloads` | 下载 | 是 | 是 |
| `ranking` | 排行榜 | 是 | 是 |
| `calendar` | 追剧日历 | 是 | 是 |
| `servers` | 服务器列表 | 是 | 是 |
| `player` | 播放页(整页接管指 OSD,见 `osd`) | 否 | 否 |
| `settings` 及 `settings.playback` `settings.danmaku` `settings.subtitle` `settings.appearance` `settings.shortcuts` `settings.storage` `settings.about` | 设置各页 | 是 | 是 |
| `settings.extensions` | 扩展组件页 | 否 | 否 |
| `settings.developer` | 开发者页 | 否 | 否 |
| `plugins` | 插件页 | **否** | **否** |
| `server.add` `login` `settings.account` `settings.network` | 凭据页 | **否** | **否** |

插件可以 `nav.push` 到任何官方路由(包括凭据页,如规则编辑器打开 `server.add` 预填插件服务器类型的表单),但不能接管、不能拦截凭据页,预填只允许插件自己服务器类型的字段。

### 锚点

| 页面 | 锚点 |
|---|---|
| 详情页 | `detail.header` `detail.title`(标题下方)`detail.actions`(播放按钮旁)`detail.overview`(简介下方)`detail.ratings` `detail.lines` `detail.seasons` `detail.episodes` `detail.cast` `detail.similar` `detail.footer`(页尾) |
| 首页 | `home.continue` `home.libraries` `home.latest` `home.footer` |
| 搜索 | `search.suggestions` `search.results` |
| 媒体库 | `library.header` `library.filters` |
| 播放页 | `player.panel`(侧栏标签容器)`player.overlay` |
| 服务器列表 | `servers.header` `servers.footer` |
| 设置 | `settings.playback.*` `settings.danmaku.*` `settings.subtitle.*` `settings.appearance.*`(每节一个锚点,如 `settings.playback.general`);凭据设置页无锚点 |

`mode`:`before` / `after`(注入,多个按安装顺序)、`replace` / `hide`(接管位,冲突用户选)。

## 20.4 官方列表名(列表变换钩子用)

`home.continue` `home.latest` `home.section.<栏目id>` `library.items` `search.results` `search.aggregate.<源>` `detail.similar` `history.items` `favorites.items` `ranking.<榜id>` `source.home` `source.category`。

## 20.5 名词对照(TVBox → 本系统)

| TVBox | 本系统 |
|---|---|
| site | 数据源(开放键 `plugin:linplayer/tvbox/<源id>`) |
| `vod_*` | 统一结构 `MediaItem` / `MediaDetail` |
| `vod_play_from` / `vod_play_url` | `lines[]` / `episodes[]` |
| `vod_remarks` | `remarks`(海报角标) |
| `homeVod` | `home.recommended` |
| `filters` | 分类 `filters[]`(单选) |
| `searchable` | 「允许聚合」默认值 |
| `parses` | TVBox 插件内部的解析链 |
| `lives` | 注册表 `live.channels` |
| `rules` / `ads` | m3u8 过滤器 |
| `spider`(jar) | `spider` 宿主能力 |
