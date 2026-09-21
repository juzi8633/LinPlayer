// 产物自检:跑在 pnpm build 之后。少一页、feed 空了、或者有域名漏进产物,这里红。
// 反向验过:把 registry.ts 的 initials() 改成返回空串,第 4 条当场失败。
import fs from 'node:fs';
import path from 'node:path';

const dist = path.resolve(import.meta.dirname, '../dist');
const idx = JSON.parse(fs.readFileSync(
  path.resolve(import.meta.dirname, '../src/data/index.sample.json'), 'utf8'));
const slugs = idx.plugins.map((p) => p.id.replace(/\//g, '-'));
const fail = [];
const need = (p) => { if (!fs.existsSync(path.join(dist, p))) fail.push(`缺 ${p}`); };

for (const s of slugs) {
  need(`plugins/${s}/index.html`);
  need(`en/plugins/${s}/index.html`);
  need(`og/${s}.png`);
}
for (const f of ['rss/new.xml', 'rss/updated.xml', 'en/rss/new.xml', 'en/rss/updated.xml']) {
  need(f);
  const xml = fs.existsSync(path.join(dist, f)) ? fs.readFileSync(path.join(dist, f), 'utf8') : '';
  const n = (xml.match(/<item>/g) || []).length;
  if (n !== slugs.length) fail.push(`${f} 有 ${n} 条,应为 ${slugs.length}`);
}

// 拼音首字母:「午夜蓝」要能被 wyl 搜到。这条断掉说明 initials() 没跑
const home = fs.readFileSync(path.join(dist, 'index.html'), 'utf8');
const m = home.match(/<script type="application\/json" id="idx">([\s\S]*?)<\/script>/);
if (!m) fail.push('首页没有内嵌搜索索引');
else {
  const rows = JSON.parse(m[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>'));
  if (rows.length !== slugs.length) fail.push(`搜索索引 ${rows.length} 条,应为 ${slugs.length}`);
  if (!rows.some((r) => r.hay.includes('wyl'))) fail.push('搜索索引里没有拼音首字母(wyl)');
}

/* 红线:产物里不许有**没打算出现**的外部主机名。

   ☠ 第一版把 SITE_URL 与 PUBLIC_REPO_URL 也算成违规 —— 而那两个正是 CI
     必须注入的东西(站点绝对地址给 RSS 与 og:image,仓库地址给页脚)。
     于是这条自检在**它唯一要跑的环境里永远红**,而本地不给变量时又永远绿。
     判据要挡的是「不知不觉混进来的第三方主机」,不是「自己配的那两个」。 */
const expected = [process.env.SITE_URL, process.env.PUBLIC_REPO_URL]
  .filter(Boolean)
  .map((u) => { try { return new URL(u).host } catch { return '' } })
  .filter(Boolean);
const ALLOW = /^(localhost(:\d+)?|www\.w3\.org|github\.com)$/;
for (const f of fs.readdirSync(dist, { recursive: true })) {
  if (!/\.(html|xml|js|css)$/.test(f)) continue;
  const text = fs.readFileSync(path.join(dist, f), 'utf8');
  for (const [, host] of text.matchAll(/https?:\/\/([a-zA-Z0-9.:-]+)/g)) {
    if (ALLOW.test(host) || expected.includes(host)) continue;
    fail.push(`${f} 里有外部主机 ${host}`);
  }
}

if (fail.length) { console.error('产物自检不通过:\n  ' + [...new Set(fail)].join('\n  ')); process.exit(1); }
console.log(`产物自检通过:${slugs.length} 个插件 × (中/英详情页 + og) + 4 个 feed,无计划外的外部主机名。`);
