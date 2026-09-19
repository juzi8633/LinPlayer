# LinPlayer

<p align="center">
  <a href="https://github.com/zzzwannasleep/LinPlayer/stargazers"><img src="https://img.shields.io/github/stars/zzzwannasleep/LinPlayer?style=flat&logo=github&label=Stars" alt="Stars"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/github/v/release/zzzwannasleep/LinPlayer?label=stable&color=blue" alt="Stable"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/github/v/release/zzzwannasleep/LinPlayer?include_prereleases&label=pre-release&color=orange" alt="Pre-release"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/github/downloads/zzzwannasleep/LinPlayer/total?label=downloads&color=green&logo=github" alt="Downloads"></a>
  <img src="https://img.shields.io/endpoint?url=https://291277.xyz/sentry/users&label=Active%20User" alt="Active User">
  <a href="https://github.com/zzzwannasleep/LinPlayer/blob/main/LICENSE"><img src="https://img.shields.io/github/license/zzzwannasleep/LinPlayer" alt="License"></a>
  <img src="https://img.shields.io/badge/Go-1.27-00ADD8?logo=go&logoColor=white" alt="Go">
  <img src="https://img.shields.io/badge/C%23-.NET%2010-512BD4?logo=dotnet&logoColor=white" alt="C#">
  <img src="https://img.shields.io/badge/Avalonia-11-8B44AC" alt="Avalonia">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <a href="https://github.com/zzzwannasleep/LinPlayer/actions"><img src="https://img.shields.io/github/actions/workflow/status/zzzwannasleep/LinPlayer/build.yml?branch=main&label=build&logo=github" alt="Build"></a>
  <a href="https://t.me/MikudesuChannels"><img src="https://img.shields.io/badge/Telegram-MikudesuChannels-26A5E4?logo=telegram&logoColor=white" alt="Telegram"></a>
</p>

<p align="center">
  <a href="../README.md">简体中文</a> ·
  <b>English</b> ·
  <a href="README.ja.md">日本語</a>
</p>

**LinPlayer** is a third-party Emby client: one shared Go core plus a native UI on each platform.
It supports **Windows / Linux / Android phone & tablet / Android TV**, and all four ship release builds. Apple platforms are not planned.

## Platform Status

| Platform | UI | Status |
|:--|:--|:--|
| **Windows** | C# + Avalonia | Stable. Portable zip; all data lives in `userdata/` next to the executable |
| **Linux** | **Same code** as Windows | Packaging and a CLI smoke test are verified in CI; **not yet used long-term on a real desktop**, please report issues. Playback uses the system libmpv |
| **Android phone / tablet** | Kotlin + Jetpack Compose | Usable. Two engines (mpv / ExoPlayer) |
| **Android TV** | Kotlin + Compose for TV | Every page is wired to the real core, with remote-control focus navigation; **not yet accepted on a real TV**. 32-bit build aimed at TV boxes |

> Since 2026-09-04 the project runs on a **Go core + native UI per platform** instead of a **Rust core + React/Tauri**.
> The old stack was removed from the repo; tag [`rust-final`](https://github.com/zzzwannasleep/LinPlayer/tree/rust-final) is its last state.

## Download

Grab it from [**Releases**](https://github.com/zzzwannasleep/LinPlayer/releases):

| Platform | File | Notes |
|:--|:--|:--|
| Windows | `LinPlayer-Windows-v*.zip` | Unzip and run; nothing written to the registry. Upgrade by overwriting the same folder — accounts and settings in `userdata/` survive |
| Linux | `LinPlayer-Linux-v*.zip` | x86_64. Unzip and run `./LinPlayer`. Needs libmpv installed on the system (see below) |
| Android phone / tablet | `app-arm64-v8a-release.apk` | arm64 |
| Android TV | `app-tv-armeabi-v7a-release.apk` | armeabi-v7a (32-bit). Check that your TV box supports this ABI first |

Two channels: **stable** and **pre-release**. Once installed, *Settings → Check for updates* downloads and applies updates in place; each platform picks its own package.

### Linux needs libmpv

The Linux package does not bundle libmpv. At runtime it looks for the system copy in the order `libmpv.so.2 → libmpv.so.1 → libmpv.so`:

| Distro | Install |
|:--|:--|
| Debian / Ubuntu 24.04+ | `sudo apt install libmpv2` |
| Ubuntu 22.04 | `sudo apt install libmpv1` |
| Fedora | `sudo dnf install mpv-libs` |
| Arch | `sudo pacman -S mpv` |

Without it the UI still opens; starting playback tells you libmpv is missing and shows the commands above.

### Command line

The executable has a windowless command-line entry that calls core commands directly and prints the result as JSON — handy for scripts or over ssh.
It shares the same `userdata/` as the UI, so there is no need to log in again:

```bash
./LinPlayer commands                                # list every command
./LinPlayer call system.capabilities                # call one command
./LinPlayer call emby.listResume '{"limit": 5}'     # JSON arguments; emby.* commands get the current session added automatically
echo '{"limit": 5}' | ./LinPlayer call emby.listResume -   # read arguments from stdin
./LinPlayer version
```

Exit codes: `0` success, `1` command failed (error on stderr), `2` usage error, `130` interrupted.
It also works on Windows (`LinPlayer.exe`); read the output through a redirect or a pipe.

## Features

Business logic (Emby protocol, networking, playback control, danmaku, downloads) lives in a **single Go core shared by every platform**,
built as the `lpcore` shared library; each platform only writes its own UI. So a ⬜ below does not mean "not built" — it means **the core has it, but that platform's UI is not wired up yet**.

| Feature | Notes | Windows | Linux | Phone / tablet | TV |
|:--|:--|:--:|:--:|:--:|:--:|
| **mpv player core** | All formats; HDR / Dolby Vision with automatic software-decode fallback; PGS/SUP graphic subtitles | ✅ | ✅ | ✅ | ✅ |
| **ExoPlayer second engine** | Android only, switchable in settings | — | — | ✅ | ✅ |
| **Image enhancement** | The six official Anime4K presets (mpv engine) | ✅ | ✅ | ✅ | ✅ |
| **Danmaku** | DanDanPlay and other sources, smart episode matching, block words and display settings | ✅ | ✅ | ✅ | ✅ |
| **Subtitles** | Embedded / external Emby subtitles; track switching, delay, styling; full libass effects | ✅ | ✅ | ✅ | ✅ |
| **Progress reporting & cross-server resume** | Emby progress reporting; the furthest position across servers wins | ✅ | ✅ | ✅ | ✅ |
| **Downloads** | Custom multi-threaded ranged downloads, whole-season download | ✅ | ✅ | ✅ | ✅ |
| **Multi-threaded loading** | Local prefetch proxy feeding the player with concurrent ranged reads | ✅ | ✅ | ✅ | ✅ |
| **Local folder playback** | Pick a local directory and browse / play it | ✅ | ✅ | ✅ | ✅ |
| **In-app updates** | Dual channel, picks the right package per platform and ABI | ✅ | ✅ | ✅ | ✅ |
| **Custom network proxy** | | ⬜ | ⬜ | ⬜ | ✅ |
| **Cloudflare best-IP speed test** | Samples Cloudflare edge nodes and measures them | ✅ | ✅ | ⬜ | ⬜ |
| **Bulk server import** | Paste multi-line configs and import them in one pass | ✅ | ✅ | ⬜ | ⬜ |
| **QR config migration** | Transfer server configs between devices (credentials included, no cloud) | ✅ | ✅ | ⬜ | ⬜ |
| **Phone remote** | The TV shows a QR code; scan it to control, search and add servers from a LAN web page | — | — | — | ✅ |
| **Keyboard shortcuts** | Player keybindings with an on-screen cheat sheet | ✅ | ✅ | — | — |
| **Command line** | `LinPlayer call <command> '<JSON>'` | ✅ | ✅ | — | — |

<sub>✅ wired and usable · ⬜ in the core, UI not wired on this platform · — not applicable</sub>

> **Out of scope** (decided 2026-09-04): cloud drives (Aliyun, Baidu, 115, 189, 139, Quark, OpenList, Feiniu),
> LAN sources (SMB / WebDAV / FTP) and Ani-RSS are all dropped and their code removed.
> Video-resource sites will only ever come back as **plugins** — the plugin system is being rebuilt from scratch, see [`plugin-system/SPEC.md`](plugin-system/SPEC.md) (Chinese). Local folder playback stays — it is table stakes for a player.

## Screenshots

### Desktop (Windows)

> Content shown courtesy of [**UHD MEDIA**](https://www.uhdnow.com). The Linux UI is identical.

<table>
  <tr>
    <td colspan="2"><img src="images/screenshots/pc-player.jpg" width="100%" alt="Player"><br><sub><b>Player</b> — danmaku, dual subtitles and image enhancement all live in this layer</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="images/screenshots/pc-home.jpg" width="100%" alt="Home"><br><sub><b>Home</b></sub></td>
    <td width="50%"><img src="images/screenshots/pc-library.jpg" width="100%" alt="Library"><br><sub><b>Library</b></sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/pc-series-detail.jpg" width="100%" alt="Series detail"><br><sub><b>Series Detail</b></sub></td>
    <td><img src="images/screenshots/pc-movie-detail.jpg" width="100%" alt="Movie detail"><br><sub><b>Movie Detail</b></sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/pc-episode-detail.jpg" width="100%" alt="Episode detail"><br><sub><b>Episode Detail</b></sub></td>
    <td><img src="images/screenshots/pc-add-server.jpg" width="100%" alt="Add server"><br><sub><b>Add Server</b> — the first-run login gate</sub></td>
  </tr>
</table>

### Tablet (Android)

> Content shown courtesy of [**稳健115**](https://shop.wenjian.de).

<table>
  <tr>
    <td width="33%"><img src="images/screenshots/tablet-home.jpg" width="100%" alt="Home"><br><sub><b>Home</b></sub></td>
    <td width="33%"><img src="images/screenshots/tablet-series-detail.jpg" width="100%" alt="Series detail"><br><sub><b>Series Detail</b></sub></td>
    <td width="33%"><img src="images/screenshots/tablet-episode-detail.jpg" width="100%" alt="Episode detail"><br><sub><b>Episode Detail</b></sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/tablet-player.jpg" width="100%" alt="Player"><br><sub><b>Player</b></sub></td>
  </tr>
</table>

### Phone (Android)

> Content shown courtesy of [**ME MEDIA**](https://shop.mebimmer.de).

<table>
  <tr>
    <td colspan="3"><img src="images/screenshots/phone-player.jpg" width="100%" alt="Player"><br><sub><b>Player</b> — landscape OSD; the side panel holds aspect ratio, version &amp; line, audio tracks, danmaku and subtitle styling</sub></td>
  </tr>
  <tr>
    <td width="33%"><img src="images/screenshots/phone-home.jpg" width="100%" alt="Home"><br><sub><b>Home</b></sub></td>
    <td width="33%"><img src="images/screenshots/phone-aggregate.jpg" width="100%" alt="Aggregate"><br><sub><b>Aggregate View</b> — favorites and downloads across servers</sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/phone-series-detail.jpg" width="100%" alt="Series detail"><br><sub><b>Series Detail</b></sub></td>
    <td><img src="images/screenshots/phone-settings.jpg" width="100%" alt="Settings"><br><sub><b>Settings</b></sub></td>
  </tr>
</table>

## Development

Repository layout, local builds, gates and the tech stack — see the **[development docs](DEVELOPMENT.md)** (Chinese). In short:

```
core/              Go core, built as the lpcore shared library and called through a C ABI
apps/windows/      C# + Avalonia desktop app (shared by Windows and Linux)
apps/android/      Kotlin + Compose (phone and TV in one project)
bindings/          command bindings generated from docs/go-migration/COMMANDS.md
scripts/           build, packaging and gate scripts (pack-win.sh / pack-linux.sh / pack-android.sh)
```

## Disclaimer

### About Content & Media

- LinPlayer is a **purely local player / third-party client**. It **does not provide, store, host, or distribute any video content**, and ships with no built-in content sources.
- All media shown and played inside the app comes from **servers the user adds themselves (e.g. Emby) or sources the user configures themselves**. The origin, copyright, and legality of that content **are solely the user's responsibility**.
- Please only play content you **lawfully own or are authorized to access**, and comply with the laws and regulations of your country/region. Any dispute, loss, or legal liability arising from improper use **is borne solely by the user** and is unrelated to this project or its developers.
- This project is **free, open-source, and non-profit**; it makes no money from content distribution in any form. If a rights holder finds certain content inappropriate, the issue lies with the content's source — please contact the corresponding resource/server provider.

### About Telemetry & Privacy

- **Releases (Windows / Linux / Android / Android TV) include anonymous crash reporting (Sentry)**: on a crash they send the stack trace,
  OS and app version, and a randomly generated anonymous install ID (used only to count active devices). Your home-folder path is replaced
  with `~` and tokens / api_keys in URLs are stripped before anything leaves the device; no performance tracing, no screen recording.
  Builds you compile yourself carry no reporting address and report nothing.
- **After a crash, the next launch automatically sends the crash details and recent logs to the developer**; the "反馈" button on error
  notices and "发送日志给开发者" in Settings use the same path. Server addresses, accounts, tokens and your username are stripped first.
- We **never collect any information that can identify you personally**: no accounts, passwords, cookies,
  tokens, server addresses, library contents, watch history, or IP addresses. **No screen recording, no behavior tracking.**
- Crash data **is never sold, shared, or used for advertising or any commercial purpose**, and is deleted per
  Sentry's retention policy (up to 90 days by default).

## License

[LICENSE](../LICENSE)

## Acknowledgements

LinPlayer stands on the shoulders of these open-source projects, media services and cores:

### Player Cores

- [mpv](https://github.com/mpv-player/mpv) / libmpv — full-format playback core
- [shinchiro mpv-winbuild](https://github.com/shinchiro/mpv-winbuild-cmake) — full-featured libmpv prebuilds for Windows (with PGS/SUP decoders)
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) — the switchable second engine on Android
- [Anime4K](https://github.com/bloc97/Anime4K) — real-time anime upscaling GLSL shaders
- [mpv_PlayKit](https://github.com/hooke007/mpv_PlayKit) — quality-preset shader ports and documentation
- [AMD FidelityFX (FSR / CAS)](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK) — upscaling and sharpening shaders
- [NVIDIA Image Scaling](https://github.com/NVIDIAGameWorks/NVIDIAImageScaling) — NVScaler / NVSharpen shaders

### UI & Framework

- [Go](https://go.dev) — the business core shared by every platform
- [.NET 10](https://dotnet.microsoft.com) / [Avalonia](https://avaloniaui.net) — the Windows and Linux desktop app
- [Kotlin](https://kotlinlang.org) / [Jetpack Compose](https://developer.android.com/compose) / Compose for TV — the Android phone and TV apps
- [Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons) (MIT) — glyph source for the Linux icon font

### Services & Data Sources

- [Emby](https://emby.media/) — media server
- [DanDanPlay](https://www.dandanplay.com/) — danmaku data
- [Bangumi (bgm.tv)](https://bgm.tv/) — anime titles (used for danmaku matching)

### Emby Servers

Thanks to the following Emby servers for providing UI demos and long-term support:

- [UHD MEDIA](https://www.uhdnow.com) — desktop screenshots content
- [稳健115](https://shop.wenjian.de) — tablet screenshots content
- [ME MEDIA](https://shop.mebimmer.de) — phone screenshots content

### Network & Tools

- [CloudflareSpeedTest](https://github.com/XIU2/CloudflareSpeedTest) — the Cloudflare best-IP feature was inspired by XIU2's project

> Content from DanDanPlay remains the copyright of its respective owners; this project only aggregates and displays it, and does not store or distribute copyrighted media.

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

## Project Activity

![Alt](https://repobeats.axiom.co/api/embed/4858243f2148dfeaa4e82f119fa918f3ec581a11.svg "Repobeats analytics image")

## Sponsors

Thanks to everyone supporting LinPlayer on [Afdian](https://afdian.com/a/zzzwannasleep) (list updated in real time):

<p align="center">
  <a href="https://afdian.com/a/zzzwannasleep"><img src="https://291277.xyz/afdian/sponsors.svg" alt="Afdian sponsors"></a>
</p>

## Join the Channel

Telegram channel [**@MikudesuChannels**](https://t.me/MikudesuChannels) — releases, previews and discussion.
