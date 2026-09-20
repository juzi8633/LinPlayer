/**
 * 直播的数据层(SPEC 17.2,D43 D61 D108 D109 D110 D112 D495)。
 *
 * ☠ 三件事必须落盘,丢了用户会当成「这插件记不住东西」:
 *   · 每个频道**上次能用的那条地址**(D61)—— 不记的话每次进来都从源 1 试起,
 *     而源 1 正是上次不能用的那条;
 *   · 收藏与自定义频道号;
 *   · 节目单缓存(D108:缓存 3 天,每 6 小时刷新,只在打开过直播时拉)。
 */
import { storage, registry, settings } from '@linplayer/plugin-sdk'

import { parsePlaylist, type Channel, type Playlist } from './m3u'
import { mergeChannels, groupOf } from './merge'
import { scanXmltv, normalizePrograms, type Program } from './epg'

export interface Source {
  id: string
  name: string
  url: string
  /** 播放列表自带的 EPG 地址会并进这里。 */
  epg?: string
  enabled?: boolean
}

const K_SOURCES = 'sources'
const K_FAV = 'favorites'
const K_LASTURL = 'lastUrl'
const K_LASTCH = 'lastChannel'
const K_NUMBERS = 'numbers'
const K_HIDDEN = 'hiddenGroups'
const K_EPG = 'epgCache'

/** 节目单缓存 3 天;刷新间隔默认 6 小时,用户可在设置里改(D108)。 */
const EPG_TTL_MS = 3 * 24 * 3600 * 1000

function epgRefreshMs(): number {
  // 摆着不生效的设置项比没有更糟:manifest 里声明了 epgRefreshHours 就得真读它
  const h = Number(settings.get('epgRefreshHours'))
  return (h > 0 ? h : 6) * 3600 * 1000
}

export function sources(): Source[] {
  return storage.get<Source[]>(K_SOURCES) || []
}

export function saveSources(list: Source[]) {
  storage.set(K_SOURCES, list)
}

export function addSource(name: string, url: string): Source[] {
  const list = sources()
  const id = 's' + Date.now().toString(36)
  list.push({ id, name: name || url, url, enabled: true })
  saveSources(list)
  return list
}

export function removeSource(id: string): Source[] {
  const list = sources().filter((s) => s.id !== id)
  saveSources(list)
  return list
}

/**
 * 注册表里别的插件写来的频道源(`live.channels`,D43 D112)。
 * TVBox 插件把配置里的 `lives` 写在这儿,直播插件不认识 TVBox 也能用上。
 */
function registrySources(): { name: string; text?: string; url?: string; list?: Playlist }[] {
  const out: { name: string; text?: string; url?: string; list?: Playlist }[] = []
  for (const e of registry.list('live.channels')) {
    const v: any = e.value
    if (!v) continue
    if (v.kind === 'url' && v.url) out.push({ name: v.name || e.key, url: v.url })
    else if (v.kind === 'list' && Array.isArray(v.groups)) {
      // 已经是结构化的:直接摊平成频道表
      const channels: Channel[] = []
      for (const g of v.groups) {
        for (const c of g.channels || []) {
          channels.push({
            name: c.name, tvgId: c.tvgId, logo: c.logo, group: g.name || '未分组',
            urls: c.urls || [], catchup: c.catchup,
          })
        }
      }
      out.push({ name: v.name || e.key, list: { channels, epg: v.epg ? [v.epg] : [] } })
    }
    // kind === 'proxy' 是 TVBox 代理直播(D534):要 jar 环境,这一版不接,
    // 在界面上灰显并说清原因,不在这里悄悄丢掉
  }
  return out
}

export interface LoadResult {
  groups: { name: string; channels: Channel[] }[]
  epgUrls: string[]
  errors: string[]
}

/** 拉齐所有源,合并同名频道,回分组视图。 */
export async function loadAll(merge: boolean): Promise<LoadResult> {
  const all: Channel[] = []
  const epgUrls: string[] = []
  const errors: string[] = []

  const jobs: { name: string; url?: string; list?: Playlist }[] = [
    ...sources().filter((s) => s.enabled !== false).map((s) => ({ name: s.name, url: s.url })),
    ...registrySources(),
  ]

  for (const j of jobs) {
    try {
      let pl: Playlist
      if (j.list) {
        pl = j.list
      } else {
        const r = await fetch(j.url!)
        if (!r.ok) throw new Error('HTTP ' + r.status)
        pl = parsePlaylist(await r.text())
      }
      if (pl.channels.length === 0) throw new Error('这个源里一个频道都没解出来')
      all.push(...pl.channels)
      for (const u of pl.epg) if (epgUrls.indexOf(u) < 0) epgUrls.push(u)
    } catch (e: any) {
      // 一个源坏了不能让整页空着:记下来显示在页脚,其余源照常用
      errors.push(j.name + ':' + ((e && e.message) || e))
    }
  }
  const channels = merge ? mergeChannels(all) : all
  return { groups: groupOf(applyOrder(channels)), epgUrls, errors }
}

/*
收藏置顶(D109):收藏的频道**复制**一份排在最前的「收藏」组里。

☠ 复制不是移动:用户在原来的分组里还要找得到它。
  但复制之后同一个台在**摊平的频道表**里会出现两次 —— 换台时会「换了个寂寞」
  (下一个还是它自己)。所以 `flatChannels()` 摊平时要按名字去重,
  而不是在这里少复制一份。
*/
function applyOrder(list: Channel[]): Channel[] {
  const fav = favorites()
  if (fav.length === 0) return list
  const isFav = (c: Channel) => fav.indexOf(c.name) >= 0
  const top = list.filter(isFav).map((c) => ({ ...c, group: '收藏' }))
  return top.concat(list)
}

/** 摊平成一条频道链(换台用)。**按名字去重** —— 收藏组里那一份是同一个台。 */
export function flatChannels(groups: { name: string; channels: Channel[] }[]): Channel[] {
  const seen = new Set<string>()
  const out: Channel[] = []
  for (const g of groups) {
    for (const c of g.channels) {
      if (seen.has(c.name)) continue
      seen.add(c.name)
      out.push(c)
    }
  }
  return out
}

export function favorites(): string[] {
  return storage.get<string[]>(K_FAV) || []
}

export function toggleFavorite(name: string): string[] {
  const list = favorites()
  const i = list.indexOf(name)
  if (i >= 0) list.splice(i, 1)
  else list.push(name)
  storage.set(K_FAV, list)
  return list
}

export function hiddenGroups(): string[] {
  return storage.get<string[]>(K_HIDDEN) || []
}

export function toggleHiddenGroup(name: string): string[] {
  const list = hiddenGroups()
  const i = list.indexOf(name)
  if (i >= 0) list.splice(i, 1)
  else list.push(name)
  storage.set(K_HIDDEN, list)
  return list
}

/** 频道号:自定义的优先,没有就按顺序编(从 1 起)。 */
export function numbersOf(groups: { name: string; channels: Channel[] }[]): Map<string, number> {
  const custom = storage.get<Record<string, number>>(K_NUMBERS) || {}
  const out = new Map<string, number>()
  let n = 1
  for (const g of groups) {
    for (const c of g.channels) {
      if (out.has(c.name)) continue
      out.set(c.name, custom[c.name] || c.number || n)
      n++
    }
  }
  return out
}

export function setNumber(name: string, no: number) {
  const custom = storage.get<Record<string, number>>(K_NUMBERS) || {}
  custom[name] = no
  storage.set(K_NUMBERS, custom)
}

/** 这个频道上次能用的那条地址(D61)。 */
export function lastGoodUrl(name: string): string {
  const m = storage.get<Record<string, string>>(K_LASTURL) || {}
  return m[name] || ''
}

export function rememberGoodUrl(name: string, url: string) {
  const m = storage.get<Record<string, string>>(K_LASTURL) || {}
  m[name] = url
  storage.set(K_LASTURL, m)
}

export function lastChannel(): string {
  return storage.get<string>(K_LASTCH) || ''
}

export function rememberChannel(name: string) {
  storage.set(K_LASTCH, name)
}

/**
 * 挑这个频道该用哪条地址:上次能用的排第一,其余按原顺序。
 * 返回的是**整条候选链**,播放失败时按顺序往下试(D61)。
 */
export function urlChain(c: Channel): { url: string; headers?: Record<string, string> }[] {
  const last = lastGoodUrl(c.name)
  if (!last) return c.urls.slice()
  const hit = c.urls.filter((u) => u.url === last)
  const rest = c.urls.filter((u) => u.url !== last)
  return hit.concat(rest)
}

// ---------------------------------------------------------------- 节目单

interface EpgCache {
  at: number
  programs: Program[]
  icons: Record<string, string>
}

/**
 * 取节目单。缓存没过期就直接用 —— 一份全国台的 xmltv 是几 MB,
 * 每次进直播页都拉一遍既慢又费流量(D108:每 6 小时刷新,缓存 3 天)。
 */
export async function loadEpg(urls: string[], force: boolean): Promise<{ programs: Program[]; icons: Record<string, string>; error?: string }> {
  const cached = storage.get<EpgCache>(K_EPG)
  const age = cached ? Date.now() - cached.at : Infinity
  if (cached && !force && age < epgRefreshMs()) {
    return { programs: cached.programs, icons: cached.icons }
  }
  const programs: Program[] = []
  const icons: Record<string, string> = {}
  let error = ''
  for (const u of urls) {
    try {
      const r = await fetch(u)
      if (!r.ok) throw new Error('HTTP ' + r.status)
      scanXmltv(await r.text(), (p) => programs.push(p), (id, _name, icon) => {
        if (icon) icons[id] = icon
      })
      // 多源按优先级:第一个有数据的就够了(D108)
      if (programs.length > 0) break
    } catch (e: any) {
      error = (e && e.message) || String(e)
    }
  }
  if (programs.length === 0) {
    // 拉不到就用旧的(哪怕过期):有昨天的节目单也比一片空白强
    if (cached && Date.now() - cached.at < EPG_TTL_MS) {
      return { programs: cached.programs, icons: cached.icons, error: error || undefined }
    }
    return { programs: [], icons: {}, error: error || '没有可用的节目单源' }
  }
  const norm = normalizePrograms(programs)
  storage.set(K_EPG, { at: Date.now(), programs: norm, icons })
  return { programs: norm, icons }
}

/** 这个频道的节目:tvg-id 对不上时退回按名字对。 */
export function programsOf(all: Program[], c: Channel): Program[] {
  const id = (c.tvgId || '').toLowerCase()
  const name = c.name.toLowerCase()
  const out = all.filter((p) => {
    const ch = p.channel.toLowerCase()
    return (id && ch === id) || ch === name
  })
  return out
}
