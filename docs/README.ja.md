# LinPlayer

<p align="center">
  <a href="https://github.com/zzzwannasleep/LinPlayer/stargazers"><img src="https://img.shields.io/github/stars/zzzwannasleep/LinPlayer?style=flat&logo=github&label=Stars" alt="Stars"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/github/v/release/zzzwannasleep/LinPlayer?label=stable&color=blue" alt="Stable"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/github/v/release/zzzwannasleep/LinPlayer?include_prereleases&label=pre-release&color=orange" alt="Pre-release"></a>
  <a href="https://github.com/zzzwannasleep/LinPlayer/releases"><img src="https://img.shields.io/github/downloads/zzzwannasleep/LinPlayer/total?label=downloads&color=green&logo=github" alt="Downloads"></a>
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
  <a href="README.en.md">English</a> ·
  <b>日本語</b>
</p>

**LinPlayer** は Emby サードパーティクライアントです。共通の Go コアに、各プラットフォームのネイティブ UI を組み合わせています。
**Windows / Linux / Android スマートフォン・タブレット / Android TV** に対応し、4 つすべてでリリースビルドを配布しています。Apple 系は対応予定なしです。

## 各プラットフォームの状況

| プラットフォーム | UI | 状況 |
|:--|:--|:--|
| **Windows** | C# + Avalonia | 安定版。インストール不要の ZIP で、データはすべて実行ファイルと同じ階層の `userdata/` に入ります |
| **Linux** | Windows と**同じコード** | パッケージ作成とコマンドラインのスモークテストは CI で検証済み。**実際のデスクトップでの長期利用はまだ**なので、問題があれば報告してください。再生にはシステムの libmpv を使います |
| **Android スマホ / タブレット** | Kotlin + Jetpack Compose | 利用可能。2 つの再生コア（mpv / ExoPlayer） |
| **Android TV** | Kotlin + Compose for TV | 全画面が実際のコアに接続済みで、リモコンのフォーカス移動に対応。**実機の TV での検収はまだ**です。TV ボックス向けの 32 ビット版 |

> 2026-09-04 以降、本プロジェクトは **Rust コア + React/Tauri** から **Go コア + 各プラットフォームのネイティブ UI** に移行しました。
> 旧スタックはリポジトリから削除済みで、タグ [`rust-final`](https://github.com/zzzwannasleep/LinPlayer/tree/rust-final) がその最終状態です。

## ダウンロード

[**Releases**](https://github.com/zzzwannasleep/LinPlayer/releases) から入手できます：

| プラットフォーム | ファイル | 説明 |
|:--|:--|:--|
| Windows | `LinPlayer-Windows-v*.zip` | 解凍してそのまま実行、レジストリにも書き込みません。更新は同じフォルダーに上書きするだけで、`userdata/` のアカウントと設定は消えません |
| Linux | `LinPlayer-Linux-v*.zip` | x86_64。解凍して `./LinPlayer` を実行。システムに libmpv が必要です（下記参照） |
| Android スマホ / タブレット | `app-arm64-v8a-release.apk` | arm64 |
| Android TV | `app-tv-armeabi-v7a-release.apk` | armeabi-v7a（32 ビット）。TV ボックスがこのアーキテクチャに対応しているか事前に確認してください |

チャンネルは **stable** と **pre-release** の 2 つ。インストール後は「設定 → 更新を確認」からそのままダウンロードして上書きでき、各プラットフォームが自分用のパッケージを自動で選びます。

### Linux には libmpv が必要

Linux 版は libmpv を同梱していません。実行時に `libmpv.so.2 → libmpv.so.1 → libmpv.so` の順でシステムのものを探します：

| ディストリビューション | インストール |
|:--|:--|
| Debian / Ubuntu 24.04+ | `sudo apt install libmpv2` |
| Ubuntu 22.04 | `sudo apt install libmpv1` |
| Fedora | `sudo dnf install mpv-libs` |
| Arch | `sudo pacman -S mpv` |

入っていなくても UI は起動します。再生を始めると libmpv がないことと、上記のインストールコマンドが表示されます。

### コマンドライン

実行ファイルにはウィンドウを開かないコマンドライン入口があり、コアのコマンドを直接呼んで結果を JSON で標準出力に書き出します。スクリプトや ssh 経由の操作に便利です。
UI と同じ `userdata/` を使うので、再ログインは不要です：

```bash
./LinPlayer commands                                # 全コマンドを一覧表示
./LinPlayer call system.capabilities                # コマンドを 1 つ呼ぶ
./LinPlayer call emby.listResume '{"limit": 5}'     # JSON 引数付き。emby.* には現在のログインセッションが自動で付きます
echo '{"limit": 5}' | ./LinPlayer call emby.listResume -   # 引数を標準入力から読む
./LinPlayer version
```

終了コード：`0` 成功、`1` コマンド失敗（エラーは標準エラー出力へ）、`2` 使い方の誤り、`130` 中断。
Windows でも使えます（`LinPlayer.exe`）。出力はリダイレクトかパイプで読み取ってください。

## 機能

ビジネスロジック（Emby プロトコル、ネットワーク、再生制御、弾幕、同期、ダウンロード、プラグイン）は**全プラットフォーム共通の Go コア**にまとまっており、
`lpcore` 共有ライブラリとしてビルドされます。各プラットフォームは自分の UI だけを書きます。そのため下表の ⬜ は「未着手」ではなく、**コアにはあるが、そのプラットフォームの UI がまだつながっていない**という意味です。

| 機能 | 説明 | Windows | Linux | スマホ / タブレット | TV |
|:--|:--|:--:|:--:|:--:|:--:|
| **mpv 再生コア** | 全フォーマット；HDR / Dolby Vision はソフトデコードへ自動切替；PGS/SUP 画像字幕 | ✅ | ✅ | ✅ | ✅ |
| **ExoPlayer（第 2 のコア）** | Android 限定、設定で切替 | — | — | ✅ | ✅ |
| **画質強化** | Anime4K 公式の 6 段階（mpv コア使用時） | ✅ | ✅ | ✅ | ✅ |
| **弾幕** | DanDanPlay ほか複数ソース、話数の自動マッチング、NG ワードと表示設定 | ✅ | ✅ | ✅ | ✅ |
| **字幕** | Emby の内蔵 / 外部字幕、トラック切替、遅延、スタイル；libass の全効果 | ✅ | ✅ | ✅ | ✅ |
| **進捗報告とサーバー間レジューム** | Emby への進捗報告；複数サーバーのうち最も進んだ位置から再開 | ✅ | ✅ | ✅ | ✅ |
| **ランキング** | DanDanPlay アニメランキング + TMDB 映画・ドラマランキング | ✅ | ✅ | ✅ | ✅ |
| **放送カレンダー** | Trakt / Bangumi の放送スケジュール（スマホは現状 Bangumi のみ） | ✅ | ✅ | ✅ | ✅ |
| **ダウンロード** | 自前のマルチスレッド Range 分割ダウンロード、シーズン一括 | ✅ | ✅ | ✅ | ✅ |
| **マルチスレッド読み込み** | ローカル先読みプロキシ。並列 Range 要求で先回りしてプレーヤーへ供給 | ✅ | ✅ | ✅ | ✅ |
| **ローカルフォルダー再生** | ローカルのディレクトリを選んでそのまま閲覧・再生 | ✅ | ✅ | ✅ | ✅ |
| **アプリ内更新** | 2 チャンネル、プラットフォームとアーキテクチャに合わせて自動選択 | ✅ | ✅ | ✅ | ✅ |
| **プラグイン** | QuickJS エンジン、プラグインマーケット、プラグインごとの権限と隔離 | ✅ | ✅ | ✅ | ⬜ |
| **Trakt / Bangumi アカウント** | ログイン、アカウントと放送スケジュールの表示 | ⬜ | ⬜ | ⬜ | ✅ |
| **視聴履歴の同期** | Trakt Scrobble / Bangumi の話数チェック | ⬜ | ⬜ | ⬜ | ⬜ |
| **カスタムネットワークプロキシ** | | ⬜ | ⬜ | ⬜ | ✅ |
| **Cloudflare 最速 IP 測定** | Cloudflare のエッジノードをサンプリングして速度測定 | ✅ | ✅ | ⬜ | ⬜ |
| **サーバー一括追加** | 複数行の設定を貼り付けて一括で解析・取り込み | ✅ | ✅ | ⬜ | ⬜ |
| **QR コードで設定移行** | 端末間でサーバー設定を直接転送（認証情報込み、クラウドを経由しない） | ✅ | ✅ | ⬜ | ⬜ |
| **スマホリモコン** | TV に表示された QR コードをスマホで読み取り、LAN 内の Web ページから操作・検索・サーバー追加 | — | — | — | ✅ |
| **キーボードショートカット** | 再生画面のキー操作とキー一覧の表示 | ✅ | ✅ | — | — |
| **コマンドライン** | `LinPlayer call <コマンド> '<JSON>'` | ✅ | ✅ | — | — |

<sub>✅ 利用可能 · ⬜ コアにはあるが、このプラットフォームの UI は未接続 · — 対象外</sub>

> **対象外にしたもの**（2026-09-04 決定）：クラウドストレージ（Aliyun / Baidu / 115 / 189 / 139 / Quark / OpenList / 飛牛）、
> LAN ソース（SMB / WebDAV / FTP）、Ani-RSS はすべて取りやめ、コードも削除済みです。
> 動画リソースサイトは今後**プラグインの形でのみ**提供されます。ローカルフォルダー再生は残します —— プレーヤーの基本機能だからです。

## スクリーンショット

### デスクトップ（Windows）

> 表示内容は [**UHD MEDIA**](https://www.uhdnow.com) によるものです。Linux 版の UI も同じです。

<table>
  <tr>
    <td colspan="2"><img src="images/screenshots/pc-player.jpg" width="100%" alt="プレーヤー"><br><sub><b>プレーヤー</b> —— 弾幕・二言語字幕・画質強化はすべてこの層にあります</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="images/screenshots/pc-home.jpg" width="100%" alt="ホーム"><br><sub><b>ホーム</b></sub></td>
    <td width="50%"><img src="images/screenshots/pc-library.jpg" width="100%" alt="ライブラリ"><br><sub><b>ライブラリ</b></sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/pc-series-detail.jpg" width="100%" alt="シリーズ詳細"><br><sub><b>シリーズ詳細</b></sub></td>
    <td><img src="images/screenshots/pc-movie-detail.jpg" width="100%" alt="映画詳細"><br><sub><b>映画詳細</b></sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/pc-episode-detail.jpg" width="100%" alt="エピソード詳細"><br><sub><b>エピソード詳細</b></sub></td>
    <td><img src="images/screenshots/pc-add-server.jpg" width="100%" alt="サーバー追加"><br><sub><b>サーバー追加</b> —— 初回起動時のログイン画面</sub></td>
  </tr>
</table>

### タブレット（Android）

> 表示内容は [**稳健115**](https://shop.wenjian.de) によるものです。

<table>
  <tr>
    <td width="33%"><img src="images/screenshots/tablet-home.jpg" width="100%" alt="ホーム"><br><sub><b>ホーム</b></sub></td>
    <td width="33%"><img src="images/screenshots/tablet-series-detail.jpg" width="100%" alt="シリーズ詳細"><br><sub><b>シリーズ詳細</b></sub></td>
    <td width="33%"><img src="images/screenshots/tablet-episode-detail.jpg" width="100%" alt="エピソード詳細"><br><sub><b>エピソード詳細</b></sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/tablet-player.jpg" width="100%" alt="プレーヤー"><br><sub><b>プレーヤー</b></sub></td>
    <td><img src="images/screenshots/tablet-rankings.jpg" width="100%" alt="ランキング"><br><sub><b>ランキング</b></sub></td>
    <td><img src="images/screenshots/tablet-calendar.jpg" width="100%" alt="放送カレンダー"><br><sub><b>放送カレンダー</b></sub></td>
  </tr>
</table>

### スマートフォン（Android）

> 表示内容は [**ME MEDIA**](https://shop.mebimmer.de) によるものです。

<table>
  <tr>
    <td colspan="3"><img src="images/screenshots/phone-player.jpg" width="100%" alt="プレーヤー"><br><sub><b>プレーヤー</b> —— 横画面 OSD。右のパネルは画面比率 / バージョンと回線 / 音声トラック / 弾幕 / 字幕スタイル</sub></td>
  </tr>
  <tr>
    <td width="33%"><img src="images/screenshots/phone-home.jpg" width="100%" alt="ホーム"><br><sub><b>ホーム</b></sub></td>
    <td width="33%"><img src="images/screenshots/phone-aggregate.jpg" width="100%" alt="集約ビュー"><br><sub><b>集約ビュー</b> —— サーバーをまたぐお気に入り / ダウンロード / ランキング / カレンダー</sub></td>
    <td width="33%"><img src="images/screenshots/phone-rankings.jpg" width="100%" alt="ランキング"><br><sub><b>ランキング</b></sub></td>
  </tr>
  <tr>
    <td><img src="images/screenshots/phone-series-detail.jpg" width="100%" alt="シリーズ詳細"><br><sub><b>シリーズ詳細</b></sub></td>
    <td><img src="images/screenshots/phone-calendar.jpg" width="100%" alt="放送カレンダー"><br><sub><b>放送カレンダー</b></sub></td>
    <td><img src="images/screenshots/phone-settings.jpg" width="100%" alt="設定"><br><sub><b>設定</b></sub></td>
  </tr>
</table>

## 開発

リポジトリ構成、ローカルビルド、ゲート、技術スタックは **[開発ドキュメント](DEVELOPMENT.md)**（中国語）を参照してください。概要：

```
core/              Go コア。lpcore 共有ライブラリとしてビルドし、C ABI 経由で各プラットフォームから呼び出す
apps/windows/      C# + Avalonia のデスクトップ版（Windows と Linux で共用）
apps/android/      Kotlin + Compose（スマホと TV は同じプロジェクト）
bindings/          docs/go-migration/COMMANDS.md から生成したコマンドバインディング
scripts/           ビルド・パッケージ作成・ゲートのスクリプト（pack-win.sh / pack-linux.sh / pack-android.sh）
```

## 免責事項

### コンテンツ・リソースについて

- LinPlayer は**純粋なローカルプレーヤー / サードパーティクライアント**であり、それ自体は**いかなる映像リソースも提供・保存・ホスト・配布しません**。コンテンツソースも内蔵していません。
- アプリ内で表示・再生されるすべてのメディアは、**ユーザー自身が追加したサーバー（Emby など）またはユーザー自身が設定した提供元**に由来し、その出所・著作権・適法性は**すべてユーザー自身の責任**です。
- **合法的に所有している、または利用を許諾されている**コンテンツのみを再生し、お住まいの国・地域の法令を遵守してください。利用者の不適切な使用に起因するいかなる紛争・損失・法的責任も**利用者自身が負う**ものとし、本プロジェクトおよび開発者とは一切関係ありません。
- 本プロジェクトは**無料・オープンソース・非営利**のソフトウェアであり、コンテンツの伝播からいかなる形でも利益を得ません。権利者の方がコンテンツを不適切とお考えの場合、問題は提供元にありますので、該当するリソース／サーバーの提供者へお問い合わせください。

### テレメトリとプライバシーについて

- **現行のすべての配布版（Windows / Linux / Android / Android TV）にはテレメトリもクラッシュ報告も一切含まれていません。**
  組み込まれていた Sentry は 2026-09-04 の Rust/Tauri スタック削除と同時に取り除かれ、`git grep -i sentry -- core/ apps/ bindings/` は何もヒットしません。
- 私たちは**個人を特定できる情報を一切収集しません**：アカウント、パスワード、Cookie、トークン、
  サーバーアドレス、ライブラリの内容、視聴履歴、IP アドレスのいずれも収集せず、**画面録画も行動追跡も行いません**。
- 将来的に匿名のクラッシュ報告を再導入する場合は、収集範囲を本節に明記したうえで、
  **販売・共有したり、広告その他いかなる商業目的にも使用しません**。

## ライセンス

[LICENSE](../LICENSE)

## 謝辞

LinPlayer は以下のオープンソースプロジェクト、メディアサービス、コアの肩の上に立っています：

### 再生コア

- [mpv](https://github.com/mpv-player/mpv) / libmpv — 全フォーマット再生コア
- [shinchiro mpv-winbuild](https://github.com/shinchiro/mpv-winbuild-cmake) — Windows 向けフル機能 libmpv プリビルド（PGS/SUP デコーダー込み）
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) — Android で切り替えられる第 2 の再生コア
- [Anime4K](https://github.com/bloc97/Anime4K) — アニメ向けリアルタイム超解像 GLSL シェーダー
- [mpv_PlayKit](https://github.com/hooke007/mpv_PlayKit) — 画質プリセットシェーダーの移植とドキュメント
- [AMD FidelityFX (FSR / CAS)](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK) — アップスケールとシャープ化シェーダー
- [NVIDIA Image Scaling](https://github.com/NVIDIAGameWorks/NVIDIAImageScaling) — NVScaler / NVSharpen シェーダー

### UI とフレームワーク

- [Go](https://go.dev) — 全プラットフォーム共通のビジネスコア
- [.NET 10](https://dotnet.microsoft.com) / [Avalonia](https://avaloniaui.net) — Windows と Linux のデスクトップ版
- [Kotlin](https://kotlinlang.org) / [Jetpack Compose](https://developer.android.com/compose) / Compose for TV — Android スマホ版と TV 版
- [Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons)（MIT）— Linux 版アイコンフォントのグリフ提供元

### サービスとデータソース

- [Emby](https://emby.media/) — メディアサーバー
- [DanDanPlay](https://www.dandanplay.com/) — 弾幕とアニメランキングデータ
- [TMDB](https://www.themoviedb.org/) — 映画・ドラマランキングデータ
- [Bangumi (bgm.tv)](https://bgm.tv/) — アニメの放送スケジュールとコレクション
- [Trakt](https://trakt.tv/) — 映画・ドラマの放送スケジュールと視聴履歴

### Emby サーバー

UI デモと長期的なサポートを提供いただいた以下の Emby サーバーに感謝します：

- [UHD MEDIA](https://www.uhdnow.com) — デスクトップのスクリーンショット提供
- [稳健115](https://shop.wenjian.de) — タブレットのスクリーンショット提供
- [ME MEDIA](https://shop.mebimmer.de) — スマートフォンのスクリーンショット提供

### ネットワークとツール

- [CloudflareSpeedTest](https://github.com/XIU2/CloudflareSpeedTest) — Cloudflare 最速 IP 機能は XIU2 氏のこのプロジェクトに着想を得ています
- [QuickJS](https://bellard.org/quickjs/) — プラグインスクリプトエンジン

> TMDB と DanDanPlay のコンテンツの著作権はそれぞれの権利者に帰属します。本プロジェクトは集約・表示を行うのみで、著作権保護されたメディアの保存や配布は行いません。

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

## プロジェクトの活動

![Alt](https://repobeats.axiom.co/api/embed/4858243f2148dfeaa4e82f119fa918f3ec581a11.svg "Repobeats analytics image")

## スポンサー

[Afdian（爱发电）](https://afdian.com/a/zzzwannasleep) で LinPlayer を支援してくださっている皆様に感謝します（リストはリアルタイム更新）：

<p align="center">
  <a href="https://afdian.com/a/zzzwannasleep"><img src="https://291277.xyz/afdian/sponsors.svg" alt="Afdian スポンサー"></a>
</p>

## チャンネル

Telegram チャンネル [**@MikudesuChannels**](https://t.me/MikudesuChannels) —— リリース、更新予告、ディスカッション。
