# 10. `<Player>` 区域跟随(D268)—— spike 结论

> 2026-09-21。到期:阶段 ②。读代码 + 读 mpv render API 契约得出,没有另写原型 ——
> 结论的关键在「桌面视频现在到底是怎么画的」,那件事读代码就能定。

## 一句话

**D268 的前提不成立了**:桌面视频**不是**独立子窗口。
「区域跟随」不需要追窗口,`<Player>` 在桌面可以实现;
真正的约束是**全局只有一个 mpv render context**,以及它现在的销毁时机。

## 前提为什么不成立

D268 写于 Rust + Tauri 时期:那时界面是 WebView,视频只能另开一个原生窗口叠上去,
所以才有「窗口跟着那块区域走」这件事。**2026-09-04 Rust 栈已从仓库删除**。

现在的桌面(Avalonia)走的是 SPEC §7.2 的**通道 B**:UI 持有 GL 上下文,
核心层往 UI 给的 FBO 里渲染。落点是一个普通的 `OpenGlControlBase`:

| 事实 | 出处 |
|---|---|
| 视频是视觉树里的一个控件,不是窗口 | `apps/windows/LinPlayer.Desktop/Views/PlayerPage.cs:31` `MpvGlView : OpenGlControlBase` |
| 每个合成帧按控件当前 `Bounds` 渲一次 | `PlayerPage.cs:69-91`(`Bounds.Width * RenderScaling` → `lp_gl_render(fb, w, h, 1)`) |
| mpv 侧是 `mpv_render_context`,不是 `--wid` | `core/player/player.go:534` `C.lp_rc_create(&out, mpvH, getProcAddress, ctx)` |

也就是说:把 `MpvGlView` 放进插件 surface 给的那个位置,画面就在那块区域里 ——
布局系统本来就在做「跟随」,不需要另写一套窗口跟随、裁切、层级。

## 真正的约束:render context 是全局单例

| 事实 | 出处 |
|---|---|
| `rctx` 是包级单例,`GLInit` 第二次调用直接返回 0(幂等) | `core/player/player.go:530-532` |
| `GLUninit` 无条件销毁它 | `core/player/player.go:688-697` |
| 控件从可视树摘下时就调 `lp_gl_uninit` | `PlayerPage.cs:101-105` `OnOpenGlDeinit` |

推论,三条都要在实现时兜住:

1. **同一时刻只能有一个视频宿主**。两个 `MpvGlView` 同时活着的话,它们朝**同一个**
   render context 要帧,两块区域画的是同一路画面(不是两路视频)。这与 D276
   「全局只有一个 mpv 实例」一致,但要在壳这边做仲裁,而不是靠插件自觉。
2. **`lp_gl_uninit` 现在是共享资源的销毁点**。插件页里的 `<Player>` 离开可视树时,
   它会把**主播放页**的 render context 一起销毁 —— 表现是主画面突然全黑、
   `wants_redraw` 恒 0、一条错误都不报(和 `PlayerPage.cs:27` 记的那个「No render context set.」
   是同一类症状,只是方向反过来)。必须改成引用计数:最后一个持有者消失时才 uninit。
3. **起播要排在 GL 就绪之后**,这条已经由核心层兜着(`core/player/player.go:703` `waitRenderCtx`),
   插件路径不需要重复一遍。

## 结论

`<Player>` 桌面实现**可做**,代价是两件小事,不是一条新通道:

- 给 `lp_gl_init` / `lp_gl_uninit` 在壳这一侧加引用计数(哪个控件先来后到都不影响);
- 加一条「同一时刻只有一个视频宿主」的仲裁:插件页的 `<Player>` 出现时,
  官方播放页那一份让位;冲突时以用户当前看得见的那一块为准。

手机 / TV 走的是通道 A(SurfaceView + `wid`,`core/player/player.go:436`),
那边「区域跟随」等于把 SurfaceView 摆到那块位置,同样不需要追窗口,
但 SurfaceView 的层级(zOrderOnTop)与插件 UI 的叠放顺序要另外定 —— 留给实现时处理。

## 没做的

没写原型:结论不依赖原型。真正的风险在上面第 2 条(共享销毁点),
那一条要在实现 `<Player>` 时用**先红后绿**的测试钉住 ——
「插件页的 `<Player>` 卸载后,主播放页还有画面」。
