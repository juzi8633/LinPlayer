import fs from 'node:fs';
import path from 'node:path';
import { pinyin } from 'pinyin-pro';
import sample from '../data/index.sample.json';

export type Lang = 'zh' | 'en';
export type Platform = 'windows' | 'linux' | 'android' | 'android_tv';
export type Category =
  | 'dataSource' | 'theme' | 'player' | 'danmakuSubtitle'
  | 'metadata' | 'download' | 'live' | 'tool';

export interface Version {
  version: string; url: string; size: number; minAppVersion: string;
  platforms?: Platform[]; released: string;
  changelog?: string; contributesDiff?: string[];
}

export interface Entry {
  id: string; name: string; description: string;
  i18n?: Record<string, { name?: string; description?: string }>;
  author?: string; repository?: string; icon?: string; iconMono?: string;
  screenshots?: string[]; readme?: string; paid?: boolean;
  requires?: { components?: string[]; [k: string]: unknown };
  lan?: boolean; contributes?: string[]; categories?: Category[];
  stars?: number; downloads?: number; official?: boolean;
  addedAt?: string; versions: Version[];
}

export interface Index { schema: 1; name: string; updated: string; plugins: Entry[] }

/** 真实索引由 CI 生成,本地没有就退回示例 —— 站点要能脱离 CI 单独构建。 */
function load(): Index {
  const real = path.resolve(process.cwd(), '../registry/index.json');
  try {
    if (fs.existsSync(real)) {
      const got = JSON.parse(fs.readFileSync(real, 'utf8')) as Index;
      // 空索引 = CI 还没灌数据,不是「这个仓库没有插件」。空着构建出来的站没法验收。
      if (got.plugins?.length) return got;
    }
  } catch (e) {
    // 索引坏了不该让整站构建失败:退回示例并在构建日志里留一行
    console.warn('[registry] ../registry/index.json 读不动,改用示例数据:', e);
  }
  return sample as unknown as Index;
}

export const index: Index = load();
export const plugins: Entry[] = index.plugins ?? [];

export const slugOf = (id: string) => id.replace(/\//g, '-');
export const byId = new Map(plugins.map((p) => [slugOf(p.id), p]));

export function tr(e: Entry, lang: Lang) {
  const o = lang === 'zh' ? undefined : e.i18n?.[lang];
  return { name: o?.name || e.name, description: o?.description || e.description };
}

export const latest = (e: Entry) => e.versions[0];

/** 支持平台取全部版本的并集:老版本掉平台不代表插件不支持。 */
export function platformsOf(e: Entry): Platform[] {
  const all = new Set<Platform>();
  for (const v of e.versions) for (const p of v.platforms ?? []) all.add(p);
  return [...all];
}

/** 首字母缩写:中文取拼音首字母,其余取词首字母。搜索索引构建期算一次。 */
function initials(s: string): string {
  const py = pinyin(s, { pattern: 'first', toneType: 'none', type: 'array', nonZh: 'consecutive' });
  return py.join('').replace(/[^a-z0-9]/gi, '').toLowerCase();
}

export interface SearchRow {
  s: string; n: string; a: string; c: Category[]; p: Platform[];
  o: boolean; paid: boolean; d: number; st: number; up: number; add: number;
  hay: string;
}

/** 构建时生成搜索索引,前端 includes() 即时过滤 —— 十几行够用,不引搜索库。 */
export function searchIndex(lang: Lang): SearchRow[] {
  return plugins.map((e) => {
    const t = tr(e, lang);
    const words = [t.name, t.description, e.author ?? '', e.id];
    const hay = [...words, initials(t.name), initials(e.author ?? '')]
      .join(' ').toLowerCase();
    return {
      s: slugOf(e.id), n: t.name, a: e.author ?? '',
      c: e.categories ?? [], p: platformsOf(e),
      o: !!e.official, paid: !!e.paid,
      d: e.downloads ?? 0, st: e.stars ?? 0,
      up: Date.parse(latest(e).released) || 0,
      add: Date.parse(e.addedAt ?? latest(e).released) || 0,
      hay,
    };
  });
}
