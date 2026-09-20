/**
 * drpy 规则编辑器(SPEC 17.6,D121 D368)。
 *
 * 给懂 drpy 的人,不做表单向导:一个源码框 + 一个「测试」按钮 + 一个「保存为数据源」。
 *
 * ☠ 测试跑在**自己的子运行时**里(`js.createContext`),不去调 TVBox 插件:
 *   跨插件调用要那边先装上、版本还要对得上,而这一页的用处恰恰是
 *   「我的规则到底哪儿写错了」—— 多一层依赖就多一种「不是我的错」的可能。
 *   drpy 引擎与依赖库打包进本插件(D17),两份各自带。
 */
import {
  definePlugin, h, Fragment, useState, useEffect,
  js, assets, files, nav, ui, storage,
  View, Column, Row, Text, Button, TextInput, Chip, ChipGroup, Divider, Spinner, EmptyState,
} from '@linplayer/plugin-sdk'

const LIBS = ['cheerio.min.js', 'crypto-js.js', 'jsencrypt.js', 'gbk.js', '模板.js']
const ENTRY = `import 'lp://globals.js';\nimport drpy from 'engine://drpy2.js';\nglobalThis.__drpy = drpy;\n`
// esbuild 把 UMD 包成 CJS 之后它们不再往全局挂;drpy2 依赖全局 CryptoJS / JSEncrypt,要先于它求值
const GLOBALS = `import CryptoJS from 'lib://crypto-js.js';\nimport JSEncrypt from 'lib://jsencrypt.js';\nglobalThis.CryptoJS = CryptoJS;\nglobalThis.JSEncrypt = JSEncrypt;\n`

let bundled: Promise<string> | null = null

/** 打一份 drpy 引擎出来。打一次缓存住:每点一次「测试」重打一遍要好几秒。 */
function engine(): Promise<string> {
  if (!bundled) {
    bundled = js.bundle('lp://entry.js', {
      resolve: async (p: string) => {
        if (p === 'lp://entry.js') return ENTRY
        if (p === 'lp://globals.js') return GLOBALS
        if (p === 'engine://drpy2.js') return assets.readText('assets/drpy/drpy2.js')
        const base = decodeURIComponent(p.slice(p.lastIndexOf('/') + 1))
        if (LIBS.includes(base)) return assets.readText('assets/drpy/lib/' + base)
        return null
      },
    })
    bundled.catch(() => { bundled = null })
  }
  return bundled
}

const STEPS = [
  { id: 'home', label: '首页' },
  { id: 'category', label: '分类' },
  { id: 'detail', label: '详情' },
  { id: 'play', label: '播放' },
]

/** 跑一遍规则。返回每一步的结果或报错栈。 */
async function runRule(rule: string, step: string): Promise<any> {
  const src = await engine()
  // withSyncHost:drpy 要同步的 req() / pdfh / pdfa,没有它规则跑不起来(和 TVBox 插件同一口径)
  const ctx = await js.createContext({ withSyncHost: true })
  try {
    await ctx.run(src, 'drpy2.bundle.js')
    await ctx.call('__drpy.init', rule)
    switch (step) {
      case 'home': return await ctx.call('__drpy.home', true)
      case 'category': return await ctx.call('__drpy.category', '1', '1', false, {})
      case 'detail': {
        const home: any = await ctx.call('__drpy.homeVod')
        const first = (home && home.list && home.list[0]) || null
        if (!first) throw new Error('首页没有条目,详情这一步没东西可试')
        return await ctx.call('__drpy.detail', String(first.vod_id))
      }
      case 'play': {
        const home: any = await ctx.call('__drpy.homeVod')
        const first = (home && home.list && home.list[0]) || null
        if (!first) throw new Error('首页没有条目,播放这一步没东西可试')
        const d: any = await ctx.call('__drpy.detail', String(first.vod_id))
        const v = (d && d.list && d.list[0]) || {}
        const flag = String(v.vod_play_from || '').split('$$$')[0]
        const url = String(v.vod_play_url || '').split('$$$')[0].split('#')[0].split('$').pop()
        return await ctx.call('__drpy.play', flag, url, [])
      }
    }
    throw new Error('没有这一步:' + step)
  } finally {
    ctx.dispose()
  }
}

function EditorPage() {
  const [rule, setRule] = useState(String(storage.get('draft') || ''))
  const [step, setStep] = useState('home')
  const [busy, setBusy] = useState(false)
  const [out, setOut] = useState('')

  useEffect(() => { storage.set('draft', rule) }, [rule])

  const test = async () => {
    if (!rule.trim()) { setOut('先把规则贴进来'); return }
    setBusy(true)
    setOut('跑「' + (STEPS.find((s) => s.id === step) || {}).label + '」…')
    try {
      const r = await runRule(rule, step)
      setOut(JSON.stringify(r, null, 2).slice(0, 8000))
    } catch (e: any) {
      /* 报错栈原样显示:宿主已按 sourcemap 映射回 TS 行号(D81),
         而规则里的错报的是规则自己的行号 —— 那才是这一页要给的东西 */
      setOut('出错了:' + ((e && e.message) || e) + '\n' + ((e && e.stack) || ''))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Column style={{ gap: 10 }}>
      <Text style={{ fontSize: 22, fontWeight: 'bold' }}>drpy 规则编辑器</Text>
      <TextInput
        multiline
        placeholder="把 drpy 规则贴这里(失焦时保存草稿)"
        defaultValue={rule}
        onChangeText={setRule}
        style={{ height: 320 }}
      />
      <ChipGroup>
        {STEPS.map((s) => <Chip key={s.id} label={s.label} selected={s.id === step} onPress={() => setStep(s.id)} />)}
      </ChipGroup>
      <Row style={{ gap: 10 }}>
        <Button title="测试" variant="primary" disabled={busy} onPress={() => void test()} />
        <Button
          title="保存为数据源"
          disabled={busy || !rule.trim()}
          onPress={() => nav.push('server.add', { type: 'linplayer/tvbox:drpy-rule', prefill: { rule } })}
        />
        <Button title="清空" variant="ghost" disabled={busy} onPress={() => { setRule(''); setOut('') }} />
      </Row>
      <Divider />
      {busy ? <Row style={{ gap: 10, align: 'center' }}><Spinner /><Text>{out}</Text></Row>
        : out ? <Text style={{ maxLines: 40 }}>{out}</Text>
        : <EmptyState text="点「测试」在自己的子运行时里跑一遍,结果或报错栈显示在这里" />}
    </Column>
  )
}

export default definePlugin({
  pages: { editor: EditorPage },
})
