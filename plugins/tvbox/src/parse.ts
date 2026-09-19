// 解析链与嗅探(D58 D59 D114 D257 D440):线路在 flags 里或返回 parse=1 就走解析;
// 按用户在解析管理里排好的顺序逐个试,type 0 与兜底走 WebView 嗅探。
import { app, settings, storage, webview, PluginError, type PlayResult } from '@linplayer/plugin-sdk'
import type { ParseCfg } from './config'
import { fetchJson, isMedia } from './net'
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

/** 把源给的播放结果变成最终可播地址(D257:play() 必须返回最终地址)。 */
export async function resolvePlay(raw: PlayRaw, flag: string, sub: SubInfo | undefined, signal?: AbortSignal): Promise<PlayResult> {
  const url = String(raw.url ?? '')
  if (!url) throw new PluginError({ kind: 'parseFailed', message: '源没有给出播放地址' })
  const needParse = raw.parse === 1 || raw.jx === 1 || (sub?.flags ?? []).includes(flag)
  if (!needParse && (isMedia(url) || !/^https?:/.test(url))) return { url, headers: raw.header }
  if (!needParse && !/\.(html?|php|shtml)(\?|$)/i.test(url)) return { url, headers: raw.header }
  const errors: string[] = []
  for (const p of sub ? orderedParsers(sub) : []) {
    try {
      const r = await tryParser(p, url, signal)
      if (r) return r
      errors.push(`${p.name}:没解析出地址`)
    } catch (e) {
      errors.push(`${p.name}:${(e as any)?.message ?? e}`)
    }
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
