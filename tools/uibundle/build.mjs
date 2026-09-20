// 把 Preact + hooks + 最小 DOM + 渲染器拼成 core/plugin/rt/uiruntime.js(D23,SPEC 7.1)。
//
// ★ 直接拼 UMD 产物,不引 bundler:preact 与 preact/hooks 的 UMD 各是一个自足文件,
//   拼接就够了。为此多装一个 esbuild/rollup 是给一次字符串拼接配一条流水线。
// ★ 产物**进仓库**:Go 侧 //go:embed 它,这样编 Go 不需要 node。
//   产物是否跟上源文件由 check-core 第 8 关守着。

import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const read = (p) => readFileSync(resolve(here, p), 'utf8')

const outArg = process.argv.indexOf('--out')
const out = outArg > 0 ? resolve(process.argv[outArg + 1]) : resolve(here, '../../core/plugin/rt/uiruntime.js')

const preactVersion = JSON.parse(read('node_modules/preact/package.json')).version

const parts = [
  `// 由 tools/uibundle/build.mjs 生成,**不要手改**。改 tools/uibundle/src/*.js 后跑 \`pnpm --dir tools/uibundle build\`。`,
  `// 内含 Preact ${preactVersion}(MIT)+ preact/hooks,外加 LinPlayer 的最小 DOM 与 surface 渲染器。`,
  `;(function (g) {`,
  // ★ document 做成**参数**:Preact 把它当自由变量读,包在这里之后它拿到的就是我们的最小 DOM,
  //   而插件看得见的 globalThis 上并没有 document —— 插件想直接摸 DOM 也摸不到。
  // ★ UMD 的 this 就是它要挂全局的那个对象:给一个假 scope,同样不污染 globalThis。
  `function __lp_makeScope(document) {`,
  `var scope = {};`,
  `(function () {`,
  read('node_modules/preact/dist/preact.umd.js'),
  `}).call(scope);`,
  `(function () {`,
  read('node_modules/preact/hooks/dist/hooks.umd.js'),
  `}).call(scope);`,
  `return scope;`,
  `}`,
  read('src/dom.js'),
  read('src/renderer.js'),
  `g.__lp_ui_init = function (host, sdk) { return makeRenderer(__lp_makeScope, host, sdk); };`,
  `g.__lp_preact_version = ${JSON.stringify(preactVersion)};`,
  `})(this);`,
]

writeFileSync(out, parts.join('\n') + '\n')
console.log(`✓ uiruntime.js(preact ${preactVersion}) → ${out}`)
