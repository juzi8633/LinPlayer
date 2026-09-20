import rss from '@astrojs/rss';
import { plugins, slugOf, tr, latest, type Lang } from './registry';
import { t, href } from './i18n';

/** 两个 feed 形状一样,只有排序和取哪个时间不同 —— 不为此造两套。 */
export function feed(kind: 'new' | 'updated', lang: Lang, site: URL | undefined) {
  const L = t(lang);
  const items = plugins
    .map((e) => {
      const v = latest(e);
      const { name, description } = tr(e, lang);
      const date = kind === 'new' ? (e.addedAt ?? v.released) : v.released;
      return {
        title: kind === 'new' ? name : `${name} v${v.version}`,
        description: kind === 'updated' && v.changelog ? v.changelog : description,
        link: href(lang, `/plugins/${slugOf(e.id)}`),
        pubDate: new Date(date),
      };
    })
    .sort((a, b) => b.pubDate.getTime() - a.pubDate.getTime());

  return rss({
    title: `${L.brand} · ${kind === 'new' ? L.feedNew : L.feedUpdated}`,
    description: kind === 'new' ? L.feedNew : L.feedUpdated,
    site: site!,
    items,
    customData: `<language>${lang === 'zh' ? 'zh-cn' : 'en'}</language>`,
  });
}
