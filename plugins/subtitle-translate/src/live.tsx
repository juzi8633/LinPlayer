/**
 * 实时模式(SPEC 17.4 的「实时模式」):订阅 `sub-text`,边播边翻一句一句显示。
 *
 * ☠ 这条路上最容易出的两件事:
 *   · **同一句被翻好几遍** —— `sub-text` 在一句显示期间会重复推同一个值,
 *     不去重的话每秒都往引擎打一次,额度几分钟就没了;
 *   · **译文比字幕晚一句** —— 翻译是异步的,回来时画面可能已经换句了。
 *     所以每次请求记住它属于哪一句,回来时对不上就丢掉,不往屏幕上贴。
 */
import {
  h, Fragment, useState, useEffect,
  player, settings,
  View, Column, Text,
} from '@linplayer/plugin-sdk'

import { engineOf } from './engines'

/** 覆盖层:默认点击穿透(没声明 interactive),只显示一行译文。 */
export function LiveOverlay() {
  const [line, setLine] = useState('')

  // 依赖里带上这几项:上一版是空数组,用户在设置里打开「实时模式」
  // 要退出重进才生效,而界面上开关已经是开着的
  const live = !!settings.get('live')
  const target = String(settings.get('target') || 'zh-Hans')
  const engineId = String(settings.get('engine') || 'openai')

  useEffect(() => {
    if (!live) return
    const to = target
    const from = String(settings.get('source') || 'auto')
    const engine = engineOf(engineId)

    let current = ''
    let seq = 0
    let last = ''
    const sub = player.observe('sub-text', (v: any) => {
      const text = String(v || '').trim()
      if (text === current) return // 同一句会重复推,不去重就是每秒打一次引擎
      current = text
      if (!text) { setLine(''); return }
      const mine = ++seq
      engine.translate([text], from, to).then(
        (out) => {
          // 回来时画面可能已经换句了:对不上就丢掉,不往屏幕上贴
          if (mine !== seq) return
          last = (out && out[0]) || ''
          setLine(last)
        },
        (e) => {
          // 失败要留痕:全静默的话用户只看到「实时翻译开着但一行都没有」
          console.warn('实时翻译这一句没成功:' + ((e && e.message) || e))
          if (mine === seq) setLine('')
        },
      )
    }, { hz: 4 })

    return () => sub.dispose()
  }, [live, target, engineId])

  if (!line) return null
  return (
    <View style={{ position: 'absolute', bottom: 34, left: 0, right: 0, align: 'center' }}>
      <Column style={{
        paddingX: 14, paddingY: 6, radius: 'token:radius.card',
        // 叠在画面上的底:写死色号必须带 alpha(仓库红线),不透明的会把画面挡死
        background: '#00000099',
      }}>
        <Text style={{ color: '#ffffffff', fontSize: 'token:font.size.title', textAlign: 'center' }}>{line}</Text>
      </Column>
    </View>
  )
}
