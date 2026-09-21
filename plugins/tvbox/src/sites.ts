// 各类站点的执行(SPEC 17.1「源的执行」):type 0/1 苹果CMS、type 3 drpy / jar / py、type 4 T4。
import { app, assets, crypt, files, html, js, spider, storage, PluginError, type JsContext, type MediaDetail, type MediaItem, type Page, type SpiderHandle } from '@linplayer/plugin-sdk'
import type { SiteCfg } from './config'
import { toCategories, toDetail, toItem, type Vod } from './convert'
import { fetchJson, fetchText, MOBILE_UA } from './net'

export interface PlayRaw {
  url: string
  header?: Record<string, string>
  parse?: number
  jx?: number
}

export interface Driver {
  home(): Promise<{ categories: any[]; recommended?: MediaItem[] }>
  category(tid: string, pg: number, filters: Record<string, string>): Promise<Page<MediaItem>>
  search(wd: string, pg: number): Promise<Page<MediaItem>>
  detail(id: string): Promise<MediaDetail>
  play(flag: string, id: string): Promise<PlayRaw>
}

export interface SourceCtx {
  key: string
  site: SiteCfg
  spiderJar?: string
  hostOverride?: string
  signal?: AbortSignal
}

function nextOf(pg: number, pagecount: any, got: number): string | undefined {
  const pc = Number(pagecount)
  if (Number.isFinite(pc) && pc > 0) return pg < pc ? String(pg + 1) : undefined
  return got > 0 ? String(pg + 1) : undefined
}

function apiOf(s: SourceCtx): string {
  if (!s.hostOverride) return s.site.api
  try {
    const u = new URL(s.site.api)
    const h = new URL(s.hostOverride.includes('://') ? s.hostOverride : 'http://' + s.hostOverride)
    u.protocol = h.protocol
    u.host = h.host
    return u.href
  } catch {
    return s.site.api
  }
}

function timeoutOf(s: SourceCtx): number {
  return s.site.timeout && s.site.timeout > 0 ? s.site.timeout * 1000 : 15000
}

// ---------------------------------------------------------------- 苹果CMS(type 1 JSON / type 0 XML)

class Cms implements Driver {
  constructor(private s: SourceCtx, private xml: boolean) {}

  private async get(params: Record<string, string>): Promise<any> {
    const api = apiOf(this.s)
    const q = new URLSearchParams(params).toString()
    const url = q ? api + (api.includes('?') ? '&' : '?') + q : api
    const o = { timeout: timeoutOf(this.s), headers: this.s.site.header, signal: this.s.signal, cookieJar: this.s.key }
    return this.xml ? parseXml(await fetchText(url, o)) : fetchJson(url, o)
  }

  /* ☠ 苹果 CMS 的**顶级分类里一部片都没有**。
     `class` 是一张平表,顶级的(`type_pid: 0`,电影 / 电视剧 / 综艺 / 动漫)只是壳,
     片子全挂在子分类(动作片 / 喜剧片…)上。照着顶级 id 请求 `ac=videolist&t=1`
     拿回来的是 `list: [], total: 0` —— 用户点「电影」看到的是空白或「加载失败」,
     而那个站其实有几万部。少数站顶级下面直接挂了几部,于是变成「点进去只有 3 条,
     还没有下一页」。
     实测(2026-09-21,用户给的 34 个源里能连上的 22 个):13 个站的 `class` 带
     `type_pid`,把子分类 id 用逗号连起来请求(`t=6,7,8,…`)13 个全部有效,
     0~12 条变成 2440~5306 条。另外 9 个站的 `class` 不给 `type_pid`,
     拿不到父子关系 —— 那 9 个站只能靠用户自己点子分类,所以空分类要说人话,
     见 category() 里那句。 */
  private kidsKey(): string {
    return 'cls:' + this.s.key
  }

  private saveTree(classes: any[] | undefined) {
    const kids: Record<string, string[]> = {}
    for (const c of classes ?? []) {
      if (!c || c.type_pid === undefined || String(c.type_pid) === '0') continue
      const p = String(c.type_pid)
      ;(kids[p] ??= []).push(String(c.type_id))
    }
    storage.set(this.kidsKey(), kids)
  }

  /** 顶级分类 id → 它全部子分类 id 拼成的串;不是顶级分类(或不知道)就原样返回。 */
  private async expandTid(tid: string): Promise<string> {
    let kids = storage.get<Record<string, string[]>>(this.kidsKey())
    if (!kids) {
      // 分类页可能是深链直接进来的,没走过首页。补一次,只补一次
      try {
        this.saveTree((await this.get({})).class)
      } catch {
        storage.set(this.kidsKey(), {})
      }
      kids = storage.get<Record<string, string[]>>(this.kidsKey())
    }
    const k = kids?.[tid]
    return k && k.length ? k.join(',') : tid
  }

  async home() {
    const r = await this.get({})
    const cats = toCategories(r.class, r.filters)
    this.saveTree(r.class)
    let list: Vod[] = r.list ?? []
    // 首页 list 常只有 id 没有图:按 id 补一次详情(和 TVBox 一致)
    if (list.length && !list[0].vod_pic && list[0].vod_id) {
      try {
        const d = await this.get({ ac: 'videolist', ids: list.slice(0, 20).map((v) => String(v.vod_id)).join(',') })
        list = d.list ?? list
      } catch {
        // 补图失败不影响首页:照原样出
      }
    }
    let rec = list.map((v) => toItem(v, apiOf(this.s)))
    // 首页一条推荐都没有的站不少(`list` 空)。空着一屏不如**退回第一个分类** ——
    // 用户要的是「看到片子」,不是「看到一个正确的空页」。只在真空时多打一次。
    // 往下试几个:第一个常常是空的父分类(见 category() 上面那段)
    for (let i = 0; rec.length === 0 && i < Math.min(3, cats.length); i++) {
      try {
        rec = (await this.category(cats[i].id, 1, {})).items
      } catch {
        // 都失败就让首页空着,分类还在,用户能自己点
      }
    }
    return { categories: cats, recommended: rec }
  }

  async category(tid: string, pg: number, filters: Record<string, string>) {
    const t = await this.expandTid(tid)
    const p: Record<string, string> = { ac: 'videolist', t, pg: String(pg) }
    if (Object.keys(filters).length) p.f = JSON.stringify(filters)
    const r = await this.get(p)
    const list: Vod[] = r.list ?? []
    // 空分类**不是错误**,但也不能一声不吭:拿不到父子关系的站(class 不给 type_pid)
    // 上,顶级分类就是这个下场,而用户看到的只是一屏空白。
    if (list.length === 0 && pg === 1 && t === tid) {
      throw new PluginError({ kind: 'notFound', message: '这个分类在站点上是空的 —— 多半是个父分类,片子挂在它下面的子分类里(动作片、喜剧片…),直接点那些' })
    }
    return { items: list.map((v) => toItem(v, apiOf(this.s))), next: nextOf(pg, r.pagecount, list.length) }
  }

  async search(wd: string, pg: number) {
    const r = await this.get({ ac: 'videolist', wd, pg: String(pg) })
    const list: Vod[] = r.list ?? []
    return { items: list.map((v) => toItem(v, apiOf(this.s))), next: nextOf(pg, r.pagecount, list.length) }
  }

  async detail(id: string) {
    const r = await this.get({ ac: 'videolist', ids: id })
    const v = (r.list ?? [])[0]
    if (!v) throw new PluginError({ kind: 'notFound', message: '站点上没有这部片' })
    return toDetail(v, apiOf(this.s))
  }

  async play(_flag: string, id: string) {
    return { url: id }
  }
}

/** 苹果CMS XML(type 0):HTML 解析器会吞掉 CDATA,先把它换成转义文本。 */
function parseXml(text: string): any {
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const src = text.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_, s) => esc(s))
  const doc = html.parse(src)
  const txt = (n: any, sel: string) => (n.querySelector(sel)?.text() ?? '').trim()
  const classes = doc.querySelectorAll('class ty').map((t) => ({ type_id: t.attr('id'), type_name: t.text().trim() }))
  const listNode = doc.querySelector('list')
  const list: Vod[] = doc.querySelectorAll('video').map((v) => {
    const dds = v.querySelectorAll('dd')
    return {
      vod_id: txt(v, 'id'),
      vod_name: txt(v, 'name'),
      vod_pic: txt(v, 'pic'),
      vod_remarks: txt(v, 'note'),
      vod_year: txt(v, 'year'),
      vod_content: txt(v, 'des'),
      vod_actor: txt(v, 'actor'),
      vod_director: txt(v, 'director'),
      vod_area: txt(v, 'area'),
      type_name: txt(v, 'type'),
      vod_play_from: dds.map((d) => d.attr('flag') ?? '').join('$$$'),
      vod_play_url: dds.map((d) => d.text().trim()).join('$$$'),
    }
  })
  return { class: classes, list, pagecount: listNode?.attr('pagecount') }
}

// ---------------------------------------------------------------- catvod JSON(T4 / drpy / jar 共用)

function catvodHome(r: any, base: string) {
  return { categories: toCategories(r.class, r.filters), recommended: (r.list ?? []).map((v: Vod) => toItem(v, base)) }
}

function catvodPage(r: any, pg: number, base: string): Page<MediaItem> {
  const list: Vod[] = r.list ?? []
  return { items: list.map((v) => toItem(v, base)), next: nextOf(pg, r.pagecount, list.length) }
}

function catvodDetail(r: any, base: string): MediaDetail {
  const v = (r.list ?? [])[0]
  if (!v) throw new PluginError({ kind: 'notFound', message: '站点上没有这部片' })
  return toDetail(v, base)
}

function parseCatvod(s: any): any {
  if (typeof s !== 'string') return s ?? {}
  if (!s.trim()) return {}
  try {
    return JSON.parse(s)
  } catch (e) {
    throw new PluginError({ kind: 'parseFailed', message: '源返回的结果不是 JSON', detail: s.slice(0, 200) })
  }
}

// ---------------------------------------------------------------- T4(D532)

class T4 implements Driver {
  constructor(private s: SourceCtx) {}
  private get(p: Record<string, string>) {
    const api = apiOf(this.s)
    const extra = typeof this.s.site.ext === 'string' && this.s.site.ext ? { extend: this.s.site.ext } : {}
    const q = new URLSearchParams({ ...p, ...extra }).toString()
    return fetchJson(api + (api.includes('?') ? '&' : '?') + q, { timeout: timeoutOf(this.s), signal: this.s.signal, cookieJar: this.s.key })
  }
  async home() {
    return catvodHome(await this.get({ filter: 'true' }), '')
  }
  async category(tid: string, pg: number, filters: Record<string, string>) {
    const p: Record<string, string> = { t: tid, pg: String(pg), ac: 'list' }
    if (Object.keys(filters).length) p.ext = crypt.base64Encode(JSON.stringify(filters))
    return catvodPage(await this.get(p), pg, '')
  }
  async search(wd: string, pg: number) {
    return catvodPage(await this.get({ wd, quick: 'false', pg: String(pg) }), pg, '')
  }
  async detail(id: string) {
    return catvodDetail(await this.get({ ac: 'detail', ids: id }), '')
  }
  async play(flag: string, id: string) {
    const r = await this.get({ play: id, flag })
    return { url: r.url, header: r.header, parse: r.parse, jx: r.jx }
  }
}

// ---------------------------------------------------------------- drpy(D530 D531 D316)

const LIBS = ['cheerio.min.js', 'crypto-js.js', 'jsencrypt.js', 'gbk.js', '模板.js']
const ENTRY = `import 'lp://globals.js';\nimport drpy from 'engine://drpy2.js';\nglobalThis.__drpy = drpy;\n`
// esbuild 把 UMD 包成 CJS 后它们不再往全局挂;drpy2 依赖全局 CryptoJS / JSEncrypt,要先于它求值挂上
const GLOBALS = `import CryptoJS from 'lib://crypto-js.js';\nimport JSEncrypt from 'lib://jsencrypt.js';\nglobalThis.CryptoJS = CryptoJS;\nglobalThis.JSEncrypt = JSEncrypt;\n`

const bundles = new Map<string, Promise<string>>()

async function builtinEngine(): Promise<string> {
  return assets.readText('assets/drpy/drpy2.js')
}

async function bundleEngine(engineSrc: string): Promise<string> {
  return js.bundle('lp://entry.js', {
    resolve: async (p: string) => {
      if (p === 'lp://entry.js') return ENTRY
      if (p === 'lp://globals.js') return GLOBALS
      if (p === 'engine://drpy2.js') return engineSrc
      const base = decodeURIComponent(p.slice(p.lastIndexOf('/') + 1))
      if (LIBS.includes(base)) return assets.readText('assets/drpy/lib/' + base)
      return null
    },
  })
}

/** 引擎用配置指定的(按 md5 缓存),拉不到或打包失败再用内置实测版兜底。 */
async function engineFor(api: string): Promise<string> {
  const key = /^https?:/.test(api) ? api : 'builtin'
  let p = bundles.get(key)
  if (!p) {
    p = (async () => {
      if (key !== 'builtin') {
        let src: string | undefined
        const cacheName = 'engine-' + crypt.md5(api).slice(0, 12) + '.js'
        try {
          src = await fetchText(api, { timeout: 20000 })
          await files.cache.write(cacheName, src)
        } catch {
          if (await files.cache.exists(cacheName)) src = await files.cache.readText(cacheName)
        }
        if (src) {
          try {
            return await bundleEngine(src)
          } catch (e) {
            console.warn('配置指定的 drpy 引擎打包失败,改用内置引擎', api, String(e))
          }
        }
      }
      return bundleEngine(await builtinEngine())
    })()
    bundles.set(key, p)
    p.catch(() => bundles.delete(key))
  }
  return p
}

/** 源的 ext 是远程地址时拉取并缓存,拉不到用上次缓存(D533)。 */
function rawExt(ext: unknown): string {
  return typeof ext === 'string' ? ext : ext == null ? '' : JSON.stringify(ext)
}

export async function extText(ext: unknown): Promise<string> {
  if (typeof ext !== 'string') return ext == null ? '' : JSON.stringify(ext)
  if (!/^https?:/.test(ext)) return ext
  const key = 'ext:' + crypt.md5(ext)
  try {
    const t = await fetchText(ext, { timeout: 20000 })
    storage.set(key, t)
    return t
  } catch (e) {
    const cached = storage.get<string>(key)
    if (cached) return cached
    throw e
  }
}

interface PoolEntry {
  ctx: Promise<JsContext>
  used: number
}
const pool = new Map<string, PoolEntry>()

function poolCap(): number {
  return app.formFactor === 'tv' ? 4 : 16
}

/** 每源一个子运行时,按需建;超过上限销毁最久没用的(D316)。 */
async function drpyCtx(s: SourceCtx): Promise<JsContext> {
  const hit = pool.get(s.key)
  if (hit) {
    hit.used = Date.now()
    return hit.ctx
  }
  const ctx = (async () => {
    const code = await engineFor(s.site.api)
    const c = await js.createContext({ withSyncHost: true, cookieJar: s.key, globals: { MOBILE_UA } })
    await c.run(code, 'drpy2.bundle.js')
    await c.call('__drpy.init', await extText(s.site.ext))
    return c
  })()
  pool.set(s.key, { ctx, used: Date.now() })
  ctx.catch(() => pool.delete(s.key))
  while (pool.size > poolCap()) {
    let oldest: string | undefined
    let t = Infinity
    for (const [k, v] of pool) if (v.used < t) (t = v.used), (oldest = k)
    if (!oldest) break
    const e = pool.get(oldest)!
    pool.delete(oldest)
    e.ctx.then((c) => c.dispose()).catch(() => {})
  }
  return ctx
}

export function dropDrpy(key: string) {
  const e = pool.get(key)
  if (e) {
    pool.delete(key)
    e.ctx.then((c) => c.dispose()).catch(() => {})
  }
}

class Drpy implements Driver {
  constructor(private s: SourceCtx) {}
  private async call(fn: string, ...args: any[]): Promise<any> {
    const c = await drpyCtx(this.s)
    try {
      return parseCatvod(await c.call('__drpy.' + fn, ...args))
    } catch (e) {
      if (e instanceof PluginError && e.kind !== 'internal') throw e
      throw new PluginError({ kind: 'parseFailed', message: '规则执行出错', detail: String((e as any)?.detail ?? e) })
    }
  }
  async home() {
    const h = await this.call('home', true)
    let rec: any[] = h.list ?? []
    if (!rec.length) {
      try {
        rec = (await this.call('homeVod')).list ?? []
      } catch {
        // 推荐行拉不到不影响分类
      }
    }
    return { categories: toCategories(h.class, h.filters), recommended: rec.map((v: Vod) => toItem(v)) }
  }
  async category(tid: string, pg: number, filters: Record<string, string>) {
    return catvodPage(await this.call('category', tid, pg, Object.keys(filters).length > 0, filters), pg, '')
  }
  async search(wd: string, pg: number) {
    return catvodPage(await this.call('search', wd, false, pg), pg, '')
  }
  async detail(id: string) {
    return catvodDetail(await this.call('detail', id), '')
  }
  async play(flag: string, id: string) {
    const r = await this.call('play', flag, id, [])
    return { url: r.url, header: r.header, parse: r.parse, jx: r.jx }
  }
}

// ---------------------------------------------------------------- jar / py(D348~D355,宿主 spider 能力)

const spiders = new Map<string, Promise<SpiderHandle>>()

export function spiderKind(site: SiteCfg): 'jar' | 'py' | null {
  if (site.type !== 3) return null
  if (/\.py(\?|$)/i.test(site.api)) return 'py'
  if (/^csp_/i.test(site.api)) return 'jar'
  return null
}

class Spider implements Driver {
  constructor(private s: SourceCtx, private kind: 'jar' | 'py') {}
  private async h(): Promise<SpiderHandle> {
    let p = spiders.get(this.s.key)
    if (!p) {
      const jar = this.s.site.jar || this.s.spiderJar || ''
      const [url, md5] = jar.split(';md5;')
      p = (async () =>
        // ext 原样交给 spider 的 init(TVBox 同款):是 URL 的话由 spider 自己决定拉不拉,宿主代拉会拉错东西
        spider.load({ kind: this.kind, url: this.kind === 'py' ? this.s.site.api : url, md5, api: this.s.site.api, ext: rawExt(this.s.site.ext), sourceKey: this.s.key }))()
      spiders.set(this.s.key, p)
      p.catch(() => spiders.delete(this.s.key))
    }
    return p
  }
  private async call(m: any, ...args: any[]) {
    return parseCatvod(await (await this.h()).call(m, ...args))
  }
  async home() {
    const h = await this.call('homeContent', true)
    let rec = h.list ?? []
    if (!rec.length) rec = (await this.call('homeVideoContent')).list ?? []
    return { categories: toCategories(h.class, h.filters), recommended: rec.map((v: Vod) => toItem(v)) }
  }
  async category(tid: string, pg: number, filters: Record<string, string>) {
    return catvodPage(await this.call('categoryContent', tid, String(pg), Object.keys(filters).length > 0, filters), pg, '')
  }
  async search(wd: string, pg: number) {
    return catvodPage(await this.call('searchContent', wd, false, String(pg)), pg, '')
  }
  async detail(id: string) {
    return catvodDetail(await this.call('detailContent', [id]), '')
  }
  async play(flag: string, id: string) {
    const r = await this.call('playerContent', flag, id, [])
    return { url: r.url, header: typeof r.header === 'string' ? JSON.parse(r.header || '{}') : r.header, parse: r.parse, jx: r.jx }
  }
}

export function driverFor(s: SourceCtx): Driver {
  switch (s.site.type) {
    case 0:
      return new Cms(s, true)
    case 1:
      return new Cms(s, false)
    case 4:
      return new T4(s)
    case 3: {
      const k = spiderKind(s.site)
      if (k) return new Spider(s, k)
      return new Drpy(s)
    }
  }
  throw new PluginError({ kind: 'unsupported', message: `不支持的站点类型 ${s.site.type}` })
}
