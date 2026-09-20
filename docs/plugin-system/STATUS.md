# 阶段 ① 验收对账(2026-09-20 审计)

> 对照 [`spec/19-plan.md`](spec/19-plan.md) 19.2 交付物、D322 四项验收、19.4 spike、19.5 门禁,逐条核对 `0520c36b..1696a090` 六个提交的实际代码。
> 标记:✅ 做完并有证据 · ⚠️ 部分 · ❌ 没做 · ⏸ 按计划不到期。
> 2026-09-20 第二轮:❌/⚠️ 已清空(六个提交 `6686ee8f..`),可进阶段 ②。

## 交付物

| # | 交付物 | 状态 | 证据 |
|---|---|---|---|
| 1 | `core/plugin`:安装器底线、manifest 校验、来源记录、启停/待重启、连错禁用、连崩安全模式、市场订阅、下载回退 | ✅ 八项全 | `install.go:24-149` `manifest.go:142-163` `state.go:17-26` `host.go:99-207,427-461` `market.go` 全文 |
| 2 | `core/plugin/rt`:goja + 事件循环 + 超时 Interrupt + 子运行时 + Web 全局 + fetch + storage/secrets/files/registry/html/crypt/js.bundle | ✅ 全 | `runtime.go:96-196` `jsctx.go` `globals.go`+`prelude.js` `fetch.go:254-385` `storage.go` `registry.go` `html.go` `crypt.go` |
| 3 | `core/source`:`SplitPlugin` 按最后一个 `/` 切、数据源动词命令、统一结构、列表钩子与屏蔽链 | ✅ 全(动词命令落在 `core/datasource`) | `source/source.go:64-74` `datasource/verbs.go:43-52,181-198` |
| 4 | 宿主 UI(两壳)13 项 | ✅ 三端齐(TV 六项 + 手机观看历史,2026-09-20 补) | 见下表 |
| 5 | `linplayer/tvbox`:type0/1、drpy、配置解码、订阅、多仓、解析链、三端嗅探、rules/ads、错误映射、Android jar、type4 | ✅ 11/11 | `plugins/tvbox/src/*.ts`;桌面 jar 按 D351 归 spike,已显式关闭 `PluginShell.cs:21-23` |
| 6 | `lp` CLI:create/build/pack/dev/check | ✅ 5/5(另有 submit) | `core/cmd/lp/main.go:94,193,219,246,281,444` |
| 7 | 假站 fixture 进仓库跑门禁(D321) | ✅ | `core/internal/fakevod/fakevod.go`;`check-core.sh` 第 1 关经 `datasource/tvbox_e2e_test.go` 拉起 |

### 交付物 4 逐项 × 三端

| 项目 | 桌面 | 手机 | TV |
|---|---|---|---|
| 服务器列表分组与角标 | ✅ | ✅ | ✅ |
| 添加服务器页插件类型表单 | ✅ | ✅ | ✅ |
| 数据源首页 / 分类 / 筛选 | ✅ | ✅ | ✅ |
| 源内搜索 | ✅ | ✅ | ✅ 按 **D554** 不做框,由搜索页的按源分行聚合搜索承担(`tv/SourcePagesTv.kt:80`) |
| 详情 / 线路 / 选集 / 播放 | ✅ | ✅ | ✅ |
| 聚合搜索**按源分行** | ✅ | ✅ | ✅ 一源一行 + partial 流式(`tv/SearchPage.kt` `AggregateRows`) |
| 换源三层 | ✅ | ✅ | ✅(分层渲染未逐行复核) |
| 「允许聚合」开关 | ✅ | ✅ | ✅ 服务器面板 + 插件源菜单键(`tv/ServersPages.kt` `AggregateItem`) |
| 全局观看历史 | ✅ | ✅ `ui/pages/ListPages.kt` `HistoryPage`,收藏页右上角入口 | ✅ `tv/ListPages.kt` `HistoryPageTv`,入口在收藏页 |
| 全部收藏含数据源 | ✅ | ✅ | ✅ 并进 `source.favorites` 分组(`tv/ListPages.kt`) |
| 插件页四标签 | ✅ | ✅ | ✅ 已安装 / 市场 / 接管位 / 仓库(`tv/SourcePagesTv.kt`) |
| 扩展组件页 | ✅ | ✅ | ✅ `tv/SourcePagesTv.kt` `ExtensionsPageTv`,设置页入口 |
| 接管位「设了有反应」 | ⏸ | ⏸ | ⏸ 阶段 ① 只登记不渲染,但界面没说明 |

### 18.1 排行榜 / 付费追剧日历回宿主

| 项 | 桌面 | 手机 | TV |
|---|---|---|---|
| 排行榜(含 Bangumi 榜) | ✅ | ✅ | ✅ |
| 追剧日历页 | ✅ | ✅ | ✅ |
| 「已入库 / 可播」(D366) | ✅ | ✅ | ✅ `tv/DiscoverPage.kt` `CalendarPane`,命中直接进详情/起播 |
| 开播提醒(后台通知) | ✅ 程序运行时已接(`AppJobs.cs:34`);⏸「应用没开时」挂 **D502 托盘**,不属阶段 ① | ✅ `CalendarWorker.kt` | ✅ 同左 |
| **付费解锁:订单号校验** | ✅ `CalendarPage.cs` `Start/ShowGate` | ✅ `DiscoverPages.kt` `CalendarGate` | ✅ `tv/DiscoverPage.kt` `CalendarGateTv`(带赞助二维码) |

桌面开播提醒这条**是我上一版写错了**:`Views/AppJobs.cs:34` 真在调 `sync.calendarDue`,
挂点在 `MainWindow.axaml.cs:1537` 的 `AppJobs.Start(_core)`,启动 1 分钟后一次、之后每 30 分钟。
差的是**「应用没开时也提醒」**:桌面整个没有托盘(`grep -rn "TrayIcon" apps/windows` 无果),
而 D502 写的就是「桌面应用退到托盘时也跑」—— 托盘本身还没建,所以这半条挂在 D502 上,不属于阶段 ①。

付费这条 **2026-09-20 已接上三端**:`system.afdianVerify` 校验通过后把订单号落进核心层偏好
`calendar_unlock_order`(`core/sync/afdian.go` `unlockCalendar`,先红后绿测试
`sync/library_test.go` `TestUnlockCalendar_凭据要落盘`),三端共用、只在解锁时校验一次。
未解锁**锁整页、连数据都不拉**(D551)。
查下来 `system.afdianVerify` 在**任何提交、任何端都没被调用过**(`git log --all -S afdianVerify -- apps/` 空);
旧实现在已删的 Rust 栈 `rust-final:ui/desktop/pages/CalendarPage.tsx` 里,解锁态存 WebView 的
`localStorage["cal:afdian"]` —— 新壳读不到,所以 D228「老用户沿用」按 **D550 推翻**,老用户重输一次。
手机 `DiscoverPages.kt` 那句「赞助后可解锁『我追的番』过滤」已改掉(手机端确实没有这个过滤;桌面有)。

## D322 四项验收

| # | 验收 | 状态 | 证据 |
|---|---|---|---|
| ① | 全链路能播 | ✅ | `TestTVBoxFullChain`(`datasource/tvbox_e2e_test.go:179`);Windows/安卓真机自检脚本各自跑通 |
| ② | 订阅更新,被删源记录还在 | ✅ | `TestTVBoxSubscriptionUpdate:285` |
| ③ | 换源三层 + 进度跳转 + 续播 | ✅ | `TestTVBoxSwitchAndResume:319` |
| ④ | 防崩三件套各有先红后绿测试 | ✅ 测试齐、断言真 | `rt/runtime_test.go:49,78,205`、`plugin/host_test.go:98,156`;「先红」只有 commit 正文的书面记录,没有独立的红色提交 |

## 19.4 spike

| spike | 到期 | 状态 |
|---|---|---|
| goja `Interrupt` 打断延迟 | ① 开始时 | ✅ 报告 + `rt/runtime.go:33-35`(regexp2 超时)+ 回归测试;已回填 `19-plan.md` 19.4 与 DECISIONS D552 D553 |
| 同上 Android 基准 | ① 开始时 | ✅ 2026-09-20 在 Android 16 模拟器(x86_64)补测九个用例;纯 JS 打断 max 2.5ms,**原生大 join p50 13.1s / max 35.0s 超过 30s 预算**(D553)。arm64 真机未测 |
| 桌面 JVM 跑 jar(D351) | ① 之后 | ⏸ |
| GeckoView(D375) | ② 前 | ⏸ |
| `<Player>` 区域跟随(D268) / 视频壁纸(D443) | ② / ⑤ | ⏸ |

## 19.5 门禁

| 门禁 | 状态 |
|---|---|
| check-core / check-bindings / check-style / check-workflows | ✅ 全绿(2026-09-20 实跑) |
| pack-win 出 exe、pack-android 出 APK | ✅ 105MB zip;arm64 + TV armv7 两个 APK,验签过 |
| `.d.ts` ↔ Go 注册骨架比对(D514) | ✅ `tools/sdkgen/gen.mjs`(TS 编译器 API,pnpm)→ `rt/sdkspec_gen.go`;比对在 `rt/sdk_contract_test.go`(两条判据),产物最新性是 check-core 第 7 关。先红后绿:把 `storage.remove` 改名 `delete`,两条判据当场红 |
| manifest schema ↔ `lp check` 一致 | ✅ check-core 第 6 关**真跑 CLI**(`lp check plugins/tvbox`)+ 一条必须红的坏 manifest。先红后绿:给 manifest 加一条没实现的 command,第 6 关红在「声明了但没实现」那句 |
| 锚点 / 路由名清单 ↔ 代码登记 | ⏸ 阶段 ② 的接管位页才产生登记 |
| mpv 脱敏清单测试(E3) | ⏸ `player` 命名空间阶段 ① 未实现(`rt/sdk.go:4`) |

## 红线

✅ 新增六个提交里没有 IP / 域名 / 端口 / 账号 / 密钥;`docs/plugin-system/tvbox-test-sites.local` 与 `scripts/drpy-source.local` 被 `.gitignore:15` 挡住,`git log --all` 查无此文件。

---

# 阶段 ② 验收对账(2026-09-20,进行中)

> 对照 [`spec/19-plan.md`](spec/19-plan.md) 19.3 的 ② 行(D543)。
> 标记同上:✅ 做完并有证据 · ⚠️ 部分 · ❌ 没做 · ⏸ 按计划不到期。

## 交付物

| # | 交付物 | 状态 | 证据 |
|---|---|---|---|
| 1 | `core/plugin/ui` 渲染器(ops 协议) | ✅ | `core/plugin/ui.go`;`plugin.ui.mount/unmount/event` 三条命令;两层合批各有测试 |
| 2 | SDK 的 Preact / 最小 DOM / JSX | ✅ | `tools/uibundle/` → `rt/uiruntime.js`(embed);41 个组件名 + h/hooks 从定义源生成 |
| 3 | 桌面渲染器 | ✅ | `Views/PluginUi.cs` + `FlexPanel.cs`;真渲染截图 |
| 4 | 手机渲染器 | ✅ | `ui/plugin/PluginSurface.kt` + `PluginRender.kt`;模拟器实测 250 条 op |
| 5 | TV 渲染器(焦点) | ✅ | `PluginRender.kt` `renderTv`;`PluginUiFocusTest` 三条按键驱动断言,先红后绿 |
| 6 | 官方调试面板插件 | ✅ 三页(面板 / 组件示例 / 一千项) | `plugins/debug-panel/` |
| 7 | `VirtualList` / `VirtualGrid`(D134) | ✅ | 1000 项首帧只建 24 个节点、127 条 op |
| 8 | `Canvas`(D104) | ✅ | 录制型 2D 上下文 → `canvas` op → 两端自绘;三端画的是同一张图 |
| 9 | 安全区 / 视口(D217 D425 D426) | ✅ | `plugin.ui.viewport` + `useViewport()`;手机实测拿到 上49 下24 |

## D543 的四条数字验收

| 验收 | 状态 | 实测 |
|---|---|---|
| 调试面板桌面跑通 | ✅ | `docs/images/plugin-ui/desktop-panel.png`、`desktop-gallery.png` |
| 三端同一组示例页截图**一致** | ⚠️ 三端截图都有,结构一致(布局 / Canvas / 输入 / 列表 / 错误边界),但**没有自动比对** | `desktop-gallery` / `phone-gallery` / `tv-gallery` |
| TV 1000 项 VirtualList ≥50fps | ⚠️ 核心层这半有证据(一次局部更新 2 条 op,20 项与 1000 项相同;首帧只建 24 个节点);**帧率没在 TV 上量过** | `rt/ui_bench_test.go` |
| 插件页首帧 < 300ms | ⚠️ 核心层 **7ms**(60 节点示例页);**壳把 ops 变成控件那一段没量** | `TestUI首帧预算` |
| 错误边界 先红后绿 | ✅ | `TestUI错误边界只崩那一块`;模拟器上真拦住了插件抛的错 |
| 焦点 先红后绿 | ✅ | `PluginUiFocusTest`:第一次跑就红出「进插件页焦点没有落点」(那个 P0) |
| 安全区 先红后绿 | ✅ | `TestUI视口与安全区送得到插件手里`:第一次跑红出「订阅挂在 useEffect 里,前 100ms 的变化没人接」 |

## 这一轮截图逼出来的 bug(都不报错、编译全绿)

| 症状 | 真因 |
|---|---|
| 整页只剩一个按钮,别处一片空白 | `#text` 做成了独立控件,塞不进 `TextBlock` |
| 样式一条都没生效 | Preact 给数字样式自动补 `px`,壳按数字读读不到 |
| 标签和值挤在一起 | `justify` 在 StackPanel 上无效(桌面);Compose 的 SpaceBetween 不撑满等于没写 |
| 点完某个按钮整个插件再没反应 | UI 回调**一点预算都没有**,死循环占住事件循环,看门狗看不见 |
| 安卓永远停在骨架屏 | 首帧在壳订阅之前就发成事件了 |
| 滑着滑着内容跳回顶部 | 虚拟列表没按 `firstIndex` 垫上方空白 |
| 开关旁边的文字被挤成一列竖字 | TV 上把 Switch 映射成了整行的 `PanelItem` |
| 进插件页遥控器整个失灵 | 没有元素认领初始焦点(TvNav 顶上警告的那个 P0) |
| 安全区永远是 0 | 订阅挂在 `useEffect` 里,而没有 rAF 时 Preact 的 effect 要等 100ms |
| Canvas 在高密度屏上只有桌面三分之一大 | 指令里的坐标是设备无关像素,而 DrawScope 用物理像素 |
