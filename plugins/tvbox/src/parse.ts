// 解析链与嗅探(D58 D59 D114 D257 D440):线路在 flags 里或返回 parse=1 就走解析;
// 按用户在解析管理里排好的顺序逐个试,type 0 与兜底走 WebView 嗅探。
import { app, settings, storage, webview, PluginError, type PlayResult } from '@linplayer/plugin-sdk'
import type { ParseCfg } from './config'
import { fetchJson, fetchResp, isMedia } from './net'
import type { PlayRaw } from './sites'

export interface SubInfo {
  id: string
  name: string
  url: string
  parses: ParseCfg[]
  flags: string[]
  rules: { hosts: string[]; regex: string[] }[]
  ads: string[]
}

/** 解析顺序:用户排过就按用户的(按名字记,不跨设备同步 D325),新出现的排在后面。 */
export function orderedParsers(sub: SubInfo): ParseCfg[] {
  const order = storage.get<string[]>('parserOrder:' + sub.id) ?? []
  const byName = new Map(sub.parses.map((p) => [p.name, p] as const))
  const out: ParseCfg[] = []
  for (const n of order) {
    const p = byName.get(n)
    if (p) {
      out.push(p)
      byName.delete(n)
    }
  }
  return out.concat([...byName.values()])
}

async function tryParser(p: ParseCfg, url: string, signal?: AbortSignal): Promise<PlayResult | null> {
  const target = p.url + url
  if (p.type === 1 || p.type === 2) {
    const r = await fetchJson<any>(target, { headers: p.header, timeout: 15000, signal })
    const u = r?.url ?? r?.data?.url
    if (typeof u === 'string' && u.startsWith('http')) {
      const headers = r.header ?? r.headers ?? (r['user-agent'] ? { 'User-Agent': r['user-agent'] } : undefined)
      return { url: u, headers, parser: p.name }
    }
    return null
  }
  if (p.type === 0) {
    const r = await webview.sniff(target, { headers: p.header, timeout: sniffMs(), visibleAfter: sniffMs(), allowVisible: true })
    return { url: r.url, headers: r.headers, parser: p.name }
  }
  return null // type 3 聚合/jar 类解析随 jar 环境,由 spider 源自己处理
}

function sniffMs(): number {
  const s = Number(settings.get('sniffTimeout') ?? 15)
  return (Number.isFinite(s) && s > 0 ? s : 15) * 1000
}

// 页面里明文写着的播放地址。`\/` 是 JSON 里转义过的斜杠,先还原再找。
const MEDIA_IN_PAGE = /https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*/i

/**
 * 「云播」线路其实是个网页 —— 抓下来把地址抠出来,不用开 WebView。
 *
 * ☠ 实测(2026-09-21,用户给的 17 个活站):7 个站有这种线路,地址长成
 * `https://<云播站>/play/<一串 id>` —— **没有扩展名**,而原来只有
 * `.html/.php/.shtml` 才会去解析,于是这条线路被原样丢给播放器,
 * 用户看到的是「放不出来」。页面本身只有 1.3~1.6 KB,是个 DPlayer 壳,
 * m3u8 就在里面,7 个里 6 个一抠就中。
 *
 * ★ 只取前 32 KB:万一判断错了、那个地址其实是视频本身,也不会把整部片读进内存。
 */
async function mediaInPage(url: string, headers?: Record<string, string>, signal?: AbortSignal): Promise<string | null> {
  const res = await fetchResp(url, { headers: { Range: 'bytes=0-32767', ...(headers ?? {}) }, timeout: 15000, signal })
  const ct = (res.headers.get('content-type') ?? '').toLowerCase()
  if (!ct.includes('html') && !ct.includes('text/plain')) return null
  const text = (await res.text()).split('\\/').join('/')
  if (text.trimStart().startsWith('#EXTM3U')) return null // 它自己就是播放列表,交给播放器
  const m = MEDIA_IN_PAGE.exec(text)
  if (!m) return null
  try {
    return new URL(m[0], url).href
  } catch {
    return null
  }
}

/** 把源给的播放结果变成最终可播地址(D257:play() 必须返回最终地址)。 */
export async function resolvePlay(raw: PlayRaw, flag: string, sub: SubInfo | undefined, signal?: AbortSignal): Promise<PlayResult> {
  const url = String(raw.url ?? '')
  if (!url) throw new PluginError({ kind: 'parseFailed', message: '源没有给出播放地址' })
  const needParse = raw.parse === 1 || raw.jx === 1 || (sub?.flags ?? []).includes(flag)
  // 看着就是媒体、或者压根不是 http(自定义协议交给宿主)→ 直接播,不多打一次请求
  if (!needParse && (isMedia(url) || !/^https?:/.test(url))) return { url, headers: raw.header }
  const errors: string[] = []
  /* ☠ 剩下的 http 地址**一律当网页处理**。原来这里是「不像网页就直接播」
     (只认 .html/.php/.shtml),而真实的云播线路没有扩展名 —— 见 mediaInPage。 */
  const fromPage = async (): Promise<PlayResult | null> => {
    try {
      const u = await mediaInPage(url, raw.header, signal)
      return u ? { url: u, headers: raw.header, parser: '页面内地址' } : null
    } catch (e) {
      errors.push(`抓页面:${(e as any)?.message ?? e}`)
      return null
    }
  }
  // 源自己说了要解析(flags / parse=1)就先听它的,它多半指向官方站,页面里抠不出东西
  if (!needParse) {
    const r = await fromPage()
    if (r) return r
  }
  for (const p of sub ? orderedParsers(sub) : []) {
    try {
      const r = await tryParser(p, url, signal)
      if (r) return r
      errors.push(`${p.name}:没解析出地址`)
    } catch (e) {
      errors.push(`${p.name}:${(e as any)?.message ?? e}`)
    }
  }
  if (needParse) {
    const r = await fromPage()
    if (r) return r
  }
  if (app.capabilities?.webview !== false) {
    try {
      const r = await webview.sniff(url, { headers: raw.header, timeout: sniffMs(), visibleAfter: sniffMs(), allowVisible: true })
      return { url: r.url, headers: r.headers, parser: '网页嗅探' }
    } catch (e) {
      errors.push(`网页嗅探:${(e as any)?.message ?? e}`)
    }
  }
  throw new PluginError({ kind: 'parseFailed', message: '解析失败,可以在播放页换个解析或换源', detail: errors.join('\n') })
}

/** 配置自带的 rules / ads 去广告(D238):删掉命中规则的分片。宿主会在删掉超过总时长 30% 时整体放弃(D494)。 */
export function filterM3u8(text: string, url: string, sub: SubInfo | undefined): string {
  if (!sub || settings.get('adRules') === false) return text
  let host = ''
  try {
    host = new URL(url).hostname
  } catch {
    return text
  }
  const regs: RegExp[] = []
  for (const r of sub.rules) {
    if (r.hosts.some((h) => h === '*' || host === h || host.endsWith('.' + h))) {
      for (const re of r.regex) {
        try {
          regs.push(new RegExp(re))
        } catch {
          // 坏正则跳过
        }
      }
    }
  }
  const ads = sub.ads.filter(Boolean)
  if (!regs.length && !ads.length) return text
  const lines = text.split('\n')
  const out: string[] = []
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i]
    if (l.startsWith('#EXTINF')) {
      const seg = lines[i + 1] ?? ''
      if (regs.some((r) => r.test(seg)) || ads.some((a) => seg.includes(a))) {
        i++ // 连同分片地址一起删
        continue
      }
    }
    out.push(l)
  }
  return out.join('\n')
}
