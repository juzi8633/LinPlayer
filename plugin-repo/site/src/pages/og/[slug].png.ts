import type { APIRoute } from 'astro';
import sharp from 'sharp';
import { plugins, slugOf, tr, latest, type Entry } from '../../lib/registry';
import { fmtNum } from '../../lib/i18n';

export function getStaticPaths() {
  return plugins.map((e) => ({ params: { slug: slugOf(e.id) }, props: { entry: e } }));
}

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** 一行放不下就砍,加省略号 —— SVG 没有自动换行,硬算字宽比引排版库划算。 */
function clip(s: string, max: number) {
  let w = 0, out = '';
  for (const ch of s) {
    w += /[　-鿿＀-￯]/.test(ch) ? 2 : 1;
    if (w > max) return out + '…';
    out += ch;
  }
  return out;
}

// ⚠ 字体靠系统:CI 的 runner 必须装中文字体,否则中文渲染成方框。
const FONT = "'Microsoft YaHei','PingFang SC','Noto Sans CJK SC','Noto Sans SC',sans-serif";

function svgOf(e: Entry) {
  const { name, description } = tr(e, 'zh');
  const v = latest(e);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630">
  <defs>
    <radialGradient id="a" cx="14%" cy="8%" r="70%">
      <stop offset="0" stop-color="#5b8def" stop-opacity="0.28"/>
      <stop offset="1" stop-color="#5b8def" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="b" cx="92%" cy="88%" r="60%">
      <stop offset="0" stop-color="#2f7d72" stop-opacity="0.22"/>
      <stop offset="1" stop-color="#2f7d72" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="1200" height="630" fill="#0c0f14"/>
  <rect width="1200" height="630" fill="url(#a)"/>
  <rect width="1200" height="630" fill="url(#b)"/>
  <rect x="84" y="150" width="148" height="148" rx="10" fill="#1b212c" stroke="#323b4a"/>
  <text x="158" y="248" font-family=${JSON.stringify(FONT)} font-size="76" font-weight="700"
        fill="#5b8def" text-anchor="middle">${esc(name.slice(0, 1))}</text>
  <text x="268" y="222" font-family=${JSON.stringify(FONT)} font-size="60" font-weight="700"
        fill="#e8ebf1">${esc(clip(name, 26))}</text>
  <text x="268" y="284" font-family=${JSON.stringify(FONT)} font-size="28"
        fill="#6b7688">${esc(e.author ?? '')} · v${esc(v.version)}</text>
  <text x="84" y="392" font-family=${JSON.stringify(FONT)} font-size="32"
        fill="#9aa4b4">${esc(clip(description, 62))}</text>
  <rect x="84" y="470" width="1032" height="1" fill="#252c38"/>
  <text x="84" y="538" font-family=${JSON.stringify(FONT)} font-size="34" font-weight="600"
        fill="#e8ebf1">${fmtNum(e.downloads ?? 0)} 次下载</text>
  <text x="1116" y="538" font-family=${JSON.stringify(FONT)} font-size="28"
        fill="#6b7688" text-anchor="end">LinPlayer 插件</text>
</svg>`;
}

export const GET: APIRoute = async ({ props }) => {
  const png = await sharp(Buffer.from(svgOf(props.entry as Entry))).png().toBuffer();
  return new Response(new Uint8Array(png), { headers: { 'Content-Type': 'image/png' } });
};
