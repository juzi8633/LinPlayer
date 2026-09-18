# 播放器 / libmpv / 字幕 / 画质 / 播放窗口

**这个领域最容易踩的坑:**
1. **视频窗是独立顶层窗口,不是网页里的元素** —— 「有声音没画面」几乎都是窗口显隐/几何/层叠出错,不是 mpv 出错;可见性只能用 Win32 `EnumWindows` 找窗口类 `lpvid` 量,CDP 截不到。
2. **mpv 的命令是异步排队的**:`loadfile` 只排队就返回,紧跟的 `sub-add`/`seek` 必失败且常被 `let _ =` 吞掉;要等 `FILE_LOADED`。
3. **属性不可用 ≠ 属性等于 0**:`get_f64` 不看返回值就会把栈上的 `0.0` 当成位置交出去;`keep-open=yes` 下 `END_FILE` 永远不发,判播完只能读 `eof-reached`。
4. **改 mpv 选项前先确认它在这颗库里真的存在**(不同 build 会砍选项),写错是静默 no-op。
5. **画质档位的「不生效」多半不是档位表**:双显卡默认跑核显、放大类 shader 有尺寸门槛、计算着色器在 ANGLE 上没法派发 —— 三种在屏幕上长得一模一样,都是「画面没变」。

> 本文件共 **23** 条。每条都标了它的原记忆文件名与类型;正文按原样搬运,未做压缩或改写。

## 本页条目

- 关掉程序还有残留进程 — `app-exit-leaves-hidden-window.md`
- PC 播放页独立窗口 — `pc-standalone-player-window.md`
- 播放窗标题栏 + 换片黑屏 — `player-window-chrome-and-black-gap.md`
- wndproc 钩子重入爆栈 — `win-wndproc-hook-reentrancy.md`
- 全屏白边=几何没跟上 — `fullscreen-white-edge-geometry.md`
- 起播不露视频窗 — `source-play-never-showed-video.md`
- 桌面双声音/孤儿播放器 — `desktop-double-audio-orphan-player.md`
- Windows 无画无声"加载不出来" — `windows-egl-surface-no-video.md`
- 网盘/strm 播放两大坑 — `netdisk-strm-playback.md`
- 没加载完就点进度条 — `seek-before-file-loaded.md`
- 进度条三症状一条链 — `progress-bar-three-symptoms-one-chain.md`
- keep-open 下 EOF 检测 — `mpv-keepopen-eof-detection.md`
- loadfile 异步吞掉 sub-add — `mpv-loadfile-async-subadd.md`
- mpv 字幕属性实测 — `mpv-subtitle-property-truths.md`
- Android mpv subtitle fonts — `android-mpv-subtitle-fonts.md`
- mpv 发行版卫生 — `mpv-release-hygiene.md`
- 画质档位口径 — `anime4k-denoise-ladder.md`
- 双显卡必须钉独显 — `hybrid-gpu-must-pin-dgpu.md`
- 超分失效根因+toast统一位置 — `superres-and-toast.md`
- Dolby auto decode — `dolby-auto-decode.md`
- FFmpeg magicyuv CVE — `ffmpeg-magicyuv-cve.md`
- ☠☠ `evFileLoaded = 6` —— 6 是 START_FILE，8 才是 FILE_LOADED — 2026-09-03
- 无窗口取一帧：`screenshot-to-file` 能用，`screenshot-raw` 不能 — 2026-09-03
- 画面增强重排:「开了没效果」的两种机制 + 档位照抄 Anime4K 官方 — 2026-09-07

---

### 关掉程序还有残留进程

> 原记忆:`app-exit-leaves-hidden-window.md` · 类型:`project`

2026-08-17 用户报「关闭程序后仍有残留进程」。**不是 mpv、不是子进程,是 LinPlayer.exe 自己**。

**根因**:`player_window_close` 是**藏起来不销毁**(重开 WebView2 要几百毫秒)。
主窗被关掉后,进程里还剩那个隐藏窗口 → tao 永远不发「最后一个窗口关闭」→
Tauri 默认退出路径根本不触发。表现:UI 全没了,进程 + mpv 还在后台,**一声不吭**。
所以只有「播过一次再关」才复现,没播过的直接关是好的。

**修法**(apps/desktop/src/lib.rs,setup 里主窗 `on_window_event`):
- 没在播 → `ah.exit(0)`;
- 在播(播放窗 `is_visible()`)→ `prevent_close` + 置 `QUIT_AFTER_PLAYER` + emit `lp://player-close`,
  走和 Alt+F4 关播放窗同一条正规退出(stopPlayback 落库),`player_window_close` 里 swap 到就 exit。
  直接 exit 会丢这一段进度(每 5s 才回传一次)。
- **兜底 3s 硬退**:播放窗前端崩了接不住时,关闭按钮按下去没反应比丢几秒进度糟得多。

**验证只能挂真 exe**:`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=…` 起
dist-portable 的包 → CDP invoke `player_window_open` 再 `player_window_close`(造出隐藏窗)
→ invoke `plugin:window|close {label:'main'}` → tasklist 看进程。反向注入(去掉 exit)确认过报红。
注意:关窗会当场断开那一页,`Runtime.evaluate` 永远不 resolve —— **只发不等**。
CI 里那份是源码级守门测试 `closing_the_main_window_must_exit_the_process`。

见 [PC 播放页独立窗口](player-mpv.md) [挂真机 CDP 调试](methodology.md)

---

### PC 播放页独立窗口

> 原记忆:`pc-standalone-player-window.md` · 类型:`project`

2026-08-01 落地。播放页不再是主窗里的一层,而是**独立 Tauri 窗口**。

**同包分流,不抽 bundle**:`index.html#player` 建第二个窗口,前端
`IS_PLAYER_WINDOW = location.hash === "#player"` 决定只渲染播放层。
播放层那一千多行(OSD/面板/弹幕/快捷键/seek 闩/进度回传/Trakt·Bangumi 收尾)
**一行没动** —— 抽出去等于重写全项目回归风险最高的一块,收益只是少几百 KB JS。

**必须一起做对的四件事:**
1. `apps/desktop/capabilities/default.json` 的 `"windows"` **必须加 `"player"`**。
   Tauri v2 按窗口 label 授权,漏了 = 播放窗每个 invoke 都失败,而窗口是透明的
   → **全黑且不报错**(同 [「黑屏」多半是 JS 崩了](ui-desktop.md) 的观感)。
2. 视频窗(mpv 独立顶层窗)要能**改挂**:`mpv::pin_overlay_below` 从「只装一次」
   改成可改挂,`PREV_PROC` 只有一个槽 —— 不先卸就装第二个,旧宿主的原 wndproc
   被永久覆盖,那个窗口从此不响应。几何来源和 wndproc 钩子**必须一起换**,
   只换一个 = 画面跟着 A 窗、层级跟着 B 窗。
3. 两个窗口都装几何同步回调,非宿主的那份必须靠 `mpv::is_overlay_host(hwnd)` 早退,
   否则拖动主窗会把播放窗里的画面拽回主窗矩形。
4. `CloseRequested`(Alt+F4)必须 `prevent_close()` + 广播给前端走正规退出:
   窗口一销毁 = 孤儿 mpv 还在出声 + 丢这段进度([桌面双声音/孤儿播放器](player-mpv.md))。

**待播条目**走核层中转(`player_window_open` 塞 → `player_take_pending` 取),
不用 URL 参数/localStorage:前者有长度和编码问题,后者是隐式耦合、时序靠猜。
窗口复用不销毁(重开 WebView2 要几百毫秒),换片靠 `lp://play-pending` 事件。

**几何照抄主窗、不 maximize**:无边框窗最大化在 Windows 上四周溢出 8px
([Win最大化控制栏消失](ui-desktop.md)),没必要为开个窗再踩一遍;照抄也让 OSD 的
布局假设一字不用改。

---

### 播放窗标题栏 + 换片黑屏

> 原记忆:`player-window-chrome-and-black-gap.md` · 类型:`project`

2026-08-02。用户两条:「单开的播放页要有标题栏,不然我拖动不了播放窗口,也点不到别的
地方的选项」+「播过一个视频再播第二个,缓冲出来之前的背景不是黑色,是之前的视频的画面」。

##### 1. 播放窗常驻标题栏 —— 视频窗必须让位,而且**两个几何入口都要减**

播放窗(见 [PC 播放页独立窗口](player-mpv.md))是无边框透明窗,原来能拖的只有 OSD 顶栏里
那条 `.p-drag`。OSD 静止 3 秒淡出、起播前整个 OSD 根本不渲染 —— 于是那几秒里窗口是一块
盖住主窗、拖不动也关不掉的黑板。修法是**不全屏就常驻** `<Titlebar/>`(和主窗同一个组件)。

标题栏不压画面:mpv 视频窗顶部让出 `--titlebar-h`(36 CSS px × scale_factor)。
★ **让位必须放在 mpv 层的一个进程级值里**(`crates/mpv` 的 `OVERLAY_TOP_INSET` +
`apply_top_inset`),不能在调用方把 y/h 算好再传:几何有**两个入口** ——
  · 宿主窗的 Tauri 事件 → `sync_overlay`
  · Alt-Tab / 点任务栏 / DPI 变化 → 只发 `WM_WINDOWPOSCHANGED`,由子类化钩子里的
    `align_to_host` 自己 GetClientRect 重算,**看不到调用方算过什么**
只在前者减的话:Alt-Tab 回来画面就被还原成满客户区、顶到标题栏底下,且只在特定操作后复现。
这是 [全屏白边=几何没跟上](player-mpv.md) 的镜像版:缺的从来不是计算,是触发。
`align_target` 已加护栏测试(摘掉让位当场红);0×0 的"最小化不动"守则必须**先于**让位跑。

##### 2. 「第二个视频露上一片画面」—— 我第一版诊断是错的

第一反应是"`ready` 从没被置回 false"。**不对**:`afterStart` 里一直有 `setReady(false)`。
真因是两件事叠加:
1. 那一句排在 `getPlaybackPrefs()` 和 `play()` 两个 await **后面**,而 play() 要走
   PlaybackInfo → 取流地址 → 起预取代理,慢服务器上是好几秒;
2. 这几秒里**上一片的状态轮询还在跑**(effect 依赖 `[playing]`,playing 还没换),
   它每 250ms 就把 `ready` 拍回 true —— 所以哪怕把复位提到 `playItem` 第一行也会被当场盖掉。
   我确实先只做了第 1 步,CDP 用例照样红,才把 `starting` 那面旗子逼出来。

现在:`playItem/playDownload/playSource` 开头 `starting.current = true; setReady(false)`,
`finally` 落旗(失败/提前 return 也要落,否则轮询被永久闭麦 = 黑屏再也撤不掉),
轮询里 `if (!starting.current && …) setReady(true)`。

顺带两条同类:
- 撤黑屏的判据从 `st.time > 0` 改成 `st.time > startPos + 0.05`。续播时核层一 loadfile
  就把位置记账成续播点,第一拍读到的就已经 >0 —— 旧判据在续播路径上等于"起播即放行"。
- 4s 兜底从"到点就放行"改成"到点**且不在等缓冲**才放行"。
- 核层补了换片前 `stop`(`crates/mpv` 的 `load_inner`):keep-open=yes 不卸载文件,
  画面冻在最后一帧;`stop` 让 mpv 自己画黑底。同时显式 `idle=yes`——libmpv 默认就是,
  但 idle=no 时 `stop` 会让核心退出 = "播第二个视频整个播放器没了"。

##### 3. 自检台:`ui/shared/player-chrome.check.mjs`

`npx vite build && node ui/shared/player-chrome.check.mjs`。真渲染 + 真点击,
六个反向注入逐个验证过报红(见文件头)。三个只有写这个台子才会撞上的坑:
- `Page.navigate` 到**完全相同**的 `index.html#player` 是**同文档**导航,
  addScriptToEvaluateOnNewDocument 不重跑、React 不重挂 → 第二次注入的待播条目进不去。
  必须带每次都变的 query。
- 假后端里 `plugin:window|is_fullscreen` 不能跟着"plugin: 就返回 1"—— 返回真值 1
  会让播放页以为在全屏,标题栏按设计本就不渲染,断言红得像功能坏了。
- `play` 必须**可延时**且在 resolve 时把 `__ST.time` 清回 0(核层 load_inner 就是这么做的)。
  秒回的 play() 把"露残帧"那个时间窗整个抹掉,测出来必然假绿。

---

### wndproc 钩子重入爆栈

> 原记忆:`win-wndproc-hook-reentrancy.md` · 类型:`project`

PC 端是「mpv 独立顶层窗 + 透明 Tauri 主窗」两层。对齐/层叠原来只挂在 Tauri 的
`Resized | Moved | Focused(true)` 三个事件上,而 **Alt-Tab、点任务栏、别的窗口插进来、
WebView2 自己动都不产生这三个事件** → 视频层「掉」到 UI 后面。

**正解:子类化主窗 HWND 接管 `WM_WINDOWPOSCHANGED`**(位置/尺寸/z 序任一变化都发),
一处钩子吃掉整类漏网,不用定时器轮询。`crates/mpv/src/lib.rs` 的 `mod overlay`。

##### ★ 三个必须记住的坑

1. **重入闸不能省 —— 少了就是当场爆栈。**
   回调里调 `SetWindowPos`/`ShowWindow` 摆视频窗 → 改变主窗相对 z 序 →
   Windows 又发一条 `WM_WINDOWPOSCHANGED` → 再进来 → 递归到栈溢出,
   **进程无声消失**:日志停在最后一行、没有 panic、Windows 事件日志里也查不到。
   实测症状 = 装上钩子后鼠标挪一下窗口 App 就没了。
   用 `AtomicBool::swap(true)` 做闸;另外状态已经对时**不要**调 `ShowWindow`(它也动 z 序)。
2. **取不到原 wndproc 就别装**。装了却把消息全喂 `DefWindowProcW` = 把 Tauri 的窗口过程
   整个换掉,窗口彻底不响应。降级回事件驱动远比这好。
3. 钩子里用的视频窗句柄可能已失效(Player 在 `Mutex<Option<Player>>` 里会被替换),
   先 `IsWindow` 再用。

##### 显隐必须是「与」

视频窗原来建出来就 `WS_VISIBLE`(X11 那半是建完就 `XMapWindow`)、每次 sync 又带
`SWP_SHOWWINDOW` → 从 App 启动起就一直在桌面上,**主窗一最小化就露出来**
(用户说的「反复最小化能看到后面在放」)。
收敛成 `WANT_VISIBLE(在播片) && 主窗没最小化/没隐藏`,三个调用点共用一个函数 ——
散着写必然出现「A 藏了 B 又亮出来」。

**X11 没有等价钩子**(要 `XSelectInput(StructureNotify)` 主窗 frame 再自己跑事件循环,
而那个 frame 会被 WM reparent),只能继续靠 Tauri 事件。这是已知能力差,不是忘了写。

##### 验法
`EnumWindows` 找窗口类 `lpvid` 查 `IsWindowVisible`/`IsIconic`,四态(未播放/播放中/
最小化/恢复)逐个对。注意 `FindWindowW(null,"LinPlayer")` 会找错窗口,**按窗口类找**。

录屏限制:两个独立顶层窗,窗口捕获只能抓一层 —— 要 UI+画面同时录只能用**显示器采集**。
真正的单窗口合成需要 WebView2 visual hosting(`CreateCoreWebView2CompositionController`),
**wry/Tauri v2 不暴露**,要做得 fork wry。

相关:[PC 窗口 chrome](ui-desktop.md)、[Win最大化控制栏消失](ui-desktop.md)、[弹幕交给 libass](danmaku-sync.md)

---

### 全屏白边=几何没跟上

> 原记忆:`fullscreen-white-edge-geometry.md` · 类型:`project`

2026-08-01。用户：「切换回播放器，白边不会消失，一直存在，**必须窗口化，再次全屏，才能消除**。其他播放器没这问题。」

**判据先于修法**：一个症状能被某个特定手势**稳定**治好，说明缺的不是**计算**，是**触发**。
「窗口化→再全屏」恰恰是会发 `WindowEvent::Resized` 的两步 —— 那就是事件那条路被激活了。

**根因**：mpv 视频窗是独立顶层窗口，它的几何**只**由 `apps/desktop` 的 `sync_video` 摆，
而那个挂在 Tauri 的 `Resized / Moved / Focused(true)` 三个事件上。
主窗几何还有别的路径会变，而那些路径**不产生**这三个事件：Alt-Tab 回来、点任务栏回来、
DPI 变化、别的全屏应用切走再切回。视频窗于是停在旧矩形上，主窗已铺满屏幕而它还是窗口大小，
四周露出的那一圈就是白边。

**修法**：几何跟着 z 序一起挪进 **`WM_WINDOWPOSCHANGED`**(位置/尺寸/层叠任一变化都发它)，
不用再枚举「还有哪些路径没覆盖到」。这和 [wndproc 钩子重入爆栈](player-mpv.md) 治 z 序漂移是同一个钩子、
同一条道理 —— 那次只挪了 z 序和显隐，几何留在了外面。

原来不在钩子里做几何的理由写在注释上：「这里拿不到 Tauri 的 inner_position」。
**那个理由不成立**：`GetClientRect` + `ClientToScreen` 量的就是同一个矩形。
实测逐像素相同 —— 窗口 `203,203 1195x729`(无边框窗口那圈不可见调整边框)，
客户区 `210,204 1180x720`，后者正是 Tauri 报的值。

两条必须留的守则(有单测 `align_target` 钉住)：
- **客户区 0×0(最小化)时不动** —— 照摆会把视频窗缩成 0×0 且再也长不回来。
- **已经对上了不动** —— 我们跑在消息回调里，`SetWindowPos` 即使不改任何东西也会再发一条。

Linux(X11)那条路没动，这个症状是 Windows 独有的。

**顺带**：驱动真 exe 复现时，`Input.dispatchMouseEvent` 的坐标是 **CSS px**(dpr=1.5 也不用换算，
实测事件落点与 `getBoundingClientRect` 中心一致)；但页面上可能盖着 sheet/更新横幅把点击吃掉，
先用 `elementFromPoint` 确认命中的是谁。CDP 挂法见 [挂真机 CDP 调试](methodology.md)。

---

### 起播不露视频窗

> 原记忆:`source-play-never-showed-video.md` · 类型:`project`
>
> 🔒 原文含真实地址/账号等具体值,已替换为占位符(原文含具体值,已脱敏)。

2026-08-16 用户报「VOD 插件播放不显示画面窗口」。**两个独立的真因叠在一起**,
各自都足以让画面消失,而且**两个都是编译绿、单测绿、类型也绿**。

##### ① 核层:`source_play` 从来没露过视频窗

桌面的视频窗是**独立顶层窗口(窗口类 `lpvid`)**,平时**藏着**(常驻可见的话主窗一
最小化就是一块黑留在桌面)。起播那一刻由 `show_video(&state, true)` 露出来。

`show_video` 是 4f72060c 引入的,当时给 `play`(Emby)和 `play_local`(下载文件)
都补上了,**唯独漏了 `source_play`** —— 而网盘 / 资源站插件 / Stremio / 飞牛
**全走那一条**。表现:mpv 在放、有声音、进度也在走,画面窗口从头到尾没露面。
没有任何报错(那是个 Win32 窗口的显隐,没返回值可断言)。

同 [媒体库屏蔽](emby.md) 那条教训的反面:**加一个横切开关,必须 grep 一遍所有调用者**。

##### ② 前端:`VodPage` 自己 invoke,绕开了播放窗

桌面起播 ≠ invoke 一次 `source_play`。真正的入口是 App.tsx 的 `playSource`:
它先 `playerWindowOpen` 把**独立播放窗**拉起来(视频窗焊在它背面、OSD 也在那个窗里),
播放窗自取待播条目后才在**那个窗里**调 source_play。

`NetdiskPage` 拿的是 `onPlay`,`SourceBrowsePage` 却**只把 onPlay 传给了 NetdiskPage**,
VodPage 那一支漏了 → 它只好自己 `playEpisode()` → 直接 invoke。播放窗根本没开。

手机端是同一根因的另一半:那边画面走的是 webview **底下**那层 SurfaceView,
页面起播完只 `back()`、**从不导航到播放页** → 不透明的目录页/详情卡盖着画面,
同样只剩声音。**网盘页和 VOD 页都是这样**(TV 端反而是对的:play 完 `go({page:"player"})`)。

修法:`playEpisode` 改成只造壳的 `episodeEntry`,页面一律走各端 App 的起播函数
(desktop 的 `playSource` prop / mobile 的 `ctx.playSource`)。

##### 钉住它的两条测试(apps/desktop/src/lib.rs `api_contract_tests`)

- `every_play_command_reveals_the_video_window` —— 扫自己的源码,凡是把新文件塞给 mpv
  的命令必须有 `show_video(&state, true)`;唯一豁免 `source_watchdog`(302 重签是播放中换链,
  窗早开着)写在测试里的白名单。**needle 用 `concat!` 拼**,否则 include_str! 会匹配到测试自己。
- `source_pages_start_playback_through_the_app` —— 桌面两个源页面都必须拿 onPlay、
  SourceBrowsePage 两个标签都必须传 onPlay;手机端 `playSource` 必须 `push({page:"player"})`、
  两个页面不许出现 `sourcePlay(`。**手机端的断言故意放在桌面这套测试里**:安卓那个 crate
  在本机连编都要 NDK,放那边等于没有门禁。
- 两条都反向注入真 bug 验过会红(删 show_video / 摘掉 onPlay / 摘掉 push)。

##### 真机验证(脚本在 scratchpad,没进仓库——它依赖用户自己的账号和线上站点)

挂打包好的 exe + CDP(`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=`),
用 `__TAURI_INTERNALS__.invoke` 走真链路;**视频窗可见性从进程外用 PowerShell 量**:
`EnumWindows` 找窗口类 `lpvid` → `IsWindowVisible` + `GetWindowRect`。
CDP 截图只截得到 webview,截不到那个独立窗口 —— 这类问题只能用 Win32 量。
实测:起播前 visible=false(基线)→ 起播后 visible=true 且矩形跟着播放窗走。

**顺带量到的一件事,不是我们的 bug**:采集站甲 那条线路起播要 ~17s。curl 实测它的
子播放列表(2777s 的片、2s 一段 ≈1400 条)**30s 都下不完**,是站点慢,不是客户端。

相关:[PC 播放页独立窗口](player-mpv.md)、[播放窗标题栏 + 换片黑屏](player-mpv.md)、
[VOD 资源站插件](plugins.md)、[挂真机 CDP 调试](methodology.md)、[测试必须先红](methodology.md)、
[媒体库屏蔽](emby.md)

---

### 桌面双声音/孤儿播放器

> 原记忆:`desktop-double-audio-orphan-player.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

**症状**：桌面播放「有两个声音」，暂停后声音还在、再播放又叠一个（同一音轨回声）。

**根因（日志铁证）**：`userdata/.../logs/linplayer-*.log` 里同一会话出现**两次**
`开始初始化 media_kit 内核`（相隔 ~62ms、中间无任何 dispose），随后两次「初始化完成」——
即**两个 VideoPlayerService/两个 mpv 实例同时存活**，各自解同一文件 → 两份音轨。暂停只作用于
UI 绑定的那个，另一个成孤儿继续出声。触发=**播放页被重复导航打开**（`context.push('/player/:id')`
手抖双击/事件重发）。media_kit `Player.open()` 自身会先 `stop()` 再 loadfile（real.dart:173），
所以**不是** reload 叠流；就是两个独立 Player。

**修法（都在桌面）**：
1. 导航去重：`lib/desktop/routes/player_navigation.dart` 的 `pushPlayerRoute(context,location)`，
   1s 内对**同一 location** 的重复 push 直接忽略。已替换 detail 头部播放按钮/菜单、下载页的
   `context.push('/player/...')`；换集用 `context.replace` 不叠、不动。
2. 孤儿守卫：`MpvPlayerAdapter` 加 `_adapterDisposed`——initialize 收尾前若已被 dispose 顶替，
   销掉刚建的 Player 再退，堵住「后台空跑出声」的孤儿(代码本就在 video_player_service.dart:662
   注释里记过这个 race)。

**排查纪律**：dev/release 版日志在 **exe 同目录 `userdata/app_support/logs`（或回退 `userdata/temp/linplayer_logs`）**，
不在 `%APPDATA%\Linplayer`。数 `开始初始化 media_kit` 次数 vs `dispose`，多出来的就是孤儿。

**同批**：Windows hwdec 默认从 `auto-copy` 改 **`d3d11va`(零拷贝)**——硬件纹理下 auto-copy 每帧
GPU→内存拷回引起卡/闪，d3d11va 与 ANGLE 同 D3D11 后端直连、消卡消闪(用户实测)。日志里
`dxva2-egl Failed to create EGL surface` 是**无害红鲱鱼**(见 [Windows 无画无声"加载不出来"](player-mpv.md))，
别被它带偏。默认 `_zeroCopyHwdec=Platform.isWindows`，更多菜单可切回 auto-copy 兜底。见 [超分失效根因+toast统一位置](player-mpv.md)。

---

### Windows 无画无声"加载不出来"

> 原记忆:`windows-egl-surface-no-video.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

**真正根因(commit 23e8c1e,ffprobe 实测锤死)**:桌面 mpv「所有视频加载不出来、无画无声」
是 `stream-lavf-o` 里的 **`multiple_requests=1`**(HTTP keep-alive 复用连接发 Range,
8ffac6f「连接保温」引入)。对部分 Emby/源,libavformat 复用连接做 seek/Range 会灾难性变慢。
ffprobe 打开同一网络 MKV 实测:**默认 1.6s;+multiple_requests=1 → 3m35s(×130);
+reconnect 系列 → 1.9s(无害)**。表现:mpv 读 MKV 尾部 Cues 索引(seek 到文件尾)卡十几秒
→无帧→无画无声。修法:`mpv_player_adapter.dart` initialize 的 stream-lavf-o **去掉
multiple_requests=1,保留 reconnect 系列**。

**红鲱鱼警告(我在这上面浪费了 5 轮)**:日志里
`mpv[libmpv_render/dxva2-egl] Failed to create EGL surface` **是无害的**——mpv 在 GL 初始化
时逐个探测 hwdec interop(d3d11va/d3d11-egl/dxva2-egl/cuda/vaapi...)全失败中的一条。
**判据**:同日志有 `GL_RENDERER='ANGLE (Intel..., D3D11)'` → 硬件渲染上下文其实正常。
别再:①当成 media_kit 渲染 surface 失败去关硬件加速(软件渲染封顶 1080p,4K 不可接受);
②当成 hwdec 问题反复改 hwdec。这些都试过,全错。

**排查方法论(有效,下次照做)**:
1. mpv 卡在哪 → 提日志到 `MPVLogLevel.debug`(Windows 平时 warn),看 GL_RENDERER + 卡住前最后一条(本次是 "Seeking to <大偏移> to read header element 0x1c53bb6b"=MKV Cues)。
2. 网络还是我们的设置 → **curl/ffprobe 直接打同一 URL**:curl 分段 range 都 <2s → 网络/服务器没问题;`ffprobe -i URL` 默认 1.6s vs 加我们的 `stream-lavf-o` 选项 → 一眼看出是自家设置。
3. 逐项 bisect libav 选项(ffprobe -multiple_requests 1 / -reconnect 1 单独测)锁定真凶。

保留的对的改动:hwdec 桌面用 copy-back(Win `auto-copy`/mac `videotoolbox-copy`,避 interop 崩溃,见 「macos-no-video-hwdec」(该条不在本库,多为 Flutter 时代的旧记忆,已作废));_applyStartupSeek 的 _player 空指针守卫。

---

### 网盘/strm 播放两大坑

> 原记忆:`netdisk-strm-playback.md` · 类型:`project`
>
> 🔒 原文含真实地址/账号等具体值,已替换为占位符(原文含具体值,已脱敏)。
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

针对 strm/网盘 后端(实测 `<某 strm/115 型 Emby 服>`「稳健115」= Emby 4.9.5.0 兼容,strm 指向 115 CDN)的两个非显然坑,均已修(commit 4d5837c + player_screen_panels 回退):

**① strm 选不了字幕/音频 —— 服务器空流,不是我们的 bug。**
strm 条目的 `PlaybackInfo` 返回 **0 视频/0 音频/0 字幕流**(服务器不 ffprobe 远程文件)。而各端字幕/音频面板原本只从 `mediaSource.mediaStreams` 建选择列表 → 空 → 没法选。**但 mpv 播放后自己解封装读到了真实轨道**(`playerService.tracksInfo`)。修:`player_screen_panels.dart` 音频/字幕面板在 Emby 流为空时**回退用 mpv 真轨**(含"关闭"项),按 mpv 轨道 id 直接 `selectAudioTrack/selectSubtitleTrack`。对普通文件(流非空)无影响。诊断:`curl -sX POST $BASE/Items/$ID/PlaybackInfo` 数 `"Type":"Audio"/"Subtitle"` 条数=0 即确认。

**② 大电影卡 = 单连接吞吐被打死,不是解码/磁盘。**
strm 播放 URL 是 `/Videos/{id}/stream` **302 跳转**到 CDN(如 `<115 CDN 域名>`)。curl 实测:**单条流式 GET(跟随302一次)=28-38MB/s,而分段 Range(每段重打 Emby URL→各自302+重连+TLS)=1.8MB/s(×15慢)**。真凶是 `stream-lavf-o` 的 **`multiple_requests=1`**(诱发大量分段 Range)。桌面 `mpv_player_adapter.dart` 早已删除(注释:同 MKV ffprobe 默认1.6s→加该参数3m35s ×130),**但 Android 原生 `MpvPlayerPlugin.kt` 漏同步一直留着** → 网盘大电影只有 ~10MB/s、缓冲跟不上。修:Android stream-lavf-o 删掉 `multiple_requests=1`,只留 reconnect 系列。

**教训**:①"选了轨不显示/没轨可选"先分清是 libass 字体(见 [Android mpv subtitle fonts](player-mpv.md))还是**服务器空流**(strm/网盘)——后者要回退播放器真轨。②排吞吐先 `curl -sL -w '%{speed_download}'` 直连测 CDN 能到多少,再看是不是我们客户端配置卡的(cache-on-disk 在**内部存储**不是瓶颈,别误判成磁盘慢)。③**Android 原生 mpv 配置(MpvPlayerPlugin.kt)与桌面(mpv_player_adapter.dart)是两套,桌面的网络/字幕修复容易漏同步到 Android**——排 Android 播放问题先对照桌面同名设置。相关:「unified-ua-and-prefs」(该条不在本库,多为 Flutter 时代的旧记忆,已作废) [Multi-thread loading prefetch proxy](network.md)

---

### 没加载完就点进度条

> 原记忆:`seek-before-file-loaded.md` · 类型:`project`
>
> 🔒 原文含真实地址/账号等具体值,已替换为占位符(原文含具体值,已脱敏)。

2026-07-27 修。用户报「视频还没缓冲到能播时点进度条跳转,之后进度条跟着缓冲进度走、
画面不变,后续调进度稳定复现」。**三个独立 bug 叠在一起**,单看每个都不像 bug。

##### 1. 量程塌成 1 秒(直接根因,三端)

核层每次 `loadfile` 都把 duration 记账清回 0,mpv 要到 FILE_LOADED 才报得出来。
**真服(<用户主力 Emby 服(UHD fork)>)实测这个窗口 6~7 秒。** 窗口内:

- PC `max={Math.max(1, status.duration)}` → max=**1**
- 手机 `max={dur || 1}` → max=**1**

用户点在条中间 → 目标 **0.5 秒**,不是片长的一半。核层照单全收地跳回片头 =「画面不变」。

同一个 `|| 0` 还有**三处克隆**,是相对跳转的上界:`Math.min(duration || 0, time+d)` ——
duration=0 时 `Math.min(0, …)` 把目标一律夹成 0,加载期快进反而跳回片头。
位置:mobile PlayerPage 的 jump、mobile Gestures.ts 横滑、tv PlayerPage 的 jump。
全改 `|| Infinity`(封不了顶就交给 mpv 夹)。

修法:PC 轮询里 `duration>0 ? st : {...st, duration: 上一次的}`(起播时已用 Emby
runtime 播下正确时长);**statusRef 必须和 state 一起写**(putStatus),只写 state
的话兜底会拿到上一集的时长。手机兜底到 `item.runtime_secs`。两端再补 `disabled`——
量程真不可信时(本地文件/网盘,runtime 未知)就不该能点。

##### 2. FILE_LOADED 之前的 seek 被静默丢掉(核层)

mpv 那会儿没有文件可跳,`seek` 只拿回命令错误;而 seek 闩在发命令**之前**就设上了
(那是 [进度条三症状一条链](player-mpv.md) 故意的设计),于是条压着用户点的
位置 2.5s,再弹回 `start=` 的续播点。

**解法和外挂字幕是同一个**(见 `add_subtitle`):没开好就排进 `SubState.pending_seek`,
FILE_LOADED 由事件线程补发。两个细节缺一不可:
- 补发时**重置闩的计时起点**,否则加载花掉的几秒让它一进来就判超时;
- 命令真失败时把闩清掉(不清 = 条钉在一个到不了的位置上 2.5s)。

顺带:`seek_abs` 送的是 `secs` 而闩记的是 `max(0.0)` 后的值,负数两边对不上。

##### 3. 缓冲条从 0 画起,右边缘永远压在播放头上(PC/TV)

`demuxer-cache-time` 是缓冲末端的**绝对时间戳**,而 demuxer 的缓存**只往前存** ——
跳到 50 分钟处时 [0, 50:00] 一个字节都没有。从 0 画到缓冲末端既是假的,更要命的是
真服实测缓冲只领先 4 秒(0.05% 宽),两条边缘完全重合 —— 这就是用户那句
「进度条卡在缓存进度上面」的**字面来源**。改成从播放头画到缓冲末端。

**Why:** 三处全都编译绿、单测绿、不抛异常,而且本地文件永远复现不出来(本地 duration
一拍就有、seek 一拍就完)。用户的描述("跟着缓存走")是**字面准确**的,别当成比喻。

**How to apply:**
- 查这类问题必须**测量加载窗口**:真服 `play` 后每 250ms 打 `status`,看 duration 多久
  才非 0。这个窗口是所有"起播期"bug 的舞台。
- 驱动真 exe 用 [挂真机 CDP 调试](methodology.md);本次靠它读到 `input.max=4165` 而非 1。
- 相关:[进度条三症状一条链](player-mpv.md)(上一轮的三症状)、
  「player-buffered-progress」(该条不在本库,多为 Flutter 时代的旧记忆,已作废)、[loadfile 异步吞掉 sub-add](player-mpv.md)(同一个 FILE_LOADED 排队坑)、
  [测试必须先红](methodology.md)(`seek_before_file_loaded_is_queued_not_sent` 反向注入验过红)。

---

### 进度条三症状一条链

> 原记忆:`progress-bar-three-symptoms-one-chain.md` · 类型:`project`

2026-07-23 修。用户报三个现象,以为是三个 bug,其实是**一条链上的三处**,而且全都不报错:

| 现象 | 真因 | 位置 |
|---|---|---|
| 正常播放进度条不走 | `onMouseUp` 挂在 `<input type=range>` **自己身上**。拖着滑块移出条外/移出窗口松手 → input 收不到 mouseup → `setSeeking(null)` 不执行 → `seeking` 永远非 null → `curTime = seeking ?? status.time` **永久钉死** | `ui/desktop/App.tsx` 进度条 JSX |
| 拖完弹回旧位置 | mpv 的 `seek` 是**排队命令**,cmd 返回只代表「收到」;`time-pos` 要 50~500ms 后才跳过去。轮询必然打进这个窗口,把旧位置画上去 | `crates/mpv/src/lib.rs` `seek_abs` |
| 条和画面对不上 / 时间抽回 0 | `get_f64` **不看 `mpv_get_property` 返回值**。属性不可用(seek 中/换片解码就绪前/缓冲饥饿)时它 < 0 且**不写 out**,栈上初值 `0.0` 被当成「位置=0」交出去 | `crates/mpv/src/lib.rs` `get_f64` |

##### 修法(都在核层,不是前端补丁)

- `try_f64` 看返回值 + 查 `is_finite`;`sticky_f64` 读不到就沿用上次(**属性暂时不可用 ≠ 值变成 0**)。`loadfile` 时清账,否则上一集的位置漏给下一集。
- **seek 闩** `apply_seek_latch(latch, reported)`:seek 后到位之前一律报目标位;`|reported-target| <= 1.5s`(mpv 按关键帧落点,精确命中是例外)或 2.5s 超时就松闩放真值。**必须有超时** —— 一直压制会把「弹回」换成更难查的「永远不动」。抽成自由函数才能脱离 libmpv 单测(`Player::new` 要建叠加窗口+桌面会话)。
- 前端提交动作挂 **window** 的 `pointerup`/`pointercancel`/`blur`,不挂 input;拖动值同时存 ref(window 监听器闭包读 state 是注册那刻的快照)。
- 轮询 500→250ms,补间交给 **CSS `transition: width 260ms linear`** 而不是 rAF+setState(App 是上千行单组件,每帧重渲染 = 拿卡顿换顺滑);拖动中加 `.dragging` 关掉过渡。上报改 `% 20` 保持 5s。
- 轮询的 `catch { /* 未就绪忽略 */ }` 是**空的** —— 起播头几拍失败该忽略,但它把「持续失败」一起咽了,故障因此只能靠猜。改成连续 8 拍(2s)后 `say()` 一次。

**Why:** 这三处任何一处单独发作都长得像「播放器坏了」,而三处都不抛异常、编译绿、测试绿。
**How to apply:** 见 「player-buffered-progress」(该条不在本库,多为 Flutter 时代的旧记忆,已作废)(缓冲进度三端口径)、[keep-open 下 EOF 检测](player-mpv.md)(同类「属性才是真相、事件不发」的坑)、[测试必须先红](methodology.md)(两条新测试都做过反向注入验红:`120 != 600`)。

---

### keep-open 下 EOF 检测

> 原记忆:`mpv-keepopen-eof-detection.md` · 类型:`reference`

我们的 mpv 开着 `keep-open=yes`（到结尾停在最后一帧而不是黑屏）。
**后果：文件不会被卸载，`MPV_EVENT_END_FILE` 因此永远不发。**
只监听事件来判「播放结束」= 写了一个永不触发的死分支。

正确做法：轮询 **`eof-reached`** 属性（mpv 文档明说它就是为 keep-open 场景准备的）。

2026-07-21 用 ctypes 直接加载 `libmpv-2.dll` 实测（1 秒 testsrc，keep-open=yes）：

```
time-pos=0.9  duration=1.0  eof-reached=no
time-pos=0.9  duration=1.0  eof-reached=yes   ← 翻转
```

duration 仍可读 = 文件确实没卸载，反证 END_FILE 不会来。
未加载文件时 `eof-reached` 返回 None（属性不可用），不是 "no" —— 判定要写成 `== Some("yes")`。

**这个坑的代价**：播放结束检测不到 → `stop_playback` 只在用户手动退出时才跑 →
Trakt/Bangumi 的「看完」一次都不会上报（用户报的「播完历史观看里没有同步」）。
换片时要清标志，否则上一集的 eof 会被下一集第一次轮询读到。

查属性是否存在的手法（别信文档信实测）：ctypes 加载 dll → `mpv_get_property_string(ctx, b"property-list")`
→ 在返回的大字符串里 grep。注意 `sub-add` 查不到是对的 —— 它是**命令**不是属性。
相关 [mpv 字幕属性实测](player-mpv.md)、[mpv 发行版卫生](player-mpv.md)。

---

### loadfile 异步吞掉 sub-add

> 原记忆:`mpv-loadfile-async-subadd.md` · 类型:`project`
>
> 🔒 原文含真实地址/账号等具体值,已替换为占位符(原文含具体值,已脱敏)。

**`loadfile` 只把条目排进 playlist 就返回,此刻并没有「当前文件」。**
`sub-add` 作用于当前文件,所以紧跟在 `load` 后面发必然拿到 **-12 error running command**。

ctypes 实跑 libmpv v0.41 复现(手法见 [keep-open 下 EOF 检测](player-mpv.md)):

```
loadfile        -> 0 success
sub-add(立刻)   -> -12 error running command   字幕轨 0 条
sub-add(等 FILE_LOADED=8 之后) -> 0 success    字幕轨 1 条
```

2026-07-21 事故:外挂字幕两端全都挂不上(用户「软件里看得见,播放页字幕选项里没有」)。
**双重静默**:`add_subtitle` 是 `let _ = self.cmd(...)` 吞掉错误,调用点还**无条件**
打一行「挂载外挂字幕 N 条」—— 日志看起来完全正常。

**已修**:事件线程 latch `MPV_EVENT_FILE_LOADED`(=8),挂载改由它在那一刻执行。
- **不能在调用点阻塞等** —— 两端 play 都在播放器锁内,等于拿着锁卡住 UI。
- **也不要另开线程**:`Drop` 是 `running=false → join(事件线程) → mpv_terminate_destroy`,
  只有跑在事件线程上才被那个 join 保护;另开线程 = 用户在字幕下载途中关播放器就 ctx 悬垂。
  代价是 `sub-add` 会**同步拉取字幕文件**(真服实测两条相隔 4s),这期间 END_FILE 只是延迟 latch。
  每条挂载前检查 `running`,关闭时不挂剩下的。
- `loaded` 与 `pending` **必须同一把锁**:拆成 AtomicBool+Mutex 会 TOCTOU ——
  调用方读到 false 正要入队时事件线程恰好取走空队列,字幕永远没人挂(同 [预取环形缓存并发竞态(已修)](network.md))。
- 判断放进 `add_subtitle` 内部,**五个**调用点(两端起播/网盘源/手动加/翻译字幕)一次全好。

**排除过的修法**(都实测否掉,别再走):
- `sub-files-append` **属性不存在**(-8 property not found)。
- `sub-files` 属性、`loadfile` 第4参 options:都能挂上,但**丢 title** ——
  前端字幕列表显示的就是 title,挂上了也是一条空白项,等于选不了。只有 `sub-add` 能带标题。
- `loadfile` 三参 options(老语法)→ -4 invalid parameter。

**前端那一半也要修**:TV 播放页原来 mount 拉一次 `tracks()` 就定死,
外挂字幕晚到(真服 6.7s / 10.8s)永远进不了面板。桌面那份「探到稳定」的轮询
已提到 `ui/shared/track-poll.ts` 两端共用 —— **两端各写一份正是这个 bug 的成因**。
另:Emby 的 stream index ≠ mpv 的 sid(外挂是追加进 track-list 的),按 title 认才对。

**验收手法(这次差点栽的地方)**:
真服 `<Emby 测试服 B>`「搏击俱乐部」内封 15 + 外挂 2(「emby-test-server-2-测试服 B」(该条不在本库,多为 Flutter 时代的旧记忆,已作废))。
我的探测脚本写了 `if len(subs)>=2: break` —— **15 条内封就满足了**,第 6 秒退出,
只看到 15 条,我差点签字说「修复在真机上没生效」并回头改代码。
**先数清楚 Emby 侧内封/外挂各几条再定阈值**;开 `LP_MPV_LOG=1` 回读 mpv 日志里的
`Run command: sub-add` 才是硬证据。归入 [测试必须先红](methodology.md) 的「验证环境≠真实环境」。

配套:[外挂字幕真根因](emby.md)(四层断口的前三层)、[网盘/strm 播放两大坑](player-mpv.md)。

---

### mpv 字幕属性实测

> 原记忆:`mpv-subtitle-property-truths.md` · 类型:`reference`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

2026-07-16 用 ctypes 直接加载 `native-poc/src-tauri/libmpv/libmpv-2.dll`(**mpv v0.41.0-744**)问出来的,不是查文档猜的。查法(可复现):

```python
import ctypes, os
os.add_dll_directory(os.getcwd())          # libmpv 所在目录
m = ctypes.CDLL("libmpv-2.dll"); m.mpv_create.restype = ctypes.c_void_p
h = ctypes.c_void_p(m.mpv_create()); m.mpv_initialize(h)
m.mpv_get_property_string.restype = ctypes.c_char_p
m.mpv_get_property_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
print(m.mpv_get_property_string(h, b"property-list"))   # ← 全部属性,别猜名字
```
没有 mpv.exe 也能问;`property-list` 是权威清单。

##### 两条会咬人的事实
1. **`sub-ass-override` 默认 `scale`,该模式下 ASS 字幕完全无视 `sub-font-size`,只认 `sub-scale`。**
   内封字幕(尤其番剧)几乎都是 ASS → 拿 sub-font-size 当「字幕大小」旋钮**对内封从来没生效过**。
   字幕大小一律用 `sub-scale`(对 ASS 和纯文本都生效)。
2. **`secondary-sub-ass-override` 默认 `strip`**(把次字幕 ASS 剥成纯文本)→ 次字幕反而**只认** sub-font-size。
   合起来就是用户 2026-07-16 报的怪象:「只能调次字幕的字体大小,主字幕调不动」+「次字幕不渲染样式」——**同一个根因的两个面**。
3. **`secondary-*` 只有** sid / ass-override / delay / pos / visibility / text / start / end / lines。
   **不存在** `secondary-sub-font-size` / `-font` / `-color` / `-scale` / `-border-size`(set 回 -8 property not found)。
   → 「次字幕单独设字号」在 mpv 层面**做不到**;所有 `sub-*` 样式是主次共用的一份。UI 要如实标「主次共用」,别造假 stepper。

**Why**:这类事「看起来接了、也不报错、就是不生效」,靠读代码永远查不出,只能问 libmpv 本人。
**How to apply**:动 mpv 字幕/属性前先跑上面那段拉 `property-list`;换 libmpv 版本后结论要重验。
⚠️ `secondary-sub-ass-override=scale`(保留原样式)可能让次字幕按 ASS 自带定位画 → 压到主字幕上、`secondary-sub-pos` 推不动。已做成开关让用户自己切,默认 scale,待真机验证。

相关:[Android mpv subtitle fonts](player-mpv.md)(libass 缺字体)、[「待接」多半是谎](methodology.md)(别信「核层没有」的注释)、夸克二维码是图不是文本(本地 sources.md,未入公开库)(观察和推理冲突时先打真接口)

---

### Android mpv subtitle fonts

> 原记忆:`android-mpv-subtitle-fonts.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

> **更正(2026 会话,libmpv 升级到 0.41 后)**:字体方案已改成**版本无关双铺**,函数改名 `setupFontconfig`→`setupLibassFonts`。二进制核实关键差异:**0.36 无 fontconfig**(FcInit 全 miss)、只走 libass **目录 provider**(扫 `config-dir/fonts` + `config-dir/subfont.ttf` 默认字体);**0.41 有 fontconfig** 且读 `FONTCONFIG_FILE`。所以现在 `setupLibassFonts` **同时**:①把 `/system/fonts` 软链进 `config-dir/fonts` + 挑 CJK 字体软链成 `subfont.ttf`(喂 0.36 目录 provider,零打包);②生成 `fonts.conf`+设 `FONTCONFIG_FILE`(喂 0.41 fontconfig)。两套 .so 都能显示文本字幕。诊断行 `sub-diag` 已带 `libass-fonts` 字段(软链数/默认字体/fontconfig 状态)。下面旧内容(fonts.conf 单铺)是 0.36 时的中间态,已被双铺取代。

安卓原生 mpv（MpvPlayerPlugin.kt → libmpv.so）**内封/外挂文本字幕(SRT/ASS)整段不渲染**的根因:Android 上 libass 没有 fontconfig,若不显式给字体目录,libass 找不到任何字体 → 文本字幕画面空白(位图 PGS/SUP 不依赖字体,不受影响,所以"全都不显示"其实主因是文本类)。

> Go 栈(2026-09 起)的安卓字体做法见本文末「安卓 mpv 内核字幕:三处接线 + 默认字体」。

**How to apply:** 排"原生 mpv 选了字幕轨也不显示"先查字体,不是查选轨。诊断:FILE_LOADED 日志里 sid 已选中但无画面字幕 → 几乎都是 libass 无字体。位图字幕(PGS)走 OSD 覆盖层、`blend-subtitles=no`/`sub-visibility=yes` 即可,与字体无关。相关原生坑见 「android-storage-and-mpv-logs」(该条不在本库,多为 Flutter 时代的旧记忆,已作废)、[Android R8 JNI keep](android.md)。

---

**更完整的根因(2026-07-08 修,补上一个更常见的坑)**:字体到位后"内封字幕仍不显示"的真正主因不是字体,是**原生轨道列表推送竞态**——Dart 侧 `_tracks` 只靠 push 的 `tracksChanged` 事件填充,而它仅在 `FILE_LOADED` 时经 `emitEvent` 推一次:
- `MpvPlayerPlugin.kt` 的 `onListen` 订阅时**不回放 `currentTracks`** → 局域网/快源 Emby 直连下 `FILE_LOADED` 抢在 Dart 订阅 EventChannel(在 `await createPlayer` **之后**才 listen)前触发,唯一一次事件投递到 null sink 后**永久丢失** → `_tracks` 整场为空。
- `track-list` 原用 `MpvFormat.NODE` 观察,但 `eventProperty` 没有任何 NODE 重载消费它 → 轨道变化**从不二次上报**。
→ `_selectInternalSubtitleMPV`/`ViaTrack` 拿到空轨道 → 退化成 `sid=auto`,内封字幕(文本+PGS)选不中/选错 → 表现为"都不显示"。**桌面 media_kit 有响应式 `stream.tracks` 持续补发+6 次 sid 校验重试(`mpv_player_adapter.dart:820-878,1144-1197`),所以三端里只有安卓 nativeMpv 炸。**

**修(全在 `MpvPlayerPlugin.kt`,commit 26484fd)**:① `onListen` 建立订阅时立即回放 `currentTracks`;② `track-list` 改 `MpvFormat.NONE` 观察 + `eventProperty(String)` 里 `when("track-list")→loadTracks()` 重解析重推(覆盖 FILE_LOADED 后才就绪的字幕轨);③ 轨道 map 同时带 `"title"` 与 `"label"` 键——选轨匹配器 `player_subtitle_loader.matchMpvSubtitleTrack` 读的是 `t["title"]`,原来只塞 `"label"` → 多条同语言内封轨(简/繁/简日/特效)按标题消歧全失效。**顺带修好 nativeMpv 音轨按正则/首选自动选**(同吃空 `_tracks`)。

**教训**:安卓原生一次性 push 的列表事件(轨道/尺寸)必须给 `onListen` 回放兜底,否则订阅竞态下永久丢事件。诊断"选了轨也不显示"先看 App 日志 `MPV 可用字幕轨道: N 个`——N=0 就是这个竞态,不是字体。

---

**第二层根因(2026-07-08 真机复验后修,commit e71090f)——这才是"内封字幕仍不显示"的最终元凶**:轨道投递竞态(第一层)修好后,app 确实能调到选轨了(日志有 `[NativeMpv] 选择字幕轨道: 1/2`),但**字幕仍不渲染**。真机日志实锤:每条"选择字幕轨道"后 1ms 内必跟 `ERROR mpv/main: Command 'set_property' not found`。根因在 `MpvPlayerPlugin.kt` `selectSubtitleTrack`/`deselectSubtitleTrack`:
```kotlin
MPVLib.command(arrayOf("set_property", "sid", trackId))  // 错!
```
`set_property` 是 **libmpv C API 函数名**,mpv **命令接口里根本没有这个命令** → mpv 拒绝执行 → `sid` 从未真正设上 → 选了字幕轨也不渲染。**音频轨(`selectAudioTrack`)本就用 `MPVLib.setPropertyString("aid", trackId)` 所以一直正常**——这是天然对照组,也解释了"有声无字幕"。修:sid 同样改走 `MPVLib.setPropertyString("sid", trackId)`(deselect 用 `"no"`)。

**诊断 SOP 升级**:安卓原生"选了轨仍不显示",按层排——①App 日志 `可用字幕轨道: N`,N=0=投递竞态(第一层);②N>0 且有 `选择字幕轨道` 却不显示 → **grep 日志 `Command '.*' not found`**,有=原生用错了 mpv 命令接口(命令接口用 `set`/属性接口用 `setProperty*`,别拿 C API 函数名 `set_property` 当命令);③都对再查字体。设属性正确写法:数值/字符串属性一律 `MPVLib.setPropertyString/Int(name,val)`,**不要** `command("set_property",…)`。

---

**第三层根因 = 字体,但上次的字体修法对这版 libmpv 是无效选项(2026-07-08 二次真机复验后修,commit bb9dd04)**:前两层修好后日志已无 `set_property` 报错、sid 能设上,但文本字幕**仍空白**。**二进制符号级实锤**(`libmpv.so` arm64,mpv v0.36):
- libass **已编入**(`ass_library_init`/`ass_render_frame`/`ass_set_fonts` 符号都在),不是缺 libass;
- 但 **`sub-fonts-dir`/`sub-font`/`sub-font-size` 这些选项在这颗 .so 里根本不存在**(精确 `grep -a` 全 miss,而 `sub-visibility`/`sub-scale` 在)→ 之前(以及本条最上面写的)`setOptionString("sub-fonts-dir","/system/fonts")` 是**未知选项、静默 no-op**,字体目录从没设进去(报错还被 `mpv/overflow: log message buffer overflow: N messages skipped` 丢了,所以日志里看不到);
- 这版 **libass 走 fontconfig**(`.so` 含 `fontconfig`/`fonts.conf`/`fontselect` 符号),Android 默认无 fontconfig 配置 → fontconfig 无字体源 → 文本字幕整段空白。位图 PGS 不依赖字体,另说。

**修**:`MpvPlayerPlugin.setupFontconfig()` 在 `MPVLib.create()` **之前**生成一份指向 `/system/fonts` 的 `fonts.conf`(泛族 sans-serif/serif/monospace + 末位兜底都解析到 NotoSansCJK/Roboto/DroidSansFallback)+ 设 `FONTCONFIG_FILE`/`FONTCONFIG_PATH` 环境变量(libass 初始化即读,故必须在 create 前)。`<cachedir>` 显式给 App 私有目录(别设 HOME,免影响其它原生库)。

**教训(重磅)**:**别假设 mpv 选项名在所有 build 都存在**——第三方/精简 libmpv(尤其安卓预编译)可能砍掉或换名。改 mpv 选项前先 `grep -a -o "选项名" libmpv.so` 确认它真在库里,否则 setOptionString 静默失败、你还以为设上了。安卓 libass 字体是 **fontconfig 问题不是 sub-fonts-dir 问题**(那选项在此 build 压根没有)。诊断被 mpv 日志缓冲区溢出挡住时,自己 `emitEvent("log")` 直发关键诊断进 App 日志(已加 `sub-diag` 行:sid/可见性/字幕轨 codec)。**候选修复,待真机复验**(能否上屏只有设备能证)。

---

### mpv 发行版卫生

> 原记忆:`mpv-release-hygiene.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

`native-poc/src-tauri/src/mpv.rs` 的 `Player::new()` 上,2026-07-15 修的三件事:

##### 1. `log-file` 是有真实成本的调试设置,不能无条件开
`set("log-file", ...)` 一旦给路径,mpv 就把日志目标**钉在 MSGL_DEBUG**,`msg-level=all=v`
**管不住它**(证据:日志里全是 `[d]` 行),还会连带把 ffmpeg 的 `av_log_set_level` 拉到 debug
→ 解码器逐 packet 打日志并**同步写盘**。实测:一个文件都没加载、光 mpv init 就写了 247 行 / 24KB。
→ 已改成 `LP_MPV_LOG` 环境变量门控:`set LP_MPV_LOG=1 && LinPlayer.exe`。

##### 2. libmpv 没有配置目录 → 不显式设就**完全没有 shader 缓存**
日志证据:`cache path: '' -> '-'`、`home path: '' -> '-'`。
后果:每次起播把整条 Anime4K 链(最重档 6 pass、VL 模型 143K)重新
glsl→SPIR-V(shaderc)→HLSL(spirv-cross)→D3D 编译一遍,首帧干等一整轮。
→ 已设 `gpu-shader-cache=yes` + `gpu-shader-cache-dir=<cache_dir>/LinPlayer/shader-cache`
(放 app data 不放 %TEMP%:被清掉就等于没缓存)。

##### 3. 查「这个 mpv 认不认某个选项」的正确姿势
`strings` 在本机 Git Bash 里**静默返回 0 行**(不报错!),据此得出的「不认这个选项」全是假的。
用 python 直接扫 DLL,并且**必须带对照组**(拿 `glsl-shaders`/`hwdec` 这种确定存在的先验一下,
方法本身不可信就别信结论):
```python
import re; b=open('src-tauri/libmpv/libmpv-2.dll','rb').read()
found={m.group(0).decode() for m in re.finditer(rb'[ -~]{4,}', b)}
```
注:`mpv.rs` 的 `set()` **忽略 mpv_set_option_string 的返回值** → 选项名写错是**静默无效**,
不会报错。这就是为什么必须先验证选项存在。

##### 已排除的怀疑(别再查一遍)
`libmpv-2.dll` **不是 debug 构建**。112MB 全是静态链进去的代码(ffmpeg+libplacebo+vulkan+
shaderc+spirv-cross+luajit+vapoursynth…),PE 段表无任何 `.debug_*` 段,日志原文
`-Doptimization=3 -Db_lto=true` + `Built with NDEBUG.`。`enabled features` 里的
`debug`/`dxgi-debug-d3d11` 是**编译期可用性标记**,不是运行期开关。

相关:[画质档位口径](player-mpv.md)、[预取代理死锁(已修)](network.md)

---

### 画质档位口径

> 原记忆:`anime4k-denoise-ladder.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

##### 用户定的口径(2026-07-15,推翻此前所有版本)
原话:「为什么要用**还原**呢?不应该是**锐化+去噪**吗」「我也需要**窗口模式下也能锐化/去噪**」
「你真应该看看人家(hooke007/mpv_PlayKit)的滤镜是怎么做的」。

##### ★ 核心:锐化/去噪 ≠ 放大,别糅成一坨
**Anime4K 是放大器**。它每个 CNN pass 都带门槛:
```
//!WHEN OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *
```
输出(mpv 画面区 = **窗口大小,不是屏幕大小**)宽高都要 > 源的 1.2 倍,否则**一帧都不跑**。
真机:源 1920×1080、窗口 1770×1080 → 0.92× → 点什么档位都毫无变化。

PlayKit 的做法才对 —— **锐化的门槛是参数、跟尺寸无关**(实测):
| shader | 门槛 | 窗口下 |
|---|---|---|
| `AMD_CAS_luma_RT`(锐化 2.3K) | `//!WHEN STR` | ✅ 跑 |
| `AMD_FSR1_RCAS_RT`(锐化 4.1K) | `//!WHEN SHARP 4.0 <` | ✅ 跑 |
| `Anime4K_Denoise_Bilateral_*`(去噪) | **无 WHEN** | ✅ 永远跑 |
| `AMD_FSR1_EASU`(放大 10K) | `OUTPUT.w HOOKED.w 1.0 * >` | 仅放大时 |

##### 档位表的三次推翻(2026-07-16 → 07-20 → 09-02 → 09-07)

三家族六档 → 四家族 28 档 → 只剩 Anime4K 一族 8 档 → **六档 A/B/C**。
前三次的具体表已作废,现表见 `core/shaders/shaders.go`。留下来的只有两条不随表变的:

- **shader 自带默认都极保守**(Adaptive 的 `STR` 默认 0.5,只开一半),档位不写参数就吃默认,
  表现正是用户说的「看不太出来」。强度是**档位设计的一部分**,不是给用户拧的旋钮。
- **`glsl-shader-opts` 是全局 K=V 表**:Adaptive / aWarpSharp2 / BCAS 都叫 `STR` 而量纲是
  0~2 / -20~20 / 0~1,叠进同一档必然串味而 mpv 不报错。一档只准挂一个锐化器,有测试钉。

shader 取自 `hooke007/mpv_PlayKit/portable_config/shaders`(AMD/ 与 Anime4K/ 与 NV/,均 MIT)。
那个仓库还有 FSRCNNX/nlmeans/SSim 等,要加档先去那儿翻。
下载法:`curl raw.githubusercontent.com/hooke007/mpv_PlayKit/main/portable_config/shaders/<dir>/<file>.glsl`
(先 `api.github.com/repos/.../git/trees/main?recursive=1` 列路径)。
⚠️ 翻到的东西**先看有没有 `//!COMPUTE`** —— 见本文 2026-09-07 那条。


**画面增强档位要落盘、要起播自动挂回来**(用户 2026-09-12:「画面增强需要支持记忆」)。
起播走 `applyPlaybackDefaults → applySavedShader(shaderLevelFor(prefs, scope))`。

**按剧记**(用户 2026-09-17:「在一个番剧里面选了,后续都是使用该模型进行画面增强」):
在放剧集时选的档位记进 `Prefs.ShaderBySeries["服务器|剧 id"]`(和片头片尾手动设定同一口径),
这部剧以后每一集都用它,关掉(`off`)也记;没记过的剧、电影、本地文件、源用 `Prefs.ShaderLevel`(剧外最后选的那档),
在剧里选**不改**全局那档。scope 在 `play()` 取完观看判据后设、`Stop` / 源起播 / 本地起播清掉 ——
不清的话停播后在设置页改档位会记到上一部剧头上。两条不能省:

- **编译校验放 goroutine。** 那一步要等真的渲染一帧才等得到错误,而起播路径上同步等
  就是白白多出几百毫秒黑屏。
- **自动挂载失败只改进程里那份,不动盘上那一档。** 一次瞬时失败就把用户记住的档位抹掉,
  是最气人的形态。所以只有「用户自己挑」那条路走 `rememberShader`。

##### 别再犯的两个错
1. **抄档位表要认 `git show HEAD:`**。`lib/core/services/anime4k_shaders.dart` 工作区有未提交的
   回退改动,照工作区抄会抄到用户已否掉的东西 —— 犯过。
2. **`count>0` ≠ shader 会跑**。mpv 收下 glsl-shaders 路径只证明路径合法。
   `set_shader_level` 现在双重回读,返回 `ShaderApplied{count, will_run, note}`;
   `shader_levels()` 多返回「窗口是否也生效」,UI 分两组 + 打「需放大」角标,**点之前就说清**。
   这个标志由 `is_upscale_gated()` 从 shader 源现算(扫 `//!WHEN` 里有没有拿 OUTPUT 比尺寸),
   **不是前端写死的名单** —— 换 shader 文件结论自动跟着变。

##### 测试当场抓到的事(说明这类测试值)
`window_ok_flag_matches_shader_gates` 抓到:我照直觉把 FSR 档标成「需放大」,
但 **RCAS 的门槛是参数不是尺寸** → FSR 档在窗口下会退化成「只锐化」,仍有效果。
`when_ratio_matches_shader_source` 抓到:`AutoDownscalePre` 用的是 **NATIVE 基准的区间闸**
(x2 管 1.2~2.0 倍、x4 管 2.4~4.0 倍),不是总闸,别拿 WHEN_RATIO 去套。

##### 另一半真相
[双显卡必须钉独显](player-mpv.md):在这之前,mpv 一直跑在**核显**上,5060 全程没参与 —— 那才是
「非常非常卡」的量级来源。而 [超分失效根因+toast统一位置](player-mpv.md):旧 Flutter 桌面端软件纹理**根本不跑 glsl**,
所以「以前测试不卡」很可能是超分从没真生效过。**这三件事叠在一起,谁都不单独解释全部现象。**

相关:[双显卡必须钉独显](player-mpv.md)、[mpv 发行版卫生](player-mpv.md)、「native-rendering-project」(该条不在本库,多为 Flutter 时代的旧记忆,已作废)

---

### 双显卡必须钉独显

> 原记忆:`hybrid-gpu-must-pin-dgpu.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

##### 症状 → 真凶
2026-07-15 用户报「Anime4K 非常非常卡,我用 mpvplaykit 都没这么卡,你肯定是使用方式有问题」。
mpv 日志第一屏就是判决书:

```
[v][vo/gpu-next/d3d11] Device Name: Intel(R) UHD Graphics   ← 核显!
[v][vo/gpu-next/d3d11] Device ID: 8086:468b
```

**Anime4K 整条 CNN 链跑在 Intel UHD 上,RTX 5060 全程没参与。**
这就是 「native-rendering-project」(该条不在本库,多为 Flutter 时代的旧记忆,已作废) 立项要治的「5060 超分卡」的真正病根 —— 跟档位、跟
media_kit 软件纹理、跟 ANGLE **都没关系**(日志里 angle/egl/opengl **零命中**,走的是原生 d3d11)。

**为什么会这样**:混合显卡笔记本上 D3D11 的默认适配器 = 接显示器的那块(核显)。
LinPlayer.exe 是个新面孔,NVIDIA 驱动的程序配置库里没有它 → 落到默认的「集显」档。

##### 修法(commit 见 native-poc/src-tauri/build.rs + lib.rs 顶部)
```rust
#[cfg(windows)] #[used] #[no_mangle]
pub static NvOptimusEnablement: u32 = 0x0000_0001;          // NVIDIA Optimus
#[cfg(windows)] #[used] #[no_mangle]
pub static AmdPowerXpressRequestHighPerformance: u32 = 1;   // AMD Enduro
```
+ build.rs:`cargo:rustc-link-arg-bins=/EXPORT:NvOptimusEnablement`(两个都要)

**两半缺一不可**:Rust exe **默认没有导出表**,只写 `#[no_mangle]` 驱动看不见;
`#[used]` 不加则 LTO 把静态量整个丢掉。而且缺了任一半**都不报错,只是继续用核显**。

比硬编码 `d3d11-adapter=NVIDIA` 好:不认厂商名、单显卡机器上天然空操作。
(mpv 确实有 `d3d11-adapter` 选项,验证过存在,是备选方案。)

##### 验证方法(必须做,别默认它生效了)
```
set LP_MPV_LOG=1 && LinPlayer.exe     # 日志门控见 [mpv 发行版卫生](player-mpv.md)
```
看 `%TEMP%\linplayer_mpv.log` 里的 `Device Name:`。实测 A/B:
- 改前 `Intel(R) UHD Graphics` / 改后 `NVIDIA GeForce RTX 5060 Laptop GPU (10de:2d19)`
- **release(LTO)版单独验过**一次 —— debug 绿不代表 release 绿(`#[used]` vs LTO)。

还可以直接查 exe 导出表(python 解 PE 即可,别用 strings,见 [mpv 发行版卫生](player-mpv.md))。

##### 教训
用户说「你肯定是使用方式有问题」时他是对的,尽管他猜的机制(ANGLE 合成)不对。
**别因为对方的归因错了就连带否掉他的观察** —— 「mpvplaykit 不卡而你卡」这个对照本身
就说明问题在我们的用法,不在 mpv,更不在档位表。我上一轮把「卡」归给档位抄错
(见 [画质档位口径](player-mpv.md))是**在没量过 GPU 的情况下下的结论**,那条只是次要因素。

相关:[画质档位口径](player-mpv.md)、[mpv 发行版卫生](player-mpv.md)、「native-rendering-project」(该条不在本库,多为 Flutter 时代的旧记忆,已作废)

##### ⚠️ 2026-09-02 补:换成 Go 核心 + C#/Avalonia 之后,上面那个修法**用不了**

导出符号那条路要求**可执行文件**自己有导出表。C#/.NET 的 apphost 没有可写的导出表,
`[UnmanagedCallersOnly]` 导出的是**函数**不是 DWORD 数据符号。照抄过不来。

换成 Windows 10 1803 起的 **per-exe 显卡偏好** —— 就是「设置 → 系统 → 显示 → 图形」
那个界面写的同一处:

```
HKCU\Software\Microsoft\DirectX\UserGpuPreferences
  值名 = exe 的完整路径
  值   = "GpuPreference=2;"      0=系统决定 1=省电 2=高性能
```

实现在 `core/system/gpupref_windows.go`,由 `lp_init` 调 ——
**必须尽早**,因为这个偏好是进程启动时被 DXGI 读走的。C# 侧 `lp_init` 排在
Avalonia 启动之前,所以**当次启动就生效**(实测,不是推断)。

相对导出符号的三个好处:一处同时覆盖 N 卡和 A 卡、不认厂商名、单显卡机器上天然空操作。

**已有值一律不覆盖**:那可能是用户自己选的(笔记本外出时故意钉核显完全合理)。

##### 新栈下的验证方法(和旧栈不同:看的不是 d3d11 的 Device Name)

新栈的画面路径是 `vo=libmpv` + **OpenGL** render API,而 Avalonia 在 Windows 上的
GL 后端是 **ANGLE(跑在 D3D11 上)**。所以判据在 `GL_RENDERER` 这一行,
不是 `[vo/gpu-next/d3d11] Device Name:`:

```bash
LP_MPV_LOG=D:/x/mpv.log LP_SHADER=ak_sharp bash scripts/selfcheck-win.sh q "player:mv-1" 片子.mp4
grep GL_RENDERER D:/x/mpv.log
```

2026-09-02 四轮实测(同一台笔记本,Intel UHD + RTX 5060):

| 显卡偏好 | GL_RENDERER |
|---|---|
| `0` 系统决定(**= 修之前的行为**) | `ANGLE (Intel, Intel(R) UHD Graphics …)` |
| `1` 省电 | `ANGLE (Intel, Intel(R) UHD Graphics …)` |
| `2` 高性能(我们写的) | `ANGLE (NVIDIA, NVIDIA GeForce RTX 5060 Laptop GPU …)` |
| 整条删掉 → 代码自己写 | `ANGLE (NVIDIA, …)`,**同一次启动** |

第一行是关键:**它证明倒退是真的**,不是推断出来的。

##### 顺带:着色器编译缓存也得自己给路径

`libmpv` 没有配置目录,不显式给 `gpu-shader-cache-dir` 它就**静默不缓存**,
每次起播重编整条 CNN 链(开着超分时第一秒卡一下)。

- 两个选项都要给:`gpu-shader-cache=yes` + `gpu-shader-cache-dir=<绝对路径>`。
  实测 libmpv client api 2.5 两个都收(返回 0);不存在的选项名返回 **-5**
- 目录**必须自己 MkdirAll**,mpv 不建
- ⚠️ 别和 `.glsl` 源文件同目录。`player.setShaderLevel` 把我们自带的 17 个
  `.glsl` 落在 `cache/shaders`;第一版把编译缓存也指到那里,两者互相误伤且不报错。
  已分成 `cache/shaders`(源)/ `cache/shader-bin`(产物),有测试钉住
- 判据是**真有字节落盘**:跑一轮 `ak_sharp` 之后 `cache/shader-bin/` 有 10 个哈希命名的
  文件、144 KB。⚠️ **没起播的话它永远是空的** —— 我第一次拿一次没真起播的运行
  下结论,差点判成「这条路不支持磁盘缓存」

##### ⚠️ 换渲染后端 = 换着色器方言(2026-09-02,比上面几条都贵)

接完画质档位面板,真机一开 `ak_sharp`,**整屏变纯蓝**。而当时:

- `mpv_set_option_string("glsl-shaders", …)` 返回 **0**
- `player.setShaderLevel` 回 `count=2, will_run=true`
- 没有任何属性、任何返回码显示出错

唯一的线索在 mpv 的 **error 级日志**里:

```
ERROR: 0:58: 'linearize' : no matching overloaded function found
```

**根因**:黄金实现(Rust)用 `vo=gpu-next` —— 那是 **libplacebo**。
新栈是 `vo=libmpv` + OpenGL render API,走的是 mpv 旧的 `gl_video`,
而 Avalonia 在 Windows 上的 GL 后端是 **ANGLE**,着色器按 `#version 300 es` 编译。
`linearize()` 是 libplacebo 提供的,这条路上**没有**。

于是那一趟 pass 编译失败,mpv **继续渲染**,输出一片纯色。

坏的**只有一个文件**:`AMD_CAS_luma_RT.glsl`,牵连 4 档
(`ak_sharp` / `fsr_sharp_l` / `fsr_sharp_m` / `fsr_sharp_h`)。

⚠️ 我一开始按「哪些文件出现了 `linearize`」推断说 5 档,把 `sh_bcas`
(`AMD_BCAS_RT.glsl`)也算了进去 —— **实测那个是好的**。同一个函数名,
一个挂 LUMA 一个挂 MAIN,mpv 给的前置声明不一样。**推断不算数,得跑。**

##### ⚠️ 全表扫描有个假绿:mpv 每个着色器程序一个进程里只报一次错

写了个 `LP_SHADER=all` 把 28 档挨个挂一遍,结果只有 `ak_sharp` 报 BAD,
后面同样挂 `AMD_CAS_luma_RT.glsl` 的 `fsr_sharp_l/m/h` **全报 ok**。
单独起一个进程只跑 `fsr_sharp_m` —— 照样炸,10 行错误。

**所以「等错误冒出来」这一招只挡得住第一档。** 用户切到第二档就会拿到一屏纯色,
而我们还告诉他「已启用」。

修法:一档失败时把坏文件记下来,后面挂着它的档位**连试都不试**直接退回。
⚠️ 归罪**必须能唯一定位才算** —— `ak_sharp` 是「去噪 + 锐化」两个 pass,
坏的只是后者;把去噪也拉黑,`ak_denoise_h` 这个好档就被冤枉了。
**误判比漏报贵**:漏报只是少挡一次,误判是无故关掉用户能用的功能。
规则是「失败档的文件里,除掉已被别的成功档证明过的,剩恰好一个时才拉黑」。

##### 处置:档位表砍到只剩 Anime4K 一族

用户 2026-09-02:「有一个 Anime4K 足以了超分,其他的不需要」。
四族 28 档 → 一族 8 档,删掉 FSR / NVIDIA / 锐化专精三族和 8 个 `.glsl`(-2239 行)。
(这推翻了 2026-07-16「加入 FSR、专门的 NV 滤镜」那次决定,是用户重新拍的板。)

`ak_sharp` **不是删掉而是换 shader**:`AMD_CAS_luma_RT` → `Adaptive_sharpen_lite`
(同轮扫描验过能编译)。它是唯一「窗口模式也生效」的锐化+去噪档,
而用户的基线是「其实清晰最重要的是锐化」。⚠️ 换 shader 要跟着换参数量纲:
CAS 的 `STR` 是 0~1,Adaptive 是 0~2,所以 0.85 → 1.30。

新表真机全扫 **8/8 ok**,mpv 日志里编译错误 **0 条**。

**少一族就少一族要在每次换后端时重验的东西** —— 这才是删的主要收益。

**教训:着色器不是数据,是代码。** 「档位表照抄过来了」不等于「档位能用」——
换渲染后端就得**重新验一遍每一档**,而且这类失败编译绿、单测绿、返回码绿。

处置:`core/player/shaderguard.go` 订阅 error 级日志,编译失败就**自动退回关闭**并
把 mpv 原话交给 UI。宁可告诉用户「这档在你机器上用不了」,也不能给他一屏纯蓝
还写「已启用」。⚠️ 分类器**只认**着色器编译/链接失败 —— 放宽成「含 failed 就算」
会把 `Loading failed.`、CUDA 探测、`auto_profiles` 这些正常日志误判,
无故关掉用户好好的档位(已有测试钉住)。

⚠️ Linux 那边 GL 后端不是 ANGLE,**要单独验**,别拿这台的结论替它签字。

##### 这一整条的机制教训:**mpv 收下选项 ≠ 选项生效**

Go 版的 `ensureMpv` 原来和 Rust 版一样把 `mpv_set_option_string` 的返回码
`_ =` 掉了(N13 记的「静默失效的机制源头」)。现在逐条看返回值,`< 0` 打 error。
配套一条测试拿**临时 mpv 句柄**把选项表逐条试一遍 —— 选项名写错、
或者 libmpv 升级把它改名,这条会红,而不是等用户报「超分没效果」。

---

### 超分失效根因+toast统一位置

> 原记忆:`superres-and-toast.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

**⚠️重大更正（2026-07-13 真机定论，见 「native-rendering-project」(该条不在本库,多为 Flutter 时代的旧记忆,已作废)）**：本文下方多处把「5060 都卡」
归因于 media_kit→ANGLE→Flutter 纹理出画管线——**这是错的**。真机实测：Windows 原生渲染(绕开 ANGLE)
最大化后一样卡，而不开超分最大化不卡 => **卡=超分 shader 的 GPU 算力随输出分辨率暴涨，与渲染管线无关**。
且 **Anime4K 去噪梯子已整体换成 ArtCNN**（luma-only + WHEN 分辨率门控 + 单文件，又轻又清晰；键名沿用
modeA..modeAC）。下面关于 Anime4K 六档链路/软硬件纹理/ANGLE 归因的内容多已过时，仅留档。当前超分事实源
仍是 `anime4k_shaders.dart`（内容已是 ArtCNN）。

**超分(Anime4K)两大坑**（2026-07 修复，⚠️部分已被上方更正推翻）：
1. **PC(media_kit Win/macOS)无效**：desktop 为消除弹面板闪屏，VideoController 用了
   `enableHardwareAcceleration:false`(软件纹理)。media_kit `video_output.cc` 据此选
   `MPV_RENDER_API_TYPE_SW`，而 libmpv 软件渲染器**根本不跑 GLSL user shader**
   (`glsl-shaders`)→超分开了画面零变化、连闪都不闪。修法：`MpvPlayerAdapter.initialize`
   收 `superResolutionLevel`，开超分时强制走硬件纹理(`softwareTexture = (mac||win) && !wantsShaders`)；
   纹理模式建 VideoController 时定死。**⚠️别整段重建播放管线切超分**——那会把已缓冲流从头
   重新下载、进度跳回记录处、爆卡(用户明确反感)。现方案:软件纹理下开超分只**存档位+提示
   「下次播放生效」**(不 reload),下次起播用硬件纹理自然带 shader;已是硬件纹理(起播就带超分)
   则档位/开关全 live apply。判定靠 `MpvPlayerAdapter.isSoftwareTexture` →
   `VideoPlayerService.superResolutionCanApplyLive`(旧 superResolutionRequiresReinit 已删)。
   **⚠️更新(2026-07 用户要「三端一致·即时生效」)**：桌面三端已全部**恒硬件纹理**
   (`const softwareTexture = false;` + `VideoController(_player!)` 不传 config)——超分/六档
   中途随手 live 切换、关掉立即不卡,不再「下次播放」。代价按平台:Windows 弹面板闪一下回归;
   **macOS 硬件纹理黑屏风险(GL 上下文泄漏,反复播放后第 N 次黑屏有声)回归**——用户已知并要真机测;
   若 macOS 真机黑屏,`mpv_player_adapter.dart` softwareTexture 那行按注释换回
   `Platform.isMacOS && (_glslShaders?.isEmpty ?? true)` 并恢复 VideoController 三元 config 即回退
   (macOS 超分退「下次生效」)。见 「mediakit-texture-flash-swtexture」(该条不在本库,多为 Flutter 时代的旧记忆,已作废) 「macos-no-video-hwdec」(该条不在本库,多为 Flutter 时代的旧记忆,已作废)。
   **卡的根因是渲染栈固有**:media_kit=`vo=libmpv`+渲染API→mpv 画进离屏纹理再由 Flutter 合成呈现
   (非原生直接上屏),shader 走 ANGLE(GLES→D3D11 翻译)+Windows 强制 `hwdec=auto-copy` 每帧拷回。
   同 libmpv 内核但「出画」这段比原生 mpv/PotPlayer(原生 D3D11 零拷贝)重,无法抹平到原生;
   零拷贝解码(去 auto-copy)可省拷回但部分显卡 dxva2-egl 崩,只能做实验开关。
   **档位三端都是 6 档 A/B/C/AA/BB/AC**(桌面菜单曾漏成 3 档,f6461788 只补了移动端;已补齐)。
   **⚠️2026-07-11 定稿:六档=纯去噪放大梯子(无 Restore)**——用户实测 **Restore CNN(锐化/还原)在动态
   画面产生边缘振铃/拖影且最吃显卡,明确不要**;超分需求就是 Anime4K 的 Upscale+Denoise(去噪放大)。
   故六档全部剔除 Restore,做成核显→壮机的算力梯子(键名沿用 modeA..modeAC 不变,持久化/UI 兼容):
   modeA=去噪S(核显) / modeB=去噪M / modeC=去噪L / modeAA=去噪叠加M / modeBB=去噪叠加L /
   modeAC=去噪叠加VL。链路:Clamp→Upscale_Denoise(x2)→AutoDownscalePre_x2/x4→〔叠加档:二次 Denoise_M〕→Upscale_CNN_M。
   assets 只保留去噪梯子用到的 9 个 shader(Denoise S/M/L/VL + Upscale_CNN S/M + Clamp + ADP x2/x4),
   **Restore/Soft/大 Upscale 全部 git rm 清掉**(之前"官方模式"版加的现已无用)。映射单一事实源仍
   `anime4k_shaders.dart`;标签三端:desktop levels+_anime4KLevelLabel、mobile _showAnime4kPanel(TV 无超分)。
   **别再往里塞 Restore/锐化**——用户点名不要。曾试过:尺寸梯子(A=S/B=M/C=VL,C卡)→官方算法模式(含 Restore,拖影),均废。
   **软解(hwdec=no)会让 OSD 闪回归→别用,保持硬解 d3d11va**(见 [桌面双声音/孤儿播放器](player-mpv.md))。
   卡的真凶在 media_kit→ANGLE→Flutter 纹理出画管线(硬解帧每帧 map 进 GL 的 interop),非 shader/GPU(5060 都卡),
   去噪梯子给 S 档兜核显、减少 CNN 层数缓解,但天花板仍是出画管线,除非上原生渲染。
   **零拷贝解码实验开关(2026-07)**:Windows media_kit hwdec 默认 `auto-copy`(每帧 GPU→内存拷回,卡的
   一大来源);更多菜单加「零拷贝解码(实验)」→ live 切 `d3d11va`(与 ANGLE 同 D3D11 后端,免拷回,治卡/闪)。
   会话级不落盘(个别显卡 d3d11va-egl 崩,重启回默认防 brick)。VideoPlayerService._zeroCopyHwdec 线程给
   MpvPlayerAdapter.applyZeroCopyHwdec;仅 Windows+mpv 显示。用户实测:开零拷贝后卡/闪明显缓解。
   注意:Anime4K 是动画专用锐化,真人内容几乎看不出效果但仍吃 GPU(=开了爆卡却「没变清晰」是正常)。
2. **Android(原生 mpv)无效**：`native_mpv_player_adapter.applySuperResolutionLevel` 只存
   level，从没有 level→shader 映射(映射当时只在 media_kit 适配器里)，且被 `_superResolutionEnabled`
   门控(UI 从不置 true)→永远没 shader 可放。修法：档位→shader 抽到
   `lib/core/services/anime4k_shaders.dart`(kAnime4KShaderPresets + resolveAnime4KShaderPaths
   落地 asset 成文件路径)，两端共用；原生适配器 initialize + applySuperResolutionLevel 都解析并应用。

档位→shader 单一事实来源已是 `anime4k_shaders.dart`，别再往适配器里复制映射。

**三端 toast 位置约定**：`AppToast`(移动/桌面,`lib/ui/widgets/common/app_toast.dart`)加了
`AppToastPosition{topCenter,lowerCenter}`，**默认 lowerCenter(中部偏下)**——非播放页统一用默认；
**播放页传 `position: AppToastPosition.topCenter`**(顶部居中不挡底部控件)。TV 侧 `TvToast` 同理加
`top` 参数(默认中部偏下,播放页 `top:true`)。全库 ~150 处裸 `ScaffoldMessenger.showSnackBar`
已换成 AppToast；剩极少数(捕获 messenger 跨 await 无 mounted 守卫 / SnackBarAction / floating)
故意保留 SnackBar。新增提示别再用裸 SnackBar，走 AppToast/TvToast。见 [别过度解读需求](methodology.md)。

---

### Dolby auto decode

> 原记忆:`dolby-auto-decode.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

**杜比视界自动切换 gpu-next + 软解**（`dolbyAutoGpuNextSwProvider`,key `linplayer_dolby_auto_gpu_next_sw`,**默认 true**,可关）。检测到当前视频流为 DV 且内核为 mpv 系(media_kit / 原生mpv)时,强制 `hwdec=no`(软解) + gpu-next 渲染——硬件 mediacodec 解 DV 丢 RPU 会偏色,gpu-next(libplacebo) 软解链路才能正确映射 IPT-PQ。

- **DV 判定**在 `MediaStream.isDolbyVision`(lib/core/api/api_interfaces.dart):VideoRangeType 含 DOVI / 编解码标签 dvhe·dvh1·dav1 / 标题含 "dolby vision"·"dovi"。新增 `videoRange`/`videoRangeType` 字段,解析见 emby_api.dart `_parseMediaStream`。
- **接线在三端播放页**各自构建 initialize 参数处:player_screen_state.dart(移动)/tv_player_screen.dart(TV)/desktop_player_screen_state.dart(桌面)。`autoDvMode` 命中则 `hardwareDecoding=false`、nativeMpv 的 `useGpuNext=true`、mpv 的 `dolbyVisionFix=true`。
- **桌面 media_kit 走 vo=libmpv,没有独立 gpu-next vo**,无法对 DV RPU 做 libplacebo 映射;退而用软解 + `dolbyVisionFix` 的色彩提示/色调映射(target-colorspace-hint/tone-mapping=spline/hdr-compute-peak)。注意:`dolbyVisionFix` 之前在 mpv_player_adapter 里是**死参数**(只接收不应用),本次才让它真正生效。
- **设置项三端都加了**:settings_player.dart(移动/桌面,仅 mpv/nativeMpv 显示) + tv_settings_screen.dart;并入备份导出/导入(settings_screen.dart `dolbyAutoGpuNextSw`)。

**安卓原生 mpv 纯软解卡顿调优**(MpvPlayerPlugin.kt `setMpvOptions` 的 `hwdec=no` 分支)——4K HEVC/DV 软解极吃 CPU,默认配置移动端会严重卡顿:`vd-lavc-threads`=核心数(coerceIn 2..16,mpv auto 在部分机型只用一半核)、`vd-lavc-skiploopfilter=nonref`(跳非参考帧去块滤波,省 CPU 肉眼无损,是能否跑实时帧率的关键)、`vd-lavc-fast=yes`、`vd-lavc-dr=yes`、`framedrop=vo`。另外 gpu-next 的 `hdr-compute-peak` 改为**仅硬解开**(`if(hardwareDecoding)"yes" else "no"`):逐帧 GPU 直方图在移动 GPU 很贵,软解时 CPU 已满再叠加会卡。

相关:「android-storage-and-mpv-logs」(该条不在本库,多为 Flutter 时代的旧记忆,已作废) [Android mpv subtitle fonts](player-mpv.md)

---

### FFmpeg magicyuv CVE

> 原记忆:`ffmpeg-magicyuv-cve.md` · 类型:`project`
>
> ⚠️ 本条含 Flutter 时代 / `native-poc/` 时代的路径。2026-07-19 仓库重构后这些路径已作废(换算表见 [仓库结构(2026-07重构后)](build-release.md))。**原文按要求原样保留,未做改写。**

CVE-2026-8461 "PixelSmash":libavcodec `magicyuv` 解码器堆越界写(恶意 slice_height),恶意 AVI/MKV/MOV → 崩溃/RCE。FFmpeg 8.1.2(2026-06-17)修复。

**本项目受影响面与处置:**
- **桌面(Win/Linux/macOS)= media_kit→libmpv**:Windows 钉 shinchiro 完整版(`magicyuv` 已编入)→ **受影响**。修复:`mpv_player_adapter.dart` 在 Player 初始化处 `setProperty('vd', '-magicyuv')` 拉黑该解码器(mpv `common/codecs.c` 语义:`-<decoder>`=排除,其余仍自动选)。一行即跨三桌面端消除攻击面,不等 libmpv 重打包。
- **Android / Android-TV = 原生 MPV 插件**:media-kit libmpv 用解码器白名单构建(`--disable-decoders` 再选择性 enable),`magicyuv` 未编入 → **本就不受影响**。
- **标准独立 ffmpeg**(CI 内置 + 运行时下载,仅 Whisper 抽音用)走 `-vn` 纯音频 → **非此视频解码器 CVE 的向量**。
- **残留**:tvOS(`apple_tv/`)用 **MDK**(非 mpv),无 mpv 式 per-codec 排除,待 MDK 自带 ffmpeg 升级;`windows/scripts/upgrade_libmpv_for_pgs.ps1` 的 shinchiro 钉版 `20260610` 早于修复,**待 shinchiro 发布 ≥20260618 构建后把 DownloadUrl 升上去**(脚本注释已标)。

**Why:** 预编译 libmpv 自带旧 ffmpeg,无法在本仓库重编;运行时拉黑单个冷门解码器是最快、跨端、零依赖的根除手段。
**How to apply:** 新增/换播放器内核时,确认走 File-browse sources(本地 sources.md,未入公开库) 的播放路径仍带 `vd=-magicyuv`;升级 Windows libmpv 钉版后该兜底仍可保留(无害)。

---

### ☠☠ `evFileLoaded = 6` —— 6 是 START_FILE,8 才是 FILE_LOADED

Go 版把 mpv 事件 id 抄错了一个数,于是 `onFileLoaded()`(里面挂**外挂字幕**)
在文件还没打开的时候就被调用,`sub-add` 回 -12,而那条错只进日志 ——
表现是**外挂字幕挂了等于没挂,一句报错都没有**。

这正是本文件 `loadfile 异步吞掉 sub-add` 那一条记过的坑,换栈时又踩了一遍,
只不过这次的形态是**常量写错**而不是时序写错。

实测(ctypes 直打 `build/core/libmpv-2.dll`,`loadfile` 之后的事件顺序):

```
[6, 8, 17, 17, 21]   START_FILE → FILE_LOADED → VIDEO_RECONFIG ×2 → PLAYBACK_RESTART
```

已加一条钉子测试 `core/player/event_ids_test.go`,期望值是**实测**来的不是抄文档的;
注入改回 6 → `MPV_EVENT_FILE_LOADED 应当是 8,实得 6`。

★ 一般化:**照抄的常量表要有一条钉子**。这类错编译期看不出来、运行期不报错,
只是某个功能默默不工作。

### 无窗口取一帧:`screenshot-to-file` 能用,`screenshot-raw` 在这版上不能

给进度条做缩略图要起第二个 mpv 实例、无窗口解一帧出来。实测(mpv v0.41.0-744):

| 做法 | 结果 |
|---|---|
| `vo=null` + `screenshot-raw [video\|subtitles\|video bgr0\|无参]` | **四种全部回 -4** |
| `vo=null` + `screenshot-to-file <path> video` | **可用**,174KB PNG |
| 再加 `vf=scale=320:-2` + `screenshot-format=jpg` + `screenshot-jpeg-quality=80` | 320×180、**7.8KB、单张 9~12ms** |

配套要点:

- `seek <t> absolute+keyframes`,**不要 exact** —— exact 要从前一个关键帧解到目标帧,
  慢一个数量级,而缩略图差几秒没人看得出来;
- 截图前**先删目标文件**:不删的话截图失败时读到的是**上一张**,而它长得完全正常;
- 收到 `PLAYBACK_RESTART` **不代表跳到了**,还要读 `time-pos` 核对落点(见 ui-desktop.md);
- 这个实例的选项要带 `config=no` / `load-scripts=no`:否则会吃到用户的 `mpv.conf`,
  里面可能带 `vo` 或滤镜。

## OSD 自动收起把自己变成了一个振荡器 — 2026-09-04

用户:「鼠标停在播放控制按钮(比如音量)上一会儿,鼠标会不断闪烁,
音量调节进度条也会不断弹出又收起」。

这不是两个 bug,是**一个环**:

1. 三秒不动 → 轮询调 `ShowOsd(false)`;
2. `ShowOsd` 把 `_bottom.IsHitTestVisible` 置 false ——
   控制条**从指针底下撤走了** → Avalonia 立刻发 `PointerExited`;
3. 那一发 Exited 把 `_osdHover` 抹成 `false`(它正是靠 Entered/Exited 维护的),
   顺带把音量条收回去;同时 `Cursor` 被设成 `None`,鼠标消失;
4. 指针抖一下(手放在鼠标上就一定会抖)→ `PointerMoved` → `ShowOsd(true)`
   → 控制条回来 → 音量条又展开、鼠标又出现;
5. 回到第 1 步。

★ 根因一句话:**用命中测试维护的悬停标志,去决定要不要关掉命中测试**。
那是个环,而环里每一步单看都是对的。

★ 解法不是加防抖,是**换判据**:记下最近一次指针坐标,
在两条 OSD 的矩形里做一次 `Rect.Contains` —— 和命中测试无关,
收起 OSD 影响不了它,环就断了。

★ 同类嗅探:凡是「隐藏 / 禁用某控件」的代码里读了**由该控件的鼠标事件维护的状态**,
都要按这个模式查一遍。

## 进度条缩略图有一条没人说出来的硬前提 — 2026-09-04

用户:「本地已经缓存了缩略图内容,但播放时依然不生效」。

`player.thumbnail` **只从本地字节里解帧**(`localSource()`),而本地字节只有两种来源:

1. 这条流走了**预取代理** —— 也就是那台服务器开了「多线程加载」;
2. 本地文件 / 已下载的文件。

「多线程加载」是**按服务器手动开**的、默认关。所以对绝大多数用户,
这个功能从上线起**一次都没工作过** —— 而界面上没有任何东西说明为什么:
划过进度条只有一行时间,看着就是「这个功能没做」。

★ 「没缓存的不能用缩略图」这条规矩本身是用户自己定的(2026-09-03),没有改。
改的是**沉默**:取不到图且 `cached_kind == "none"` 时,在 OSD 上说一次
「要在设置 → 多线程加载里给这台服务器打开」。一场播放只说一次
(鼠标划过进度条一秒钟能触发几十次)。

★ 通用口径:一个功能有**隐式前提**而前提不满足时静默什么都不做,
等价于这个功能坏了。前提必须在失败的那一刻说出来,不能只写在代码注释里。


## 缩略图请求要**丢过期的**,不是去重 — 2026-09-04

用户:「在我滑动缩略图的时候,视频会不断卡顿,画面不断抽搐」。

上一版是「每一格各发各的,靠 `_busy` 去重」。听起来像限流,其实**一点流都没限**:
去重的粒度是**格**(整条时间轴 300 格),而拖一趟进度条会扫过几十上百格 ——
于是几十上百个请求一起压到核心层那把 `thumbs.mu` 上排队。每张要 seek + 解码 + 落一次盘,
而这几秒里:

- 那台第二个 `vo=null` 的 mpv 实例一直在满负荷解码,抢的是正片的解码线程;
- 它在环形缓存文件上**满文件随机读**,而正片正在同一个文件上顺序读写。

★★ 关键在于:**队列里除了最后一个,其余全都已经没人要了** ——
鼠标早就划过去了。所以正确的动作不是「限流」,是**丢掉过期请求**:
排队位只留一个,新的直接顶掉旧的;同一时刻只解一张。

★ 实测(自检,1800s 片子,扫过 91 格):
  - 新:**真发 2 次请求**,1.5 秒内答完;
  - 旧:同一次扫描,**150 秒都没排完**(每次打不开文件要走一遍 10 秒超时)。
★ 手停下来之后最后那一格照样会被解出来,用户看到的图一张都不少。
★ 门禁 `LP_PREFETCH=1 LP_THUMB=1`:判据是**请求数**,不是「有没有图」——
  两种实现都有图,差别在扫过去那一路上有没有把 CPU 和磁盘占满。

## 只有 Opacity 的淡出,在半透明蒙版上读不出来 — 2026-09-04

用户 2026-09-03 说「播放页的上下栏不会渐隐渐显」,做了一版 200ms 的 Opacity 过渡;
2026-09-04 又说「**还是**不会」。

自检(逐帧采 `Opacity`)当时是绿的 —— 属性确实在动。问题是**动得看不出来**:
一块本来就半透明的黑色渐变蒙版,200ms 从 1 淡到 0,在亮场景里几乎读不到。
而那段注释自己写着「出场 160 / 退场 260」,代码里却是同一个 200 —— 注释在撒谎。

★ 两件事一起改才够:①出场 170 / 退场 420(退场慢一倍多才读得出「让开」);
  ②上下栏各自**滑出画面** 14px。位移是「离开」最直白的语言,而且它不依赖蒙版的透明度。
★ Avalonia 的 `Transitions` 是一条属性一套参数,**没法两个方向不同时长** ——
  出场退场两套时长靠**改值之前先换 Transitions 对象**。顺序反了就还按上一次那套跑。
★ 门禁要**同时采 Opacity 和位移**:只采一个的话,另一个静止了也看不出来。
  实测退场 24 帧里位移经过 24 个中间值(0 → 13.2px)。


## 「没有新帧就不渲」= 往屏幕上推黑帧 — 2026-09-05 定论

**Avalonia 的 `OpenGlControlBase` 不保证 FBO 的内容跨帧留存。** 所以

```csharp
if (Native.lp_gl_wants_redraw() != 0) { render(); }   // ☠ 跳过的那一帧是黑的
```

这个「省 GPU」的优化,每跳一个合成帧就往屏幕上推一帧黑。**这是「画面抽搐」的根因**,
和授时无关。现在无条件每帧都渲(`LP_SKIP_REDRAW=1` 回到旧行为,用来 A/B)。

### 怎么验出来的 —— 直接问 GL,别靠现象反推

靠现象反推错了两轮(先怀疑授时、再怀疑合成线程被堵),两轮都改错了地方。
最后是 20 行一次性实验给的答案:第 30 帧 `glClearColor(1,0,0,1)` 涂纯红,之后什么都不画,
每 10 帧 `glReadPixels` 读回左下角一个像素:

```
第 40 帧 (255,0,0)   第 50 帧 (255,0,0)   ← 还留着
第 60 帧 (0,0,0)     第 70…120 帧 全是 (0,0,0)   ← 黑了
```

**留两帧然后变黑** —— 合成表面是轮换的,轮完一圈就拿到一块没画过的。
★ Avalonia 升级之后这个前提要重验,方法就是上面这 20 行。

### 它解释了此前**全部**的报告

| 场景 | 合成帧里画到的比例 | 用户报告 |
|---|---|---|
| block=1 正常播放 | 98%(mpv 把合成线程拖成视频帧率,几乎每帧都画) | 正常 |
| block=1 **慢放 0.25×** | **9%** | 「慢放时画面会不断闪烁」 |
| block=1 **暂停** | **0%** | 「哪怕我暂停了画面还是在抽搐」 |
| block=0 + 自己排程 正常播放 | ~30% | 「怎么正常播放也会画面抽搐了」 |
| 打开设置面板 | 面板逼着合成变密 → 黑帧更密 | 「打开面板一直抽搐画面」 |

2026-09-04 那次把 block 改成 0 被当天打回,归因写的是「授时没了」——
**大概率也是这件事**:block=0 之后合成不再被 mpv 拖慢,跳过的比例从 ~0% 跳到 ~70%。

### 门禁

`LP_SELFCHECK_STUTTER=1` 的 `[黑帧]` 一行:**合成帧数 vs 真正 render 的次数**,
低于 90% 就红。宿主侧数 `MpvGlView.GlFrames`,核心层数 `renderCalls`,两边对账。
反向注入 `LP_SKIP_REDRAW=1`:慢放 9%、暂停 0%,离阈值九倍。

★ 阈值不写 100%:两个计数器不在同一瞬间读,差十来帧是采样错位。
  真出问题差的是**数量级**。

## 授时:mpv 在合成线程上等 — 已量清楚,默认没动 — 2026-09-05

`block_for_target_time=1` 的语义是「在 `mpv_render_context_render` 里阻塞到该帧的呈现
时刻」,而这个调用跑在 Avalonia 的**合成线程**上。实测(854×480 24fps,Release):

| | render 单次耗时 | 合成帧率 |
|---|---|---|
| block=1(默认) | **均 38.3ms** | 正常速 **24 fps** |
| block=0 + 每帧都渲 | 均 0.4ms | 正常速 **94 fps** |

24 帧/秒 × 38ms = **合成线程 90% 的时间堵在这一行**,界面上每个按钮的悬停高亮、
OSD 淡入淡出都只能以 24fps 跑。

★★ 我一度把这条当成「播放页的按钮每个都会闪」的机制,**2026-09-05 实测否掉了**:
  同一段片子、OSD 钉住、连拍按钮行 —— block=1(合成 24fps)差 2.94%,
  block=0(合成 94fps)差 5.85%,**合成帧率根本不影响那个数**。
  按钮闪的真因在 UI 侧(scrim 太透),见 docs/lessons/ui-desktop.md。
  这里剩下的是「播放时全站 UI 动效只有 24fps」——真实但和「闪」不是一回事。

**默认仍然是 1。** 代价已经量清楚了:`[提前量]` 那一行 —— mpv 提前约**一帧**把画面
交给我们(24fps 片子实测 36~38ms,且**改不掉**:`video-latency-hacks=yes` 试过,
render 照堵 38.4ms)。block=1 时 mpv 在 render 里等掉这段,画面按点上屏;
设成 0 就是**画面比声音早 36ms** —— 均匀偏移不是抖动,但它在 ITU 的可察觉阈值
(视频超前 45ms)边上。

换来的是合成线程从 24fps 回到 94fps。**而这笔交易买不到「按钮不闪」**(见上面那条
被否掉的归因),所以不值得默认承担那 36ms。留成 `LP_BLOCK_FOR_TARGET_TIME=0`。

★ 想两头都要,得自己接管授时:按 `target_time` 决定何时推进,同时**自己留一份画面**
  在跳过的合成帧上贴回去(宿主 FBO 不留内容)。没做 —— 那是几十行会静默黑屏的 GL。

★ 试过「门控 + 仍让 mpv 做最后一小段等待」的折中 —— 那次的测量是在纳秒/微秒单位
  算错的情况下做的,**结论作废,没有真正验过**。要重开这条路先把那次重做。

### ☠ `target_time` 的单位是纳秒

`render.h` 注释写的是 "same unit and base as **mpv_get_time_us()**",**实测不是**
(libmpv client API 2.1):`target=996995600` 对 `now(us)=1000543` —— 按 us 算差 99 万毫秒。

按微秒算的那一版,误差全被护栏丢掉,排程**整个空转**,而自检**照样打绿**:
样本数为 0 时统计量全是 0,判据「|偏置| < 10ms」对 0 成立。两条教训:

1. 量纲护栏不许静默丢样本 —— 对不上就退回安全行为并且**吼一声**;
2. **统计类判据必须先判样本数**,`n=0` 时几乎每条「差值很小」的断言都成立。

### 「画面抽搐」的判据换过三版,前两版都被反向注入打掉

| 判据 | 怎么死的 |
|---|---|
| mpv 的 `avsync` | 注入之后是绿的 —— 它量的是音频时钟,不是画面上屏的时刻 |
| 相邻两帧间隔抖动 `frame_jitter_ms` | 好的坏的**都是 6.3ms**,分不出来 |
| **合成帧里画到的比例** | 现在这条。注入时 9% / 0%,正常 >95% |

★ 平均帧率永远是对的(24.1 / 24.2)—— **抽搐的时候平均帧率是对的**,拿它当判据也量不出来。

## 缩略图:本地自己解,只用已缓存的字节 — 2026-09-04 定论

★★ **用户拍的板,别再翻**:

> 「缩略图要自己生,所以我才说要在缓存内才做缩略图,不然给服务端的压力太大了。
>  用章节图或者 BIF 这种做多人观看的 Emby 不会搞的,不然服务端压力很大。
>  **宁愿是增加客户端的包体积,也不干损服务器的事情。**」

同一天我做过一版「向服务端要预览图」,**当天就撤了**。撤的理由不是技术不通 ——
那条路实测是通的 —— 是**方向反了**:把本该客户端花的算力摊到一台多人共用的服务器上。
这类判断**不在代码里**,只能问人;而我当时是从「用户说不要 mpv 实例」倒推出来的,
倒推错了方向。

★ 顺带纠正一条:用户说「不要 mpv 实例」时给的替代方案是 thumbfast / mpv_thumbnail_script,
  而**那两个脚本自己就是再起一个 mpv 进程**(thumbfast 用 `--vo=image` 起隐藏 mpv,
  把 BGRA 写进命名管道)。把这件事讲清楚之后用户当场说「没问题的」——
  **他反对的不是「多一个 mpv」,是那一版的表现**。
  遇到「用户给的方案和他的目标看着矛盾」时,先把事实摆出来,别自己替他选一个。

### 包里到底有没有 FFmpeg —— 有,但调不到

```
libmpv-2.dll                             112 MB(静态链着整套 FFmpeg)
exports.txt 里 mpv_* 符号                   54 个
exports.txt 里 avcodec_/avformat_/sws_       0 个
```

★★ 所以「把包里那份 FFmpeg 用起来」这条路**走不通**:要直接调就得再装一份 dll
(avcodec/avformat/avutil/swscale,20~30MB),而同样的代码已经在包里躺着了。
**经 libmpv 去解一帧,是唯一能用上它的方式。**

★ FFmpegDotNet.Skia(用户给的 NuGet 链接)**确实存在** —— 上次我把名字记成
  「FFmpeg.Skia」并说它不存在,是我错了。但包本身 46KB、**不含原生二进制**,
  页面写明要另配,所以它不改变上面那个结论。

### 查过但用不上的服务端事实(留档,别再查一遍)

对 Emby **4.9.5** 实测:

```
GET /Videos/{Id}/index.bif?Width=320   → 200,标准 BIF(Roku 那个格式)
openapi.json(452 条路由)搜 "trickplay" → 零命中
```

★ **Trickplay 是 Jellyfin 的,Emby 没有**,别照着 Jellyfin 的文档写。
★ BIF 要服务端先跑过「视频预览缩略图」计划任务;没生成时**照样回 200**,
  只是图片数 = 0(72 字节 = 64 头 + 8 结束项)。
★ 章节图很粗:实测 85 分钟的电影只有 2 个章节。
★ 这三条都成立,**但都不用** —— 记在这里是为了下次有人再提「问服务端要」时,
  能一句话说清为什么不。

## 画面增强重排:「开了没效果」的两种机制 — 2026-09-07

用户报三件事:PC 开了超分看不出效果、移动端开了超级卡而且也看不出效果、
档位要收成六档 A/B/C 且两端算法分开。三件事有两个共同的机制根因。

### 机制一:计算着色器在 ANGLE 的 GLES 上跑不起来,而且不算「编译失败」

`ArtCNN_C4F16.glsl` 那两档挂的是 **八个 pass 全是 `//!COMPUTE`** 的着色器。桌面走
Avalonia 的 ANGLE(`Avalonia.Angle.Windows.Natives`,deps.json 里能看到),
那条路上不一定有计算着色器 —— 于是:

| 检查点 | 结果 |
|---|---|
| `mpv_set_option_string("glsl-shaders", …)` | 0(收下了) |
| `player.setShaderLevel` 返回 | `count=2, will_run=true` |
| 属性 / 事件 | 一个字都没有 |
| mpv 的 error 级日志 | `Failed dispatching COMPUTE shader` |

`shaderguard` 的分类器当时**只认编译失败**(`shader compile log` / `no matching
overloaded function` / `shader link log`),这条从闸门底下走过去,UI 照样报「已启用」。

**一般化的教训:那个分类器分的不是「编不过」,是「跑不起来」。**
一个 pass 可以编译得过、只是没法派发;可以派发、只是 `//!WHEN` 判假被跳过。
三种在屏幕上长得一模一样,都是「画面没变」。分类只覆盖第一种,就等于只挡了三分之一。

处置:分类器补 `failed dispatching` 那一支;`core/shaders` 加一条门禁
`TestNoComputeOnlyShaders` —— 往 `files/` 里再放带 `//!COMPUTE` 的 shader 会当场红。
往 PlayKit 那个仓库翻新 shader 时,**先看有没有 `//!COMPUTE`**。

### 机制二:「纯放大档」在同分辨率下一帧都不跑

Anime4K 每个 CNN pass 都带 `//!WHEN OUTPUT.w MAIN.w / 1.200 > …`。
1080p 屏上播 1080p(全屏也一样,比例是 1.0)→ 整条链空转。上一版八档里五档是纯放大,
用户挨个点一遍,每一档都「已启用」而画面一动不动。

处置:六档里**只允许 Mode C 是纯放大**,其余五档都靠不挑尺寸的 Restore 兜底。
门禁 `TestOnlyModeCIsUpscaleOnly` 钉住这件事,反向注入(把 Mode A 的 Restore 拿掉)当场红。

放大那半跑没跑**只进日志不进界面**:屏幕就这么大不是故障,不该每次切档都念给用户听。

### 档位:照抄 Anime4K 官方那六个

用户原话:「按照 Anime4K 里面说的档位去分吧,PC 端和移动端都是,移动端还是最高到 medium 档,
PC 端到 High 档,都是 6 个档位 A B C A+A B+B A+C」。

链**一字不改**抄自官方模板的 `input.conf`(`md/Template/GLSL_Windows_{Low,High}-end/`),
Low-end→medium(移动端,M/S 模型)、High-end→high(桌面,VL/M 模型),
两个模板的差别只有模型大小,模式结构完全一样。测试 `Test六条链和Anime4K官方逐字一致`
把 12 条链逐字钉住 —— **抄错一个模型大小只会让画面「略有不同」,不报错、没人看得出来**。

☠ 官方链里有两处看着像笔误、其实就是这么写的,别去顺手理顺(反向注入验过会红):

- **B+B 的第二趟 Restore 夹在两个 `AutoDownscalePre` 中间**(A+A 和 C+A 都在两个之后)。
- **C+A 的第二趟用的是 `Restore_CNN`(不带 Soft)**,不是 `Restore_CNN_Soft`。

⚠️ **Restore CNN 回来了。** 用户 2026-07-11 / 07-15 两次否过它(边缘振铃 / 拖影),
仓库为此立过一条测试;2026-09-07 他自己改了主意 —— 官方 A/B 就是 Restore,
没有 Restore 就没有 A/B。那条测试同日删除。

**只有 Mode C 会遇到「开了不生效」。** Anime4K 的放大 pass 都带
`//!WHEN 输出 > 源 × 1.2`,而 **Restore 没有尺寸门槛**(实测:六个 `Restore_CNN_*`
里 0 处 `OUTPUT` 比较,有测试钉)。所以 1080p 屏播 1080p 时,只有 Mode C 整条空转,
`WillRun` 判 false,两端 UI 都把它藏掉;其余五档任何尺寸下都有 Restore 在干活。

### 名字:不叫「画质」也不叫「超分」,叫**画面增强**

用户 2026-09-07:「超分就是超分,不是画质。我们不转码,自然不可能有分辨率的更改。
后期还会加上超帧,甚至是 SDR-HDR 的『超分』,我觉得叫超分太细了」。

- 「画质」错在它像是在选码率 / 版本,而这里一帧也没重编码。
- 「超分」错在它只是**空间**那一维;超帧是时间维,SDR→HDR 是动态范围维。

所以菜单项和按钮统一成**画面增强**,shader 档位只是它底下的第一类。
命令名 `player.setShaderLevel` 没动 —— 那是契约,改它要动四方比对。

### ⚠️ 订正:安卓的 libmpv **不是纯软解**

仓库里 `surface_android.go` 和几处注释写着「安卓端 libmpv 是纯软解」——**这是错的**,
而且它误导过一次归因。实证:`third_party/libmpv/android/*/libmpv.so` 里
`mediacodec-copy` 是合法的 `hwdec` 取值,`mediacodec` 相关符号 43 处;
起手选项给的是 `hwdec=auto`;`player.status` 一直在发 `hwdec-current`。

所以移动端「开 Anime4K 超级无敌卡」**卡在着色器不在解码** ——
手机 GPU 是集显且有温度墙,VL 那条 CNN 链在它上面跑不动。
排查时先读 `hwdec-current` 那个字段再下结论。

### 顺带:让 GL 自己报家门

`lp_gl_init` 现在用宿主给的 `get_proc_address` 取一次 `glGetString`,打一行:

```
GL 后端:OpenGL ES 3.0 (ANGLE …) | 显卡:ANGLE (NVIDIA GeForce RTX …) | 着色器语言:…
```

在这之前,「超分没效果」到底是方言不对、计算着色器没有、还是跑在核显上,
**三者在日志里长得一模一样**(都是一片安静)。这一行把前两种当场分开,
顺带也是「有没有跑在独显上」的判据(见 [双显卡必须钉独显](player-mpv.md))。
取不到就打一句 warn,不假装成功 —— EGL 允许 `eglGetProcAddress` 不返回核心函数。

### 还没验的

⚠️ 上面 ANGLE 那条的**具体 ES 版本没有实测**,是从「Avalonia 用 ANGLE + mpv 报
dispatch 失败」推出来的。装上新包之后那行 `GL 后端:…` 会给出确切答案;
在它出现之前,别拿「ES 3.0 没有计算着色器」当已证事实往外说。
Linux 那边 GL 后端不是 ANGLE,**要单独验**。

## 跨域交叉引用

这些条目和本领域强相关,但正文放在别的文件里(一条经验只存一份正文):

- [外挂字幕真根因](emby.md) — 外挂字幕不加载的第一层断口在 Emby 的 DeviceProfile,不在 mpv
- [跳到未缓冲位置卡死=我们拼错API根](emby.md) — 「跳到未缓冲位置卡死」是 URL 前缀拼错导致服务器不给 206
- [弹幕交给 libass](danmaku-sync.md) — 弹幕曾走 mpv 的 secondary-sid(2026-07-27 已整条删除)
- [PC 快捷键 + mpv.conf](ui-desktop.md) — 自定义 mpv.conf 靠 libmpv 的 config-dir,以及 seek 闩的粘性值坑
- [安卓视频透出四层](android.md) — 安卓「有声音没画面」的四层透出链
- [预取代理死锁(已修)](network.md) — 「有流量没画面」还有一整族根因在预取代理里
- [弹弹Play 三个静默失败](danmaku-sync.md) — 弹幕相关的静默失败

---

## 「两个功能挂在一个开关上」——缩略图 vs 多线程加载(2026-09-06)

用户报「不开多线程加载就没有缩略图」。派去的 agent 回来说「代码层不存在耦合」——
**它错了,而且错得很典型**:判据不是「有没有一句 `if multithread`」,
是「关掉开关之后这功能还工不工作」。

真正那一行在 `core/player/playback.go`:

```go
on := acc != nil && p.PrefetchEnabledFor(acc.Server)
if !on && !warmHit { closeSharedProxy(); return target.URL }   // ← 直连,不起代理
```

而缩略图唯一的字节来源是代理的只读缓存端点(`warm.go` 的 `localSource`)。
开关关着 → 不起代理 → 没有环形缓存 → 缩略图整个不存在。耦合藏在两层之外。

**改法是把「起不起代理」和「要不要超前拉」拆开**:代理常开(旁路落盘),
开关只管超前窗口(`prefetch.StartPassthrough`,readAheadChunks=1)。
顺带解释了用户第二句「缩略图那个实例比播放的加载得多」——
超前拉本来就跑在播放位置前面很远,而进度条上「有缩略图」的带子画的正是缓存区间。

★ 旁路模式的 UA 也换回了播放那条道:它**就是**用户正在看的那一路,
  用 LinPlayerPreload 会让服主在日志里把它错认成一次旁路预取。

## 换掉手写弹层之后,自检的失效形状会变(2026-09-06)

播放页的设置抽屉换成 Avalonia 的 `Flyout` 之后,老那条断言
「静止 4.5 秒面板还在没有」**不再区分对错** —— 弹层是自己的顶层窗口,
OSD 收了它照样在。反向注入(摘掉 `!_popupOpen` 守卫)实测:第一条照样绿,
只有第二条「控制条有没有跟着留着」变红。

★ 规矩:**换了实现就要重新做一次反向注入**。断言的措辞没变不代表它还在验同一件事 ——
  一条一直绿的断言,可能是从某次重构起就再也红不了了。

## 用户的 mpv.conf 会顶掉我们在 init 之前设的选项(2026-09-08)

「导入自己的 mpv.conf」这条路上有两个坑,顺序上是连着的。

**第一个:不给 `config-dir`,那份文件就是一张废纸。** libmpv 默认 `config=no`,
而我们从来没设过 `config` / `config-dir` —— 核心层的 `player.setMpvConf` 写文件、
返回成功,mpv 从头到尾没读过它。命令在那儿摆了很久,一直是个装成功能的入口。

**第二个:开了 config-dir 之后,配置文件赢。** 用 `keep-open` 当探针实测
(`probeConfDirPrecedence`,住在 `core/player/player.go`,理由同 `checkOptionNames`:
`_test.go` 不能 `import "C"`):

```
mpv.conf 写 keep-open=yes,我们在 mpv_initialize 之前设 keep-open=no
→ init 之后读回来是 yes
```

也就是说一行 `vo=gpu` 就能把 `vo=libmpv` 换掉。而 `vo=libmpv` 是 render context 的
前提,换掉的表现是**桌面端全程黑屏、一条错都不报**。安卓那边同理(`vo=gpu` +
`gpu-context=android` + `opengl-es`)。

★ 所以交给 mpv 的**不是用户那份文件,是过滤过的副本**(`userdata/mpv/effective/`):
  `sanitizeMpvConf` 把 `vo` / `wid` / `gpu-context` / `opengl-es` / `config` /
  `config-dir` / `include` / `terminal` 这几行注释掉,别的一行不动。
  过滤而不是自己解析 —— profile、include、引号这些 mpv 自己解析得最准,
  我们只负责摘掉会砸掉画面输出的那几行。

★ 注释掉而不是删掉:行号得和用户手上那份对得上,不然日志里那句
  「这一行被忽略」他找不到是哪一行。

☠ 这张黑名单里**只放两类**:会让画面整个没掉的,和会绕过这张表本身的。
  「我们也设了的项」不用进 —— 那种冲突已经由「我们的值在后面」解决了。

## 2026-09-08 · 字幕样式那五颗旋钮,哪几颗对谁生效

用户要「字幕大小 / 位置 / 描边 / 缩放 / 粗体」。直接摆五个滑块会踩自己定的规矩
(「不生效的选项直接删掉」)—— 因为**它们的生效范围不一样**:

| 项 | mpv 属性 | ASS 特效字幕 | 纯文本字幕(SRT/VTT) |
|---|---|---|---|
| 大小 | `sub-scale` | ✅ | ✅ |
| 位置 | `sub-pos` | ✅ | ✅ |
| 描边 | `sub-outline-size` + `sub-border-size` | ❌ 自带样式 | ✅ |
| 粗体 | `sub-bold` | ❌ 自带样式 | ✅ |
| 缩放 | `sub-scale-by-window` | ✅ | ✅ |

- **「大小」不能用 `sub-font-size`。** mpv 默认 `sub-ass-override=scale`,
  这个模式下 ASS **完全忽略** sub-font-size,而内封字幕绝大多数是 ASS
  (老实测记在 `setSubScale` 上面)。所以不能同时摆「大小」和「缩放」两个倍率滑块 ——
  其中一个在大部分片子上是死的。「缩放」在面板上的含义改成
  **字号跟窗口走还是跟片源分辨率走**(`sub-scale-by-window`),那是另一颗真旋钮。
- **描边属性在 mpv 0.38 改过名**(`sub-border-size` → `sub-outline-size`)。
  mpv 对不认识的属性只回错误码就完事 —— 写死哪一个都会在另一半构建上静默失效。
  **两个都发**,谁在就谁生效。
- **`sub-pos` 上限是 150 不是 100。** 100 是画面下沿,再往下是黑边;
  宽银幕片子上「把字压进黑边、一点画面都不挡」是最常用的一档。
- **必须落库 + 起播时重压一次**(`applySubStyle`,调用点在 `applyPlaybackDefaults` 里 —— Emby 起播和源起播都经过它)。
  mpv 每次冷启动都是新的,不落库的表现是「调好了,关掉软件再打开又回默认」。
  ☠ 落库那一步**不能在 `ensureMpv` 里做** —— 它全程持着 `mpvMu`,而 `setProp`
  自己也要拿这把锁。

`player.setSubStyle` 从注册那天起**两端 UI 一次都没调过**(第六次「后端领先前端」)。

---

## 弹幕不该交给 mpv 画(2026-09-10)

弹幕曾经走 mpv 的 `osd-overlay` + `format=ass-events`,照抄 uosc_danmaku。
**整段已经删掉**,改成核心层只排版、两端 UI 各自绘制。三条理由,每条单独都够:

- **Exo 内核下那条路根本不存在。** 安卓换成 ExoPlayer 时 mpv 手里没有这一片,
  `pushOverlay` 拿不到句柄直接返回 —— 而 `player.danmakuSet` 照样返回成功、
  照样报「挂上 N 条」。一个装成功能的接口。
- **平滑度被钉死在片源帧率上。** mpv 手册写着 `osd-overlay` 的 `Timing is unused`,
  位置要宿主每拍自己算;而唯一的时钟 `time-pos` **只在视频帧边界更新**。
  24fps 片源上推 60 次也只得到 24 个不同的位置。uosc_danmaku 靠插一道
  `vf append @danmaku:fps=fps=N` 抬帧率绕开,代价是多一遍解码。
- **每秒 120 条命令全排在 mpv 的核心线程上**,而那条线程同时在解封装、解字幕。
  图形字幕(PGS)这种解码量大的最先被挤掉。

排版留在核心层(`core/player/danmakustyle.go`),因为两端各写一份必然漂;
绘制交给 UI,因为那是每帧都要干的活。接口是 `player.danmakuLayout`:
一次取走整份,字号 / 行高 / 滚动时长**已经把缩放和速度折算进去**。

排版这一半的口径(和载体无关,继续成立):

- **合并必须排在分轨之前。** 反过来的话被合掉的条目已经占过轨道,
  屏幕上留下一排排空行,而弹幕总数看起来又是对的。
- **存原始语料,不存布局结果。** 范围 / 行数 / 缩放 / 速度 / 合并这五项一改都要重排,
  而重排的输入是「合并之前、丢弃之前」的那一份。只留布局结果的话,
  范围调小一次就再也调不回去 —— 丢掉的条目找不回来。
- **排不下就丢掉,不硬塞。** 硬塞是两条叠一行,比少一条难看得多。
- **行数的 0 是合法值**(= 这一类不显示),不能和别的数值项一样「0 当没设、回默认」。
  写错的表现是「置顶行数拉到 0,顶部弹幕照样满屏」。
- 一轨的再用时机 = **本条尾巴进画面的时刻**(`t + w/(resX+w)*roll`),
  不是「整条走完」。用后者的话一轨一次只放得下一条,屏幕会空得离谱。
- 滚动一条要走 **`宽 + 自己的宽`**,不是只走画面宽。少算这一截的表现是
  长弹幕走到左边突然消失,短的看不太出来。
- 颜色**原样是 RGB** 交给两端的画笔。走 ASS 那一版要在出口做一次红蓝对调
  (ASS 是 BGR),那次对调随载体一起删了 —— 留着的表现是红色弹幕变蓝,
  而白色灰色看不出来。判据只能用纯红。

`danmaku.*` 十四条命令里,除 `autoLoad` 外**十一条零调用** —— 搜索、选集、换源、
屏蔽词在两端都没有入口。这是本仓第七次「后端领先前端」。

---

## PGS 字幕「时不时不出现」:`demuxer-mkv-subtitle-preroll` 的默认值对图形字幕等于没开(2026-09-10)

mpv 的这一项默认是 `index`:**只有字幕轨在 cue 索引里有条目才做预读**。
而 mkv 的 cue 通常只给视频轨,字幕轨一条都没有 —— 于是 `index` 档判定「没索引」,
预读不做。

对文本字幕看不太出来(一句一两秒、包密),对 PGS 这类**包稀疏、每包持续好几秒**
的图形字幕就很致命:seek 落在一句字幕的中间时,那一包在 seek 点之前,
永远不会被解出来。表现正是「时不时不出现」,而下一句照常出来,所以看着像随机。

改成 `yes`(无条件预读)。代价是每次 seek 多读一小段。

★ 相邻的一条:弹幕走 `osd-overlay` 时每秒 120 条命令压在 mpv 核心线程上,
而那条线程同时在解图形字幕 —— 那一版删掉之后这条路上少了一个干扰源。

---

## 弹幕开关是落库的,所以起播时必须自己去匹配一次(2026-09-10)

「不会自动加载弹幕」的根因不是匹配算法,是**没有任何东西在起播时调它**。
`autoLoad` 只挂在「拨开关」和「重新匹配」两个按钮上,而开关落在
`prefs.danmaku_enabled` 里 —— 上一集打开过,这一集进来面板上写着「已打开」,
却一条都没取。看上去还像是开着的,所以用户根本不会去拨它。

配套两条:

- **换集要先清。** 核心层存的是上一集的语料,这一集匹配不上时它原样留着 ——
  表现是新一集播着上一集的弹幕。
- 自动匹配**不许弹「没匹配到」**。九成片子本来就没有弹幕,每集弹一次等于每集骂一次。
  那句提示只在用户主动点「重新匹配」时说。

---

## PGS 盲修三处(2026-09-11)

用户点名不给日志,让先盲修。按「这条路上哪些环节会静默失败」挨个补:

1. **`demuxer-mkv-subtitle-preroll-secs` 的默认值是 1 秒。**
   上一轮把 `demuxer-mkv-subtitle-preroll` 开成 `yes` 解了一半 —— 但真正决定
   「回溯多远」的是这个 secs。PGS 一句常驻 4~8 秒,seek 落在第 5 秒时那一包在
   4 秒前,1 秒够不着,于是「开了预读还是时不时不出现」。给 8 秒。
2. **图形字幕的格式名各家 fork 写法不一**(`pgssub` / `pgs` / `hdmv_pgs_subtitle`)。
   `DeviceProfile.SubtitleProfiles` 漏掉一个写法的后果不是「这条字幕没声明」,
   而是服务器把整条轨判成 **Encode** —— 它要转码才给,而我们不走转码流,
   表现就是「这个片子的 PGS 字幕整条不见了」,一声不吭。多声明几种没有副作用。
3. **一句字幕解坏了不许把整片打死。** media3 1.11 的字幕解析在**解封装线程**上,
   `SubtitleParser.parse` 抛出来的异常会被包成 `ExoPlaybackException` 一路冒到
   `onPlayerError`,画面当场停住。而图形字幕最容易解坏(PGS 的 RLE 段被切流 /
   转封装动过就会读越界)。`SafeParser` 包一层,只吞**这一句**,不关整条轨。
   ★ 日志那一步做成可注入的 `log: (String) -> Unit` —— 单测里 `android.util.Log`
   是个会抛异常的桩,不留这个缝的话这条用例测的就变成了日志而不是吞异常。

## 外部播放器:标题要逐条给,续播点只给第一条(2026-09-12)

用户:「外部播放器:目前并不能传递视频标题、多个视频地址。因此无法自动搜索弹幕、
无法自动续播」。

本机 mpv(`D:\MPV\mpv-lazy\mpv.exe`)实测,三条都是**跑出来**的不是查出来的:

- 多个地址直接当参数递过去 = 一个播放列表,按顺序放。
- 标题必须用 `--{ … --}` **逐条包起来**,`--force-media-title` 才跟着各自那一条走。
  实测两条分别报出 `标题甲` / `标题乙`。写成一个全局的话第二集起显示第一集的名字,
  靠标题搜弹幕的脚本(uosc_danmaku 一类)会跟着搜错。
- `--start=` 只能放**第一条**那一组里。放全局的话每一集都跳到同一个续播点 ——
  实测第一条 `POS=2.000` 起、第二条 `POS=0.000` 起,证明它确实是文件局部的。

队列上限 12 集:每一集都要单独问一次 `PlaybackInfo`(一次 POST,服务端还会为它
开一个会话),整季一百集就是点一下按钮等半分钟。

不是 mpv 的播放器**一个参数都不给**:它们会忽略未知参数**或者直接报错**,
后者连片都打不开。

## vo=libmpv 下 input.conf 的按键绑定一条都不会生效(2026-09-12)

用户:「希望可以识别 mpv 的 input.conf……可以部分生效但不是全部」。

「部分生效」的那部分是 `mpv.conf` 里的选项。**按键绑定是零生效** ——
`vo=libmpv` 下 mpv 没有自己的窗口,一个键盘事件都收不到,`config-dir` 设了也没用。

补法是壳把**自己没用掉**的键原样 `keypress` 给 mpv(`PlayerPage.ForwardToMpv`)。
配套三条,少一条就出事:

- **`input-default-bindings=no` 必须一起加。** 转发之后 mpv 的默认键位会突然活过来,
  `q` 当场把播放器退了、`s` 悄悄截一张图。这些键在转发之前一个都到不了 mpv,
  所以关掉是**维持原样**,不是减功能。实测:关掉之后 `keypress q` 播放器还活着。
- 单字母 + Shift 在 mpv 那边就是**大写字母本身**,不是 `Shift+d`。写错的话用户
  `input.conf` 里那条 `D` 永远匹配不上,而且一句错都不报。实测 `Ctrl+d` 和 `D`
  两条绑定都真的触发了(speed 2.5 / 3.5)。
- 焦点在输入框里就别转:搜弹幕输个 `s` 会顺手截张图,没有任何提示。

键名映射由 `LP_KEYPROBE` 钉住(纯映射,进得了 CI)。

## 安卓 libmpv 是 0.36:loadfile 没有 index 那一格(2026-09-14)

**症状**:移动端 mpv 内核,**有观看记录的片子播不了**;从头放的片子、ExoPlayer 内核、桌面端都正常。

**根因**:`loadfile` 的签名在 mpv 0.38 改过 ——

| 版本 | 签名 |
|---|---|
| 0.37 及以前 | `loadfile <url> [<flags> [<options>]]` |
| 0.38 起 | `loadfile <url> [<flags> [<index> [<options>]]]` |

(原文:mpv 仓库 `DOCS/man/input.rst` 的 v0.37.0 / v0.38.0 两个 tag;`DOCS/interface-changes.rst` 0.38 节「move the `options` argument of the `loadfile` command from the third parameter to the fourth」)

桌面 libmpv 在 0.38 之后,2026-09-03 为它改成了 `replace -1 start=…`;
安卓打包的 `media-kit/libmpv-android-video-build` v1.1.11 里是 **`mpv v0.36.0-549`**,
`-1` 落进选项那一格 → -4 → `loadfile 失败`。只有带续播进度的条目会拼出第 4 段,所以只坏「有观看记录的」。

**怎么查出版本**:`third_party/libmpv/android/arm64-v8a/libmpv.so` 里搜 `mpv v0.` 字符串。

**修法**(`core/player/load.go`):先按记住的写法发;带续播位置被拒时换另一种写法重发,成功就记住。
被拒发生在参数解析阶段,mpv 什么都没做,重发是安全的。门禁 `load_test.go` 用两种语法的假 mpv 各跑一遍,
改回只有四段式当场红在 0.36 那一格。

**同一次顺手修的**:手机播放页拿「位置比上一次大」判「在播了」,续播时第一次状态就从 0 跳到续播点,
于是黑幕提前撤、起播失败被当成播完静默退出。改成「前进 0.05~2 秒才算」(`ui/player/Progress.kt`)。

**失效条件**:安卓换到 0.38 以后的 libmpv,两种写法的回退就只会走新语法那条,这段逻辑可以删。
**同类风险**:核心层发给 mpv 的其它命令(`sub-add` / `seek absolute+exact` / `screenshot-to-file` / `stop`)在 0.36 都有;
以后加命令或属性,先查它是哪个版本进的 mpv —— 安卓那颗库比桌面老两个大版本。

## 一拍 `eof-reached` 不等于播完(2026-09-16)

用户:「PC 端播放的时候会闪退」。追问后确认是「**退回到上一页,程序还在**」、
「播了一会儿(几分钟后)」—— 那就不是进程崩溃,是**播放页自己主动走的**。

`PlayerPage.Poll()` 每 250ms 一拍,里面一句无条件的:

```csharp
if (eof && !_leaving) { Leave(); }   // Leave() → Nav.Back()
```

`eof` 来自核心层 `get("eof-reached") == "yes"`。
**`eof-reached` 在流断掉的时候也会翻成 yes** —— 预取代理掉线、上游 502、
反代把长连接掐了,而那会儿片子才放到一半。于是「播着播着自己退回上一页」。

之所以一直没人怀疑它,是因为 `keep-open=yes` 下判播完**只能**读这个属性
(`END_FILE` 永远不发,见上面那条)。大家记住了「要读 eof-reached」,
却没人问过「它 yes 的时候一定是播完了吗」。

修法再加两道判据:**真的放到片尾附近**(`pos >= dur - 5`)、而且**连着两拍**都说 eof。
`dur <= 0`(时长还没解出来)时一律不退 —— 那时候位置也不可信,
宁可让用户自己按返回,也不能把正在看的片子扔掉。

> 通例:**mpv 的状态属性描述的是「此刻的解复用器状态」,不是「用户视角的事件」。**
> 拿它当事件用之前,先问一句:它在**异常路径**上会不会也是这个值。
> 同类可疑的还有 `idle-active`、`core-idle`、`paused-for-cache`。

**还没验的**:这条是按代码路径推出来的,**没有在真机上复现过那次退页**
(要一台会中途断流的服务器)。如果用户改完还报同样的现象,
下一个嫌疑是预取代理里那批**没有 recover 的 goroutine**
(`prefetch.go:285`、`serve.go:405`/`:152` —— seek 一次就新开一批),
那条会表现为**整个进程无声消失**而不是退页,两者可以按「程序还在不在」分开。

## 安卓 mpv 内核字幕:三处接线 + 默认字体(2026-09-17)

用户报「MPV 内核的字幕显示出问题了」,附的日志里**一行字幕相关的都没有**(mpv 日志只订了 error 级)。
下面全是读代码查出来的,四处都修了,真机还没复验。

1. **字幕样式冷启动不生效**:`applySubStyle` 以前只在 `playFile` 里调,而 `playFile` 只有探针那条
   「只传 path」的分支会进 —— Emby 起播从来不经过。表现:调好的大小 / 位置,杀掉重开回默认,面板上的数却还是调过的。
   挪进 `applyPlaybackDefaults`(`loadDanmakuStyle` 同理)。
2. **选轨偏好从没在起播时应用**:`prefs.applyPrefs` 全仓没有调用方,只有生成的绑定里有。
   「默认开启字幕」关了照样出字幕;开着时外挂字幕(FILE_LOADED 之后才 `sub-add … auto`)永远不会被 `sid=auto` 选上。
   现在 `onFileLoaded` 挂完外挂字幕后调 `applyTrackPrefs`:关 → `sid=no`;开 → 正则 / 语言挑,
   挑不出且 mpv 一条没选时补选第一条。详情页显式选的轨由壳在这之后设,会覆盖它。
3. **手机 mpv 内核的字幕面板没有「关闭字幕」**(只加在 Exo 那边)。顺带修了 `current` 取的是
   **任意类型**里第一条 selected(字幕面板高亮的其实是音轨的 id)。
4. **默认字体族名不存在**:这颗 libmpv(0.36)无 fontconfig,libass 只认 `sub-fonts-dir` 读进来的字体、按**族名**找;
   mpv 默认 `sub-font=sans-serif`,`/system/fonts` 里没有叫这个名字的字体。SRT 的样式字体就是它,
   ASS 指名的字体不在系统里时也回落到它 → 找不到就不画。现在起手从候选文件
   (`NotoSansCJK-Regular.ttc` → `NotoSansSC` → `DroidSansFallback*` → `MiSansVF.ttf`)里读 **name 表的族名**
   (`core/player/subfont.go`,零依赖,按偏移读,20MB 的 ttc 只读几百字节),ttc 优先 ` SC` 那张脸,设给 `sub-font`。
   **`sub-fonts-dir` 在这颗 libmpv 上是存在的**:日志里没有「mpv 选项没设上」,上面 Flutter 时代那句「两版都不存在」是错的,已删。
   `config=no` 时 `~~/subfont.ttf` 那条路走不通(没有 config dir),所以没照搬 Flutter 时代的软链做法。

**真机复验时看**:mpv 日志临时订到 warn,确认没有 `fontselect: failed to find any fallback`;
`player.opts` 回读 `sub-font` 是族名;设置里关字幕后起播 `sid=no`。


## 补帧(2026-09-17)

用户定的口径:「画面增强」以后拆三类 —— **画质增强(有档位)/ 补帧(有档位)/ SDR2HDR(一个开关)**。
补帧先放进「增强」弹层下半段,不单开按钮。默认补 2 倍,3 / 4 倍是给强显卡的。

### 方案:DRBA / RIFE 走 vf=vapoursynth + DirectML

- 代码:`core/interp`(档位、运行时下载、DXGI 选卡、脚本)+ `core/player/interpcmds.go`(命令、起播回挂、丢帧闸)。
- 推理走 **DirectML**,任何 DX12 独显都能跑,不分 N 卡 A 卡 —— 所以没做 AMD 专用的 `vf=amf_frc`,也没做 TensorRT 版(2.8GB)。
- 运行时 66MB(zip)由 `scripts/pack-interp-runtime.py` 从 hooke007/mpv_PlayKit `20260510` 懒人包挑文件打成,
  **可复现**(固定时间戳,连打两次 sha256 一致),发在本仓库 Release `interp-runtime-1`,**`--latest=false`**。
  更新检查:稳定渠道看 `/releases/latest`,不 `--latest=false` 就会顶掉正式版;预览渠道按版本号比,`interp-runtime-1` 解不出 x.y.z,不会被当成新版。
- 挑文件的表是实测的:少了根目录那批 `.pyd`,VSScript 初始化直接失败,报 `Failed to initialize VapourSynth VSScript library: last error unknown`。
- 脚本 `core/interp/files/interp.vpy` 是自己写的(上游 .vpy 按其 LICENSE.MD 视作未授权),直接调 k7sfunc 的 `DRBA_HUB` / `RIFE_ORT_HUB`:
  k7sfunc 的 `DRBA_DML` / `RIFE_DML` 把 `gpu` 钉死在 0/1/2,机器上有远程桌面软件装的虚拟显示卡时独显序号会排到后面。

### 实测(RTX 5060 Laptop,1080p 23.976,DirectML)

| 档 | 补帧前高度 | 结果 |
|---|---|---|
| DRBA 2× | 1080 | 不丢帧(mpv.exe 不计时 88fps;LinPlayer 里每秒出 48.3 帧) |
| DRBA 3× | 1080 | 10 秒丢 122 帧 ✗ |
| DRBA 3× | 720 | 丢 0~7 帧 ✓ |
| DRBA 4× | 1080 | 10 秒丢 666 帧 ✗(LinPlayer 里 8 秒丢 278 帧,闸撤掉) |
| DRBA 4× | 720 | LinPlayer 里每秒出 96 帧 ✓ |
| RIFE 2× | 1080 | 丢 131 帧 ✗ |
| RIFE 2× | 900 | 丢 0 帧 ✓ |
| RIFE 3× | 900 | 丢 485 帧 ✗ |
| DRBA 2× 核显 Intel UHD | 720 | 丢 178 帧 ✗ → 专用显存 < 2GB 的卡不给开 |

- 硬解:偏好 `auto-safe` 时 mpv **自己切到 `d3d11va-copy`**;强制 `d3d11va` 反而退回软解(丢 53 帧)。不用改解码偏好。
- seek:vapoursynth 会重载脚本,**画面停约 4 秒**再以 2 倍接上(自检实测 pos 55 停在原地 4 秒)。上游行为,没法绕。
- mpv 的 `frame-drop-count` **在 seek 时清零**(实测 19 → 0),量丢帧必须跨清零累加(`dropCounter`)。

### 坑

- **DXGI 适配器序号必须在本进程里枚举。** 在别的进程(python)里 N 卡是 1 号,在 LinPlayer 里是 **0 号** ——
  `gpupref_windows.go` 把本程序钉到了高性能显卡,per-exe 偏好会改 `EnumAdapters1` 的顺序。写死序号就跑到核显上。
- **VSScript.dll 靠预加载,不靠环境变量。** libmpv 是 `LoadLibraryW("VSScript.dll")` 按名字找(只搜 exe 目录 / PATH),
  读 `VSSCRIPT_PATH` 用的是 C 运行库的 `getenv`,Go 的 `os.Setenv` 改不到那份。先 `LoadLibraryExW(全路径, LOAD_WITH_ALTERED_SEARCH_PATH)` 装进进程,同名模块 mpv 直接拿到。
- **`DEVMODEW.dmDisplayFrequency` 在偏移 184,不是 188。** 按文档推算错过一次,本机 dump 实测:168 位深、172/176 宽高、184 刷新率。
- **补完的帧率超过屏幕刷新率就不给开**(24×3=72 在 60Hz 屏上多出来的帧显示不出来,还会被丢帧闸当成跑不动)。60 帧片源不补(上游同样卡 32)。
- **丢帧闸必须实测变红过**:把 DRBA 4× 临时改回 1080p 高度,LinPlayer 里闸 2 秒就撤(丢 68 / 应出 192)。跑不动时每秒只出 8 帧,所以量窗没满、丢帧过 25% 就提前撤。
- 自检:`LP_INTERP=drba_2 LP_CORELOG=1 LP_WAIT=48 bash scripts/selfcheck-win.sh interp play:mv-1 <片子>`。
  页面参数必须是 `play:mv-1`,`player` 只进播放页不起播(status 里 duration 恒 0,闸永远在等)。

### 安卓:自编 libmpv + OpenCL 光流滤镜 vf=lpinterp(2026-09-17)

用户定:「走 opencl」「肯定是走 libmpv」「能拿官方编译好的就拿,没有再自己编」。

- **mpv 官方没有安卓 libmpv 成品,Windows 也没有 libmpv-2.dll**(官方 Release 的 msvc 包只有 mpv.exe + vulkan-1.dll)。
  PC 维持 shinchiro(mpv 官网安装页列的构建);安卓用官方 mpv-android 的 buildscripts 自编
  (`.github/workflows/libmpv-android.yml`,依赖全钉提交;本机没有 WSL / Docker,脚本只支持 Linux)。
- 官方脚本的产物是**一组 so**:libmpv + ffmpeg 动态库 + libc++_shared。`av_jni_set_java_vm` 在 libavcodec.so 里,
  lpcore 链接要加 `-lavcodec`;APK 里要带全部 so(`build-core-android.sh` 整目录拷)。
- 滤镜在 `third_party/mpv-interp`,算法来自 HopperRender(作者 Windows 版 GPL-3.0 → 只能进 GPL 构建的 libmpv,本体 AGPL-3.0 兼容)。
  上游**不能直接搬**:kernel 运行时从 `$HOME/mpv-build/...` 读盘;有桌面状态面板(fork python、写失败 `exit`);
  用 `__local` + `barrier` 归约和已废弃的 `atomic_add`(Mali / 三星 ANGLE-CL 上编不过);
  窗口求和 `unsigned int` 在大窗口溢出;输出比时间戳晚一个源帧(24fps 下 42ms)。全部重写。
- **OpenCL 必须 dlopen**:硬链 libOpenCL.so 的话没有 OpenCL 的机器整颗 libmpv 加载失败,mpv 内核起不来。
  targetSdk 31+ 还要在 manifest 声明 `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`,否则命名空间直接拒。
  Pixel 的库叫 `libOpenCL-pixel.so`。
- **计算核在 PC 上先验**(`bench/lpi_bench.c`,不依赖 mpv):值噪声纹理平移,真值中间帧 = 平移一半。
  不能用正弦叠加纹理 —— 周期纹理平移半周期和自己一样,光流对上错的周期,测到的是纹理的问题。

| Intel UHD 770,1080p | 光流(半径 5) | 补帧 PSNR | 直接混合 |
|---|---|---|---|
| 上游算法 | 139 ms | — | — |
| 重写后(一窗一个工作项,大窗口取 8×8 样) | 22 ms | 平移 12×6:33.5 dB | 24.6 dB |
| 同上,平移 30×-10 | 17 ms | 24.1 dB | 18.7 dB |
| 同上,平移 48×20 | 34 ms(半径 16 时 51 ms) | 16.1 dB(半径 16:20.6) | 16.0 dB |

  RTX 5060 上半径 5 每源帧 10 ms。中间帧生成 + 读回每张 3~5 ms,上传两帧 1 ms —— 光流是唯一的大头。
  **手机上没有实测**(截至 2026-09-17 没有真机)。滤镜按实测耗时自调搜索半径,最小半径连续 24 个源帧超预算就放弃并 MP_ERR。
- 安卓核心层拿不到屏幕刷新率,由 UI 随 `player.interpLevels` / `setInterpLevel` 传 `display_hz`。

### 安卓补帧第二轮:遮挡掩膜 + 分层搜索(2026-09-18)

真值验证的做法:`bench/lpi_real.c` 取真实片源第 0、2 帧算中间帧,和**真的第 1 帧**比。
合成纹理测不出问题 —— 它没有遮挡、没有形变、没有细线条。

**先纠正一个量法上的坑**:随机取时间点做样本会被动画本身骗。实测这部番 **69.2% 的相邻帧几乎完全相同**
(2396 个帧对:<0.3/255 占 69.2%、真实运动 3~40 只占 21.3%),8 个随机取样点里 7 个落在按住的原画上,
两种算法当然打平。**必须先扫出 `|f0-f1|` 和 `|f1-f2|` 都在 3~40 且幅度接近的三元组**再比。
另:EISAI(ECCV 2022)的用户实验里 PSNR 高的那版只有 19~25% 被人眼认为更好 —— PSNR 奖励糊,
**`occ_pct`(被判成遮挡的格子占比)是更好用的质量代理**,流场越可信它越低。

**三处改动,都在 `third_party/mpv-interp/`**:

1. **遮挡掩膜**(`lpi_occ` / `lpi_occ_post`)。原来 `lpi_warp` 最后是无条件 `a*(1-t)+b*t` ——
   上游 HopperRender 也没做(`warpFrameKernelSDR.h:186-188` 同样是裸的加权平均,全仓 grep `occlusion` 零命中)。
   遮挡区光流其实是对的,但角色背后的像素在另一帧里**根本不存在**,硬混就成了**半透明的人**。
   判据两条,都用现成数据、不需要第二遍运动估计:匹配残差 + 流场自洽。
   ☠ **残差必须自归一化**(拿「补偿后」和「完全不补」相比),定死绝对阈值在合成纹理上实测误报 38.6%。
   判出来之后退回「最近一帧的原像素」:那块因此不流畅,但不会有鬼影(SVP 官方管这个取舍叫 "lose smoothness")。
   静止场景实测 44.61 → **57.90 dB**,而动画 69% 的帧正是这种。

2. **分层搜索步长**(`lvl_scale`)。原来**每一级窗口都用同一套候选** `-4,-1,0,1,4` ——
   粗层(W=256)和最细层(W=2)步长一样,八轮累计只能挪 **±32 px**,而 1080p 动画快动作帧间位移常到 50~100 px。
   以前只能靠把 radius 从 5 堆到 16 换范围,代价 3.2 倍。按窗口缩放后 radius 还是 5,累计可达 **±256 px**。

3. **最细窗口当第二把旋钮**(`lpi_set_min_win`)。最后三级窗口(W=8→2)占 **60% 算力却几乎不改善结果** ——
   流场随后要过 3×3 中值,比格子更细的运动本来就被抹掉了。
   **降级顺序是实测定的:先把窗口调粗、后砍搜索半径** —— R=8/W=16 只要 7.1ms,
   质量却全面压过 R=5/W=2 的 11.2ms。反过来就是拿质量换了个更贵的档。
   这一档也是「跑不动就整个关掉补帧」之前的兜底:最低档 R=5/W=32 只要 3.6ms。

| Intel UHD 770,1080p,真实番剧 | 光流耗时 | tri_49 样本 | 直接混合 |
|---|---|---|---|
| 改动前默认(R=5 未缩放,W→2) | 12.0 ms | 19.14 dB | 18.80 dB |
| 改动前最高档(R=16) | 31.8 ms | 20.55 dB | — |
| **现在起步档**(R=5,W→16) | **5.2 ms** | **20.45 dB** | — |
| 现在最高档(R=16,W→8) | 21.6 ms | 20.83 dB | — |
| 现在最低兜底(R=5,W→32) | 3.6 ms | 20.49 dB | — |

合成纹理平移 12×6 同步从 33.5 → **37.8 dB**。

**试过没用、已经删掉的**(别再走一遍):
- **矢量场 3×3 中值取代 8×8 方框均值**:实测两者差在 ±0.15 dB 内,耗时相同。ANVIL 论文说中值+高斯值 +1.57dB,
  那是对 H.264 宏块级 MV 场说的,我们的场已经在 270p 上又被后处理过,没有离群值可去。
- **逐采样点的邻居一致性项**(原 `iteration>=3` 那段):质量完全无贡献,却占 R=16 时 **32%** 的耗时(53.7→36.4ms)。
- **`lambda·|v−邻域中值|` 惩罚项**:λ 从 0 扫到 16,合成和真实动画上都在噪声里。保留是因为去掉后
  搜索完全没有空间平滑约束,且它每窗口只算一次(不是每采样点),代价可忽略。

**仍未验证**:没有真机。以上全部来自 RTX 5060 + Intel UHD 770(当手机代理)。
阶梯的**相对顺序**(窗口比半径划算)是算法结构决定的,应该可移植;**绝对数字一定会变**。
静止早退(`LPI_STILL_TH`)在这两块卡上收益≈0(零偏移时访存全命中缓存),留着是给缓存更弱的手机 GPU,收益未验证。

### 切镜检测与按住帧跳过(2026-09-18)

两条都在 `lpi_pair_stats` 里一次 CPU 扫描解决:16×9 个块、每块固定取 8×8 个点,
和分辨率无关,一共 9216 次取样,微秒级。判据放在 `lpinterp_ocl.c`(不依赖 mpv)所以能单测,
见 `bench/lpi_pair_test.c` —— 改阈值前先跑它。

**阈值是实测标定的,不是拍脑袋**(2396 个相邻帧对 + 240 帧全分辨率复核):

| | 整体差 | 超标块占比 |
|---|---|---|
| 真实快速运动 | 24~35 | 36%~59% |
| 真正的切镜 | 76~165 | 84%~100% |

☠ **MVTools 默认的 `thSCD2=51%` 在动画上会误杀** —— 实测快速运动有 54%~59% 的块超标。
所以要「整体差 ≥50 **且** 超标块 ≥70%」两个条件同时满足。自检里有一条专打这个退化。

**按住的原画跳过光流**(动画一拍二/一拍三):判为按住时中间帧就是 prev 本身,
`mp_image_new_ref` 只加引用计数、不拷像素,整个光流 + warp 一起省掉。

☠ **判「按住」必须同时看整体差和最大块差**。只看整体差的话,静止背景上一个小物体在动
(整帧平均差还是 0,但那一块差 15)会被当成没动,那块的运动就永远补不出来。自检里也有这一条。

| 判据 | 跳过比例 | 最差那一对 PSNR | 中位 |
|---|---|---|---|
| mean≤1 且 max_block≤4 **(选用)** | **39.1%** | 47.1 dB | 68.3 dB |
| 放宽到 max_block≤6 | 39.9% | 34.2 dB | 68.2 dB |

放宽只多拿 0.8%,却把最差那一对从 47.1 拉到 34.2 dB —— 不值。40dB 以上人眼看不出。

⚠️ **降采样标定会高估**:同一批素材在 320×180 上量到 66.5% 可跳过,全分辨率复核只有 39.1%
—— 降采样把局部差异平均掉了。**标定这类判据必须在实际分辨率上复核**。

### 硬字幕保护:不需要写代码(2026-09-18)

原计划按工业做法做「图形性 AND 静止性」两级与门 + 膨胀扩边。**实测发现根本不用做。**

造了对抗性测例(静止硬字幕压在 |f0−f1|=22.1 的强运动背景上,用 drawtext 烧进真实番剧帧),
用「有字幕版减无字幕版」做出精确的文字掩膜,只量文字像素:

| | 文字像素 RMSE | 非文字区 RMSE |
|---|---|---|
| 补帧 | **3.49** | 27.33 |
| 直接混合 | 3.49 | — |

文字误差 1.4%,和直接混合完全相同,比画面其余部分好 8 倍。
**根因:高对比度的静止文字主导了所在块的 SAD,块匹配天然锁在零矢量上。**
遮挡掩膜对这件事只有 3% 贡献 —— 保护来自块匹配本身,不是掩膜。

### 安卓补帧第三轮:搜索改到降采样平面上做(2026-09-18)

天玑 8100 实测「卡到自动关掉补帧」。先定位是算力不够还是带宽不够 —— 同一份代码两块卡对比:

| 档位 | Intel UHD 770 | RTX 5060 | 倍数 |
|---|---|---|---|
| R=5 W=32 | 3.7 ms | 0.7 ms | 5.3× |
| R=16 W=8 | 19.7 ms | 2.6 ms | **7.6×** |

两卡算力差 ~25 倍,带宽差 ~9 倍。**实测倍数贴着带宽走,不贴算力** —— 瓶颈在访存。

根因在 `lpi_search` 的采样循环:每个采样点直接从**全分辨率**平面抓 3 个字节,
行距列距都是 `1<<resShift`,一条 64 字节 cache line 只用得上 4 个字节;
1080p 的 Y 平面有 2MB,放不进移动 GPU 的 L2。

改法:`lpi_shrink` 把 NV12 盒式平均成一格一个 `uchar4`(Y,U,V,0),搜索只读它。
1080p 是 480×270×4 = 0.5MB,常驻 L2,每个采样点只读一次对齐的 4 字节。
配套两处:
- `lvl_scale` 的步长下限钉到一个低分辨率格 —— 比一格更细的位移在降采样平面上分辨不出来,
  试了只是把同一对像素再比一遍。钉住之后矢量恒为整格倍数,寻址是精确的不是截断的。
- 窗口内 `offs` 本来就恒定(粗层整块覆盖细层),所以整窗共用一组偏移,不必逐采样点重读。

Intel UHD 770 / tri_49 / 5 次中位:

| 档位 | 改前 | 改后 |
|---|---|---|
| R=5 W=16(默认) | 5.2 ms | **3.3 ms** |
| R=16 W=8(顶格) | 19.7 ms | **9.2 ms** |
| SHIFT+1 R=5 W=32(新地板) | — | **1.3 ms** |

质量:三段真实番剧的遮挡掩膜 76.2/37.9/66.7% → 77.4/37.9/67.4%,PSNR 差 ≤0.15 dB。
代价:`lpi_shrink` 每帧多 0.45 ms(核显),而且**按住的原画跳过了光流也躲不掉它**。
默认档净赚,顶格档大赚。

第三把旋钮:光流工作分辨率 `res_shift`(270p → 135p),搜索格子数直接变四分之一。
**降级顺序实测定的:窗口 → 半径 → 分辨率。** 同样 3.6 ms,
「半分辨率 + 最好的窗口半径」掩膜 78/47/77%,「全分辨率 + 最差的窗口半径」79/45/73% ——
打平甚至更好,而且后者还便宜。所以分辨率是最后一道闸,过了它只能整个关掉补帧。

☠ **`adapt` 原来拿峰值帧对预算,是错的。** 动画 39% 的相邻帧是按住的原画(0 ms),
但 adapt 只在真跑了光流的帧上调用 —— 拿 100% 的贵帧去对 100% 的预算,
实际负载只有 59%。改成每个源帧都记一笔(跳过的记 0)走指数滑动平均,
等效预算放宽 1.7 倍。计时起点也从光流挪到上传(降采样、上传都是每帧要付的),
但要躲开 `ensure_ocl` 第一次编 kernel 的几百毫秒,否则第一帧就把档位打到底。

☠ **`lpi_set_res_shift` 在值相同时提前 return,而 create 恰好用相同值调它** ——
`low_w/low_h` 从没被赋值,恒为 0,光流场 0 格,补帧结果和直接混合逐字节相同。不报错。

☠ **切档时前一帧的降采样版是按老档降的,必须一起重降。** 漏了流场会悄悄错。
`bench/lpi_real.c` 里钉了门禁,但**第一版是假绿的**:自检里每次切档都推了两帧,
把两帧都重降了,刚好盖住 bug。照搬滤镜真实时序(切完档只推一帧)才红。

试过但删掉的:
- `lpi_shrink` 内部把逐字节 `min()` 换成「内部无分支 + 边缘一条判断」—— 10 次中位没差别,撤回。
- 把 `lpi_occ` 也改到降采样平面上 —— 实测它只占 0.3 ms(高档位上甚至在噪声里),不值得动。

**FSR 不能拿来补帧。** FSR 1 是空间放大;FSR 2/3 的放大要引擎给运动矢量 + 深度;
FSR 3 的帧生成(Fluid Motion Frames)同样要 render-resolution 的运动矢量和深度缓冲,
[官方集成文档](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-interpolation/)
明确写着这是引擎级输入。视频只有最终画面,拿不到这些。
AFMF 是 AMD 驱动里的闭源功能、只在 RDNA 上跑,移不到 Mali。
FSR 家族里唯一能借的是「贵的那步在低分辨率上做」—— 而这正是本轮在做的事。

### N 卡加速(TensorRT):直接用上游完整包(2026-09-18)

用户 5060 上 RIFE 通用版(DirectML)开了就被丢帧闸撤掉,定了「加入 N 卡特供方案」「TensorRT 单独一个 Release,
A 卡那些直接默认用」;随后自己重打的小包发不上去,又定「别人项目有完整的包,直接解析完整的包」。

- **上传走不通**:1.3GB 的七个包,本机并行传全部 rc=1、CI 上 `gh release create` 第一个文件就 HTTP 500
  「Error saving asset」,重试五次仍然 500。已经上传成功的都 ≤63MB。**所以不再自己托管大包**:
  播放器直接下上游 hooke007/mpv_PlayKit 的 vsNV 分卷(2.9GB,16 段并发约 10MB/s),按区段校验 sha256,
  用**系统自带的 tar.exe**(Windows 10 1803 起,libarchive 能读 7z)只解需要的几个文件(实测 104 秒),解完删包。
  下过一次的包校验能对上就不再重下(上次解压被打断时用得上)。
- **只要 6 个文件**:vstrt + cudart + nvinfer + nvinfer_plugin + nvonnxparser + trtexec,
  再加一份按显卡架构的 `nvinfer_builder_resource_smXX`(7.5=RTX20,8.6=RTX30,8.9=RTX40,12.0=RTX50)。
  少 nvinfer_plugin 的话 trtexec 报「Unable to open library」;cuBLAS / cuDNN / cuFFT 用不着
  (k7sfunc 调 TRT 时 use_cublas / use_cudnn 都是 False)。TensorRT 10 不支持 Pascal(GTX 10 系)。
- **引擎必须在用户机器上建**(和显卡、驱动绑定),一次 40~55 秒。**装完就预建**:
  起一个 vo=null 的 mpv 实例喂 `av://lavfi:color=...s=1920x1080`,建出来的引擎和播放时要的是同一个。
  为此脚本对 TRT **一律补黑边到 1920×1080** 再补帧、补完裁回去 —— DRBA 的 TRT 引擎是静态形状,
  不统一尺寸的话每换一种分辨率就现建 45 秒,而且会被丢帧闸当成「没生效」撤掉。720p 片实测复用同一个引擎。

| RTX 5060 Laptop,真实 HEVC 10bit 1080p 动画,LinPlayer 里量 8 秒 | 通用版(DirectML) | N 卡加速(TensorRT) |
|---|---|---|
| RIFE 补 1 倍 | 4% 丢帧(降到 900p 才跑得动) | 1080p 原画,丢 0 帧 |
| DRBA 补 1 倍 | 丢 0 帧 | 丢 0 帧 |
| RIFE 补 2 倍(24→72) | 没测(余量不够) | 丢 0 帧 |

### 三个排查坑

- **同一个进程里 Python 只有一份,脚本的全局变量却会被回收。** 脚本为了藏 trtexec 的控制台窗口替换了
  `subprocess.Popen.__init__`,替换进去的函数引用脚本全局 `_popen_init` —— 第二次执行脚本时那个全局已经没了,
  建引擎当场 `NameError`(预建 RIFE 实测撞上;mpv.exe 每次新进程,单跑永远复现不出来)。
  原函数要用**默认参数**带进去,并且整个进程**只替换一次**(在 subprocess 模块上打标记)。
- **`bus.NewErr` 从来不做格式化。** 它把第二个参数当原样文本,而核心层有 121 处写的是
  `NewErr(code, "%v", err)` —— 用户看到的就是字面的「%v」,原因整个丢了(补帧装包失败时才发现)。
  已改成:带 % 且有参数就 Sprintf,不带 % 的仍把参数当 Detail。
  ☠ 直接把 args 透传给 Sprintf 会让 `go vet` 把 NewErr 认成 printf 包装器,于是第二种调法全仓报错 —— 拷一份再转发。
- **等 mpv 的 END_FILE 之前要先抽干事件队列**:预建是一个实例里先 DRBA 后 RIFE,
  RIFE 刚 loadfile 就吃到上一段留下的 END_FILE,被当成「放完了」,引擎一次都没建(连 trtexec 日志都没有)。
  先抽干、再等 START_FILE、最后等 END_FILE,每一步之后还要核对**这一个算法**的引擎文件在不在。

### 两个排查坑

- **`nm | grep -q` 在 `set -o pipefail` 下会把「找到了」报成「没找到」**:grep 命中即退出,nm 吃 SIGPIPE,整条管道判失败。
  第一轮 CI 就这样把导出了的 `mpv_create` 报成缺失(`build-core-android.sh` 里早有同一条注释,又踩一次)。先落文件再 grep。
- **mpv 日志事件的正文不带模块名**(`vapoursynth` / `lpinterp` 在 `prefix` 字段里)。补帧报错原来只按正文认,
  `Script evaluation failed` 这种一条都认不出来,只能靠 15 秒「没生效」兜底。现在按 prefix 认(有单测)。

### 安卓调研时排除掉的路(2026-09-17)

- **蹭不到系统补帧**:手机厂商视频补帧全是包名白名单,没有 API、没有 manifest 标记;芯片厂的(高通 AFME、联发科 MFRC)只给游戏或整机厂。
- **现有 libmpv.so 里做不了**:它是 media-kit v1.1.11 的现成包(不是自编),`--disable-filters`,没有 vapoursynth / minterpolate;GLSL 用户着色器拿不到上一帧。
  mpv 内建 `interpolation` 只是帧混合(去抖动,不是运动补偿),这份 0.36 的 so 只认旧名 `override-display-fps`。
- **minterpolate 重编进去也不实时**:单线程无 SIMD,本机 i5-13500HX 单核 720p 2× 只有 17fps。
- RIFE 上手机:骁龙 8 Gen 3 上 720p 约 10fps,瓶颈在 Resize / GridSample 算子,换推理框架救不了。
- **真能跑的两条(都要先自编 libmpv),选了第 2 条**:
  1. ANVIL(arXiv 2603.26835,开源 `NihilDigit/mpv-android-anvil`,MIT):编进 libmpv 的 `vf=anvil`,骁龙 8 Gen 2/3 NPU,1080p 30→60。
     限制:只吃 H.264、必须软解、有 B 帧的片实际只有 30→45。番剧多是 HEVC,覆盖面窄。
  2. OpenCL 光流:SVPlayer(闭源,基于 mpv,推荐骁龙 855+)证明可行;开源参考 HopperRender(`HopperLogger/mpv-frame-interpolator`,mpv 版文件无许可头、作者的 Windows 版是 GPL-3.0 —— 只能进 GPL 构建的 libmpv;17 star,主打 Linux,安卓无人验证)。
