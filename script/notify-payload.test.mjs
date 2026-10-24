// 手机通知的内容规则（纯函数）：桥在「等用户回应」与「agent 主动发通知」两处用它。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  oneLine,
  uiPromptNotice,
  shouldNotifyOnce,
  customNotifyPayload,
  bridgeNotifyRequest,
  notifyBroadcastArgs,
  ntfyPublishRequest,
  turnEndNotice,
} from '../termux/notify-payload.mjs';

test('oneLine flattens newlines and control chars', () => {
  assert.equal(oneLine('a\nb\tc'), 'a b c');
  assert.equal(oneLine('  x   y  '), 'x y');
  assert.equal(oneLine(null), '');
  assert.equal(oneLine('x'.repeat(300), 10), 'x'.repeat(10));
});

test('uiPromptNotice covers the four blocking methods only', () => {
  for (const method of ['confirm', 'select', 'input', 'editor']) {
    const notice = uiPromptNotice({ type: 'extension_ui_request', method, id: 'r1', message: '继续吗？' });
    assert.ok(notice, method);
    assert.equal(notice.kind, 'needs-input');
    assert.equal(notice.requestId, 'r1');
    assert.match(notice.text, /继续吗/);
  }
  // 单向通知（给编辑器塞文本）不是“等回应”，不叫醒人
  assert.equal(uiPromptNotice({ type: 'extension_ui_request', method: 'set_editor_text', text: 'x' }), null);
  assert.equal(uiPromptNotice({ type: 'agent_end' }), null);
  assert.equal(uiPromptNotice(null), null);
});

test('uiPromptNotice summarises options and falls back to a generic sentence', () => {
  const notice = uiPromptNotice({
    type: 'extension_ui_request',
    method: 'select',
    id: 'r2',
    options: ['Yes', 'No', 'Cancel', 'Other', 'Fifth'],
  });
  assert.equal(notice.title, '需要你选一个');
  assert.match(notice.text, /Yes \/ No \/ Cancel \/ Other …/);
  assert.doesNotMatch(notice.text, /Fifth/);
});

test('uiPromptNotice keeps titles short and single line', () => {
  const notice = uiPromptNotice({
    type: 'extension_ui_request',
    method: 'confirm',
    id: 'r3',
    title: 'title\nwith newline and a very long tail that should be trimmed at some point because broadcasts cannot carry it',
  });
  assert.ok(!notice.title.includes('\n'));
  assert.ok(notice.title.length <= 60);
});

test('shouldNotifyOnce dedupes within the window and prunes old keys', () => {
  const seen = new Map();
  assert.equal(shouldNotifyOnce(seen, 'a', 1000), true);
  assert.equal(shouldNotifyOnce(seen, 'a', 2000), false);          // 同一请求不重复叫
  assert.equal(shouldNotifyOnce(seen, 'a', 1000 + 60_001), true);  // 过了窗口可以再叫
  assert.equal(shouldNotifyOnce(seen, null, 100_000), true);       // 无 key 不拦
  // 大量 key 后只留最近的
  for (let i = 0; i < 260; i++) shouldNotifyOnce(seen, 'k' + i, 200_000 + i);
  assert.ok(seen.size <= 200);
});

test('customNotifyPayload trims and rejects empty', () => {
  const payload = customNotifyPayload({ title: '  等你拍板 ', text: '  方案 A 还是 B  \n选一个' });
  assert.equal(payload.title, '等你拍板');
  assert.equal(payload.text, '方案 A 还是 B 选一个');
  assert.equal(payload.kind, 'note');
  assert.equal(customNotifyPayload({}), undefined);
  assert.equal(customNotifyPayload({ title: 'pi 通知' }), undefined);
});

test('notifyBroadcastArgs targets the receiver explicitly', () => {
  // 2026-10-24 真机：隐式广播（只给 action）不会投给 manifest 接收器 → 通知永远不到。
  const argv = notifyBroadcastArgs({ title: 't', text: 'x', file: '/s.jsonl', kind: 'done' });
  assert.equal(argv[0], 'broadcast');
  assert.equal(argv[argv.indexOf('-a') + 1], 'id.harness.pipocket.NOTIFY');
  assert.equal(argv[argv.indexOf('-n') + 1], 'id.harness.pipocket/.NotifyReceiver');
  assert.ok(argv.includes('--es'));
  // 控制字符不能带进参数
  const dirty = notifyBroadcastArgs({ title: 'a\nb', text: 'c\td' });
  const titleIdx = dirty.indexOf('title');
  assert.equal(dirty[titleIdx + 1], 'a b');
  assert.equal(dirty[dirty.indexOf('text') + 1], 'c d');
});

test('ntfyPublishRequest builds a push request for non-Android hosts', () => {
  const cfg = { phone: { enabled: true, serverUrl: 'https://ntfy.sh/', topic: 'topic-abc' } };
  const req = ntfyPublishRequest(cfg, { title: '需要你确认\n第二行', text: '选 A 还是 B', kind: 'needs-input' });
  assert.ok(req);
  assert.ok(req.url.startsWith('https://ntfy.sh/topic-abc?'));
  assert.match(req.url, /title=/);
  assert.doesNotMatch(req.url, /%0A/);            // 换行已被压平，不会进 header/query
  assert.equal(req.body, '选 A 还是 B');
  assert.equal(req.headers.authorization, undefined);

  // token 有就带 Bearer；topic 要 URL 编码
  const withToken = ntfyPublishRequest(
    { phone: { enabled: true, serverUrl: 'https://ntfy.example', topic: 'a b/c', token: 't0k' } },
    { text: 'x' },
  );
  assert.equal(withToken.headers.authorization, 'Bearer t0k');
  assert.match(withToken.url, /\/a%20b%2Fc/);

  // 关掉 / 没 topic / 没 serverUrl → null（调用方回 sent:false，不报错）
  assert.equal(ntfyPublishRequest({ phone: { enabled: false, serverUrl: 'https://ntfy.sh', topic: 'x' } }, { text: 'x' }), null);
  assert.equal(ntfyPublishRequest({ phone: { enabled: true, serverUrl: 'https://ntfy.sh' } }, { text: 'x' }), null);
  assert.equal(ntfyPublishRequest(null, { text: 'x' }), null);

  // 环境变量可覆盖配置（排障用）
  assert.equal(
    ntfyPublishRequest({ phone: {} }, { text: 'x' }, { PI_POCKET_NTFY_URL: 'https://n.example', PI_POCKET_NTFY_TOPIC: 'tk' }).url.startsWith('https://n.example/tk'),
    true,
  );
});

test('bridgeNotifyRequest reads port and token from the bridge env', () => {
  const req = bridgeNotifyRequest({ PI_POCKET_PORT: '8790', PI_POCKET_TOKEN: 'tok' }, { title: 't', text: 'x' });
  assert.equal(req.url, 'http://127.0.0.1:8790/v1/notify');
  assert.equal(req.headers.authorization, 'Bearer tok');
  assert.deepEqual(req.body, { title: 't', text: 'x' });

  // 没设端口走默认 8789；没 token 就不带 Authorization
  const plain = bridgeNotifyRequest({}, { text: 'x' });
  assert.equal(plain.url, 'http://127.0.0.1:8789/v1/notify');
  assert.equal(plain.headers.authorization, undefined);
  assert.equal(plain.body.title, 'pi 通知');
});

test('turnEndNotice distinguishes 回答完成 / 已停止 / 出错了', () => {
  // 正常答完：正文是最后一条 assistant 正文压成一行，过长的截断
  const done = turnEndNotice({ stopReason: 'stop', text: '改好了\n共 3 处' });
  assert.deepEqual(done, { kind: 'turn-end', title: '回答完成', text: '改好了 共 3 处' });
  assert.equal(turnEndNotice({ stopReason: 'toolUse', text: 'x'.repeat(200) }).text.length, 120);
  // 没正文（比如只调了工具）也不能发空通知
  assert.equal(turnEndNotice({ stopReason: 'stop' }).text, '这一轮答完了');

  // 用户在本 app 里停的 / pi 自己说 aborted / agent_end 带 aborted ⇒ 已停止
  for (const input of [
    { abortedByUser: true, stopReason: 'aborted' },
    { stopReason: 'aborted' },
    { aborted: true },
  ]) {
    const notice = turnEndNotice(input);
    assert.equal(notice.kind, 'stopped', JSON.stringify(input));
    assert.equal(notice.title, '已停止');
  }

  // 报错：以前桥不看 stopReason ⇒ 报「回答完成」（用户 2026-10-24 的投诉）。正文用 errorMessage。
  const failed = turnEndNotice({ stopReason: 'error', errorMessage: 'no response body\xff', text: '' });
  assert.equal(failed.kind, 'error');
  assert.equal(failed.title, '出错了');
  assert.match(failed.text, /no response body/);
  assert.equal(turnEndNotice({ stopReason: 'error' }).text, '这一轮出错了（没拿到回答）');

  // 参数缺失：不能抛
  assert.equal(turnEndNotice().title, '回答完成');
});
