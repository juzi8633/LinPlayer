# LinPlayer

<p align="center">
  <a href="https://github.com/zzzwannasleep/LinPlayer/stargazers"><img src="https://img.shields.io/endpoint?url=https://291277.xyz/gh/stars&style=flat&logo=github&label=Stars" alt="Stars"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/endpoint?url=https://291277.xyz/gh/stable&label=stable" alt="Stable"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/endpoint?url=https://291277.xyz/gh/prerelease&label=pre-release" alt="Pre-release"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/endpoint?url=https://291277.xyz/gh/downloads&logo=github&label=downloads" alt="Downloads"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/blob/main/LICENSE"><img src="https://img.shields.io/endpoint?url=https://291277.xyz/gh/license&label=license" alt="License"></a>
  <img src="https://img.shields.io/badge/Go-1.27-00ADD8?logo=go&logoColor=white" alt="Go">
  <img src="https://img.shields.io/badge/C%23-.NET%2010-512BD4?logo=dotnet&logoColor=white" alt="C#">
  <img src="https://img.shields.io/badge/Avalonia-11-8B44AC" alt="Avalonia">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <a href="https://github.com/zzzwannasleep/LinPlayer/actions"><img src="https://img.shields.io/github/actions/workflow/status/zzzwannasleep/LinPlayer/build.yml?branch=main&label=build&logo=github" alt="Build"></a>
  <a href="https://t.me/MikudesuChannels"><img src="https://img.shields.io/badge/Telegram-MikudesuChannels-26A5E4?logo=telegram&logoColor=white" alt="Telegram"></a>
</p>

<p align="center">
  <b>简体中文</b> ·
  <a href="docs/README.en.md">English</a> ·
  <a href="docs/README.ja.md">日本語</a>
</p>

**LinPlayer** 是一个 Emby 第三方客户端：一份 Go 核心层，加上每个平台各自的原生界面。
支持 **Windows / Linux / Android 手机与平板 / Android TV**，四端都有发布包。苹果全线不做。

## 各端状态

| 端 | 界面 | 状态 |
|:--|:--|:--|
| **Windows** | C# + Avalonia | 稳定可用。免安装绿色包，数据全在主程序同级的 `userdata/` |
| **Linux** | 与 Windows **同一份代码** | 出包与命令行冒烟在 CI 上验证；**尚未在真实桌面上长期使用**，遇到问题请反馈。播放用系统的 libmpv |
| **Android 手机 / 平板** | Kotlin + Jetpack Compose | 可用。双内核（mpv / ExoPlayer） |
| **Android TV** | Kotlin + Compose for TV | 全部页面已接真核心层，遥控器焦点导航；**还没在真电视上验收**。32 位包，面向电视盒子 |

> 2026-09-04 起项目从 **Rust 核心 + React/Tauri** 换成了 **Go 核心 + 各端原生界面**，
> 旧栈已从仓库删除，tag [`rust-final`](https://github.com/zzzwannasleep/LinPlayer/tree/rust-final) 是它的最后状态。

## 下载

去 [**Releases**](https://github.com/zzzwannasleep/LinPlayer/releases) 拿：

| 端 | 文件 | 说明 |
|:--|:--|:--|
| Windows | `LinPlayer-Windows-v*.zip` | 解压即用，不写注册表。升级时覆盖同一目录，`userdata/` 里的账号和配置不会丢 |
| Linux | `LinPlayer-Linux-v*.zip` | x86_64。解压后运行 `./LinPlayer`。需要系统装有 libmpv（见下） |
| Android 手机 / 平板 | `app-arm64-v8a-release.apk` | arm64 |
| Android TV | `app-tv-armeabi-v7a-release.apk` | armeabi-v7a（32 位）。装之前确认电视盒子支持这个架构 |

两个渠道：**stable**（稳）与 **pre-release**（快）。装好之后在「设置 → 检查更新」里可以直接下载覆盖，各端会自动挑自己的包。

### Linux 需要 libmpv

Linux 包不自带 libmpv，运行时按 `libmpv.so.2 → libmpv.so.1 → libmpv.so` 的顺序找系统里的：

| 发行版 | 安装 |
|:--|:--|
| Debian / Ubuntu 24.04+ | `sudo apt install libmpv2` |
| Ubuntu 22.04 | `sudo apt install libmpv1` |
| Fedora | `sudo dnf install mpv-libs` |
| Arch | `sudo pacman -S mpv` |

没装的话界面照常能开，起播时会提示缺 libmpv 并给出上面的安装命令。

### 命令行

主程序带一个不开窗口的命令行入口，直接调核心层命令，结果以 JSON 打到标准输出，适合写脚本或 ssh 远程用。
它和界面共用同一个 `userdata/`，账号不用再登一遍：

```bash
./LinPlayer commands                                # 列出全部命令
./LinPlayer call system.capabilities                # 调一条命令
./LinPlayer call emby.listResume '{"limit": 5}'     # 带 JSON 参数;emby.* 自动带上当前登录的会话
echo '{"limit": 5}' | ./LinPlayer call emby.listResume -   # 参数从标准输入读
./LinPlayer version
```

退出码：`0` 成功，`1` 命令失败（错误写到标准错误），`2` 用法错误，`130` 被中断。
Windows 上同样可用（`LinPlayer.exe`），输出需要重定向或经管道读取。

## 功能

业务能力（Emby 协议、网络、播放控制、弹幕、同步、下载、插件）集中在**各端共用的 Go 核心层**里，
编译成 `lpcore` 动态库；每端只写自己的界面。所以下表标 ⬜ 的并不是「没做」，而是**核心层已经有了、那一端的界面还没接**。

| 功能 | 说明 | Windows | Linux | 手机 / 平板 | TV |
|:--|:--|:--:|:--:|:--:|:--:|
| **mpv 播放内核** | 全格式；HDR / 杜比视界自动切软解；PGS/SUP 图形字幕 | ✅ | ✅ | ✅ | ✅ |
| **ExoPlayer 第二内核** | 安卓专有，可在设置里切换 | — | — | ✅ | ✅ |
| **画面增强** | Anime4K 官方六档（mpv 内核下） | ✅ | ✅ | ✅ | ✅ |
| **弹幕** | 弹弹play 等多来源，智能集数匹配，屏蔽词与显示设置 | ✅ | ✅ | ✅ | ✅ |
| **字幕** | Emby 内封 / 外挂字幕，轨道切换、延迟、样式；libass 完整特效 | ✅ | ✅ | ✅ | ✅ |
| **进度上报与跨服续播** | Emby 进度上报；同一部片在多台服务器间取最靠后的进度 | ✅ | ✅ | ✅ | ✅ |
| **排行榜** | 弹弹play 动漫榜 + TMDB 影视榜 | ✅ | ✅ | ✅ | ✅ |
| **追剧日历** | Trakt / Bangumi 放送表（手机端目前只有 Bangumi） | ✅ | ✅ | ✅ | ✅ |
| **下载** | 自建多线程 Range 分段下载，整季下载 | ✅ | ✅ | ✅ | ✅ |
| **多线程加载** | 本地预取代理，并发 Range 超前拉流喂给播放器 | ✅ | ✅ | ✅ | ✅ |
| **本机文件夹播放** | 选一个本地目录直接浏览播放 | ✅ | ✅ | ✅ | ✅ |
| **应用内更新** | 双渠道，按平台与架构自动挑包 | ✅ | ✅ | ✅ | ✅ |
| **插件** | QuickJS 脚本引擎，插件市场，逐插件授权与隔离 | ✅ | ✅ | ✅ | ⬜ |
| **Trakt / Bangumi 账号** | 登录、查看账号与放送表 | ⬜ | ⬜ | ⬜ | ✅ |
| **观看记录同步** | Trakt Scrobble / Bangumi 点格子 | ⬜ | ⬜ | ⬜ | ⬜ |
| **自定义网络代理** | | ⬜ | ⬜ | ⬜ | ✅ |
| **CF 优选 IP 测速** | 抽样 Cloudflare 边缘节点测速 | ✅ | ✅ | ⬜ | ⬜ |
| **批量添加服务器** | 粘贴多行配置一次性解析导入 | ✅ | ✅ | ⬜ | ⬜ |
| **扫码迁移配置** | 设备之间直传服务器配置（含凭据，不经云端） | ✅ | ✅ | ⬜ | ⬜ |
| **手机遥控** | 电视出二维码，手机扫码后在局域网网页里遥控、搜索、加服务器 | — | — | — | ✅ |
| **快捷键** | 播放页键盘操作与按键提示层 | ✅ | ✅ | — | — |
| **命令行** | `LinPlayer call <命令> '<JSON>'` | ✅ | ✅ | — | — |

<sub>✅ 已接入可用 · ⬜ 核心层已有，该端界面还没接 · — 该端不适用</sub>

> **不做的东西**（2026-09-04 定）：网盘（阿里 / 百度 / 115 / 189 / 139 / 夸克 / OpenList / 飞牛）、
> 局域网源（SMB / WebDAV / FTP）、Ani-RSS 全部下线，代码已删净。
> 资源站将来只以**插件**形式出现。本机文件夹播放保留 —— 它是播放器的基础能力。

## 界面预览

### 桌面端（Windows）

> 截图内容来自 [**UHD MEDIA**](https://www.uhdnow.com)。Linux 端界面与之相同。

<table>
  <tr>
    <td colspan="2"><img src="docs/images/screenshots/pc-player.jpg" width="100%" alt="播放页"><br><sub><b>播放页</b> —— 弹幕、双语字幕、画面增强都在这一层</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/images/screenshots/pc-home.jpg" width="100%" alt="首页"><br><sub><b>首页</b></sub></td>
    <td width="50%"><img src="docs/images/screenshots/pc-library.jpg" width="100%" alt="媒体库"><br><sub><b>媒体库</b></sub></td>
  </tr>
  <tr>
    <td><img src="docs/images/screenshots/pc-series-detail.jpg" width="100%" alt="剧集详情"><br><sub><b>剧集详情</b></sub></td>
    <td><img src="docs/images/screenshots/pc-movie-detail.jpg" width="100%" alt="电影详情"><br><sub><b>电影详情</b></sub></td>
  </tr>
  <tr>
    <td><img src="docs/images/screenshots/pc-episode-detail.jpg" width="100%" alt="集详情"><br><sub><b>集详情</b></sub></td>
    <td><img src="docs/images/screenshots/pc-add-server.jpg" width="100%" alt="添加服务器"><br><sub><b>添加服务器</b> —— 首次启动的登录闸口</sub></td>
  </tr>
</table>

### 平板（Android）

> 截图内容来自 [**稳健115**](https://shop.wenjian.de)。

<table>
  <tr>
    <td width="33%"><img src="docs/images/screenshots/tablet-home.jpg" width="100%" alt="首页"><br><sub><b>首页</b></sub></td>
    <td width="33%"><img src="docs/images/screenshots/tablet-series-detail.jpg" width="100%" alt="剧集详情"><br><sub><b>剧集详情</b></sub></td>
    <td width="33%"><img src="docs/images/screenshots/tablet-episode-detail.jpg" width="100%" alt="集详情"><br><sub><b>集详情</b></sub></td>
  </tr>
  <tr>
    <td><img src="docs/images/screenshots/tablet-player.jpg" width="100%" alt="播放页"><br><sub><b>播放页</b></sub></td>
    <td><img src="docs/images/screenshots/tablet-rankings.jpg" width="100%" alt="排行榜"><br><sub><b>排行榜</b></sub></td>
    <td><img src="docs/images/screenshots/tablet-calendar.jpg" width="100%" alt="追剧日历"><br><sub><b>追剧日历</b></sub></td>
  </tr>
</table>

### 手机（Android）

> 截图内容来自 [**ME MEDIA**](https://shop.mebimmer.de)。

<table>
  <tr>
    <td colspan="3"><img src="docs/images/screenshots/phone-player.jpg" width="100%" alt="播放页"><br><sub><b>播放页</b> —— 横屏 OSD，右侧是画面比例 / 版本与线路 / 音轨 / 弹幕 / 字幕样式</sub></td>
  </tr>
  <tr>
    <td width="33%"><img src="docs/images/screenshots/phone-home.jpg" width="100%" alt="首页"><br><sub><b>首页</b></sub></td>
    <td width="33%"><img src="docs/images/screenshots/phone-aggregate.jpg" width="100%" alt="聚合视界"><br><sub><b>聚合视界</b> —— 跨服务器的收藏 / 下载 / 排行榜 / 日历</sub></td>
    <td width="33%"><img src="docs/images/screenshots/phone-rankings.jpg" width="100%" alt="排行榜"><br><sub><b>排行榜</b></sub></td>
  </tr>
  <tr>
    <td><img src="docs/images/screenshots/phone-series-detail.jpg" width="100%" alt="剧集详情"><br><sub><b>剧集详情</b></sub></td>
    <td><img src="docs/images/screenshots/phone-calendar.jpg" width="100%" alt="追剧日历"><br><sub><b>追剧日历</b></sub></td>
    <td><img src="docs/images/screenshots/phone-settings.jpg" width="100%" alt="设置"><br><sub><b>设置</b></sub></td>
  </tr>
</table>

## 开发

仓库结构、本地构建、门禁与技术栈详见 **[开发文档](docs/DEVELOPMENT.md)**。简要：

```
core/              Go 核心层，编成 lpcore 动态库，经 C ABI 给各端调用
apps/windows/      C# + Avalonia 桌面端（Windows 与 Linux 共用）
apps/android/      Kotlin + Compose（手机与 TV 同一个工程）
bindings/          从 docs/go-migration/COMMANDS.md 生成的命令绑定
scripts/           构建、出包与门禁脚本（pack-win.sh / pack-linux.sh / pack-android.sh）
```

## 免责声明

### 关于内容与资源

- LinPlayer 是一款**纯本地播放器 / 第三方客户端**，自身**不提供、不存储、不托管、不分发任何影视资源**，也不内置任何内容源。
- 应用内展示与播放的所有媒体，均来自**用户自行添加的服务器（如 Emby）或用户自行配置的来源**，资源的来源、版权与合法性**由用户自行负责**。
- 请仅用于播放你**依法拥有或已获授权**的内容，并遵守你所在国家 / 地区的法律法规。因使用者不当使用而产生的任何纠纷、损失或法律责任，**由使用者自行承担**，与本项目及开发者无关。
- 本项目为**免费开源、非营利**软件，不以任何形式从内容传播中获利。如有版权方认为相关内容不妥，问题在于内容来源方，请联系对应的资源 / 服务器提供者。

### 关于遥测与隐私

- **发行版（Windows / Linux / Android / Android TV）带匿名崩溃上报（Sentry）**：程序崩溃时上报错误堆栈、操作系统与应用版本，以及一个随机生成的匿名安装标识（只用来统计活跃设备数）。
  出站前会把本机用户目录抹成 `~`、把链接里的 token / api_key 抹掉；不开性能追踪、不录屏。自己编译的版本不带上报地址，完全不上报。
- **崩溃后下次启动，会自动把崩溃现场和最近的日志发给开发者**；出错提示上的「反馈」、设置里的「发送日志给开发者」也走同一条路。日志离开本机前会抹掉服务器地址、账号、令牌和你的用户名。
- 我们**绝不采集任何可识别你个人身份的信息**：不采集账号、密码、Cookie、Token、服务器地址、媒体库内容、观看记录或 IP；**不录屏、不追踪行为轨迹**。
- 崩溃数据**绝不出售、共享或用于广告及任何商业用途**，按 Sentry 的保留策略（默认最长 90 天）自动删除。

## 许可证

[LICENSE](LICENSE)

## 致谢

感谢以下开源项目、媒体服务与内核，LinPlayer 站在它们的肩膀上：

### 播放内核

- [mpv](https://github.com/mpv-player/mpv) / libmpv — 全格式播放核心
- [shinchiro mpv-winbuild](https://github.com/shinchiro/mpv-winbuild-cmake) — Windows 完整版 libmpv 预编译（含 PGS/SUP 解码器）
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) — 安卓端可切换的第二播放内核
- [Anime4K](https://github.com/bloc97/Anime4K) — 动漫实时超分辨率 GLSL 着色器
- [mpv_PlayKit](https://github.com/hooke007/mpv_PlayKit) — 画质档位 shader 移植与文档
- [AMD FidelityFX (FSR / CAS)](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK) — 放大与锐化着色器
- [NVIDIA Image Scaling](https://github.com/NVIDIAGameWorks/NVIDIAImageScaling) — NVScaler / NVSharpen 着色器

### 界面与框架

- [Go](https://go.dev) — 各端共用的业务核心
- [.NET 10](https://dotnet.microsoft.com) / [Avalonia](https://avaloniaui.net) — Windows 与 Linux 桌面端
- [Kotlin](https://kotlinlang.org) / [Jetpack Compose](https://developer.android.com/compose) / Compose for TV — 安卓手机与 TV 端
- [Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons)（MIT）— Linux 端图标字体的字形来源

### 服务与数据源

- [Emby](https://emby.media/) — 媒体服务器
- [弹弹play (DanDanPlay)](https://www.dandanplay.com/) — 弹幕与动漫排行榜数据
- [TMDB](https://www.themoviedb.org/) — 影视排行榜数据
- [Bangumi (bgm.tv)](https://bgm.tv/) — 番剧放送表与收藏
- [Trakt](https://trakt.tv/) — 影视放送表与观看记录

### Emby 服

感谢以下 Emby 服为 LinPlayer 提供界面演示与长期支持：

- [UHD MEDIA](https://www.uhdnow.com) — 桌面端截图内容来源
- [稳健115](https://shop.wenjian.de) — 平板截图内容来源
- [ME MEDIA](https://shop.mebimmer.de) — 手机端截图内容来源

### 网络与工具

- [CloudflareSpeedTest](https://github.com/XIU2/CloudflareSpeedTest) — CF 优选 IP 的灵感来自 XIU2 的这个项目
- [QuickJS](https://bellard.org/quickjs/) — 插件脚本引擎

> 数据来源 TMDB 与弹弹play 的内容版权归各自所有；本项目仅作聚合展示，不存储或分发受版权保护的媒体。

## Star History

<!-- 自建实时图(oauth-proxy/functions/star/history.svg.js)。
     不用 star-history.com:它没命中缓存就现场去 GitHub 拉,超过自己 10 秒上限就回 500。 -->
<a href="https://github.com/zzzwannasleep/LinPlayer/stargazers">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://291277.xyz/star/history.svg?theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://291277.xyz/star/history.svg" />
   <img alt="Star History Chart" src="https://291277.xyz/star/history.svg" width="100%" />
 </picture>
</a>

## 项目活跃度

![Alt](https://repobeats.axiom.co/api/embed/4858243f2148dfeaa4e82f119fa918f3ec581a11.svg "Repobeats analytics image")

## 赞助

感谢在 [爱发电](https://afdian.com/a/zzzwannasleep) 支持 LinPlayer 的各位（名单实时更新）：

<p align="center">
  <a href="https://afdian.com/a/zzzwannasleep"><img src="https://291277.xyz/afdian/sponsors.svg" alt="爱发电赞助者"></a>
</p>

## 加入频道

Telegram 频道 [**@MikudesuChannels**](https://t.me/MikudesuChannels) —— 版本发布、更新预告与讨论。
