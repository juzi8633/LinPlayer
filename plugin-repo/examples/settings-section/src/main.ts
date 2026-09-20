// 纯声明式设置分节:manifest 里写清楚就够了,不需要自己画界面(SPEC 6.2,D286)。
//
// 要自定义界面时把 `settings` 换成 `block`,再在这里导出那个区块。
import { definePlugin, settings } from '@linplayer/plugin-sdk'

export default definePlugin({
  activate() {
    // 读自己的设置项:官方设置页改完会立刻反映到这里
    const hi = String(settings.get('greeting') || '你好')
    console.log(hi + (settings.get('loud') ? '!!!' : ''))
  },
})
