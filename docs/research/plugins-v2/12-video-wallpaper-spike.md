# 视频壁纸怎么实现(D443 的 spike)

> 2026-09-21。D443 原话:「各端视频壁纸的实现方式(第二个轻量 libmpv 实例或平台原生播放器)**先 spike**」。
> 这份结论只回答「用什么放」,不回答「壁纸接管位怎么设计」—— 那在 SPEC 11.5 里已经定了。

## 一句话结论

| 端 | 结论 | 靠什么 |
|---|---|---|
| 安卓(手机 / TV) | **能做,现在就能做** | 已经在依赖里的 ExoPlayer(`media3-exoplayer:1.11.0`),独立 `SurfaceView` 垫在 Compose 内容下面 |
| 桌面(Windows / Linux) | **2.0.0 不做**,降级成静态图 + Canvas / 着色器动态壁纸 | 第二条 GL 通道这条路被实测否掉,见下 |

## 桌面:为什么第二个 libmpv 实例这条路走不通

三件事叠在一起:

1. **一个进程只能有一条 GL 通道。** 这不是推断,是仓库里记着的实测结论:
   `docs/go-migration/UI_PC.md:360` —「主窗与播放窗**共用同一条 GL 通道** —— 一个进程只能有一条
   (`SPEC.md` §7.2 通道 B 约束 4)。实测:连开两条会卡死」。
2. **核心层的 render context 是全局单例,不是句柄。** `core/player/player.go:179-184`:
   ```go
   // 一个进程只能有一条 GL 通道(SPEC §7.2 约束 4),所以是全局单例而不是句柄。
   var (
       mpvH    unsafe.Pointer // mpv_handle*
       rctx    unsafe.Pointer // mpv_render_context*
   )
   ```
   壁纸要第二个实例,就得把 `mpvH` / `rctx` 从包级变量改成句柄,并且把 FFI 上
   `lp_gl_init` / `lp_gl_render` / `lp_gl_uninit` 三个导出函数全部改成收句柄 ——
   那是**三端绑定层一起改**的动静。
3. **`lp_gl_uninit` 现在无条件销毁**(`core/ffi/main.go:327`)。这正是 D561 给 `<Player>`
   标出来的那条约束:插件页的 `<Player>` 卸载会把主播放页的画面一起弄没。
   壁纸是「一直在后台放着」的东西,和播放页同时存在是常态 —— 它比 `<Player>` 更早撞上这条。

所以桌面视频壁纸和 `<Player>` 是**同一件地基**:GL 通道要么带引用计数、要么改成句柄。
在那件事做完之前,桌面做视频壁纸只会把「插件页开了之后主播放页黑屏」这个故障提前。

### 桌面上被考虑过但否掉的三条

| 路子 | 为什么否 |
|---|---|
| mpv 用 `--vo=gpu` 开一个子窗口垫在主窗后面 | 这正是 D561 判定「前提作废」的那套独立子窗口方案:现在的视频是视觉树里的 `OpenGlControlBase`(通道 B),再引一个原生子窗口等于把已经删掉的那套 airspace 问题请回来 |
| 引一个独立的播放库(LibVLCSharp 之类) | 为了壁纸多一个几十 MB 的原生依赖,而它解码的还是同一批格式;仓库红线是「不为一个实现造抽象」,这条是「不为一个壁纸造第二个播放器」 |
| 自己用 ffmpeg 解帧再往 Canvas 上贴 | 每帧一次 CPU→GPU 上传,1080p 30fps 是 178 MB/s 的拷贝;而壁纸是**一直在跑**的东西 |

### 桌面的降级口径(2.0.0)

- 静态图壁纸:照常。
- **动态壁纸走 Canvas / 着色器**:SDK 里 `<Canvas animate>`(D19 D104 D105)和着色器能力都已经有了,
  程序生成的动态背景不需要视频解码,也不碰 GL 通道那条独木桥。
- 用户挑了一个视频壁纸包时:显示它的**封面帧**(静态),并说清「这一端暂时只放第一帧」——
  不是静默放一张图,也不是报错说包坏了。

## 安卓:为什么现在就能做

- `androidx.media3:media3-exoplayer:1.11.0` **已经在依赖里**(`apps/android/app/build.gradle.kts:182`),
  它和 mpv 是**两套并存的内核**(同文件 :176 的注释:「mpv 走核心层里的 libmpv;ExoPlayer 走安卓平台自己的……
  所以是**并存可切**,不是取代」)。壁纸用 ExoPlayer 不占播放用的那一路。
- 垫图层用 `SurfaceView` 而不是 `TextureView`,和播放页现在的做法一致
  (`ui/player/ExoEngine.kt:204`:「独立合成层」)。壁纸这一层放在 Compose 内容**下面**,
  `setZOrderMediaOverlay(false)`。
- 低分辨率(D443):给 ExoPlayer 设 `maxVideoSize`(如 720×405)让它在多码率源上挑小的;
  单码率源就按原样解,壁纸本来就该是小文件。

### D443 的三个「要暂停」落在哪

| 时机 | 怎么知道 |
|---|---|
| 进播放页 | 插件系统已经有 `player.start` / `player.end` 事件(SPEC 9.1);壁纸层订阅它,start 暂停、end 恢复 |
| 应用到后台 | `Lifecycle.Event.ON_STOP` / `ON_START`(壳里已经有 `app.foreground` / `app.background` 两个事件在发) |
| 系统省电 | `PowerManager.isPowerSaveMode` + 监听 `ACTION_POWER_SAVE_MODE_CHANGED`;省电时**不恢复** |

☠ 三个条件是**与**的关系:任何一条要求暂停就暂停,三条都允许才放。
写成「最后一个事件说了算」的话,「息屏回来 + 省电模式开着」会把它放起来,
而用户打开省电模式正是为了别让后台有东西在解码。

## 还没验的(写清楚免得被当成已验)

- **没有真机实测**:这份 spike 是读代码 + 读仓库里已有的实测记录得出的,没有起一个双 GL 通道的进程去撞一次。
  「连开两条会卡死」引的是 `UI_PC.md:360` 记的那次实测,不是这一轮重新量的。
- ExoPlayer 做壁纸层的**功耗**没量。低分辨率是按 D443 的要求写进结论的,不是量出来的阈值。
- Linux 上 `SurfaceView` 那套不适用(那是安卓的),而 Linux 桌面和 Windows 桌面走同一条结论(不做)。
