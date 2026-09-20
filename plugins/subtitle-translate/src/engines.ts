/**
 * 翻译引擎(SPEC 17.4 的「引擎」那一段,D364)。
 *
 * 用户自己的 key,存**密钥区**(manifest 里声明成 `password` 类型,宿主收进 secrets)。
 * 各家的批量上限照搬旧实现实测出来的数:大模型 ≤40 条 / ≤4000 字 / 并发 3,
 * 百度 ≤50 条 / ≤2000 字。这几个数是踩出来的,别凭感觉改。
 */
import { settings, secrets, crypt } from '@linplayer/plugin-sdk'
import type { Engine } from './pipeline'

const LANG_NAME: Record<string, string> = {
  'zh-Hans': '简体中文', 'zh-Hant': '繁體中文', en: 'English', ja: '日本語', ko: '한국어',
}

function systemPrompt(target: string): string {
  const name = LANG_NAME[target] || target
  return 'You are a professional subtitle translator. ' +
    'Translate every item of the input JSON array into ' + name + '. ' +
    'Rules: (1) Return ONLY a JSON array of strings, same length and order as the input. ' +
    '(2) Keep line breaks inside an item as \\n. ' +
    '(3) Do not merge or split items, add numbering, notes, or romanization. ' +
    '(4) Keep proper nouns natural. Output must be valid JSON, nothing else.'
}

/**
 * 从模型回复里抠出 JSON 数组。
 *
 * ☠ **长度对不上一律当失败**,让上层二分重试:模型偶尔会合并两条短句,
 *   放过去的话译文从那一条起整轨错位,而字幕看起来「有内容」,没人会怀疑是这里。
 */
function parseArray(raw: string, expected: number): string[] {
  const a = raw.indexOf('[')
  const b = raw.lastIndexOf(']')
  if (a < 0 || b <= a) throw new Error('模型没回 JSON 数组')
  const arr = JSON.parse(raw.slice(a, b + 1))
  if (!Array.isArray(arr) || arr.length !== expected) {
    throw new Error('模型回了 ' + (Array.isArray(arr) ? arr.length : '?') + ' 条,给的是 ' + expected + ' 条')
  }
  return arr.map((x: any) => String(x))
}

async function postJSON(url: string, body: any, headers: Record<string, string>): Promise<any> {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  const text = await r.text()
  if (!r.ok) throw new Error('HTTP ' + r.status + ':' + text.slice(0, 300))
  try {
    return JSON.parse(text)
  } catch {
    throw new Error('回包不是 JSON:' + text.slice(0, 300))
  }
}

/** OpenAI 兼容 / Anthropic。整批交给模型:有上下文,质量更好也更省请求数。 */
function aiEngine(anthropic: boolean): Engine {
  return {
    maxBatch: 40,
    maxChars: 4000,
    concurrency: 3,
    async translate(texts, _from, to) {
      const key = secrets.get(anthropic ? 'anthropic_key' : 'openai_key')
      if (!key) throw new Error('还没填 API key(在插件设置里)')
      const base = String(settings.get(anthropic ? 'anthropic_base' : 'openai_base') || '').replace(/\/+$/, '')
      if (!base) throw new Error('还没填接口地址(在插件设置里)')
      const model = String(settings.get(anthropic ? 'anthropic_model' : 'openai_model') || '')
      if (!model) throw new Error('还没填模型名(在插件设置里)')

      const user = JSON.stringify(texts)
      let data: any
      let out = ''
      if (anthropic) {
        data = await postJSON(base + '/messages', {
          model, max_tokens: 8192, temperature: 0.2,
          system: systemPrompt(to),
          messages: [{ role: 'user', content: user }],
        }, { 'x-api-key': key, 'anthropic-version': '2023-06-01' })
        out = (((data.content || [])[0] || {}).text) || ''
      } else {
        data = await postJSON(base + '/chat/completions', {
          model, temperature: 0.2,
          messages: [
            { role: 'system', content: systemPrompt(to) },
            { role: 'user', content: user },
          ],
        }, { Authorization: 'Bearer ' + key })
        out = ((((data.choices || [])[0] || {}).message) || {}).content || ''
      }
      if (!out) throw new Error('模型回了空')
      return parseArray(out, texts.length)
    },
  }
}

/** 百度翻译:一次一段、按行回包,所以整批用 `\n` 拼起来发。 */
const baidu: Engine = {
  maxBatch: 50,
  maxChars: 2000,
  concurrency: 1,
  async translate(texts, from, to) {
    const appid = String(settings.get('baidu_appid') || '')
    const key = secrets.get('baidu_key')
    if (!appid || !key) throw new Error('还没填百度翻译的 appid / 密钥')
    const salt = String(Date.now())
    const q = texts.join('\n')
    const sign = crypt.md5(appid + q + salt + key)
    const u = 'https://fanyi-api.baidu.com/api/trans/vip/translate'
    const body = new URLSearchParams({
      q, from: baiduLang(from), to: baiduLang(to), appid, salt, sign,
    }).toString()
    const r = await fetch(u, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
    const data = await r.json()
    if (data.error_code) throw new Error('百度:' + data.error_code + ' ' + (data.error_msg || ''))
    const lines: string[] = (data.trans_result || []).map((x: any) => String(x.dst))
    // ☠ 百度偶发把空行合并,行数就对不齐 —— 交给上层二分重试,别硬对
    if (lines.length !== texts.length) {
      throw new Error('百度回了 ' + lines.length + ' 行,给的是 ' + texts.length + ' 行')
    }
    return lines
  },
}

function baiduLang(code: string): string {
  if (code === 'zh-Hans' || code === 'zh') return 'zh'
  if (code === 'zh-Hant') return 'cht'
  if (code === 'ja') return 'jp'
  if (code === 'ko') return 'kor'
  if (!code || code === 'auto') return 'auto'
  return code
}

export const ENGINES: Record<string, () => Engine> = {
  openai: () => aiEngine(false),
  anthropic: () => aiEngine(true),
  baidu: () => baidu,
}

export function engineOf(id: string): Engine {
  const make = ENGINES[id]
  if (!make) throw new Error('没有这个引擎:' + id)
  return make()
}
