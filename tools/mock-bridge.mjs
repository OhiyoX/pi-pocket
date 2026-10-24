#!/usr/bin/env node
/**
 * **视觉验收用的假桥**（不改 pi、不连真机、不花额度）。
 *
 * 为什么要它：Markdown / 输入区 / 表格这类**视觉**改动需要反复看截图，
 * 走真 pi 每轮都要等模型 + 会往会话目录里落测试会话；这个 mock 直接回一份**固定转录**，
 * 于是模拟器上每改一次都能在几秒内重看一眼。
 *
 * 用法（模拟器里用 10.0.2.2 指向宿主机 loopback）：
 *   node tools/mock-bridge.mjs --port 8799
 *   adb -s emulator-5556 shell am broadcast ... --es action setconfig --es url http://10.0.2.2:8799
 *   adb -s emulator-5556 shell am broadcast ... --es action openfirst
 *
 * 覆盖的视觉面：标题（H1/H2/H3）、引用、无序/有序列表、行内码、链接、代码块、
 * 表格（中英混排 + 宽表）、分隔线、用户消息带图（回看）、工具块（成功/失败）、提醒。
 */

import http from 'node:http';

const argv = process.argv.slice(2);
const portArg = argv.indexOf('--port');
const PORT = portArg >= 0 ? Number(argv[portArg + 1]) : 8799;

const nowIso = () => new Date().toISOString();
const T0 = Date.parse('2026-10-19T00:00:00Z');

/** 1×1 红点 + 8×8 蓝块的 PNG（base64）—— 用来验证消息里的图能画出来。 */
// 96×96 三色样例图（红底 + 蓝方 + 黄圆）：够大够显眼，验证“缩图能画出来”不会看不清
const SAMPLE_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAABPklEQVR42u3csRGCQBAFUGEoxshKCCzBwLIMLMGASowtxsBMMFC5vQXehirM7Lu/w8wdY/PY73fqc7UIAAECBAgQIECAFCBAgAABArSu6r69oD8OS+95uPUSZMQAAQIECJACBAgQIECAAAHa2Q+qW/fzafzh4XLdOtCky/jbilJdTprJH1dhavPr/HnVwhL0Z5PxUWoXpFMlSh7zaYDmXfawELVL1Ik0MmIJgMotdUCIJAhQcqDSU1D6/hIECBAgQIAAfazSezel7y9BgPIDlZuCgL1XCcoBVGKpY7bu4xI0bz9hBxtGLBPQXMseeS4WfXD46u3nTZz40+c6I/Zbn1XO5qu9vPBVlLb4dsdb594PypsRj3lAgAABAgQIkAIECBAgQIAAbaoaf7gtQYAAAQIECBAgBQgQIECAAK2rnsufSZMYh2pEAAAAAElFTkSuQmCC';


/** app 会话注入样例（对齐真桥 /v1/app-profile 的返回形状）。 */
const APP_PROFILE_STATE = {
  enabled: true,
  appendPrompt: { id: 'appendPrompt', enabled: true, present: true },
  skills: [
    {
      id: 'zcode-visualize',
      name: 'visualize',
      description: '对话内作图：静态结构用 mermaid、需要交互/图表用 html-visual',
      enabled: true,
    },
  ],
  mcp: [{ id: 'health', name: 'health', description: 'HTTP server（样例）', enabled: true, remote: true }],
};

const MARKDOWN = [
  '# Weekly review',
  '',
  '## Reading pipeline — week 42',
  '',
  'Three notes moved forward; one is blocked on a citation.',
  '',
  '| Note | Status | Change |',
  '| --- | --- | --- |',
  '| Attention Is All You Need | done | summary + 4 links |',
  '| Designing Data-Intensive Applications | reading | ch. 5–7 |',
  '| Local-first software | blocked | needs citation |',
  '',
  '- **Finished:** 1',
  '- **Active:** 1',
  '- **Blocked:** 1',
  '',
  '### The one thing to fix',
  '',
  'Add the missing citation to **Local-first software**. That unblocks the next review without touching anything else.',
  '',
  '> The blocked note can move as soon as its source is linked.',
  '',
  '| key | value | note | extra |',
  '| --- | --- | --- | --- |',
  '| `PI_POCKET_PORT` | 8789 | bridge port | default |',
  '| `PI_POCKET_HOST` | 127.0.0.1 | bind address | loopback |',
  '',
  '```mermaid',
  'flowchart LR',
  '  Capture --> Triage --> Read --> Summarize --> File',
  '```',
  '',
  '---',
  '',
  'That is the whole review.',
].join('\n');

const messages = [
  { role: 'user', timestamp: T0, content: "Review this week's reading pipeline. Keep it short, show a table, and tell me the one thing to fix." },
  {
    role: 'user',
    timestamp: T0 + 1000,
    content: [
      { type: 'text', text: 'Also check the image and the local-first note in the reading folder.' },
      { type: 'image', mimeType: 'image/png', data: SAMPLE_PNG },
      { type: 'image', mimeType: 'image/png', omitted: true, bytes: 12345 },
    ],
  },
  {
    role: 'assistant',
    timestamp: T0 + 2000,
    content: [
      // thinking 也要带样式：pi 是按 Markdown 渲染 thinking 的（加粗/行内代码/列表都显示）
      { type: 'thinking', thinking: 'I will read the weekly queue, compare done vs blocked notes, then pick the single highest-leverage fix. Keep the answer short — one table and one action.' },
      { type: 'text', text: MARKDOWN },
    ],
    model: 'mock/mock-flash',
    usage: { totalTokens: 12345, cost: { total: 0.0012 } },
  },
  {
    role: 'assistant',
    timestamp: T0 + 3000,
    content: [
      { type: 'toolCall', id: 't-ok', name: 'bash', arguments: { command: 'ls -t _Workspace/20-Knowledge/Reading | head -5' } },
    ],
  },
  {
    role: 'toolResult',
    toolCallId: 't-ok',
    toolName: 'bash',
    isError: false,
    timestamp: T0 + 3100,
    content: [{ type: 'text', text: 'attention-is-all-you-need.md\ndesigning-data-intensive-applications.md\nlocal-first-software.md\nreading-queue.base\nweekly-review.md' }],
  },
  {
    role: 'assistant',
    timestamp: T0 + 3200,
    content: [
      { type: 'toolCall', id: 't-bad', name: 'bash', arguments: { command: 'rg -n "citation" _Workspace/20-Knowledge/Reading' } },
    ],
  },
  {
    role: 'toolResult',
    toolCallId: 't-bad',
    toolName: 'bash',
    isError: true,
    timestamp: T0 + 3300,
    content: [{ type: 'text', text: 'exit_code=1\nno matches found' }],
  },
  // read 一张图片：toolResult.content 里带 type:"image"（pi 的 message-types.md 就是这个形状），
  // app 应该把它当缩图画出来，点开能全屏看（否则只能看到“[图片]”两个字）
  {
    role: 'assistant',
    timestamp: T0 + 3400,
    content: [
      { type: 'toolCall', id: 't-img', name: 'read', arguments: { path: '/home/demo/workspace/reading-map.png' } },
    ],
  },
  {
    role: 'toolResult',
    toolCallId: 't-img',
    toolName: 'read',
    isError: false,
    timestamp: T0 + 3500,
    content: [
      { type: 'text', text: 'read image: /home/demo/workspace/reading-map.png (96x96)' },
      { type: 'image', mimeType: 'image/png', data: SAMPLE_PNG },
    ],
  },
  // read **文本**：用户口径（2026-10-22）—— 文本和图像都要能点开看。
  // 以前只有图片有入口，read 读文本压根没有（卡片里只能看被截断的两行）。
  {
    role: 'assistant',
    timestamp: T0 + 3600,
    content: [
      { type: 'toolCall', id: 't-txt', name: 'read', arguments: { path: '/home/demo/workspace/notes/local-first.md' } },
    ],
  },
  {
    role: 'toolResult',
    toolCallId: 't-txt',
    toolName: 'read',
    isError: false,
    timestamp: T0 + 3700,
    content: [
      {
        type: 'text',
        text: '1→# 笔记\n2→第一行正文，长到会被卡片截断的那种……\n3→第二行正文\n4→（下面还有很多行）',
      },
    ],
  },
  {
    role: 'assistant',
    timestamp: T0 + 3800,
    content: [
      { type: 'text', text: 'The blocked note is missing its source link. I left everything else untouched — add the citation and the pipeline is clean for next week.' },
    ],
    model: 'mock/mock-flash',
    usage: { totalTokens: 13200, cost: { total: 0.0014 } },
  },
];

const session = {
  file: '/mock/sessions/demo-markdown.jsonl',
  id: 'demo-markdown',
  cwd: '/home/demo/aurora-notes',
  name: 'Weekly review · reading pipeline',
  updatedAt: nowIso(),
  bytes: 4096,
  createdAt: new Date(T0).toISOString(),
};

// Synthetic session list so the demo shows grouping across projects.
const demoSessions = [
  session,
  {
    ...session,
    file: '/mock/sessions/demo-tool-output.jsonl',
    id: 'demo-tool-output',
    cwd: '/home/demo/photo-catalog',
    name: 'Tool output · failure and retry',
    bytes: 8192,
    updatedAt: new Date(T0 + 3 * 3600 * 1000).toISOString(),
  },
  {
    ...session,
    file: '/mock/sessions/demo-shell.jsonl',
    id: 'demo-shell',
    cwd: '/home/demo/workbench',
    name: 'Shell · bridge health check',
    bytes: 2048,
    updatedAt: new Date(T0 + 5 * 3600 * 1000).toISOString(),
  },
];

const info = {
  state: {
    sessionId: session.id,
    sessionFile: session.file,
    sessionName: session.name,
    model: { id: 'mock-flash', provider: 'mock' },
    thinkingLevel: 'high',
    isStreaming: false,
  },
  cwd: session.cwd,
  levels: ['off', 'low', 'high'],
  stats: {
    contextUsage: { percent: 12.5, tokens: 62000, contextWindow: 500000 },
    // 四项分开（真桥的 SessionStats.tokens 就是这么给的）：页面上要显示 `↑↓ R/W CH%`
    tokens: { input: 4200, output: 910, cacheRead: 6800, cacheWrite: 435, total: 12345 },
    cost: 0.0012,
    userMessages: 2,
    assistantMessages: 4,
    toolCalls: 2,
  },
};

// 故意放长名字：验证"模型名最多两行"和搜索（用户 2026-10-22）
const models = [
  { id: 'claude-sonnet-4-5-20250929', provider: 'anthropic', label: 'Claude Sonnet 4.5 (2025-09-29)' },
  { id: 'claude-haiku-4', provider: 'anthropic', label: 'Claude Haiku 4' },
  { id: 'deepseek-v4.1-flash-thinking', provider: 'deepseek', label: 'DeepSeek V4.1 Flash Thinking' },
  { id: 'gemini-3.6-pro-preview-11-2025', provider: 'google', label: 'Gemini 3.6 Pro Preview' },
  { id: 'gpt-5-mini-high-reasoning', provider: 'openai', label: 'GPT-5 mini (high)' },
  { id: 'mock-flash', provider: 'mock', name: 'Mock Flash' },
  { id: 'mock-pro', provider: 'mock', name: 'Mock Pro' },
];

const commands = [
  { name: '/model', description: '切换模型' },
  { name: '/mode', description: '权限模式 build/edit/plan/yolo' },
  { name: '/compact', description: '压缩上下文' },
];

const slashCommands = [
  { name: '/model', args: true, argsHint: '<model>', rpc: 'set_model', description: '切换模型', available: true },
  { name: '/mode', args: true, argsHint: '<mode>', rpc: 'set_mode', description: '权限模式 build/edit/plan/yolo', available: true },
  { name: '/compact', args: false, argsHint: '', rpc: 'compact', description: '压缩上下文', available: true },
];

const ok = (data) => JSON.stringify({ ok: true, data });

// ── /v1/providers 的 mock 数据（不落盘，只用于 UI 联调）──────────────────────────────
const MOCK_PROVIDERS = {
  'mock-official': {
    baseUrl: 'https://api.example.com/v1',
    api: 'openai-completions',
    models: [
      { id: 'mock-chat', name: 'Mock Chat', contextWindow: 128000, maxTokens: 8192 },
      { id: 'mock-reason', name: 'Mock Reasoner', contextWindow: 200000, maxTokens: 16384, thinkingLevels: ['low', 'high'] },
    ],
    hasKey: true,
  },
  'mock-local': {
    baseUrl: 'http://10.0.2.2:11434/v1',
    api: 'openai-completions',
    models: [{ id: 'qwen3:8b', name: 'qwen3:8b', contextWindow: 32768 }],
    hasKey: false,
  },
};

// discover 返回的形状：模型 id + 参数（contextWindow/maxTokens/thinkingLevels）
const MOCK_DISCOVERED = [
  { id: 'mock-chat', name: 'Mock Chat', contextWindow: 128000, maxTokens: 8192 },
  { id: 'mock-reason', name: 'Mock Reasoner', contextWindow: 200000, maxTokens: 16384, thinkingLevels: ['low', 'high'] },
  { id: 'mock-embed', name: 'Mock Embedding', contextWindow: 8192, maxTokens: 0 },
];
const clients = new Set();

/**
 * 会话树的假数据（形状照 pi 的 `SessionEntry` 投影：`GET /v1/tree?raw=0`）。
 *
 * 造一棵**有分叉**的小树，才能验到连接线：
 * ```
 * u1 2026-10-22T10-00-00
 * ├─ a1（assistant 回）+ 一个 read 调用 + 结果
 * │  └─ u2
 * └─ a1b（另一个分支 ⇒ u1 这一层要画 ├─ / └─）
 * ```
 * 另外塞一条 `thinking_level_change`（默认过滤档下应该看不到）与一条 compaction（应该看得到）。
 */
/** app 上报的设备信息（`/v1/device`），用 `/mock/device` 看 */
let lastDevice = null;

/** 让 `/v1/prompt` 人为失败（验「发送失败保留草稿」）。`/mock/fail?prompt=1` 打开，0 关掉。 */
let failPrompt = false;

/**
 * `/v1/messages` 默认把图数据剔成 `omitted`（对齐真桥：历史不带 base64）。
 * 打开这个开关就不剔 —— 用来复现「实时事件带图数据」那一路（工具卡不该内联缩略图，issue #40）。
 * 用法：`curl 'http://127.0.0.1:8799/mock/images?full=1'`
 */
let keepImages = false;

/** 行为开关的 mock 状态（字段与真桥 `/v1/behavior` 一致）。 */
const BEHAVIOR = {
  thinking: 'high',
  steeringMode: 'one-at-a-time',
  followUpMode: 'one-at-a-time',
  autoCompaction: true,
  autoRetry: true,
  isStreaming: false,
  isCompacting: false,
  pendingMessageCount: 0,
  messageCount: 42,
  retry: { maxRetries: 5, baseDelayMs: 1000 },
};


/** 造一棵 N 条的假树（形状同 /v1/tree 投影：id/parentId/type/role/text/label…）。 */
function bigTree(count) {
  const nodes = [];
  let parent = null;
  let lastUser = null;
  for (let i = 0; i < count; i++) {
    const id = 'E' + String(i).padStart(5, '0');
    const ts = new Date(Date.UTC(2026, 9, 22, 0, 0, 0) + i * 1000).toISOString();
    const kind = i % 6;
    if (kind === 0) {
      nodes.push({ id, parentId: parent, type: 'message', role: 'user', text: `第 ${i} 条用户消息（大树基准）`, timestamp: ts, label: i % 60 === 0 ? '里程碑 ' + i : null });
      lastUser = id;
    } else if (kind === 1) {
      nodes.push({ id, parentId: parent, type: 'message', role: 'assistant', text: `第 ${i} 条回复`, timestamp: ts, toolCalls: [{ id: 'C' + i, name: 'read', argsText: `{"path":"/mock/f${i}.md"}` }] });
    } else if (kind === 2) {
      nodes.push({ id, parentId: parent, type: 'message', role: 'toolResult', toolName: 'read', toolCallId: 'C' + (i - 1), text: '…', timestamp: ts });
    } else if (kind === 3) {
      nodes.push({ id, parentId: parent, type: 'thinking_level_change', thinkingLevel: 'high', timestamp: ts });
    } else if (kind === 4) {
      nodes.push({ id, parentId: parent, type: 'compaction', tokensBefore: 42000, summary: '压缩', timestamp: ts });
    } else {
      nodes.push({ id, parentId: lastUser || parent, type: 'message', role: 'assistant', text: `第 ${i} 条（另一个分支）`, timestamp: ts });
    }
    parent = id;
  }
  return { leafId: parent, nodes };
}

const TREE = {  leafId: 'a2',
  nodes: [
    { id: 'u1', parentId: null, type: 'message', role: 'user', text: '把 mermaid 的字号改对', timestamp: '2026-10-22T10:00:00.000Z', label: null },
    { id: 'a1', parentId: 'u1', type: 'message', role: 'assistant', text: '先看一下正文用的 sp', timestamp: '2026-10-22T10:00:30.000Z',
      toolCalls: [{ id: 'c1', name: 'read', argsText: '{"path":"ui/Mermaid.kt"}' }] },
    { id: 'r1', parentId: 'a1', type: 'message', role: 'toolResult', toolName: 'read', toolCallId: 'c1', text: '…文件内容…', timestamp: '2026-10-22T10:00:31.000Z' },
    { id: 't1', parentId: 'r1', type: 'thinking_level_change', thinkingLevel: 'high', timestamp: '2026-10-22T10:00:35.000Z' },
    { id: 'u2', parentId: 't1', type: 'message', role: 'user', text: '改完再截个图', timestamp: '2026-10-22T10:01:00.000Z' },
    { id: 'a2', parentId: 'u2', type: 'message', role: 'assistant', text: '好，已经按 fontBodySp 改了', timestamp: '2026-10-22T10:01:30.000Z' },
    { id: 'a1b', parentId: 'u1', type: 'message', role: 'assistant', text: '（另一个分支：先查主题再说）', timestamp: '2026-10-22T10:02:00.000Z' },
    { id: 'cp1', parentId: 'a1b', type: 'compaction', tokensBefore: 48000, summary: '压缩了前面的调试过程', timestamp: '2026-10-22T10:03:00.000Z' },
  ],
};
let queue = { steering: [], followUp: [] };

function route(req, res, body) {
  const url = new URL(req.url, 'http://mock');
  const path = url.pathname;
  const t0 = Date.now();
  res.on('finish', () => console.log(`${req.method} ${path} ${res.statusCode} ${Date.now() - t0}ms`));
  const send = (payload, code = 200, type = 'application/json') => {
    res.writeHead(code, { 'content-type': type });
    res.end(payload);
  };

  if (path === '/v1/health') {
    return send(ok({
      ok: true, pid: process.pid, sourceSha: null, bridge: 'mock-0.1.0', host: 'demo-phone', platform: 'linux', arch: 'x64',
      pi: 'pi', cwd: session.cwd, sessionsRoot: '/mock/sessions',
      bind: `127.0.0.1:${PORT}`, worker: true, subscribers: 0, auth: false,
    }));
  }
  if (path === '/v1/sessions') return send(ok({ sessions: demoSessions }));
  if (path === '/v1/messages') {
    // full=1 时把被省掉的图补回来（验证「点开加载历史图」这条路）；`/mock/images?full=1` 同理（issue #40）
    const full = url.searchParams.get('full') === '1' || keepImages;
    const list = JSON.parse(JSON.stringify(messages));
    if (!full) {
      for (const m of list) {
        if (!Array.isArray(m.content)) continue;
        m.content = m.content.map((p) => (p.type === 'image' && p.data
          ? { type: 'image', mimeType: p.mimeType, omitted: true, bytes: Math.floor(p.data.length * 3 / 4) }
          : p));
      }
    }
    return send(ok({ messages: list }));
  }
  if (path === '/v1/info') {
    // 与真桥一致：`get_state` + stats + git 分支（分支读的是 `.git/HEAD` 的口径，这里给常量）
    return send(ok({ ...info, gitBranch: 'mock-branch' }));
  }
  if (path === '/v1/models') return send(ok({ models }));

  // ── /v1/providers 家族（对齐真桥）：给「Pi Provider」UI 做无手机联调 ─────────────────
  // 口径：列表回 hasKey 但**永不回显 key**；discover 只返回模型与参数，不落盘。
  if (path === '/v1/providers' && req.method === 'GET') {
    return send(ok({
      providers: Object.entries(MOCK_PROVIDERS).map(([name, p]) => ({
        name, baseUrl: p.baseUrl, api: p.api, models: p.models.map((m) => m.id),
        modelParams: p.models, hasKey: p.hasKey, authType: p.hasKey ? 'api_key' : null,
      })),
      agentDir: '/mock/.pi/agent',
    }));
  }
  if (path === '/v1/providers/discover' && req.method === 'POST') {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    return req.on('end', () => {
      let body = {};
      try { body = JSON.parse(raw || '{}'); } catch (e) { /* 当空体处理 */ }
      const key = String(body.apiKey || MOCK_PROVIDERS[body.name]?.hasKey ? 'x' : '');
      if (body.baseUrl && String(body.baseUrl).includes('401')) {
        return send(fail('401 未授权：key 不对或没有权限'), 200);
      }
      if (body.baseUrl && String(body.baseUrl).includes('404')) {
        return send(fail('404：该 provider 不实现 /models，请手动填写模型 id'), 200);
      }
      if (!key) return send(fail('缺少 apiKey，且该 provider 尚未保存过 key'), 200);
      return send(ok({
        models: MOCK_DISCOVERED,
        source: 'GET ' + String(body.baseUrl || 'https://api.example.com/v1') + '/models',
      }));
    });
  }
  if (path === '/v1/slash') return send(ok({ commands: slashCommands }));
  if (path === '/v1/commands') return send(ok({ commands }));

  // ── 会话树 / 分叉（对齐真桥的 /v1/tree · fork-messages · fork · clone）──────────
  // mock 里特意造一棵**有分叉**的小树：u1 → (a1 → u2 → a2) 与 (a1b)，验证连接线与「你在这」。
  if (path === '/v1/tree') {
    // MOCK_TREE_NODES=3000 时造一棵大树（量 UI 在真实大会话上的表现，不用拿用户手机试）
    const n = Number(process.env.MOCK_TREE_NODES || 0);
    if (n > 0) return send(ok(bigTree(n)));
    return send(ok({ leafId: TREE.leafId, nodes: TREE.nodes }));
  }
  if (path === '/v1/fork-messages') {
    return send(ok({
      messages: TREE.nodes
        .filter((n) => n.type === 'message' && n.role === 'user')
        .map((n) => ({ entryId: n.id, text: n.text })),
    }));
  }
  if (path === '/v1/fork' || path === '/v1/clone') {
    let payload = {};
    try { payload = JSON.parse(body || '{}'); } catch { /* 空体 */ }
    const kind = path.endsWith('clone') ? 'clone' : 'fork';
    if (kind === 'fork' && !payload.entryId) return send(fail('fork 需要 entryId'), 200);
    const picked = TREE.nodes.find((n) => n.id === payload.entryId);
    // 模拟「分叉后是新会话文件」（真桥会 rebind；app 得跟着切过去）
    const newFile = '/mock/sessions/2026-10-22T12-00-00_fork-' + kind + '.jsonl';
    return send(ok({
      kind,
      cancelled: false,
      text: picked ? picked.text : '',
      file: newFile,
      state: { sessionFile: newFile },
    }));
  }

  // ── 交付 / 导出 + 快切（对齐真桥：/v1/export · /v1/last-text · /v1/thinking/cycle）──
  if (path === '/v1/export') {
    return send(ok({ path: '/home/demo/workspace/pi-session-mock.html', bytes: 245760 }));
  }
  if (path === '/v1/last-text') {
    return send(ok({ text: '这是最后一条回答的正文。\n第二行细节，用来验证 /copy。' }));
  }
  if (path === '/v1/thinking/cycle') {
    const levels = ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
    const i = levels.indexOf(BEHAVIOR.thinking || 'high');
    BEHAVIOR.thinking = levels[(i + 1) % levels.length];
    return send(ok({ level: BEHAVIOR.thinking, thinking: BEHAVIOR.thinking }));
  }

  // ── 行为开关（对齐真桥的 /v1/behavior · /v1/retry/abort）────────────────────────
  if (path === '/v1/behavior') {
    if (req.method === 'POST') {
      let payload = {};
      try { payload = JSON.parse(body || '{}'); } catch { /* 空体 */ }
      if (typeof payload.autoCompaction === 'boolean') BEHAVIOR.autoCompaction = payload.autoCompaction;
      if (typeof payload.autoRetry === 'boolean') BEHAVIOR.autoRetry = payload.autoRetry;
      if (payload.steeringMode) BEHAVIOR.steeringMode = payload.steeringMode;
      if (payload.followUpMode) BEHAVIOR.followUpMode = payload.followUpMode;
    }
    return send(ok(BEHAVIOR));
  }
  if (path === '/v1/retry/abort') {
    BEHAVIOR.isStreaming = false;
    return send(ok({ aborted: true }));
  }

  if (path === '/v1/app-profile') {    // app 会话注入：mock 里给一份样例（真桥是桥目录下的 skills/ 与 app-profile/）
    if (req.method === 'POST') {
      if (argv.includes('--profile-save-error')) return send(JSON.stringify({ok: false, error: 'mock: 注入设置保存失败'}), 500);
      let patch;
      try { patch = JSON.parse(body); } catch { return send(JSON.stringify({ok: false, error: 'invalid JSON'}), 400); }
      if (patch.reset) {
        APP_PROFILE_STATE.appendPrompt.enabled = true;
        for (const item of [...APP_PROFILE_STATE.skills, ...APP_PROFILE_STATE.mcp]) item.enabled = true;
      }
      if (typeof patch.appendPrompt === 'boolean') APP_PROFILE_STATE.appendPrompt.enabled = patch.appendPrompt;
      for (const key of ['skills', 'mcp']) for (const [id, enabled] of Object.entries(patch[key] || {})) {
        const item = APP_PROFILE_STATE[key].find(item => item.id === id);
        if (item && typeof enabled === 'boolean') item.enabled = enabled;
      }
    }
    return send(ok(APP_PROFILE_STATE));
  }
  if (path === '/v1/events') {
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    });
    // 与真桥一致：连上先发一条 `hello`（app 收到会重拉历史 / 重新对齐状态）。
    // ⚠️ 事件信封的键是 **kind**（不是 type）—— 真桥是 `broadcast({kind:'pi'|'bridge',...})`，
    // 假桥以前写成 `type`，于是 app 的 onEvent 一个分支都进不去（事件静默丢弃）。
    res.write('data: ' + JSON.stringify({ kind: 'bridge', event: 'hello', worker: true, at: Date.now() }) + '\n\n');
    clients.add(res);
    const timer = setInterval(() => res.write(`event: ping\ndata: {"at":${Date.now()}}\n\n`), 20000);
    // 注意：GET 无 body 时 `req` 的 'close' 会立刻触发（请求流读完即关），要挂 `res` 才知道客户端断开
    res.on('close', () => {
      clearInterval(timer);
      clients.delete(res);
    });
    return undefined;
  }
  // app 上报的屏幕/字号（真桥会拼进注入提示；mock 只记下来给 /mock/device 看）
  if (path === '/v1/device' && req.method === 'POST') {
    try { lastDevice = JSON.parse(body || '{}'); } catch { lastDevice = null; }
    return send(ok({ saved: true, changed: true, device: lastDevice }));
  }
  if (path === '/mock/device') return send(ok({ device: lastDevice }));

  // 人为让"发送"失败：`curl 'http://127.0.0.1:8799/mock/fail?prompt=1'`
  if (path === '/mock/fail') {
    if (url.searchParams.has('prompt')) failPrompt = url.searchParams.get('prompt') === '1';
    return send(ok({ failPrompt }));
  }
  // 历史里保留图数据（复现「实时事件带图」：验工具卡只给入口、不内联缩略图）
  if (path === '/mock/images') {
    if (url.searchParams.has('full')) keepImages = url.searchParams.get('full') === '1';
    return send(ok({ keepImages }));
  }
  // 让 mock 主动推一条事件（验证「排队条」等 UI；真桥对应 pi 的 queue_update）
  // 用法：curl 'http://127.0.0.1:8799/mock/emit?type=queue_update&steering=第一行%0A第二行&followUp=稍后再做'
  if (path === '/mock/emit') {
    const type = url.searchParams.get('type') || 'queue_update';
    const record = { type };
    for (const key of ['steering', 'followUp']) {
      const raw = url.searchParams.getAll(key);
      if (raw.length) record[key] = raw;
      // 单个参数用 | 分隔多条
      else if (url.searchParams.get(key)) record[key] = url.searchParams.get(key).split('|');
    }
    // 压缩完成事件：真桥的形状是 `record.result = { tokensBefore, estimatedTokensAfter, summary }`
    // （app 的卡片从 result 里取，不在顶层）。方便本地验证"摘要可展开"。
    if (type === 'compaction_end') {
      const tokensBefore = Number(url.searchParams.get('tokensBefore') || 18240);
      const after = Number(url.searchParams.get('estimatedTokensAfter') || 4100);
      const summary = url.searchParams.get('summary')
        || '## 摘要\n\n- 保留了鉴权中间件的改造要求\n- 待办：补测试\n- 结论：先做导出与快切';
      record.result = { tokensBefore, estimatedTokensAfter: after, summary };
      for (const k of ['tokensBefore', 'estimatedTokensAfter', 'summary']) delete record[k];
    }
    if (type === 'queue_update') {
      queue = { steering: [...(record.steering || [])], followUp: [...(record.followUp || [])] };
    }
    // 其他事件的数值参数直接直通（验证 auto_retry_start 横幅用）：
    //   /mock/emit?type=auto_retry_start&attempt=1&maxAttempts=3&delayMs=4000&errorMessage=429
    for (const [k, v] of url.searchParams) {
      if (k === 'type' || k === 'steering' || k === 'followUp') continue;
      const n = Number(v);
      record[k] = Number.isFinite(n) && v.trim() !== '' ? n : v;
    }
    // 形状必须和真桥一致：pi 的记录包在 {kind:'pi', record, sessionFile} 里
    const ev = { kind: 'pi', sessionFile: session.file, record };
    const line = 'data: ' + JSON.stringify(ev) + '\n\n';
    for (const c of clients) c.write(line);
    return send(ok({ emitted: ev, subscribers: clients.size }));
  }
  // /mock/stream?chars=20&delayMs=120：模拟一次流式回答（message_start → 若干 text_delta → message_end）。
  // 用来在 AVD 上肉眼验证"逐字显现"，不用真模型。
  if (path === '/mock/stream') {
    const full = url.searchParams.get('text')
      || '这是一段用来验证逐字显现的示例回答。它会被拆成很多小块推给 app，' +
         '每块之间隔一小会儿，所以你看到的效果应该像有人在打字。' +
         '当积压变多时，逐字速度会自动加快，保证不会落后真实内容太远。';
    const chunk = Number(url.searchParams.get('chars') || 8);
    const delayMs = Number(url.searchParams.get('delayMs') || 120);
    const sendEvent = (record) => {
      const line = 'data: ' + JSON.stringify({ kind: 'pi', sessionFile: session.file, record }) + '\n\n';
      for (const c of clients) c.write(line);
    };
    sendEvent({ type: 'message_start', message: { role: 'assistant', timestamp: Date.now() } });
    // th=1：先推一段 thinking（带 markdown 样式），用来验证 thinking 的渲染（加粗/代码/列表/斜体）
    const THINK = url.searchParams.get('th') === '1'
      ? '先确认**要点**：\n\n- `wcwidth` 与中文宽度\n- *斜体* 与 ~~删除线~~\n\n> 引用块也要有样式'
      : '';
    if (THINK) {
      sendEvent({ type: 'message_update', assistantMessageEvent: { type: 'thinking_delta', delta: THINK } });
    }
    let i = 0;
    const timer = setInterval(() => {
      if (i >= full.length) {
        clearInterval(timer);
        const message = {
          role: 'assistant', timestamp: Date.now(), stopReason: 'stop',
          content: [
            ...(THINK ? [{ type: 'thinking', thinking: THINK }] : []),
            { type: 'text', text: full },
          ],
          usage: { inputTokens: 10, outputTokens: 40, totalTokens: 50, cost: { total: 0.0001 } },
        };
        messages.push(message);
        sendEvent({ type: 'message_end', message });
        sendEvent({ type: 'agent_end' });
        return;
      }
      const piece = full.slice(i, i + chunk);
      i += chunk;
      sendEvent({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: piece } });
    }, delayMs);
    return send(ok({ streaming: true, chars: full.length, chunk, delayMs }));
  }

  // /v1/bash：跑一条命令（终端 `!cmd`）。mock 回固定输出，并把 bashExecution 记进历史 ——
  // 这样 app 侧"跑完刷新历史就能看到那条命令"的路径也能验。
  if (path === '/v1/bash') {
    // 注意：`body` 进来是**原始字符串**（整个 mock 都是各处自己 JSON.parse），别直接当对象用
    let payload = {};
    try { payload = JSON.parse(body || '{}'); } catch { /* 空体 */ }
    const command = payload.command || url.searchParams.get('command') || '';
    const output = command.includes('/v1/health')
      ? `Linux demo-phone 6.1.0-android15 aarch64

{
  "ok": true,
  "host": "demo-phone",
  "version": "0.4.4",
  "sessions": 3
}`
      : 'demo-phone · ' + command + '\nexit 0';
    messages.push({
      role: 'bashExecution', command, output, exitCode: 0,
      cancelled: false, truncated: false, timestamp: Date.now(),
      excludeFromContext: Boolean(payload.excludeFromContext),
    });
    return send(ok({
      output, exitCode: 0, cancelled: false, truncated: false, id: payload.id || 'bash-mock',
    }));
  }

  // /v1/file：给「查看交付」用（文本/图片/目录）。mock 里图片返回内嵌的样例 PNG。
  // 字段名跟真桥一致（`mime` + `base64` + `size` + `name`）——FileViewer.ImagePane 读的就是这三个，
  // 以前 mock 给的是 `mimeType`/`data`，于是「查看图片」点开只显示「不像图片」（假桥自己的锅，2026-10-24 发现）。
  if (path === '/v1/file') {
    const p = url.searchParams.get('path') || '';
    if (/\.(png|jpg|jpeg|webp|gif|bmp)$/i.test(p)) {
      const bytes = Math.floor(SAMPLE_PNG.length * 3 / 4);
      return send(ok({ path: p, kind: 'image', name: p.split('/').pop(), mime: 'image/png', base64: SAMPLE_PNG, size: bytes }));
    }
    if (p.endsWith('/') || !/\.[a-z0-9]+$/i.test(p)) {
      return send(ok({ path: p, kind: 'dir', name: p.replace(/\/$/, '').split('/').pop(), entries: [{ name: 'a.txt', dir: false }, { name: 'b.png', dir: false }, { name: 'sub', dir: true }] }));
    }
    const text = 'mock 文件内容：' + p + '\n第二行\n第三行';
    return send(ok({ path: p, kind: 'text', name: p.split('/').pop(), mime: 'text/plain', size: text.length, text }));
  }
  // 排队（跟随 pi 的 queue_update / clear_queue 语义；专门给 app 侧排队条用）
  if (path === '/v1/queue/clear') {
    const out = { steering: [...queue.steering], followUp: [...queue.followUp], cleared: true };
    queue = { steering: [], followUp: [] };
    return send(ok(out));
  }
  // 发送：mock 里默认"收下了"，failPrompt 打开时回 500（对齐真桥出错：ok=false + error）
  if (path === '/v1/prompt' && req.method === 'POST') {
    if (failPrompt) {
      return send(JSON.stringify({ ok: false, error: 'mock: 桥出错（人为，用来验发送失败保留草稿）' }), 500);
    }
    return send(ok({ accepted: true, disposition: 'started' }));
  }
  if (req.method === 'POST') return send(ok({ accepted: true, path }));
  return send(JSON.stringify({ ok: false, error: 'mock: unknown route ' + path }), 404);
}

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (chunk) => { body += chunk; });
  req.on('end', () => route(req, res, body));
});
server.listen(PORT, '0.0.0.0', () => {
  const PORT = server.address().port;
  console.log(`mock bridge on http://0.0.0.0:${PORT}  (emulator: http://10.0.2.2:${PORT})`);
});
