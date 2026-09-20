/**
 * Trakt / Bangumi 同步(SPEC 17.3,D125 D363 D365)。
 *
 * 账号连接与 token 刷新**在宿主**(用户只登一次),这里经 `trakt.request` /
 * `bangumi.request` 借用 —— 插件拿不到 token。
 *
 * ☠ 同步是记账,播放是主线:**任何一条失败都不许打断看片**。
 *   上游挂了、网断了、没登录,统统只记一条日志,不弹窗、不抛到宿主。
 */
import {
  definePlugin, h, Fragment, useState, useEffect, useSetting,
  trakt, bangumi, events, player, storage, settings, ui, nav,
  View, Column, Row, Text, Button, Chip, ChipGroup, Divider, EmptyState, Switch,
} from '@linplayer/plugin-sdk'

import { scrobbleStart, scrobblePause, scrobbleStop, markBangumiEpisode, statusOf } from './sync'

/** 详情页锚点块:显示这一部在两家的状态(SPEC 17.3 的第 2 层)。 */
function DetailStatus(props: { item?: any }) {
  const item = props.item || {}
  const [state, setState] = useState<any>({ loading: true })

  useEffect(() => {
    let live = true
    statusOf(item).then(
      (s) => { if (live) setState({ ...s, loading: false }) },
      (e) => { if (live) setState({ loading: false, error: String((e && e.message) || e) }) },
    )
    return () => { live = false }
  }, [item.id, item.source])

  if (state.loading) return <Text style={{ color: 'token:color.ink3' }}>同步状态查询中…</Text>
  // 没连账号不是错误:大多数人只连一家,另一家一直是「没连」
  if (state.error) return <Text style={{ color: 'token:color.ink3' }}>同步状态取不到:{state.error}</Text>
  if (!state.trakt && !state.bangumi) return null

  return (
    <Row style={{ gap: 10, align: 'center', paddingTop: 6, paddingBottom: 6 }}>
      {state.trakt ? <Chip label={'Trakt · ' + state.trakt} /> : null}
      {state.bangumi ? <Chip label={'Bangumi · ' + state.bangumi} /> : null}
      <Button title="管理" variant="ghost" onPress={() => nav.push('linplayer/sync:manage', { item })} />
    </Row>
  )
}

/** 设置分节:开关与阈值。声明式那几项由宿主画,这里画「连了哪几家」。 */
function SettingsBlock() {
  const [on, setOn] = useSetting<boolean>('scrobble')
  const [who, setWho] = useState<any>({ loading: true })

  useEffect(() => {
    Promise.all([
      trakt.request('GET', '/users/me').then(() => true, () => false),
      bangumi.request('GET', '/v0/me').then(() => true, () => false),
    ]).then(([t, b]) => setWho({ trakt: t, bangumi: b, loading: false }))
  }, [])

  return (
    <Column style={{ gap: 6 }}>
      <Row style={{ align: 'center', gap: 10 }}>
        <Switch value={!!on} onChange={setOn} a11yLabel="播放时同步进度" />
        <Text>播放时同步进度</Text>
      </Row>
      {who.loading ? (
        <Text style={{ color: 'token:color.ink3' }}>查询已连接的账号…</Text>
      ) : (
        <Text style={{ color: 'token:color.ink3' }}>
          Trakt {who.trakt ? '已连接' : '未连接'} · Bangumi {who.bangumi ? '已连接' : '未连接'}
          {who.trakt || who.bangumi ? '' : ' —— 到设置里的账号页连一次'}
        </Text>
      )}
    </Column>
  )
}

/** 收藏管理页(SPEC 17.3 的第 3 层):想看单、集数进度、标记看过。 */
function ManagePage(props: { params?: any }) {
  const item = (props.params && props.params.item) || {}
  const [rows, setRows] = useState<any[]>([])
  const [tab, setTab] = useState('bangumi')
  const [hint, setHint] = useState('')

  useEffect(() => {
    const path = tab === 'bangumi'
      ? '/v0/users/-/collections?subject_type=2&type=3&limit=50'
      : '/sync/watchlist/shows'
    const req = tab === 'bangumi' ? bangumi.request('GET', path) : trakt.request('GET', path)
    req.then(
      (r: any) => setRows(tab === 'bangumi' ? (r && r.data) || [] : r || []),
      (e: any) => { setRows([]); setHint(String((e && e.message) || e)) },
    )
  }, [tab])

  return (
    <Column style={{ gap: 14 }}>
      <Text style={{ fontSize: 22, fontWeight: 'bold' }}>同步与收藏</Text>
      <ChipGroup>
        {['bangumi', 'trakt'].map((t) => (
          <Chip key={t} label={t === 'bangumi' ? 'Bangumi 在看' : 'Trakt 想看'} selected={t === tab} onPress={() => setTab(t)} />
        ))}
      </ChipGroup>
      {item && item.title ? (
        <Row style={{ gap: 10, align: 'center' }}>
          <Text style={{ grow: 1 }}>当前:{item.title}</Text>
          <Button
            title="标记看过"
            onPress={() => markWatched(item).then(
              () => setHint('已标记'),
              (e: any) => setHint('标不上:' + ((e && e.message) || e)),
            )}
          />
        </Row>
      ) : null}
      {hint ? <Text style={{ color: 'token:color.ink2' }}>{hint}</Text> : null}
      <Divider />
      {rows.length === 0 ? (
        <EmptyState text="这一栏是空的(没连账号时也是空的)" />
      ) : (
        <Column>
          {rows.slice(0, 50).map((r: any, i: number) => (
            <View key={i} style={{ direction: 'row', justify: 'between', paddingTop: 6, paddingBottom: 6 }}>
              <Text style={{ grow: 1, maxLines: 1 }}>{titleOf(r)}</Text>
              <Text style={{ color: 'token:color.ink3' }}>{progressOf(r)}</Text>
            </View>
          ))}
        </Column>
      )}
    </Column>
  )
}

function titleOf(r: any): string {
  if (r.subject) return r.subject.name_cn || r.subject.name || String(r.subject.id)
  if (r.show) return r.show.title || ''
  return r.name || r.title || ''
}

function progressOf(r: any): string {
  if (typeof r.ep_status === 'number') return r.ep_status + ' 话'
  if (r.listed_at) return String(r.listed_at).slice(0, 10)
  return ''
}

/** 标「看过」:两家各走各的,哪家没连就跳过哪家。 */
async function markWatched(item: any) {
  const ids = item.externalIds || {}
  const jobs: Promise<any>[] = []
  if (ids.bangumi) jobs.push(bangumi.request('POST', '/v0/users/-/collections/' + ids.bangumi, { type: 2 }))
  if (ids.imdb || ids.tmdb) {
    jobs.push(trakt.request('POST', '/sync/history', {
      movies: item.kind === 'movie' ? [{ ids: pickIds(ids) }] : undefined,
      shows: item.kind === 'movie' ? undefined : [{ ids: pickIds(ids) }],
    }))
  }
  if (jobs.length === 0) throw new Error('这一条没有 TMDB / IMDb / Bangumi id,对不上上游的条目')
  // 一家失败不影响另一家:allSettled 之后只在**全挂**时才抛
  const rs = await Promise.allSettled(jobs)
  if (rs.every((r) => r.status === 'rejected')) {
    throw new Error((rs[0] as any).reason?.message || '两家都没标上')
  }
}

function pickIds(ids: any) {
  const out: any = {}
  if (ids.imdb) out.imdb = ids.imdb
  if (ids.tmdb) out.tmdb = Number(ids.tmdb) || ids.tmdb
  return out
}

export default definePlugin({
  async activate(ctx) {
    /* 进度同步挂在播放事件上(SPEC 17.3 的第 1 层)。
       ☠ 不订阅 time-pos 逐帧算:那是每秒四次把插件叫醒,而 scrobble 只要
         「开始 / 暂停 / 停止」三个时刻。停止时的百分比从 player.state() 读。 */
    ctx.subscriptions.push(events.on('player.start', (np) => void scrobbleStart(np)))
    ctx.subscriptions.push(events.on('player.pause', (np) => void scrobblePause(np)))
    ctx.subscriptions.push(events.on('player.resume', (np) => void scrobbleStart(np)))
    ctx.subscriptions.push(events.on('player.end', (np) => void onEnd(np)))
    ctx.subscriptions.push(events.on('player.episodeChange', (np) => void scrobbleStart(np)))
  },

  pages: { manage: ManagePage },
  blocks: { detailStatus: DetailStatus, settingsBlock: SettingsBlock },
})

async function onEnd(np: any) {
  await scrobbleStop(np)
  const pct = percentOf(np)
  const threshold = Number(settings.get('threshold')) || 80
  if (pct >= threshold) await markBangumiEpisode(np)
}

function percentOf(np: any): number {
  const st = player.state() as any
  const d = (st && st.duration) || np.duration || 0
  const p = (st && st.position) || np.position || 0
  return d > 0 ? Math.round((p / d) * 100) : 0
}
