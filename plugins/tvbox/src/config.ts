// TVBox 配置拉取与解码(D100):按影视仓/FongMi 的顺序依次识别
// ;pk; 密钥(AES-ECB)→ 合法 JSON → 「8 位标记 + ** + base64」(图片尾部藏配置同一形状)→ 「2423」开头的 AES-CBC → JSON5。
import { crypt, PluginError } from '@linplayer/plugin-sdk'
import { fetchText } from './net'

export interface SiteCfg {
  key: string
  name: string
  type: number
  api: string
  ext?: unknown
  jar?: string
  searchable?: number
  quickSearch?: number
  filterable?: number
  timeout?: number
  playUrl?: string
  header?: Record<string, string>
}

export interface ParseCfg {
  name: string
  type: number
  url: string
  header?: Record<string, string>
  ext?: unknown
}

export interface TvConfig {
  sites?: SiteCfg[]
  parses?: ParseCfg[]
  flags?: string[]
  rules?: { host?: string; hosts?: string[]; rule?: string[]; regex?: string[]; name?: string }[]
  ads?: string[]
  lives?: { name?: string; url?: string; type?: number; group?: string }[]
  spider?: string
  urls?: { url: string; name?: string }[]
  storeHouse?: { sourceName?: string; sourceUrl?: string }[]
}

export interface Repo {
  id: string
  name: string
  url: string
}

function padEnd(s: string): string {
  return (s + '0000000000000000').slice(0, 16)
}

function hexToText(hex: string): string {
  const bytes = new Uint8Array(hex.length / 2)
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16)
  return new TextDecoder().decode(bytes)
}

function isJson(s: string): boolean {
  const t = s.trim()
  if (!(t.startsWith('{') || t.startsWith('['))) return false
  try {
    JSON.parse(t)
    return true
  } catch {
    return false
  }
}

/** JSON5 的子集:注释、尾逗号、单引号字符串(真实配置里常见的就这三样)。 */
export function parseJson5(src: string): unknown {
  let out = ''
  let i = 0
  while (i < src.length) {
    const c = src[i]
    if (c === '"' || c === "'") {
      let j = i + 1
      let body = ''
      while (j < src.length && src[j] !== c) {
        if (src[j] === '\\') {
          body += src[j] + src[j + 1]
          j += 2
          continue
        }
        body += c === "'" && src[j] === '"' ? '\\"' : src[j]
        j++
      }
      out += '"' + body + '"'
      i = j + 1
      continue
    }
    if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') i++
      continue
    }
    if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2)
      i = end < 0 ? src.length : end + 2
      continue
    }
    if (c === ',') {
      let j = i + 1
      while (j < src.length && /\s/.test(src[j])) j++
      if (src[j] === '}' || src[j] === ']') {
        i++
        continue
      }
    }
    out += c
    i++
  }
  return JSON.parse(out)
}

/** 配置里的相对路径 ./ ../ 换成相对配置地址的绝对地址(同 FongMi 的 fix)。 */
function fixRelative(url: string, text: string): string {
  if (!/^https?:/.test(url)) return text
  const base = url.slice(0, url.lastIndexOf('/') + 1)
  if (text.includes('../')) text = text.split('../').join(new URL('../', base).href)
  if (text.includes('./')) text = text.split('./').join(base)
  return text
}

/** 把拉到的原文解码成配置文本。stage 写明卡在哪一步(D100:解码不出来要说清楚)。 */
export function decodeConfigText(raw: string, url: string, pk?: string): string {
  let text = raw.replace(/^﻿/, '')
  if (pk) {
    try {
      text = crypt.aesDecrypt(text.trim(), { key: padEnd(pk), mode: 'ecb', in: 'hex' })
    } catch (e) {
      throw new PluginError({ kind: 'parseFailed', message: '配置解密失败(;pk; 密钥不对或配置不是 AES 加密的)', detail: String(e) })
    }
  }
  if (isJson(text)) return fixRelative(url, text)
  const m = /[A-Za-z0-9]{8}\*\*/.exec(text)
  if (m) {
    const b64 = text.slice(m.index + 10).replace(/[^A-Za-z0-9+/=_-]/g, '')
    try {
      text = crypt.base64Decode(b64)
    } catch (e) {
      throw new PluginError({ kind: 'parseFailed', message: '配置解码失败:base64 部分损坏', detail: String(e) })
    }
    if (isJson(text)) return fixRelative(url, text)
  }
  // 加密格式按定长截取,首尾的空白(文件末尾的换行)会让截取错位
  text = text.trim()
  if (text.startsWith('2423')) {
    try {
      const idxKey = text.indexOf('2324') + 4
      const key = hexToText(text.slice(0, idxKey)).replace('$#', '').replace('#$', '')
      const iv = hexToText(text.slice(text.length - 26).trim())
      text = crypt.aesDecrypt(text.slice(idxKey, text.length - 26), { key: padEnd(key), iv: padEnd(iv), mode: 'cbc', in: 'hex' })
    } catch (e) {
      throw new PluginError({ kind: 'parseFailed', message: '配置解密失败:AES-CBC 格式不对', detail: String(e) })
    }
    if (isJson(text)) return fixRelative(url, text)
  }
  return fixRelative(url, text)
}

export function parseConfig(text: string): TvConfig {
  try {
    return JSON.parse(text)
  } catch {
    try {
      return parseJson5(text) as TvConfig
    } catch (e) {
      throw new PluginError({ kind: 'parseFailed', message: '配置不是 TVBox 格式(JSON/JSON5 解析失败,也不是已知的加密格式)', detail: String(e) })
    }
  }
}

/** 拉并解码一份配置。地址可带 ;pk;密钥。 */
export async function loadConfig(input: string): Promise<{ cfg: TvConfig; url: string }> {
  const [url, pk] = input.trim().split(';pk;')
  // 拉配置用 okhttp 的 UA(TVBox 系客户端都这样):不少中转按 UA 分流,浏览器 UA 拿到的是下载页
  const raw = await fetchText(url, { timeout: 30000, headers: { 'User-Agent': 'okhttp/3.12.13' } })
  const cfg = parseConfig(decodeConfigText(raw, url, pk))
  if (!cfg || typeof cfg !== 'object') throw new PluginError({ kind: 'parseFailed', message: '配置内容为空' })
  return { cfg, url }
}

/** 多仓配置(urls / storeHouse)列出各仓(D346)。不是多仓返回 null。 */
export function reposOf(cfg: TvConfig): Repo[] | null {
  const list: Repo[] = []
  for (const u of cfg.urls ?? []) if (u && u.url) list.push({ id: shortHash(u.url), name: u.name || u.url, url: u.url })
  for (const s of cfg.storeHouse ?? []) if (s && s.sourceUrl) list.push({ id: shortHash(s.sourceUrl), name: s.sourceName || s.sourceUrl, url: s.sourceUrl })
  if (list.length === 0 || (cfg.sites && cfg.sites.length > 0)) return null
  return list
}

export function shortHash(s: string): string {
  return crypt.md5(s).slice(0, 10)
}
