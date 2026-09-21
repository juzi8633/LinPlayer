# 19. 实施计划

## 19.1 总顺序(D312 D327)

| 阶段 | 内容 | 顺带 |
|---|---|---|
| ① | 运行时(goja + esbuild)+ manifest + 安装管理 + 数据源接口 + 官方页面画数据源;TVBox 插件验收 | 排行榜、追剧日历从 `6b290d80^` 恢复进宿主(18.1) |
| ② | UI 渲染器(JSX → 两端原生) | 调试面板插件(渲染器的第一个真实用户) |
| ③ | Trakt/Bangumi 同步插件、字幕翻译插件(先补回老功能) | 规则编辑器插件 |
| ④ | 直播插件 | — |
| ⑤ | 主题与壁纸 | 各端官方示范主题 |
| 发布 | 2.0.0 | GitHub Release 说明 + Pages 介绍文章(D415);官方仓库清空重建前再确认(D149)。大版本的 Release 正文**必须**带 `docs/plugin-system/RELEASE-<VERSION>.md`(加了什么 / 删了什么 / 挪去哪了),`scripts/release-notes.sh` 少了它直接红(D570)—— 只有提交清单的话,用户读完不知道这版删了什么 |

**三端推进**:每个阶段桌面先打通(有 `scripts/selfcheck-win.sh` 自检),Android 手机/TV 紧跟;**2.0.0 要求三端齐**(D314)。

**先定 API 再写代码**(D329):本文 + `api/` 草稿 + `COVERAGE.md` 已完成这一步;实现中发现 API 要改,先改 `api/plugin-sdk.d.ts` 与本文,再改代码。

## 19.2 阶段 ① 细化

交付物:
1. `core/plugin`:包解析与安装器底线(D421)、manifest 校验(对 `api/manifest.schema.json`)、安装来源记录、启停与「待重启」状态、连错自动禁用、连崩安全模式、市场订阅与 index.json、下载与回退。
2. `core/plugin/rt`:goja 运行时 + 事件循环 + 超时计时与 `Interrupt` + 子运行时 + Web 全局 + `fetch`(含局域网拦截、默认超时、manual 重定向、Cookie 罐)+ `storage`/`secrets`/`files`/`registry`/`html`/`crypt`/`js.bundle`。
3. `core/source`:`SplitPlugin` 改按最后一个 `/` 切;数据源动词的核心层命令;统一结构;列表钩子与屏蔽的调用链。
4. 宿主 UI(两壳):服务器列表分组与卡片角标、添加服务器页的插件服务器类型表单、数据源首页/分类/筛选/搜索(**TV 除外,见 D554**)/详情/线路/选集/播放、聚合搜索按源分行、换源三层、「允许聚合」开关、全局观看历史与全部收藏、插件页(已安装/市场/接管位/仓库)、扩展组件页。**阶段 ① 不做 UI 渲染器**:需要插件自绘 UI 的地方(如解析管理页)先用 manifest 设置项或推迟到 ②。
5. `linplayer/tvbox` 插件:type 0/1、drpy、配置解码、订阅、多仓、解析链、嗅探(WebView 三端)、rules/ads、错误映射。jar/py/type 4 在 ① 内做 Android 与 type 4;桌面 jar 等 spike。
6. `lp` CLI:create/build/pack/dev(桌面本地目录)/check。
7. 假站 fixture:苹果CMS 接口 + drpy 规则 + 加密配置样本,进仓库跑门禁(D321)。

验收(D322,四项全过):
1. 全链路能播:添加订阅 → 选源 → 首页/分类/搜索/详情 → 出画面;
2. 订阅更新:增删源后列表跟着变,被删源的记录还在;
3. 换源三层出结果并带进度跳转 + 观看记录续播;
4. 防崩三件套(死循环被超时打断、连错自动禁用、连崩进安全模式)各有**先红后绿**的测试。

真实站点只在本地端到端测,地址在被忽略的 `docs/plugin-system/tvbox-test-sites.local`,**不进任何提交**(D321)。

## 19.3 阶段 ②~⑤ 验收

| 阶段 | 验收 | 出处 |
|---|---|---|
| ② | 调试面板插件桌面跑通;三端同一组示例页(布局/列表/输入/Canvas/动效)截图对比一致;TV 1000 项 VirtualList ≥50fps、插件页首帧 <300ms;错误边界/焦点/安全区各有先红后绿测试 | D543 |
| ③ | 老功能对等(对照 `6b290d80^` 逐项勾:同步、字幕翻译、Whisper;排行榜/日历在 ① 恢复时同样逐项对等);实际登录一次跑通 scrobble | D545 |
| ④ | 公开 IPTV 源(本地测)能播能换台;XMLTV 节目单正确;回看/时移至少一种格式跑通;TV 遥控全流程(上下换台、数字键、确认呼出列表、返回两次退出) | D544 |
| ⑤ | 各端一套官方示范主题;主题出错回退先红后绿测试;壁纸与视频壁纸跑通 | D546 |

## 19.4 Spike(结果决定设计分支,先做再写正式代码)

| spike | 何时 | 决定什么 | 出处 |
|---|---|---|---|
| ~~goja `Interrupt` 打断死循环的实际延迟;Android 同组基准~~ **已做**,结论见下 | ① 开始时 | 超时机制够用 + 补 regexp2 超时 | `09-goja-interrupt-spike.md` |
| 桌面 JVM 跑安卓 jar:用公开配置的 jar 源跑 home/search,报告跑通率与组件体积 | ① 之后 | 桌面 jar 支持 / 改「仅安卓」 | D351 |
| ~~Android 动态加载 GeckoView~~ **已做,结论:不做**,见下 | ② 前 | 改成「缺 WebView 就明说不可用 + 指路装系统 WebView」(D560) | `11-geckoview-spike.md` |
| ~~`<Player>` 视频层区域跟随~~ **已做**,见下 | ② | 桌面视频不是子窗口,`<Player>` 可做;约束是 render context 单例(D561) | `10-player-region-spike.md` |
| ~~视频壁纸实现方式~~ **已做,结论:安卓做、桌面 2.0.0 不做**,见下 | ⑤ | 安卓 ExoPlayer + 独立 SurfaceView;桌面降级静态图 + Canvas/着色器(D564) | `12-video-wallpaper-spike.md` |

spike 结论写进 `docs/research/plugins-v2/`,并回填本文与 DECISIONS.md。

### 已完成:`<Player>` 区域跟随(2026-09-21,`10-player-region-spike.md`)

D268 的前提**不成立了**:桌面视频不是独立子窗口,而是视觉树里的 `OpenGlControlBase`
(通道 B,`PlayerPage.cs:31`)。区域跟随靠布局系统本来就有,不需要追窗口。
真正的约束是 **mpv render context 全局单例**(`core/player/player.go:530`):
同一时刻只能有一个视频宿主,而且 `lp_gl_uninit` 现在无条件销毁它 ——
插件页的 `<Player>` 卸载会把主播放页的画面一起弄没。实现时要加引用计数 + 一条「谁在前台谁持有」的仲裁,
并用**先红后绿**的测试钉住「插件页的 `<Player>` 卸载后主播放页还有画面」(D561)。

### 已完成:GeckoView(2026-09-21,`11-geckoview-spike.md`)

结论 **不做**(D560)。dex 动态加载这条路仓库里已经在走(jar spider),但 GeckoView 的主体是
原生 `libxul.so` + 一整套 assets,它的加载器按「构建期依赖」写死,要从私有目录加载等于
自己维护一份加载器补丁并跟着它每次升版重做;而这条 spike 的价值全在「各盒子兼容性」那半 ——
手上没有盒子,模拟器上的结论对定制盒子没有签字效力。
改成:缺 WebView 时 `app.capabilities.webview === false`(已实现)+ 界面说人话并指路装系统 WebView。
TVBox 侧只有嗅探(D59)依赖它,jar / drpy / 苹果CMS / type4 四类源都不经过。

### 已完成:goja `Interrupt`(2026-09-20,`09-goja-interrupt-spike.md`)

1. **超时机制够用**:纯 JS 死循环(含微任务里的)被打断的延迟桌面 <1ms、Android <2.5ms,
   远小于最小预算 `BudgetHook = 300ms`。两个平台同量级 —— goja 每条字节码查中断标志,与平台无关。
2. **必须补一处**:回溯类正则(前瞻 / 反向引用)会落到 regexp2,而 regexp2 默认不超时、
   匹配中途也不看 goja 的中断标志,**一条坏规则能把运行时永久卡死**。
   `core/plugin/rt` 在包初始化把 `regexp2.DefaultMatchTimeout` 钉 1 秒(D552)。
   代价:超时的那次匹配被 goja 当成「不匹配」,插件看不到错误结果。
3. ⚠️ **预算不是硬上限**:单次原生大操作(超大数组 `join`、超大字符串 `repeat`)中途不查中断标志,
   要跑完这一步才停。同一段 3000 万元素 `join` 桌面 2.8s、Android 模拟器 p50 13.1s / max 35.0s ——
   **慢设备上一次这种操作就能超过 `BudgetData = 30s`**。不再加机制,内存看门狗(D141)兜住同一类问题,
   但文档里不许把预算说成硬上限(D553)。

## 19.5 提交与门禁(D328 D229)

- 每个阶段**本地提交,到 2.0.0 一起推**(push main 会触发自动预发布);已知风险:推之前本机是唯一副本。
- 提交规范沿用仓库:`git add` 逐个文件,中文 commit message `类型(范围): 说明`。
- 每次提交前:`check-core.sh` + `check-bindings.sh` + `check-style.sh`;动了 CI 跑 `check-workflows.sh`;出 exe(`pack-win.sh`)与 APK。
- 新门禁必须先红:反向注入真 bug 看它红。
- **判据别只挂在某一个平台的打包脚本上**(D582):问一句「不装那个平台的人改了这块代码,
  他本机会红吗?」答案是否,就把它挪进本机跑得到的门禁里 —— 否则就是一条注定
  「本机全绿、CI 红」的关卡。
- **靠概率复现的门禁给它重试,但不许放过**(D577):信号栈探针的对照组要「关掉修复必须崩」才算有牙,
  而那个死法是概率性的 —— 崩一次就通过,连着三次都不崩才判「测不出来」并判红。
  改成「没崩就算过」等于这一关只是在跑一遍程序。
- 新增门禁:`.d.ts` ↔ Go 注册骨架比对(D514)、锚点/路由名清单 ↔ 代码登记比对(6.5)、mpv 脱敏清单测试(9.1)、manifest schema ↔ `lp check` 一致。
- **红线**:任何 IP、域名、端口、账号、密钥、token 不进提交;测试站点、镜像前缀、自定义域名一律放被忽略的本地文件或 CI secret。

### 已完成:视频壁纸(2026-09-21,`12-video-wallpaper-spike.md`)

**安卓能做、桌面 2.0.0 不做。**

安卓那半是现成的:`media3-exoplayer` 已经在依赖里,而且和 mpv 是**并存可切**的两套内核,
壁纸用它不占播放那一路;垫图层用 `SurfaceView`(独立合成层),放在内容下面。

桌面那半被三件事叠着否掉:一个进程只能有一条 GL 通道(`UI_PC.md:360` 记的实测:连开两条会卡死)、
核心层的 render context 是**全局单例不是句柄**(`core/player/player.go:179`)、
`lp_gl_uninit` 无条件销毁。这正是 D561 给 `<Player>` 标出来的同一件地基 ——
壁纸是「一直在后台放着」的东西,比 `<Player>` 更早撞上它。桌面降级成静态图 +
Canvas/着色器动态壁纸(SDK 里都已经有),挑了视频壁纸包时显示封面帧并说清这一端只放第一帧。
