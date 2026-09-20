/**
 * 官方调试面板(SPEC 16、19.1 阶段 ②)。
 *
 * ★ 它的**第一身份是 UI 渲染器的真实用户**:渲染器的组件、样式、事件、列表、
 *   错误边界在这一页上全都用到一遍。渲染器坏了,这一页先坏 ——
 *   这比再写一套「渲染器自测页」有用,因为自测页只会用到写它时想得起来的那些路径。
 */
import {
  definePlugin, h, Fragment, useState, useEffect, app, ui,
  View, Column, Text, Button, TextInput, Switch, Divider, Chip, ChipGroup,
} from '@linplayer/plugin-sdk'

/** 一行「标签 : 值」。示例页与面板共用,顺便测**组件复用**下的 key 语义。 */
function KV(props: { label: string; value: string }) {
  return (
    <View style={{ direction: 'row', justify: 'between', align: 'center', paddingTop: 6, paddingBottom: 6 }}>
      <Text style={{ color: 'token:Ink2' }}>{props.label}</Text>
      <Text style={{ fontWeight: 'bold' }}>{props.value}</Text>
    </View>
  )
}

function Panel() {
  const [tab, setTab] = useState('运行时')
  const caps = app.capabilities

  return (
    <Column style={{ gap: 14 }}>
      <Text style={{ fontSize: 22, fontWeight: 'bold' }}>调试面板</Text>

      <ChipGroup>
        {['运行时', '能力', '示例'].map((t) => (
          <Chip key={t} label={t} onPress={() => setTab(t)} style={{ background: t === tab ? 'token:Accent' : 'token:PanelAlt' }} />
        ))}
      </ChipGroup>

      {tab === '运行时' ? (
        <Column>
          <KV label="应用版本" value={app.version} />
          <KV label="平台" value={app.platform} />
          <KV label="形态" value={app.formFactor} />
          <KV label="开发者模式" value={app.devMode ? '开' : '关'} />
          <KV label="减少动态效果" value={app.reducedMotion ? '开' : '关'} />
        </Column>
      ) : tab === '能力' ? (
        <Column>
          <KV label="WebView" value={caps.webview ? '可用' : '不可用'} />
          <KV label="触屏" value={caps.touch ? '是' : '否'} />
          <KV label="TV" value={caps.tv ? '是' : '否'} />
          <KV label="jar 运行时" value={caps.components['jar-runtime'] ? '已装' : '未装'} />
          <KV label="Python" value={caps.components['python'] ? '已装' : '未装'} />
        </Column>
      ) : (
        <Gallery />
      )}

      <Button title="弹一条 toast" onPress={() => ui.toast('调试面板:渲染器与事件通道都是通的')} />
    </Column>
  )
}

/**
 * 组件示例页(D543 验收要的那一组:布局 / 列表 / 输入 / 动效)。
 * 三端截图对着这一页比 —— 所以这里**不要**写平台分支。
 */
function Gallery() {
  const [text, setText] = useState('')
  const [on, setOn] = useState(true)
  const [n, setN] = useState(0)
  const [boom, setBoom] = useState(false)

  useEffect(() => {
    // 有副作用也要跑得起来:hooks 是从宿主那份 Preact 转出来的,换一份就断
    if (n > 99) setN(0)
  }, [n])

  if (boom) {
    // 故意抛:验错误边界只崩这一块(D136)。宿主会把它变成 surface 的 error 状态。
    throw new Error('示例页故意抛的错误,用来看错误边界')
  }

  return (
    <Column style={{ gap: 14 }}>
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>布局</Text>
      <KV label="计数" value={String(n)} />
      <View style={{ direction: 'row', gap: 10 }}>
        <Button title="加一" onPress={() => setN((v) => v + 1)} />
        <Button title="连加十次" onPress={() => { for (let i = 0; i < 10; i++) setN((v) => v + 1) }} />
      </View>

      <Divider />
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>输入</Text>
      <TextInput placeholder="输点什么,失焦时才回传(非受控,D135)" onChangeText={setText} />
      <Text style={{ color: 'token:Ink2' }}>收到:{text || '(还没有)'}</Text>
      <View style={{ direction: 'row', align: 'center', gap: 10 }}>
        <Switch checked={on} onToggle={setOn} />
        <Text>开关现在是 {on ? '开' : '关'}</Text>
      </View>

      <Divider />
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>列表</Text>
      <Column>
        {Array.from({ length: 8 }, (_, i) => (
          <KV key={i} label={'第 ' + (i + 1) + ' 项'} value={i % 2 === 0 ? '偶' : '奇'} />
        ))}
      </Column>

      <Divider />
      <Button title="触发一次错误(看错误边界)" onPress={() => setBoom(true)} />
    </Column>
  )
}

definePlugin({
  pages: { panel: Panel, gallery: Gallery },
})
