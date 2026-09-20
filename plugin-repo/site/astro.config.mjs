// @ts-check
import { defineConfig } from 'astro/config';

// 站点绝对地址只从构建期环境变量来 —— 自定义域名存在 Pages 设置里,
// 仓库文件里不写(SPEC 15.6)。本地构建没给就用 localhost,RSS/og 仍能生成。
const site = process.env.SITE_URL || 'http://localhost:4321';

export default defineConfig({
  site,
  base: process.env.BASE_PATH || '/',
  trailingSlash: 'ignore',
  build: { format: 'directory' },
  // 默认语言不带前缀:中文在 /,英文在 /en/(D150 D399)
  i18n: {
    defaultLocale: 'zh',
    locales: ['zh', 'en'],
    routing: { prefixDefaultLocale: false },
  },
  markdown: { shikiConfig: { theme: 'github-dark-dimmed' } },
});
