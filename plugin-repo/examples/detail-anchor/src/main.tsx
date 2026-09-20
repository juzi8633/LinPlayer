/** @jsx h */
// 注入位:官方详情页的锚点后面挂一块自己的内容(SPEC 6.1,D155)。
//
// ☠ 拿不到条目就**整块不画**,不要画一句「加载中」占着位置 ——
//   官方页上多一块永远转圈的东西比没有更糟。
import { h, definePlugin, Row, Text, Badge, type BlockProps } from '@linplayer/plugin-sdk'

function Mine(p: BlockProps) {
  const item = p.item
  if (!item) return null
  return (
    <Row style={{ gap: 6, align: 'center' }}>
      <Badge text={item.kind === 'series' ? '剧集' : '电影'} />
      {item.year ? <Text style={{ color: 'token:color.ink3' }}>{String(item.year)}</Text> : null}
    </Row>
  )
}

export default definePlugin({ blocks: { mine: Mine } })
