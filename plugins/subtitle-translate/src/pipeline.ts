/**
 * 翻译管线(SPEC 17.4,D364 D487~D489)。
 *
 * ☠ **照搬旧实现已验证的做法**,一条都别自作聪明:
 *   分块 → 并发 → 失败二分重试 → 单条失败回退原文 → **全部失败要报错**。
 *   最后那条是整条管线里最重要的一句:静默交出一份没翻的字幕,
 *   用户看到的是「翻译了但没变化」,而真相是引擎根本不可用(没开通 / 鉴权错)。
 */

export interface Cue {
  start: number
  end: number
  text: string
  translated?: string
}

export interface Engine {
  /** 单批最多几条。 */
  maxBatch: number
  /** 单批最多几个字(0 = 不限)。 */
  maxChars: number
  /** 并发批次上限。限流敏感的引擎取 1。 */
  concurrency: number
  /** 翻译一批,返回与输入**等长**的译文。长度不等就抛,交给二分重试。 */
  translate(texts: string[], from: string, to: string): Promise<string[]>
}

/**
 * 按引擎能力切批。
 * 条数超限或累计字数超限就断批;**单条超限也自成一批**,不丢。
 */
export function chunkRanges(cues: Cue[], maxSize: number, maxChars: number): [number, number][] {
  const out: [number, number][] = []
  let start = 0
  let chars = 0
  for (let i = 0; i < cues.length; i++) {
    const len = [...cues[i].text].length
    const cur = i - start
    const overSize = cur >= maxSize
    const overChars = maxChars > 0 && chars + len > maxChars
    if (cur > 0 && (overSize || overChars)) {
      out.push([start, i])
      start = i
      chars = 0
    }
    chars += len
  }
  if (start < cues.length) out.push([start, cues.length])
  return out
}

interface Outcome {
  texts: string[]
  failed: number
  err?: any
}

/**
 * 翻一块;引擎抛错(比如回包条数不齐)就**二分重试**,单条仍失败回退原文。
 *
 * ☠ 回退原文而不是丢掉那一条:译文要贴回**对应那一条**,少一条整轨就错位了。
 */
export async function translateChunk(engine: Engine, texts: string[], from: string, to: string): Promise<Outcome> {
  if (texts.length === 0) return { texts: [], failed: 0 }
  try {
    const v = await engine.translate(texts, from, to)
    if (v.length !== texts.length) throw new Error('引擎回了 ' + v.length + ' 条,给的是 ' + texts.length + ' 条')
    return { texts: v, failed: 0 }
  } catch (e) {
    if (texts.length === 1) return { texts, failed: 1, err: e }
    const mid = texts.length >> 1
    const l = await translateChunk(engine, texts.slice(0, mid), from, to)
    const r = await translateChunk(engine, texts.slice(mid), from, to)
    return { texts: l.texts.concat(r.texts), failed: l.failed + r.failed, err: r.err || l.err }
  }
}

/** 就地填 `translated`。全部失败时**抛错**,不交出一份没翻的字幕。 */
export async function translateDocument(
  cues: Cue[],
  engine: Engine,
  from: string,
  to: string,
  onProgress?: (done: number, total: number) => void,
): Promise<void> {
  const total = cues.length
  if (total === 0) return
  const chunks = chunkRanges(cues, engine.maxBatch, engine.maxChars)
  const conc = Math.max(1, Math.min(engine.concurrency, 8))

  let done = 0
  let failed = 0
  let lastErr: any

  // 按并发能力分波:每波起 conc 个批次,等齐再下一波
  for (let i = 0; i < chunks.length; i += conc) {
    const wave = chunks.slice(i, i + conc)
    const results = await Promise.all(wave.map(([s, e]) =>
      translateChunk(engine, cues.slice(s, e).map((c) => c.text), from, to)))
    for (let j = 0; j < wave.length; j++) {
      const [s, e] = wave[j]
      for (let k = 0; k < results[j].texts.length && s + k < e; k++) {
        cues[s + k].translated = results[j].texts[k]
      }
      failed += results[j].failed
      if (results[j].err) lastErr = results[j].err
      done += e - s
      if (onProgress) onProgress(done, total)
    }
  }

  /* ☠ 判的是**条数**,不是「有没有拿到一个 truthy 的错误对象」。
     上一版写成 `failed >= total && lastErr`:引擎 reject 一个空串 / undefined 时
     lastErr 是 falsy,于是全部失败也静默交出一份没翻的字幕 —— 用户看到的是
     「翻译了但没变化」,而真相是引擎根本不可用。 */
  if (failed >= total) {
    const why = (lastErr && lastErr.message) || String(lastErr || '引擎没说原因')
    throw new Error('翻译引擎不可用,全部 ' + total + ' 条都失败了:' + why)
  }
  // 大部分失败也要留痕:99/100 失败时字幕看起来「有内容」,没人会怀疑是这里
  if (failed > 0) {
    console.warn('翻译有 ' + failed + '/' + total + ' 条没成功,这些条回退成了原文')
  }
}

// ---------------------------------------------------------------- 解析与输出

/** 粗判是不是字幕。为的是**别把 404 的 HTML 错误页当字幕** —— 那会解析出 0 条,
 *  报一句「源字幕是空的」,而真实原因是那个地址压根不对。 */
export function looksLikeSubtitle(body: string): boolean {
  const head = (body || '').slice(0, 4000)
  if (!head.trim()) return false
  return head.includes('-->') || head.includes('Dialogue:') ||
    head.trimStart().startsWith('WEBVTT') || head.includes('[Script Info]')
}

const TS = /(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})/

function toSec(m: RegExpMatchArray): number {
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) + Number(m[4].padEnd(3, '0')) / 1000
}

/** 解 SRT / VTT。ASS 走另一条(它有自己的样式行,不能按空行分块)。 */
export function parseSrt(text: string): Cue[] {
  const out: Cue[] = []
  for (const block of text.replace(/\r/g, '').split(/\n\s*\n/)) {
    const lines = block.split('\n').filter((l) => l.trim() !== '')
    if (lines.length === 0) continue
    let i = 0
    // 序号行可有可无(VTT 没有)
    if (/^\d+$/.test(lines[0].trim()) && lines.length > 1) i = 1
    const arrow = lines[i] || ''
    if (!arrow.includes('-->')) continue
    const [a, b] = arrow.split('-->')
    const ma = a.match(TS)
    const mb = b.match(TS)
    if (!ma || !mb) continue
    const body = lines.slice(i + 1).join('\n').trim()
    if (body) out.push({ start: toSec(ma), end: toSec(mb), text: body })
  }
  return out
}

function srtTime(sec: number): string {
  const ms = Math.round((sec - Math.floor(sec)) * 1000)
  const s = Math.floor(sec)
  const p = (n: number, w = 2) => String(n).padStart(w, '0')
  return p(Math.floor(s / 3600)) + ':' + p(Math.floor(s / 60) % 60) + ':' + p(s % 60) + ',' + p(ms, 3)
}

export type Layout = 'translated' | 'bilingual' | 'source'

/** 出 SRT。三种排版(D364):只译文 / 双语 / 只原文。 */
export function toSrt(cues: Cue[], layout: Layout): string {
  const parts: string[] = []
  cues.forEach((c, i) => {
    const tr = c.translated || c.text
    const body = layout === 'source' ? c.text
      : layout === 'translated' ? tr
      // 双语:译文在上 —— 眼睛先落在第一行,而用户要的是看得懂的那一行
      : tr === c.text ? c.text : tr + '\n' + c.text
    parts.push(String(i + 1) + '\n' + srtTime(c.start) + ' --> ' + srtTime(c.end) + '\n' + body + '\n')
  })
  return parts.join('\n')
}
