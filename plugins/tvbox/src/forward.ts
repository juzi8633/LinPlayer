// ForwardWidget 的小组件清单(`.fwd`)→ 站点表。
//
// 那是**另一个播放器的插件格式**,不是 TVBox 配置:一份清单列若干个小组件,
// 每个小组件是一段独立的 JS,由它自己的运行时(WidgetMetadata + modules)跑。
// 我们不跑那套运行时 —— 但这类清单里绝大多数小组件干的事只有一件:
// 包一个苹果 CMS 采集站。站点地址就明文写在脚本开头的 RESOURCE_SITES 里,
// 抠出来就是 `{name, type: 1, api}`,和 TVBox 的 type 1 站点一模一样。
//
// ★ 抠不出来的小组件**跳过不报错**:一份清单里混进一个真用了 Forward API 的
//   小组件是常态,为它整份导入失败不值当。一个都抠不出来才报。
import { PluginError } from '@linplayer/plugin-sdk'
import type { SiteCfg, TvConfig } from './config'
import { fetchText } from './net'

export interface ForwardManifest {
  title?: string
  widgets?: { id?: string; title?: string; url?: string }[]
}

/** 是小组件清单而不是 TVBox 配置。有 sites 的按 TVBox 走 —— 那才是我们的主业。 */
export function isForward(cfg: TvConfig): boolean {
  const w = (cfg as unknown as ForwardManifest).widgets
  return Array.isArray(w) && w.length > 0 && !cfg.sites?.length
}

// 脚本开头的 `const RESOURCE_SITES = \`名字,地址\`` —— 一行一个站。
const SITES_BLOCK = /RESOURCE_SITES\s*=\s*`([^`]*)`/

function sitesOf(js: string, fallbackName: string): SiteCfg[] {
  const block = SITES_BLOCK.exec(js)?.[1]
  if (!block) return []
  const out: SiteCfg[] = []
  for (const line of block.split('\n')) {
    const i = line.indexOf(',')
    if (i < 0) continue
    const name = line.slice(0, i).trim()
    const api = line.slice(i + 1).trim()
    if (!/^https?:\/\//.test(api)) continue
    out.push({ key: '', name: name || fallbackName, type: 1, api, searchable: 1, quickSearch: 1 })
  }
  return out
}

export async function forwardToSites(m: ForwardManifest): Promise<TvConfig> {
  const list = (m.widgets ?? []).filter((w) => w && w.url)
  const sites: SiteCfg[] = []
  // 一次 6 个:清单动辄几十个小组件,全并发对源站不礼貌,一个个来又太慢。
  for (let i = 0; i < list.length; i += 6) {
    const got = await Promise.all(list.slice(i, i + 6).map(async (w) => {
      try {
        // 过一遍 URL 解析:清单里的地址是别人写的,相对路径 / 缺协议的直接在这里抛,
        // 比拼出一个奇怪的地址去请求好。路径里带中文很常见,但**不用自己转义** ——
        // 实测(fakevod 的 /widget/一.js)宿主发请求时已经做了百分号编码。
        return sitesOf(await fetchText(new URL(w.url!).href, { timeout: 20000 }), w.title || w.id || '')
      } catch {
        return [] // 单个小组件拉不到就少一个站,不该让整份清单导入失败
      }
    }))
    for (const s of got) sites.push(...s)
  }
  if (sites.length === 0) {
    throw new PluginError({
      kind: 'parseFailed',
      message: `这是 ForwardWidget 的小组件清单(${list.length} 个小组件),不是 TVBox 配置 —— 而且里面没有能转成采集站的地址`,
    })
  }
  return { sites }
}
