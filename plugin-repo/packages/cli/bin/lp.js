#!/usr/bin/env node
/**
 * `lp` 的包装:把参数原样转给随包装下来的原生二进制。
 *
 * ☠ 不在这里实现任何子命令。`lp` 的逻辑在主仓库的 Go 代码里(core/cmd/lp),
 *   在这儿再写一份 JS 版的话两边迟早对不上 —— 而对不上的表现是
 *   「本地 lp check 过了,CI 上没过」,作者会以为是 CI 的问题。
 */
const { spawnSync } = require('node:child_process')
const { existsSync } = require('node:fs')
const { join } = require('node:path')

const exe = join(__dirname, process.platform === 'win32' ? 'lp.exe' : 'lp')
if (!existsSync(exe)) {
  console.error('lp 二进制还没装好。重新装一次:pnpm add -D @linplayer/cli')
  console.error('装不下来的话,也可以从 LinPlayer 的 Releases 里直接下 lp 放进 PATH。')
  process.exit(1)
}
const r = spawnSync(exe, process.argv.slice(2), { stdio: 'inherit' })
process.exit(r.status === null ? 1 : r.status)
