/** @jsx h */
/** @jsxFrag Fragment */
// 迷你直播示例:核对 UI、注册表、播放器、命令、后台任务这几组 API 能否写出一个真实插件。
import {
  h, definePlugin, registry, player, ui, nav, storage, useState, useViewport, usePlayerState,
  Row, Column, Text, VirtualList, Pressable, Player, Stack, Button,
  type LiveChannel, type PageProps, type BlockProps,
} from '@linplayer/plugin-sdk'

function allChannels(): LiveChannel[] {
  const out: LiveChannel[] = []
  for (const e of registry.list('live.channels')) {
    if (e.value.kind === 'list') for (const g of e.value.groups) out.push(...g.channels)
  }
  return out
}

function tune(index: number) {
  const list = allChannels()
  if (!list.length) return
  const i = (index + list.length) % list.length
  storage.set('current', i)
  const ch = list[i]!
  void player.playUrl(ch.urls[0]!.url, ch.urls[0]!.headers, { title: ch.name, recordKey: `live:${ch.name}` })
}

function LivePage(_p: PageProps) {
  const vp = useViewport()
  const [current, setCurrent] = useState(storage.get<number>('current') ?? 0)
  const channels = allChannels()
  const list = (
    <VirtualList
      itemCount={channels.length}
      renderItem={(i) => (
        <Pressable key={channels[i]!.name} onPress={() => { setCurrent(i); tune(i) }} a11yLabel={channels[i]!.name}>
          <Text style={{ padding: 10, color: i === current ? 'token:color.accent' : 'token:color.ink' }}>{channels[i]!.name}</Text>
        </Pressable>
      )}
    />
  )
  if (vp.formFactor === 'tv') {
    nav.setPageOptions({ immersive: true, edgeToEdge: true })
    return <Stack style={{ grow: 1 }}><Player style={{ position: 'absolute', inset: 0 }} autoplay /></Stack>
  }
  return (
    <Row style={{ grow: 1, gap: 10 }}>
      <Column style={{ width: 260 }}>{list}</Column>
      <Player style={{ grow: 1 }} autoplay />
    </Row>
  )
}

function LiveOsd(_p: BlockProps) {
  const st = usePlayerState({ hz: 1 })
  return (
    <Row style={{ padding: 14, gap: 10, background: '#00000099', align: 'center' }}>
      <Text style={{ color: '#FFFFFFFF', grow: 1 }}>{st?.item.title ?? ''}</Text>
      <Button title="上一台" onPress={() => tune((storage.get<number>('current') ?? 0) - 1)} />
      <Button title="下一台" onPress={() => tune((storage.get<number>('current') ?? 0) + 1)} />
      <Button title="字幕" variant="ghost" onPress={() => player.openPanel('subtitles')} />
    </Row>
  )
}

export default definePlugin({
  pages: { live: LivePage },
  blocks: { 'live-osd': LiveOsd },
  commands: {
    next() { tune((storage.get<number>('current') ?? 0) + 1) },
    prev() { tune((storage.get<number>('current') ?? 0) - 1) },
    tune(args) {
      const i = allChannels().findIndex((c) => c.name === args.channel)
      if (i >= 0) tune(i)
    },
  },
  background: {
    tasks: {
      async remind(ctx) {
        if (ctx.signal.aborted) return
        await ui.notify({ title: '迷你直播', body: '你收藏的节目快开始了', command: 'next' })
      },
    },
  },
})
