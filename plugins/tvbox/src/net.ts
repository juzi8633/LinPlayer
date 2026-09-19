// 请求与错误映射(SPEC 17.1):403/验证页 → needVerify;429 → rateLimited;连接失败/5xx → siteDown。
import { PluginError } from '@linplayer/plugin-sdk'

export const MOBILE_UA =
  'Mozilla/5.0 (Linux; Android 11; M2007J3SC Build/RKQ1.200826.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/77.0.3865.120 MQQBrowser/6.2 TBS/045714 Mobile Safari/537.36'

export interface ReqOpts {
  headers?: Record<string, string>
  timeout?: number
  signal?: AbortSignal
  cookieJar?: string
  method?: string
  body?: string
}

export async function fetchResp(url: string, o: ReqOpts = {}): Promise<Response> {
  let res: Response
  try {
    res = await fetch(url, {
      method: o.method ?? 'GET',
      body: o.body,
      headers: { 'User-Agent': MOBILE_UA, ...(o.headers ?? {}) },
      signal: o.signal,
      lp: { cookieJar: o.cookieJar, timeout: o.timeout ? { connect: Math.min(o.timeout, 10000), idle: o.timeout } : undefined },
    })
  } catch (e) {
    if (e instanceof PluginError && e.kind !== 'network') throw e
    throw new PluginError({ kind: 'siteDown', message: '站点连不上', detail: String(e) })
  }
  if (res.status === 429) {
    const ra = Number(res.headers.get('retry-after') ?? '')
    throw new PluginError({ kind: 'rateLimited', message: '请求太频繁', retryAfter: Number.isFinite(ra) && ra > 0 ? ra : 30 })
  }
  if (res.status === 403) throw new PluginError({ kind: 'needVerify', message: '站点需要验证', verifyUrl: url })
  if (res.status === 404) throw new PluginError({ kind: 'notFound', message: '站点上没有这个内容' })
  if (res.status >= 500) throw new PluginError({ kind: 'siteDown', message: `站点暂时不可用(${res.status})` })
  if (res.status >= 400) throw new PluginError({ kind: 'network', message: `站点返回 ${res.status}` })
  return res
}

// 「JS 写一个校验 cookie 再刷新」的防护页:不用真跑脚本,抠出那句赋值带上重试一次即可(影视仓同款做法)
const JS_COOKIE = /document\.cookie\s*=\s*["']([^"';=\s]+=[^"';]+)/

export async function fetchText(url: string, o: ReqOpts = {}): Promise<string> {
  const res = await fetchResp(url, o)
  let text = await res.text()
  const jsCookie = text.length < 4000 ? JS_COOKIE.exec(text) : null
  if (jsCookie && !o.headers?.Cookie) {
    text = await (await fetchResp(url, { ...o, headers: { ...(o.headers ?? {}), Cookie: jsCookie[1] } })).text()
  }
  if (/cf-browser-verification|challenge-platform|人机验证|安全验证/.test(text.slice(0, 4000))) {
    throw new PluginError({ kind: 'needVerify', message: '站点需要验证', verifyUrl: url })
  }
  return text
}

export async function fetchJson<T = any>(url: string, o: ReqOpts = {}): Promise<T> {
  const text = await fetchText(url, o)
  try {
    return JSON.parse(text)
  } catch (e) {
    throw new PluginError({ kind: 'parseFailed', message: '站点返回的不是 JSON', detail: text.slice(0, 200) })
  }
}

export function isMedia(url: string): boolean {
  return /\.(m3u8|mp4|flv|mkv|avi|mov|ts|m4a|mp3)(\?|#|$)/i.test(url) || /\/m3u8\b|mime=video|\.m3u8/i.test(url)
}
