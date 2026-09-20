// surface 渲染器(SPEC 7.2 7.3):Preact 的 DOM 变更 → ops → 一帧一条发给壳。
//
// ★ 一份 Preact、一份 document,多个 surface 共存;节点 id 全局唯一,
//   ops 靠 dom.js 的「接上根时才路由」分流。回调号也全局唯一 ——
//   号跨 surface 复用的话,壳上一次迟到的点击会打到另一块 UI 上。

function makeRenderer(makeScope, host) {
  const surfaces = new Map()
  const fns = new Map() // 回调号 → {fn, node}
  const fnByPath = new Map() // "节点id.属性名" → 回调号
  let fnSeq = 1

  const router = {
    push(surfaceId, op) {
      const s = surfaces.get(surfaceId)
      if (!s) return
      s.ops.push(op)
      schedule(s)
    },
    /** 函数属性 → {$fn:n};其余递归。同一个 id.属性 重复设值时旧号当场作废。 */
    value(id, name, v) {
      return serialize(id + '.' + name, v)
    },
    dropFn(id, name) {
      const path = id + '.' + name
      const n = fnByPath.get(path)
      if (n === undefined) return
      fns.delete(n)
      fnByPath.delete(path)
    },
    /** 节点连同子树被删:它们的回调号一起作废,否则壳上迟到的点击会调进已卸载的闭包。 */
    dropSubtree(_surfaceId, node) {
      const stack = [node]
      while (stack.length) {
        const n = stack.pop()
        for (const k of Object.keys(n.__lpprops || {})) router.dropFn(n.__lpid, k)
        for (const c of n.childNodes) stack.push(c)
      }
    },
  }

  function serialize(path, v) {
    if (typeof v === 'function') {
      const old = fnByPath.get(path)
      if (old !== undefined) fns.delete(old)
      const n = fnSeq++
      fns.set(n, v)
      fnByPath.set(path, n)
      return { $fn: n }
    }
    if (v === null || typeof v !== 'object') return v
    if (Array.isArray(v)) return v.map((x, i) => serialize(path + '.' + i, x))
    const out = {}
    for (const k of Object.keys(v)) out[k] = serialize(path + '.' + k, v[k])
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

  return {
    mount(surfaceId, render) {
      const s = { id: surfaceId, ops: [], scheduled: false, frame: 0 }
      s.root = document.createRoot(surfaceId)
      surfaces.set(surfaceId, s)
      // 根要先告诉壳:后面所有 parent 为 0 的 insert 指的就是它
      s.ops.push({ op: 'root', id: 0 })
      preact.render(preact.h(Boundary, { __surface: surfaceId, __render: render }), s.root)
      flush(s)
      host.surfaceState(surfaceId, 'ready')
      return surfaceId
    },
    unmount(surfaceId) {
      const s = surfaces.get(surfaceId)
      if (!s) return
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
    surfaceIds: () => Array.from(surfaces.keys()),
    /** 给测试与基准用:当前挂着多少个回调号。泄漏了这个数会一路涨。 */
    fnCount: () => fns.size,
  }
}
