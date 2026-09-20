// 壁纸接管位(SPEC 11.5,D441 D442)。
//
// 两条路都要有:
//   · `wallpaper` 入口给**初始**内容 —— 宿主在启动与切换壁纸之后各问一次;
//   · 运行中用 `wallpaper.set()` 换 —— 只有当前选中的壁纸插件调得动。
// ☠ 只写 set 不写入口的话,插件启动后到第一次 set 之间那段时间是**空的**;
//   只写入口不写 set 的话,壁纸永远停在开机那一张。
import { definePlugin, wallpaper, assets } from '@linplayer/plugin-sdk'

function pick(): string {
  const h = new Date().getHours()
  if (h < 6 || h >= 19) return 'night.webp'
  if (h < 10) return 'morning.webp'
  return 'day.webp'
}

export default definePlugin({
  wallpaper: async () => ({ kind: 'image', image: assets.url(pick()) }),

  activate(ctx) {
    // 每半小时看一次要不要换。宿主在后台/省电时会暂停视频壁纸,
    // 图片壁纸没有这个开销,定时器照常跑。
    const t = setInterval(() => {
      void wallpaper.set({ kind: 'image', image: assets.url(pick()) })
    }, 30 * 60 * 1000)
    ctx.subscriptions.push({ dispose: () => clearInterval(t) })
  },
})
