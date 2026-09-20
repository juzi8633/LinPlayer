/**
 * 校验一条上架 PR(SPEC 15.3),并判断能不能自动合并(15.2 第 3 条)。
 *
 * 用法:node tools/validate-index.mjs --base <旧 index> --head <新 index>
 *                                    --author <PR 提交者> --out <判决 json>
 *
 * ☠ **只读数据,不执行 PR 带来的任何东西**。这个脚本跑在 pull_request_target 里,
 *   手上有仓库的写权限 —— 跑一行来自 PR 的代码就等于把写权限交给任意提交者。
 *   包要不要真装一遍由 `lp check` 做,它在容器里跑,且只解压不执行。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? [...acc, [v.slice(2), a[i + 1]]] : acc), []),
)

const fail = []
const note = (m) => fail.push(m)

const base = JSON.parse(readFileSync(args.base, 'utf8'))
let head
try {
  head = JSON.parse(readFileSync(args.head, 'utf8'))
} catch (e) {
  finish('index.json 不是合法 JSON:' + e.message)
}

/* ☠ 同一个 id 放两条时,`new Map` 是**后来者覆盖**,而宿主装包那一侧
     (`core/plugin/market.go`)是**先到先得** —— 校验看的是后一条、
     用户装的是前一条,脏条目放前面就能绕过全部判据。所以重复 id 直接拒。 */
function byId(list, hardOnDup) {
  const m = new Map()
  for (const p of list.plugins || []) {
    if (m.has(p.id) && hardOnDup) {
      finish('index.json 里 ' + p.id + ' 出现了不止一条 —— 校验看后一条、宿主装前一条,脏条目放前面就能绕过全部判据')
    }
    m.set(p.id, p)
  }
  return m
}
const b = byId(base, false)
const h = byId(head, true)

// 1. 只许动一条,而且是自己的
const changed = []
for (const [id, p] of h) {
  const old = b.get(id)
  if (!old || JSON.stringify(old) !== JSON.stringify(p)) changed.push(id)
}
for (const id of b.keys()) if (!h.has(id)) changed.push(id + '(被删了)')

if (changed.length === 0) finish('这条 PR 没有改动任何条目')
if (changed.length > 1) note('一次改了 ' + changed.length + ' 条:' + changed.join(', '))

const id = changed[0]
const entry = h.get(id)
if (!entry) finish('条目 ' + id + ' 被删了 —— 下架要维护者处理(D296:不做下架标记)')

// 2. 作者名 = PR 提交者;`linplayer/` 前缀只许维护者
const author = (id.split('/')[0] || '').toLowerCase()
if (author === 'linplayer') note('`linplayer/` 前缀的条目只能由维护者改')
else if (author !== String(args.author || '').toLowerCase()) {
  note('条目作者是 ' + author + ',而提交者是 ' + args.author)
}

/* 3. 版本必须递增。
   ☠ 条目里的版本在 `versions[]` 里(index.schema.json 的 entry),不是一个平铺字段。
     按平铺字段读的话 `undefined <= undefined`,这条判据恒过 —— 而它正是
     「作者把包换成旧版本」唯一的拦截点。 */
const latest = (e) => (e && e.versions || []).slice().sort((x, y) => cmpSemver(y.version, x.version))[0]
const now = latest(entry)
const was = latest(b.get(id))
if (!now) finish('条目里没有任何版本')
if (was && cmpSemver(now.version, was.version) <= 0) {
  note('版本没递增:' + was.version + ' → ' + now.version)
}

// 4. 包能下载、manifest 合法、能装起来、解压安全 —— 全交给官方 lp
//    (本地 `lp check` 跑的是同一套,D484:作者在本地就能先验一遍)
try {
  // LP_BIN 让本地自测能指到别处;CI 里就是 ./lp(tools/fetch-lp.sh 取下来的官方构建)
  execFileSync(process.env.LP_BIN || './lp', ['check', '--remote', now.url], { stdio: 'inherit' })
} catch {
  note('lp check 没过(包下不下来 / manifest 不合法 / 装不起来 / 解压不安全)')
}

// 5. repository 要公开可访问
if (!entry.repository) note('缺 repository —— 进官方索引必须是公开仓库(D408 D483)')

/* 6. 已经上过架的那几版**一个字都不许改**。
   ☠ 只验最新版的话,作者可以把历史版本的 url 换成任意地址 ——
     而按版本取包的用户(minAppVersion 挡住新版时,D450)拿到的就是那一个。 */
const prev = b.get(id)
if (prev) {
  const wasVers = new Map((prev.versions || []).map((v) => [v.version, JSON.stringify(v)]))
  for (const v of entry.versions || []) {
    const before = wasVers.get(v.version)
    if (before && before !== JSON.stringify(v)) {
      note('改了已经上架的版本 ' + v.version + ' —— 历史版本不许动')
    }
  }
}

/* 7. 只有维护者能给的字段:自己填 official / 刷下载量都不算数。 */
if (entry.official && !(prev && prev.official)) note('`official` 只能由维护者给')
for (const k of ['downloads', 'stars']) {
  const nowV = entry[k]
  const wasV = prev ? prev[k] : undefined
  if (nowV !== undefined && nowV !== wasV) note('`' + k + '` 由定时任务写,PR 里不许改(D240)')
}

finish(null)

function finish(hard) {
  const reason = hard || (fail.length ? fail.join(';') : '')
  const verdict = { automerge: hard || fail.length ? 'no' : 'yes', reason: reason || '全部通过' }
  if (args.out) writeFileSync(args.out, JSON.stringify(verdict, null, 2))
  else console.log(JSON.stringify(verdict))
  // ☠ 退出码恒为 0:「不能自动合并」不是 CI 失败,它是一条**判决**。
  //   报成失败的话作者会以为自己的包坏了,而实际上只是要等人看一眼。
  process.exit(0)
}

function cmpSemver(a, b) {
  const pa = String(a).split('.').map(Number)
  const pb = String(b).split('.').map(Number)
  for (let i = 0; i < 3; i++) {
    if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0)
  }
  return 0
}
