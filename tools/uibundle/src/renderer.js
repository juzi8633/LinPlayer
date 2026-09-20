// surface 渲染器(SPEC 7.2 7.3):Preact 的 DOM 变更 → ops → 一帧一条发给壳。
//
// ★ 一份 Preact、一份 document,多个 surface 共存;节点 id 全局唯一,
//   ops 靠 dom.js 的「接上根时才路由」分流。回调号也全局唯一 ——
//   号跨 surface 复用的话,壳上一次迟到的点击会打到另一块 UI 上。

function makeRenderer(makeScope, host, sdk) {
  const surfaces = new Map()
  const fns = new Map() // 回调号 → 函数
  // 节点id → Map(序列化路径 → 回调号)。
  // ☠ 按**节点**分桶而不是一张全局 path 表:回收时要么按属性名前缀删(属性更新)、
  //   要么整节点删(子树卸载),两种都要能在不遍历全表的前提下找齐**嵌套**的号。
  //   上一版只记顶层属性名,`{items:[{onPress}]}` 这种号永远回收不掉,fns 只增不减。
  const fnsByNode = new Map()
  let fnSeq = 1

  const router = {
    push(surfaceId, op) {
      const s = surfaces.get(surfaceId)
      if (!s) return
      s.ops.push(op)
      schedule(s)
    },
    /** 函数属性 → {$fn:n};其余递归。同一个 id.属性 重复设值时**连同嵌套的**旧号一起作废。 */
    value(id, name, v) {
      router.dropFn(id, name)
      return serialize(id, id + '.' + name, v)
    },
    /** 作废 id.name 以及它下面所有嵌套路径上的号。 */
    dropFn(id, name) {
      const m = fnsByNode.get(id)
      if (!m) return
      const pre = id + '.' + name
      for (const [path, n] of m) {
        if (path === pre || path.startsWith(pre + '.')) {
          fns.delete(n)
          m.delete(path)
        }
      }
      if (!m.size) fnsByNode.delete(id)
    },
    /** 节点连同子树被删:它们的回调号一起作废,否则壳上迟到的点击会调进已卸载的闭包。 */
    dropSubtree(_surfaceId, node) {
      const stack = [node]
      while (stack.length) {
        const n = stack.pop()
        const m = fnsByNode.get(n.__lpid)
        if (m) {
          for (const num of m.values()) fns.delete(num)
          fnsByNode.delete(n.__lpid)
        }
        for (const c of n.childNodes) stack.push(c)
      }
    },
  }

  function serialize(nodeId, path, v) {
    if (typeof v === 'function') {
      let m = fnsByNode.get(nodeId)
      if (!m) {
        m = new Map()
        fnsByNode.set(nodeId, m)
      }
      const old = m.get(path)
      if (old !== undefined) fns.delete(old)
      const n = fnSeq++
      fns.set(n, v)
      m.set(path, n)
      return { $fn: n }
    }
    if (v === null || typeof v !== 'object') return v
    if (v.$grad) return v // 渐变对象:已经是可序列化的纯数据,别当普通对象再走一遍
    if (Array.isArray(v)) return v.map((x, i) => serialize(nodeId, path + '.' + i, x))
    const out = {}
    for (const k of Object.keys(v)) out[k] = serialize(nodeId, path + '.' + k, v[k])
    return out
  }

  // 顺序是定死的:先有 router 才能造 document,先有 document 才能造绑着它的那份 Preact
  const document = makeDom(router)
  const scope = makeScope(document)
  const preact = scope.preact
  const hooks = scope.preactHooks

  /* 把 dom.js 造的假事件还原成插件写的参数。`options.event` 是 Preact 的公开钩子,
     在处理函数拿到事件**之前**跑。SDK 里所有回调都是 0 或 1 个参数
     (onPress() / onChangeText(text) / renderItem(i)),所以取第一个就够。 */
  preact.options.event = (e) => (e && e.__lpargs ? e.__lpargs[0] : e)

  /**
   * 错误边界(D136):一块崩了只让那一块显示「出错」,不带倒整页,更不带倒别的插件。
   * 渲染期的错误走 useErrorBoundary;事件回调里抛的由 event() 兜。
   */
  function Boundary(props) {
    const [err, setErr] = hooks.useState(null)
    hooks.useErrorBoundary((e) => setErr(e))
    if (err) {
      host.surfaceState(props.__surface, 'error', {
        message: String((err && err.message) || err),
        stack: (err && err.stack) || '',
      })
      return null
    }
    return props.__render()
  }

  function flush(s) {
    s.scheduled = false
    if (!s.ops.length) return
    const ops = s.ops
    s.ops = []
    host.frame(s.id, ++s.frame, ops)
  }

  /** 一次交互里的多次 setState 落进同一帧(D318)。 */
  function schedule(s) {
    if (s.scheduled) return
    s.scheduled = true
    host.nextFrame(() => flush(s))
  }

  /* 视口与安全区(SPEC 7.7,D217 D425 D426)。
     ★ 一个 surface 一份:同一个插件的页和侧栏区块视口本来就不一样。
     ☠ 默认值不能是 0:插件在收到第一条 viewport 之前就要渲染一次,
       宽度读成 0 的话按断点分支的布局会全走 compact,而那一帧是用户真看得见的。 */
  const viewports = new Map()
  const vpListeners = new Map()

  function viewportOf(id) {
    let v = viewports.get(id)
    if (!v) {
      v = { width: 1280, height: 720, breakpoint: 'expanded', formFactor: 'desktop', insets: { top: 0, right: 0, bottom: 0, left: 0 } }
      viewports.set(id, v)
    }
    return v
  }

  /**
   * useViewport():订阅当前 surface 的视口,变了就重渲染。
   *
   * ☠ 订阅**在渲染期就挂上**,不放进 useEffect:没有 requestAnimationFrame 时
   * Preact 的 effect 要等 100ms 才跑,而壳常常在挂载后立刻报一次尺寸 ——
   * 那一条正好落在这个窗口里,没人接。表现是「安全区永远是 0」,不报错。
   */
  function useViewport() {
    const id = hooks.useContext(SurfaceCtx)
    const [, bump] = hooks.useState(0)
    const ref = hooks.useRef(null)
    if (!ref.current) {
      ref.current = () => bump((n) => n + 1)
      let set = vpListeners.get(id)
      if (!set) {
        set = new Set()
        vpListeners.set(id, set)
      }
      set.add(ref.current)
    }
    hooks.useEffect(() => () => {
      const set = vpListeners.get(id)
      if (set) set.delete(ref.current)
    }, [id])
    return viewportOf(id)
  }

  const SurfaceCtx = preact.createContext('')

  /* ---------------------------------------------------------------- hooks(SPEC 28 节)

     ☠ 每一个在 plugin-sdk.d.ts 里声明的 hook 都必须**挂上**。
       不挂的表现是插件拿到 undefined —— 报错报在插件那边,看起来像插件写错了。
       后面那个命名空间还没实现的,挂一个当场说人话的版本,不要留空。 */

  // 环境(主题 + 减少动态效果)由壳报上来,一份全局。
  let env = { reducedMotion: false, theme: { mode: 'dark', tokens: {} } }
  const envListeners = new Set()

  /** 订阅一份全局状态:渲染期就挂上,卸载时摘掉。
   *  ☠ 不放进 useEffect —— 没有 rAF 时 Preact 的 effect 要等 100ms,
   *    而壳常常在挂载后立刻报一次,那一条正好落在这个窗口里(和 useViewport 同一个坑)。 */
  function useSubscription(set, read) {
    const [, bump] = hooks.useState(0)
    const ref = hooks.useRef(null)
    if (!ref.current) {
      ref.current = () => bump((n) => n + 1)
      set.add(ref.current)
    }
    hooks.useEffect(() => () => set.delete(ref.current), [])
    return read()
  }

  function useTheme() {
    const t = useSubscription(envListeners, () => env.theme)
    return hooks.useMemo(() => ({
      mode: t.mode,
      token: (name) => t.tokens[String(name).replace(/^token:/, '')],
    }), [t])
  }

  function useReducedMotion() {
    return useSubscription(envListeners, () => env.reducedMotion)
  }

  /** 一个 key 一组订阅者:同一份设置被两块 UI 读时,改了两块都要跟着变。 */
  function makeKeyedStore(read, write, subscribeHost) {
    const byKey = new Map()
    const notify = (key) => { const s = byKey.get(key); if (s) for (const f of s) f() }
    return function useKeyed(key, initial) {
      let set = byKey.get(key)
      if (!set) { set = new Set(); byKey.set(key, set) }
      const cur = useSubscription(set, () => {
        const v = read(key)
        return v === undefined ? initial : v
      })
      // 宿主那边也可能改(设置页、另一个插件实例):有订阅接口就接上
      hooks.useEffect(() => (subscribeHost ? subscribeHost(key, () => notify(key)) : undefined), [key])
      const put = hooks.useCallback((v) => { write(key, v); notify(key) }, [key])
      return [cur, put]
    }
  }

  const useSetting = makeKeyedStore(
    (k) => sdk.settings.get(k),
    (k, v) => sdk.settings.set(k, v),
    (k, cb) => { const d = sdk.settings.onChange(k, cb); return () => d && d.dispose && d.dispose() },
  )
  const useStorage = makeKeyedStore((k) => sdk.storage.get(k), (k, v) => sdk.storage.set(k, v), null)

  /* usePlayerState 靠 `player` 命名空间(SPEC 9)。它还没实现,所以这里
     挂一个**当场说人话**的版本:返回 undefined 的话插件读 `.paused` 会崩在自己的代码里,
     错误看起来像插件写错了(D555)。 */
  function usePlayerState() {
    hooks.useState(0) // 占住一个 hook 位:实现之后加订阅不会改变 hook 顺序
    if (!sdk.player || typeof sdk.player.observe !== 'function') {
      throw new Error('usePlayerState 需要 player 命名空间,这一版宿主还没有(SPEC 9)')
    }
    return null
  }


  return {
    useViewport,
    useTheme,
    useReducedMotion,
    useSetting,
    useStorage,
    usePlayerState,
    /** 壳报来的环境变化(主题 / 减少动态效果)。 */
    env(v) {
      if (!v) return
      env = {
        reducedMotion: !!v.reducedMotion,
        theme: { mode: (v.theme && v.theme.mode) || 'dark', tokens: (v.theme && v.theme.tokens) || {} },
      }
      for (const fn of envListeners) fn()
    },
    /** 壳报来的视口变化。节流由壳那边做(每帧最多一条)。 */
    viewport(surfaceId, v) {
      const cur = viewportOf(surfaceId)
      if (
        cur.width === v.width && cur.height === v.height &&
        cur.breakpoint === v.breakpoint && cur.formFactor === v.formFactor &&
        JSON.stringify(cur.insets) === JSON.stringify(v.insets)
      ) return
      viewports.set(surfaceId, v)
      const set = vpListeners.get(surfaceId)
      if (set) for (const fn of set) fn()
    },
    mount(surfaceId, render) {
      const s = { id: surfaceId, ops: [], scheduled: false, frame: 0 }
      s.root = document.createRoot(surfaceId)
      surfaces.set(surfaceId, s)
      // 根要先告诉壳:后面所有 parent 为 0 的 insert 指的就是它
      s.ops.push({ op: 'root', id: 0 })
      // 用 Context 把 surface id 传下去:useViewport 要知道自己属于哪一块
      preact.render(
        preact.h(SurfaceCtx.Provider, { value: surfaceId },
          preact.h(Boundary, { __surface: surfaceId, __render: render })),
        s.root,
      )
      flush(s)
      host.surfaceState(surfaceId, 'ready')
      return surfaceId
    },
    unmount(surfaceId) {
      const s = surfaces.get(surfaceId)
      if (!s) return
      viewports.delete(surfaceId)
      vpListeners.delete(surfaceId)
      preact.render(null, s.root)
      router.dropSubtree(surfaceId, s.root)
      surfaces.delete(surfaceId)
      // 卸载后不再发帧:这一份 ops 没人要了
      s.ops = []
    },
    /** 壳回传的一次交互。回调里抛错不许冒到宿主 —— 那会把整个事件循环带走。 */
    event(surfaceId, fn, args) {
      if (!surfaces.has(surfaceId)) return
      const cb = fns.get(fn)
      // 号作废是常态(属性更新过、节点已卸载),不是错误 —— 悄悄丢
      if (!cb) return
      try {
        cb.apply(null, args || [])
      } catch (e) {
        host.surfaceState(surfaceId, 'error', {
          message: String((e && e.message) || e),
          stack: (e && e.stack) || '',
        })
      }
    },
    // h / Fragment / hooks 必须来自**这一份** Preact:hooks 挂的是它的 options,
    // 换一份就全断了(而且断得没有报错,只是 useState 永远拿不到更新)
    preact,
    hooks,
    // 这两个是**真组件**不是字符串:窗口内的项由 JS 渲染,壳只报可见范围(D134)
    VirtualList: makeVirtualList(preact, hooks, 'VirtualList'),
    VirtualGrid: makeVirtualList(preact, hooks, 'VirtualGrid'),
    Canvas: makeCanvasComponent(preact, hooks, host, SurfaceCtx),
    surfaceIds: () => Array.from(surfaces.keys()),
    /**
     * 一个 surface 当前的组件树(调试面板的「UI 树」,D81)。
     *
     * 读的是最小 DOM 的节点树本身,不是把 ops 重放一遍 —— 重放出来的是
     * 「壳应该长什么样」,而真正要查的问题恰恰是「壳长的和这边不一样」。
     * 函数属性折成 `fn:号`:面板显示得出来,而把闭包塞进 JSON 会当场炸。
     */
    tree(surfaceId) {
      const s = surfaces.get(surfaceId)
      if (!s) return null
      const walk = (n) => ({
        id: n.__lpid,
        type: n.__lptype || (n.nodeType === 3 ? '#text' : '?'),
        text: n.nodeType === 3 ? n.data : undefined,
        props: plain(n.__lpprops || {}),
        children: (n.childNodes || []).map(walk),
      })
      const plain = (o) => {
        const out = {}
        for (const k of Object.keys(o)) {
          const v = o[k]
          out[k] = typeof v === 'function' ? 'fn' : v
        }
        return out
      }
      return walk(s.root)
    },
    /** 给测试与基准用:当前挂着多少个回调号。泄漏了这个数会一路涨。 */
    fnCount: () => fns.size,
  }
}

/**
 * VirtualList / VirtualGrid(D134):插件给 itemCount + renderItem(i),
 * 原生端只向 JS 要**可见范围**的那些项。
 *
 * ★ 不另造一条通道:可见范围就是一次普通的回调(`onRange`),走已有的 {$fn} 机制。
 *   新开一条 `plugin.ui.range` 命令的话,回调号作废、surface 卸载这些规矩全要再写一遍。
 * ☠ 首屏那一窗**必须由 JS 先给一批**:等原生端报范围再渲染的话,
 *   第一帧是空的,壳那边量到的「首帧」就成了一个空列表。
 */
function makeVirtualList(preact, hooks, type) {
  return function VirtualList(props) {
    const count = props.itemCount | 0
    const initial = Math.min(count, props.initialWindow || 24)
    const [win, setWin] = hooks.useState({ from: 0, to: initial })
    const from = Math.max(0, Math.min(win.from, Math.max(0, count - 1)))
    const to = Math.min(count, Math.max(win.to, from))

    const kids = []
    for (let i = from; i < to; i++) {
      const child = props.renderItem(i)
      // key 必须是**真实下标**:窗口一滑,同一个位置换成了另一条数据,
      // 用相对下标当 key 会让 Preact 认成「同一项改了内容」,状态串到别的项上
      kids.push(preact.h(preact.Fragment, { key: 'v' + i }, child))
    }
    return preact.h(
      type,
      {
        style: props.style,
        itemCount: count,
        itemHeight: props.itemHeight,
        columns: props.columns,
        horizontal: props.horizontal,
        firstIndex: from,
        onRange: (r) => {
          if (!r) return
          const f = r.from | 0
          const t = r.to | 0
          if (f !== win.from || t !== win.to) setWin({ from: f, to: t })
        },
        onEndReached: props.onEndReached,
      },
      kids,
    )
  }
}

/**
 * Canvas(D19 D104 D105):仿 HTML5 Canvas 2D,一帧的绘制调用**录成指令流**一次性发过去。
 *
 * ★ 录制而不是每调一次过一次桥:一帧几百条调用,逐条过桥的开销比画本身还大。
 * ★ 属性赋值录成 ['set', 名字, 值] —— 原生端照着设,不用为每个属性开一个方法名。
 * ☠ `measureText` 在 JS 这边**量不了**(字体在原生那边):返回一个按字数估的宽度,
 *   并且这件事要写在文档里 —— 悄悄返回 0 的话插件的居中全会歪。
 */
function makeGradient(kind, args) {
  // 指令流里的渐变就是一坨纯数据,原生端照着建自己的画刷。
  // addColorStop 必须**不可枚举**:它要跟着 fillStyle 一起被 JSON 序列化过桥,
  // 枚举得到的话序列化会在函数上炸掉,而那一炸整帧 ops 会凭空消失。
  const g = { $grad: kind, args, stops: [] }
  Object.defineProperty(g, 'addColorStop', {
    enumerable: false,
    value: (offset, color) => { g.stops.push([offset, color]) },
  })
  return g
}

function makeCanvas2D(width, height, cmds) {
  const props = ['fillStyle', 'strokeStyle', 'lineWidth', 'font', 'textAlign', 'textBaseline', 'globalAlpha']
  const methods = [
    'fillRect', 'strokeRect', 'clearRect', 'fillText', 'strokeText',
    'beginPath', 'closePath', 'moveTo', 'lineTo', 'arc', 'quadraticCurveTo', 'bezierCurveTo',
    'rect', 'fill', 'stroke', 'clip', 'drawImage', 'save', 'restore',
    'translate', 'rotate', 'scale', 'setTransform', 'resetTransform',
  ]
  const ctx = { width, height }
  for (const p of props) {
    let v
    Object.defineProperty(ctx, p, {
      get: () => v,
      set: (x) => {
        v = x
        cmds.push(['set', p, x])
      },
    })
  }
  for (const m of methods) {
    ctx[m] = function () {
      cmds.push([m].concat(Array.prototype.slice.call(arguments)))
    }
  }
  ctx.createLinearGradient = (x0, y0, x1, y1) => makeGradient('linear', [x0, y0, x1, y1])
  ctx.createRadialGradient = (x0, y0, r0, x1, y1, r1) => makeGradient('radial', [x0, y0, r0, x1, y1, r1])
  // 字体在原生那边,这里只能估:按 CJK 一个字一个全角宽、其余半角
  ctx.measureText = (text) => {
    const size = parseFloat(String(ctx.font || '14')) || 14
    let w = 0
    for (const ch of String(text)) w += /[　-鿿＀-￯]/.test(ch) ? size : size * 0.55
    return { width: w }
  }
  return ctx
}

/** 逐帧的两档(D105):画得完就 60fps,画不完退到 30fps,而不是排一堆追不上的帧。 */
const FRAME_FAST = 16
const FRAME_SLOW = 33

function makeCanvasComponent(preact, hooks, host, SurfaceCtx) {
  return function Canvas(props) {
    const surfaceId = hooks.useContext(SurfaceCtx)
    const size = { w: props.style && props.style.width, h: props.style && props.style.height }
    const clock = hooks.useRef(null)
    if (!clock.current) clock.current = { t0: Date.now(), last: Date.now(), delay: FRAME_FAST }
    const [, bump] = hooks.useState(0)

    // animate(D105):逐帧重画。下一帧的间隔按**上一帧真画了多久**定 ——
    // 固定 16ms 排下去的话,慢设备上排的帧永远追不上画的帧,越积越卡。
    hooks.useEffect(() => {
      if (!props.animate) return
      let live = true
      let id = setTimeout(function tick() {
        if (!live) return
        bump((n) => n + 1)
        id = setTimeout(tick, clock.current.delay)
      }, clock.current.delay)
      return () => { live = false; clearTimeout(id) }
    }, [props.animate])

    const cmds = []
    // draw 在**渲染期**跑:它只是往数组里记,不碰任何原生资源
    if (typeof props.draw === 'function') {
      const now = Date.now()
      const frame = { time: (now - clock.current.t0) / 1000, dt: (now - clock.current.last) / 1000 }
      clock.current.last = now
      const ctx = makeCanvas2D(size.w || 0, size.h || 0, cmds)
      try {
        props.draw(ctx, frame)
        clock.current.delay = Date.now() - now > FRAME_FAST ? FRAME_SLOW : FRAME_FAST
      } catch (e) {
        // ☠ 吞掉异常等于一块空白 + 零线索。SPEC 7.11:surface 就是错误边界,
        //   画崩了要让整块进 error 态,插件作者才看得见自己写错了什么。
        cmds.length = 0
        host.surfaceState(surfaceId, 'error', {
          message: 'Canvas 绘制出错:' + String((e && e.message) || e),
          stack: (e && e.stack) || '',
        })
      }
    }
    return preact.h('Canvas', { style: props.style, cmds })
  }
}
