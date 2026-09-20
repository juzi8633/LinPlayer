/**
 * 直播(SPEC 17.2,D110 D356~D360 D491)。
 *
 * 总原则照搬 TVBox 的操作习惯(D356):上下换台、数字键输频道号、
 * 确认呼出列表、返回两次退出。这几件在 TV 上全靠 `player.onKey`(D563)——
 * 没有它插件一个遥控键都收不到。
 *
 * ☠ 换台不是「换个地址给 mpv」:播不了或卡超 8 秒要自动切下一条(D61),
 *   而且**记住这个频道上次能用的那条**,否则每次进来都从上次坏掉的那条试起。
 */
import {
  definePlugin, h, Fragment, useState, useEffect, useRef,
  player, nav, ui, settings, app,
  View, Column, Row, Text, Button, Chip, ChipGroup, Divider, EmptyState, Spinner,
  Image, Icon, TextInput, VirtualList, Pressable, ProgressBar,
} from '@linplayer/plugin-sdk'

import type { Channel } from './m3u'
import { catchupUrl, catchupWindowSec } from './catchup'
import { nowNext, type Program } from './epg'
import {
  loadAll, loadEpg, programsOf, urlChain, rememberGoodUrl, rememberChannel,
  lastChannel, favorites, toggleFavorite, numbersOf, sources, addSource, removeSource,
} from './store'

/** 卡顿多久算「这条不行」(D61 的 8 秒)。 */
const STALL_MS = 8000

interface Loaded {
  groups: { name: string; channels: Channel[] }[]
  programs: Program[]
  icons: Record<string, string>
  errors: string[]
}

/** 拉数据:频道表 + 节目单。两者分开等 —— 节目单慢不该把频道表一起卡住。 */
function useLive(): { data: Loaded | null; err: string; reload: () => void } {
  const [data, setData] = useState<Loaded | null>(null)
  const [err, setErr] = useState('')
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const merge = settings.get('merge') !== false
        const r = await loadAll(merge)
        if (!alive) return
        setData({ groups: r.groups, programs: [], icons: {}, errors: r.errors })
        const e = await loadEpg(r.epgUrls, false)
        if (!alive) return
        setData((d) => (d ? { ...d, programs: e.programs, icons: e.icons } : d))
      } catch (e: any) {
        if (alive) setErr((e && e.message) || String(e))
      }
    })()
    return () => { alive = false }
  }, [tick])

  return { data, err, reload: () => setTick((t) => t + 1) }
}

/**
 * 换台。按候选链依次试,能播的那条记下来。
 *
 * ☠ 「能播」的判据是**真的开始出画**,不是 playUrl 没抛错:
 *   坏地址常常连得上、也回 200,卡在那里不动。所以起播后盯一段时间的时间戳。
 */
async function tune(c: Channel, onNote: (s: string) => void): Promise<boolean> {
  const chain = urlChain(c)
  if (chain.length === 0) {
    onNote('这个频道没有可用地址')
    return false
  }
  for (let i = 0; i < chain.length; i++) {
    const u = chain[i]
    onNote(chain.length > 1 ? '源 ' + (i + 1) + '/' + chain.length + '…' : '连接中…')
    try {
      await player.playUrl(u.url, u.headers, { title: c.name })
    } catch (e: any) {
      onNote('源 ' + (i + 1) + ' 打不开:' + ((e && e.message) || e))
      continue
    }
    if (await waitPlaying()) {
      rememberGoodUrl(c.name, u.url)
      rememberChannel(c.name)
      onNote('')
      return true
    }
    onNote('源 ' + (i + 1) + ' 卡住了,换下一条')
  }
  onNote('这个频道的地址都打不开')
  return false
}

/** 等到时间戳真的在走。超过 STALL_MS 还没动就算这条不行。 */
async function waitPlaying(): Promise<boolean> {
  const t0 = Date.now()
  let seen = -1
  while (Date.now() - t0 < STALL_MS) {
    const st: any = player.state()
    const pos = (st && st.position) || 0
    if (seen >= 0 && pos > seen) return true
    if (pos > 0) seen = pos
    await sleep(400)
  }
  return false
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

/** 台标:tvg-logo > EPG icon > 文字台标(频道名前两字)。 */
function Logo(p: { c: Channel; icons: Record<string, string> }) {
  const src = p.c.logo || (p.c.tvgId ? p.icons[p.c.tvgId] : '') || ''
  if (src) return <Image src={src} style={{ width: 42, height: 42, radius: 6 }} />
  return (
    <View style={{ width: 42, height: 42, radius: 6, background: 'token:color.surface2', align: 'center', justify: 'center' }}>
      <Text style={{ color: 'token:color.ink2' }}>{p.c.name.slice(0, 2)}</Text>
    </View>
  )
}

/** 一行频道:台标 + 号 + 名 + 当前节目。 */
function ChannelRow(p: { c: Channel; no: number; now?: Program; icons: Record<string, string>; onPress: () => void; selected?: boolean }) {
  return (
    <Pressable onPress={p.onPress} focusable style={{ padding: 6, radius: 10, background: p.selected ? 'token:color.surface2' : undefined }}>
      <Row style={{ gap: 10, align: 'center' }}>
        <Text style={{ color: 'token:color.ink3', width: 34 }}>{String(p.no)}</Text>
        <Logo c={p.c} icons={p.icons} />
        <Column style={{ gap: 2 }}>
          <Text>{p.c.name}</Text>
          <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.small' }}>
            {p.now ? p.now.title : '　'}
          </Text>
        </Column>
      </Row>
    </Pressable>
  )
}

/** 直播页:左分组 / 中频道 / 右节目单。手机上竖着摞。 */
function LivePage() {
  const { data, err, reload } = useLive()
  const [gi, setGi] = useState(0)
  const [note, setNote] = useState('')
  const [picked, setPicked] = useState('')

  if (err) return <EmptyState title="直播源出错了" description={err} />
  if (!data) return <Column style={{ gap: 10, padding: 14 }}><Spinner /><Text>正在拉频道表…</Text></Column>
  if (data.groups.length === 0) {
    return (
      <Column style={{ gap: 14, padding: 14 }}>
        <EmptyState title="还没有直播源" description="贴一条 m3u / txt 地址就能看。也可以装 TVBox 插件,它配置里的直播源会自动出现在这儿。" />
        <SourceEditor onChanged={reload} />
      </Column>
    )
  }

  const group = data.groups[Math.min(gi, data.groups.length - 1)]
  const numbers = numbersOf(data.groups)
  const nowSec = Math.floor(Date.now() / 1000)

  return (
    <Column style={{ gap: 10, padding: 14 }}>
      <ChipGroup>
        {data.groups.map((g, i) => (
          <Chip key={g.name} label={g.name + ' ' + g.channels.length} selected={i === gi} onPress={() => setGi(i)} />
        ))}
      </ChipGroup>
      {note ? <Text style={{ color: 'token:color.ink2' }}>{note}</Text> : null}
      <VirtualList
        itemCount={group.channels.length}
        itemHeight={58}
        renderItem={(i) => {
          const c = group.channels[i]
          const { now } = nowNext(programsOf(data.programs, c), nowSec)
          return (
            <ChannelRow
              c={c} no={numbers.get(c.name) || i + 1} now={now} icons={data.icons}
              selected={c.name === picked}
              onPress={() => { setPicked(c.name); void tune(c, setNote) }}
            />
          )
        }}
      />
      {picked ? (
        <EpgList
          c={group.channels.find((x) => x.name === picked) || group.channels[0]}
          programs={data.programs} nowSec={nowSec} onNote={setNote}
        />
      ) : null}
      {data.errors.length > 0 ? (
        <Text style={{ color: 'token:color.warn', fontSize: 'token:font.size.small' }}>
          {data.errors.length} 个源没拉到:{data.errors.join(';')}
        </Text>
      ) : null}
      <Divider />
      <SourceEditor onChanged={reload} />
    </Column>
  )
}

/**
 * 节目单一栏(SPEC 17.2 的「回看/时移入口」,D111)。
 *
 * 已播的那几档带「回看」标,点了从头播。没有回看模板的频道**不画那个标** ——
 * 画一个点了会跳直播的按钮比没有更糟。
 */
function EpgList(p: { c: Channel; programs: Program[]; nowSec: number; onNote: (s: string) => void }) {
  const list = programsOf(p.programs, p.c)
  if (list.length === 0) {
    return <Text style={{ color: 'token:color.ink3' }}>{p.c.name}:没有节目单</Text>
  }
  const canCatchup = !!p.c.catchup
  const window = catchupWindowSec(p.c.catchup)
  return (
    <Column style={{ gap: 6 }}>
      <Text style={{ fontWeight: 'bold' }}>{p.c.name} 节目单</Text>
      {list.map((g) => {
        const past = g.end <= p.nowSec
        const live = g.start <= p.nowSec && p.nowSec < g.end
        const replayable = canCatchup && past && p.nowSec - g.start <= window
        return (
          <Pressable
            key={String(g.start)}
            focusable={replayable}
            onPress={() => { if (replayable) void playCatchup(p.c, g, p.onNote) }}
            style={{ padding: 6, radius: 6 }}
          >
            <Row style={{ gap: 10, align: 'center' }}>
              <Text style={{ color: 'token:color.ink3', width: 60 }}>{hhmmOf(g.start)}</Text>
              <Text style={{ color: live ? 'token:color.accent' : undefined }}>{g.title}</Text>
              {replayable ? <Chip label="回看" /> : null}
            </Row>
          </Pressable>
        )
      })}
    </Column>
  )
}

function hhmmOf(sec: number): string {
  const d = new Date(sec * 1000)
  const p2 = (n: number) => String(n).padStart(2, '0')
  return p2(d.getHours()) + ':' + p2(d.getMinutes())
}

/** 源管理:贴地址、删源。 */
function SourceEditor(p: { onChanged: () => void }) {
  const [url, setUrl] = useState('')
  const [name, setName] = useState('')
  const [list, setList] = useState(sources())

  return (
    <Column style={{ gap: 10 }}>
      <Text style={{ fontWeight: 'bold' }}>直播源</Text>
      {list.map((s) => (
        <Row key={s.id} style={{ gap: 10, align: 'center' }}>
          <Text>{s.name}</Text>
          <Button title="删除" variant="ghost" onPress={() => { setList(removeSource(s.id)); p.onChanged() }} />
        </Row>
      ))}
      <Row style={{ gap: 10 }}>
        <TextInput placeholder="名字(可不填)" onChangeText={setName} />
        <TextInput placeholder="m3u / txt 地址" onChangeText={setUrl} />
        <Button
          title="添加"
          disabled={!url}
          onPress={() => {
            setList(addSource(name, url))
            setUrl('')
            p.onChanged()
          }}
        />
      </Row>
    </Column>
  )
}

export default definePlugin({
  pages: { live: LivePage },
  blocks: { osd: LiveOsd },
  commands: {
    /** 侧栏 / 命令面板:直接进上次看的频道。 */
    async openLast() {
      nav.push('linplayer/live:live')
    },
  },
})

// ---------------------------------------------------------------- 播放页覆盖层

/**
 * 直播 OSD(D357 D358):换台信息条 + 频道列表 + 数字键输号。
 *
 * 遥控器口径照搬 TVBox(D356):
 *   上/下 = 上一个/下一个频道(可在设置里反转,D359)
 *   确认 = 呼出频道列表,再按确认 = 选中
 *   数字键 = 输频道号(右上角大字,1.5 秒不按就跳)
 *   返回 = 有列表先关列表,再按提示「再按一次退出」
 */
function LiveOsd() {
  const [bar, setBar] = useState<{ c: Channel; until: number } | null>(null)
  const [listOpen, setListOpen] = useState(false)
  const [digits, setDigits] = useState('')
  const [askExit, setAskExit] = useState(false)
  const st = useRef<{ groups: { name: string; channels: Channel[] }[]; programs: Program[]; icons: Record<string, string> }>({ groups: [], programs: [], icons: {} })
  const digitTimer = useRef<any>(null)
  // 按键回调里读的是这几个 ref(见下面 useEffect 的说明)
  const listRef = useRef(false)
  const exitRef = useRef(false)
  const digitsRef = useRef('')

  useEffect(() => {
    let alive = true
    void (async () => {
      const r = await loadAll(settings.get('merge') !== false)
      if (!alive) return
      st.current.groups = r.groups
      const e = await loadEpg(r.epgUrls, false)
      if (!alive) return
      st.current.programs = e.programs
      st.current.icons = e.icons
    })()
    return () => { alive = false }
  }, [])

  const flat = (): Channel[] => st.current.groups.reduce<Channel[]>((a, g) => a.concat(g.channels), [])

  const showBar = (c: Channel) => setBar({ c, until: Date.now() + 3000 })

  const step = (delta: number) => {
    const all = flat()
    if (all.length === 0) {
      // 频道表还没拉回来时**要说话**:静默 return 的表现是「遥控器按了没反应」
      ui.toast('频道表还在拉,稍等一下')
      return
    }
    const cur = lastChannel()
    let i = all.findIndex((c) => c.name === cur)
    if (i < 0) i = 0
    const next = all[(i + delta + all.length) % all.length]
    showBar(next)
    void tune(next, () => {})
  }

  const jumpTo = (no: string) => {
    const all = flat()
    if (all.length === 0) {
      ui.toast('频道表还在拉,稍等一下')
      return
    }
    const numbers = numbersOf(st.current.groups)
    const want = Number(no)
    const hit = all.find((c) => (numbers.get(c.name) || -1) === want)
    if (!hit) {
      ui.toast('没有 ' + no + ' 号频道')
      return
    }
    showBar(hit)
    void tune(hit, () => {})
  }

  /* ☠ 按键回调**只注册一次**,状态从 ref 读。
     照 React 的习惯把 listOpen / digits 写进依赖数组的话,每按一次键都要
     退订再注册一次 —— 两次按键挨得近时,第二次会打到还没换掉的旧回调上,
     表现是「确认键按了两下,列表只开不关」。 */
  useEffect(() => {
    const sub = player.onKey((e) => {
      const k = e.key
      const invert = !!settings.get('invertUpDown')

      if (k >= '0' && k <= '9') {
        const s = (digitsRef.current + k).slice(0, 4)
        digitsRef.current = s
        setDigits(s)
        if (digitTimer.current) clearTimeout(digitTimer.current)
        // 1.5 秒不按就按现在输的号跳 —— TVBox 就是这个手感
        digitTimer.current = setTimeout(() => {
          const want = digitsRef.current
          digitsRef.current = ''
          setDigits('')
          jumpTo(want)
        }, 1500)
        return true
      }
      if (k === 'up' || k === 'channelUp') { step(invert ? 1 : -1); return true }
      if (k === 'down' || k === 'channelDown') { step(invert ? -1 : 1); return true }
      if (k === 'ok') {
        if (!listRef.current) { listRef.current = true; setListOpen(true); return true }
        return false // 列表开着时把确认交给列表里的按钮
      }
      if (k === 'back') {
        if (listRef.current) { listRef.current = false; setListOpen(false); return true }
        if (!exitRef.current) {
          exitRef.current = true
          setAskExit(true)
          setTimeout(() => { exitRef.current = false; setAskExit(false) }, 2000)
          ui.toast('再按一次返回键退出直播')
          return true
        }
        return false // 两秒内第二次:放给宿主,由它退出播放(D459)
      }
      return false
    })
    return () => sub.dispose()
  }, [])

  const visible = bar && Date.now() < bar.until
  if (!visible && !listOpen && !digits) return null

  const nowSec = Math.floor(Date.now() / 1000)
  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}>
      {digits ? (
        <View style={{ position: 'absolute', right: 26, top: 26, paddingX: 18, paddingY: 10, radius: 10, background: '#000000aa' }}>
          <Text style={{ color: '#ffffffff', fontSize: 42 }}>{digits}</Text>
        </View>
      ) : null}

      {visible && bar ? <InfoBar c={bar.c} programs={st.current.programs} icons={st.current.icons} nowSec={nowSec} /> : null}

      {listOpen ? (
        <View style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 420, background: '#000000cc', padding: 14 }}>
          <VirtualList
            itemCount={flat().length}
            itemHeight={58}
            renderItem={(i) => {
              const c = flat()[i]
              const { now } = nowNext(programsOf(st.current.programs, c), nowSec)
              return (
                <ChannelRow
                  c={c} no={numbersOf(st.current.groups).get(c.name) || i + 1} now={now}
                  icons={st.current.icons}
                  onPress={() => { setListOpen(false); showBar(c); void tune(c, () => {}) }}
                />
              )
            }}
          />
        </View>
      ) : null}
    </View>
  )
}

/** 换台信息条:台标 + 号 + 名 + 当前/下一个节目 + 源几。 */
function InfoBar(p: { c: Channel; programs: Program[]; icons: Record<string, string>; nowSec: number }) {
  const list = programsOf(p.programs, p.c)
  const { now, next } = nowNext(list, p.nowSec)
  const pct = now && now.end > now.start ? (p.nowSec - now.start) / (now.end - now.start) : 0
  return (
    <View style={{ position: 'absolute', left: 26, right: 26, bottom: 42, padding: 14, radius: 10, background: '#000000cc' }}>
      <Row style={{ gap: 14, align: 'center' }}>
        <Logo c={p.c} icons={p.icons} />
        <Column style={{ gap: 2 }}>
          <Text style={{ color: '#ffffffff', fontSize: 'token:font.size.title' }}>{p.c.name}</Text>
          <Text style={{ color: '#ffffffcc' }}>{now ? '正在播:' + now.title : '没有节目单'}</Text>
          {next ? <Text style={{ color: '#ffffff99' }}>{'接下来:' + next.title}</Text> : null}
        </Column>
      </Row>
      {now ? <ProgressBar value={pct} style={{ marginY: 6 }} /> : null}
    </View>
  )
}

/** 回看:节目单里点已播节目,从头播(D111)。导出给节目单那一栏用。 */
export async function playCatchup(c: Channel, p: Program, onNote: (s: string) => void) {
  const nowSec = Math.floor(Date.now() / 1000)
  if (!c.catchup) {
    onNote('这个频道没有回看')
    return
  }
  if (nowSec - p.start > catchupWindowSec(c.catchup)) {
    onNote('这一档超出回看时限了')
    return
  }
  const base = urlChain(c)[0]
  const u = catchupUrl(base.url, c.catchup, p.start, p.end, nowSec)
  if (!u) {
    onNote('这个频道的回看模板认不出来')
    return
  }
  await player.playUrl(u, base.headers, { title: c.name + ' · ' + p.title })
}
