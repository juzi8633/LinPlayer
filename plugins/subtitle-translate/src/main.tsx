/**
 * 字幕翻译(SPEC 17.4,D125 D364 D487~D489)。
 *
 * 取原文 → 分块并发翻 → 失败二分重试 → 贴回**对应那一条** → 出 SRT → 挂回播放器。
 * 管线在 pipeline.ts(照搬旧实现已验证的做法),引擎在 engines.ts。
 *
 * ☠ 结果按「源字幕 + 引擎 + 语言 + 排版」缓存:同一集看第二遍不该再花一次 API 额度。
 */
import {
  definePlugin, h, Fragment, useState, useEffect,
  player, settings, storage, ui, crypt, ext,
  View, Column, Row, Text, Button, Chip, ChipGroup, Divider, EmptyState, Spinner, Switch,
} from '@linplayer/plugin-sdk'

import { parseSrt, toSrt, translateDocument, looksLikeSubtitle, type Cue, type Layout } from './pipeline'
import { engineOf } from './engines'
import { LiveOverlay } from './live'

const LAYOUTS: { id: Layout; label: string }[] = [
  { id: 'bilingual', label: '双语' },
  { id: 'translated', label: '只译文' },
  { id: 'source', label: '只原文' },
]

function cacheKey(src: string, engine: string, to: string, layout: string): string {
  return 'sub:' + crypt.md5(src).slice(0, 16) + ':' + engine + ':' + to + ':' + layout
}

/** 播放页侧栏那一块:选引擎、选排版、开翻。 */
function Panel() {
  const [engine, setEngine] = useState(String(settings.get('engine') || 'openai'))
  const [layout, setLayout] = useState<Layout>((settings.get('layout') as Layout) || 'bilingual')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [pct, setPct] = useState(0)

  const run = async (useWhisper: boolean) => {
    setBusy(true)
    setMsg(useWhisper ? '转写中…' : '取原文…')
    setPct(0)
    try {
      const to = String(settings.get('target') || 'zh-Hans')
      if (useWhisper) {
        // 缺组件时这一步会弹下载确认;用户拒绝抛 unsupported,下面的 catch 原样显示
        await ext.ensure('whisper')
      }
      const src = useWhisper
        ? await player.transcribe({ lang: String(settings.get('source') || ''), onProgress: (p) => setPct(Math.round(p * 100)) })
        : await player.getSubtitleText()
      if (!looksLikeSubtitle(src.text)) {
        // 404 的 HTML 错误页解析出来是 0 条,报「源字幕是空的」会让人查错方向
        throw new Error('取回来的不像字幕(可能那条轨取不到,或者服务器回了错误页)')
      }
      const key = cacheKey(src.text, engine, to, layout)
      const hit = storage.get<string>(key)
      if (hit) {
        await player.addSubtitle(hit, { format: 'srt', lang: to, title: '翻译 · ' + engine, select: true })
        setMsg('用了缓存,已挂上')
        return
      }
      const cues: Cue[] = parseSrt(src.text)
      if (cues.length === 0) throw new Error('源字幕解析出 0 条')
      setMsg('翻译中…')
      await translateDocument(cues, engineOf(engine), String(settings.get('source') || 'auto'), to,
        (done, total) => setPct(Math.round((done / total) * 100)))
      const out = toSrt(cues, layout)
      storage.set(key, out)
      await player.addSubtitle(out, { format: 'srt', lang: to, title: '翻译 · ' + engine, select: true })
      setMsg('已挂上(' + cues.length + ' 条)')
    } catch (e: any) {
      // 报错要说人话并留在面板上:toast 一闪而过,用户回头想看是什么原因就没了
      setMsg('没成功:' + ((e && e.message) || e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Column style={{ gap: 10 }}>
      <Text style={{ fontSize: 18, fontWeight: 'bold' }}>字幕翻译</Text>
      <ChipGroup>
        {['openai', 'anthropic', 'baidu'].map((e) => (
          <Chip key={e} label={e} selected={e === engine} onPress={() => { setEngine(e); settings.set('engine', e) }} />
        ))}
      </ChipGroup>
      <ChipGroup>
        {LAYOUTS.map((l) => (
          <Chip key={l.id} label={l.label} selected={l.id === layout} onPress={() => { setLayout(l.id); settings.set('layout', l.id) }} />
        ))}
      </ChipGroup>
      <Row style={{ gap: 10 }}>
        <Button title="翻当前字幕" disabled={busy} onPress={() => void run(false)} />
        <Button title="没字幕?先转写" variant="ghost" disabled={busy} onPress={() => void run(true)} />
      </Row>
      <Row style={{ align: 'center', gap: 10 }}>
        <Switch value={!!settings.get('live')} onChange={(v) => settings.set('live', v)} a11yLabel="实时翻译" />
        <Text>实时翻译(边播边翻一句一句显示)</Text>
      </Row>
      {busy ? <Row style={{ gap: 10, align: 'center' }}><Spinner /><Text>{msg} {pct}%</Text></Row>
        : msg ? <Text style={{ color: 'token:color.ink2' }}>{msg}</Text> : null}
      <Divider />
      <Text style={{ color: 'token:color.ink3' }}>
        引擎的 key 在插件设置里填,存在密钥区,不会随设置导出分享出去。
      </Text>
    </Column>
  )
}

export default definePlugin({
  blocks: { panel: Panel, live: LiveOverlay },
  commands: {
    /** 命令面板 / 快捷键触发:直接翻当前字幕,不用先打开侧栏。 */
    async translateNow() {
      const to = String(settings.get('target') || 'zh-Hans')
      const engine = String(settings.get('engine') || 'openai')
      const layout = (settings.get('layout') as Layout) || 'bilingual'
      const src = await player.getSubtitleText()
      if (!looksLikeSubtitle(src.text)) throw new Error('取回来的不像字幕')
      const key = cacheKey(src.text, engine, to, layout)
      const hit = storage.get<string>(key)
      if (hit) {
        await player.addSubtitle(hit, { format: 'srt', lang: to, select: true })
        ui.toast('用了缓存,字幕已挂上')
        return
      }
      const cues = parseSrt(src.text)
      await translateDocument(cues, engineOf(engine), String(settings.get('source') || 'auto'), to)
      const out = toSrt(cues, layout)
      storage.set(key, out)
      await player.addSubtitle(out, { format: 'srt', lang: to, select: true })
      ui.toast('翻好了,' + cues.length + ' 条')
    },
  },
})
