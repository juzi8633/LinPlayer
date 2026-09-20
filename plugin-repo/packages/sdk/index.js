// 运行时由宿主注入到全局 `__linplayer_sdk`(SPEC 2.3)。
//
// 这个文件存在的唯一理由:让 `import { player } from '@linplayer/plugin-sdk'`
// 在打包器眼里是一个真模块。打包时 `lp build` 会把它整条换成对全局对象的引用 ——
// 所以这里**不能**有任何实现,有了也跑不到。
module.exports = globalThis.__linplayer_sdk;
