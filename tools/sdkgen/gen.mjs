// 读 docs/plugin-system/api/plugin-sdk.d.ts,产出 Go 注册骨架(D514)。
//
// ★ 必须用 TypeScript 编译器 API,不能用 esbuild —— esbuild 只做转译,把类型全丢了,
//   `declare namespace` 里的成员名根本到不了产物(D514 原话)。
// ★ 产出的是**骨架**不是实现:只有「哪个命名空间有哪些成员」。
//   门禁拿它和运行时真正挂上去的 __linplayer_sdk 逐名比对(rt/sdk_contract_test.go)。

import ts from 'typescript'
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const dts = resolve(here, '../../docs/plugin-system/api/plugin-sdk.d.ts')
// --out 让门禁生成到临时文件再比对 —— 不覆盖仓库里那份,比对结果就不受工作区状态影响
const outArg = process.argv.indexOf('--out')
const out = outArg > 0 ? resolve(process.argv[outArg + 1]) : resolve(here, '../../core/plugin/rt/sdkspec_gen.go')

const src = ts.createSourceFile(dts, readFileSync(dts, 'utf8'), ts.ScriptTarget.Latest, true)

/** 命名空间名 → 成员名(函数、const、accessor 都算)。 */
const spec = new Map()

function membersOf(body) {
  const names = []
  for (const st of body.statements ?? []) {
    if (ts.isFunctionDeclaration(st) && st.name) names.push(st.name.text)
    else if (ts.isVariableStatement(st)) {
      for (const d of st.declarationList.declarations) if (ts.isIdentifier(d.name)) names.push(d.name.text)
    }
  }
  return names
}

for (const st of src.statements) {
  if (!ts.isModuleDeclaration(st) || !st.name || !ts.isIdentifier(st.name)) continue
  if (!st.body || !ts.isModuleBlock(st.body)) continue
  const names = membersOf(st.body)
  // 只收真有成员的:`namespace h { namespace JSX {...} }` 那种纯类型壳没有可注册的东西
  if (names.length) spec.set(st.name.text, names.sort())
}

if (spec.size < 10) {
  console.error(`只解析出 ${spec.size} 个命名空间 —— 定义源的写法八成变了,先看清楚再改这个脚本`)
  process.exit(1)
}

const lines = []
lines.push('package rt')
lines.push('')
lines.push('// 由 tools/sdkgen/gen.mjs 从 docs/plugin-system/api/plugin-sdk.d.ts 生成(D514)。')
lines.push('// **不要手改**:改定义源,然后 `pnpm --dir tools/sdkgen gen`。')
lines.push('// 谁在用:sdk_contract_test.go 拿它和运行时真挂上的 __linplayer_sdk 逐名比对。')
lines.push('')
lines.push('// SDKSpec 定义源里每个命名空间声明了哪些成员。')
lines.push('var SDKSpec = map[string][]string{')
for (const k of [...spec.keys()].sort()) {
  lines.push(`\t${JSON.stringify(k)}: {${spec.get(k).map((n) => JSON.stringify(n)).join(', ')}},`)
}
lines.push('}')
writeFileSync(out, lines.join('\n') + '\n')
console.log(`✓ ${spec.size} 个命名空间 → ${out}`)
