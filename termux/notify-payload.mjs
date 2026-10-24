/**
 * 手机通知的「内容规则」——纯函数，桥与 app 侧扩展共用，单测直接覆盖。
 *
 * 两类来源：
 * 1. **pi 自己在等人**：`extension_ui_request`（confirm / select / input / editor）⇒ 人不在屏幕前
 *    也该被叫醒，通知里带会话指针，点了直接落到那条提示上；
 * 2. **agent 主动发**：app 会话里注入的 `pocket_notify` 工具 → 桥的 `POST /v1/notify`。
 *
 * 通知文案守三条：标题短、正文压成一行、任何控制字符都干掉（`am broadcast` 参数里不能带换行）。
 */

/** 单行化 + 截断：`am broadcast --es` 的参数里不能有控制字符。 */
export function oneLine(value, limit = 160) {
  return String(value ?? '')
    .replace(/[\u0000-\u001f\u007f]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, limit);
}

const METHOD_TITLES = {
  confirm: '需要你确认',
  select: '需要你选一个',
  input: '需要你输入',
  editor: '需要你编辑',
};

/**
 * `extension_ui_request` → 通知内容；不是「等用户回应」的记录返回 null。
 *
 * @param {object|null} record pi 的原始记录
 * @returns {{kind: string, title: string, text: string, requestId: string|null}|null}
 */
export function uiPromptNotice(record) {
  if (!record || record.type !== 'extension_ui_request') return null;
  const method = String(record.method || '').trim();
  // 只对**阻塞等人**的四种发通知；set_editor_text / 其它单向通知不发
  if (!METHOD_TITLES[method]) return null;
  const title = oneLine(record.title, 60) || METHOD_TITLES[method];
  const message = oneLine(record.message, 120);
  const options = Array.isArray(record.options) ? record.options.filter((o) => typeof o === 'string' && o.trim()) : [];
  const hint = options.length > 0 ? `（${options.slice(0, 4).join(' / ')}${options.length > 4 ? ' …' : ''}）` : '';
  const text = (message || 'pi 在等你回应') + hint;
  return {
    kind: 'needs-input',
    title,
    text: oneLine(text, 160),
    requestId: typeof record.id === 'string' && record.id ? record.id : null,
  };
}

/**
 * 本地通知的 `am broadcast` 参数。
 *
 * **必须显式指定组件**（`-n id.harness.pipocket/.NotifyReceiver`）：只给 action 的隐式广播从
 * Android 8 起不会投给 manifest 里的静态接收器 —— 2026-10-24 真机实测：隐式广播发出去了但
 * 通知永远不出现（channel 也没建），改成显式后立即出通知。
 */
export function notifyBroadcastArgs({ title, text, file, kind }) {
  const esc = (v) => String(v ?? '').replace(/[\u0000-\u001f]/g, ' ').slice(0, 300);
  const argv = ['broadcast', '-a', 'id.harness.pipocket.NOTIFY', '-n', 'id.harness.pipocket/.NotifyReceiver'];
  if (title) argv.push('--es', 'title', esc(title));
  if (text) argv.push('--es', 'text', esc(text));
  if (file) argv.push('--es', 'file', esc(file));
  if (kind) argv.push('--es', 'kind', esc(kind));
  return argv;
}

/** 一个 `(file, requestId)` 只通知一次：pi 可能重发同一个请求（重连/重放）。 */
export function shouldNotifyOnce(seen, key, at = Date.now(), windowMs = 60_000) {
  if (!key) return true;
  const last = seen.get(key);
  if (last !== undefined && at - last < windowMs) return false;
  seen.set(key, at);
  if (seen.size > 200) {
    // 先剔过期的；还超就按时间丢最旧的（长会话里不能无限长）
    const cutoff = at - windowMs;
    for (const [k, time] of seen) if (time < cutoff) seen.delete(k);
    if (seen.size > 200) {
      const oldest = [...seen.entries()].sort((a, b) => a[1] - b[1]).slice(0, seen.size - 200);
      for (const [k] of oldest) seen.delete(k);
    }
  }
  return true;
}

/**
 * 桥的 `POST /v1/notify` 请求体 → 规整后的通知内容（`undefined` = 拒绝）。
 * agent 自己发的通知：标题/正文都可由调用方给，但都过一遍单行化与长度限制。
 */
export function customNotifyPayload(body) {
  const title = oneLine(body?.title, 60) || 'pi 通知';
  const text = oneLine(body?.text ?? body?.message, 160);
  const kind = oneLine(body?.kind, 24) || 'note';
  if (!text && title === 'pi 通知') return undefined;
  return { kind, title, text };
}

/**
 * 桌面/服务器上跑桥时：用 **ntfy** 推给手机（和 `pi-notify` 共用 `~/.pi/agent/pi-notify.json` 的 phone 段）。
 *
 * 为什么需要：`am broadcast` 只在 Android 上存在；host-a/WSL 上的会话想叫人，只能走推送。
 * 没配 topic / 被关掉就返回 null（调用方回 `sent:false`，不报错）。
 */
export function ntfyPublishRequest(config, { title, text, kind } = {}, env = {}) {
  const phone = config?.phone || {};
  const enabled = phone.enabled !== false;
  const serverUrl = String(env.PI_POCKET_NTFY_URL || phone.serverUrl || '').trim();
  const topic = String(env.PI_POCKET_NTFY_TOPIC || phone.topic || '').trim();
  const token = String(env.PI_POCKET_NTFY_TOKEN || phone.token || '').trim();
  if (!enabled || !serverUrl || !topic) return null;
  const base = serverUrl.replace(/\/+$/, '');
  const params = new URLSearchParams();
  const cleanTitle = oneLine(title, 60);
  if (cleanTitle) params.set('title', cleanTitle);
  if (kind) params.set('tags', kind === 'needs-input' ? 'question' : 'speech_balloon');
  const query = params.toString();
  const headers = { 'content-type': 'text/plain; charset=utf-8' };
  if (token) headers.authorization = 'Bearer ' + token;
  return {
    url: `${base}/${encodeURIComponent(topic)}${query ? '?' + query : ''}`,
    headers,
    body: oneLine(text, 400) || cleanTitle || 'pi',
  };
}

/**
 * app 会话里注入的 `pocket_notify` 工具 → 桥的 HTTP 请求参数。
 *
 * 端口/口令都从 pi 子进程环境里取（桥 spawn pi 时继承了 `PI_POCKET_PORT` / `PI_POCKET_TOKEN`），
 * 所以插件不需要另配凭据；缺端口按 8789（桥的默认）。
 */
export function bridgeNotifyRequest(env, args) {
  const port = String(env?.PI_POCKET_PORT || '8789').trim() || '8789';
  const token = String(env?.PI_POCKET_TOKEN || '').trim();
  const title = oneLine(args?.title, 60);
  const text = oneLine(args?.text ?? args?.message, 160);
  const body = { title: title || 'pi 通知', text };
  if (args?.kind) body.kind = oneLine(args.kind, 24);
  const headers = { 'content-type': 'application/json' };
  if (token) headers.authorization = 'Bearer ' + token;
  return { url: `http://127.0.0.1:${port}/v1/notify`, headers, body };
}

/**
 * 一轮结束时该发什么通知（纯函数，桥在 `agent_end` 调）。
 *
 * 用户 2026-10-24：报错也弹「回答完成」是错的 —— 三种结局必须分得开。
 * 依据（pi 自带文档 `docs/json.md` / `docs/message-types.md`）：
 *  · `agent_end` 只有 `messages` / `willRetry`，**既没文本也没结局**；
 *  · 结局在上一份 `message_end.message` 上：`stopReason`（pending|stop|length|toolUse|error|aborted|deferred）
 *    与 `errorMessage`。
 * 所以调用方要从最后一条 assistant 消息里取 `stopReason` / `errorMessage` 传进来。
 *
 * @param {{abortedByUser?: boolean, aborted?: boolean, stopReason?: string, errorMessage?: string, text?: string}} input
 * @returns {{kind: string, title: string, text: string}}
 */
export function turnEndNotice({ abortedByUser, aborted, stopReason, errorMessage, text } = {}) {
  const reason = String(stopReason || '').trim();
  if (abortedByUser === true || aborted === true || reason === 'aborted') {
    return { kind: 'stopped', title: '已停止', text: oneLine(text, 120) || '这一轮已停止' };
  }
  if (reason === 'error') {
    // 失败时 assistant 的正文通常是空的/半句 ⇒ 正文用错误原因，别拿“回答摘要”冒充
    return { kind: 'error', title: '出错了', text: oneLine(errorMessage, 160) || '这一轮出错了（没拿到回答）' };
  }
  return { kind: 'turn-end', title: '回答完成', text: oneLine(text, 120) || '这一轮答完了' };
}
