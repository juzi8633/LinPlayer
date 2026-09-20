// 最小 DOM(SPEC 7.1):只实现 Preact 真正会碰的那一层,每次变更记成一条 op。
//
// ★ 为什么是「假 DOM」而不是「改 Preact」:Preact 的渲染器写死了 DOM,
//   但它碰到的 DOM 面其实很窄。给它一个假的,Hooks / Context / Fragment / key
//   全部原样可用,而且 Preact 本体一行不改 —— 升级时不用重做适配。
// ☠ 代价是**这一层跟着 Preact 的内部实现走**:哪天它开始用新的 DOM 接口,
//   症状是「某些节点不更新」而不是报错。ui_test.go 那组用例就是为那一天存在的。
//
// ★ 一份 document 管所有 surface:节点 id 全局唯一,ops 先**挂在节点自己身上**,
//   等它被接进某棵树时才知道属于哪个 surface,这时候整条挂着的 ops 一起放出去。
//   Preact 是自底向上建树的(先给父节点塞子节点,最后才把父节点接上根),
//   所以「接上根的那一刻才路由」是唯一不需要猜的时机 ——
//   靠 `options` 之类的内部钩子去猜「现在在渲染哪个 surface」才是会碎的写法。

/** SPEC 7.5 的样式子集。Preact 对名为 `style` 的属性会**逐键**写进 dom.style,
 *  所以这里给每个已知键定义 setter 把它收回来 —— 不用 Proxy,也不用每帧扫一遍对象
 *  (1000 项列表那种场合扫不起)。 */
// ☠ 这张表少一个键,那个样式**连一条 op 都不发** —— 壳那边再怎么实现也没用,
//    而插件作者看到的是「写了没反应」。paddingX / marginX 就这么漏过一整轮。
//    判据在 rt/ui_test.go:它和 .d.ts 的 Style 逐键比对。
const STYLE_KEYS = [
  'direction', 'justify', 'align', 'gap', 'grow', 'shrink', 'basis', 'wrap',
  'width', 'height', 'minWidth', 'maxWidth', 'minHeight', 'maxHeight',
  'padding', 'paddingX', 'paddingY', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
  'margin', 'marginX', 'marginY', 'marginTop', 'marginRight', 'marginBottom', 'marginLeft',
  'position', 'inset', 'top', 'left', 'right', 'bottom', 'zIndex', 'aspectRatio',
  'background', 'opacity', 'radius', 'borderWidth', 'borderColor', 'shadow',
  'backdropBlur', 'overflow',
  'color', 'fontSize', 'fontWeight', 'fontFamily', 'lineHeight', 'textAlign',
  'maxLines', 'letterSpacing',
  'fit', 'tint',
  'translateX', 'translateY', 'scale', 'rotate',
  'transition', 'animation',
]

/* ☠ Preact 写 style 时会给**数字**自动补 px(它以为自己在跟 CSS 打交道),
   于是插件写的 `fontSize: 22` 到壳那边变成字符串 "22px" ——
   壳按数字读,读不到,表现是「样式全都没生效」而不报错。
   这里还原成数字:我们的协议里长度就是数字(设备无关像素,SPEC 7.5),不是 CSS。 */
function unpx(v) {
  if (typeof v !== 'string') return v
  const m = /^(-?\d+(?:\.\d+)?)px$/.exec(v)
  return m ? Number(m[1]) : v
}

function makeDom(router) {
  let nextId = 1

  /** 一条 op 的去处:节点已在某棵树上就直接进那个 surface 的缓冲,否则先挂在节点上。 */
  function emit(n, op) {
    if (n.__lproot) router.push(n.__lproot, op)
    else n.__lppending.push(op)
  }

  /** 把 c 及其整棵子树接到 root 上:先放它自己攒下的 ops,再放 insert,再递归子节点。 */
  function attach(c, root, parentId, beforeId) {
    c.__lproot = root
    const pend = c.__lppending
    c.__lppending = []
    for (const op of pend) router.push(root, op)
    router.push(root, { op: 'insert', parent: parentId, id: c.__lpid, before: beforeId })
    for (const g of c.childNodes) {
      // 子节点是在 c 还没接上根时塞进来的,它们的 insert 这会儿才补发
      attach(g, root, c.__lpid, null)
    }
  }

  function baseNode(nodeType) {
    return {
      nodeType,
      parentNode: null,
      childNodes: [],
      __lproot: null,
      __lppending: [],
      get firstChild() {
        return this.childNodes[0] || null
      },
      get nextSibling() {
        const p = this.parentNode
        if (!p) return null
        const i = p.childNodes.indexOf(this)
        return i < 0 ? null : p.childNodes[i + 1] || null
      },
      appendChild(c) {
        return this.insertBefore(c, null)
      },
      insertBefore(c, ref) {
        const moving = c.__lproot !== null && c.parentNode !== null
        if (c.parentNode) c.parentNode.detach(c)
        const at = ref ? this.childNodes.indexOf(ref) : -1
        if (at < 0) this.childNodes.push(c)
        else this.childNodes.splice(at, 0, c)
        c.parentNode = this
        const beforeId = ref ? ref.__lpid : null
        if (moving && c.__lproot === this.__lproot) {
          // 同一棵树里挪位置:壳收到「已存在的 id 又被 insert 一次」= 移动,不是新建
          router.push(c.__lproot, { op: 'insert', parent: this.__lpid, id: c.__lpid, before: beforeId })
        } else if (this.__lproot) {
          attach(c, this.__lproot, this.__lpid, beforeId)
        }
        return c
      },
      /** 只从树上摘下来,不发 remove —— 紧接着会 insert 到别处。 */
      detach(c) {
        const at = this.childNodes.indexOf(c)
        if (at >= 0) this.childNodes.splice(at, 1)
        c.parentNode = null
      },
      removeChild(c) {
        this.detach(c)
        if (c.__lproot) {
          router.push(c.__lproot, { op: 'remove', id: c.__lpid })
          router.dropSubtree(c.__lproot, c)
        }
        return c
      },
      remove() {
        if (this.parentNode) this.parentNode.removeChild(this)
      },
    }
  }

  function makeStyle(n) {
    const store = {}
    const flush = () => setProp(n, 'style', store)
    const s = {
      setProperty(k, v) {
        store[k] = v
        flush()
      },
      removeProperty(k) {
        delete store[k]
        flush()
      },
      get cssText() {
        return ''
      },
      set cssText(_v) {
        for (const k of Object.keys(store)) delete store[k]
        flush()
      },
    }
    for (const k of STYLE_KEYS) {
      Object.defineProperty(s, k, {
        get: () => store[k],
        set: (v) => {
          if (v === undefined || v === null || v === '') delete store[k]
          else store[k] = unpx(v)
          flush()
        },
      })
    }
    return s
  }

  function setProp(n, name, value) {
    // Canvas 的一帧指令流走**专门的 op**(协议 7.3):它不是属性,
    // 走 props 的话每帧都要和上一帧做一次数组 diff,而它本来就是整帧替换
    if (name === 'cmds' && n.__lptype === 'Canvas') {
      emit(n, { op: 'canvas', id: n.__lpid, cmds: value })
      return
    }
    if (value === undefined || value === null || value === false) {
      if (!(name in n.__lpprops)) return
      delete n.__lpprops[name]
      router.dropFn(n.__lpid, name)
      emit(n, { op: 'props', id: n.__lpid, unset: [name] })
      return
    }
    n.__lpprops[name] = value
    emit(n, { op: 'props', id: n.__lpid, set: { [name]: router.value(n.__lpid, name, value) } })
  }

  function createElement(type) {
    const n = baseNode(1)
    n.__lpid = nextId++
    n.__lptype = type
    n.__lpprops = {}
    n.__lppending.push({ op: 'create', id: n.__lpid, type })
    n.style = makeStyle(n)
    n.setAttribute = (name, value) => setProp(n, name, value)
    n.removeAttribute = (name) => setProp(n, name, undefined)
    /* ☠ Preact 传进来的 **不是**插件写的那个函数,而是它自己的 eventProxy ——
       真正的处理函数藏在节点上一个**被压缩过名字**的内部字段里,拿不到。
       所以这里存一层包装:触发时按原样调那个代理(`this` 必须是节点、`e.type`
       必须是 Preact 当初给的名字),让它自己去查真处理函数。
       插件那边要收到的是原始参数而不是这个假事件,那一步由 renderer.js 的
       `options.event` 还原 —— 那是 Preact 的公开钩子,不是内部字段。
       ★ 附带的好处:处理函数**换实现时 Preact 不会再调一次 addEventListener**,
       所以回调号跨重渲染是稳定的,少一大批 props op。 */
    n.addEventListener = (name, proxy) => {
      setProp(n, 'on' + name, function () {
        const args = Array.prototype.slice.call(arguments)
        return proxy.call(n, { type: name, __lpargs: args })
      })
    }
    n.removeEventListener = (name) => setProp(n, 'on' + name, undefined)
    return n
  }

  function createTextNode(data) {
    const n = baseNode(3)
    n.__lpid = nextId++
    n.__lpprops = {}
    let cur = String(data)
    n.__lppending.push({ op: 'create', id: n.__lpid, type: '#text' })
    n.__lppending.push({ op: 'text', id: n.__lpid, value: cur })
    Object.defineProperty(n, 'data', {
      get: () => cur,
      set: (v) => {
        cur = String(v)
        emit(n, { op: 'text', id: n.__lpid, value: cur })
      },
    })
    n.style = {}
    n.setAttribute = () => {}
    n.removeAttribute = () => {}
    n.addEventListener = () => {}
    n.removeEventListener = () => {}
    return n
  }

  /** surface 的根:id 恒为 0,不发 create —— 壳那边的容器已经在了。 */
  function createRoot(surfaceId) {
    const n = baseNode(1)
    n.__lpid = 0
    n.__lptype = '#root'
    n.__lpprops = {}
    n.__lproot = surfaceId
    n.style = {}
    n.setAttribute = () => {}
    n.removeAttribute = () => {}
    n.addEventListener = () => {}
    n.removeEventListener = () => {}
    return n
  }

  return {
    createElement,
    createElementNS: (_ns, type) => createElement(type),
    createTextNode,
    createRoot,
  }
}
