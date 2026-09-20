/**
 * 官方开发者工具 `linplayer/devtools`(SPEC 16.5 17.5,D80 D81 D367)。
 *
 * 面板本身是 SPEC 16.5 的四块:日志 / 网络 / UI 树 / 性能与存储(在 panel.tsx)。
 * 另外两页(组件示例、一千项大列表)是**渲染器的真实用户**:渲染器坏了这两页先坏,
 * 三端截图对比与 TV 帧率验收用的也是它们(D543)。
 */
import {
  definePlugin, h, Fragment, useState, useEffect, useViewport, useTheme, useReducedMotion,
  app, ui, debug,
  View, Column, Text, Button, TextInput, Switch, Divider, Chip, ChipGroup,
  EmptyState, VirtualList, Canvas,
} from '@linplayer/plugin-sdk'
import { KV, PluginPicker, LogsBlock, NetworkBlock, UiTreeBlock, PerfBlock } from './panel'

const TABS = ['日志', '网络', 'UI 树', '性能与存储', '运行时', '示例']

function Panel() {
  const [tab, setTab] = useState(TABS[0])
  const [plugins, setPlugins] = useState<any[]>([])
  const [cur, setCur] = useState('')
  const [tick, setTick] = useState(0)
  // 开发者模式关着时整个 debug 命名空间不在(D555):先判再用,
  // 直接调会抛在渲染期、整块进错误边界,而该说的是「去打开开发者模式」
  const on = typeof debug !== 'undefined' && !!debug

  // 面板只有桌面(D367):手机/TV 上一块能看不能查的面板比没有更糟 ——
  // 那边的日志与报错栈走 `lp dev` 实时回传到电脑终端。示例页与大列表页三端都在(D559)。
  if (app.formFactor !== 'desktop') {
    return (
      <Column style={{ gap: 14 }}>
        <Text style={{ fontSize: 22, fontWeight: 'bold' }}>开发者工具</Text>
        <EmptyState text="调试面板只有桌面端。手机 / TV 的日志、报错栈与网络请求走 lp dev 实时回传到电脑终端。" />
      </Column>
    )
  }

  useEffect(() => {
    if (!on) return
    debug.plugins().then((l: any[]) => {
      setPlugins(l)
      if (!cur && l.length) setCur(l[0].id)
    }, () => setPlugins([]))
  }, [on, tick])

  if (!on) {
    return (
      <Column style={{ gap: 14 }}>
        <Text style={{ fontSize: 22, fontWeight: 'bold' }}>开发者工具</Text>
        <EmptyState text="开发者模式没开,调试 API 不存在。到「设置 → 关于」连点版本号 7 次打开它。" />
      </Column>
    )
  }

  return (
    <Column style={{ gap: 14 }}>
      <View style={{ direction: 'row', justify: 'between', align: 'center' }}>
        <Text style={{ fontSize: 22, fontWeight: 'bold' }}>开发者工具</Text>
        <Button title="刷新" onPress={() => setTick((v) => v + 1)} />
      </View>

      <ChipGroup>
        {TABS.map((t) => <Chip key={t} label={t} selected={t === tab} onPress={() => setTab(t)} />)}
      </ChipGroup>

      {tab === '日志' || tab === '网络' || tab === '性能与存储'
        ? <PluginPicker list={plugins} current={cur} onPick={setCur} />
        : null}

      {tab === '日志' ? <LogsBlock plugin={cur} tick={tick} />
        : tab === '网络' ? <NetworkBlock plugin={cur} tick={tick} />
        : tab === 'UI 树' ? <UiTreeBlock tick={tick} />
        : tab === '性能与存储' ? <PerfBlock plugin={cur} tick={tick} />
        : tab === '运行时' ? <Runtime />
        : <Gallery />}
    </Column>
  )
}

/** 运行时与能力:排查「这台机器上为什么用不了」的第一站。 */
function Runtime() {
  const caps = app.capabilities
  const theme = useTheme()
  const reduced = useReducedMotion()
  return (
    <Column>
      <KV label="应用版本" value={app.version} />
      <KV label="平台" value={app.platform} />
      <KV label="形态" value={app.formFactor} />
      <KV label="开发者模式" value={app.devMode ? '开' : '关'} />
      <KV label="主题" value={theme.mode} />
      <KV label="强调色" value={String(theme.token('color.accent') || '(没给)')} />
      <KV label="减少动态效果" value={reduced ? '开' : '关'} />
      <KV label="WebView" value={caps.webview ? '可用' : '不可用'} />
      <KV label="触屏" value={caps.touch ? '是' : '否'} />
      <KV label="TV" value={caps.tv ? '是' : '否'} />
      <KV label="jar 运行时" value={caps.components['jar-runtime'] ? '已装' : '未装'} />
      <KV label="Python" value={caps.components['python'] ? '已装' : '未装'} />
      <Viewport />
      <Button title="弹一条 toast" onPress={() => ui.toast('开发者工具:渲染器与事件通道都是通的')} />
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
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>Canvas</Text>
      <Canvas
        style={{ height: 120 }}
        draw={(ctx) => {
          // 一帧的调用录成指令流一次性发给原生(D104),这里画的是三端该长一样的东西
          ctx.fillStyle = '#2b3350'
          ctx.fillRect(0, 0, 320, 120)
          for (let i = 0; i < 6; i++) {
            ctx.fillStyle = i % 2 === 0 ? '#ff8800' : '#4aa3ff'
            ctx.fillRect(12 + i * 48, 90 - i * 12, 36, 20 + i * 12)
          }
          ctx.strokeStyle = '#ffffff'
          ctx.lineWidth = 2
          ctx.beginPath()
          ctx.moveTo(8, 100)
          ctx.lineTo(312, 100)
          ctx.stroke()
          ctx.fillStyle = '#ffffff'
          ctx.font = '16px sans-serif'
          ctx.fillText('指令流画的', 12, 28)
        }}
      />

      <Divider />
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>输入</Text>
      <TextInput placeholder="输点什么,失焦时才回传(非受控,D135)" onChangeText={setText} />
      <Text style={{ color: 'token:color.ink2' }}>收到:{text || '(还没有)'}</Text>
      <View style={{ direction: 'row', align: 'center', gap: 10 }}>
        <Switch value={on} onChange={setOn} />
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

/** 视口与安全区(SPEC 7.7):这一行在有刘海 / 手势条 / 电视过扫描边的设备上数字不一样。 */
function Viewport() {
  const v = useViewport()
  return (
    <Column>
      <KV label="视口" value={`${Math.round(v.width)}×${Math.round(v.height)} · ${v.breakpoint} · ${v.formFactor}`} />
      <KV
        label="安全区"
        value={`上${Math.round(v.insets.top)} 下${Math.round(v.insets.bottom)} 左${Math.round(v.insets.left)} 右${Math.round(v.insets.right)}`}
      />
    </Column>
  )
}

/**
 * 大列表页(D134 / D543 的「TV 1000 项 ≥50fps」那条)。
 * 一千项交给 VirtualList,JS 只渲染窗口内那几十条,壳只画可见范围。
 */
function BigList() {
  return (
    <Column style={{ gap: 10 }}>
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>一千项</Text>
      <VirtualList
        style={{ height: 620 }}
        itemCount={1000}
        itemHeight={56}
        renderItem={(i) => (
          <View style={{ direction: 'row', justify: 'between', align: 'center', paddingTop: 10, paddingBottom: 10 }}>
            <Text>第 {i + 1} 项</Text>
            <Text style={{ color: 'token:color.ink2' }}>{i % 3 === 0 ? '三的倍数' : ''}</Text>
          </View>
        )}
      />
    </Column>
  )
}

definePlugin({
  pages: { panel: Panel, gallery: Gallery, list: BigList },
})
