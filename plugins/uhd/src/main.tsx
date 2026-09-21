/**
 * UHD 助手:流量 / 求片 / 测速三合一(SPEC 17,旧 `com.linplayer.uhdnow` 的 2.0 版)。
 *
 * 旧版是三个插件,同一套网站账密填三遍、存三份。合成一个之后账号只填一次。
 *
 * ☠ 站点地址、账号、密码全在设置里,**代码里一个都没有**。密码与 token 走
 *   `secrets`(系统密钥库),不进普通存储、不跨设备同步。
 */
import {
  definePlugin, h, useState, useEffect,
  ui, settings,
  Column, Row, Text, Button, Chip, ChipGroup, Divider, EmptyState, Spinner,
  Image, TextInput, ProgressBar, Pressable,
} from '@linplayer/plugin-sdk'

import {
  SIZES, createRequest, lastSpeed, lines, me, myRequests, plaza, rememberSpeed,
  search, speedTest, traffic,
  type Found, type Line, type Topic, type Traffic,
} from './api'

const GiB = 1073741824

function gb(bytes: number): string {
  return (bytes / GiB).toFixed(bytes >= GiB * 10 ? 0 : 1) + ' GB'
}

function errText(e: unknown): string {
  const m = (e as { message?: string })?.message
  return m ? String(m) : String(e)
}

// ---------------------------------------------------------------- 流量

function TrafficCard() {
  const [t, setT] = useState<Traffic | null>(null)
  const [who, setWho] = useState('')
  const [err, setErr] = useState('')
  useEffect(() => {
    void (async () => {
      try {
        setT(await traffic())
      } catch (e) {
        setErr(errText(e))
        return
      }
      // 名字取不到不算错:流量才是这一块的正事
      try {
        setWho((await me()).name)
      } catch {
        /* 忽略 */
      }
    })()
  }, [])

  if (err) return <EmptyState text={"流量看不了:" + err} />
  if (!t) return <Row style={{ gap: 10 }}><Spinner /><Text>正在读流量…</Text></Row>

  const left = Math.max(0, t.limitBytes - t.usedBytes)
  const pct = t.limitBytes > 0 ? Math.min(1, t.usedBytes / t.limitBytes) : 0
  return (
    <Column style={{ gap: 6 }}>
      <Row style={{ gap: 10, alignItems: 'center' }}>
        <Text style={{ fontSize: 'token:font.size.title', fontWeight: 'bold' }}>
          {t.unlimited ? '不限流量' : '剩余 ' + gb(left)}
        </Text>
        {who ? <Text style={{ color: 'token:color.ink3' }}>{who}</Text> : null}
      </Row>
      {t.unlimited ? null : <ProgressBar value={pct} />}
      <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.body' }}>
        已用 {gb(t.usedBytes)}{t.unlimited ? '' : ' / 共 ' + gb(t.limitBytes)}
      </Text>
    </Column>
  )
}

// ---------------------------------------------------------------- 求片

const TYPES: { value: string; label: string; hint: string }[] = [
  { value: 'missing', label: '求片', hint: '库里没有,想让服主加' },
  { value: 'refresh', label: '追新', hint: '库里有但没更新到最新' },
  { value: 'feedback', label: '反馈', hint: '资源有问题(画质/音轨/字幕)' },
]

function FoundRow({ f, onPick }: { f: Found; onPick: () => void }) {
  const bits: string[] = [f.mediaType === 'tv' ? '剧集' : '电影']
  if (f.year) bits.push(String(f.year))
  if (f.inLibrary) bits.push('已在库')
  if (!f.allowed && f.blockedReason) bits.push(f.blockedReason)
  return (
    <Pressable onPress={onPick}>
      <Row style={{ gap: 10, padding: 6, alignItems: 'center' }}>
        {f.poster ? <Image src={f.poster} style={{ width: 46, height: 68, borderRadius: 6 }} /> : null}
        <Column style={{ gap: 2, flex: 1 }}>
          <Text style={{ fontWeight: 'bold' }}>{f.title}</Text>
          <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.body' }}>{bits.join(' · ')}</Text>
        </Column>
      </Row>
    </Pressable>
  )
}

function RequestPanel() {
  const [kw, setKw] = useState('')
  const [type, setType] = useState('missing')
  const [content, setContent] = useState('')
  const [items, setItems] = useState<Found[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')

  async function doSearch() {
    if (!kw.trim()) {
      setNote('先写个片名')
      return
    }
    setBusy(true)
    setNote('')
    try {
      const r = await search(kw.trim(), type)
      setItems(r)
      if (!r.length) setNote(`没搜到「${kw.trim()}」`)
    } catch (e) {
      setNote(errText(e))
    } finally {
      setBusy(false)
    }
  }

  async function submit(f: Found) {
    // ☠ 说明必填 —— 留空的话站点回「参数验证失败」,而用户看不懂那是在说哪一格
    if (!content.trim()) {
      setNote('「说明」是必填的:写一句你想要什么版本、什么问题')
      return
    }
    if (!f.allowed) {
      setNote(f.blockedReason || '这一条站点不让提交')
      return
    }
    const ok = await ui.confirm({
      title: `提交${TYPES.find((t) => t.value === type)?.label}`,
      message: `${f.title}\n\n${content.trim()}\n\n提交后不能修改。`,
      ok: '提交',
    })
    if (!ok) return
    setBusy(true)
    try {
      await createRequest(f, type, content)
      ui.toast('已提交,到「我的」看进度', { tone: 'ok' })
      setItems(null)
      setContent('')
    } catch (e) {
      setNote(errText(e))
    } finally {
      setBusy(false)
    }
  }

  const t = TYPES.find((x) => x.value === type)!
  return (
    <Column style={{ gap: 10 }}>
      <ChipGroup>
        {TYPES.map((x) => (
          <Chip label={x.label} selected={x.value === type} onPress={() => setType(x.value)} />
        ))}
      </ChipGroup>
      <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.body' }}>{t.hint}</Text>
      <TextInput placeholder="片名(中文名或原名都行)" defaultValue={kw} onChangeText={setKw} onSubmit={doSearch} />
      <TextInput placeholder="说明(必填):想要的版本、字幕、分辨率,或遇到的问题" defaultValue={content} onChangeText={setContent} multiline />
      <Row style={{ gap: 10 }}>
        <Button title={busy ? '请稍等…' : '搜索'} onPress={doSearch} disabled={busy} />
      </Row>
      {note ? <Text style={{ color: 'token:color.warn' }}>{note}</Text> : null}
      {items && items.length ? (
        <Column style={{ gap: 2 }}>
          <Divider />
          <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.body' }}>点一条提交</Text>
          {items.map((f) => (
            <FoundRow f={f} onPick={() => void submit(f)} />
          ))}
        </Column>
      ) : null}
    </Column>
  )
}

// ---------------------------------------------------------------- 列表(我的 / 广场)

function TopicRow({ t }: { t: Topic }) {
  const bits: string[] = []
  if (t.year) bits.push(String(t.year))
  if (t.missing) bits.push('求片 ' + t.missing)
  if (t.refresh) bits.push('追新 ' + t.refresh)
  if (t.feedback) bits.push('反馈 ' + t.feedback)
  bits.push(t.open > 0 ? `待处理 ${t.open}/${t.total}` : `${t.total} 条都处理完了`)
  return (
    <Row style={{ gap: 10, padding: 6, alignItems: 'center' }}>
      {t.poster ? <Image src={t.poster} style={{ width: 40, height: 60, borderRadius: 6 }} /> : null}
      <Column style={{ gap: 2, flex: 1 }}>
        <Text style={{ fontWeight: 'bold' }}>{t.title}</Text>
        <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.body' }}>{bits.join(' · ')}</Text>
      </Column>
    </Row>
  )
}

function TopicList({ mine }: { mine: boolean }) {
  const [items, setItems] = useState<Topic[] | null>(null)
  const [total, setTotal] = useState(0)
  const [err, setErr] = useState('')
  useEffect(() => {
    void (async () => {
      try {
        const r = await (mine ? myRequests(1) : plaza(1))
        setItems(r.items)
        setTotal(r.total)
      } catch (e) {
        setErr(errText(e))
      }
    })()
  }, [mine])

  if (err) return <EmptyState text={"列表拉不到:" + err} />
  if (!items) return <Row style={{ gap: 10 }}><Spinner /><Text>正在拉…</Text></Row>
  if (!items.length) {
    return (
      <EmptyState text={mine ? '你还没提过求片 —— 到「求片」那一栏搜片名再提交' : '广场上还没有求片'} />
    )
  }
  return (
    <Column style={{ gap: 2 }}>
      <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.body' }}>共 {total} 条,显示前 {items.length} 条</Text>
      {items.map((t) => (
        <TopicRow t={t} />
      ))}
    </Column>
  )
}

// ---------------------------------------------------------------- 测速

function SpeedPanel() {
  const [ls, setLs] = useState<Line[] | null>(null)
  const [err, setErr] = useState('')
  const [size, setSize] = useState(32)
  const [running, setRunning] = useState('')
  const [prog, setProg] = useState(0)
  const [result, setResult] = useState<Record<string, string>>({})

  useEffect(() => {
    void (async () => {
      try {
        setLs(await lines())
      } catch (e) {
        setErr(errText(e))
      }
    })()
  }, [])

  async function run(l: Line) {
    setRunning(l.id)
    setProg(0)
    try {
      const r = await speedTest(l, size, (p) => setProg(p.totalBytes ? p.bytes / p.totalBytes : 0))
      rememberSpeed(l.id, r.avgMbps)
      setResult({ ...result, [l.id]: `${r.avgMbps.toFixed(1)} Mbps(峰值 ${r.peakMbps.toFixed(0)},${(r.bytes / 1048576).toFixed(0)} MiB / ${(r.ms / 1000).toFixed(1)} 秒)` })
    } catch (e) {
      setResult({ ...result, [l.id]: errText(e) })
    } finally {
      setRunning('')
      setProg(0)
    }
  }

  if (err) return <EmptyState text={"线路表拉不到:" + err} />
  if (!ls) return <Row style={{ gap: 10 }}><Spinner /><Text>正在拉线路…</Text></Row>
  if (!ls.length) return <EmptyState text="这个账号下没有可测的线路" />

  return (
    <Column style={{ gap: 10 }}>
      <Text style={{ color: 'token:color.warn', fontSize: 'token:font.size.body' }}>
        测速会真的下载数据,消耗你账户的流量(每次 {size} MiB)。
      </Text>
      <ChipGroup>
        {SIZES.map((s) => (
          <Chip label={s + ' MiB'} selected={s === size} onPress={() => setSize(s)} />
        ))}
      </ChipGroup>
      <Divider />
      {ls.map((l) => {
        const last = lastSpeed(l.id)
        const line = result[l.id] || (last ? `上次 ${last.mbps.toFixed(1)} Mbps` : l.description || '')
        return (
          <Column style={{ gap: 6 }}>
            <Row style={{ gap: 10, alignItems: 'center' }}>
              <Column style={{ gap: 2, flex: 1 }}>
                <Text style={{ fontWeight: 'bold' }}>{l.name}</Text>
                {line ? <Text style={{ color: 'token:color.ink3', fontSize: 'token:font.size.body' }}>{line}</Text> : null}
              </Column>
              <Button title={running === l.id ? '测速中…' : '测速'} onPress={() => void run(l)} disabled={!!running} />
            </Row>
            {running === l.id ? <ProgressBar value={prog} /> : null}
          </Column>
        )
      })}
    </Column>
  )
}

// ---------------------------------------------------------------- 页面

const TABS = [
  { id: 'req', label: '求片' },
  { id: 'mine', label: '我的' },
  { id: 'plaza', label: '广场' },
  { id: 'speed', label: '测速' },
]

function UhdPage() {
  const [tab, setTab] = useState('req')
  const configured = String(settings.get('site') ?? '').trim() && String(settings.get('username') ?? '').trim()

  if (!configured) {
    return (
      <Column style={{ gap: 14, padding: 14 }}>
        <EmptyState text="先填站点和账号:到 设置 → 插件 → UHD 助手,填站点地址、网站用户名和密码。三块功能(流量 / 求片 / 测速)共用这一份。" />
      </Column>
    )
  }

  return (
    <Column style={{ gap: 14, padding: 14 }}>
      <TrafficCard />
      <Divider />
      <ChipGroup>
        {TABS.map((t) => (
          <Chip label={t.label} selected={t.id === tab} onPress={() => setTab(t.id)} />
        ))}
      </ChipGroup>
      {tab === 'req' ? <RequestPanel /> : null}
      {tab === 'mine' ? <TopicList mine /> : null}
      {tab === 'plaza' ? <TopicList mine={false} /> : null}
      {tab === 'speed' ? <SpeedPanel /> : null}
    </Column>
  )
}

export default definePlugin({
  pages: { uhd: UhdPage },
})
