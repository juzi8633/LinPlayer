// POST /api/report   body: { kind, version, platform, text, crash, log }
// 应用里「发送崩溃报告 / 反馈问题」→ 转成一条 Telegram 消息 + 一个 report.txt 附件。
//
// 环境变量(Encrypted):
//   TG_BOT_TOKEN  @BotFather 给的 bot token —— 只能放这里,绝不能进客户端(包里的东西都能被提取)
//   TG_CHAT_ID    收报告的会话 id(你自己和 bot 的私聊,或一个群)
//
// 共享密钥校验在 _middleware.js 里(/api/* 统一)。脱敏在客户端核心层做完了才发过来。

const MAX_BYTES = 1 << 20;

export async function onRequestPost({ env, request }) {
  if (!env.TG_BOT_TOKEN || !env.TG_CHAT_ID) return json({ error: '反馈服务没配置(TG_BOT_TOKEN / TG_CHAT_ID)' }, 503);
  if (Number(request.headers.get('Content-Length') || 0) > MAX_BYTES) return json({ error: '报告太大' }, 413);

  // 同一 IP 一分钟一条。ponytail: 用 Cache API 当计数器,按机房各算各的;真被刷再换 KV / Rate Limiting 规则
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const slot = new Request('https://rate.limit/report/' + encodeURIComponent(ip));
  const cache = caches.default;
  if (await cache.match(slot)) return json({ error: 'too many' }, 429);
  await cache.put(slot, new Response('1', { headers: { 'Cache-Control': 'max-age=60' } }));

  const r = await request.json().catch(() => null);
  if (!r) return json({ error: 'bad json' }, 400);

  const head = [
    r.kind === 'crash' ? '🧨 崩溃报告' : '💬 问题反馈',
    `版本 ${s(r.version)} · ${s(r.platform)}`,
    s(r.text) && '\n' + s(r.text),
  ].filter(Boolean).join('\n');
  const file = [head, '', '== 崩溃 ==', s(r.crash) || '(无)', '', '== 日志 ==', s(r.log) || '(无)'].join('\n');

  const form = new FormData();
  form.append('chat_id', env.TG_CHAT_ID);
  // caption 上限 1024 字符
  form.append('caption', head.length > 1000 ? head.slice(0, 1000) + '…' : head);
  form.append('document', new Blob([file], { type: 'text/plain' }), `report-${Date.now()}.txt`);
  const tg = await fetch(`https://api.telegram.org/bot${env.TG_BOT_TOKEN}/sendDocument`, { method: 'POST', body: form });
  if (!tg.ok) return json({ error: 'telegram ' + tg.status + ' ' + (await tg.text()).slice(0, 200) }, 502);
  return json({ ok: true });
}

function s(v) {
  return typeof v === 'string' ? v : '';
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json' } });
}
