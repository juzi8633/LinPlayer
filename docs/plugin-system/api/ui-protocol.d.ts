/**
 * 插件 UI 渲染协议 —— 核心层 ↔ 壳(Avalonia / Compose)的内部契约,插件看不到。
 * 正文:SPEC 7.2 7.3。@see D23 D133 D135 D318 D319 D134 D104
 * 走控制通道:核心 → 壳是事件 `plugin.ui` / `plugin.ui.surface`,壳 → 核心是命令 `plugin.ui.*`。
 */

export type SurfaceKind = 'page' | 'block' | 'overlay' | 'globalOverlay' | 'panel' | 'osd' | 'window' | 'settingsSection' | 'homeSection' | 'wallpaper'

// ───── 壳 → 核心(命令)─────

/** 壳要在某个槽位挂插件 UI。返回 surfaceId。 */
export interface MountCmd {
  cmd: 'plugin.ui.mount'
  plugin: string
  /** 贡献点里的 id(页面 id / 区块 id)。 */
  target: string
  kind: SurfaceKind
  props: Record<string, unknown>
}

export interface UnmountCmd { cmd: 'plugin.ui.unmount'; surface: string }

/** 用户交互:fn 是 props 里 `{"$fn": n}` 的 n;预算 1 秒。@see D53 */
export interface EventCmd { cmd: 'plugin.ui.event'; surface: string; fn: number; args: unknown[] }

/** 虚拟列表向 JS 要可见范围。@see D134 */
export interface RangeCmd { cmd: 'plugin.ui.range'; surface: string; list: number; from: number; to: number }

/** 视口/断点/安全区变化(节流到帧)。@see D217 D426 */
export interface ViewportCmd { cmd: 'plugin.ui.viewport'; surface: string; width: number; height: number; breakpoint: 'compact' | 'medium' | 'expanded'; insets: [number, number, number, number] }

// ───── 核心 → 壳(事件)─────

/** 一帧一条:16ms 内的全部变更合批。@see D318 */
export interface FrameEvent {
  name: 'plugin.ui'
  surface: string
  frame: number
  ops: Op[]
}

export type Op =
  | { op: 'create'; id: number; type: string }                       // 未知 type:画「需要更新 LinPlayer」占位(D319)
  | { op: 'props'; id: number; set?: Record<string, PropValue>; unset?: string[] } // 未知属性忽略(D319)
  | { op: 'insert'; parent: number; id: number; before: number | null }
  | { op: 'remove'; id: number }                                     // 连同子树
  | { op: 'text'; id: number; value: string }                        // 文本节点,只能在 Text 下
  | { op: 'canvas'; id: number; cmds: CanvasCmd[] }                  // 一帧的绘制指令流(D104)
  | { op: 'root'; id: number }                                       // 指定根节点

/** 函数属性序列化成回调号;其余是 JSON 值。 */
export type PropValue = null | boolean | number | string | PropValue[] | { $fn: number } | { [k: string]: PropValue }

/** Canvas 指令:[方法名, ...参数];属性赋值写成 ['set', 属性名, 值]。 */
export type CanvasCmd = [string, ...(number | string | boolean | null)[]]

/** surface 状态:骨架屏 / 首帧就绪 / 出错(错误边界)。@see D271 D136 */
export interface SurfaceStateEvent {
  name: 'plugin.ui.surface'
  surface: string
  state: 'loading' | 'ready' | 'error'
  error?: { message: string; plugin: string; dev?: { stack: string } }
}
