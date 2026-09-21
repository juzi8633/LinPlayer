import type { APIRoute } from 'astro';
import fs from 'node:fs';
import path from 'node:path';

// 客户端的「官方市场」地址就指到这里(LP_PLUGIN_MARKET_URL)。
// ☠ 不能复用 lib/registry 的 index:它读不到真索引时会退回 src/data/index.sample.json,
//   那样站点照样构建成功,而客户端会拿到一份**假的**市场 —— 里面的下载地址全是 404。
//   宁可让构建红。
export const GET: APIRoute = () => {
  const real = path.resolve(process.cwd(), '../registry/index.json');
  if (!fs.existsSync(real)) throw new Error(`registry/index.json 不在(${real}),市场地址会指向一个假索引`);
  const body = fs.readFileSync(real, 'utf8');
  const got = JSON.parse(body);
  if (!got.plugins?.length) throw new Error('registry/index.json 是空的 —— 客户端会看到一个没有插件的市场');
  return new Response(body, {
    headers: {
      'content-type': 'application/json; charset=utf-8',
      // Pages 不认这个头(静态托管自己定缓存),留着是给自建反代看的
      'cache-control': 'public, max-age=300',
    },
  });
};
