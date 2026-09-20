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

/** 顶层 UI 组件:`export declare function View(p): LpElement`。
 *  JSX 里 <View> 编译成**标识符** View,所以 SDK 上必须有同名的值。 */
const components = []

/** 顶层 hooks:`export declare function useTheme(): ...`。
 * 它们和组件一样挂在 SDK 根上,却一直不在门禁里 —— 于是 .d.ts 声明了五个、
 * 运行时一个没挂,插件拿到 undefined,而报错报在插件那边(D555)。 */
const hooks = []

for (const st of src.statements) {
  if (
    ts.isFunctionDeclaration(st) && st.name &&
    st.type && ts.isTypeReferenceNode(st.type) &&
    ts.isIdentifier(st.type.typeName) && st.type.typeName.text === 'LpElement' &&
    // h / Fragment 也返回 LpElement,但它们是 JSX 工厂不是组件 —— 由渲染器那份 Preact 提供
    st.name.text !== 'h' && st.name.text !== 'Fragment'
  ) {
    components.push(st.name.text)
  }
  if (
    ts.isFunctionDeclaration(st) && st.name && /^use[A-Z]/.test(st.name.text) &&
    st.modifiers && st.modifiers.some((m) => m.kind === ts.SyntaxKind.DeclareKeyword)
  ) {
    hooks.push(st.name.text)
  }
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
if (hooks.length < 5) {
  console.error(`只解析出 ${hooks.length} 个 hook —— 定义源里 hooks 的写法八成变了`)
  process.exit(1)
}
if (components.length < 20) {
  console.error(`只解析出 ${components.length} 个组件 —— 定义源里组件的写法八成变了`)
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
lines.push('')
lines.push('// SDKComponents 定义源里声明的 UI 组件名。SDK 上每个名字挂的就是它自己(字符串),')
lines.push('// 因为 JSX 的 <View> 编译成标识符 View,而 Preact 见到字符串类型就建宿主元素。')
lines.push('var SDKComponents = []string{')
for (const c of components.slice().sort()) lines.push(`\t${JSON.stringify(c)},`)
lines.push('}')
lines.push('')
lines.push('// SDKHooks 定义源里声明的 hooks。它们和组件一样挂在 SDK 根上,')
lines.push('// 少一个的表现是插件拿到 undefined,而报错报在插件那边。')
lines.push('var SDKHooks = []string{')
for (const h of hooks.slice().sort()) lines.push(`	${JSON.stringify(h)},`)
lines.push('}')
writeFileSync(out, lines.join('\n') + '\n')
console.log(`✓ ${spec.size} 个命名空间、${components.length} 个组件、${hooks.length} 个 hook → ${out}`)
