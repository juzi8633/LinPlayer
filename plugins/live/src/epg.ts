/**
 * 节目单(SPEC 17.2,D108 D495):XMLTV 与 DIYP。
 *
 * ☠ XMLTV 用扫描解析而不是建 DOM:一份全国台的 xmltv 有几十万个 `<programme>`,
 *   在 JS 里建对象树会把插件的内存预算吃光,表现是「打开直播页卡住几十秒然后白屏」。
 * ☠ 时间戳带时区偏移(`20260921120000 +0800`),**不能当本地时间解**:
 *   差 8 小时的节目单看起来是「有节目单但全错位」,而不是「没有节目单」。
 */

export interface Program {
  channel: string
  start: number // 秒
  end: number
  title: string
  desc?: string
}

/** `20260921120000 +0800` / `20260921120000Z` / `20260921120000` → 秒。解不出回 0。 */
export function parseXmltvTime(s: string): number {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4}|Z)?/.exec(s.trim())
  if (!m) return 0
  const utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0))
  const tz = m[7]
  if (!tz || tz === 'Z') return Math.round(utc / 1000)
  const sign = tz[0] === '-' ? 1 : -1 // 偏移要减回去才是 UTC
  const off = (+tz.slice(1, 3) * 60 + +tz.slice(3, 5)) * 60
  return Math.round(utc / 1000) + sign * off
}

function unescapeXml(s: string): string {
  return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, d) => String.fromCharCode(+d))
    .replace(/&amp;/g, '&') // 放最后:先换 &amp; 会把 &amp;lt; 变成 <
}

function tagText(block: string, tag: string): string {
  const m = new RegExp('<' + tag + '\\b[^>]*>([\\s\\S]*?)</' + tag + '>').exec(block)
  return m ? unescapeXml(m[1]).trim() : ''
}

function attr(open: string, name: string): string {
  const m = new RegExp(name + '\\s*=\\s*"([^"]*)"').exec(open)
  return m ? unescapeXml(m[1]) : ''
}

/**
 * 扫一遍 XMLTV,每解出一条就交给 `onProgram`。
 *
 * `onChannel` 收 `<channel>` 里的 id → 显示名 / 台标:频道匹配要靠它,
 * m3u 的 `tvg-id` 对不上时还能用显示名兜一层。
 */
export function scanXmltv(
  xml: string,
  onProgram: (p: Program) => void,
  onChannel?: (id: string, name: string, icon: string) => void,
): void {
  if (onChannel) {
    const re = /<channel\b([^>]*)>([\s\S]*?)<\/channel>/g
    let m: RegExpExecArray | null
    while ((m = re.exec(xml)) !== null) {
      const id = attr(m[1], 'id')
      if (!id) continue
      const icon = /<icon\b[^>]*src\s*=\s*"([^"]*)"/.exec(m[2])
      onChannel(id, tagText(m[2], 'display-name'), icon ? unescapeXml(icon[1]) : '')
    }
  }
  const re = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g
  let m: RegExpExecArray | null
  while ((m = re.exec(xml)) !== null) {
    const channel = attr(m[1], 'channel')
    const start = parseXmltvTime(attr(m[1], 'start'))
    const end = parseXmltvTime(attr(m[1], 'stop'))
    const title = tagText(m[2], 'title')
    if (!channel || !start || !title) continue
    const desc = tagText(m[2], 'desc')
    // stop 缺失的源不少:留 0 让上层按「下一条的 start」补,别猜一个时长
    onProgram(desc ? { channel, start, end, title, desc } : { channel, start, end, title })
  }
}

/**
 * 按开始时间排好,并把缺失的 `end` 补成下一条的 `start`。
 *
 * ☠ 不补的话进度条量程是 0,信息条上「当前节目」永远显示第一条。
 *   最后一条没得补时给 1 小时 —— 它是**猜**的,但总比 0 强:0 会让 UI 除零。
 */
export function normalizePrograms(list: Program[]): Program[] {
  const out = list.slice().sort((a, b) => a.start - b.start)
  for (let i = 0; i < out.length; i++) {
    if (out[i].end > out[i].start) continue
    out[i].end = i + 1 < out.length ? out[i + 1].start : out[i].start + 3600
  }
  return out
}

/** DIYP 一天一个频道的回包。 */
export function parseDiyp(json: any, dayStartSec: number): Program[] {
  const name = String((json && json.channel_name) || '')
  const arr = (json && json.epg_data) || []
  const out: Program[] = []
  for (const it of arr) {
    const s = hhmm(String(it.start || ''), dayStartSec)
    const e = hhmm(String(it.end || ''), dayStartSec)
    const title = String(it.title || '').trim()
    if (!s || !title) continue
    // 跨零点的节目 end 会小于 start,补一天
    out.push({ channel: name, start: s, end: e > s ? e : e + 86400, title })
  }
  return out
}

function hhmm(s: string, dayStartSec: number): number {
  const m = /^(\d{1,2}):(\d{2})/.exec(s.trim())
  return m ? dayStartSec + (+m[1] * 3600 + +m[2] * 60) : 0
}

/** 当前正在播的那一条,以及下一条。 */
export function nowNext(list: Program[], nowSec: number): { now?: Program; next?: Program } {
  for (let i = 0; i < list.length; i++) {
    if (nowSec < list[i].start) return { next: list[i] }
    if (nowSec < list[i].end) return { now: list[i], next: list[i + 1] }
  }
  return {}
}
