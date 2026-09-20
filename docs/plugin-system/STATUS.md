# 阶段 ① 验收对账(2026-09-20 审计)

> 对照 [`spec/19-plan.md`](spec/19-plan.md) 19.2 交付物、D322 四项验收、19.4 spike、19.5 门禁,逐条核对 `0520c36b..1696a090` 六个提交的实际代码。
> 标记:✅ 做完并有证据 · ⚠️ 部分 · ❌ 没做 · ⏸ 按计划不到期。
> 下一轮先清本文的 ❌/⚠️,再进阶段 ②。

## 交付物

| # | 交付物 | 状态 | 证据 |
|---|---|---|---|
| 1 | `core/plugin`:安装器底线、manifest 校验、来源记录、启停/待重启、连错禁用、连崩安全模式、市场订阅、下载回退 | ✅ 八项全 | `install.go:24-149` `manifest.go:142-163` `state.go:17-26` `host.go:99-207,427-461` `market.go` 全文 |
| 2 | `core/plugin/rt`:goja + 事件循环 + 超时 Interrupt + 子运行时 + Web 全局 + fetch + storage/secrets/files/registry/html/crypt/js.bundle | ✅ 全 | `runtime.go:96-196` `jsctx.go` `globals.go`+`prelude.js` `fetch.go:254-385` `storage.go` `registry.go` `html.go` `crypt.go` |
| 3 | `core/source`:`SplitPlugin` 按最后一个 `/` 切、数据源动词命令、统一结构、列表钩子与屏蔽链 | ✅ 全(动词命令落在 `core/datasource`) | `source/source.go:64-74` `datasource/verbs.go:43-52,181-198` |
| 4 | 宿主 UI(两壳)13 项 | ⚠️ 桌面基本齐、手机缺 1 项、**TV 缺 6 项** | 见下表 |
| 5 | `linplayer/tvbox`:type0/1、drpy、配置解码、订阅、多仓、解析链、三端嗅探、rules/ads、错误映射、Android jar、type4 | ✅ 11/11 | `plugins/tvbox/src/*.ts`;桌面 jar 按 D351 归 spike,已显式关闭 `PluginShell.cs:21-23` |
| 6 | `lp` CLI:create/build/pack/dev/check | ✅ 5/5(另有 submit) | `core/cmd/lp/main.go:94,193,219,246,281,444` |
| 7 | 假站 fixture 进仓库跑门禁(D321) | ✅ | `core/internal/fakevod/fakevod.go`;`check-core.sh` 第 1 关经 `datasource/tvbox_e2e_test.go` 拉起 |

### 交付物 4 逐项 × 三端

| 项目 | 桌面 | 手机 | TV |
|---|---|---|---|
| 服务器列表分组与角标 | ✅ | ✅ | ✅ |
| 添加服务器页插件类型表单 | ✅ | ✅ | ✅ |
| 数据源首页 / 分类 / 筛选 | ✅ | ✅ | ✅ |
| 源内搜索 | ✅ | ✅ | ⚠️ 有意改走搜索页(`tv/SourcePagesTv.kt:79`) |
| 详情 / 线路 / 选集 / 播放 | ✅ | ✅ | ✅ |
| 聚合搜索**按源分行** | ✅ | ✅ | ❌ 压平成一个列表(`tv/SearchPage.kt:112-119`) |
| 换源三层 | ✅ | ✅ | ✅(分层渲染未逐行复核) |
| 「允许聚合」开关 | ✅ | ✅ | ❌ `tv/ServersPages.kt` 无此开关 |
| 全局观看历史 | ✅ | ❌ 无路由,`DataSourcePages.kt:520` 零调用 | ❌ |
| 全部收藏含数据源 | ✅ | ✅ | ❌ 只有 `emby.listFavorites` |
| 插件页四标签 | ✅ | ✅ | ⚠️ 只有已安装 + 市场 |
| 扩展组件页 | ✅ | ✅ | ❌ |
| 接管位「设了有反应」 | ⏸ | ⏸ | ⏸ 阶段 ① 只登记不渲染,但界面没说明 |

### 18.1 排行榜 / 付费追剧日历回宿主

| 项 | 桌面 | 手机 | TV |
|---|---|---|---|
| 排行榜(含 Bangumi 榜) | ✅ | ✅ | ✅ |
| 追剧日历页 | ✅ | ✅ | ✅ |
| 「已入库 / 可播」(D366) | ✅ | ✅ | ❌ 不调 `sync.calendarLibrary` |
| 开播提醒(后台通知) | ❌ | ✅ `CalendarWorker.kt` | ✅ 同左 |
| **付费解锁:订单号校验** | ❌ | ❌ | ❌ |

付费这条:核心层 `core/sync/afdian.go:41` + 命令 `system.afdianVerify` 都在,**三端无人调用**,日历现在是免费的。
`6b290d80^` 里也没有这个界面 —— 是历史欠账,不是本轮回归,但 SPEC 18.1 写了要有。
另:手机 `DiscoverPages.kt:597` 文案承诺「赞助后可解锁『我追的番』过滤」,而该过滤控件不存在。

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
| goja `Interrupt` 打断延迟 | ① 开始时 | ✅ 有报告 `docs/research/plugins-v2/09-goja-interrupt-spike.md`,结论已落到 `rt/runtime.go:33-35`(regexp2 超时)+ 回归测试;**❌ 未按 19-plan.md:56 回填本文与 DECISIONS.md** |
| 同上 Android 真机基准 | ① 开始时 | ❌ 没测(报告自述 `adb devices` 为空);现在已有可用设备 |
| 桌面 JVM 跑 jar(D351) | ① 之后 | ⏸ |
| GeckoView(D375) | ② 前 | ⏸ |
| `<Player>` 区域跟随(D268) / 视频壁纸(D443) | ② / ⑤ | ⏸ |

## 19.5 门禁

| 门禁 | 状态 |
|---|---|
| check-core / check-bindings / check-style / check-workflows | ✅ 全绿(2026-09-20 实跑) |
| pack-win 出 exe、pack-android 出 APK | ✅ 105MB zip;arm64 + TV armv7 两个 APK,验签过 |
| `.d.ts` ↔ Go 注册骨架比对(D514) | ❌ 没有任何脚本/测试;`plugin-sdk.d.ts` 只在 Go 注释里被提到 |
| manifest schema ↔ `lp check` 一致 | ⚠️ 两边共用同一份 schema 且有 `TestSchemaCopiesInSync`,但没有门禁真的跑一次 `lp check` |
| 锚点 / 路由名清单 ↔ 代码登记 | ⏸ 阶段 ② 的接管位页才产生登记 |
| mpv 脱敏清单测试(E3) | ⏸ `player` 命名空间阶段 ① 未实现(`rt/sdk.go:4`) |

## 红线

✅ 新增六个提交里没有 IP / 域名 / 端口 / 账号 / 密钥;`docs/plugin-system/tvbox-test-sites.local` 与 `scripts/drpy-source.local` 被 `.gitignore:15` 挡住,`git log --all` 查无此文件。
