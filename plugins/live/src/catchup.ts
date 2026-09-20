/**
 * 回看 / 时移地址(SPEC 17.2,D111)。
 *
 * 四种格式,各家写法不一:
 *   · append   —— 把 source 直接接在原地址后面(source 里通常带 `?utc=…`);
 *   · default  —— source 是**完整地址模板**,替换占位符;
 *   · shift    —— 在原地址上加 `?utcstart=…&utcend=…`(没给 source 时的老写法);
 *   · flussonic —— 把路径里的 `index.m3u8` 换成 `archive-<start>-<duration>.m3u8`。
 *
 * ☠ 时间占位符有两套写法(`${start}` 与 `{utc}`),而且同一个源里可能混用。
 *   只认一套的表现是「点回看跳到直播」—— 地址是通的,内容不对,没有任何报错。
 */

import type { Catchup } from './m3u'

/** 两位补零。日期模板要用到。 */
function p2(n: number): string {
  return String(n).padStart(2, '0')
}

/**
 * 把一个占位符名字解成值。
 * 认得的名字全在这里,加新名字改这一处。
 */
function placeholder(name: string, startSec: number, endSec: number, nowSec: number): string | null {
  const dur = Math.max(0, Math.round(endSec - startSec))
  const d = new Date(startSec * 1000)
  switch (name.toLowerCase()) {
    case 'start':
    case 'utc':
    case 'utcstart':
    case 'timestamp':
      return String(Math.round(startSec))
    case 'end':
    case 'utcend':
      return String(Math.round(endSec))
    case 'offset':
      // 距现在多少秒(某些源按偏移取档)
      return String(Math.max(0, Math.round(nowSec - startSec)))
    case 'duration':
    case 'dur':
      return String(dur)
    case 'start-1':
      return String(Math.round(startSec) - 1)
    case 'y':
      return String(d.getUTCFullYear())
    case 'm':
      return p2(d.getUTCMonth() + 1)
    case 'd':
      return p2(d.getUTCDate())
    case 'h':
      return p2(d.getUTCHours())
    case 'min':
      return p2(d.getUTCMinutes())
    case 's':
      return p2(d.getUTCSeconds())
  }
  return null
}

/**
 * 替换 `${name}` 与 `{name}` 两套占位符。
 *
 * 不认得的占位符**原样留着**:抹成空串会让地址少一段而看起来仍然合法,
 * 留着的话至少能在日志里看出是哪个名字没认出来。
 */
export function fillTemplate(tpl: string, startSec: number, endSec: number, nowSec: number): string {
  return tpl.replace(/\$?\{([^{}]+)\}/g, (all, name) => {
    const v = placeholder(String(name), startSec, endSec, nowSec)
    return v === null ? all : v
  })
}

/** 地址上加查询参数,已经有 `?` 就用 `&`。 */
function addQuery(url: string, qs: string): string {
  if (!qs) return url
  return url + (url.includes('?') ? '&' : '?') + qs.replace(/^[?&]/, '')
}

/**
 * 算回看地址。`c` 为空或类型不认得时返回空串 —— 调用方据此**不显示回看入口**,
 * 而不是给一个点了会跳直播的按钮。
 */
export function catchupUrl(
  liveUrl: string,
  c: Catchup | undefined,
  startSec: number,
  endSec: number,
  nowSec: number,
): string {
  if (!c || !liveUrl) return ''
  const src = c.source || ''
  switch (c.type) {
    case 'default':
      // source 是整条地址模板;没给 source 的 default 退回 shift 的老写法
      return src ? fillTemplate(src, startSec, endSec, nowSec)
        : addQuery(liveUrl, 'utcstart=' + Math.round(startSec) + '&utcend=' + Math.round(endSec))

    case 'append':
      return liveUrl + fillTemplate(src, startSec, endSec, nowSec)

    case 'shift':
      return addQuery(liveUrl, src
        ? fillTemplate(src, startSec, endSec, nowSec)
        : 'utcstart=' + Math.round(startSec) + '&utcend=' + Math.round(endSec))

    case 'flussonic': {
      const dur = Math.max(1, Math.round(endSec - startSec))
      const seg = 'archive-' + Math.round(startSec) + '-' + dur + '.m3u8'
      // 路径末尾那一段换掉:index.m3u8 / mono.m3u8 / video.m3u8 都有人用
      const q = liveUrl.indexOf('?')
      const path = q < 0 ? liveUrl : liveUrl.slice(0, q)
      const tail = q < 0 ? '' : liveUrl.slice(q)
      const slash = path.lastIndexOf('/')
      if (slash < 0) return ''
      return path.slice(0, slash + 1) + seg + tail
    }
  }
  return ''
}

/** 这条回看模板还能往回看多久(秒)。没给 days 时按 7 天 —— 绝大多数源就是这个数。 */
export function catchupWindowSec(c: Catchup | undefined): number {
  if (!c) return 0
  return (c.days && c.days > 0 ? c.days : 7) * 24 * 3600
}
