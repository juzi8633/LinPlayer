/**
 * UHD 站点的 `/api/v1` 客户端。
 *
 * 端点全部 2026-09-21 在真站上实测过(见 docs/lessons/plugins.md「UHD 站点接口实测」),
 * 不是照着旧插件抄的 —— 旧插件里的「全部求片」`/media-requests/list` 现在是 404,
 * 站点换成了「话题广场」`/media-requests/topics/list`,投票接口整个没了。
 *
 * ☠ 站点地址**不写死在代码里**,从设置读。仓库红线:任何域名不进提交。
 */
import { PluginError, secrets, settings, storage } from '@linplayer/plugin-sdk'

export interface Traffic {
  usedBytes: number
  limitBytes: number
  unlimited: boolean
}

export interface Line {
  id: string
  name: string
  description?: string
  /** 线路入口地址。**UI 上不显示** —— 用户要求不暴露 domain。 */
  domain: string
}

export interface Found {
  tmdbId: number
  mediaType: string
  title: string
  originalTitle?: string
  year?: number
  poster?: string
  overview?: string
  inLibrary: boolean
  allowed: boolean
  blockedReason?: string
}

export interface Topic {
  id: string
  title: string
  year?: number
  mediaType: string
  poster?: string
  open: number
  total: number
  missing: number
  refresh: number
  feedback: number
  latestAt?: string
}

/** 站点地址。没填就让调用方说人话,而不是去请求一个空地址。 */
export function site(): string {
  const s = String(settings.get('site') ?? '').trim().replace(/\/+$/, '')
  if (!s) throw new PluginError({ kind: 'invalid', message: '还没填站点地址 —— 到设置里的「UHD 助手」填上你的站点地址和账号' })
  if (!/^https?:\/\//i.test(s)) throw new PluginError({ kind: 'invalid', message: `站点地址要带 http:// 或 https://,现在是「${s}」` })
  return s
}

function ua(): string {
  // ★ 必须显式给 UA:挂 CDN 的站对没有 UA 的请求直接 403,而那个错长得像「账号不对」
  return 'Mozilla/5.0 (LinPlayer UHD Plugin)'
}

function headers(token?: string): Record<string, string> {
  const base = site()
  const h: Record<string, string> = {
    'Content-Type': 'application/json',
    'User-Agent': ua(),
    Origin: base,
    Referer: base + '/',
  }
  if (token) {
    // ★ 是**裸 token**,不是 Bearer。站点前端同时还带一个同名 Cookie,照做。
    h.Authorization = token
    h.Cookie = 'Authorization=' + token
  }
  return h
}

async function readJson(res: Response, what: string): Promise<any> {
  const text = await res.text()
  try {
    return JSON.parse(text)
  } catch {
    // 停靠页 / 网关错误页会回 HTML,原样糊给用户等于没说
    throw new PluginError({
      kind: 'parseFailed',
      message: `${what}:站点返回的不是接口数据(HTTP ${res.status})—— 站点地址填对了吗?`,
    })
  }
}

/** 登录换 token。token 存密钥区,不进普通存储、不进备份的「不带账号」档。 */
async function login(): Promise<string> {
  const u = String(settings.get('username') ?? '').trim()
  const p = String(settings.get('password') ?? '').trim() || secrets.get('password') || ''
  if (!u || !p) throw new PluginError({ kind: 'auth', message: '还没填网站账号 —— 到设置里的「UHD 助手」填用户名和密码' })
  const res = await fetch(site() + '/api/v1/auth/login', {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ username: u, password: p }),
    lp: { timeout: { connect: 10000, idle: 20000 } },
  })
  const j = await readJson(res, '登录')
  if (!j?.ok || !j?.data?.token) {
    throw new PluginError({ kind: 'auth', message: '登录失败:' + (j?.msg || `HTTP ${res.status}`) })
  }
  secrets.set('token', String(j.data.token))
  return String(j.data.token)
}

/**
 * 带鉴权的请求。401 自动重登一次再重试 —— token 有有效期,过期了不该让用户自己去点重登。
 * `base` 给测速用:会话和下载要打解析出来的那条线路,不是主站。
 */
export async function api(method: 'GET' | 'POST', path: string, body?: unknown, base?: string): Promise<any> {
  let token = secrets.get('token') || (await login())
  for (let round = 0; round < 2; round++) {
    const res = await fetch((base || site()) + path, {
      method,
      headers: headers(token),
      body: body === undefined ? undefined : JSON.stringify(body),
      lp: { timeout: { connect: 10000, idle: 30000 } },
    })
    if (res.status === 401 && round === 0) {
      secrets.remove('token')
      token = await login()
      continue
    }
    const j = await readJson(res, path)
    if (j?.ok) return j.data
    throw new PluginError({ kind: res.status >= 500 ? 'siteDown' : 'invalid', message: j?.msg || `请求失败(HTTP ${res.status})` })
  }
  throw new PluginError({ kind: 'auth', message: '登录状态一直拿不到,检查账号密码' })
}

export async function traffic(): Promise<Traffic> {
  const d = await api('GET', '/api/v1/traffic/me')
  return {
    usedBytes: Number(d?.used_bytes) || 0,
    limitBytes: Number(d?.limit_bytes) || 0,
    // ★ 站点有「不限流量」档:limit 照样是个数,只是不该拿它算百分比
    unlimited: d?.display_unlimited_traffic === true,
  }
}

export async function me(): Promise<{ name: string; balance: number }> {
  const d = await api('GET', '/api/v1/users/me')
  return { name: String(d?.name ?? ''), balance: Number(d?.balance) || 0 }
}

export async function lines(): Promise<Line[]> {
  const d = await api('GET', '/api/v1/subscriptions/domains')
  const arr: any[] = Array.isArray(d) ? d : []
  return arr
    .filter((x) => x && x.id)
    // ☠ 真站上有条线路的 domain 前面带一个空格,不 trim 的话 URL 拼出来就是错的
    .map((x) => ({ id: String(x.id), name: String(x.name || x.id), description: x.description, domain: String(x.domain || '').trim().replace(/\/+$/, '') }))
}

/** 解析出真正要打的节点。返回的是动态 CDN host,**不要缓存**。 */
export async function resolveLine(id: string): Promise<{ node: string; name: string }> {
  const d = await api('GET', '/api/v1/subscriptions/domains/' + encodeURIComponent(id) + '/resolve')
  const node = String(d?.domain || '').trim().replace(/\/+$/, '')
  if (!node) throw new PluginError({ kind: 'notFound', message: '这条线路解析不出可用节点' })
  return { node, name: String(d?.name || '') }
}

const POSTER = (p: unknown): string | undefined => {
  const s = String(p ?? '')
  if (!s) return undefined
  // 三种形态:完整 URL / 站点自托管的 /img/... / 裸 TMDB 路径
  if (/^https?:\/\//i.test(s)) return s
  if (s.startsWith('/img/')) return site() + s
  return 'https://image.tmdb.org/t/p/w342' + (s.startsWith('/') ? s : '/' + s)
}

export async function search(keyword: string, type: string): Promise<Found[]> {
  const d = await api('POST', '/api/v1/media-requests/search', { keyword, request_type: type, page: 1, page_size: 20 })
  const arr: any[] = Array.isArray(d) ? d : d?.list ?? []
  return arr.map((x) => ({
    tmdbId: Number(x.tmdb_id),
    mediaType: String(x.media_type || 'movie'),
    title: String(x.title || x.original_title || ''),
    originalTitle: x.original_title,
    year: x.year,
    poster: POSTER(x.poster_path),
    overview: x.overview,
    inLibrary: x.exists_in_library === true,
    allowed: x.allowed_to_create !== false,
    blockedReason: x.blocked_reason || undefined,
  }))
}

/**
 * 提交求片 / 追新 / 反馈。
 *
 * ☠ `content`(说明)**必填**,站点上留空回的是「参数验证失败」——
 * 实测过这条错误路径(2026-09-21)。body 只有这四个字段,多给的会被忽略。
 */
export async function createRequest(f: Found, type: string, content: string): Promise<string> {
  const d = await api('POST', '/api/v1/media-requests', {
    request_type: type,
    media_type: f.mediaType,
    tmdb_id: f.tmdbId,
    content: content.trim(),
  })
  return String(d?.topic_id ?? '')
}

function topicOf(x: any): Topic {
  const m = x?.media ?? {}
  return {
    id: String(x?.id ?? ''),
    title: String(m.title || m.original_title || ''),
    year: m.year,
    mediaType: String(m.media_type || ''),
    poster: POSTER(m.poster_path),
    open: Number(x?.open_item_count) || 0,
    total: Number(x?.total_item_count) || 0,
    missing: Number(x?.missing_count) || 0,
    refresh: Number(x?.refresh_count) || 0,
    feedback: Number(x?.feedback_count) || 0,
    latestAt: x?.latest_request_at,
  }
}

export async function myRequests(page: number): Promise<{ items: Topic[]; total: number }> {
  const d = await api('POST', '/api/v1/media-requests/mine/list', { page, page_size: 20 })
  return { items: (d?.list ?? []).map(topicOf), total: Number(d?.total) || 0 }
}

export async function plaza(page: number): Promise<{ items: Topic[]; total: number }> {
  const d = await api('POST', '/api/v1/media-requests/topics/list', { page, page_size: 20 })
  return { items: (d?.list ?? []).map(topicOf), total: Number(d?.total) || 0 }
}

export interface SpeedProgress {
  bytes: number
  totalBytes: number
  mbps: number
}

/** 官网只收这三个大小,给别的值会被「参数验证失败」挡回来。 */
export const SIZES = [32, 64, 100]

/**
 * 一条线路跑一次测速。**会真的下载 sizeMib 的数据,消耗账户流量。**
 *
 * ☠ 会话与下载都打**解析出来的节点**,report 打主站;`size_mb` 必须等于会话的
 * `size_mib`,对不上服务端只回几十字节 —— 所以不能分段下载。
 * ★ 前 300 毫秒 / 1 MiB 是热身,不计进平均:连接刚建起来那几个包算进去,
 *   测出来的数比真实带宽低一截。官网也是这么算的。
 */
export async function speedTest(line: Line, sizeMib: number, onProgress: (p: SpeedProgress) => void): Promise<{ avgMbps: number; peakMbps: number; bytes: number; ms: number; node: string }> {
  const { node } = await resolveLine(line.id)
  const sess = await api('POST', '/api/v1/speed-test/session', { parent_domain_id: line.id, size_mib: sizeMib }, node)
  const sid = String(sess?.session_id ?? '')
  const rtok = String(sess?.report_token ?? '')
  if (!sid || !rtok) throw new PluginError({ kind: 'invalid', message: '测速会话建不起来' })

  const url = `${node}/api/v1/speed-test/download?size_mb=${sizeMib}&session_id=${encodeURIComponent(sid)}`
  const res = await fetch(url, {
    headers: { Authorization: secrets.get('token') || '', 'User-Agent': ua(), Referer: site() + '/' },
    lp: { timeout: { connect: 10000, idle: 30000 } },
  })
  if (!res.ok || !res.body) {
    throw new PluginError({ kind: res.status === 403 ? 'invalid' : 'siteDown', message: res.status === 403 ? '这条线路或流量当前不可用' : `测速下载失败(HTTP ${res.status})` })
  }
  const totalBytes = Number(res.headers.get('content-length')) || sizeMib * 1048576

  const reader = res.body.getReader()
  const t0 = Date.now()
  let bytes = 0
  let warmAt = 0
  let warmBytes = 0
  let peak = 0
  let lastAt = t0
  let lastBytes = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    bytes += value.byteLength
    const now = Date.now()
    if (!warmAt && now - t0 >= 300 && bytes > 1048576) {
      warmAt = now
      warmBytes = bytes
    }
    if (now - lastAt >= 250) {
      const inst = ((bytes - lastBytes) * 8) / ((now - lastAt) / 1000) / 1e6
      if (inst > peak) peak = inst
      lastAt = now
      lastBytes = bytes
      onProgress({ bytes, totalBytes, mbps: inst })
    }
  }
  const ms = Math.max(Date.now() - t0, 1)
  const avgMs = warmAt ? Math.max(Date.now() - warmAt, 250) : ms
  const avgBytes = warmAt ? bytes - warmBytes : bytes
  const avgMbps = (avgBytes * 8) / (avgMs / 1000) / 1e6
  if (peak <= 0) peak = avgMbps

  // 上报失败不影响用户看到结果 —— 那是站点侧的统计,不是他要的东西
  try {
    await api('POST', '/api/v1/speed-test/report', {
      session_id: sid,
      report_token: rtok,
      average_mbps: Number(avgMbps.toFixed(3)),
      peak_mbps: Number(peak.toFixed(3)),
      elapsed_ms: Math.round(ms),
      sample_count: 1,
      server_downloaded_bytes: bytes,
    })
  } catch {
    // 忽略
  }
  return { avgMbps, peakMbps: peak, bytes, ms, node }
}

/** 上次测速结果,按线路 id 记 —— 换台/重进页面不该把刚测的数丢掉。 */
export function lastSpeed(id: string): { mbps: number; at: number } | undefined {
  return storage.get<{ mbps: number; at: number }>('speed:' + id)
}

export function rememberSpeed(id: string, mbps: number) {
  storage.set('speed:' + id, { mbps, at: Date.now() })
}
