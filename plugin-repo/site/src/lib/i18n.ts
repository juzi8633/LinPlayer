import type { Lang } from './registry';

// UI 文案两份平铺,不做嵌套命名空间 —— 站点一共这么多字,多一层只是多一层查找。
const S = {
  zh: {
    brand: 'LinPlayer 插件', navPlugins: '插件', navDocs: '文档', navGuide: '上架指南',
    navDownload: '下载 LinPlayer', theme: '明暗切换', langSwitch: 'English',
    search: '搜索插件 —— 名称、作者、描述,或拼音首字母',
    all: '全部', platform: '平台', sort: '排序', officialOnly: '只看官方', paidHide: '隐藏含付费功能',
    sortDownloads: '下载量', sortUpdated: '最近更新', sortStars: 'star', sortNew: '新上架',
    downloads: '下载', stars: 'star', empty: '没有匹配的插件。换个词,或把筛选条件放宽。',
    count: (n: number) => `${n} 个插件`,
    install: '一键安装', installing: '正在唤起 LinPlayer…', noResponse: '没有反应?',
    getApp: '下载 LinPlayer', manual: '手动下载包', scan: '手机扫码安装',
    contributes: '贡献点', changelog: '更新日志', versions: '历史版本',
    platformsTitle: '支持平台', requires: '所需组件', caps: '必需能力', minApp: '最低应用版本',
    sameAuthor: '同作者的其它插件', readme: '说明', overview: '概览',
    lanCap: '局域网访问', official: '官方', paid: '含付费功能', size: '体积',
    released: '发布于', back: '返回插件墙', repo: '源码仓库', feedNew: '新上架', feedUpdated: '最近更新',
    footerRepo: '主仓库', footerGuide: '上架须知', footerRss: 'RSS',
    platWindows: 'Windows', platLinux: 'Linux', platAndroid: 'Android 手机', platAndroidTv: 'Android TV',
    catDataSource: '数据源', catTheme: '主题', catPlayer: '播放', catDanmakuSubtitle: '弹幕字幕',
    catMetadata: '刮削同步', catDownload: '下载', catLive: '直播', catTool: '工具',
  },
  en: {
    brand: 'LinPlayer Plugins', navPlugins: 'Plugins', navDocs: 'Docs', navGuide: 'Publishing',
    navDownload: 'Get LinPlayer', theme: 'Theme', langSwitch: '中文',
    search: 'Search plugins — name, author, description, or pinyin initials',
    all: 'All', platform: 'Platform', sort: 'Sort', officialOnly: 'Official only', paidHide: 'Hide paid features',
    sortDownloads: 'Downloads', sortUpdated: 'Updated', sortStars: 'Stars', sortNew: 'Newest',
    downloads: 'downloads', stars: 'stars', empty: 'Nothing matched. Try another word, or loosen a filter.',
    count: (n: number) => `${n} plugin${n === 1 ? '' : 's'}`,
    install: 'Install', installing: 'Opening LinPlayer…', noResponse: 'Nothing happened?',
    getApp: 'Get LinPlayer', manual: 'Download the package', scan: 'Scan to install on a phone',
    contributes: 'Contributions', changelog: 'Changelog', versions: 'Version history',
    platformsTitle: 'Platforms', requires: 'Components', caps: 'Capabilities', minApp: 'Minimum app version',
    sameAuthor: 'More from this author', readme: 'README', overview: 'Overview',
    lanCap: 'Local network', official: 'Official', paid: 'Paid features', size: 'Size',
    released: 'Released', back: 'Back to plugins', repo: 'Source', feedNew: 'New plugins', feedUpdated: 'Updates',
    footerRepo: 'Repository', footerGuide: 'Publishing terms', footerRss: 'RSS',
    platWindows: 'Windows', platLinux: 'Linux', platAndroid: 'Android phone', platAndroidTv: 'Android TV',
    catDataSource: 'Data source', catTheme: 'Theme', catPlayer: 'Playback', catDanmakuSubtitle: 'Subtitles',
    catMetadata: 'Metadata', catDownload: 'Download', catLive: 'Live TV', catTool: 'Tools',
  },
} as const;

export const t = (lang: Lang) => S[lang];
export const other = (lang: Lang): Lang => (lang === 'zh' ? 'en' : 'zh');

/** 语言前缀:中文无前缀。拼路径统一走这里,免得 /en 漏在某几个链接上。 */
export const href = (lang: Lang, p: string) => {
  const clean = p.startsWith('/') ? p : `/${p}`;
  return lang === 'zh' ? clean : `/en${clean === '/' ? '' : clean}`;
};

const CAT_KEY = {
  dataSource: 'catDataSource', theme: 'catTheme', player: 'catPlayer',
  danmakuSubtitle: 'catDanmakuSubtitle', metadata: 'catMetadata',
  download: 'catDownload', live: 'catLive', tool: 'catTool',
} as const;
const PLAT_KEY = {
  windows: 'platWindows', linux: 'platLinux',
  android: 'platAndroid', android_tv: 'platAndroidTv',
} as const;

export const catName = (lang: Lang, c: keyof typeof CAT_KEY) => S[lang][CAT_KEY[c]];
export const platName = (lang: Lang, p: keyof typeof PLAT_KEY) => S[lang][PLAT_KEY[p]];
export const CATEGORIES = Object.keys(CAT_KEY) as (keyof typeof CAT_KEY)[];
export const PLATFORMS = Object.keys(PLAT_KEY) as (keyof typeof PLAT_KEY)[];

export const fmtNum = (n: number) =>
  n >= 10000 ? `${(n / 1000).toFixed(0)}k` : n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
export const fmtSize = (b: number) =>
  b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.round(b / 1024)} KB`;
export const fmtDate = (iso: string, lang: Lang) =>
  new Date(iso).toLocaleDateString(lang === 'zh' ? 'zh-CN' : 'en-GB',
    { year: 'numeric', month: 'short', day: 'numeric' });
