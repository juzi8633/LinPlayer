// POST /sentry/hook?key=<SENTRY_HOOK_KEY> —— Sentry 告警 → Telegram。
// 用户不用点任何东西:Sentry 收到新崩溃,告警规则触发,这里转成一条 TG 消息。
//
// 环境变量:TG_BOT_TOKEN / TG_CHAT_ID(和 /api/report 共用)、SENTRY_HOOK_KEY(随便一串长随机数)
// Sentry 侧:项目 Settings → Legacy Integrations → WebHooks,回调地址填上面这个 URL;
// 再建一条 Alert Rule(新 issue / 回归时)动作选「Send a notification via WebHooks」。
//
// 不在 /api/ 下:Sentry 发不了 X-LinPlayer-Key 头,所以用 URL 里的 key 自己校验。

export async function onRequestPost({ env, request }) {
  const url = new URL(request.url);
  if (!env.SENTRY_HOOK_KEY || url.searchParams.get('key') !== env.SENTRY_HOOK_KEY) {
    return new Response('unauthorized', { status: 401 });
  }
  if (!env.TG_BOT_TOKEN || !env.TG_CHAT_ID) return new Response('tg not configured', { status: 503 });

  const b = await request.json().catch(() => ({}));
  // 兼容两种形状:Legacy WebHooks(字段在顶层)和 Internal Integration 的 event_alert(在 data.event)
  const ev = (b.data && b.data.event) || b.event || {};
  const tags = Object.fromEntries((ev.tags || []).filter(Array.isArray));
  const lines = [
    '🔥 Sentry 新崩溃',
    ev.title || b.message || '(无标题)',
    (b.culprit || ev.culprit) && '位置: ' + (b.culprit || ev.culprit),
    (ev.release || tags.release) && '版本: ' + (ev.release || tags.release),
    tags['os.name'] && '系统: ' + tags['os.name'],
    b.url || ev.web_url || '',
  ].filter(Boolean);

  const tg = await fetch(`https://api.telegram.org/bot${env.TG_BOT_TOKEN}/sendMessage`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ chat_id: env.TG_CHAT_ID, text: lines.join('\n').slice(0, 4000), disable_web_page_preview: true }),
  });
  // 回非 2xx 的话 Sentry 会重试,TG 那边挂了时正好
  return new Response(tg.ok ? 'ok' : 'telegram ' + tg.status, { status: tg.ok ? 200 : 502 });
}
