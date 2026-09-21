/**
 * 从 `plugins/<名字>/manifest.json` 生成官方市场索引 `plugin-repo/registry/index.json`。
 *
 * ☠ 条目里的**每个数都要是真的**:体积是 `lp pack` 出来那个文件的字节数,
 *   贡献点是从 manifest 读的。编一个数出来的下场是用户点下载拿到一个长度对不上的包,
 *   而客户端只会说「下载失败」。
 *
 * ☠ `downloads` / `stars` / `readme` **不在这里填** —— 它们由官方仓库的定时任务
 *   抓取回写(D240 D481)。在这儿写死等于给一个永远不动的假数字。
 *
 *   node scripts/gen-registry.mjs            # 生成(包要先 pack 好)
 *   node scripts/gen-registry.mjs --check    # 只校验,不写
 */
import { readFileSync, writeFileSync, statSync, existsSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const pluginsDir = join(root, 'plugins')
const packDir = join(root, 'build', 'lpplugin')
const out = join(root, 'plugin-repo', 'registry', 'index.json')
const checkOnly = process.argv.includes('--check')

// 包发到官方仓库的 Releases,index 里的地址指向**官方副本**(SPEC 15.2 第 4 条)——
// 作者删仓 / 换包都不影响已上架的版本。仓库地址从环境变量来,不写死。
//
// 全部插件共用**一个** Release(D572)。一个插件一个 Release 的话,九个插件就是
// 九条 Release,往后每发一版再加一条,Release 页很快变成一堵墙,而用户在那页上
// 找的是「应用的新版本」。资产名带版本号,所以历史版本照样各有各的地址(D450)。
// ☠ 没给 slug 就**直接退**,不要退回占位符:占位符会原样写进 index.json,
//   而那是客户端「插件商店」读的同一份文件 —— 九条下载地址全指向一个不存在的
//   OWNER/REPO,生成还一行错都不报。2026-09-21 顺手跑了一次 `node scripts/gen-registry.mjs`
//   就这么把整份索引写坏了,靠 git diff 才看出来。
const REPO = process.env.PLUGIN_REPO_SLUG
if (!REPO) {
  console.error('没给 PLUGIN_REPO_SLUG(形如 owner/repo)—— 索引里的下载地址要靠它,不能用占位符。')
  process.exit(1)
}
const TAG = process.env.PLUGIN_RELEASE_TAG || 'plugins'
const assetUrl = (name, ver) =>
  `https://github.com/${REPO}/releases/download/${TAG}/${name}-${ver}.lpplugin`

/** manifest 的 contributes → 贡献点摘要(D79 D239)。CI 按它自动分类。 */
function contribKeys(c) {
  if (!c) return []
  const out = []
  for (const [k, v] of Object.entries(c)) {
    if (v === undefined || v === null) continue
    if (Array.isArray(v) && v.length === 0) continue
    out.push(k)
  }
  return out.sort()
}

/** 贡献点 → 分类。schema 的 categories 只收这几个值。 */
function categories(keys, id) {
  const cats = new Set()
  if (keys.includes('dataSource')) cats.add('dataSource')
  if (keys.includes('theme')) cats.add('theme')
  if (keys.includes('playerOverlays') || keys.includes('playerPanels') || keys.includes('osd')) cats.add('player')
  if (id.includes('subtitle') || id.includes('danmaku')) cats.add('danmakuSubtitle')
  if (id.includes('live')) cats.add('live')
  if (keys.includes('pages') && cats.size === 0) cats.add('tool')
  if (cats.size === 0) cats.add('tool')
  return [...cats].sort()
}

// 沿用上一份索引里的日期:`addedAt` 是「这个插件什么时候上架的」,
// `released` 是「这个版本什么时候发的」—— 每次重新生成都盖成当天的话,
// 两个日期就都变成了「上次跑生成器的时间」,而且每发一版九条全变,
// 真正改了什么全淹在 diff 里。版本号变了才给新的 released。
const prev = new Map()
if (existsSync(out)) {
  try {
    for (const p of JSON.parse(readFileSync(out, 'utf8')).plugins ?? []) prev.set(p.id, p)
  } catch { /* 上一份坏了就当没有,重新生成一份干净的 */ }
}

const entries = []
const problems = []

for (const name of readdirSync(pluginsDir).sort()) {
  const dir = join(pluginsDir, name)
  const mf = join(dir, 'manifest.json')
  if (!existsSync(mf)) continue
  const m = JSON.parse(readFileSync(mf, 'utf8'))

  const pkg = join(packDir, `${name}.lpplugin`)
  if (!existsSync(pkg)) {
    problems.push(`${name}:还没 pack(build/lpplugin/${name}.lpplugin 不在)`)
    continue
  }
  const size = statSync(pkg).size

  const keys = contribKeys(m.contributes)
  const e = {
    id: m.id,
    name: m.name,
    description: m.description,
    author: m.id.split('/')[0],
    repository: process.env.MAIN_REPO_URL || undefined,
    official: true,
    addedAt: prev.get(m.id)?.addedAt ?? new Date().toISOString(),
    contributes: keys,
    categories: categories(keys, m.id),
    versions: [{
      version: m.version,
      url: assetUrl(name, m.version),
      size,
      minAppVersion: m.minAppVersion,
      released: prev.get(m.id)?.versions?.find((v) => v.version === m.version)?.released ?? new Date().toISOString(),
      platforms: m.platforms,
    }],
  }
  if (m.lan) e.lan = true
  if (m.paid) e.paid = true
  if (m.requires) e.requires = m.requires
  // undefined 的键不进 JSON:schema 是 additionalProperties:false,写 null 会红
  entries.push(JSON.parse(JSON.stringify(e)))
}

if (problems.length) {
  console.error('生成不了,先把这些解决掉:\n  ' + problems.join('\n  '))
  process.exit(1)
}

const index = {
  $schema: '../packages/sdk/schema/index.schema.json',
  schema: 1,
  name: 'LinPlayer 官方插件',
  updated: new Date().toISOString(),
  plugins: entries,
}

if (checkOnly) {
  console.log(`校验通过:${entries.length} 条,总计 ${entries.reduce((a, e) => a + e.versions[0].size, 0)} 字节`)
  process.exit(0)
}
writeFileSync(out, JSON.stringify(index, null, 2) + '\n')
console.log(`✓ ${entries.length} 条 → ${out}`)
for (const e of entries) console.log(`   ${e.id}  ${e.versions[0].version}  ${e.versions[0].size} 字节`)
