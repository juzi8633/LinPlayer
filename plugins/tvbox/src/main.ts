// linplayer/tvbox:TVBox 配置订阅 / drpy 规则 → 数据源(SPEC 17.1)。
import {
  definePlugin, PluginError, registry, settings, sources, spider, storage, ui,
  type Json, type SourceCallContext, type SourceDraft, type SourceInfo,
} from '@linplayer/plugin-sdk'
import { loadConfig, reposOf, shortHash, type SiteCfg, type TvConfig } from './config'
import { dropDrpy, driverFor, spiderKind, type SourceCtx } from './sites'
import { filterM3u8, resolvePlay, type SubInfo } from './parse'

const SERVER_TYPE_SUB = 'subscription'
const SERVER_TYPE_RULE = 'drpy-rule'
const RULE_GROUP = 'drpy-rules'

interface SrcConfig {
  sub?: string // 所属订阅 id(= 分组)
  site: SiteCfg
  spider?: string // 配置级 spider(jar 源没写 jar 时用它)
}

interface SubRecord extends SubInfo {
  known: string[] // 见过的站点 key:用户添加时没勾的不再自动加回来,新出现的自动加入(D231)
  at: number // 上次成功刷新
  repoUrl?: string // 来自哪个多仓地址
}

// ---------------------------------------------------------------- 订阅存储(插件 KV)

function subs(): string[] {
  return storage.get<string[]>('subs') ?? []
}

function getSub(id: string | undefined): SubRecord | undefined {
  return id ? storage.get<SubRecord>('sub:' + id) : undefined
}

function putSub(s: SubRecord) {
  storage.set('sub:' + s.id, s)
  const list = subs()
  if (!list.includes(s.id)) storage.set('subs', [...list, s.id])
}

function subInfoOf(cfg: TvConfig, id: string, name: string, url: string): SubRecord {
  return {
    id, name, url, known: [], at: Date.now(),
    parses: (cfg.parses ?? []).filter((p) => p && p.url),
    flags: cfg.flags ?? [],
    rules: (cfg.rules ?? []).map((r) => ({ hosts: r.hosts ?? (r.host ? [r.host] : []), regex: r.regex ?? r.rule ?? [] })),
    ads: cfg.ads ?? [],
  }
}

function sourceId(subId: string, key: string): string {
  const k = key.replace(/[^A-Za-z0-9._-]/g, '')
  return subId + '.' + (k.length >= 2 ? k.slice(0, 40) : shortHash(key))
}

async function unavailable(site: SiteCfg): Promise<string | undefined> {
  const k = spiderKind(site)
  if (k) {
    const s = await spider.supported(k)
    return s.ok ? undefined : s.reason ?? '本设备不支持这种源'
  }
  if (![0, 1, 3, 4].includes(site.type)) return `不支持的站点类型 ${site.type}`
  return undefined
}

async function draftsOf(cfg: TvConfig, sub: SubRecord): Promise<SourceDraft[]> {
  const out: SourceDraft[] = []
  const seen = new Set<string>()
  for (const site of cfg.sites ?? []) {
    if (!site || !site.key || !site.api) continue
    const id = sourceId(sub.id, site.key)
    if (seen.has(id)) continue
    seen.add(id)
    const conf: SrcConfig = { sub: sub.id, site, spider: cfg.spider }
    out.push({
      id, name: site.name || site.key, config: conf as unknown as Json, group: sub.id, groupName: sub.name,
      aggregateDefault: Number(site.searchable) === 1, unavailableReason: await unavailable(site),
    } as SourceDraft & { groupName: string })
  }
  return out
}

/** 配置里的直播源交给直播插件(D275),不在这里当数据源。 */
function publishLives(cfg: TvConfig, sub: SubRecord) {
  ;(cfg.lives ?? []).forEach((l, i) => {
    if (l && l.url) registry.put('live.channels', `${sub.id}:${i}`, { kind: 'url', name: l.name || sub.name, url: l.url })
  })
}

// ---------------------------------------------------------------- 刷新(D46 D230 D231 D345)

async function refreshSub(id: string): Promise<{ added: number; removed: number; updated: number }> {
  const sub = getSub(id)
  if (!sub) throw new PluginError({ kind: 'notFound', message: '没有这个订阅' })
  let cfg: TvConfig
  try {
    cfg = (await loadConfig(sub.url)).cfg
  } catch (e) {
    // 刷新失败沿用上次成功的副本,并提示原因(D345)
    ui.toast(`「${sub.name}」订阅刷新失败:${(e as any)?.message ?? e},继续使用上次的内容`)
    throw e
  }
  const next: SubRecord = { ...subInfoOf(cfg, sub.id, sub.name, sub.url), known: sub.known, repoUrl: sub.repoUrl }
  const drafts = await draftsOf(cfg, next)
  const mine = sources.list().filter((s) => s.group === sub.id)
  const have = new Map(mine.map((s) => [s.id, s] as const))
  let added = 0,
    removed = 0,
    updated = 0
  for (const d of drafts) {
    if (have.has(d.id)) {
      await sources.update(d.id, d)
      updated++
    } else if (!sub.known.includes(d.id)) {
      await sources.add(d, SERVER_TYPE_SUB) // 新出现的源自动加入(D231)
      added++
    }
  }
  const alive = new Set(drafts.map((d) => d.id))
  for (const s of mine) {
    if (!alive.has(s.id)) {
      await sources.remove(s.id) // 记录由宿主保留(D230)
      dropDrpy(s.key)
      removed++
    }
  }
  next.known = [...new Set([...sub.known.filter((k) => alive.has(k)), ...drafts.map((d) => d.id)])]
  putSub(next)
  publishLives(cfg, next)
  return { added, removed, updated }
}

async function refreshAll(onlyStale: boolean) {
  const hours = Number(settings.get('refreshHours') ?? 24)
  for (const id of subs()) {
    const s = getSub(id)
    if (!s) continue
    if (onlyStale && Date.now() - s.at < hours * 3600e3 && refreshedThisRun.has(id)) continue
    try {
      await refreshSub(id)
    } catch {
      // refreshSub 已经 toast 过原因
    }
    refreshedThisRun.add(id)
  }
}
const refreshedThisRun = new Set<string>()

// ---------------------------------------------------------------- 源 → 驱动

function srcCtx(ctx: SourceCallContext): { s: SourceCtx; sub?: SubRecord } {
  const conf = ctx.source.config as unknown as SrcConfig
  if (!conf || !conf.site) throw new PluginError({ kind: 'invalid', message: '这个源的配置损坏了,请删除后重新添加' })
  return { s: { key: ctx.source.key, site: conf.site, spiderJar: conf.spider, hostOverride: ctx.source.hostOverride, signal: ctx.signal }, sub: getSub(conf.sub) }
}

function firstValues(f: Record<string, string[]>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const k of Object.keys(f ?? {})) if (f[k]?.length) out[k] = f[k][0]
  return out
}

function pageNo(cursor?: string): number {
  const n = Number(cursor ?? 1)
  return Number.isFinite(n) && n >= 1 ? n : 1
}

function srcBySubscription(sourceKey: string): SourceInfo | undefined {
  return sources.list().find((s) => s.key === sourceKey)
}

export default definePlugin({
  activate() {
    // 默认每次启动刷新一次 + 运行中每 N 小时(D46);只在应用运行时,不走后台(D504)
    setTimeout(() => void refreshAll(false), 5000)
    const hours = Number(settings.get('refreshHours') ?? 24)
    setInterval(() => void refreshAll(true), Math.max(1, hours) * 3600e3)
  },

  dataSource: {
    async createSources(serverType, form) {
      if (serverType === SERVER_TYPE_RULE) {
        const rule = String(form.rule ?? '').trim()
        if (!rule) throw new PluginError({ kind: 'invalid', message: '请填写 drpy 规则文本或地址' })
        const title = /title\s*:\s*['"]([^'"]+)['"]/.exec(rule)?.[1] ?? '自定义规则'
        const site: SiteCfg = { key: 'rule', name: title, type: 3, api: 'builtin', ext: rule, searchable: 1 }
        return { sources: [{ id: 'rule-' + shortHash(rule), name: title, config: { site } as unknown as Json, group: RULE_GROUP, groupName: 'drpy 规则', aggregateDefault: true } as SourceDraft] }
      }
      const url = String(form.url ?? '').trim()
      if (!url) throw new PluginError({ kind: 'invalid', message: '请填写配置地址' })
      const { cfg } = await loadConfig(url)
      const repos = reposOf(cfg)
      const chosen = (form.$repos as string[] | undefined) ?? undefined
      if (repos && !chosen) {
        // 多仓:先列出各仓让用户勾(D346);多仓地址本身也记住,仓列表变了下次刷新时提示
        storage.set('repos:' + shortHash(url), repos)
        return { sources: [], repos: repos.map((r) => ({ id: r.id, name: r.name })) }
      }
      const targets = repos ? repos.filter((r) => chosen!.includes(r.id)) : [{ id: shortHash(url), name: '', url }]
      const all: SourceDraft[] = []
      // 多仓里一个仓挂了(403 / 地址失效 / 格式认不出)只跳过它,别的仓照常加;全挂才报错
      const failed: string[] = []
      let firstErr: unknown = null
      for (const t of targets) {
        let c = cfg
        if (repos) {
          try {
            c = (await loadConfig(t.url)).cfg
          } catch (e) {
            failed.push(t.name || t.id)
            firstErr ??= e
            continue
          }
        }
        const name = t.name || (() => { try { return new URL(t.url.split(';pk;')[0]).hostname } catch { return 'TVBox 订阅' } })()
        const sub = subInfoOf(c, 'tv' + shortHash(t.url), name, t.url)
        if (repos) sub.repoUrl = url
        const drafts = await draftsOf(c, sub)
        sub.known = drafts.map((d) => d.id)
        putSub(sub)
        publishLives(c, sub)
        all.push(...drafts)
      }
      if (failed.length === targets.length && firstErr) throw firstErr
      if (failed.length > 0) ui.toast(`这些仓读不到,已跳过:${failed.join('、')}`, { tone: 'warn' })
      return { sources: all }
    },

    async home(ctx) {
      const { s } = srcCtx(ctx)
      const h = await driverFor(s).home()
      return { categories: h.categories, recommended: h.recommended }
    },

    async category(req, ctx) {
      const { s } = srcCtx(ctx)
      const pg = pageNo(req.cursor)
      return driverFor(s).category(req.categoryId, pg, firstValues(req.filters))
    },

    async search(req, ctx) {
      const { s } = srcCtx(ctx)
      const pg = pageNo(req.cursor)
      const p = await driverFor(s).search(req.keyword, pg)
      if (!p.items.length && pg === 1) return { items: [] }
      return p
    },

    async detail(id, ctx) {
      const { s } = srcCtx(ctx)
      const d = await driverFor(s).detail(id)
      if (!d.lines?.length) throw new PluginError({ kind: 'notFound', message: '这部片没有可播放的线路' })
      return d
    },

    async play(req, ctx) {
      const { s, sub } = srcCtx(ctx)
      const raw = await driverFor(s).play(req.lineId, req.episodeId)
      return resolvePlay(raw, req.lineId, sub, ctx.signal)
    },
  },

  m3u8Filters: {
    ads(m3u8) {
      const src = m3u8.source ? srcBySubscription(m3u8.source) : undefined
      const conf = src?.config as unknown as SrcConfig | undefined
      return filterM3u8(m3u8.text, m3u8.url, getSub(conf?.sub))
    },
  },

  commands: {
    async refresh(args) {
      const key = String(args.server_id ?? '')
      const src = key ? srcBySubscription(key) : undefined
      const group = String(args.group ?? src?.group ?? '')
      if (!group || group === RULE_GROUP) {
        await refreshAll(false)
        return
      }
      const r = await refreshSub(group)
      ui.toast(`订阅已刷新:新增 ${r.added} 个源,移除 ${r.removed} 个`)
    },
    async editUrl(args) {
      const src = srcBySubscription(String(args.server_id ?? ''))
      const sub = getSub(src?.group)
      const url = String(args.url ?? '').trim()
      if (!sub) throw new PluginError({ kind: 'notFound', message: '这个源不属于任何订阅' })
      if (!url) throw new PluginError({ kind: 'invalid', message: '请填写新的订阅地址' })
      await loadConfig(url) // 新地址先验一遍,拉不到就不换
      putSub({ ...sub, url })
      await refreshSub(sub.id)
    },
    async reselect(args) {
      // 重新勾选:把订阅里全部源连同「是否已添加」交给宿主的勾选界面
      const src = srcBySubscription(String(args.server_id ?? ''))
      const sub = getSub(src?.group)
      if (!sub) throw new PluginError({ kind: 'notFound', message: '这个源不属于任何订阅' })
      const { cfg } = await loadConfig(sub.url)
      return { plugin_id: 'linplayer/tvbox', type_id: SERVER_TYPE_SUB, sources: await draftsOf(cfg, sub) } as unknown as void
    },
    parsers(args) {
      const src = srcBySubscription(String(args.server_id ?? ''))
      const sub = getSub(src?.group)
      return (sub ? sub.parses.map((p) => ({ name: p.name, type: p.type })) : []) as unknown as void
    },
    setParserOrder(args) {
      const src = srcBySubscription(String(args.server_id ?? ''))
      if (src?.group && Array.isArray(args.order)) storage.set('parserOrder:' + src.group, args.order as string[])
    },
  },
})
