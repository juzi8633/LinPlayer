// 最小数据源示例:苹果CMS type 1 JSON 接口 → 统一结构。
// 只用于核对 API 草稿能否写出一个真实插件(docs/plugin-system/COVERAGE.md 的编译检查用它)。
import {
  definePlugin, PluginError, storage, settings, sources,
  type MediaItem, type MediaDetail, type Page, type SourceCallContext, type Line,
} from '@linplayer/plugin-sdk'

interface CmsVod {
  vod_id: number | string
  vod_name: string
  vod_pic?: string
  vod_year?: string
  vod_remarks?: string
  vod_content?: string
  vod_play_from?: string
  vod_play_url?: string
}

async function cms(ctx: SourceCallContext, query: Record<string, string>): Promise<{ list: CmsVod[]; pagecount?: number }> {
  const cfg = ctx.source.config as { api: string }
  const base = ctx.source.hostOverride ?? cfg.api
  const url = base + '?' + new URLSearchParams(query).toString()
  const res = await fetch(url, { signal: ctx.signal })
  if (res.status === 429) {
    throw new PluginError({ kind: 'rateLimited', message: '请求太频繁', retryAfter: Number(res.headers.get('retry-after') ?? 30) })
  }
  if (res.status >= 500) throw new PluginError({ kind: 'siteDown', message: '站点暂时不可用' })
  if (!res.ok) throw new PluginError({ kind: 'network', message: `站点返回 ${res.status}` })
  return res.json()
}

function toItem(v: CmsVod): MediaItem {
  return {
    id: String(v.vod_id),
    kind: 'series',
    title: v.vod_name,
    year: v.vod_year ? Number(v.vod_year) : undefined,
    poster: v.vod_pic ? { url: v.vod_pic } : undefined,
    remarks: v.vod_remarks,
    overview: v.vod_content,
  }
}

function toLines(v: CmsVod): Line[] {
  const froms = (v.vod_play_from ?? '').split('$$$')
  const urls = (v.vod_play_url ?? '').split('$$$')
  return froms.map((name, i) => ({
    id: String(i),
    name,
    episodes: (urls[i] ?? '').split('#').filter(Boolean).map((seg, j) => {
      const [epName, epUrl] = seg.split('$')
      return { id: epUrl ?? seg, name: epName ?? `第${j + 1}集` }
    }),
  }))
}

function cached<T>(key: string, compute: () => Promise<T>): Promise<T> {
  const minutes = settings.get<number>('cacheMinutes') ?? 10
  const hit = storage.get<{ at: number; v: T }>(key)
  if (hit && Date.now() - hit.at < minutes * 60_000) return Promise.resolve(hit.v)
  return compute().then((v) => {
    storage.set(key, { at: Date.now(), v })
    return v
  })
}

export default definePlugin({
  dataSource: {
    async createSources(_type, form) {
      const api = String(form.api ?? '').trim()
      if (!api) throw new PluginError({ kind: 'invalid', message: '请填写接口地址' })
      return { sources: [{ id: 'cms-' + api.length.toString(36), name: String(form.name || '苹果CMS'), config: { api } }] }
    },
    async home(ctx) {
      return cached(`home:${ctx.source.id}`, async () => {
        const r = await cms(ctx, { ac: 'list' })
        return { categories: [], recommended: r.list.slice(0, 20).map(toItem) }
      })
    },
    async category(req, ctx): Promise<Page<MediaItem>> {
      const pg = Number(req.cursor ?? 1)
      const r = await cms(ctx, { ac: 'detail', t: req.categoryId, pg: String(pg) })
      return { items: r.list.map(toItem), next: r.pagecount && pg < r.pagecount ? String(pg + 1) : undefined }
    },
    async search(req, ctx) {
      const pg = Number(req.cursor ?? 1)
      const r = await cms(ctx, { ac: 'detail', wd: req.keyword, pg: String(pg) })
      return { items: r.list.map(toItem), next: r.list.length ? String(pg + 1) : undefined }
    },
    async detail(id, ctx): Promise<MediaDetail> {
      const r = await cms(ctx, { ac: 'detail', ids: id })
      const v = r.list[0]
      if (!v) throw new PluginError({ kind: 'notFound', message: '没找到这部片' })
      return { ...toItem(v), lines: toLines(v) }
    },
    async play(req) {
      return { url: req.episodeId }
    },
  },
  homeSections: {
    async latest() {
      return []
    },
  },
  commands: {
    'clear-cache'() {
      for (const k of storage.keys('home:')) storage.remove(k)
    },
  },
  settingActions: {
    clear() {
      for (const k of storage.keys('home:')) storage.remove(k)
    },
  },
  activate() {
    // 运行期可增删源(订阅类插件用):
    void sources.list()
  },
})
