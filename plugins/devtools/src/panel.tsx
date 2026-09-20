/**
 * 调试面板的四块(SPEC 16.5,D80 D81):日志 / 网络 / UI 树 / 性能与存储。
 *
 * ☠ 开发者模式关着时 `debug` 整个命名空间不存在(D555 的口径)。这里先判再用 ——
 *   直接调会抛在渲染期,整块进错误边界,而真正该说的是「去设置里打开开发者模式」。
 */
import {
  h, Fragment, useState, useEffect, debug,
  View, Column, Text, Button, TextInput, Switch, Divider, Chip, ChipGroup, Pressable,
  EmptyState, VirtualList,
} from '@linplayer/plugin-sdk'

/** 一行「标签 : 值」。 */
export function KV(props: { label: string; value: string }) {
  return (
    <View style={{ direction: 'row', justify: 'between', align: 'center', paddingTop: 6, paddingBottom: 6 }}>
      <Text style={{ color: 'token:color.ink2' }}>{props.label}</Text>
      <Text style={{ fontWeight: 'bold' }}>{props.value}</Text>
    </View>
  )
}

/** 一条记录摊成两列。左边可以很长,右边是状态。 */
function Line(props: { left: string; right?: string; tone?: string }) {
  return (
    <View style={{ direction: 'row', justify: 'between', align: 'start', gap: 10, paddingTop: 2, paddingBottom: 2 }}>
      <Text style={{ color: props.tone || 'token:color.ink', grow: 1, maxLines: 2 }}>{props.left}</Text>
      {props.right ? <Text style={{ color: 'token:color.ink3' }}>{props.right}</Text> : null}
    </View>
  )
}

function hhmmss(ts: number) {
  const d = new Date(ts)
  const p = (n: number) => (n < 10 ? '0' + n : String(n))
  return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

/** 四块都按插件分帐,所以选择器提到最上面一层。 */
export function PluginPicker(props: { list: any[]; current: string; onPick: (id: string) => void }) {
  return (
    <ChipGroup>
      {props.list.map((p: any) => (
        <Chip key={p.id} label={p.name || p.id} selected={p.id === props.current} onPress={() => props.onPick(p.id)} />
      ))}
    </ChipGroup>
  )
}

const LEVELS = ['全部', 'debug', 'info', 'warn', 'error']
const LEVEL_TONE: Record<string, string> = {
  error: 'token:color.danger', warn: 'token:color.warn', debug: 'token:color.ink3',
}

/** 1/4 日志:按 debug / info / warn / error 筛(D81)。 */
export function LogsBlock(props: { plugin: string; tick: number }) {
  const [rows, setRows] = useState<any[]>([])
  const [level, setLevel] = useState('全部')
  useEffect(() => {
    debug.logs(props.plugin).then(setRows, () => setRows([]))
  }, [props.plugin, props.tick])
  const shown = rows.filter((r: any) => level === '全部' || r.level === level)
  return (
    <Column style={{ gap: 10 }}>
      <ChipGroup>
        {LEVELS.map((l) => <Chip key={l} label={l} selected={l === level} onPress={() => setLevel(l)} />)}
      </ChipGroup>
      {shown.length === 0 ? (
        <EmptyState text="这一档没有日志" />
      ) : (
        <VirtualList
          style={{ height: 320 }}
          itemCount={shown.length}
          itemHeight={44}
          renderItem={(i: number) => (
            <Line left={hhmmss(shown[i].ts) + '  ' + shown[i].msg} right={shown[i].level} tone={LEVEL_TONE[shown[i].level]} />
          )}
        />
      )}
    </Column>
  )
}

/** 2/4 网络:URL / 状态码 / 耗时(D81)。 */
export function NetworkBlock(props: { plugin: string; tick: number }) {
  const [rows, setRows] = useState<any[]>([])
  const [failedOnly, setFailedOnly] = useState(false)
  useEffect(() => {
    debug.requests(props.plugin).then(setRows, () => setRows([]))
  }, [props.plugin, props.tick])
  const bad = (r: any) => !!r.err || r.status >= 400
  const shown = rows.filter((r: any) => !failedOnly || bad(r))
  return (
    <Column style={{ gap: 10 }}>
      <View style={{ direction: 'row', align: 'center', gap: 10 }}>
        <Switch value={failedOnly} onChange={setFailedOnly} a11yLabel="只看失败的请求" />
        <Text>只看失败的</Text>
      </View>
      {shown.length === 0 ? (
        <EmptyState text="还没有网络请求" />
      ) : (
        <VirtualList
          style={{ height: 320 }}
          itemCount={shown.length}
          itemHeight={44}
          renderItem={(i: number) => (
            <Line
              left={shown[i].method + ' ' + shown[i].url}
              right={shown[i].err ? shown[i].err : shown[i].status + ' · ' + shown[i].ms + 'ms'}
              tone={bad(shown[i]) ? 'token:color.danger' : undefined}
            />
          )}
        />
      )}
    </Column>
  )
}

/** 一棵树摊成一串行:壳侧没有树控件,缩进由前缀全角空格表达。 */
function flatten(node: any, depth: number, out: any[]) {
  if (!node) return out
  out.push({ depth, node })
  for (const c of node.children || []) flatten(c, depth + 1, out)
  return out
}

/** 3/4 UI 树:任一 surface 的组件树与属性,选中看详情(D81)。 */
export function UiTreeBlock(props: { tick: number }) {
  const [list, setList] = useState<any[]>([])
  const [sid, setSid] = useState('')
  const [tree, setTree] = useState<any>(null)
  const [picked, setPicked] = useState(-1)

  useEffect(() => {
    debug.surfaces().then((s: any[]) => {
      setList(s)
      // 选过的那块还在就不动 —— 刷新时把选择重置回第一块,等于每 2 秒把用户的选择抢走
      if (!s.some((x: any) => x.surface === sid)) setSid(s.length ? s[0].surface : '')
    }, () => setList([]))
  }, [props.tick])

  useEffect(() => {
    if (!sid) { setTree(null); return }
    debug.uiTree(sid).then(setTree, () => setTree(null))
  }, [sid, props.tick])

  const rows = flatten(tree, 0, [])
  const sel = picked >= 0 && picked < rows.length ? rows[picked].node : null
  const keys = sel ? Object.keys(sel.props || {}) : []
  return (
    <Column style={{ gap: 10 }}>
      <ChipGroup>
        {list.map((s: any) => (
          <Chip
            key={s.surface}
            label={s.plugin + ':' + s.target}
            selected={s.surface === sid}
            onPress={() => { setSid(s.surface); setPicked(-1) }}
          />
        ))}
      </ChipGroup>
      {rows.length === 0 ? (
        <EmptyState text="这一块没有挂着的 UI" />
      ) : (
        <VirtualList
          style={{ height: 260 }}
          itemCount={rows.length}
          itemHeight={32}
          renderItem={(i: number) => (
            <Pressable onPress={() => setPicked(i)} a11yLabel={rows[i].node.type}>
              <Line
                left={'　'.repeat(rows[i].depth) + rows[i].node.type + (rows[i].node.text ? ' 「' + rows[i].node.text + '」' : '')}
                right={'#' + rows[i].node.id}
                tone={i === picked ? 'token:color.accent' : undefined}
              />
            </Pressable>
          )}
        />
      )}
      {sel ? (
        <Column style={{ gap: 2, padding: 10, radius: 'token:radius.card', background: 'token:color.surfaceAlt' }}>
          <Text style={{ fontWeight: 'bold' }}>{sel.type} 的属性</Text>
          {keys.length === 0
            ? <Text style={{ color: 'token:color.ink3' }}>(没有属性)</Text>
            : keys.map((k) => <Line key={k} left={k} right={JSON.stringify(sel.props[k])} />)}
        </Column>
      ) : null}
    </Column>
  )
}

/** 4/4 性能与存储:调用耗时、内存增量、KV 查看与编辑(D81)。 */
export function PerfBlock(props: { plugin: string; tick: number }) {
  const [stats, setStats] = useState<any>(null)
  const [kv, setKv] = useState<Record<string, any>>({})
  const [key, setKey] = useState('')
  const [val, setVal] = useState('')
  const [hint, setHint] = useState('')

  const reload = () => {
    debug.stats(props.plugin).then(setStats, () => setStats(null))
    debug.storage(props.plugin).then(setKv, () => setKv({}))
  }
  useEffect(() => { reload() }, [props.plugin, props.tick])

  const keys = Object.keys(kv)
  const save = () => {
    if (!key) { setHint('先填键名'); return }
    // 值按 JSON 解,解不动就当字符串存 —— 面板里最常改的是一个开关或一段文本,
    // 强制要求带引号会让人以为「改不了」
    let parsed: any
    try { parsed = JSON.parse(val) } catch (e) { parsed = val }
    debug.setStorage(props.plugin, key, parsed).then(
      () => { setHint('已写入 ' + key); reload() },
      (e: any) => setHint('写不进去:' + ((e && e.message) || String(e))),
    )
  }

  return (
    <Column style={{ gap: 10 }}>
      <Column style={{ gap: 2 }}>
        <KV label="调用次数" value={stats ? String(stats.calls) : '—'} />
        <KV label="累计耗时" value={stats ? stats.total_ms + ' ms' : '—'} />
        <KV label="最慢一次" value={stats ? stats.max_ms + ' ms' : '—'} />
        <KV label="超预算次数" value={stats ? String(stats.timeouts) : '—'} />
        {/* 进程级采样的近似,不是这个插件真占了多少 —— 写明,否则会被当成精确值 */}
        <KV label="堆增量(近似)" value={stats ? Math.round(stats.heap_delta / 1024) + ' KB' : '—'} />
      </Column>

      <Divider />
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>存储({keys.length} 个键)</Text>
      {keys.length === 0 ? (
        <EmptyState text="这个插件还没存过东西" />
      ) : (
        <Column>
          {keys.map((k) => (
            <Pressable key={k} onPress={() => { setKey(k); setVal(JSON.stringify(kv[k])) }} a11yLabel={'编辑 ' + k}>
              <Line left={k} right={JSON.stringify(kv[k])} />
            </Pressable>
          ))}
        </Column>
      )}
      <TextInput placeholder="键名" defaultValue={key} onChangeText={setKey} />
      <TextInput placeholder="值(JSON;解不动就当字符串)" defaultValue={val} onChangeText={setVal} />
      <View style={{ direction: 'row', gap: 10 }}>
        <Button title="写入" onPress={save} />
        <Button title="删除" onPress={() => debug.setStorage(props.plugin, key, null).then(() => { setHint('已删 ' + key); reload() })} />
      </View>
      {hint ? <Text style={{ color: 'token:color.ink2' }}>{hint}</Text> : null}
    </Column>
  )
}
