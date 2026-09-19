# LinPlayer 插件系统 SPEC

> 状态:**定稿**(2026-09-20)。本文是插件系统的设计正本,实现以它为准。
> 它由 549 条问答决定([`DECISIONS.md`](DECISIONS.md),正文里写作 `D123`)整理而成:
> 决定原文说「定了什么」,本文说「整体怎么运转、每块的细节、边界与失败时怎么办」。
> 两处冲突时**以 DECISIONS.md 为准**,并当场修正本文。
>
> 配套:
> - API 草稿 [`api/`](api/) —— `plugin-sdk.d.ts`(宿主 API 的单一定义源,D514)、`manifest.schema.json`、`index.schema.json`、`theme-json.schema.json`、`ui-protocol.d.ts`(核心层 ↔ 壳)、`examples/`(用草稿写的两个真实插件,过类型检查)
> - 逐条核对 [`COVERAGE.md`](COVERAGE.md) —— 每条决定在本文哪一章、API 哪个文件落地;由 `tools/coverage.py` 生成
> - 外部调研与实测 `docs/research/plugins-v2/`(运行时选型依据是 `08-runtime-benchmark.md`)

## 章节

| 章 | 文件 | 内容 |
|---|---|---|
| 1 | [总览](spec/01-overview.md) | 目标、非目标、原则、术语、读法 |
| 2 | [架构](spec/02-architecture.md) | 核心层插件宿主、运行时、线程模型、三条通道、包结构 |
| 3 | [信任与安全](spec/03-trust-security.md) | 完全信任 + 例外清单、凭据保护、局域网拦截、安装器底线 |
| 4 | [插件包与生命周期](spec/04-package-lifecycle.md) | `.lpplugin`、manifest、id、版本、加载/回收、启停与重启、防崩、安全模式、内存 |
| 5 | [运行时与宿主 API](spec/05-runtime-api.md) | 全局对象、fetch、存储、文件、注册表、子运行时、解析/加解密、WebView、代理、定时与后台 |
| 6 | [贡献点总表](spec/06-contributions.md) | 接管位、注入位、钩子、提供者、系统入口 —— 全部清单与冲突规则 |
| 7 | [UI 系统](spec/07-ui.md) | JSX 渲染协议、组件、样式布局、事件、列表、Canvas、动效、焦点、错误边界、页面与导航 |
| 8 | [数据源](spec/08-datasource.md) | 接口、统一数据结构、宿主侧页面行为、聚合、换源、历史收藏 |
| 9 | [播放器扩展](spec/09-player.md) | mpv 透传与脱敏、还原记账、着色器链、OSD、覆盖层、侧栏、连播、字幕/转写/抽帧、投屏、下载 |
| 10 | [提供者](spec/10-providers.md) | 弹幕、字幕、元数据、下载后端、片头片尾、预览图、EPG/频道 |
| 11 | [主题与壁纸](spec/11-theme.md) | 分端主题、桌面 `.axaml`、手机/TV JSON、壁纸接管位 |
| 12 | [系统集成](spec/12-system.md) | 命令面板、按键手势、深链、外部输入、通知、后台、Android/桌面系统入口 |
| 13 | [存储、同步与备份](spec/13-storage-sync.md) | 三类存储、同步范围、备份档位、卸载 |
| 14 | [分发与插件页](spec/14-distribution.md) | 仓库与 index.json、安装/更新/回退、插件页四个标签、免责提示 |
| 15 | [官方仓库、Pages 站与 CI](spec/15-repo-pages-ci.md) | 仓库结构、上架流程、自动合并、Pages 站、许可证 |
| 16 | [开发者工具链](spec/16-devtools.md) | `lp` CLI、SDK、开发模式、调试面板、文档 |
| 17 | [官方插件](spec/17-official-plugins.md) | TVBox、直播、Trakt/Bangumi 同步、字幕翻译、调试面板、规则编辑器、后续路线 |
| 18 | [宿主侧变更](spec/18-host-changes.md) | 排行榜/追剧日历回宿主、账号连接、扩展组件页、Watch Next、老用户迁移 |
| 19 | [实施计划](spec/19-plan.md) | 五个阶段、每阶段交付与验收、spike、提交与发版 |
| 20 | [附录](spec/20-appendix.md) | 数字总表、错误类型、接管位与锚点初版清单、名词对照 |
