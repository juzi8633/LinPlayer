// POST /sentry/hook —— Sentry 告警 → Telegram。
// 用户不用点任何东西:Sentry 收到新崩溃,告警规则触发,这里转成一条 TG 消息。
//
// 环境变量:TG_BOT_TOKEN / TG_CHAT_ID(和 /api/report 共用),再加下面任一种校验:
//   SENTRY_CLIENT_SECRET  Internal Integration 的 Client Secret(推荐):校验 Sentry-Hook-Signature
//   SENTRY_HOOK_KEY       一串随机数,填进回调地址的 ?key=(Legacy WebHooks 只能用这个)
// Sentry 侧怎么配见 oauth-proxy/README.md「崩溃报告 → Telegram」。
//
// 不在 /api/ 下:Sentry 发不了 X-LinPlayer-Key 头,所以自己校验。

// 明确无关的推送回 200 不打扰 TG。用黑名单不用白名单:新版告警引擎的推送类型名
// 和文档对不上,白名单会把真告警当噪音吞掉 —— 而且回的是 200,Sentry 那边显示「成功」,谁也看不出来
const NOISE = new Set(['installation', 'uninstallation', 'comment', 'seer']);

export async function onRequestPost({ env, request }) {
  const raw = await request.text();
  const resource = request.headers.get('Sentry-Hook-Resource');
  const ok = await authorized(env, request, raw);
  // CF 的实时日志(Pages → Functions → Logs)里看得到;不打任何密钥
  console.log('sentry/hook', JSON.stringify({ resource, auth: ok, signed: !!request.headers.get('Sentry-Hook-Signature'), bytes: raw.length }));
  if (!ok) return new Response('unauthorized', { status: 401 });
  if (!env.TG_BOT_TOKEN || !env.TG_CHAT_ID) return new Response('tg not configured', { status: 503 });

  if (NOISE.has(resource)) return new Response('ignored');

  let b = {};
  try { b = JSON.parse(raw); } catch { /* Sentry 的测试按钮有时发空体,照样回一条测试消息 */ }
  // issue 推送只要「新建」和「复发」,其它(指派、解决…)不转
  if (resource === 'issue' && !['created', 'unresolved'].includes(b.action)) return new Response('ignored');

  // 三种形状:Legacy WebHooks(顶层)、告警规则动作 event_alert(data.event)、issue 推送(data.issue)
  const d = b.data || {};
  const ev = d.event || d.issue || d.error || b.event || {};
  const tags = Object.fromEntries((ev.tags || []).filter(Array.isArray));
  const rule = d.triggered_rule ? '规则: ' + d.triggered_rule : '';
  const lines = [
    b.action === 'unresolved' ? '🔁 Sentry 崩溃复发' : '🔥 Sentry 新崩溃',
    ev.title || b.message || '(无标题 —— 多半是 Sentry 的测试推送)',
    (b.culprit || ev.culprit) && '位置: ' + (b.culprit || ev.culprit),
    (ev.release || tags.release) && '版本: ' + (ev.release || tags.release),
    tags['os.name'] && '系统: ' + tags['os.name'],
    rule,
    b.url || ev.web_url || ev.permalink || '',
  ].filter(Boolean);

  const tg = await fetch(`https://api.telegram.org/bot${env.TG_BOT_TOKEN}/sendMessage`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ chat_id: env.TG_CHAT_ID, text: lines.join('\n').slice(0, 4000), disable_web_page_preview: true }),
  });
  // 回非 2xx 的话 Sentry 会重试,TG 那边挂了时正好
  return new Response(tg.ok ? 'ok' : 'telegram ' + tg.status, { status: tg.ok ? 200 : 502 });
}

async function authorized(env, request, raw) {
  const key = new URL(request.url).searchParams.get('key');
  if (env.SENTRY_HOOK_KEY && key === env.SENTRY_HOOK_KEY) return true;
  const sig = request.headers.get('Sentry-Hook-Signature');
  if (!env.SENTRY_CLIENT_SECRET || !sig) return false;
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(env.SENTRY_CLIENT_SECRET),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(raw)));
  return [...mac].map(x => x.toString(16).padStart(2, '0')).join('') === sig;
}
