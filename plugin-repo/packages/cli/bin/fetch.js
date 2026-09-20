#!/usr/bin/env node
/**
 * 装包时按平台把 `lp` 二进制拉下来。
 *
 * ☠ 拉不到**不能让 `pnpm add` 失败**:装不上 CLI 只是少个工具,
 *   而 postinstall 非零退出会把整条依赖安装打断 —— 用户会以为是自己的项目坏了。
 *   所以这里失败只打一行说明,真正的报错留给 `lp.js`(它会说「二进制还没装好」)。
 */
const { createWriteStream, chmodSync, mkdirSync } = require('node:fs')
const { join } = require('node:path')
const https = require('node:https')

// 发布地址由 CI 在打包时写进 bin/release.json;仓库文件里不写死任何地址(SPEC 15.6)。
let base = ''
try {
  base = require('./release.json').base
} catch {
  // 本地开发装的包里没有这份清单:直接跳过,用户自己放二进制
}

const triples = {
  'win32-x64': 'lp-windows-x64.exe',
  'linux-x64': 'lp-linux-x64',
  'darwin-x64': 'lp-macos-x64',
  'darwin-arm64': 'lp-macos-arm64',
}
const key = `${process.platform}-${process.arch}`
const name = triples[key]

if (!base || !name) {
  console.log(`[linplayer] 这个平台(${key})没有预编译的 lp,跳过。可以自己从 Releases 下一个放进 PATH。`)
  process.exit(0)
}

const out = join(__dirname, process.platform === 'win32' ? 'lp.exe' : 'lp')
https.get(`${base}/${name}`, (res) => {
  if (res.statusCode !== 200) {
    console.log(`[linplayer] lp 没下下来(HTTP ${res.statusCode}),跳过。`)
    res.resume()
    return
  }
  const f = createWriteStream(out)
  res.pipe(f)
  f.on('close', () => {
    try {
      if (process.platform !== 'win32') chmodSync(out, 0o755)
    } catch {
      // 改不了权限时 lp.js 会报「装好了但跑不起来」,比在这里中断安装好
    }
  })
}).on('error', (e) => {
  console.log(`[linplayer] lp 没下下来(${e.message}),跳过。`)
})
