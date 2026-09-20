/** @jsx h */
// 播放器扩展(SPEC 9.5,D65 D279 D300 D563)。
//
// ☠ 覆盖层默认**点击穿透**:不声明 `interactive` 就不能拦鼠标与遥控器焦点,
//   否则用户会发现「进度条拖不动了」而完全看不出是谁干的。
// ☠ `player.onKey` 只在这个插件有可见的面板/覆盖层时问得到;
//   回 true = 这一下我接走了,宿主不再按默认处理。
import {
  h, definePlugin, player, useState, useEffect, usePlayerState,
  View, Column, Text,
} from '@linplayer/plugin-sdk'

function Info() {
  const st = usePlayerState({ hz: 2 })
  if (!st) return <Text>还没开始播</Text>
  return (
    <Column style={{ gap: 6, padding: 14 }}>
      <Text style={{ fontWeight: 'bold' }}>{st.item.title}</Text>
      <Text style={{ color: 'token:color.ink3' }}>
        {Math.floor(st.positionSec)} / {Math.floor(st.durationSec)} 秒
      </Text>
    </Column>
  )
}

function Hint() {
  const [msg, setMsg] = useState('')

  useEffect(() => {
    // 注册一次就好:把状态放进闭包外面读,不要跟着 state 反复退订重订 ——
    // 两次按键挨得近时,第二次会打到还没换掉的旧回调上
    const sub = player.onKey((e) => {
      if (e.key !== 'info') return false
      setMsg('按了信息键')
      setTimeout(() => setMsg(''), 2000)
      return true
    })
    return () => sub.dispose()
  }, [])

  if (!msg) return null
  return (
    <View style={{ position: 'absolute', top: 26, right: 26, paddingX: 14, paddingY: 6, radius: 10, background: '#000000aa' }}>
      <Text style={{ color: '#ffffffff' }}>{msg}</Text>
    </View>
  )
}

export default definePlugin({ blocks: { info: Info, hint: Hint } })
