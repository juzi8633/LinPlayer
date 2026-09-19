// vod_* → 统一结构(D254 D330 D331 D334)。
import type { Category, FilterDimension, Line, MediaDetail, MediaItem } from '@linplayer/plugin-sdk'

export interface Vod {
  vod_id?: string | number
  vod_name?: string
  vod_pic?: string
  vod_year?: string | number
  vod_remarks?: string
  vod_content?: string
  vod_actor?: string
  vod_director?: string
  vod_area?: string
  vod_lang?: string
  type_name?: string
  vod_play_from?: string
  vod_play_url?: string
  vod_douban_id?: string | number
  vod_douban_score?: string | number
}

function split(s?: string): string[] | undefined {
  if (!s) return undefined
  const out = s.split(/[,,/、|]/).map((x) => x.trim()).filter(Boolean)
  return out.length ? out : undefined
}

function abs(u: string | undefined, base: string): string | undefined {
  if (!u) return undefined
  u = u.trim()
  if (u.startsWith('//')) return 'https:' + u
  if (/^https?:/.test(u) || !base) return u
  try {
    return new URL(u, base).href
  } catch {
    return u
  }
}

/** vod_pic 里常见「地址@Referer=xxx」这种带头写法。 */
function poster(u: string | undefined, base: string) {
  const url = abs(u, base)
  if (!url) return undefined
  const m = /^(.*?)@(Referer|User-Agent|Cookie)=(.*)$/i.exec(url)
  if (m) return { url: m[1], headers: { [m[2]]: m[3] } }
  return { url }
}

export function toItem(v: Vod, base = ''): MediaItem {
  const year = Number(String(v.vod_year ?? '').slice(0, 4))
  const it: MediaItem = {
    id: String(v.vod_id ?? ''),
    kind: 'series',
    title: String(v.vod_name ?? '').trim(),
    year: year > 1800 ? year : undefined,
    poster: poster(v.vod_pic, base),
    remarks: v.vod_remarks || undefined,
    overview: v.vod_content || undefined,
    actors: split(v.vod_actor),
    directors: split(v.vod_director),
    countries: split(v.vod_area),
    genres: split(v.type_name),
  }
  if (v.vod_douban_score && Number(v.vod_douban_score) > 0) it.ratings = [{ source: '豆瓣', value: Number(v.vod_douban_score), max: 10 }]
  if (v.vod_douban_id) it.externalIds = { douban: String(v.vod_douban_id) }
  return it
}

/** vod_play_from / vod_play_url(`$$$` 分线路,`#` 分集,`名$地址`)→ 线路。线路 id 就是站点给的线路名(播放时回传做 flag)。 */
export function toLines(v: Vod): Line[] {
  const froms = String(v.vod_play_from ?? '').split('$$$')
  const urls = String(v.vod_play_url ?? '').split('$$$')
  const lines: Line[] = []
  froms.forEach((from, i) => {
    const eps = (urls[i] ?? '')
      .split('#')
      .map((s) => s.trim())
      .filter(Boolean)
      .map((seg, j) => {
        const k = seg.indexOf('$')
        const name = k >= 0 ? seg.slice(0, k) : `第${j + 1}集`
        const url = k >= 0 ? seg.slice(k + 1) : seg
        return { id: url, name: name || `第${j + 1}集` }
      })
      .filter((e) => e.id) // 「第1集$」这种没有地址的集点了也播不了,不列出来
    if (eps.length) lines.push({ id: from || `线路${i + 1}`, name: from || `线路${i + 1}`, episodes: eps })
  })
  return lines
}

export function toDetail(v: Vod, base = ''): MediaDetail {
  const d: MediaDetail = { ...toItem(v, base), lines: toLines(v) }
  const n = Math.max(0, ...(d.lines ?? []).map((l) => l.episodes.length))
  d.episodeCount = n
  d.kind = n <= 1 ? 'movie' : 'series'
  return d
}

/** TVBox filters:{分类id: [{key, name, value:[{n, v}]}]};全是单选(D334)。 */
export function toFilters(raw: any): FilterDimension[] | undefined {
  if (!Array.isArray(raw)) return undefined
  return raw.map((f: any) => ({
    key: String(f.key),
    name: String(f.name ?? f.key),
    multi: false,
    options: (f.value ?? []).map((o: any) => ({ name: String(o.n ?? o.name ?? ''), value: String(o.v ?? o.value ?? '') })),
  }))
}

export function toCategories(classes: any[] | undefined, filters: any): Category[] {
  return (classes ?? []).map((c: any) => {
    const id = String(c.type_id ?? c.id ?? '')
    return { id, name: String(c.type_name ?? c.name ?? id), filters: filters ? toFilters(filters[id]) : undefined }
  })
}
