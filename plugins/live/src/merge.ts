/**
 * 同名频道合并(SPEC 17.2,D465 D466)。
 *
 * ☠ 归一化**保留清晰度后缀**:把「CCTV1 4K」和「CCTV1」合成一个,
 *   用户点开只能拿到其中一路,而他明明看到过 4K 那一条 —— 这是 D466 的原话。
 */

import type { Channel } from './m3u'

/** 清晰度后缀:命中就**留着**,它是频道身份的一部分,不是噪音。 */
const QUALITY = ['8k', '4k', '2k', '1080p', '720p', 'uhd', 'fhd', 'hd', 'sd', '高清', '超清', '蓝光', '标清', '流畅']

/**
 * 归一化频道名:去掉装饰(空格、`-`、`_`、全角括号里的线路号),
 * 保留清晰度后缀,`CCTV-1` 与 `CCTV1` 视为同一个。
 */
export function normalizeName(raw: string): string {
  let s = (raw || '').trim().toLowerCase()
  s = s.replace(/[【】\[\]()（）]/g, ' ')       // 方括号圆括号里的线路号当分隔
  s = s.replace(/线路\s*\d+|源\s*\d+|备\s*\d+/g, ' ')
  s = s.replace(/[-_·・\s]+/g, '')
  const found = QUALITY.find((q) => s.endsWith(q))
  if (found) {
    const head = s.slice(0, s.length - found.length)
    return head + '@' + found                            // 后缀留着,但和主名分开
  }
  return s
}

/**
 * 合并同名频道:地址按出现顺序进「源 1/2/3」,去重。
 * 台标 / tvg-id / 回看模板取**第一个有值的**,分组取第一次出现的那个。
 */
export function mergeChannels(list: Channel[]): Channel[] {
  const byKey = new Map<string, Channel>()
  const out: Channel[] = []
  for (const c of list) {
    const key = normalizeName(c.name)
    const hit = byKey.get(key)
    if (!hit) {
      const copy: Channel = { ...c, urls: c.urls.slice() }
      byKey.set(key, copy)
      out.push(copy)
      continue
    }
    for (const u of c.urls) {
      if (!hit.urls.some((x) => x.url === u.url)) hit.urls.push(u)
    }
    if (!hit.logo && c.logo) hit.logo = c.logo
    if (!hit.tvgId && c.tvgId) hit.tvgId = c.tvgId
    if (!hit.catchup && c.catchup) hit.catchup = c.catchup
    if (!hit.number && c.number) hit.number = c.number
  }
  return out
}

/** 分组视图:保留源里的分组顺序,组内保留频道顺序。 */
export function groupOf(list: Channel[]): { name: string; channels: Channel[] }[] {
  const order: string[] = []
  const map = new Map<string, Channel[]>()
  for (const c of list) {
    if (!map.has(c.group)) {
      map.set(c.group, [])
      order.push(c.group)
    }
    map.get(c.group)!.push(c)
  }
  return order.map((name) => ({ name, channels: map.get(name)! }))
}
