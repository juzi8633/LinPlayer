/**
 * 同步这一半的纯逻辑(SPEC 17.3 的第 1 层与条目对应)。
 *
 * ☠ **全部失败都吞**:同步是记账,播放是主线。把上报失败弹给用户,
 *   只会在网断的时候不停打断看片 —— 这条是从旧实现搬过来的口径。
 *   吞不代表不留痕:每一条都进 console,调试面板的「日志」那一块看得到。
 */
import { trakt, bangumi, player, storage, settings } from '@linplayer/plugin-sdk'

/** 条目对应:优先外部 id,没有再按片名记一次(SPEC 17.3 末段)。 */
function idsOf(item: any): { kind: 'movie' | 'episode'; ids: any } | null {
  const ext = (item && item.externalIds) || {}
  const ids: any = {}
  if (ext.imdb) ids.imdb = ext.imdb
  if (ext.tmdb) ids.tmdb = Number(ext.tmdb) || ext.tmdb
  if (Object.keys(ids).length === 0) return null
  return { kind: item.kind === 'movie' ? 'movie' : 'episode', ids }
}

/** 记过的对应关系:按剧记住,下次不再问(SPEC 17.3「按剧记住」)。 */
function remembered(key: string): any {
  return storage.get('match:' + key)
}

export function remember(key: string, value: any) {
  storage.set('match:' + key, value)
}

function enabled(): boolean {
  const v = settings.get('scrobble')
  return v === undefined ? true : !!v
}

async function scrobble(action: 'start' | 'pause' | 'stop', np: any) {
  if (!enabled()) return
  const item = (np && np.item) || {}
  const m = idsOf(item) || remembered(String(item.id || ''))
  if (!m) {
    console.log('scrobble 跳过:这一条没有 TMDB / IMDb id,对不上 Trakt 的条目')
    return
  }
  // 进度优先用事件里带的(那是这一刻的真值);播放器状态是兜底
  const st = player.state() as any
  const d = Number(np && np.durationSec) || (st && st.duration) || 0
  const p = Number(np && np.positionSec) || (st && st.position) || 0
  const progress = d > 0 ? Math.min(100, Math.max(0, (p / d) * 100)) : 0
  const body: any = { progress }
  if (m.kind === 'movie') body.movie = { ids: m.ids }
  else body.episode = { ids: m.ids }
  try {
    await trakt.request('POST', '/scrobble/' + action, body)
  } catch (e: any) {
    // 409 是 Trakt 说「这条刚上报过」,不是错;其余也只记一笔
    console.log('scrobble ' + action + ' 没成功:' + ((e && e.message) || e))
  }
}

export const scrobbleStart = (np: any) => scrobble('start', np)
export const scrobblePause = (np: any) => scrobble('pause', np)
export const scrobbleStop = (np: any) => scrobble('stop', np)

/**
 * Bangumi:播完达阈值时**先把条目设成「在看」再标单集**(SPEC 17.3 第 1 层)。
 *
 * ☠ 顺序不能反:没有收藏关系时直接标单集,Bangumi 回 404 ——
 *   而旧实现只看返回的 bool、不看原因,所以「点格子恒 false」活了几个月。
 * ☠ 单集那条路径里 subject 那一位**必须是字面量 `-`**(同一个事故的另一半)。
 */
export async function markBangumiEpisode(np: any) {
  if (!enabled()) return
  const item = (np && np.item) || {}
  const subject = (item.externalIds && item.externalIds.bangumi) || remembered('bgm:' + (item.id || ''))
  const episode = item.bangumiEpisodeId || (np && np.episodeId)
  if (!subject) return
  try {
    await bangumi.request('POST', '/v0/users/-/collections/' + subject, { type: 3 })
  } catch (e: any) {
    console.log('Bangumi 设「在看」没成功:' + ((e && e.message) || e))
  }
  if (!episode) return
  try {
    await bangumi.request('PUT', '/v0/users/-/collections/-/episodes/' + episode, { type: 2 })
  } catch (e: any) {
    console.log('Bangumi 标单集没成功:' + ((e && e.message) || e))
  }
}

/** 详情页要显示的两行状态。哪家没连就回空,不抛 —— 大多数人只连一家。 */
export async function statusOf(item: any): Promise<{ trakt?: string; bangumi?: string }> {
  const out: { trakt?: string; bangumi?: string } = {}
  const ext = (item && item.externalIds) || {}
  if (ext.imdb || ext.tmdb) {
    try {
      const r: any = await trakt.request('GET', '/sync/playback/episodes')
      if (Array.isArray(r)) {
        const hit = r.find((x: any) => sameIds(x, ext))
        out.trakt = hit ? '看到 ' + Math.round(hit.progress || 0) + '%' : '还没看过'
      }
    } catch (e) {
      // 没连账号 / 上游挂了都走这里:这一行不显示就是了
    }
  }
  if (ext.bangumi) {
    try {
      const r: any = await bangumi.request('GET', '/v0/users/-/collections/' + ext.bangumi)
      if (r && typeof r.ep_status === 'number') out.bangumi = '在看 ' + r.ep_status + ' 话'
      else if (r && r.type === 2) out.bangumi = '看过'
    } catch (e) {
      // 404 = 没收藏过,不是错
    }
  }
  return out
}

function sameIds(x: any, ext: any): boolean {
  const ids = (x && x.episode && x.episode.ids) || (x && x.movie && x.movie.ids) || {}
  if (ext.imdb && ids.imdb) return ids.imdb === ext.imdb
  if (ext.tmdb && ids.tmdb) return String(ids.tmdb) === String(ext.tmdb)
  return false
}
