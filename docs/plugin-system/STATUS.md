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
