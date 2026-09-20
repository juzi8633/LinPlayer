/**
 * 直播源解析:M3U / TXT(SPEC 17.2 的「数据」,D43 D112 D113 D468)。
 *
 * ☠ 三件事错了都不报错,只是「某些台打不开」:
 *   · 频道级请求头丢了(`#EXTVLCOPT` 与 URL 后面的 `|User-Agent=`)→ 403,看起来像源挂了;
 *   · 分组丢了 → 所有台挤进「未分组」,TV 上三栏里的第一栏是空的;
 *   · 回看模板只认 `catchup` 不认 `catchup-type` → 节目单里点回看跳去直播流。
 */

export interface Catchup {
  type: 'append' | 'shift' | 'default' | 'flussonic'
  source?: string
  days?: number
}

export interface Channel {
  name: string
  tvgId?: string
  logo?: string
  group: string
  /** 同一个频道的多个地址(「源 1/2/3」)。 */
  urls: { url: string; headers?: Record<string, string> }[]
  catchup?: Catchup
  /** 自定义频道号(`tvg-chno`),没有时由调用方按顺序编。 */
  number?: number
}

export interface Playlist {
  channels: Channel[]
  /** 播放列表自带的 EPG 地址(`x-tvg-url` / `url-tvg`),可能是逗号分隔的多条。 */
  epg: string[]
}

/** 认得的回看类型。不认得的一律丢掉 —— 留着会让「有回看标但点了跳直播」。 */
const CATCHUP_TYPES = ['append', 'shift', 'default', 'flussonic']

function catchupOf(attrs: Record<string, string>, fallback?: Catchup): Catchup | undefined {
  const raw = (attrs['catchup'] || attrs['catchup-type'] || '').trim().toLowerCase()
  const src = attrs['catchup-source'] || attrs['timeshift-source'] || ''
  const days = Number(attrs['catchup-days'] || attrs['timeshift-days'] || 0)
  let type = raw
  // 只给了 catchup-source 没给 type 时:带模板占位就是 default,否则是 append
  if (!type && src) type = src.includes('${') || src.includes('{utc') ? 'default' : 'append'
  if (!CATCHUP_TYPES.includes(type)) return fallback
  const out: Catchup = { type: type as Catchup['type'] }
  if (src) out.source = src
  if (days > 0) out.days = days
  return out
}

/** `a="b" c="d"` → 对象。值里可能有空格,所以不能按空格切。 */
function parseAttrs(s: string): Record<string, string> {
  const out: Record<string, string> = {}
  const re = /([\w-]+)\s*=\s*"([^"]*)"/g
  let m: RegExpExecArray | null
  while ((m = re.exec(s)) !== null) out[m[1].toLowerCase()] = m[2]
  return out
}

/**
 * 拆 URL 后面挂的请求头:`http://…|User-Agent=x&Referer=y`(TVBox / ffmpeg 的写法)。
 *
 * ☠ 只认**最后一个** `|`:地址本身可以带 `|`(某些代理把参数编在路径里),
 *   从第一个切的话会把地址切断,表现是这一台永远 404。
 */
export function splitUrlHeaders(raw: string): { url: string; headers?: Record<string, string> } {
  const s = raw.trim()
  const i = s.lastIndexOf('|')
  if (i < 0 || i < s.indexOf('://')) return { url: s }
  const tail = s.slice(i + 1)
  // `|` 后面必须长得像 `k=v`,否则它是地址的一部分
  if (!/^[\w-]+=/.test(tail)) return { url: s }
  const headers: Record<string, string> = {}
  for (const kv of tail.split('&')) {
    const j = kv.indexOf('=')
    if (j > 0) headers[normHeader(kv.slice(0, j))] = decodeURIComponent(kv.slice(j + 1))
  }
  return { url: s.slice(0, i), headers }
}

/** `user-agent` / `UA` / `referrer` 各家写法不一,统一成 HTTP 头的写法。 */
function normHeader(k: string): string {
  const low = k.trim().toLowerCase()
  if (low === 'ua' || low === 'user-agent' || low === 'http-user-agent') return 'User-Agent'
  if (low === 'referer' || low === 'referrer' || low === 'http-referrer') return 'Referer'
  if (low === 'origin' || low === 'http-origin') return 'Origin'
  return k.trim()
}

/** 一条 `#EXTVLCOPT:k=v`。只认与取流有关的那几个,其余(音轨、缓存)交给播放器。 */
function vlcOpt(line: string, into: Record<string, string>): void {
  const body = line.slice('#EXTVLCOPT:'.length)
  const i = body.indexOf('=')
  if (i <= 0) return
  const k = body.slice(0, i).trim().toLowerCase()
  const v = body.slice(i + 1).trim()
  if (k === 'http-user-agent' || k === 'http-referrer' || k === 'http-origin') {
    into[normHeader(k)] = v
  }
}

export function parseM3U(text: string): Playlist {
  const lines = text.replace(/\r/g, '').split('\n')
  const channels: Channel[] = []
  const epg: string[] = []

  let pending: Channel | null = null
  let headers: Record<string, string> = {}
  let fileCatchup: Catchup | undefined

  for (const raw of lines) {
    const line = raw.trim()
    if (!line) continue

    if (line.startsWith('#EXTM3U')) {
      const a = parseAttrs(line)
      for (const key of ['x-tvg-url', 'url-tvg', 'tvg-url']) {
        for (const u of (a[key] || '').split(',')) {
          if (u.trim()) epg.push(u.trim())
        }
      }
      fileCatchup = catchupOf(a)
      continue
    }

    if (line.startsWith('#EXTINF')) {
      const a = parseAttrs(line)
      const comma = line.lastIndexOf(',')
      const name = (comma >= 0 ? line.slice(comma + 1) : '').trim() || a['tvg-name'] || '未命名'
      headers = {}
      pending = {
        name,
        tvgId: a['tvg-id'] || undefined,
        logo: a['tvg-logo'] || undefined,
        group: a['group-title'] || '未分组',
        urls: [],
        catchup: catchupOf(a, fileCatchup),
      }
      const chno = Number(a['tvg-chno'] || a['channel-number'] || 0)
      if (chno > 0) pending.number = chno
      continue
    }

    if (line.startsWith('#EXTVLCOPT:')) {
      vlcOpt(line, headers)
      continue
    }
    if (line.startsWith('#EXTHTTP:')) {
      try {
        const o = JSON.parse(line.slice('#EXTHTTP:'.length))
        for (const k of Object.keys(o || {})) headers[normHeader(k)] = String(o[k])
      } catch {
        // 这一行写坏了只影响请求头,频道本身照样能播 —— 不要因此丢掉整个频道
      }
      continue
    }
    if (line.startsWith('#')) continue

    if (pending) {
      const u = splitUrlHeaders(line)
      const merged = { ...headers, ...(u.headers || {}) }
      pending.urls.push(Object.keys(merged).length ? { url: u.url, headers: merged } : { url: u.url })
      channels.push(pending)
      pending = null
      headers = {}
    }
  }
  return { channels, epg }
}

/**
 * TXT 格式(TVBox 的 `lives` 常用):
 * ```
 * 央视,#genre#
 * CCTV1,http://a#http://b
 * ```
 * `#genre#` 那一行是分组名;频道行的多个地址用 `#` 分开。
 */
export function parseTXT(text: string): Playlist {
  const channels: Channel[] = []
  let group = '未分组'
  for (const raw of text.replace(/\r/g, '').split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const i = line.indexOf(',')
    if (i <= 0) continue
    const left = line.slice(0, i).trim()
    const right = line.slice(i + 1).trim()
    if (right === '#genre#') {
      group = left || '未分组'
      continue
    }
    // 分组行之外,右边不像地址的行直接跳过(有些源在中间插说明文字)
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(right) && !right.includes('://')) continue
    const urls = right.split('#').map((s) => s.trim()).filter(Boolean).map(splitUrlHeaders)
    if (urls.length === 0) continue
    channels.push({ name: left, group, urls })
  }
  return { channels, epg: [] }
}

/** 按内容判格式再解析 —— 文件名与 Content-Type 都不可靠。 */
export function parsePlaylist(text: string): Playlist {
  return text.trimStart().startsWith('#EXTM3U') || text.includes('#EXTINF')
    ? parseM3U(text)
    : parseTXT(text)
}
