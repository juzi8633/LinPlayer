# 阶段 ① 验收对账(2026-09-20 审计)

> 对照 [`spec/19-plan.md`](spec/19-plan.md) 19.2 交付物、D322 四项验收、19.4 spike、19.5 门禁,逐条核对 `0520c36b..1696a090` 六个提交的实际代码。
> 标记:✅ 做完并有证据 · ⚠️ 部分 · ❌ 没做 · ⏸ 按计划不到期。
> 2026-09-20 第二轮:七条缺口里 5 条真补上、1 条只重分类(桌面提醒)、1 条补一半(spike 回填漏了 SPEC 正文)。
> 复核另发现一个漏口:开播提醒绕过付费门(见 18.1 表下)。

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
| **提醒通道有没有过这道门** | ❌ | ❌ | ❌ |

⚠️ 2026-09-20 复核发现的漏口:`CalendarWorker.kt:28`(安卓/TV,每小时)与 `Views/AppJobs.cs:33`(桌面,每 30 分钟)
直接调 `sync.calendarDue`,三处都不看 `calendar_unlock_order`,核心层 `core/sync/library.go:242` 注册时也不校验 ——
**没解锁的用户照样收到「xx 开播了」通知**,与 D551「连数据都不拉」的口径冲突。
另:桌面 `CalendarPage.cs` 构造函数先铺完整日历版式再异步换成门,未解锁会闪一帧空日历;手机/TV 用三态避开了。

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
| GeckoView(D375) | ② 前 | ❌ **逾期**:② 已做完,这条没做 |
| `<Player>` 区域跟随(D268) | ② | ❌ **逾期**:spike 没做,`<Player>` 两端都是占位 |
| 视频壁纸(D443) | ⑤ | ⏸ |

## 19.5 门禁

| 门禁 | 状态 |
|---|---|
| check-core / check-bindings / check-style / check-workflows | ✅ 全绿(2026-09-20 实跑) |
| pack-win 出 exe、pack-android 出 APK | ✅ 105MB zip;arm64 + TV armv7 两个 APK,验签过 |
| `.d.ts` ↔ Go 注册骨架比对(D514) | ✅ `tools/sdkgen/gen.mjs`(TS 编译器 API,pnpm)→ `rt/sdkspec_gen.go`;比对在 `rt/sdk_contract_test.go`(两条判据),产物最新性是 check-core 第 7 关。先红后绿:把 `storage.remove` 改名 `delete`,两条判据当场红 |
| manifest schema ↔ `lp check` 一致 | ✅ check-core 第 6 关**真跑 CLI**(`lp check plugins/tvbox`)+ 一条必须红的坏 manifest。先红后绿:给 manifest 加一条没实现的 command,第 6 关红在「声明了但没实现」那句 |
| 锚点 / 路由名清单 ↔ 代码登记 | ⏸ 阶段 ② 的接管位页才产生登记 |
| `tools/coverage.py`(决定落点) | ❌ **没进任何门禁**:D550~D554 加进去后 `COVERAGE.md` 一直停在 549,本次复核重跑才补上(内容通过,554/554) |
| mpv 脱敏清单测试(E3) | ⏸ `player` 命名空间阶段 ① 未实现(`rt/sdk.go:4`) |

## 红线

✅ 新增六个提交里没有 IP / 域名 / 端口 / 账号 / 密钥;`docs/plugin-system/tvbox-test-sites.local` 与 `scripts/drpy-source.local` 被 `.gitignore:15` 挡住,`git log --all` 查无此文件。

---

# 阶段 ② 验收对账(2026-09-21,**清完上一轮复核的欠账后重写**)

> 对照 [`spec/19-plan.md`](spec/19-plan.md) 19.3 的 ② 行(D543)。
> 标记:✅ 做完并有证据 · ⚠️ 部分 · ❌ 没做 · ⏸ 按计划不到期。
> 自评 ✅ 之前逐条问过一遍「**把被测代码删掉,这条还绿吗**」——
> 答「还绿」的一律没写 ✅,下面「判据是怎么验的」那一节列了每条的反向注入结果。

## 一、上一轮的欠账,逐条

| # | 欠账(上一版 STATUS 的原话) | 现状 | 证据 |
|---|---|---|---|
| 1 | 付费门漏口:三处直接调 `sync.calendarDue`,都不看 `calendar_unlock_order` | ✅ 核心层一处堵死 | `core/sync/calendar_gate.go:27-42`(`registerGated`);四条日历命令全走它(`commands.go:46,100`、`library.go:234,242`);壳侧不再把「没解锁」当失败重试(`CalendarWorker.kt:30`、`AppJobs.cs:35`) |
| 2 | 桌面追剧日历先铺日历版式再换成门,未解锁会闪一帧空日历 | ✅ 先铺骨架 | `CalendarPage.cs:78-80`(`Skeleton.Grid`)+ `ShowCalendar()`(`:182`) |
| 3 | 静默失败:`rt/ui.go` marshal 失败仍发帧 | ✅ 改成进 error 态 | `core/plugin/rt/ui.go:62-80` |
| 4 | 静默失败:`renderer.js` 嵌套 `$fn` 永不回收 | ✅ 按节点分桶 + 前缀回收 | `tools/uibundle/src/renderer.js:9-14,29-62` |
| 5 | 静默失败:Canvas 吞异常不发 error | ✅ 画崩进 surface error | `renderer.js:345-352` |
| 6 | 假绿:`sdk_contract_test.go` 组件那条恒真 | ✅ 真判据挪到壳侧 | 组件/属性/动效的三端覆盖由 `scripts/check-plugin-ui.py` 判(check-core 第 10 关);Go 侧那条只留「真组件是函数、字符串组件挂的是自己的名字」这一半 |
| 7 | 假绿:`core/plugin/ui_test.go` 名为「JS 层合批」数的是 Go 层事件 | ✅ 换层 + 换判据 | 旧用例删了(`ui_test.go:225-235` 留了原因);新的在 `rt/ui_test.go` `TestUI一次渲染的ops只提交一次` |
| 8 | 假绿:`rt/ui_test.go` 「回调号作废」靠 surface 存在性兜住 | ✅ 换成 surface 全程挂着的三条 | `core/plugin/rt/ui_fn_test.go` 全文 |
| 9 | 假绿:check-core 第 7/8 关没 pnpm 时只警告不计 fail | ✅ 计 fail | `scripts/check-core.sh:93-97,117-121` |
| 10 | 假绿:`ui_bench_test.go` 零个 `func Benchmark` + 150ms 墙钟是 CI 飘红源 | ✅ 改名 + 去掉墙钟断言 + 真加 Benchmark | `core/plugin/rt/ui_perf_test.go:34-78` |
| 11 | 假绿:devtools 的 `gallery` / `list` 两页没有任何 Go 测试渲染过 | ✅ 两页各一条 | `core/plugin/devtools_pages_test.go:52,82` |
| 12 | hooks 与 `.d.ts` 脱节:五个声明了没挂 | ✅ 全挂 + 进门禁 | `renderer.js:105-176`;`rt/ui.go:120-134`(挂不上就整个插件加载失败);`gen.mjs` 产出 `SDKHooks`;`rt/sdk_contract_test.go:154-215` 三条 |
| 13 | 组件口径:桌面 28/41、安卓 23/41 | ✅ 两端 41/41 | `check-plugin-ui.py`;桌面 `PluginUiComponents.cs` 新建 873 行,安卓 `PluginComponents.kt` 新建 683 行 |
| 14 | 动效:`transition` / `animation` 两端 0 实现却照样放行 | ✅ 两端都实现 | 桌面 `PluginUiComponents.cs` `Motion`(Avalonia Transitions / KeyFrame + Win32 `SPI_GETCLIENTAREAANIMATION`);安卓 `PluginRender.kt` `motionOf()` / `keyframed()`(复用已有的 `LocalMotionScale`) |
| 15 | 桌面 `Slider`/`Select` 是死控件、`onEndReached` 从不触发、insets 写死 0 | ✅ 三条都接上 | `PluginUi.cs:522,534,631,690`;insets 走 `TopLevel.InsetsManager`(`:112`,Win32 平台没实现时退回 0 并注明) |
| 15.5 | (这一轮新发现)组件名对上不代表能用:`Chip.selected` 安卓认桌面不认,`onFocus`/`onBlur`/`onLongPress` 两端都没接 | ✅ 门禁扩到**属性**级,24 条全接 | `check-plugin-ui.py` 第 2 项;豁免只有 `ProgressBar.seekable`(D562:要等 `player` 命名空间)与整个 `Player`(D268),两条都写了理由在门禁文件里 |
| 16 | 安卓无 a11y、`disabled` 只对 Button 生效、`grow` 在 Column 里失效 | ✅ 三条都修 | `PluginRender.kt:645`(`Modifier.a11y()`,对所有组件生效)、`:295` + 各组件真断回调、`:218`(`ColumnScope.GrowBox`) |
| 17 | D543 数字门禁化:首帧口径不对、没脚本;TV jank 没脚本;未知组件 grep 只 echo | ✅ 四条全部脚本化并会改退出码 | 见下面第三节 |
| 18 | `tools/coverage.py` 没进任何门禁 | ✅ check-core 第 9 关 | `scripts/check-core.sh` step 9 |
| 19 | D553「预算不是硬上限」没回填 SPEC 正文 | ✅ 四处都回填 | `spec/02-architecture.md:58`、`spec/20-appendix.md:7`、`spec/16-devtools.md:54`、`spec/19-plan.md:84` |
| 20 | 两个逾期 spike(GeckoView D375、`<Player>` D268) | ✅ 都做了,结论回填 | `docs/research/plugins-v2/11-geckoview-spike.md`(结论:不做,D560)、`10-player-region-spike.md`(D268 前提作废,D561);`spec/19-plan.md:52-74` 已改 |
| 21 | 调试面板名不副实,id 也不对 | ✅ 改成 `linplayer/devtools`,四块齐 | `plugins/devtools/src/panel.tsx`(日志 / 网络 / UI 树 / 性能与存储)+ `main.tsx`;背后的 `debug` 命名空间:`.d.ts` + `core/plugin/rt/debug.go` + `core/plugin/debug.go` |
| 22 | `tv-gallery.png` 拍于 Canvas 落地之前 | ✅ 重拍(Canvas 在图里) | `docs/images/plugin-ui/tv-gallery.png`、`tv-list.png`(2026-09-21,TV 模拟器实跑) |

## 二、D543 的验收,逐条

| 验收 | 状态 | 实测 |
|---|---|---|
| 调试面板桌面跑通 | ✅ | `build/desktop-panel.png`(已看图:六个标签、插件选择器、级别筛选、空态都在) |
| 三端同一组示例页截图一致 | ⚠️ | 三端都真渲染、Canvas 在三端的图里都画出来了(`docs/images/plugin-ui/`);**判据仍是「零占位」不是「像素比对」** —— 见下面「还没做的」 |
| TV 1000 项 VirtualList ≥50fps | ✅ | `selfcheck-android.sh` 第 11 关:732 帧 / 掉 2 帧 / 0%,门槛 5%。反向注入(`LP_TVJANK_KEYS=3`)当场红在「只渲染了 18 帧,列表没真滚起来」 |
| 插件页首帧 < 300ms | ✅ | 桌面 252 ms(`selfcheck-win.sh`);安卓 TV 热路径 229 ms、冷启动 313 ms 不计门槛(`selfcheck-android.sh`)。两端反向注入(`LP_FIRSTFRAME_MS=50`)都当场红 |
| 错误边界 先红后绿 | ✅ | 核心层 `rt/ui_test.go:226`;**粒度问题作废**:D136 的「区块级」= surface 级,7.2 里 `block` 本身就是一种 surface(D557 澄清,SPEC 7.11 已加一段) |
| 焦点 先红后绿 | ✅ | `PluginUiFocusTest`(Robolectric,真发按键);这一轮把「不许降级成占位」的类型表从 18 个扩到 41 个,当场红出 `ScrollView` 无界高度必崩 |
| 安全区 先红后绿 | ✅ | `TestUI视口与安全区送得到插件手里` |

## 三、数字验收是怎么脚本化的

| 数字 | 脚本 | 口径 | 反向注入 |
|---|---|---|---|
| 首帧 < 300ms(桌面) | `scripts/selfcheck-win.sh`(第 10 段)| 起点 `PluginPageHost` 构造(nav.push 同一次调用,`PluginUi.cs:805`),终点 `TopLevel.RequestAnimationFrame`(合成帧提交后,`PluginUi.cs:193`) | `LP_FIRSTFRAME_MS=50` → `exit 1` |
| 首帧 < 300ms(安卓 / TV) | `scripts/selfcheck-android.sh`(第 10 关)| 起点 `PluginNavClock.start()`(`PluginPages.kt:525`),终点 `registerFrameCommitCallback`(`PluginSurface.kt` `onFirstFrameDrawn`);**只判热路径** —— SPEC 7.12 写的是「插件已加载的前提下」 | `LP_FIRSTFRAME_MS=50` → `exit 1` |
| TV 1000 项 ≥50fps | `scripts/selfcheck-android.sh`(第 11 关,`LP_TVJANK=1`)| `dumpsys gfxinfo` 的 janky 比例 ≤5%,外加**帧数下限 100** —— 不设下限的话「列表根本没滚起来」也是 0% | `LP_TVJANK_KEYS=3` → 18 帧 → `exit 1` |
| 未知组件降级成占位 | 两端自检各一段 | 抓日志里的「未知组件」,**会改退出码**(上一版只 echo) | 两端都从 `fail` 计数 |
| 组件 / 属性 / 动效 / hooks 三端一致 | `scripts/check-plugin-ui.py`(check-core 第 10 关)| `.d.ts` 是唯一定义源:41 个组件名 + 每个组件声明的属性 + `style.transition/animation` + `SDKHooks` 名单 | 从两个壳里删掉任一分支都会红 |

## 四、判据是怎么验的(反向注入记录)

| 判据 | 注入什么 | 结果 |
|---|---|---|
| `TestCalendarGate_未解锁一条都不给` | 把 `sync.traktCalendar` 改回 `bus.Register` | 红:「应当是 E_PERMISSION,实际 E_AUTH」 |
| `TestUI嵌套的回调号不许泄漏` | `serialize` 退回只记顶层属性名 | 红:回调号从 2 涨到 42 |
| `TestUI一次渲染的ops只提交一次` | `schedule()` 改成来一条发一条 | 红:12 条 op 拆成 11 次提交 |
| `TestSDKHooks全部挂上且可调用` | `rt/ui.go` 漏挂 `useTheme` / `useSetting` | 红:两条 `typeof = undefined` |
| `TestDevtools示例页画得出Canvas和输入` | Canvas 的 `draw` 里直接 return | 红:「Canvas 只录到 0 条指令」 |
| 桌面首帧门禁 | `LP_FIRSTFRAME_MS=50` | `exit 1`,日志「最慢一次 288 ms,超过 50 ms」 |
| 安卓首帧门禁 | `LP_FIRSTFRAME_MS=50` | `exit 1`,日志「(热)最慢 231 ms,超过 50 ms」 |
| TV jank 门禁 | `LP_TVJANK_KEYS=3` | `exit 1`,日志「只渲染了 18 帧,列表没真滚起来」 |
| `debug` 四块数据 | —(写的时候当场红)| 这条测试**抓到一个真 bug**:`c.Argument(0)` 在 `r.async` 的 goroutine 里取,goja 的值跨线程读成空串,`debug.logs('x')` 查的是 id 为空的插件、返回空表。改成在 JS 线程上先取完(`rt/debug.go:106-118`) |

## 五、还没做的(阶段 ② 范围内)

| 项 | 为什么还没做 | 打算 |
|---|---|---|
| 「三端截图一致」的判据仍是**零占位**,不是像素/结构比对 | 三端的原生控件本来就不可能像素一致(字体、行高、焦点态);要做的是**结构**比对:同一份示例页在三端产生的组件树种类与层级一致 | 用 `debug.uiTree` 把三端的树导出来比对,做成门禁。放在阶段 ③ 开头 |
| `<Player>` 两端仍是「不可用」占位 | spike 刚出结论(D561):可做,但要给 GL 初始化/销毁加引用计数 + 前台仲裁 | 跟着阶段 ③ 的播放器扩展(`player` 命名空间)一起做 |
| `usePlayerState` 调用时抛「这一版宿主还没有 player 命名空间」 | `player` 命名空间属于 SPEC 9,阶段 ③ 才做(D555 定的口径:不挂的 hook 要当场说人话,不能返回 undefined) | 阶段 ③ |
| `ui-protocol.d.ts` 与实现分叉(`ViewportCmd.insets` 四元组 vs 对象、少 `formFactor`、`plugin.ui.range` 从未注册)且无人校验 | 这一轮的门禁只覆盖 `plugin-sdk.d.ts`(插件看得见的那份);`ui-protocol.d.ts` 是核心层 ↔ 壳的内部协议 | 阶段 ③ 开头一起收:要么让 `gen.mjs` 也读它,要么合进 `plugin-sdk.d.ts` |
| 桌面 `WebView` 的 `ref` 句柄(`postMessage` / `evaluate`) | `WebViewHost` 只有窗口级的 Sniff/Evaluate/Open,没有双向句柄通道 | 已**认领并打 warn**,不是静默忽略;阶段 ③ 补通道 |
| 手机端示例页截图没随这一轮重拍 | 这一轮跑的是桌面 + TV | 阶段 ③ 开头补 |

## 六、红线

✅ 这一轮的改动里没有 IP / 域名 / 端口 / 账号 / 密钥;
真实站点仍只在被 `.gitignore` 挡住的 `docs/plugin-system/tvbox-test-sites.local`;
安卓自检里的 `10.0.2.2` 是模拟器访问宿主机的**固定保留地址**,不是任何真实主机。

---

# 阶段 ② 的第三方反向审计(2026-09-21)

> 做法照上一轮的教训:**给它代码,不给它我的结论**(明确禁止它读 STATUS)。
> 它反向注入验了四条,抓到的东西按严重度排在下面。已修的标 ✅,还没修的进「欠账」。

## 一、它抓到的(都复核过,不是误报)

| # | 问题 | 我复核的方式 | 处置 |
|---|---|---|---|
| 1 | `'token:名字'` 在两端都解不出 SPEC 20.4 的名字:桌面 `Tok.Of("color.ink2")` 查不到 → `Tok.cs:38` 返回 **透明**,那段文字整个看不见;安卓 `PluginRender.kt:297` 的 `when` 只认 `Accent`/`Ink2` 那套旧名 | 读两端代码确认 | ✅ 桌面走 `PluginShell.ColorTokens` / `NumberTokens`(和 `plugin.setEnv` **同一张表**,D556),数值 token 也认了;安卓同法 |
| 2 | `check-plugin-ui.py` 的「属性都有人接」是假绿:判据在**两个壳文件拼起来的整段文本**里搜字符串,不分组件 | 自己注入过:把桌面 `ProgressBar` 的 value 实现改成永假 → 门禁 `exit 0`,7 条 ✓ 一条没少 | ⚠️ **修了一半**:见下 |
| 3 | `([^)]*)` 在第一个右括号截断,**18 个组件的属性一条都没查**(签名里有 `() => void`) | 改成配对扫描后当场多出 9 条真缺口 | ✅ `check-plugin-ui.py` `signature()` / `top_level_props()` |
| 4 | `nav` 整个命名空间不存在;`ui` 只挂了 `toast` | 临时探针确认 | ✅ `core/plugin/rt/navui.go`;壳没接时**当场**抛 unsupported,不排一条注定超时的请求 |
| 5 | 门禁只有单向判据(挂上的必须在定义源里有),「定义源声明了、运行时一个没挂」完全看不见 | — | ✅ 反方向判据 `TestSDK定义源里的命名空间要么全挂要么在账上`:缺的成员要么挂上,要么记在 `notYetImplemented` 上**并写明理由**。当场逼出 40 条真账 |
| 6 | `paddingX/Y` `marginX/Y` 在最小 DOM 的白名单里就没有 —— **连一条 op 都不发**,壳再实现也没用 | 补进白名单后新增的用例当场从红转绿 | ✅ `dom.js` + `TestUI样式白名单与定义源逐键对齐`(gen.mjs 产 `SDKStyleKeys`,两边无共同源)。反向注入:去掉 `paddingX/Y` → 当场红 |
| 7 | `DECISIONS.md` 的 D268 原行没按表头自己的规矩改写(D561 已作废它的前提);`spec/07-ui.md` 7.4.1 写「待 spike」而 `19-plan.md` 写「已完成」,同一份 SPEC 自相矛盾 | 读两处确认 | ✅ D268 原行改写 + 7.4.1 改写 |
| 8 | 错误边界少了 D136 明文的 `[重试] [禁用]` 两个按钮(安卓那个重试按钮的代码就摆在 `Base.kt:599`,只是没传 `onRetry`) | — | ❌ **欠账**(下一轮壳侧一起做) |
| 9 | `Style` 61 个键里 20 多个两端都不读(`position/inset/zIndex/aspectRatio/shadow/backdropBlur/overflow/fit/tint/lineHeight/fontFamily/letterSpacing/borderWidth/borderColor` + 渐变背景) | 门禁第 3 关只查 `transition`/`animation` 两个键 | ❌ **欠账** |
| 10 | `TextInput` 两端行为**互为反面**(桌面失焦才发、安卓每敲一个字就发),D135 要的防抖 + `live` + `onSubmit` 两端都没有;`Checkbox` 在安卓画成拨动开关 | 门禁现在把 `TextInput.live` / `onSubmit` 列成真缺口 | ✅ 桌面接齐 `defaultValue` / `secret` / `multiline` / `live` / `onSubmit`(默认**不逐字**,声明 `live` 才逐字,D135);安卓两条同法。`Checkbox` 仍是欠账 |
| 11 | SPEC 16.5「报错栈经 sourcemap 映射回 TS 行号」完全没做:sourcemap 产出来、打进包,**没有任何消费方** | — | ✅ `core/plugin/rt/sourcemap.go`(自己解 VLQ,不引依赖);错误与 surface error 两条路都过映射;报错也进日志环,调试面板的「日志」那一块才看得到错误。反向注入:关掉映射 → 当场红 |
| 12 | 安卓 `PluginSurface(props:)` 是死参数(mount 从不转发);两端也只挂过 `page` 一种 surface,SPEC 7.2 的另外五种没有宿主挂载点 | — | ❌ **欠账**(锚点块这一轮刚接上,是第二种) |
| 13 | SPEC 16.4 的四条开发者 warn 只实现了「未知组件」一条;「未知属性」那条代码里还有注释明说不要记,和 D319 原文相反 | — | ❌ **欠账**,和 #2 一起修 |

## 二、#2 为什么只修了一半

配对扫描修掉了「18 个组件根本没查」,但**按组件分帐**没修:
判据仍是「这个属性名在两个壳文件的整段文本里出现过」,所以
`Image.placeholder` 一直被 `TextInput` 的水印顶着。

真正的修法不是把正则写得更花,是让**壳自己报**它认得哪些属性 ——
而且那张表要**被渲染器自己用**(SPEC 16.4 的「未知属性报 warn」正需要它),
表和行为才不会分叉。这件事和 #13 是同一件,一起做。

## 三、它查过、确认没问题的(免得下一个人重查)

- `core/plugin/ui.go:120` 的安全区:注入写死 0 → 那条测试当场红,是真判据。
- rt 那几条渲染器用例(合批、回调号回收、错误边界)反向注入都红,判据为真。
- 41 个组件在两端都有**真分支**,不是落进 `Unknown()` 兜底。

---

# 阶段 ③ 验收对账(2026-09-21,进行中)

> 对照 [`spec/19-plan.md`](spec/19-plan.md) 19.3 的 ③ 行(D545:**老功能对等** + 实际登录跑通 scrobble)。
> 老功能的基准是删除前那一版:`git show 6b290d80^:core/sync/…` 与 `core/translate/…`。

## 一、地基(插件能用上这些,三个插件才写得出来)

| 件 | 状态 | 证据 |
|---|---|---|
| `player` 命名空间(SPEC 9) | ✅ | `core/plugin/rt/player.go`;脱敏清单、改动记账与还原、observe 限频三件都在 rt 这一层 —— 它们是**对插件**的约束,放宿主那边会被别的入口绕过去 |
| mpv 脱敏门禁(19.5 列的那条) | ✅ | `rt/player_test.go` 四条。反向注入:从表里删一条 + 去掉脱敏分支 → 当场红在「把真值交出去了」 |
| `player.getSubtitleText`(D487) | ✅ | `core/player/subtext.go`;取回来是空的**报错**,不当成「这一轨没字幕」—— 翻译插件收到空文本会安静地产出一份空字幕 |
| `player.addSubtitle` 收文本(D164 D188) | ✅ | `core/player/transport.go`;落进宿主字幕缓存再 `sub-add`,不走 data: URL(长参数在某些 mpv 版本上被静默截断) |
| `trakt.request` / `bangumi.request`(D365) | ✅ | `core/sync/proxyreq.go`;没连账号抛 auth(不是回空),path 只收相对路径。两条各有测试,反向注入放行绝对地址 → 当场红 |
| `events` 命名空间(D94) | ✅ | `core/plugin/rt/events.go` + `core/plugin/events.go`;播放类由核心层自己的 `player.status` 推导,**按状态变化发**不是按 status 到达发 |
| 注入位(锚点 / 设置分节) | ✅ | `core/plugin/anchors.go` + 两端渲染;详情页 9~10 个锚点,四种 mode |
| `nav` / `ui` 对话框 | ⚠️ 核心层挂齐,**壳侧在做** | `core/plugin/rt/navui.go`;壳没报 `shell` 能力时**当场**抛 unsupported,不排一条注定超时的请求 |
| Whisper 转写(`player.transcribe`) | ✅ | `core/transcribe/`(6 个文件)+ `core/plugin/ext.go:110 transcribeCurrent`。**地址取自 mpv 的 `path`,不是让插件传** —— 带 token 的取流地址只有宿主拿得到(D11) |
| `ext` 命名空间(D380~D382) | ✅ | `core/plugin/rt/ext.go` + `core/plugin/ext.go`;六个组件三种命运(核心层自己下 / 壳那边装 / D560 已否掉)。四条测试,两处反向注入验过 |

## 二、D545 的「老功能对等」逐项勾

基准:删除前那一版的命令表。左边是老命令,右边是现在这件事由谁做。

| 老命令(`6b290d80^`) | 现在谁做 | 状态 |
|---|---|---|
| `sync.traktScrobble` | `linplayer/sync` 的 `scrobbleStart/Pause/Stop`(`src/sync.ts`),经 `trakt.request` | ✅ |
| `sync.bangumiSetCollection` | 同上 `markWatched` / `markBangumiEpisode` | ✅ |
| `sync.bangumiUpdateEpisode` | 同上;**subject 那一位仍是字面量 `-`**(旧实现那个「点格子恒 false」活了几个月的根因,注释原样留着) | ✅ |
| `sync.traktCalendar` / `bangumiCalendar` / `calendarLibrary` / `calendarDue` | **留在宿主**(18.1:日历要弹弹签名与 TMDB key,做成插件要走 CF 中转) | ✅ 已在阶段 ① |
| `sync.traktAccount/DeviceCode/Poll/Logout`、`bangumiAccount/AuthorizeUrl/Exchange/LoginToken/Logout` | **留在宿主**(18.2:用户只登一次) | ✅ 已在阶段 ① |
| `translate.subtitle` | `linplayer/subtitle-translate` 的面板与 `translateNow` 命令 | ✅ |
| `translate.liveStart` / `liveStop` | 同插件的实时模式(`src/live.tsx`,订阅 `sub-text`) | ✅ |
| `translate.translationEngineStatus` | 同插件设置分节里的「已连接 / 未连接」 | ✅ |
| `translate.whisperModels/Download/Delete/Deps/DownloadFfmpeg` | `core/transcribe/` + `ext` 命名空间 | ✅ 档位表、依赖定位、ffmpeg 下载全在;插件调 `ext.ensure('whisper')` 触发 |
| `prefs.getTranslationSettings` / `setTranslationSettings` | 插件自己的 manifest 设置项(key 进密钥区) | ✅ |
| 翻译管线:分块 / 并发 / 二分重试 / 回退原文 / 全失败报错 | `plugins/subtitle-translate/src/pipeline.ts`,**照搬**旧实现 | ✅ 四条测试,三条反向注入验过 |
| 各引擎的批量上限(大模型 ≤40 条 ≤4000 字 并发 3;百度 ≤50 条 ≤2000 字) | `src/engines.ts` 原样照搬 —— 这几个数是踩出来的 | ✅ |

## 三、首发官方插件(SPEC 17 的表)

| 插件 | 状态 |
|---|---|
| `linplayer/tvbox` | ✅ 阶段 ① |
| `linplayer/devtools` | ✅ 阶段 ② |
| `linplayer/sync` | ✅ |
| `linplayer/subtitle-translate` | ✅ |
| `linplayer/rule-editor` | ✅ |
| `linplayer/live` | ⏸ 阶段 ④ |

判据:`core/plugin/official_plugins_test.go` 逐个**真加载**一遍 ——
「目录在」不算数,贡献点声明了没实现 / 入口报错 / 依赖的命名空间没挂,
这三种都只在加载那一刻现形,而表现都是「装了没反应」。

## 四、还没做的

| 项 | 为什么 |
|---|---|
| **实际登录一次跑通 scrobble**(D545 明写的那一条) | 要真的 Trakt / Bangumi 账号。我手上没有,也不该去拿 —— 这一条只能由你本机登一次跑 |
| `media` / `system` / `oauth` / `emby` / `download` 命名空间 | 都记在 `rt/sdk_contract_test.go` 的 `notYetImplemented` 账上,每条带「什么时候做」。账是判据的一部分:不记在账上的缺口会让那条判据当场红 |
| `<Player>` 两端仍是「不可用」占位 | D561 的结论要给 GL 初始化/销毁加引用计数 + 前台仲裁,跟着这一批一起做 |
| 审计剩下的 7 条欠账 | 见上一节 |

---

# 阶段 ③ 的对抗审计与返工(2026-09-21)

派了一个只读代码、不读我结论的审计 agent。它抓到的东西比这一轮自己写的判据多 ——
下面按「它说了什么 / 我验了什么 / 改成什么样」记,**每条都反向注入验过会红**。

## 一、最要命的一条:同步插件的记账一次都没发生过

| | |
|---|---|
| 症状 | `linplayer/sync` 的 scrobble 从来没真发出去过,而盯它的测试正因为「它没发生」才绿 |
| 根因 | 事件载荷的字段名和定义源**三个全对不上**:`.d.ts:1041` 是 `item` / `positionSec` / `durationSec`,宿主发的是 `itemId` / `position` / `duration`(旧 `core/plugin/events.go:48`) |
| 用户能看到的 | 插件日志里一句「这一条没有 TMDB / IMDb id」—— 把宿主的问题说成了片源的问题,用户会去怀疑刮削 |
| 假绿怎么来的 | 判据写的是 `strings.Contains(msg, "scrobble")`,而那句跳过日志里正好有这四个字 |
| 现在 | `core/plugin/nowplaying.go` 按定义源组载荷,并且**真去取外部 id**(`emby.providers`,新命令);测试改成拿假的代发命令记账,判「`/scrobble/start` 有没有被调到」 |
| 反向注入 | 把载荷改回 `itemId/position/duration` → `sync_plugin_test.go:153` 当场红:`没等到代发 "/scrobble/start";这一轮实际发出去的是:[]` |

## 二、判据本身的返工

| 原来 | 问题 | 现在 |
|---|---|---|
| `rt/player_test.go:72` `len(names) < 8` | 表里 16 项,删掉 `http-header-fields` 和 `loadfile` 之后四条用例全绿 | 逐名对账(`player_test.go:81`),删项当场红;加项不用改 |
| `rt/navui_test.go:63` 只调 `notifyAllowed` | 真实路径上超额那一支直接 return,「折叠」核心层没做、壳注释写着「折叠在核心层」—— 两边都以为是对方的事 | 从 `ui.notify` 进,看壳**真的收到**那条折叠通知(`navui_test.go:70`);折叠在 `rt/navui.go:116 notifyFold` |
| 四处 `t.Skip("仓库里没有 …")` | 仓库资产找不到 = 真出事了,不是跳过的理由 | 改成 `t.Fatal`;转写那条改成指一个不存在的路径,任何机器上都走得到 |
| `implementedFully` 不含新命名空间 | player/nav/ui/events/ext/system 少成员不会红 | 九个命名空间全进表(`sdk_contract_test.go:27`) |
| `go test` 带缓存 | 跑插件 `.ts` 的那几条测试,改坏 TS 之后回 `ok (cached)`,门禁全绿 | `check-core.sh:36` 加 `-count=1`,注释写死不许去掉 |

## 三、静默失败(逐条改掉)

| 位置 | 原来的表现 | 现在 |
|---|---|---|
| `rt/navui.go` `tell()` | `nav.*` 的错误被 `_, _ =` 丢掉 → 「路由名写错」「这一端没这个页面」都表现为「调了没反应」 | 落进插件自己的日志(`navui.go:61`),调试面板看得到 |
| `rt/navui.go` `nav.onBack` | 订的是一个**没人发**的事件,返回值还被丢掉 —— 「回调返回 false 阻止返回」从来没生效 | 改成壳主动问(`rt/slots.go` + `plugin.backRequest`),D563 |
| `core/bus/bus.go` `Emit` | `q == nil` 时直接 return,进程内旁路整条链是死的 | 旁路通知挪到队列判空**之前**(`bus.go:146`) |
| `rt/errors.go` | 宿主命令的错误码全被压成 `internal`:「还没连 Trakt」和「Trakt 挂了」长得一样 | `fromBusErr`(`errors.go:225`)把 9 个错误码转成对应 kind |
| `core/plugin/player.go` `observeProp` | 属性名拼错时每轮 `continue` → 「订阅了但从不回调」 | 头一次就 `bus.Logf("warn", …)` |
| `rt/player.go` `command` | `command('set', …)` 既不记账也不看脱敏清单 → D302 的还原白写,脱敏项能被改 | 走同一条规矩(`player.go:229 notePropWrite`) |
| `pipeline.ts:112` | `failed >= total && lastErr`:引擎 reject 一个 falsy 值时全部失败也静默交出原文 | 只判条数;部分失败也 `console.warn` 留痕 |
| `live.tsx:22` | `useEffect` 依赖数组为空 → 设置里开「实时模式」要退出重进才生效 | 依赖 `[live, target, engineId]`;失败也留痕 |
| `core/transcribe` | 临时文件按地址哈希命名(两次转写互删)、`context.Background()`(停用插件后 ffmpeg/whisper 继续跑满 CPU)、解包失败留半截 exe 在最终名上 | 每次独立临时目录;ctx 绑插件生命周期(`rt.Runtime.Ctx()`);解包先写 `.part` 再改名 |
| `rt/system.go` `badAppTarget` | 漏 UNC、`\?\`、POSIX 绝对路径 | 三种都挡(`system.go:86`),测试里各一条 |

## 四、审计说了、我验完认为**不成立**的

| 它说的 | 实测 |
|---|---|
| 模型下载被上游提前截断会当成「已下载」 | Go 的 transport 对「Content-Length 说 1MB 只给 4KB」自己就报 `unexpected EOF`,`Download` 那条路会删掉 `.part`。长度自检还是加了(上游不给长度时它是第二道),但判据改成钉**结果**(正式文件不许存在),不钉报错文案 |

## 五、这一轮新增的能力

| 件 | 出处 | 为什么 |
|---|---|---|
| `player.onKey`(D563) | `rt/slots.go` + `core/plugin/keys.go` + `plugin.playerKey` | 没有它 SPEC 17.2 的 TV 直播操作一条都做不了,而 D544 把它们列成验收项 —— 规格自相矛盾,按 DECISIONS 判并追了决定 |
| `system` 命名空间 | `rt/system.go` + 两端壳 | D196 D197;`openApp` 挡本机文件与本机路径(挡的是本机程序,不是「不认识的 scheme」) |
| `ext` 命名空间 | `rt/ext.go` + `core/plugin/ext.go` | D380~D382;确认框放 rt 这一层,壳问不了人就**不下** |
| `player.playUrl` | `core/player/playurl.go` | 直播频道要播一条裸地址,且**不能**走 Emby 上报那条路 |
| `emby.providers` | `core/emby/commands.go:146` | 同步插件要外部 id 才对得上 Trakt 条目 |

## 六、还没做的(阶段 ③ 剩下的)

| 项 | 为什么 |
|---|---|
| **实际登录一次跑通 scrobble**(D545 明写) | 要真的 Trakt / Bangumi 账号,我手上没有。现在至少「有 id 就一定会发」这条有判据钉着了 |
| `media` / `oauth` / `emby` / `download` 命名空间 | 记在 `rt/sdk_contract_test.go` 的 `notYetImplemented` 账上,每条带「什么时候做」 |
| `<Player>` 两端仍是「不可用」占位 | D561 的结论要给 GL 初始化/销毁加引用计数 + 前台仲裁 |
| 两端壳还没接 `player.openPanel` / `player.setOsdVisible` / `plugin.backRequest` / `plugin.playerKey` | 核心层这一侧已经齐了;壳侧跟阶段 ④ 的直播 TV 操作一起做 |
| 审计提的 `NowPlaying` 之外的契约核对 | 这一轮只对齐了 `NowPlaying`;`.d.ts` 与宿主载荷的**逐个**核对要做成门禁 |

---

# 阶段 ④ 验收对账(2026-09-21)

> 对照 D544:**公开 IPTV 源本地测能播能换台、XMLTV 节目单正确、回看/时移至少一种格式跑通、
> TV 遥控全流程(上下换台、数字键、确认呼出列表、返回两次退出)**。

## 做到的

| 项 | 判据在哪 | 反向注入 |
|---|---|---|
| m3u / txt 解析,频道级请求头两种写法都认 | `core/plugin/live_parse_test.go:96` —— 拿**真的 `plugins/live/src/m3u.ts`** 编出来在 goja 里跑 | 丢掉 `#EXTVLCOPT` 的头 → 当场红「UA 丢了:map[]」 |
| 同名频道合并保留清晰度后缀(D466) | 同上 `:146` | —— |
| 回看四种格式(append / shift / default / flussonic) | 同上 `:191`;认不出来的模板**回空串**,界面据此不显示回看入口 | flussonic 不换段名 → 当场红 |
| XMLTV 时区当偏移解 | 同上 `:226`,以及 `live_plugin_test.go:290` 的端到端那条 | 符号反过来 → 端到端那条红在「没有『正在播:』那一行」 |
| 播不动自动换下一条并**记住能用的那条**(D61) | `core/plugin/live_plugin_test.go:177` —— 判据是「第一条不出画时真的换到了第二条」,不是「playUrl 没抛错」 | 不等出画就算成功 → 红 |
| TV 遥控:上下换台 / 数字键输号 / 确认呼出列表 / 返回两次才退 | `live_plugin_test.go:207 / :228 / :242`,走的是**真实那条路**(壳调 `plugin.playerKey` → 插件 `player.onKey` 回 consumed) | 返回键一次就放行 → 红 |
| 两端壳接上按键、返回问询、播放器覆盖层与侧边面板 | 桌面 `Views/PluginShell.cs` + `PlayerPage.cs`;安卓 `ui/plugin/PluginKeys.kt` + `PluginPlayerSurfaces.kt` | 安卓侧两条、桌面侧一条,各自当场红 |
| 覆盖层真的挂得出来(这一版最大的洞) | 桌面真机日志:`[插件UI] linplayer/live/osd 首帧上屏(冷) 29 ms,1 个节点` | —— |

## 没做到的

| 项 | 为什么 |
|---|---|
| **用公开 IPTV 源在真机上跑一遍**(D544 第一句) | 判据里的「能播能换台」是拿**本地假源**验的(起一个 httptest 服务器吐 m3u,一条地址永不出画)。真源要联网,而且地址不能进仓库(红线)—— 这一条要你本机拿一条真源点一遍 |
| 手机上插件收不到遥控器按键 | 手机播放页本来就没有按键处理(纯触摸)。闸门只接在 TV 播放页上,**没有为手机凭空造一条按键路径** |
| 代理直播(`proxy://do=live`,D534) | 要 jar 环境;注册表里读到这种源时不静默丢掉,界面上灰显 |

---

# 阶段 ⑤ 验收对账(2026-09-21,核心层完成,壳侧在做)

> 对照 D546:**各端一套官方示范主题、主题出错回退先红后绿测试、壁纸与视频壁纸跑通**。

## 做到的

| 项 | 判据在哪 |
|---|---|
| D443 的 spike 出结论(**这是阶段 ⑤ 的前置**) | `docs/research/plugins-v2/12-video-wallpaper-spike.md`,结论追成 D564:安卓做、桌面 2.0.0 不做 |
| 主题加载失败**回退官方并记住** | `core/plugin/theme_test.go:24` —— 判据是「重启之后 `ActiveTheme` 回的是空」,不是「有没有打日志」;用户手动重选时清掉那条记录 |
| 一包一端(D72) | `theme_test.go:69` |
| 各端一套官方示范主题 | `plugins/theme-midnight`(桌面 .axaml)/ `-phone` / `-tv`(JSON);`theme_test.go:120` 逐端验「列得出来 + 文件真在包里」 |
| 壁纸只有**当前选中的那个插件**换得动(D441) | `theme_test.go:87` |
| 壁纸切换**不重启**、认不出来的 kind 当场报 | `theme_test.go:125` |

## 没做到的

| 项 | 为什么 |
|---|---|
| 桌面视频壁纸 | D564 定案不做:一个进程只能有一条 GL 通道,而核心层的 render context 是全局单例 —— 和 `<Player>`(D561)是同一件地基。桌面降级成静态图 + Canvas/着色器 |
| 两端壳把主题真画出来、壁纸真垫上 | 核心层这一侧齐了(挑哪个、文件在哪、崩过没有);壳侧**在做** |
| 主题 JSON 的 `motion` / `icons` / `fonts` 三段 | 这一版壳先解释 `tokens` 与 `layout`;解释不了的段落跳过并记日志,不算加载失败 |

---

# 发布就绪(2026-09-21)

「一条命令就能发 2.0.0」:`bash scripts/release.sh` —— 门禁 → 出三端包 → 生成更新说明,
**最后停在「可以推了」**。推是外发操作,脚本不碰。

| 件 | 在哪 | 状态 |
|---|---|---|
| Release 说明(人写的那半) | `docs/plugin-system/RELEASE-2.0.0.md` | ✅ 含「已知的没做到」一节 |
| 发版手册(含要你点头的四件事) | `docs/plugin-system/RELEASE-RUNBOOK.md` | ✅ |
| 官方仓库内容 | `plugin-repo/`(**没推到任何远端**) | ✅ 结构、README、许可证口径 |
| 上架 CI(index 校验 + 可信作者自动合并) | `plugin-repo/.github/workflows/validate-pr.yml` + `tools/validate-index.mjs` | ✅ 四条自测:三拒一放行 |
| SDK / CLI 发 pnpm | `scripts/publish-sdk.sh`(**默认只演练**) | ✅ 演练列出每一个会发出去的文件 |
| 官方仓库同步 | `scripts/sync-plugin-repo.sh` | ✅ 单向:主仓库是正本 |
| 红线门禁 | `scripts/check-secrets.sh` | ✅ 新增的一律红;存量进欠账清单(只存哈希) |
| Pages 站(Astro) | `plugin-repo/site/` | ⚠️ **在做** |
| `VERSION` 改成 2.0.0 | 仓库根 `VERSION` | ⏸ **故意没改** —— 改它就等于发版,那一下留给你 |

---

# 阶段 ④ ⑤ 的对抗审计与返工(2026-09-21)

又派了一个只读代码、不读我结论的审计 agent。它抓到的最狠一条不在判据上,在**产品行为**里。

## 一、最要命的:连按两下换台会把坏地址钉成「上次能用的」

| | |
|---|---|
| 症状 | 用户在 TV 上连按两下换台之后,**上一个台**从此每次进都先卡 8 秒 |
| 根因 | `tune()` 没有代次:上一轮还在等出画(D61 的 8 秒),等满之后它把自己那条(坏的)地址 `rememberGoodUrl` 写进盘,还把 `lastChannel` 拽回上一个台 |
| 为什么没发现 | 守着 D61 的那条测试**明确回避了中途按键**的场景 —— 判据本身是对的,只是它量的那条路上没有第二次按键 |
| 现在 | `plugins/live/src/main.tsx:74` 的 `tuneSeq`:换台一次一个代次,作废的那一轮结果**一概不作数** |

同一处的第二个:换台基准原来是 `lastChannel()`(要等真出画才写盘),连按上/下时
第二下从上一个**成功**的台再算一次 —— 表现是「按了两下只走了一格」。
改成「这一刻指着哪个台」(`curRef`)。

## 二、三条判据被证明是空的(改完各自反向注入验过)

| 原来声称验了什么 | 实际 | 现在 |
|---|---|---|
| 频道级请求头(D468) | 假 player **只记 url** —— 把插件里的 headers 改成 undefined,全套直播用例照样绿。真机上的表现是 403「这台打不开」,和源挂了长得一样 | `live_plugin_test.go` 的假 player 记下 headers;样例里给二号台加了 `#EXTVLCOPT` 与地址后缀两种写法 |
| 「TV 遥控全流程」 | 只按过数字键 / 返回键 / 确认键;把 up/down 那两行改成 `return false` 也全绿 | 新增「上下键真的换台」,并验「按上」回得去 |
| 回看跑通(D544) | 插件层零覆盖;把 `catchupUrl` 的结果改成空串(回看永远失败)全绿 | 从**真实那条路**验:挂直播页 → 点频道 → 节目单里点已播那一档 → 看播的是不是回看地址 |

顺带一条写进 lessons 的:**「回看」是 `<Chip label>` 的属性不是文本节点** ——
按文本找永远找不到,而那会被读成「没有回看入口」。

## 三、门禁自己的洞(全部实测过)

| 洞 | 后果 |
|---|---|
| 上架 CI 的「只许改 index.json」写的是 `$GITHUB_ENV`,而出口读 `steps.judge.outputs` | 同时改 index.json 和 `.github/workflows/*` 的 PR **照样自动合并**,下一次 `pull_request_target` 跑的就是刚合进去的那份工作流 |
| 同一个 id 放两条 | 校验看后一条、宿主装前一条 —— 脏条目放前面就绕过全部判据 |
| 只验最新版本的 url | 历史版本的地址可以随便换,而按 `minAppVersion` 取旧版的用户拿到的就是那一个(D450) |
| `official` / `downloads` / `stars` 不校验 | 自己给自己盖官方章、刷下载量 |
| 红线扫描 | 漏 rtmp/rtsp/ws/ftp、漏 `协议://用户:口令@主机`(**域名和口令一起隐身**)、漏 IPv6、漏不带引号的 `TOKEN=`;`check-secrets.sh` 里还有一份**死的**后缀表且含 `ts` |
| 红线扫描读不到的文件**静默跳过** | 加上「读不到要报出来」之后当场现形:`git ls-files` 默认把非 ASCII 文件名转义成八进制,**18 个中文名文件从来没被扫过**,而门禁一直报绿 |
| `lp check` 的「声明的贡献点都有实现」 | 漏了 `playerOverlays` / `playerPanels` / `osd` / `anchors` / `settingsSections` / `sidebar` —— 声明了没实现照样报绿,而那正是「装了没反应」的头号来源 |

## 四、两端壳这一轮补齐的

| | 桌面 | 安卓 |
|---|---|---|
| 播放器按键 / 返回问询 | ✅ | ✅(TV;手机本来就没有按键路径) |
| 播放器覆盖层 / 侧边面板挂载点 | ✅ 真机日志:`linplayer/live/osd 首帧上屏(冷) 29 ms` | ✅ |
| 插件主题 + 失败回退 | ✅ 真造坏 .axaml 验过:界面回官方、红卡带错误原文、`theme_failed` 落盘 | ✅ |
| 壁纸 | ✅ image / canvas / shader;video 按 D564 只给底色并说清 | ✅ image / canvas / **video**;shader 记日志说没做 |
| `system.*` 五个操作 | ✅ | ✅ |

## 五、仍然没做的(逐条)

| 项 | 为什么 |
|---|---|
| **公开 IPTV 源真机跑一遍**(D544 第一句) | 判据是拿本地假源验的。真源要联网,地址不能进仓库(红线)—— 只能你本机点一次 |
| **实际登录跑通 scrobble**(D545) | 要真的 Trakt / Bangumi 账号 |
| `<Player>` 两端仍是占位 | D561 的地基(GL 引用计数 + 前台仲裁)没做 |
| 桌面视频壁纸 | D564 定案不做,和 `<Player>` 同一件地基 |
| `media` / `oauth` / `emby` / `download` 等命名空间 | 记在 `rt/sdk_contract_test.go` 的账上,每条带「什么时候做」 |
| 主题 JSON 的 `components` / `motion` / `icons` / `fonts` | 两端都先解释 `tokens` 与 `layout`,其余跳过并记日志 |
| 官方仓库的开发者文档(Starlight)与真截图 | `plugin-repo/docs/` 还是空的;索引里的 `screenshots` / `icon` **没有编造** |

---

# 发布 2.0.0(2026-09-21)

## 一、已经做掉的,带证据

| 事 | 证据 |
|---|---|
| 官方插件仓库重建并上线 | `33be484`(保留历史,一次提交换内容);站点首页中英、详情页、RSS、og 图各 200 |
| 九个官方插件发了 Release | `release-plugins.sh --yes` 发完**照索引里的地址逐条真下一遍**,九条体积全对得上 |
| 官方市场地址可用 | `https://<插件站>/registry/index.json` 返回 200 / 9 条 / `minAppVersion` 全是 `2.0.0`;`LP_PLUGIN_MARKET_URL` 已配进主仓库 Secrets |
| `VERSION` 1.1.0 → 2.0.0 | 线上最高 `1.1.0-build804`,压得过 |
| 大版本更新说明 | `RELEASE-2.0.0.md` 重写:第一张表就是「原来在哪 / 现在在哪 / 升级后要做什么」,后面紧跟「没有动的」逐条点名 |
| Trakt / Bangumi 账号连接回桌面与手机 | `ca1801cf`;两端都编过 |
| `LP_DRPY_BASE` 配进 Secrets | 地址按 sha256 反查确认(六份文件逐个比对,和本机那份**字节一致**) |

## 二、这一轮新加的判据(都反向注入验过会红)

| 判据 | 注入什么 | 红了吗 |
|---|---|---|
| `core/plugin/market_official_test.go` | `minAppVersion` 抬到 9.0.0 / `platforms` 改 `ios` / `size` 改 0 | 三次全红 |
| 插件站 `check-build.mjs` 的市场端点检查 | 删产物里的 `registry/index.json` / 体积改 0 / 少一条 | 三次全红 |
| 站点 `registry/index.json.ts` | 把真索引挪走 | 构建直接非零退出(不退回示例) |
| `release-notes.sh` 的大版本说明闸门 | 把 `RELEASE-2.0.0.md` 挪走 | 红 |
| `gen-icon-font.py --check` 改看字体产物 | 码位从 `LinIcons.codepoints` 里删掉 | 红 |
| `check-android-args.py` 放宽跟函数规则后 | 核心层改读 `pluginId` | 仍然红 |
| `release-plugins.sh` 的发后回验 | —— | 当场抓到真 bug(九个资产名全错、九条地址 404) |

## 三、一直红但不挡发布的:`Smoke Linux GUI` 的信号栈探针

`scripts/sigprobe-linux.sh` 是 issue #65 的**反向对照**:先关掉 SA_ONSTACK 修复,
要求它崩;不崩就判「探针测不出这个死法」。实测**两跑一红一绿**
(35551981736 绿,35552679783 红,后者活过 15 秒、419905 次进 Go、654 轮 GC)——
崩不崩取决于 GC 暂停信号有没有落在刚进过 Go 的线程上,是时序赌博。

它不在 `create-prerelease` 的 `needs` 里,所以不挡发布。但按本仓自己的规矩
**时红时绿的门禁等于没有门禁**,要么把触发做成确定的(自己在探针里主动往那些
线程发 34 号信号,不等 GC 碰运气),要么明说它是「抽检」。没做,记在这儿。

## 四、还要你自己来的

| 项 | 为什么 |
|---|---|
| **装上预发布点一次「设置 → 插件 → 市场」** | 包里不带任何插件,市场地址是唯一一条路;它漏注入时**不报错**,只是商店空的 |
| 公开 IPTV 源真机跑一遍(D544)、实际登录跑通 scrobble(D545) | 要你的网络与账号 |
| git 历史里的旧泄漏 | 红线原文要求「改写历史或删库重建」,破坏性操作 |

---

# 贡献点对账(2026-09-21 下午)

起因是一句话:「插件不是写了就行的,还要写好」。查下来最大的问题不在插件那一头 ——
**`contributes` 的 35 个键,宿主这一版真接了的只有 13 个**,而安装确认、插件详情、
市场分类把 35 个一视同仁地列成中文名。声明了没接的表现是:装上、点开、什么都不多,
**一条错也没有**。

## 一、接通了的(带判据)

| 贡献点 | 落点 | 反向注入 |
|---|---|---|
| `homeSections`(TV 那一端,D584) | TV 首页官方栏目之后 | 换掉 TV 的 `plugin.homeSections` → 门禁红;改名 `rememberPlayerSurfaces` → 门禁红 |
| `pageTakeovers`(D586) | `plugin.pageTakeovers`,三个壳画官方页之前问一次 | 去掉「只认选中的那个」→ 单测红 |
| `searchActions`(D587) | `plugin.searchActions`,三个壳画在搜索框下面 | 摘掉门禁标记 → 红;TV 不画 → 红;不筛掉缺标题的 → 单测红 |

## 二、没接的不再装作接了(D585)

- 核心层一张 `contribPoints`:每个键写清 `label` + 落点(空 = 没接)+ 只接了一半时接的是哪一半
- 贡献点清单给没接的加「(这一版还不支持)」
- `lp check` 多打一行警告。实跑一个声明了 `trayMenu` / `virtualLibraries` 的包:

```
  ✓ 声明的贡献点都有实现
  ! trayMenu:宿主这一版还没接「托盘菜单」,装上去不会有任何反应
  ! virtualLibraries:宿主这一版还没接「虚拟媒体库」,装上去不会有任何反应
  ! menus:只有数据源列表项那一处;条目卡片 / 单集 / 播放页更多还没有
```

## 三、门禁自己的两个洞(这一轮堵上)

| 洞 | 后果 | 堵法 |
|---|---|---|
| 安卓一棵树里装着手机和 TV 两个壳,门禁当成**一个**数 | 手机调了就算绿,TV 上一行没画也看不出来 —— 自己加的门禁第二天就被自己漏过去 | 拆成「手机 = 安卓树去掉 `tv/`」「TV = 只看 `tv/`」分别算 |
| 门禁管哪些命令写死在 `anchors.go` 一个文件名上 | 新开一个文件注册的命令悄悄漏出这一关 | 改成**源文件自己声明**(文件里写「门禁:三端都要调」);再把第 6 条和它绑起来,标记被删也会红 |

## 四、仍然没接的(声明了不会有任何反应)

`osd` `launchTargets` `nextUp` `shaders` `settingsPage` `globalOverlays`
`virtualLibraries` `keybindings` `gestures` `remoteButtons` `windows` `trayMenu`
`android` `deepLinks` `externalInputs` `providers` `m3u8Filters` `registry` `background`。

这些不再需要靠记忆维护 —— 正本是 `core/plugin/contribPoints`,`check-plugin-ui.py`
第 6 条拿 `manifest.schema.json` 对账,漏一个键就红。
