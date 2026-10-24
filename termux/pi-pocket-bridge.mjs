#!/usr/bin/env node
// pi-pocket-bridge — 把 pi 官方的 `--mode rpc` JSONL 协议包成手机 app 能用的本地 HTTP + SSE 网关。
//
// 为什么存在：Android app 不能直接开 Termux 里的进程；而 Termux 内的 127.0.0.1 与其它 app 共用同一
// loopback，所以「Termux 里跑本桥 + app 连 127.0.0.1」是手机本地（非远程）最省事的一条路。
// 协议真相在 pi 自带文档：docs/rpc.md（framing/correlate）、docs/rpc-commands.md（命令面）、
// docs/json.md（事件流）。本文件只做搬运与收敛，不发明协议。
//
// 用法：
//   node pi-pocket-bridge.mjs                      # 127.0.0.1:8789
//   PI_POCKET_PORT=8790 PI_POCKET_TOKEN=xxx node ... # 换端口 / 开 token
//   PI_POCKET_CWD=/data/data/com.termux/files/home/workspace node ...
//
// 环境变量：
//   PI_POCKET_HOST       默认 127.0.0.1（非 loopback 会要求 TOKEN）
//   PI_POCKET_PORT       默认 8789
//   PI_POCKET_TOKEN      设了就必须带 Authorization: Bearer / ?token=
//   PI_POCKET_CWD        默认工作目录（新会话用）
//   PI_POCKET_PI         默认 pi（可用绝对路径）
//   PI_POCKET_IDLE_MS    闲置多久回收 pi 子进程，默认 900000（15 min），0 = 不回收
//   PI_POCKET_SESSIONS   会话目录，默认 $PI_CODING_AGENT_DIR/sessions 或 ~/.pi/agent/sessions

import http from 'node:http';
import { spawn } from 'node:child_process';
import child_process from 'node:child_process';
import url from 'node:url';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { RpcChannel } from './rpc-channel.mjs';
import { OrderedEvents, writeEvent } from './event-stream.mjs';
import { uiPromptNotice, shouldNotifyOnce, customNotifyPayload, notifyBroadcastArgs, ntfyPublishRequest, turnEndNotice } from './notify-payload.mjs';

const HOST = process.env.PI_POCKET_HOST || '127.0.0.1';
// 会话树投影（纯函数，能脱离设备测：node tools/tree-bench.mjs）
import { treeFromSessionFile } from './tree-from-file.mjs';

const PORT = Number(process.env.PI_POCKET_PORT || 8789);
const TOKEN = process.env.PI_POCKET_TOKEN || '';
const PI_BIN = process.env.PI_POCKET_PI || 'pi';
const IDLE_MS = Number(process.env.PI_POCKET_IDLE_MS ?? 15 * 60 * 1000);
const DEFAULT_CWD = process.env.PI_POCKET_CWD || process.cwd();
const AGENT_DIR = process.env.PI_CODING_AGENT_DIR || path.join(os.homedir(), '.pi', 'agent');
const SESSIONS_ROOT = process.env.PI_POCKET_SESSIONS || path.join(AGENT_DIR, 'sessions');
/** 桥自己的小状态（当前会话）：重启后要读回来，见文件末尾 restoreActiveFile。 */
const STATE_FILE = process.env.PI_POCKET_STATE || path.join(AGENT_DIR, 'pi-pocket-state.json');
const MAX_BODY = 48 * 1024 * 1024; // 图片 base64 走 JSON body，给足余量
const STARTED_AT = new Date().toISOString();

// ── app 会话专属 profile（用户 2026-10-19 口径）─────────────────────────────
// 「app 内置一批预设 skills / MCP，只在通过 app 连上的会话里注入」。桥就是 app 的那条路，
// 所以注入点放这里：spawn pi 时加 --skill / --append-system-prompt / -e。
// 终端里直接跑的 pi、别的 harness 完全不受影响（它们的 pi 不是桥起的）。
// 关掉：PI_POCKET_APP_PROFILE=0；换位置：PI_POCKET_SKILLS / PI_POCKET_APP_PROMPT / PI_POCKET_APP_EXTENSION。
const BRIDGE_DIR = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const APP_PROFILE_ON = process.env.PI_POCKET_APP_PROFILE !== '0';
// 先看桥旁边的 skills/（仓库布局），没有再退回 agent 目录下自更新装的副本
const APP_SKILLS_DIR = process.env.PI_POCKET_SKILLS || (
  fs.existsSync(path.join(BRIDGE_DIR, 'skills'))
    ? path.join(BRIDGE_DIR, 'skills')
    : path.join(AGENT_DIR, 'pi-pocket-app-profile', 'skills')
);
// app-profile 同理：仓库布局优先，其次 agent 目录（app 自更新写入的位置）
const APP_PROFILE_DIR = process.env.PI_POCKET_APP_PROFILE_DIR || (fs.existsSync(path.join(BRIDGE_DIR, 'app-profile'))
  ? path.join(BRIDGE_DIR, 'app-profile')
  : path.join(AGENT_DIR, 'pi-pocket-app-profile'));
const APP_PROMPT = process.env.PI_POCKET_APP_PROMPT || path.join(APP_PROFILE_DIR, 'append-system.md');
// 设备信息（屏幕 dp/px、正文字号、主题…）：app 每次变化上报一次，桥生成这份文件后
// 作为**第二个** --append-system-prompt 注入（pi 允许重复给）。内容是给 agent 看的"目标画布"。
const DEVICE_JSON = path.join(APP_PROFILE_DIR, 'device.json');
const DEVICE_PROMPT = path.join(APP_PROFILE_DIR, 'device-context.md');
const APP_EXTENSION = process.env.PI_POCKET_APP_EXTENSION || path.join(APP_PROFILE_DIR, 'extension.mjs');
const APP_MCP_PRESETS = process.env.PI_POCKET_MCP_PRESETS || path.join(APP_PROFILE_DIR, 'mcp-presets.json');
const APP_SELECTION = process.env.PI_POCKET_APP_SELECTION || path.join(APP_PROFILE_DIR, 'selection.json');

/** app 里的开关（哪些 skill / MCP / 附加提示要注入）落在这里；缺省 = 全开。 */
function readSelection() {
  try { return JSON.parse(fs.readFileSync(APP_SELECTION, 'utf8')); } catch { return {}; }
}
function writeSelection(sel) {
  fs.mkdirSync(path.dirname(APP_SELECTION), { recursive: true });
  fs.writeFileSync(APP_SELECTION, JSON.stringify(sel, null, 2));
}

/** `skills/` 下每个带 SKILL.md 的子目录 = 一个可注入项（ｕ frontmatter 取 name/description）。 */
function listAppSkills() {
  const out = [];
  if (!fs.existsSync(APP_SKILLS_DIR)) return out;
  for (const entry of fs.readdirSync(APP_SKILLS_DIR, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const file = path.join(APP_SKILLS_DIR, entry.name, 'SKILL.md');
    if (!fs.existsSync(file)) continue;
    const head = (() => { try { return fs.readFileSync(file, 'utf8').slice(0, 2000); } catch { return ''; } })();
    const raw = (m) => (new RegExp(`^${m}:\\s*(.+)$`, 'm').exec(head)?.[1] || '').trim().replace(/^["']|["']$/g, '');
    out.push({ id: entry.name, name: raw('name') || entry.name, description: raw('description') });
  }
  return out;
}

function listAppMcp() {
  try {
    const data = JSON.parse(fs.readFileSync(APP_MCP_PRESETS, 'utf8'));
    return Object.entries(data.servers || {}).map(([id, cfg]) => ({
      id,
      enabled: cfg?.enabled !== false,
      remote: Boolean(cfg?.url),
      description: cfg?.description || (cfg?.url ? 'HTTP server' : 'stdio server'),
    }));
  } catch { return []; }
}

/** 读回上次上报的设备信息（没上报过 → null）。 */
function readDevice() {
  try { return JSON.parse(fs.readFileSync(DEVICE_JSON, 'utf8')); } catch { return null; }
}

/**
 * 设备信息 → 注入提示（中文，短）。
 *
 * 目的（用户 2026-10-23）：让 agent 知道"画布只有 ~360dp 宽、正文 12sp"，
 * 于是表格列数、图的比例、字号都往手机上调，而不是按桌面宽度设计再让 app 去截断。
 */
function devicePromptText(d) {
  if (!d || !d.screenWdp) return '';
  const bodyW = d.contentWidthDp || (d.screenWdp - 32);
  // 一屏大概能放几行正文：去掉顶栏/输入区约 190dp，再按 1.5 倍行高估
  const lines = Math.max(6, Math.round((d.screenHdp - 190) / ((d.bodySp || 12) * 1.5)));
  return [
    '# 当前设备（app 实时上报，按它来排版）',
    '',
    `- 屏幕：${d.screenWpx}×${d.screenHpx} px，${d.screenWdp}×${d.screenHdp} dp，density ${d.density}（${d.densityDpi} dpi），主题 ${d.theme === 'light' ? '浅色' : '深色'}`,
    `- 对话正文可用宽度：**约 ${bodyW} dp**（手机竖屏；表格/图/代码块都按这个宽算，别按桌面宽）`,
    `- 字号：正文 ${d.bodySp} sp、工具卡 ${d.toolSp} sp、元信息 ${d.metaSp} sp（用户可调，可能是这个值附近）；系统字体缩放 ×${d.fontScale}，对话额外 ×${d.chatScale}`,
    `- 单屏可见：正文约 ${lines} 行`,
    `- html-visual：宽度同正文 ${bodyW} dp；内联整段铺开（上限 ${d.htmlInlineMaxDp} dp，超过会被截并给全屏入口）⇒ 单块建议 ≤2 屏（≈1000dp），更长请拆成多块或改用 mermaid`,
    '',
    '排版口径（据此画，不要靠 app 猜）：',
    '- 表格：列数 ≤4（最多 5 列且前两列要短）；每格文字控制在 8 个汉字内，长内容拆成多行/多段而不是加列。',
    '- 图：宽高比按竖屏（宽:高 ≈ 1.6:1），坐标轴字号 ≥10px；横向很宽的图请改成纵向条形或分段折线，或明说"建议横屏看"。',
    '- 代码/表格之外的文字不要写死颜色（尤其浅灰 #9aa0a6 这类）：浅色模式下 app 会压暗行内色，但 canvas 里的颜色改不了；给深色/浅色都看得清的色值，或省略颜色。',
    '- html-visual 只在需要交互或真实图表时用；静态结构用 mermaid。',
  ].join('\n') + '\n';
}

/** 落盘 + 返回是否真的变了（变了才值得回收 pi 子进程让新提示生效）。 */
function writeDevice(d) {
  const prev = readDevice();
  const changed = JSON.stringify(prev || null) !== JSON.stringify(d || null);
  try {
    if (!fs.existsSync(APP_PROFILE_DIR)) fs.mkdirSync(APP_PROFILE_DIR, { recursive: true });
    fs.writeFileSync(DEVICE_JSON, JSON.stringify(d || {}, null, 2));
    fs.writeFileSync(DEVICE_PROMPT, devicePromptText(d));
  } catch (e) { console.warn('[bridge] 写设备信息失败：' + e.message); }
  return changed;
}

function appProfileSnapshot() {
  const sel = readSelection();
  return {
    enabled: APP_PROFILE_ON,
    paths: { skills: APP_SKILLS_DIR, prompt: APP_PROMPT, mcp: APP_MCP_PRESETS, selection: APP_SELECTION },
    appendPrompt: { id: 'appendPrompt', enabled: sel?.appendPrompt !== false, present: fs.existsSync(APP_PROMPT) },
    device: readDevice(),
    skills: listAppSkills().map((s) => ({ ...s, enabled: sel?.skills?.[s.id] !== false })),
    mcp: listAppMcp().map((m) => ({ ...m, enabled: sel?.mcp?.[m.id] !== false })),
  };
}

function appProfileArgs() {
  if (!APP_PROFILE_ON) return [];
  const sel = readSelection();
  const args = [];
  const skills = listAppSkills();
  const on = skills.filter((s) => sel?.skills?.[s.id] !== false);
  if (on.length > 0) {
    // 全选时直接给目录（省参数）；有排除项时逐个给子目录
    if (on.length === skills.length) args.push('--skill', APP_SKILLS_DIR);
    else for (const s of on) args.push('--skill', path.join(APP_SKILLS_DIR, s.id));
  }
  if (sel?.appendPrompt !== false && fs.existsSync(APP_PROMPT)) args.push('--append-system-prompt', APP_PROMPT);
  // 设备画布：让 agent 知道手机屏有多大、正文多少 sp（pi 允许 --append-system-prompt 多次）
  if (fs.existsSync(DEVICE_PROMPT)) args.push('--append-system-prompt', DEVICE_PROMPT);
  // 预设 MCP：扩展在加载时读同名文件，按这个变量决定注入哪几个（空 = 全部）
  if (fs.existsSync(APP_EXTENSION)) args.push('-e', APP_EXTENSION);
  return args;
}

function appProfileEnv() {
  if (!APP_PROFILE_ON) return {};
  const sel = readSelection();
  const mcp = listAppMcp();
  const on = mcp.filter((m) => sel?.mcp?.[m.id] !== false).map((m) => m.id);
  return { PI_POCKET_MCP_ENABLE: on.length === mcp.length ? '' : on.join(',') };
}

// chalk（pi 的 theme.bold/italic/underline 就是它）只在检测到"彩色终端"时才输出 ANSI；
// 桥由 app/nohup 拉起、stdin/stdout 都是管道 ⇒ chalk 判成 level 0 ⇒ **加粗被静默剥掉**。
// 颜色不受影响（theme.fg 自己拼 truecolor），所以现象很怪：卡片有颜色，但工具名不再加粗
// （用户 2026-10-23 报的"调用字符加粗等样式被移除"）。
// 必须在 import pi 的模块**之前**设好：chalk 在加载时读这个变量。
if (!process.env.FORCE_COLOR && !process.env.NO_COLOR) process.env.FORCE_COLOR = '3';

const log = (...a) => console.error(`[pi-pocket ${new Date().toISOString()}]`, ...a);
const BRIDGE_VERSION = '0.2.0';

// ── SSE 订阅者 ────────────────────────────────────────────────────────────────
const subscribers = new Set();
/** 已发过通知的「等用户回应」请求（同一个请求只叫一次）。 */
const uiNoticeSeen = new Map();
/** 从事件流里维护「这轮在跑」，闲置回收要避开正在跑的一轮（app 崩了也别打断）。 */
function noteStreaming(record, w) {
  const type = record?.type;
  if (!type || !w) return;
  if (type === 'agent_start' || type === 'message_start' || type === 'tool_execution_start') w.streaming = true;
  if (type === 'agent_settled' || type === 'worker_exit') w.streaming = false;
  // 用户改了字号/旋转了屏幕：这一轮结束后再换提示，别在跑的过程中被打断
  if ((type === 'agent_settled' || type === 'worker_exit') && pendingDeviceRecycle) {
    setTimeout(maybeRecycleForDevice, type === 'worker_exit' ? 200 : 800);
  }
}

/**
 * 手机通知：这一轮**结束/被停/出错**时发一条本地广播，由 app 决定弹不弹。
 *
 * 为什么桥来发：app 进后台后 Android 会收掉 socket，此时谁也收不到流式事件；
 * 只有桥一直活着，知道"pi 说这一轮结束了"。
 * 只有真的在 Android 上跑才发（在桌面/CI 上跑桥不该报错）。
 */
function notifyPhone({ title, text, file, kind }) {
  if (process.platform === 'android') {
    const argv = notifyBroadcastArgs({ title, text, file, kind });
    try {
      const child = spawn('am', argv, { stdio: 'ignore', detached: true });
      child.on('error', () => { /* 没装 app / 没 am：静默 */ });
      child.unref();
    } catch { /* 忽略 */ }
    return 'am';
  }
  // 非 Android（host-a/WSL/桌面）：**优先走 pocket 通道** —— 把通知当成一条桥事件推给 app，
  // 由 app 弹它自己的通知（用户口径：不要 ntfy 那套）。
  // 前提：app 要保持 SSE 连接（app 用前台服务守连接，见 LinkService）。
  // 注意字段名：信封本身就是 `{kind:'bridge', event:…}`，所以通知的分类必须叫 notifyKind，
  // 否则会把信封的 kind 覆盖掉，app 就不当桥事件处理了（2026-10-24 实测踩过）。
  emitBridge('notify', { title, text, file, notifyKind: kind });
  let via = subscribers.size > 0 ? 'sse' : null;
  // ntfy 只在显式开启时当兜底（PI_POCKET_NTFY_FALLBACK=1）；默认不推。
  if (process.env.PI_POCKET_NTFY_FALLBACK === '1') {
    const req = ntfyPublishRequest(notifyConfig(), { title, text, kind }, process.env);
    if (req) {
      fetch(req.url, { method: 'POST', headers: req.headers, body: req.body, signal: AbortSignal.timeout(8000) })
        .then((res) => { if (!res.ok) log(`[notify] ntfy HTTP ${res.status}`); })
        .catch((err) => log('[notify] ntfy 发送失败: ' + err.message));
      via = via || 'ntfy';
    }
  }
  return via;
}

/** 读 `pi-notify` 的配置（带缓存）；没有就返回 null。topic/token 不落日志。 */
let notifyConfigCache;
function notifyConfig() {
  if (notifyConfigCache !== undefined) return notifyConfigCache;
  const file = process.env.PI_NOTIFY_CONFIG || path.join(AGENT_DIR, 'pi-notify.json');
  notifyConfigCache = readJsonSafe(file);
  return notifyConfigCache;
}

function broadcast(record) {
  const line = `data: ${JSON.stringify(record)}\n\n`;
  for (const res of subscribers) {
    if (!writeEvent(res, line)) subscribers.delete(res);
  }
}
const emitBridge = (event, extra = {}) => broadcast({ kind: 'bridge', event, at: Date.now(), ...extra });

/** 容错读 JSON（不存在/坏掉都返回 null）。 */
function readJsonSafe(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

/** 原子写 JSON（先写临时文件再 rename），权限默认 600：里面可能有 API key。 */
function writeJsonSafe(file, data, mode = 0o600) {
  const tmp = file + '.tmp-' + process.pid;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n', { mode });
  fs.renameSync(tmp, file);
  try { fs.chmodSync(file, mode); } catch { /* 非 POSIX 忽略 */ }
}



// ── 与终端一致的显示文本（唯一事实源 = pi 自己的渲染代码）─────────────────────
// 用户口径："终端什么样 app 自动跟随"。所以不要在本文件里另写一套拼装规则，
// 而是**直接调用 pi 自带的 `dist/core/tools/render-utils.js`**：
//   - formatToolCallWithArgs(name, args, theme, expanded)  → 标题行 / 展开后的参数块
//   - OUTPUT_PREVIEW_LINES（MCP 渲染器用的 5 视觉行）
// 另外 MCP 工具的标题，pi 是在 `registerToolRenderer` 里按 `mcp__<server>__<tool>` 拆成 `server/tool`
// （见 pi dist 里的 createMcpToolRenderers(`${server}/${tool}`)）。
// 拿不到 pi 的模块时整段降级为 null，app 侧会退回自己的兜底逻辑（不会崩）。
const NOOP_THEME = { fg: (_c, s) => s, bg: (_c, s) => s, bold: (s) => s, dim: (s) => s };
const MCP_NAME_RE = /^mcp__(.+?)__(.+)$/;
let piRenderUtils = null;
let piRenderTried = false;
let piDistRoot = null;
let piRenderUtilsPromise = null;

async function loadPiRenderUtils() {
  // 并发安全：同时有很多工具结果在渲染时，第一个调用还没 await 完，其它调用不能拿到 null。
  if (piRenderUtilsPromise) return piRenderUtilsPromise;
  piRenderUtilsPromise = (async () => {
  if (piRenderTried) return piRenderUtils;
  piRenderTried = true;
  const candidates = [];
  // 0) 允许手工指定（排查时最省事）
  if (process.env.PI_POCKET_RENDER_UTILS) candidates.push(process.env.PI_POCKET_RENDER_UTILS);
  // 0b) 常见全局安装位置（Termux 里没有 `which`，只能按前缀直接找）
  const PKG = '@earendil-works/pi-coding-agent/dist/core/tools/render-utils.js';
  for (const prefix of [
    process.env.PREFIX,                        // Termux: /data/data/com.termux/files/usr
    path.join(os.homedir(), '.local'),          // host-a: ~/.local/lib/node_modules/...
    '/usr/local', '/usr', '/opt/homebrew',
    path.join(os.homedir(), '.npm-global'),
  ]) {
    if (!prefix) continue;
    candidates.push(path.join(prefix, 'lib/node_modules', PKG));
    candidates.push(path.join(prefix, 'node_modules', PKG));
  }
  // 1) 从 pi 可执行文件的真实路径**向上**找包目录：
  //    Termux:  .../usr/bin/pi → .../lib/node_modules/@earendil-works/pi-coding-agent
  //    host-a:    ~/.local/bin/pi → ~/.local/lib/node_modules/…（realpath 指向 dist/bundle/cli.js）
  try {
    const which = child_process.execFileSync(process.platform === 'win32' ? 'where' : 'which', ['pi'], { encoding: 'utf8' })
      .split(String.fromCharCode(10))[0].trim();
    let dir = path.dirname(which);
    try { dir = path.dirname(fs.realpathSync(which)); } catch { /* 用原路径 */ }
    for (let i = 0; i < 10 && dir && dir !== path.dirname(dir); i++) {
      candidates.push(path.join(dir, 'dist/core/tools/render-utils.js'));
      candidates.push(path.join(dir, 'node_modules/@earendil-works/pi-coding-agent/dist/core/tools/render-utils.js'));
      dir = path.dirname(dir);
    }
  } catch { /* pi 不在 PATH */ }
  for (const file of candidates) {
    try {
      if (!fs.existsSync(file)) continue;
      piRenderUtils = await import(url.pathToFileURL(file).href);
      // render-utils.js 在 dist/core/tools/ 下 ⇒ 上三级才是 dist
      piDistRoot = path.dirname(path.dirname(path.dirname(file))); // …/dist
      console.info('[bridge] 工具卡显示文本来自 pi 的 render-utils: ' + file);
      return piRenderUtils;
    } catch (e) {
      console.warn('[bridge] 加载 pi render-utils 失败：' + file + ' → ' + e.message);
    }
  }
  console.warn('[bridge] 没找到 pi 的 render-utils，工具卡显示退回 app 侧兜底');
  return null;
  })();
  return piRenderUtilsPromise;
}

/** 一个工具调用的显示文本（标题 / 收起参数 / 展开参数 / 预览行数），全部来自 pi 的实现。 */
async function toolDisplay(name, args) {
  const ru = await loadPiRenderUtils();
  if (!ru || typeof ru.formatToolCallWithArgs !== 'function') return null;
  const m = MCP_NAME_RE.exec(String(name ?? ''));
  const title = m ? `${m[1]}/${m[2]}` : String(name ?? '');
  let collapsed = '';
  let expanded = '';
  try {
    collapsed = String(ru.formatToolCallWithArgs(title, args ?? {}, NOOP_THEME, false) ?? '');
    expanded = String(ru.formatToolCallWithArgs(title, args ?? {}, NOOP_THEME, true) ?? '');
  } catch (e) {
    return null;
  }
  // 把标题从两段文本里摘掉，app 单独渲染标题（加粗/上色由 app 决定）
  const stripTitle = (text) => {
    const t = text.startsWith(title) ? text.slice(title.length) : text;
    return t.replace(/^\s+/, '');
  };
  return {
    title,
    argsCollapsed: stripTitle(collapsed),
    argsExpanded: stripTitle(expanded),
    // pi 的 MCP 渲染器：OUTPUT_PREVIEW_LINES = 5（视觉行）
    previewLines: 5,
  };
}

// ── 工具结果的「终端原样 HTML」────────────────────────────────────────────────
// 用户口径：能继承终端就继承终端。pi 的每个内置工具自带 renderResult（TUI 组件），
// 这里直接调它 + pi 自己的 ANSI→HTML，把 edit 的 diff 块、bash 的输出框等**按终端样式**吐成 HTML。
// app 只负责把这段 HTML 放进 WebView，不再自己重写 diff/边框规则；pi 改渲染，app 自动跟随。
let piToolKit = null;
let piToolKitTried = false;
let piToolKitPromise = null;
const toolHtmlCache = new Map();
const toolTermCache = new Map();
/** toolCallId → args：end 事件不保证带 args，缺了只渲染得出 `$ ...`（见 tool_execution_start/end 处）。 */
const argsByToolCallId = new Map();
let termCols = 80; // app 上报的等宽列数（决定换行点，与终端 1:1）
const TOOL_HTML_CACHE_MAX = 300;

async function loadPiToolKit(cwd) {
  if (piToolKitPromise) return piToolKitPromise;
  piToolKitPromise = (async () => {
  if (piToolKitTried) return piToolKit;
  piToolKitTried = true;
  const ru = await loadPiRenderUtils();
  if (!ru || !piDistRoot) {
    console.warn('[bridge] 没有 pi 的 dist 根，工具 HTML 渲染不可用');
    return null;
  }
  const imp = (rel) => import(url.pathToFileURL(path.join(piDistRoot, rel)).href);
  try {
    const [toolsMod, themeMod, ansiMod, mcpMod, codemodeMod] = await Promise.all([
      imp('core/tools/index.js'),
      imp('modes/interactive/theme/theme.js'),
      imp('core/export-html/ansi-to-html.js'),
      // MCP 工具的渲染器（用户 2026-10-21：gitea / obsidian / health 这些卡片也照终端画）。
      // `createMcpToolRenderers(label)` 的注释就写着「also used before the tool is registered」
      // —— 正好是桥要的场景：只知道 `mcp__<server>__<tool>` 就能渲染。
      imp('extensions/mcp/tools.js').catch(() => null),
      // codemode 的渲染器是 pi **内置**的（`builtin:codemode`，不在 core/tools 里）
      imp('extensions/codemode/renderer.js').catch(() => null),
    ]);
    try {
      if (themeMod.initTheme) themeMod.initTheme('dark');
    } catch (e) {
      console.warn('[bridge] initTheme 失败（会退回纯文本）：' + e.message);
    }
    const theme = themeMod.getThemeByName ? themeMod.getThemeByName('dark') : null;
    if (!theme) throw new Error('拿不到 dark 主题');
    const factories = {
      edit: toolsMod.createEditToolDefinition,
      read: toolsMod.createReadToolDefinition,
      write: toolsMod.createWriteToolDefinition,
      bash: toolsMod.createBashToolDefinition,
      grep: toolsMod.createGrepToolDefinition,
      ls: toolsMod.createLsToolDefinition,
      find: toolsMod.createFindToolDefinition,
    };
    const defs = {};
    for (const [name, factory] of Object.entries(factories)) {
      if (typeof factory !== 'function') continue;
      try { defs[name] = factory({ cwd }); } catch (e) { /* 该工具定义不可用就跳过 */ }
    }
    piToolKit = {
      defs,
      theme,
      ansiLinesToHtml: ansiMod.ansiLinesToHtml,
      cwd,
      mcpRenderers: mcpMod && typeof mcpMod.createMcpToolRenderers === 'function'
        ? mcpMod.createMcpToolRenderers
        : null,
      codemodeRenderers: codemodeMod && codemodeMod.codemodeRenderers ? codemodeMod.codemodeRenderers : null,
    };
    console.info('[bridge] 工具 HTML 渲染来自 pi: ' + piDistRoot +
      '（内置工具: ' + Object.keys(defs).join(',') +
      '；MCP ' + (piToolKit.mcpRenderers ? 'ok' : '缺') +
      '；codemode ' + (piToolKit.codemodeRenderers ? 'ok' : '缺') + '）');
    return piToolKit;
  } catch (e) {
    console.warn('[bridge] 加载 pi 工具渲染器失败：' + e.message);
    return null;
  }
  })();
  return piToolKitPromise;
}

/**
 * 渲染一条工具结果 → { htmlCollapsed, htmlExpanded }。
 * 只对 pi 内置工具（edit/read/write/bash/grep/ls/find）生效；扩展工具（unified-exec/intercom 等）
 * 的渲染器在扩展进程里，桥拿不到，这里返回 null，app 继续用纯文本兜底。
 */
/**
 * 扩展工具（exec_command / write_stdin / web_search / MCP…）的终端行由 pi 进程里的
 * `pocket-term.mjs` 扩展算好落盘（~/.pi/agent/pocket-term/<toolCallId>.json），这里读回来。
 * 为什么绕这一圈：那些工具的渲染器注册在扩展里，桥进程 import 不到（缺 pi 内部依赖）。
 */
const POCKET_TERM_DIR = path.join(AGENT_DIR, 'pocket-term');

// ── 工具渲染器「适配器」（pi 包里的工具：exec_command / web_search …）─────────────────
// 见 termux/tool-render/README.md：pi 在 --mode rpc 下不会调 registerToolRenderer，
// 所以桥自己去包内部取渲染函数；每个包一个适配器，升级后只改对应那个文件。
let adapterRegistryPromise = null;

async function adapterRegistry() {
  if (adapterRegistryPromise) return adapterRegistryPromise;
  adapterRegistryPromise = (async () => {
    const candidates = [
      path.join(path.dirname(fileURLToPath(import.meta.url)), 'tool-render/index.mjs'),
      path.join(os.homedir(), 'pi-pocket/termux/tool-render/index.mjs'),
      path.join(os.homedir(), '.pi/agent/pi-pocket/tool-render/index.mjs'),
    ];
    for (const file of candidates) {
      try {
        if (!fs.existsSync(file)) continue;
        const mod = await import(url.pathToFileURL(file).href);
        const reg = await mod.createAdapterRegistry({
          home: os.homedir(),
          prefix: process.env.PREFIX,
          distRoot: piDistRoot,
          log: (m) => console.info(m),
        });
        console.info('[adapters] 就绪：' + (reg.tools ? reg.tools().join(',') : ''));
        return reg;
      } catch (e) {
        console.warn('[adapters] 加载失败 ' + file + ' → ' + e.message);
      }
    }
    console.warn('[adapters] 没找到 tool-render 目录，扩展工具的终端渲染不可用');
    return { get: () => null, status: [{ id: '*missing*', ok: false, error: '没有 tool-render 目录' }] };
  })();
  return adapterRegistryPromise;
}


function readPocketTerm(toolCallId) {
  if (!toolCallId) return null;
  try {
    const file = path.join(POCKET_TERM_DIR, String(toolCallId).replace(/[^\w.-]/g, '_') + '.json');
    if (!fs.existsSync(file)) return null;
    const raw = fs.readFileSync(file, 'utf8');
    const obj = JSON.parse(raw);
    return obj && (obj.call || obj.resultCollapsed || obj.resultExpanded) ? obj : null;
  } catch {
    return null;
  }
}

/** app 报的列数落盘，给 pi 进程里的扩展用（它读同一个文件）。 */
function writeTermCols(cols) {
  try {
    const n = Math.max(20, Math.min(200, Math.floor(cols)));
    fs.mkdirSync(POCKET_TERM_DIR, { recursive: true });
    fs.writeFileSync(path.join(POCKET_TERM_DIR, 'cols'), String(n) + String.fromCharCode(10), { mode: 0o600 });
  } catch { /* 写不了就用默认 80 */ }
}

/**
 * 终端的定宽排版会**补尾部空格**并且在块首尾留空白行（`edit` 的头行就是「空行 + ` edit x` + 空行」）。
 * 手机上卡片宽度不等于终端的 80/90 列，所以这些纯排版噪声会被二次折行放大成“歪/多空行”。
 * 因此：去掉行尾空白，丢掉首尾纯空白行（中间的空行保留，那是 pi 故意留的呼吸）。
 * 注意：判空要去掉 ANSI 再判，否则只有颜色码的行会被当成“有内容”。
 */
function trimTermLines(lines, keepLeadingBlank = false) {
  if (!Array.isArray(lines)) return lines;
  const plain = (s) => String(s).replace(/\u001b\[[0-9;]*m/g, '');
  const out = lines.map((l) => String(l).replace(/[ \t]+$/, ''));
  if (keepLeadingBlank) {
    // **结果块必须留住开头那条空行**：pi 就是在头行和正文之间留一条（终端实拍：
    // `$ cmd (yield …)` → 空行 → 输出）。删了就不一致；连续多条也只留一条。
    while (out.length > 1 && !plain(out[0]).trim() && !plain(out[1]).trim()) out.shift();
  } else {
    while (out.length && !plain(out[0]).trim()) out.shift();
  }
  while (out.length && !plain(out[out.length - 1]).trim()) out.pop();
  return out;
}

/**
 * 工具块的**终端原样行**（ANSI）：`renderCall` + `renderResult`，按 app 给的列宽排版。
 *
 * 为什么还要这一份（除了 html）：用户口径是「1:1 对比终端」—— 终端的 block 不只是 diff，
 * 头一行是 ` edit <path>`（renderCall），宽度不同换行点也不同。所以按 app 的列数render，
 * app 端用 AnsiText 直接画这些行（颜色/缩进/换行全按终端），不做二次排版。
 */
/**
 * 「pi 自带、但不在 core/tools 里」的渲染器：MCP 工具 与 codemode。
 *
 * 为何必须在这层做：`pocket-term` 扩展走的是 `registerToolRenderer(resolver)`，
 * 而那个 resolver **只在 TUI 渲染时**才被调用（`interactive-mode.resolveToolRenderers`）
 * ⇒ 在 `--mode rpc` 下永远不会执行，扩展落盘的文件自然是空的；
 * 而 `getAllTools()` 只给 name/description/parameters，拿不到渲染器。
 *
 * 覆盖不到的工具（第三方扩展自己注册的，如 notify_ask / web_search）仍然走 app 兜底排版，
 * 那种情况要动 `termux/tool-render/adapters/`。
 */
const extraDefCache = new Map();
function extraRenderers(name) {
  if (!name) return null;
  if (extraDefCache.has(name)) return extraDefCache.get(name);
  const kit = piToolKit;
  let def = null;
  try {
    // MCP：`mcp__<server>__<tool>` ⇒ 显示名 `server/tool`（与终端一致）
    const m = /^mcp__(.+?)__(.+)$/.exec(name);
    if (m && kit && kit.mcpRenderers) def = kit.mcpRenderers(m[1] + '/' + m[2]);
    if (!def && name === 'codemode' && kit && kit.codemodeRenderers) def = kit.codemodeRenderers;
  } catch (e) {
    if (process.env.PP_TOOL_TERM_DEBUG) console.warn('[bridge] extra renderers 失败 ' + name + '：' + e.message);
    def = null;
  }
  extraDefCache.set(name, def);
  return def;
}

async function toolTerm(name, args, result, cols, partial = false) {
  const kit = await loadPiToolKit(DEFAULT_CWD);
  let def = kit && kit.defs && kit.defs[name];
  if (!def) {
    // 不是 pi 内置工具：问适配器（pi 包里的 exec_command / web_search …）
    const reg = await adapterRegistry();
    const adapted = reg.get(name);
    if (adapted) def = adapted;
  }
  // 再退一层：pi **自己就带着**的渲染器（MCP 工具、codemode）
  if (!def) def = extraRenderers(name);
  if (!def) {
    if (process.env.PP_TOOL_TERM_DEBUG) console.warn('[bridge] term: 没有 ' + name + ' 的定义');
    return null;
  }
  const width = Math.max(20, Math.min(200, Number(cols) || 80));
  const key = 'T|' + name + '|' + String(result && result.toolCallId || '') + '|' + width + '|' +
    (partial ? 'P' : 'F') + '|' +
    (Array.isArray(result && result.content) ? result.content.length : 0) + '|' +
    String(result && result.details && typeof result.details.diff === 'string' ? result.details.diff.length : 0) + '|' +
    (result && result.isError ? 'E' : 'A');
  const hit = toolTermCache.get(key);
  if (hit) return hit;
  // pi 的渲染器把「耗时」记在 context.state 的 startedAt/endedAt 上（不是 ctx.durationMs），
  // 历史消息里 pi 存了 durationMs，这里折算成 startedAt/endedAt ⇒ footer 才会出现 `took 2.7s`。
  const durMs = result && Number.isFinite(Number(result.durationMs)) ? Number(result.durationMs) : null;
  const now = Date.now();
  const ctx = (expanded) => ({
    args: args || {}, toolCallId: String(result && result.toolCallId || 'x'), invalidate: () => {},
    lastComponent: undefined,
    state: durMs != null && result && result.content ? { startedAt: now - durMs, endedAt: now } : {},
    cwd: kit.cwd, executionStarted: true, argsComplete: true,
    isPartial: partial, expanded, showImages: false, isError: Boolean(result && result.isError),
    durationMs: durMs == null ? undefined : durMs,
    outputPad: 1,
  });
  const out = {};
  try {
    if (typeof def.renderCall === 'function') {
      // **头行用宽宽度渲染**：pi 的 renderCall 在窄宽度下会把参数直接截成 `...`
      // （`read ...` / `$ ... (cwd: …)`），命令就看不见了（用户 2026-10-21 报的「命令没显示全」）。
      // 终端里同宽度是**换行**不是截断；app 的 AnsiText 是 softWrap=true，所以给足够宽、
      // 让 app 自己软折（视觉上等于把终端窗口拉窄）。
      const callWidth = Math.max(width, 100);
      // `edit` 的 diff 预览是**异步**的：renderCall 里先返回空壳，computeEditsDiff 完成后
      // 调 `context.invalidate()` 让终端重渲染。桥只快照一次 ⇒ app 里的 edit 卡片永远没有
      // 那段绿/红 diff（用户 2026-10-23："edit 工具这些样式好像没看到了"）。
      // 做法与终端一致：**同一份 state + 上一版组件**再渲染一次（等它算完）。
      const state = {};
      const renderOnce = (last) => {
        const c = ctx(false);
        c.state = state;
        c.lastComponent = last;
        return def.renderCall(args || {}, kit.theme, c);
      };
      let comp = renderOnce(undefined);
      if (name === 'edit' || name === 'write') {
        try {
          await new Promise((r) => setTimeout(r, 160));
          comp = renderOnce(comp) || comp;
        } catch { /* 预览算失败就用第一版（终端此时也只是没有 diff） */ }
      }
      out.call = comp.render(callWidth);
    }
  } catch (e) { if (process.env.PP_TOOL_TERM_DEBUG) console.warn('[bridge] term renderCall 失败 ' + name + '：' + e.message); }
  if (result && typeof def.renderResult === 'function') {
    const content = Array.isArray(result.content) ? result.content : [{ type: 'text', text: String(result.content == null ? '' : result.content) }];
    const agentResult = { content, details: result.details, isError: Boolean(result.isError) };
    for (const expanded of [false, true]) {
      try {
        const comp = def.renderResult(agentResult, { expanded, isPartial: partial }, kit.theme, ctx(expanded));
        out[expanded ? 'resultExpanded' : 'resultCollapsed'] = comp.render(width);
      } catch (e) { if (process.env.PP_TOOL_TERM_DEBUG) console.warn('[bridge] term renderResult 失败 ' + name + '：' + e.message); }
    }
  }
  if (!out.call && !out.resultCollapsed && !out.resultExpanded) return null;
  // 去掉定宽排版噪声：头行去掉首尾空行（`edit` 的头行是「空行 + ` edit x` + 空行」，那两条
  // 空行在终端里属于分隔，不属于头行）；结果块**保留开头的空行**（那是头行与正文之间的呼吸）。
  if (Array.isArray(out.call)) out.call = trimTermLines(out.call, false);
  if (Array.isArray(out.resultCollapsed)) out.resultCollapsed = trimTermLines(out.resultCollapsed, true);
  if (Array.isArray(out.resultExpanded)) out.resultExpanded = trimTermLines(out.resultExpanded, true);
  // 结果块的**存在与否**也要告诉 app：`read` 的 collapsed 渲染本来就是空（终端收起也不露正文），
  // app 不能把“空”当成“没渲染”去拿 expanded 兜底，否则收起态会多出一块终端里没有的正文。
  out.hasCollapsed = Object.prototype.hasOwnProperty.call(out, 'resultCollapsed');
  out.hasExpanded = Object.prototype.hasOwnProperty.call(out, 'resultExpanded');
  // 控制体积：超过 ~48KB 的不带（app 会退回文本/HTML）
  const size = JSON.stringify(out).length;
  if (process.env.PP_TOOL_TERM_DEBUG) console.warn('[bridge] term ' + name + ' size=' + size + ' call=' + Boolean(out.call) + ' rc=' + Boolean(out.resultCollapsed) + ' re=' + Boolean(out.resultExpanded));
  if (size > 48000) return null;
  if (toolTermCache.size > TOOL_HTML_CACHE_MAX) toolTermCache.clear();
  toolTermCache.set(key, out);
  return out;
}

async function toolHtml(name, args, result) {
  if (!name || !result) return null;
  const kit = await loadPiToolKit(DEFAULT_CWD);
  const def = (kit && kit.defs && kit.defs[name]) || extraRenderers(name);
  if (!def || typeof def.renderResult !== 'function') return null;
  const content = Array.isArray(result.content) ? result.content : [{ type: 'text', text: String(result.content == null ? '' : result.content) }];
  const details = result.details;
  const isError = Boolean(result.isError);
  const diffLen = details && typeof details.diff === 'string' ? details.diff.length : 0;
  const key = name + '|' + String(result.toolCallId || '') + '|' + content.length + '|' + diffLen + '|' + (isError ? 'E' : 'A');
  const hit = toolHtmlCache.get(key);
  if (hit) return hit;
  const agentResult = { content, details, isError };
  const out = {};
  for (const expanded of [false, true]) {
    try {
      const ctx = {
        args: args || {}, toolCallId: String(result.toolCallId || 'x'), invalidate: () => {},
        lastComponent: undefined, state: {}, cwd: kit.cwd, executionStarted: true,
        argsComplete: true, isPartial: false, expanded, showImages: false, isError,
        durationMs: result && Number.isFinite(Number(result.durationMs)) ? Number(result.durationMs) : undefined,
        outputPad: 1,
      };
      const component = def.renderResult(agentResult, { expanded, isPartial: false }, kit.theme, ctx);
      const html = kit.ansiLinesToHtml(component.render(80)) || '';
      if (html) out[expanded ? 'htmlExpanded' : 'htmlCollapsed'] = html;
    } catch (e) {
      if (process.env.PP_TOOL_HTML_DEBUG) console.warn('[bridge] 渲染 ' + name + ' 失败：' + e.message);
    }
  }
  if (!out.htmlCollapsed && !out.htmlExpanded) return null;
  if (toolHtmlCache.size > TOOL_HTML_CACHE_MAX) toolHtmlCache.clear();
  toolHtmlCache.set(key, out);
  return out;
}



// ── pi 子进程（一个活跃会话一个 worker） ──────────────────────────────────────
class PiWorker {
  constructor(cwd, sessionFile = null) {
    this.cwd = cwd;
    this.channel = null;
    this.events = new OrderedEvents(broadcast);
    this.alive = false;
    this.idleTimer = null;
    this.child = null;
    this.stdoutBuf = '';
    this.stderrTail = [];
    /** pi 当前是否在跑一轮（由 agent_start/agent_settled 事件维护）。 */
    this.streaming = false;
    /** 这个 worker 当前打开的会话文件（app 用它把事件对上会话）。 */
    this.sessionFile = sessionFile;
    /** 最后一条 assistant 的正文 / 结局：`agent_end` 自己不带这些，通知摘要靠它们。 */
    this.lastAssistantText = '';
    this.lastAssistantStop = '';
    this.lastAssistantError = '';
    /** 用户在本 app 里主动停的（agent_end 时据此把通知写成「已停止」）。 */
    this.abortedByUser = false;
    /** 已经绑在 sessionFile 上了（spawn 时就带了 `--session`，或事后 switch 过）。 */
    this.bound = Boolean(sessionFile && fs.existsSync(sessionFile));
  }

  start() {
    if (this.alive) return;
    // 已知会话文件就直接 `pi --session <path>` 起来（等于终端里 `pi --session <path>`）。
    // 不这样做的话 pi 会先建一个空会话文件、再被 switch_session 丢在一边 —— 会话列表里就多一条空会话。
    const bound = this.sessionFile && fs.existsSync(this.sessionFile) ? ['--session', this.sessionFile] : [];
    const child = spawn(PI_BIN, ['--mode', 'rpc', ...appProfileArgs(), ...bound], {
      cwd: this.cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, ...appProfileEnv() },
    });
    this.child = child;
    this.alive = true;
    this.stdoutBuf = '';
    this.channel = new RpcChannel((payload, callback) => child.stdin.write(payload, callback));
    const channel = this.channel;
    child.stdin.on('error', error => channel.close(error));
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => this.#onStdout(chunk));
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => {
      this.stderrTail.push(chunk);
      if (this.stderrTail.length > 40) this.stderrTail.shift();
    });
    child.on('exit', (code, signal) => {
      channel.close(new Error(`pi exited (code=${code} signal=${signal})`));
      if (this.child !== child) return;
      this.alive = false;
      this.streaming = false;
      child.stdout.removeAllListeners('data');
      log(`pi worker exited code=${code} signal=${signal}`);
      emitBridge('worker_exit', { sessionFile: this.sessionFile, code, signal, stderr: this.stderrTail.join('').slice(-2000) });
    });
    child.on('error', (err) => {
      channel.close(err);
      if (this.child !== child) return;
      this.alive = false;
      this.streaming = false;
      log('pi spawn error', err.message);
      emitBridge('worker_error', { sessionFile: this.sessionFile, message: err.message });
    });
    emitBridge('worker_started', { cwd: this.cwd, pid: child.pid });
  }

  // pi 的 framing 是严格 JSONL：只在 LF 切；不要用会认 U+2028/U+2029 的 readline。
  #onStdout(chunk) {
    this.stdoutBuf += chunk;
    let idx;
    while ((idx = this.stdoutBuf.indexOf('\n')) >= 0) {
      let line = this.stdoutBuf.slice(0, idx);
      this.stdoutBuf = this.stdoutBuf.slice(idx + 1);
      if (line.endsWith('\r')) line = line.slice(0, -1);
      if (!line.trim()) continue;
      let record;
      try { record = JSON.parse(line); } catch { broadcast({ kind: 'pi_raw', line }); continue; }
      if (this.channel.accept(record)) continue;
      // 嵌套调用（codemode 脚本里的 `tools.*`、MCP 子调用…）**与终端一致：不上屏**。
      //
      // 依据（用户 2026-10-21 追问的「gitea 卡」）：
      // · pi 自己就是这么干的：TUI 收到带 `parentToolCallId` 的 tool_execution_start 直接 break；
      // · 嵌套调用**不写进会话**（只挂在父调用的 result.details 里）⇒ 历史里根本没有它，
      //   app 若按实时事件建卡，就会多出一张「刷新/重进后就消失」的幽灵卡；
      // · 父卡自己的终端渲染（codemode 的 `✓ mcp__gitea__list_commits …` 行）已经把内层调用写出来了。
      if (record && record.parentToolCallId && String(record.type || '').startsWith('tool_execution')) {
        if (process.env.PP_TOOL_TERM_DEBUG) console.warn('[bridge] 跳过嵌套工具事件 ' + record.type + ' ' + record.toolName);
        continue;
      }
      noteStreaming(record, this);
      // 通知摘要：记下最后一条助手正文（agent_end 事件本身不带文本）
      if (record?.type === 'message_end' && record.message?.role === 'assistant') {
        const text = (record.message.content || [])
          .filter((c) => c?.type === 'text').map((c) => c.text).join('').trim();
        if (text) this.lastAssistantText = text;
        // 结局也要记：`agent_end` 只有 messages / willRetry，既没文本也没结局；
        // stopReason / errorMessage 在 assistant 消息上（docs/message-types.md）。
        // 用户 2026-10-24：以前没接这两项 ⇒ 报错也弹「回答完成」。
        this.lastAssistantStop = String(record.message.stopReason || '');
        this.lastAssistantError = String(record.message.errorMessage || '');
      }
      if (record?.type === 'agent_end') {
        const notice = turnEndNotice({
          abortedByUser: this.abortedByUser,
          aborted: record.aborted === true,
          stopReason: this.lastAssistantStop,
          errorMessage: this.lastAssistantError,
          text: this.lastAssistantText,
        });
        this.abortedByUser = false;
        notifyPhone({ ...notice, file: this.sessionFile || null });
        this.lastAssistantText = '';
        this.lastAssistantStop = '';
        this.lastAssistantError = '';
      }
      // 「有人在等你回答」：pi 弹 confirm/select/input/editor 时也叫一下人（旧版本只有前台能看到卡片）。
      if (record?.type === 'extension_ui_request') {
        const notice = uiPromptNotice(record);
        const key = notice ? `${this.sessionFile || 'nofile'}|${notice.requestId || ''}` : null;
        if (notice && shouldNotifyOnce(uiNoticeSeen, key)) {
          notifyPhone({
            title: notice.title,
            text: notice.text,
            file: this.sessionFile || null,
            kind: notice.kind,
          });
        }
      }
      // 工具开始/结束时把「终端显示文本」一起带上，app 直接照着画
      // tool_execution_update：工具**跑的一半**也要能像终端那样实时长出来（用户：poll 这类动态显示的
      // 工具要同步支持）。以前桥只广播原始 record，app 只能拿 partialResult 纯文本画；
      // 现在同样走 pi 自己的 renderResult（isPartial=true）⇒ 运行中的块与完成后同形。
      if (record && (record.type === 'tool_execution_start' || record.type === 'tool_execution_end' || record.type === 'tool_execution_update')) {
        // start 也渲染 term 的 call 头行（`$ cmd`），否则运行中工具卡只能退回「工具名」标题
        // （用户报的「linux 命令显示成 exec_command」）。start 传 null result：只生成 call，不生成结果块。
        const end = record.type === 'tool_execution_end';
        const update = record.type === 'tool_execution_update';
        // **参数缓存**：pi 的 end 事件不保证带 args，缺了 pi 的渲染器就只画得出 `$ ...`
        // （用户 2026-10-21 截图里的那张卡就是这么来的）。end/update 时优先用 record.args，否则回退 start 那份。
        if (record.toolCallId && record.args) {
          argsByToolCallId.set(String(record.toolCallId), record.args);
          if (argsByToolCallId.size > 400) {
            const first = argsByToolCallId.keys().next().value;
            argsByToolCallId.delete(first);
          }
        }
        const rawArgs = record.args || argsByToolCallId.get(String(record.toolCallId || '')) || {};
        // exec_command 两个名字都有（`cmd` / `command`）：pi 的终端渲染器只认 `cmd`，
        // 只给 `command` 时它画 `$ ...`（实拍踩到）。这里补齐，不改原 args。
        const argsForRender = (rawArgs && rawArgs.command && !rawArgs.cmd)
          ? { ...rawArgs, cmd: rawArgs.command }
          : rawArgs;
        const resultArg = (end || update)
          ? { ...(update ? (record.partialResult || {}) : (record.result || {})), toolCallId: record.toolCallId }
          : null;
        this.events.enqueue(record, this.sessionFile || null, () => Promise.all([
          toolDisplay(record.toolName, argsForRender),
          end
            ? toolHtml(record.toolName, argsForRender, resultArg)
            : Promise.resolve(null),
          toolTerm(record.toolName, argsForRender, resultArg, termCols, update),
        ]).then(([display, html, term]) => {
          const extra = {};
          if (display) extra.display = display;
          if (html) extra.html = html;
          const fallbackTerm = term || readPocketTerm(record.toolCallId);
          if (fallbackTerm) extra.term = fallbackTerm;
          return extra;
        }));
        continue;
      }
      this.events.enqueue(record, this.sessionFile || null);
    }
  }

  request(type, params = {}, timeoutMs = 120000) {
    this.start();
    return this.channel.request(type, params, timeoutMs);
  }

  async call(type, params, timeoutMs) {
    const res = await this.request(type, params, timeoutMs);
    if (!res.success) throw new Error(res.error || `pi command failed: ${type}`);
    return res.data ?? {};
  }

  // 非命令记录（扩展 UI 的回应就是这类）：直接写一行 JSON 进 pi 的 stdin
  sendRaw(record) {
    this.start();
    if (!this.child?.stdin?.writable) throw new Error('pi worker is not writable');
    this.child.stdin.write(JSON.stringify(record) + '\n');
  }

  touch() {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    if (!IDLE_MS) return;
    this.idleTimer = setTimeout(() => {
      // 正在跑的一轮不回收：app 崩了/被杀也要让 pi 把活跑完（用户口径）
      if (this.streaming) {
        log('idle reap skipped: turn still running');
        this.touch();
        return;
      }
      log('idle reap: stopping pi worker (session stays on disk)');
      this.stop('idle');
    }, IDLE_MS);
    this.idleTimer.unref?.();
  }

  stop(reason = 'manual') {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = null;
    if (!this.alive || !this.child) return;
    const child = this.child;
    log(`stopping pi worker (${reason})`);
    try { child.stdin.end(); } catch { /* ignore */ }
    setTimeout(() => { if (child.exitCode === null && child.signalCode === null) { try { child.kill('SIGTERM'); } catch { /* ignore */ } } }, 1500).unref?.();
  }
}

/**
 * **每个会话一个 pi 子进程**（用户口径：切走再回来，原来的会话不能被打断）。
 *
 * pi 的 RPC 是"一个进程一个会话"；以前全局只有一个 worker，`switch_session` 就把上一个
 * 会话的运行上下文切走了 —— 表现就是"切走时状态没了，回来显示工作中却没有 thinking/工具输出"。
 * 现在按会话文件分 worker；app 通过 /v1/sessions/open 把 activeFile 切到它的会话。
 */
const workers = new Map();      // sessionFile -> PiWorker
let DEFAULT_KEY = '';           // 还没绑定会话时（新建会话）用这个 key
let activeFile = null;          // app 当前在看哪个会话

function workerFor(file) {
  const key = file || DEFAULT_KEY;
  let w = workers.get(key);
  if (!w || !w.alive) {
    // 有会话文件：spawn 时就绑上（cwd 也用它自己记的，别把会话拽到桥的默认 cwd）
    const sessionCwd = file ? readHeader(file)?.cwd : null;
    const cwd = sessionCwd && fs.existsSync(sessionCwd) ? sessionCwd : DEFAULT_CWD;
    w = new PiWorker(cwd, file || null);
    workers.set(key, w);
  }
  w.touch();
  return w;
}

/** 当前请求该用哪个 worker：app 正在看的那个会话。 */
function getWorker() {
  if (activeFile) return workerFor(activeFile);
  return workerFor(null);
}

/** 只切"当前会话"，**不要**顺手创建 worker（否则会造出没绑会话的空 worker）。 */
function setActiveFile(file) {
  activeFile = file || null;
  // 落盘：桥重启（app 的「强制重启桥」/自更新/崩溃）后要读回来，否则下一条消息会落进新会话
  try {
    writeJsonSafe(STATE_FILE, { activeFile, updatedAt: new Date().toISOString() });
  } catch (err) {
    log('state persist failed:', err.message);
  }
}

/** /v1/reload 用：只回收指定的（或当前的）worker。 */
function recycleWorkerFor(file) {
  const key = file || activeFile || DEFAULT_KEY;
  const w = workers.get(key);
  if (w) { try { w.stop('reload'); } catch { /* ignore */ } workers.delete(key); }
}

// ── 会话树（pi 的 get_tree → app 画树用）────────────────────────────────────

/**
 * pi 的树（`SessionTreeNode[]`，含完整 message）→ 扁平列表（DFS 序，`parentId` 保留）。
 *
 * 只留树上要用的字段：完整 message 可能有几 MB（图片/工具输出），手机上没必要全传。
 */
function flattenTree(tree) {
  const out = [];
  const walk = (node, label, labelTs) => {
    const entry = node.entry || {};
    const proj = projectTreeEntry(entry);
    // 标签会累积到节点上（pi 的 getTree 已经解析好了）
    proj.label = node.label ?? label ?? null;
    proj.labelTimestamp = node.labelTimestamp ?? labelTs ?? null;
    out.push(proj);
    for (const child of node.children || []) walk(child, node.label, node.labelTimestamp);
  };
  for (const root of tree) walk(root);
  return out;
}

/**
 * fork / clone 后的**重绑**。
 *
 * pi 在 fork 时是「把这棵树的一个分支写成**新的会话文件**」（`createBranchedSession`），
 * 进程还是原来那个、但已经在别的会话上了。桥里 worker 是按会话文件索引的 ⇒ 三件事必须做：
 *   ① `w.sessionFile` 换成新文件（事件按它归属，不换的话 app 会把事件当“别的会话”扔掉）；
 *   ② `workers` 的 key 换成新文件（旧的删掉，否则同一个进程被挂两份）；
 *   ③ `activeFile` 指到新文件（否则下一条消息落回分叉前的会话）。
 */
async function rebindWorkerAfterFork(w, oldFile) {
  const state = await w.call('get_state', {}, 60000);
  const next = state?.sessionFile || null;
  if (!next) return state;
  if (oldFile && oldFile !== next) workers.delete(oldFile);
  w.sessionFile = next;
  w.bound = true;
  workers.set(next, w);
  setActiveFile(next);
  log(`fork rebind: ${oldFile || '(none)'} → ${next}`);
  broadcast({ kind: 'bridge', event: 'forked', from: oldFile || null, to: next, at: Date.now() });
  return state;
}

/**
 * 行为开关的快照（`GET/POST /v1/behavior`）。
 *
 * `get_state` 给了 steeringMode / followUpMode / autoCompactionEnabled，
 * 但 **autoRetryEnabled 不在里面** —— pi 把它放在 `~/.pi/agent/settings.json` 的
 * `retry.enabled`（`core/settings-manager.ts:893`），所以这里直接读文件（只读）。
 */
async function behaviorSnapshot(w) {
  const state = await w.call('get_state', {}, 60000);
  const settings = readAgentSettings();
  return {
    steeringMode: state?.steeringMode || 'one-at-a-time',
    followUpMode: state?.followUpMode || 'one-at-a-time',
    autoCompaction: Boolean(state?.autoCompactionEnabled),
    // 读不到就当开（pi 的 SETTINGS_DEFAULTS.retry.enabled）
    autoRetry: settings?.retry?.enabled !== false,
    isStreaming: Boolean(state?.isStreaming),
    isCompacting: Boolean(state?.isCompacting),
    pendingMessageCount: state?.pendingMessageCount ?? 0,
    messageCount: state?.messageCount ?? 0,
    sessionFile: state?.sessionFile || null,
    // pi 的重试参数（给 UI 解释“重试 n/m 是怎么来的”）：只读
    retry: {
      maxRetries: settings?.retry?.maxRetries ?? null,
      baseDelayMs: settings?.retry?.baseDelayMs ?? null,
    },
  };
}

/** 读 pi 的**全局**设置（`~/.pi/agent/settings.json`）；解不开就返回 null。 */
function readAgentSettings() {
  const agentDir = process.env.PI_CODING_AGENT_DIR || path.join(os.homedir(), '.pi', 'agent');
  try {
    return JSON.parse(fs.readFileSync(path.join(agentDir, 'settings.json'), 'utf8'));
  } catch {
    return null;
  }
}

/** fork / clone 的公共流程（pi 里两者都是 `runtimeHost.fork`，差别只在 position）。 */async function forkOrClone(kind, params, body) {
  const w = getWorker();
  const oldFile = w.sessionFile || activeFile || null;
  const data = await w.call(kind, params, 180000);
  if (data?.cancelled) return { ...data, kind, state: null };
  const state = await rebindWorkerAfterFork(w, oldFile);
  // 分叉出来的新会话名字默认是空的 —— 给个能认出来的默认名（pi 的 /auto-title 也能改）
  if (body?.name !== false) {
    try {
      const base = pickName(oldFile, fs.statSync(oldFile).size) || '会话';
      const suffix = kind === 'clone' ? '副本' : '分支';
      await w.call('set_session_name', { name: `${base} · ${suffix}` }, 60000);
      state.sessionName = `${base} · ${suffix}`;
    } catch { /* 没有旧文件/命名失败都不影响分叉 */ }
  }
  return { ...data, kind, state, file: state?.sessionFile || null };
}

/** app 里改了注入开关：干掉**所有** worker（之后按新选择重新拉起 pi）。 */
/** 设备信息变了但当时正在跑：等这一轮结束再回收（见 agent_settled 处理）。 */
let pendingDeviceRecycle = false;

/**
 * 该回收就回收：没有在跑的轮次才动手。
 * 除了 agent_settled，还挂了个定时兜底 —— 事件丢了（worker 崩、SSE 断）也不能让新提示永远不生效。
 */
function maybeRecycleForDevice() {
  if (!pendingDeviceRecycle) return;
  const busy = [...workers.values()].some((w) => w.alive && w.streaming);
  if (busy) {
    setTimeout(maybeRecycleForDevice, 5000);
    return;
  }
  pendingDeviceRecycle = false;
  recycleWorker('device info changed (deferred)');
  log('device updated：本轮结束，已回收 pi 子进程');
}

function recycleWorker(reason) {
  for (const [key, w] of workers) {
    try { w.stop(reason); } catch { /* ignore */ }
    workers.delete(key);
  }
  activeFile = null;
}

// ── 会话列表（pi 没有「列会话」的 RPC 命令，直接扫盘；只读，不改） ─────────────
const NAME_TAIL_BYTES = 512 * 1024;

function pickName(file, size) {
  // pi 的会话标题写在 `session_info` 行里（/auto-title 也是写它）。
  // 关键：**必须取最后一条**，而且它可能不在文件尾部那点窗口里 ——
  // 大会话（几 MB）里标题行可能被后面几十条消息挤到 512KB 之外，之前就是这样读不到、
  // 列表只好退回"第一条用户消息"。现在：先读尾部 4MB，再不行整文件扫（上限 32MB）。
  const scan = (buf) => {
    let found = null;
    for (const line of buf.toString('utf8').split(String.fromCharCode(10))) {
      if (!line.includes('"name"') || !line.includes('session_')) continue;
      try {
        const obj = JSON.parse(line);
        if (obj?.name && (obj.type === 'session_info' || obj.type === 'session_name')) found = obj.name;
      } catch { /* 半行或非 JSON，略过 */ }
    }
    return found;
  };

  try {
    const fd = fs.openSync(file, 'r');
    try {
      const tailLen = Math.min(size, 4 * 1024 * 1024);
      const tail = Buffer.alloc(tailLen);
      fs.readSync(fd, tail, 0, tailLen, size - tailLen);
      const fromTail = scan(tail);
      if (fromTail) return fromTail;

      if (size > tailLen) {
        const headLen = Math.min(size, 32 * 1024 * 1024);
        const all = Buffer.alloc(headLen);
        fs.readSync(fd, all, 0, headLen, 0);
        const fromAll = scan(all);
        if (fromAll) return fromAll;
      }
    } finally {
      fs.closeSync(fd);
    }
  } catch { /* 读不到就当没有名字 */ }
  return null;
}

function firstUserSnippet(file, maxBytes = 128 * 1024) {
  const pick = (buf, from) => {
    for (const line of buf.toString('utf8').split('\n')) {
      if (!line.includes('"role":"user"')) continue;
      try {
        const obj = JSON.parse(line);
        const msg = obj?.message ?? obj;
        const content = msg?.content;
        let text = '';
        if (typeof content === 'string') text = content;
        else if (Array.isArray(content)) {
          text = content
            .filter((c) => c?.type === 'text' && typeof c.text === 'string')
            .map((c) => c.text)
            .join(' ');
        }
        const one = String(text).replace(/\s+/g, ' ').trim();
        // 跳过纯附件/占位消息
        if (one && one !== '[图片]' && !one.startsWith('<')) {
          return one.length > 48 ? one.slice(0, 48) + '…' : one;
        }
      } catch { /* 半行跳过 */ }
    }
    return null;
  };

  try {
    const st = fs.statSync(file);
    const headLen = Math.min(st.size, maxBytes);
    const fd = fs.openSync(file, 'r');
    try {
      const head = Buffer.alloc(headLen);
      fs.readSync(fd, head, 0, headLen, 0);
      const found = pick(head);
      if (found) return found;

      if (st.size > headLen) {
        const tailLen = Math.min(st.size - headLen, maxBytes);
        const tail = Buffer.alloc(tailLen);
        fs.readSync(fd, tail, 0, tailLen, st.size - tailLen);
        const fromTail = pick(tail);
        if (fromTail) return fromTail;
      }
    } finally {
      fs.closeSync(fd);
    }
  } catch { /* 读不到就交给下一级兜底 */ }
  return null;
}

/**
 * 标题的最终兜底：用会话 cwd 的目录名（`/home/user/workspace` → `workspace`）。
 * 仍然拿不到才返回 null（此时列表会用 id，但至少已经尽力）。
 */
function cwdTitle(cwd) {
  if (!cwd) return null;
  const base = path.basename(String(cwd).replace(/[\/]+$/, ''));
  return base && base !== '/' ? base : null;
}

/**
 * `contextUsage` 缺失时的兜底估算 —— **照 pi 终端自己的算法**（dist/core/agent-session.js `getContextUsage`）：
 *
 *   1. 只信「最后一次压缩之后」的 assistant usage（压缩前的 usage 反映的是压缩前的上下文，不能用）；
 *   2. `contextTokens = usage.totalTokens || input+output+cacheRead+write`；
 *   3. 再加上**该条之后**所有消息的估算量（pi 用 字符数/4）；
 *   4. 百分比 = tokens / contextWindow。
 *
 * 这样和终端 footer 显示的百分比一致（同一套口径），只是我们是从会话文件里复算。
 */
function estimateContextFromFile(file, contextWindow) {
  if (!file || !contextWindow) return null;
  let fd;
  try {
    const st = fs.statSync(file);
    const len = Math.min(st.size, 512 * 1024);
    const buf = Buffer.alloc(len);
    fd = fs.openSync(file, 'r');
    fs.readSync(fd, buf, 0, len, st.size - len);
    const lines = buf.toString('utf8').split(String.fromCharCode(10));

    // ① 找最后一次压缩的位置（只信它之后的 usage）
    let lastCompaction = -1;
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].includes('"type":"compaction"')) lastCompaction = i;
    }

    // ② 找最后一条有效 assistant usage（跳过 aborted/error）
    let usageTokens = 0;
    let usageIndex = -1;
    for (let i = lines.length - 1; i > lastCompaction; i--) {
      const line = lines[i];
      if (!line.includes('"usage"')) continue;
      let obj;
      try { obj = JSON.parse(line); } catch { continue; }
      const msg = obj?.message ?? obj;
      if (msg?.role !== 'assistant') continue;
      if (msg.stopReason === 'aborted' || msg.stopReason === 'error') continue;
      const u = msg.usage;
      if (!u) continue;
      const total = Number(u.totalTokens || 0) ||
        (Number(u.input || 0) + Number(u.output || 0) + Number(u.cacheRead || 0) + Number(u.cacheWrite || 0));
      if (total <= 0) continue;
      usageTokens = total;
      usageIndex = i;
      break;
    }

    // ③ 尾随消息估算（pi: 字符数 / 4）
    let trailingChars = 0;
    const collectChars = (content) => {
      if (typeof content === 'string') return content.length;
      if (!Array.isArray(content)) return 0;
      let chars = 0;
      for (const block of content) {
        if (block?.type === 'text' && block.text) chars += block.text.length;
        else if (block?.type === 'thinking' && block.thinking) chars += block.thinking.length;
        else if (block?.type === 'toolCall') chars += (block.name || '').length + JSON.stringify(block.arguments || {}).length;
        else if (block?.type === 'image') chars += 4800;
      }
      return chars;
    };
    for (let i = Math.max(usageIndex + 1, lastCompaction + 1); i < lines.length; i++) {
      const line = lines[i];
      if (!line.includes('"role"')) continue;
      let obj;
      try { obj = JSON.parse(line); } catch { continue; }
      const msg = obj?.message ?? obj;
      if (!msg?.role || msg.role === 'assistant') continue;
      if (msg.role === 'bashExecution') { trailingChars += (msg.command || '').length + (msg.output || '').length; continue; }
      trailingChars += collectChars(msg.content);
    }

    let tokens = usageTokens + Math.ceil(trailingChars / 4);
    // 完全拿不到 usage（比如刚压缩完）：按尾随消息估算，但宁可给 null 也不编数字
    if (usageTokens === 0) {
      let allChars = 0;
      for (let i = Math.max(lastCompaction + 1, 0); i < lines.length; i++) {
        if (!lines[i].includes('"role"')) continue;
        try {
          const obj = JSON.parse(lines[i]);
          const msg = obj?.message ?? obj;
          if (msg?.role) allChars += collectChars(msg.content);
        } catch { /* skip */ }
      }
      if (allChars === 0) return null;
      tokens = Math.ceil(allChars / 4);
      return { tokens, contextWindow, percent: Math.min(100, (tokens / contextWindow) * 100), estimated: true, estimatedOnly: true };
    }
    return { tokens, contextWindow, percent: Math.min(100, (tokens / contextWindow) * 100), estimated: true };
  } catch { /* ignore */ } finally {
    if (fd !== undefined) { try { fs.closeSync(fd); } catch { /* ignore */ } }
  }
  return null;
}

function readHeader(file) {
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const buf = Buffer.alloc(8192);
      const n = fs.readSync(fd, buf, 0, buf.length, 0);
      const first = buf.toString('utf8', 0, n).split('\n')[0];
      return JSON.parse(first);
    } finally { fs.closeSync(fd); }
  } catch { return null; }
}

/** 会话名缓存：key = file|mtime|size（会话变长才重算）。列 40 条会话原来要读几十 MB，太慢。 */
const nameCache = new Map();

function sessionNameCached(file, st) {
  const key = `${file}|${st.mtimeMs}|${st.size}`;
  const hit = nameCache.get(key);
  if (hit !== undefined) return hit;
  const piName = pickName(file, st.size);
  const firstMsg = piName ? null : firstUserSnippet(file);
  const header = readHeader(file);
  const value = {
    name: piName || firstMsg || cwdTitle(header?.cwd) || header?.id || null,
    nameSource: piName ? 'pi' : (firstMsg ? 'firstMessage' : (cwdTitle(header?.cwd) ? 'cwd' : 'id')),
  };
  nameCache.set(key, value);
  // 控制内存：最多 500 条
  if (nameCache.size > 500) nameCache.delete(nameCache.keys().next().value);
  return value;
}

function listSessions(limit = 40) {
  const out = [];
  let dirs = [];
  try { dirs = fs.readdirSync(SESSIONS_ROOT, { withFileTypes: true }).filter((d) => d.isDirectory()); } catch { return out; }
  for (const d of dirs) {
    const dir = path.join(SESSIONS_ROOT, d.name);
    let files = [];
    try { files = fs.readdirSync(dir).filter((f) => f.endsWith('.jsonl')); } catch { continue; }
    for (const f of files) {
      const full = path.join(dir, f);
      let st;
      try { st = fs.statSync(full); } catch { continue; }
      const header = readHeader(full);
      const named = sessionNameCached(full, st);
      out.push({
        file: full,
        id: header?.id || f.replace(/\.jsonl$/, '').split('_').pop(),
        cwd: header?.cwd || null,
        createdAt: header?.timestamp || null,
        updatedAt: new Date(st.mtimeMs).toISOString(),
        bytes: st.size,
        // 优先 pi 自己的会话标题（session_info.name），没有就退回**第一条用户输入**，
        // 最后才是原始 id —— 用户口径：不要拿"用户最后输入"当标题，也别把 id 当标题。
        name: named.name,
        nameSource: named.nameSource,
        // 这条会话是不是真的在跑（worker 的 streaming，由 agent_start/settled 维护）
        running: Boolean(workers.get(full)?.alive && workers.get(full)?.streaming),
      });
    }
  }
  out.sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1));
  return out.slice(0, limit);
}

// ── HTTP ─────────────────────────────────────────────────────────────────────
function json(res, code, body) {
  const text = JSON.stringify(body);
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(text) });
  res.end(text);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { reject(new Error(`bad json: ${e.message}`)); }
    });
    req.on('error', reject);
  });
}

// 历史里的图片 base64 对手机太重：默认只留占位，需要原图时带 ?full=1
function stripImages(value) {
  if (Array.isArray(value)) return value.map(stripImages);
  if (value && typeof value === 'object') {
    if (value.type === 'image' && typeof value.data === 'string') {
      return { type: 'image', mimeType: value.mimeType, omitted: true, bytes: Math.floor((value.data.length * 3) / 4) };
    }
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = stripImages(v);
    return out;
  }
  return value;
}

const normalizeImages = (images) => (Array.isArray(images) ? images : [])
  .filter((i) => i && typeof i.data === 'string')
  .map((i) => ({ type: 'image', mimeType: i.mimeType || 'image/jpeg', data: i.data }));


// ── 远端文件（SFTP 语义）─────────────���───────────────────────────────────────────
// 用户 2026-10-21 口径：**别再让 app 存一套 SSH 凭据**，直接走 Termux 自己的 `~/.ssh/config`
// （别名 / IdentityFile / ssh-agent / known_hosts 全都复用）。
// 所以 app 只发「别名 + 路径」，真正的 ssh/sftp 由桥在 Termux 里 spawn。

/** 单引号安全转义（PATH 里可能有空格、中文、$）。 */
function shq(s) {
  return "'" + String(s).split("'").join("'\''") + "'";
}

/** 解析 ~/.ssh/config 的 Host 块；跳过通配符块（`Host *` 之类是默认值，不是可选目标）。 */
function sshConfigHosts() {
  const file = path.join(os.homedir(), '.ssh', 'config');
  let raw = '';
  try { raw = fs.readFileSync(file, 'utf8'); } catch { return { file, hosts: [] }; }
  const hosts = [];
  let cur = null;
  for (const line of raw.split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const m = t.match(/^(\w+)[\s=]+(.+)$/);
    if (!m) continue;
    const k = m[1].toLowerCase();
    const v = m[2].trim();
    if (k === 'host') {
      const aliases = v.split(/\s+/).filter((a) => a && !/[*?!]/.test(a));
      cur = aliases.length ? { alias: aliases[0], aliases, hostName: '', user: '', port: 22, identityFile: '' } : null;
      if (cur) hosts.push(cur);
      continue;
    }
    if (!cur) continue;
    if (k === 'hostname') cur.hostName = v;
    else if (k === 'user') cur.user = v;
    else if (k === 'port') cur.port = Number(v) || 22;
    else if (k === 'identityfile') cur.identityFile = v;
  }
  return { file, hosts };
}


/** 路径引用：`~` / `~/x` 展开成 "$HOME"（引号内 ~ 不会展开，这是会话 cwd 报错的根因）。 */
function qpath(p) {
  const s = String(p == null ? '' : p);
  if (s === '~') return '"$HOME"';
  if (s.startsWith('~/')) return '"$HOME"/' + shq(s.slice(2));
  return shq(s);
}

/** 跑一条远端命令（BatchMode：绝不卡在密码提示上）。 */
function runRemote(alias, remoteCmd, { input = null, timeoutMs = 20000, maxBytes = 8 << 20 } = {}) {
  const local = alias === 'local' || alias === 'termux';
  return new Promise((resolve, reject) => {
    const args = [
      '-o', 'BatchMode=yes',
      '-o', 'StrictHostKeyChecking=accept-new',
      '-o', `ConnectTimeout=${Math.max(3, Math.round(timeoutMs / 1000))}`,
      alias, '--', remoteCmd,
    ];
    const child = local
      ? spawn('sh', ['-c', remoteCmd], { stdio: ['pipe', 'pipe', 'pipe'] })
      : spawn('ssh', args, { stdio: ['pipe', 'pipe', 'pipe'] });
    const out = [];
    let size = 0;
    let err = '';
    const timer = setTimeout(() => { try { child.kill('SIGKILL'); } catch { /* ignore */ } reject(new Error(`ssh ${alias} 超时（${timeoutMs}ms）`)); }, timeoutMs + 5000);
    child.stdout.on('data', (b) => {
      size += b.length;
      if (size > maxBytes) { try { child.kill('SIGKILL'); } catch { /* ignore */ } reject(new Error('输出过大')); return; }
      out.push(b);
    });
    child.stderr.on('data', (b) => { err += b.toString(); if (err.length > 4000) err = err.slice(-4000); });
    child.on('error', (e) => { clearTimeout(timer); reject(e); });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code !== 0) return reject(new Error((err.trim() || `ssh exit ${code}`).split('\n').slice(-3).join(' ')));
      resolve(Buffer.concat(out));
    });
    if (input) child.stdin.end(input); else child.stdin.end();
  });
}


// ── 内置 slash 命令：**从 pi 自己的交互模式里抠出来**（用户口径：能继承终端就继承终端）──
// pi 没有导出"命令表"的 API：这些命令是 TUI 里 `handleSubmit` 的 if 链（interactive-mode.js）。
// 所以我们直接读那个文件，抽出 `text === "/x"` / `text.startsWith("/x ")` 两种形态：
//   · 出现 `startsWith("/x ")` ⇒ 该命令**带参数**（如 /model <q>、/compact <instructions>）
//   · 其余 ⇒ 无参
// 升级 pi 后这份表自动跟着变，不需要在 app 里手抄。
let slashCache = null;

function piInteractiveFiles() {
  const out = [];
  const cands = [];
  if (piDistRoot) cands.push(path.join(piDistRoot, 'modes/interactive/interactive-mode.js'));
  // Termux 里**没有 which**（已知坑）⇒ 必须走已知前缀，别指望 execFileSync('which')
  const REL = '@earendil-works/pi-coding-agent/dist/modes/interactive/interactive-mode.js';
  for (const prefix of [process.env.PREFIX, path.join(os.homedir(), '.local'), '/usr/local', '/usr', path.join(os.homedir(), '.npm-global')]) {
    if (!prefix) continue;
    cands.push(path.join(prefix, 'lib/node_modules', REL));
    cands.push(path.join(prefix, 'node_modules', REL));
  }
  for (const pkgRoot of [path.join(os.homedir(), '.pi/agent/npm/node_modules')]) {
    cands.push(path.join(pkgRoot, REL));
  }
  const which = (() => {
    try {
      return child_process.execFileSync(process.platform === 'win32' ? 'where' : 'which', ['pi'], { encoding: 'utf8' })
        .split(String.fromCharCode(10))[0].trim();
    } catch { return null; }
  })();
  if (which) {
    let dir = path.dirname(which);
    try { dir = path.dirname(fs.realpathSync(which)); } catch { /* ignore */ }
    for (let i = 0; i < 10 && dir && dir !== path.dirname(dir); i++) {
      // dir 可能是 .../dist/bundle（realpath 指向 bundle/cli.js）也可能已经是 .../dist
      cands.push(path.join(dir, 'modes/interactive/interactive-mode.js'));
      cands.push(path.join(dir, 'dist/modes/interactive/interactive-mode.js'));
      cands.push(path.join(dir, 'node_modules/@earendil-works/pi-coding-agent/dist/modes/interactive/interactive-mode.js'));
      dir = path.dirname(dir);
    }
  }
  for (const c of cands) if (fs.existsSync(c) && !out.includes(c)) out.push(c);
  return out;
}

/** app 侧能落地成 RPC 的那些（其余内置命令是 TUI 专属，如实标出来别假装能用）。 */
const SLASH_RPC = {
  model: { rpc: '/v1/model', args: true, label: '切换模型' },
  thinking: { rpc: '/v1/thinking', args: true, label: '思考等级' },
  compact: { rpc: '/v1/compact', args: true, label: '压缩上下文' },
  reload: { rpc: '/v1/reload', args: false, label: '重载会话（重启 pi）' },
  new: { rpc: '/v1/sessions/new', args: false, label: '新建会话' },
  name: { rpc: '/v1/rename', args: true, label: '重命名会话' },
  resume: { rpc: '/v1/sessions/open', args: true, label: '打开会话' },
  quit: { rpc: '/v1/restart', args: false, label: '重启 pi 进程' },
};

function readSlashCommands() {
  if (slashCache) return slashCache;
  const files = piInteractiveFiles();
  const names = new Map();
  for (const f of files) {
    let text = '';
    try { text = fs.readFileSync(f, 'utf8'); } catch { continue; }
    const reEq = /text === "\/([a-zA-Z0-9:_-]+)"/g;
    const reStartsWith = /text\.startsWith\("\/([a-zA-Z0-9:_-]+) "\)/g;
    let m;
    while ((m = reEq.exec(text))) names.set(m[1], names.get(m[1]) || false);
    while ((m = reStartsWith.exec(text))) names.set(m[1], true);
    if (names.size) { slashCache = { file: f, names }; break; }
  }
  if (!slashCache) slashCache = { file: null, names };
  const commands = [...slashCache.names.entries()].map(([name, args]) => {
    const rpc = SLASH_RPC[name];
    return {
      name: '/' + name,
      argsHint: args ? '<参数>' : '',
      args,
      source: 'pi',
      rpc: rpc ? rpc.rpc : null,
      // 能落地的标"可用"，TUI 专属的标"仅终端"
      description: rpc ? rpc.label : 'pi 终端专属（app 里不可用）',
      available: Boolean(rpc),
    };
  });
  return slashCache;
}


/**
 * 当前工作目录的 git 分支 —— **照 pi 的 `footer-data-provider.ts:239 resolveGitBranchSync()`**：
 * 直接读 `.git/HEAD`，不跑子进程（手机上 spawn 一次 git 要几十毫秒，且不一定装了 git）。
 *   · `.git` 是**目录** → 读 `.git/HEAD`；
 *   · `.git` 是**文件**（worktree / submodule）→ 读里面的 `gitdir: <path>`；
 *   · `ref: refs/heads/<name>` → 返回 `<name>`；
 *   · 其它（裸 SHA）⇒ `detached`（pi 的口径也是这个字符串）；
 *   · 找不到仓库 / 读失败 ⇒ null（页面上就不显示）。
 * 结果按 cwd 缓存 5 秒：footer 会频繁刷新，不能每次都读盘。
 */
const gitBranchCache = new Map(); // cwd -> { at, branch }
function gitBranchOf(cwd) {
  if (!cwd) return null;
  const hit = gitBranchCache.get(cwd);
  const now = Date.now();
  if (hit && now - hit.at < 5000) return hit.branch;
  let branch = null;
  try {
    let dir = cwd;
    for (let i = 0; i < 40; i++) {
      const dotGit = path.join(dir, '.git');
      if (fs.existsSync(dotGit)) {
        const st = fs.statSync(dotGit);
        let gitDir = dotGit;
        if (st.isFile()) {
          const line = fs.readFileSync(dotGit, 'utf8').trim();
          const m = /^gitdir:\s*(.+)$/.exec(line);
          if (!m) break;
          gitDir = path.resolve(dir, m[1].trim());
        }
        const head = fs.readFileSync(path.join(gitDir, 'HEAD'), 'utf8').trim();
        if (head.startsWith('ref: refs/heads/')) branch = head.slice('ref: refs/heads/'.length);
        else branch = 'detached';
        break;
      }
      const parent = path.dirname(dir);
      if (parent === dir) break;
      dir = parent;
    }
  } catch { branch = null; }
  gitBranchCache.set(cwd, { at: now, branch });
  return branch;
}

const routes = {
  // app 会话注入开关（app 里的「App 会话注入」面板读写这个）
  'GET /v1/app-profile': async () => appProfileSnapshot(),
  'POST /v1/app-profile': async (body) => {
    // 只接受这三类键，其余忽略；缺省 = 开
    const sel = readSelection();
    if (body && typeof body === 'object') {
      if (typeof body.appendPrompt === 'boolean') sel.appendPrompt = body.appendPrompt;
      if (body.skills && typeof body.skills === 'object') sel.skills = { ...(sel.skills || {}), ...body.skills };
      if (body.mcp && typeof body.mcp === 'object') sel.mcp = { ...(sel.mcp || {}), ...body.mcp };
      if (body.reset === true) {
        delete sel.appendPrompt;
        delete sel.skills;
        delete sel.mcp;
      }
    }
    writeSelection(sel);
    recycleWorker('app-profile changed');
    log('app profile updated', JSON.stringify(sel));
    return appProfileSnapshot();
  },

  // app 上报屏幕/字号/主题（app 一旋转、一改字号就来一次）。变了就回收 pi 子进程让新提示生效：
  // 正在跑的那一轮不能被打断 ⇒ 标记 pending，等 agent_settled/worker 停了再回收。
  'GET /v1/device': async () => readDevice() || {},
  'POST /v1/device': async (body) => {
    const d = body && typeof body === 'object' ? body : {};
    const changed = writeDevice(d);
    if (changed) {
      const busy = [...workers.values()].some((w) => w.alive && w.streaming);
      if (busy) {
        pendingDeviceRecycle = true;
        log('device updated（正在跑，稍后回收）', JSON.stringify(d));
        setTimeout(maybeRecycleForDevice, 5000);
      } else {
        recycleWorker('device info changed');
        log('device updated（已回收 pi 子进程）', JSON.stringify(d));
      }
    }
    return { saved: true, changed, device: readDevice() };
  },

  // 看交付物：文本直接回文本、图片回 base64、目录回列表（app 侧「查看交付」用）。
  // 范围只允许 bridge 用户的 home 与默认工作目录；>8MB 直接拒（避免 app 被大文件拖死）。
  'GET /v1/file': async (_body, url) => {
    const raw = url.searchParams.get('path') || '';
    if (!raw) throw new Error('path required');
    const resolved = path.resolve(raw.replace(/^~(?=\/|$)/, os.homedir()));
    const roots = [os.homedir(), DEFAULT_CWD].map((r) => path.resolve(r));
    if (!roots.some((r) => resolved === r || resolved.startsWith(r + path.sep))) {
      throw new Error('拒绝：只允许 home 或工作目录下的路径（' + resolved + '）');
    }
    const st = fs.statSync(resolved);
    if (st.isDirectory()) {
      const entries = fs.readdirSync(resolved, { withFileTypes: true }).slice(0, 300).map((e) => ({
        name: e.name,
        dir: e.isDirectory(),
      }));
      return { kind: 'dir', path: resolved, name: path.basename(resolved), entries };
    }
    if (st.size > 8 * 1024 * 1024) throw new Error('文件太大（' + Math.round(st.size / 1024) + 'KB > 8MB）');
    const buf = fs.readFileSync(resolved);
    const ext = path.extname(resolved).toLowerCase();
    const imageExt = ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp'].includes(ext);
    const textExt = ['.md', '.txt', '.json', '.jsonl', '.js', '.mjs', '.cjs', '.ts', '.tsx', '.jsx', '.kt', '.java', '.py', '.sh', '.bash', '.yml', '.yaml', '.toml', '.ini', '.csv', '.log', '.html', '.css', '.xml', '.sql'].includes(ext);
    return {
      kind: imageExt ? 'image' : (textExt ? 'text' : 'binary'),
      path: resolved,
      name: path.basename(resolved),
      size: st.size,
      mime: imageExt ? 'image/' + (ext === '.jpg' ? 'jpeg' : ext.slice(1)) : 'application/octet-stream',
      text: textExt ? buf.toString('utf8').slice(0, 200000) : undefined,
      base64: imageExt ? buf.toString('base64') : undefined,
    };
  },

  // 从**配置**里扫机器（用户口径：扫描也要能扫 config 里的机器）：
  //   · ~/.ssh/config 的 Host 段（user/hostname/port）
  //   · ~/.pi/agent/pi-pocket-hosts.json（本项目的机器清单，形如 [{host,port,user,token}]）
  // 只读，不改任何配置。
  'GET /v1/config/machines': async () => {
    const out = [];
    const sshConfig = path.join(os.homedir(), '.ssh', 'config');
    if (fs.existsSync(sshConfig)) {
      let host = null;
      const entries = [];
      for (const raw of fs.readFileSync(sshConfig, 'utf8').split('\n')) {
        const line = raw.trim();
        if (/^#/.test(line) || !line) continue;
        const m = /^(\w+)\s+(.+)$/.exec(line);
        if (!m) continue;
        const key = m[1].toLowerCase();
        const val = m[2].trim();
        if (key === 'host') {
          if (host) entries.push(host);
          host = { alias: val, host: '', user: '', port: 22, source: 'ssh-config' };
        } else if (host && key === 'hostname') host.host = val;
        else if (host && key === 'user') host.user = val;
        else if (host && key === 'port') host.port = Number(val) || 22;
      }
      if (host) entries.push(host);
      for (const e of entries) {
        if (!e.host) e.host = e.alias;
        if (!e.host || e.host.includes('*') || e.host.includes('?')) continue;
        out.push({ host: e.host, port: e.port, user: e.user, alias: e.alias, source: 'ssh-config', url: `http://${e.host}:8789` });
      }
    }
    const hostsFile = path.join(AGENT_DIR, 'pi-pocket-hosts.json');
    if (fs.existsSync(hostsFile)) {
      try {
        const list = JSON.parse(fs.readFileSync(hostsFile, 'utf8'));
        for (const item of Array.isArray(list) ? list : list.hosts || []) {
          if (!item?.host) continue;
          out.push({
            host: item.host,
            port: Number(item.port) || 8789,
            user: item.user || '',
            alias: item.name || item.host,
            source: 'pi-pocket-hosts',
            url: item.url || `http://${item.host}:${Number(item.port) || 8789}`,
            hasToken: Boolean(item.token),
          });
        }
      } catch { /* 解析不了就跳过 */ }
    }
    return { machines: out, sources: { sshConfig: fs.existsSync(sshConfig), hostsFile: fs.existsSync(hostsFile) } };
  },

  'GET /v1/health': async () => ({
    ok: true,
    pid: process.pid,
    sourceSha: process.env.PI_POCKET_SOURCE_SHA || null,
    bridge: BRIDGE_VERSION,
    host: os.hostname(),
    platform: process.platform,
    arch: process.arch,
    pi: PI_BIN,
    cwd: DEFAULT_CWD,
    sessionsRoot: SESSIONS_ROOT,
    bind: `${HOST}:${PORT}`,
    worker: Boolean(activeFile ? workers.get(activeFile)?.alive : workers.size > 0),
    workers: workers.size,
    activeFile: activeFile,
    subscribers: subscribers.size,
    auth: Boolean(TOKEN),
    startedAt: STARTED_AT,
  }),

  'GET /v1/info': async () => {
    const w = getWorker();
    const state = await w.call('get_state', {}, 60000);
    let stats = null;
    try { stats = await w.call('get_session_stats', {}, 60000); } catch { /* 空会话还没有统计 */ }
    // contextUsage 缺失（刚压缩完 / 虚模型不给）→ 用最后一条 assistant 的 usage 估算
    if (stats) {
      const cu = stats.contextUsage;
      if (!cu || cu.tokens == null || cu.percent == null) {
        const window0 = cu?.contextWindow || state?.model?.contextWindow || 0;
        const est = estimateContextFromFile(stats.sessionFile || state?.sessionFile, window0);
        if (est) stats.contextUsage = est;
      }
    }
    let levels = [];
    try { levels = (await w.call('get_available_thinking_levels', {}, 60000)).levels || []; } catch { /* 模型未定 */ }
    return {
      state, stats, levels, cwd: w.cwd, modelDisplay: modelDisplayOf(state, w),
      // git 分支（终端 footer 的 `~/p (main)` 那一段）；不是仓库就是 null
      gitBranch: gitBranchOf(w.cwd),
    };
  },

  'GET /v1/sessions': async (body, url) => ({ sessions: listSessions(Number(url.searchParams.get('limit') || 40)) }),

  /**
   * 重载会话：把当前 pi 子进程杀掉重起，再 switch_session 回原来那个会话文件。
   *
   * 用途（用户口径「我要 reload 旧会话」）：换过 skills / app-profile / 扩展之后，
   * 旧会话里的 pi 还是老进程，`/reload` 也只重载扩展、不一定带上 app 注入的 skills；
   * 这里直接**换一个干净的 pi 进程**，会话内容仍在磁盘上、随后重新打开，等于在终端里
   * "退出 pi → pi --continue" 的效果。
   */
  /**
   * 强制重启桥（app 的「强制重启桥」按钮）。
   *
   * 派一个 detached 的重启脚本（FORCE=1 start-bridge.sh：pkill 旧进程 → 拉起新进程），
   * 响应先发出去，随后本进程会被脚本杀掉，app 自己重连。
   */
  'POST /v1/restart': async () => {
    const script = path.join(AGENT_DIR, 'start-bridge.sh');
    if (!fs.existsSync(script)) throw new Error('缺少 ' + script + '（先跑 termux/install.sh 或自更新）');
    const child = spawn('bash', ['-lc', 'FORCE=1 setsid -f bash ' + JSON.stringify(script)], {
      detached: true,
      stdio: 'ignore',
    });
    child.unref();
    log('restart requested via /v1/restart');
    return { restarting: true, script };
  },

  'POST /v1/reload': async (body) => {
    const file = body?.file || null;
    let sessionFile = file;
    let cwd = DEFAULT_CWD;
    if (!sessionFile) {
      const cur = activeFile ? workers.get(activeFile) : null;
      if (!cur?.alive) return { reloaded: false, reason: 'no active session' };
      try {
        const state = await cur.call('get_state', {}, 30000);
        sessionFile = state?.sessionFile || null;
        cwd = state?.cwd || cur.cwd || DEFAULT_CWD;
      } catch { /* 拿不到就按默认 cwd 重起 */ }
    } else if (sessionFile && fs.existsSync(sessionFile)) {
      cwd = readHeader(sessionFile)?.cwd || DEFAULT_CWD;
    }
    recycleWorkerFor(sessionFile);
    broadcast({ kind: 'bridge', event: 'reload', at: Date.now(), file: sessionFile || null });
    const w = workerFor(sessionFile);
    w.cwd = cwd;
    w.sessionFile = sessionFile || null;
    let opened = null;
    if (sessionFile && fs.existsSync(sessionFile)) {
      opened = await w.call('switch_session', { sessionPath: sessionFile }, 120000);
      w.bound = true;
    }
    const state = await w.call('get_state', {}, 30000);
    return { reloaded: true, file: sessionFile || state?.sessionFile || null, state };
  },

  'POST /v1/sessions/open': async (body) => {
    const file = body.file;
    if (!file || !fs.existsSync(file)) throw new Error(`session file not found: ${file}`);
    const cwd = readHeader(file)?.cwd || DEFAULT_CWD;
    // 这个会话已经有自己的 worker（可能正在跑）→ 直接切过去，不要 switch、不要重启
    let existing = workers.get(file);
    // 已有 worker 且**确实绑过这个会话**才跳过；否则（之前只被 info 等接口懒创建过）要补一次 switch
    if (existing && existing.alive && existing.bound) {
      setActiveFile(file);
      return { skipped: true, file, resumed: true };
    }
    const w = existing && existing.alive ? existing : new PiWorker(cwd, file);
    w.sessionFile = file;
    workers.set(file, w);
    setActiveFile(file);
    // spawn 时就带 `--session` 的 worker 已经绑好了，不用再 switch（也不留空会话文件）
    if (w.bound) return { skipped: true, file, resumed: true };
    const data = await w.call('switch_session', { sessionPath: file }, 120000);
    w.bound = true;
    return { ...data, file };
  },

  'POST /v1/sessions/new': async (body) => {
    const cwd = body.cwd || DEFAULT_CWD;
    // 新建会话用一个全新的 worker（不复用旧会话的，避免把它的上下文顶掉）
    const key = 'new-' + Date.now();
    const w = new PiWorker(cwd);
    workers.set(key, w);
    w.touch();
    const data = await w.call('new_session', {}, 120000);
    const state = await w.call('get_state', {}, 60000);
    if (state?.sessionFile) {
      // 把 worker 重新挂到这个新会话文件下，并设为当前会话
      workers.delete(key);
      w.sessionFile = state.sessionFile;
      workers.set(state.sessionFile, w);
      setActiveFile(state.sessionFile);
    }
    return { ...data, state };
  },

  'GET /v1/messages': async (body, url) => {
    const w = getWorker();
    const data = await w.call('get_messages', {}, 120000);
    const full = url.searchParams.get('full') === '1';
    // app 会带上它屏幕能放下的列数（等宽字符数）；桥按这个宽度调 pi 的渲染器 ⇒ 换行点与终端一致
    const colsParam = Number(url.searchParams.get('cols') || 0);
    if (colsParam > 10) { termCols = colsParam; writeTermCols(termCols); }
    const termColsLocal = termCols;
    const messages = full ? data.messages : stripImages(data.messages);
    // 历史里的工具调用也带上「终端显示文本」：一次算好，app 端不用再猜（口径随 pi 版本自动跟随）
    const argsById = new Map();
    await Promise.all((messages || []).map(async (msg) => {
      if (!msg || !Array.isArray(msg.content)) return;
      await Promise.all(msg.content.map(async (part) => {
        if (!part || (part.type !== 'toolCall' && part.type !== 'tool_use')) return;
        if (part.id) argsById.set(part.id, { name: part.name, args: part.arguments ?? part.input ?? {} });
        const display = await toolDisplay(part.name, part.arguments ?? part.input);
        if (display) part.display = display;
        // **call 侧也带上 term**：历史里 toolResult 可能没配上对（id 缺失/被裁剪），
        // app 就退回"工具名 + 参数"那种自拼排版（用户报的"linux 命令被解析成 exec_command"）。
        // 这里把 renderCall 的结果直接挂到 toolCall 上，头部始终是终端的 `$ cmd`。
        try {
          const callArgs = part.arguments ?? part.input ?? {};
          const callTerm = await toolTerm(part.name, callArgs, { toolCallId: part.id }, termColsLocal);
          if (callTerm && Array.isArray(callTerm.call)) part.term = { call: callTerm.call };
        } catch { /* 渲染失败不影响其它字段 */ }
      }));
    }));
    // 工具结果的「终端原样 HTML」：按 toolCallId 找回参数后渲染（内置工具才有）
    await Promise.all((messages || []).map(async (msg) => {
      if (!msg || msg.role !== 'toolResult') return;
      const call = argsById.get(msg.toolCallId) || {};
      const name = msg.toolName || call.name;
      const html = await toolHtml(name, call.args || {}, msg);
      if (html) msg.html = html;
      // 终端原样行（1:1 排版）：按 app 报的列数渲染
      let term = await toolTerm(name, call.args || {}, msg, termColsLocal);
      if (!term) term = readPocketTerm(msg.toolCallId);
      if (term) msg.term = term;
    }));
    return { ...data, messages };
  },


  // ── provider 配置（给 app 的「Pi Provider」页用；**绝不回传 key 原文**）────────
  // pi 的配置位置（一手）：~/.pi/agent/models.json 的 providers[name] = {baseUrl, api, models:[{id,…}]}
  //                      ~/.pi/agent/auth.json  的 [name] = {type:'api_key', key}
  // 诊断：工具渲染器适配器的状态（升级 pi/插件后先看这个）
  'GET /v1/term/adapters': async () => {
    const reg = await adapterRegistry();
    return { adapters: reg.status || [], tools: reg.tools ? reg.tools() : [] };
  },

  'GET /v1/providers': async () => {
    const models = readJsonSafe(AGENT_DIR + '/models.json') || {};
    const auth = readJsonSafe(AGENT_DIR + '/auth.json') || {};
    const providers = models.providers || {};
    const list = Object.entries(providers).map(([name, cfg]) => ({
      name,
      baseUrl: cfg?.baseUrl ?? '',
      api: cfg?.api ?? '',
      models: (cfg?.models ?? []).map((m) => (typeof m === 'string' ? m : m?.id)).filter(Boolean),
      hasKey: Boolean(auth?.[name]?.key),
      authType: auth?.[name]?.type ?? null,
      builtin: false,
    }));
    // 内置 provider（pi 自带 catalog，如官方 deepseek / openai / anthropic）：
    // 它们不在 models.json 里，但只要有 auth.json 的凭据就该在 App 里看得见。
    const store = readJsonSafe(AGENT_DIR + '/models-store.json') || {};
    const have = new Set(list.map((p) => p.name));
    for (const [name, cfg] of Object.entries(store)) {
      if (have.has(name)) continue;
      const modelsArr = Array.isArray(cfg?.models) ? cfg.models : [];
      if (!modelsArr.length) continue;
      const cred = auth?.[name];
      if (!cred) continue;
      list.push({
        name,
        baseUrl: cfg?.baseUrl ?? cfg?.apiEndpoint ?? '',
        api: cfg?.api ?? '内置',
        models: modelsArr.map((m) => (typeof m === 'string' ? m : m?.id)).filter(Boolean),
        hasKey: Boolean(cred?.key || cred?.type),
        authType: cred?.type ?? null,
        builtin: true,
      });
    }
    return { providers: list, agentDir: AGENT_DIR };
  },

  'POST /v1/providers': async (body) => {
    const name = String(body.name ?? '').trim();
    if (!name) throw new Error('name required');
    if (!/^[A-Za-z0-9._-]+$/.test(name)) throw new Error('name 只能包含字母数字 . _ -');
    const baseUrl = String(body.baseUrl ?? '').trim();
    const api = String(body.api ?? 'openai-completions').trim();
    const ids = (Array.isArray(body.models) ? body.models : String(body.models ?? '').split(','))
      .map((x) => String(x).trim()).filter(Boolean);
    const apiKey = typeof body.apiKey === 'string' ? body.apiKey.trim() : '';

    const modelsPath = AGENT_DIR + '/models.json';
    const authPath = AGENT_DIR + '/auth.json';
    const models = readJsonSafe(modelsPath) || {};
    models.providers = models.providers || {};
    const prev = models.providers[name] || {};
    models.providers[name] = {
      ...prev,
      ...(baseUrl ? { baseUrl } : {}),
      ...(api ? { api } : {}),
      models: ids.length
        ? ids.map((id) => {
            const old = (prev.models ?? []).find((m) => (typeof m === 'string' ? m : m?.id) === id);
            return typeof old === 'object' && old ? { ...old, id } : { id, name: id };
          })
        : prev.models ?? [],
    };
    writeJsonSafe(modelsPath, models, 0o600);

    if (apiKey) {
      const auth = readJsonSafe(authPath) || {};
      auth[name] = { type: 'api_key', key: apiKey };
      writeJsonSafe(authPath, auth, 0o600);
    }
    // 写盘后主动校验一次：能解析、key 在、模型非空
    const check = readJsonSafe(modelsPath)?.providers?.[name];
    return {
      ok: true,
      name,
      baseUrl: check?.baseUrl ?? '',
      api: check?.api ?? '',
      models: (check?.models ?? []).map((m) => m?.id).filter(Boolean),
      hasKey: Boolean(readJsonSafe(authPath)?.[name]?.key),
    };
  },

  'POST /v1/providers/remove': async (body) => {
    const name = String(body.name ?? '').trim();
    if (!name) throw new Error('name required');
    for (const file of [AGENT_DIR + '/models.json', AGENT_DIR + '/auth.json']) {
      const data = readJsonSafe(file);
      if (!data) continue;
      if (file.endsWith('models.json')) {
        if (data.providers?.[name]) delete data.providers[name];
      } else if (data[name]) {
        delete data[name];
      }
      writeJsonSafe(file, data, 0o600);
    }
    return { ok: true, removed: name };
  },

  // ── 从零部署：app 用 Termux RUN_COMMAND 跑 termux/bootstrap.sh（见 docs/BOOTSTRAP.md）──
  'POST /v1/prompt': async (body) => {
    const text = String(body.text ?? '');
    const images = normalizeImages(body.images);
    if (!text.trim() && !images.length) throw new Error('empty prompt');
    const w = getWorker();
    const cmd = { type: body.steer ? 'steer' : 'prompt', message: text };
    if (images.length) cmd.images = images;
    if (body.streamingBehavior) cmd.streamingBehavior = body.streamingBehavior;
    // app 侧的"是否在跑"可能过期（闪退/重进会话时拿不到事件）：
    // 这时直接发 prompt 会被 pi 拒绝（"Agent is already processing"）—— 看起来就像会话被停掉了。
    // 所以这里问一下 pi 自己的状态，在跑就自动按 steer 排队，不让消息丢掉。
    if (!cmd.streamingBehavior && cmd.type === 'prompt') {
      try {
        const st = await w.call('get_state', {}, 15000);
        if (st?.isStreaming) cmd.streamingBehavior = 'steer';
      } catch { /* 拿不到状态就按原样发 */ }
    }
    const data = await w.call(cmd.type, cmd, 120000);
    return { ...data, images: images.length };
  },

  // 清空排队（steer / followUp）并**返回全部文本** —— app 用它在"取消某一条/编辑某一条"后重新排队其余的。
  'POST /v1/queue/clear': async () => {
    const w = getWorker();
    return await w.call('clear_queue', {}, 30000);
  },

  'POST /v1/abort': async () => {
    const w = getWorker();
    // 记一笔"是用户停的"：agent_end 时据此把通知写成「已停止」而不是「回答完成」
    w.abortedByUser = true;
    return w.call('abort', {}, 180000);
  },

  'GET /v1/models': async () => {
    const w = getWorker();
    return w.call('get_available_models', {}, 120000);
  },

  'POST /v1/model': async (body) => {
    const w = getWorker();
    let data;
    if (body.cycle) {
      // pi 的 `cycle_model` 支持方向（终端是 app.model.cycleForward/Backward 两个键）
      const direction = body.cycle === 'backward' ? 'backward' : undefined;
      data = await w.call('cycle_model', direction ? { direction } : {}, 120000);
    } else {
      // pi 的 RPC 参数叫 **`modelId`**（docs/rpc-commands.md §set_model：`{provider, modelId}`）。
      // 以前这里传的是 `model` ⇒ pi 收到 modelId=undefined ⇒ 永远报
      // "Model not found: <provider>/undefined"，而 app 把失败吞了 ⇒ 顶部一直显示旧模型。
      // 两种名字都收，别再让客户端踩同一个坑。
      const provider = body.provider;
      const modelId = body.modelId ?? body.model;
      if (!provider || !modelId) throw new Error('set_model 需要 provider 与 model（或 modelId）');
      data = await w.call('set_model', { provider, modelId }, 120000);
    }
    const state = await w.call('get_state', {}, 60000);
    return { ...data, state, modelDisplay: modelDisplayOf(state, w) };
  },

  'POST /v1/thinking': async (body) => {
    const w = getWorker();
    const data = await w.call('set_thinking_level', { level: body.level }, 120000);
    return data;
  },

  /**
   * 快切思考等级（pi 的 `cycle_thinking_level`；终端是 `app.thinking.cycle` 键）。
   *
   * 返回 `{level}`；模型没有可用的思考等级时 pi 返回 null（app 要当“没法切”处理）。
   */
  'POST /v1/thinking/cycle': async () => {
    const w = getWorker();
    const data = await w.call('cycle_thinking_level', {}, 120000);
    const state = await w.call('get_state', {}, 60000);
    return { level: data?.level ?? null, thinking: state?.thinkingLevel ?? null };
  },

  'POST /v1/compact': async (body) => {
    const w = getWorker();
    return w.call('compact', body.instructions ? { customInstructions: body.instructions } : {}, 600000);
  },

  'POST /v1/rename': async (body) => {
    const w = getWorker();
    return w.call('set_session_name', { name: String(body.name ?? '') }, 60000);
  },

  // ── 会话树 / 分叉（pi 的 get_tree · get_fork_messages · fork · clone） ────────
  //
  // 「会话树」是 pi 的核心结构（`core/session-manager.ts: SessionEntry.parentId`）：
  // 每个条目都有 parentId，`fork` 就是从某个检查点切出去 ⇒ 一个新会话文件。
  // 以前 app 把会话当成**线性列表**画，是因为桥没暴露这几个命令。
  //
  // 桥只做**搬运 + 投影**（把 pi 的 SessionEntry 缩成树上要用的字段）——
  // 过滤档/连接线/文案全在 app 侧（照 pi 的 `components/tree-selector.ts` 实现）。
  'GET /v1/tree': async (_body, url) => {
    // 默认走**文件快路**（几百 ms）；`?rpc=1` 走 pi 的 get_tree（对账/调试用，大会话很慢）
    if (url.searchParams.get('rpc') === '1') {
      const w = getWorker();
      const data = await w.call('get_tree', {}, 120000);
      if (url.searchParams.get('raw') === '1') return data;
      return { leafId: data.leafId ?? null, nodes: flattenTree(data.tree || []) };
    }
    const file = activeFile || getWorker().sessionFile || null;
    const snap = treeFromSessionFile(file);
    if (url.searchParams.get('raw') === '1') return snap;
    return snap;
  },

  // 「可以从哪分叉」的候选（pi 的 `get_fork_messages`：只有用户消息，带 entryId）
  'GET /v1/fork-messages': async () => {
    const w = getWorker();
    return w.call('get_fork_messages', {}, 60000);
  },

  /**
   * 从某个条目**分叉**（默认 `position: "before"`，pi 只接受用户消息）。
   *
   * ⚠️ fork 在 pi 里是「**开一个新会话文件**」（`agent-session-runtime.ts:262` 的
   * `createBranchedSession(targetLeafId)`）然后 rebind ⇒ 桥必须把 worker 的 key 与
   * `w.sessionFile` 一起换掉，否则事件会被算到分叉前的会话上、app 拉历史也会拉错文件。
   */
  'POST /v1/fork': async (body) => {
    if (!body.entryId) throw new Error('fork 需要 entryId（用户消息的条目 id）');
    return forkOrClone('fork', { entryId: String(body.entryId) }, body);
  },

  /** 复制当前会话（pi 的 `clone` = 在当前 leaf 处 `fork({position:"at"})`）。 */
  'POST /v1/clone': async (body) => forkOrClone('clone', {}, body || {}),

  // ── 行为开关（pi 的 set_auto_compaction · set_auto_retry · set_steering_mode ·
  //    set_follow_up_mode · abort_retry）────────────────────────────────────
  //
  // 这五个以前 app 里根本看不到（终端能在 /settings 里改）。取值口径全部照 pi 源码：
  //  · steeringMode / followUpMode = "all" | "one-at-a-time"（`core/agent-session.ts:1642`）
  //  · autoCompactionEnabled 在 `get_state` 里；**autoRetryEnabled 不在** ——
  //    pi 把它存在 `~/.pi/agent/settings.json` 的 `retry.enabled`（`core/settings-manager.ts:893`），
  //    所以桥直接读那个文件（只读，不写；写入还给 pi 自己管）。
  'GET /v1/behavior': async () => behaviorSnapshot(getWorker()),

  'POST /v1/behavior': async (body) => {
    const w = getWorker();
    const applied = [];
    if (typeof body?.autoCompaction === 'boolean') {
      await w.call('set_auto_compaction', { enabled: body.autoCompaction }, 60000);
      applied.push('autoCompaction');
    }
    if (typeof body?.autoRetry === 'boolean') {
      await w.call('set_auto_retry', { enabled: body.autoRetry }, 60000);
      applied.push('autoRetry');
    }
    if (body?.steeringMode === 'all' || body?.steeringMode === 'one-at-a-time') {
      await w.call('set_steering_mode', { mode: body.steeringMode }, 60000);
      applied.push('steeringMode');
    }
    if (body?.followUpMode === 'all' || body?.followUpMode === 'one-at-a-time') {
      await w.call('set_follow_up_mode', { mode: body.followUpMode }, 60000);
      applied.push('followUpMode');
    }
    const snapshot = await behaviorSnapshot(w);
    return { ...snapshot, applied };
  },

  /** 中断正在进行的自动重试（pi 的 `abort_retry`；终端里是重试倒计时中按 Esc）。 */
  'POST /v1/retry/abort': async () => {
    const w = getWorker();
    return w.call('abort_retry', {}, 60000);
  },

  // ── 交付 / 导出（pi 的 export_html · get_last_assistant_text）─────────────────
  //
  // 终端里是 `/export`（写一个自包含 HTML）与 `/copy`（复制最后一条回答）。
  // pi 的 `export_html` 默认把文件写到 **worker 的 cwd**，文件名 `pi-session-<会话名>.html`
  // （`core/export-html/index.ts:274-281`）；这里让 app 可以指定目录，默认仍交给 pi 决定。
  'POST /v1/export': async (body) => {
    const w = getWorker();
    const outputPath = typeof body?.path === 'string' && body.path.trim() ? body.path.trim() : undefined;
    const data = await w.call('export_html', outputPath ? { outputPath } : {}, 300000);
    const file = data?.path || null;
    let bytes = null;
    try { if (file) bytes = fs.statSync(file).size; } catch { /* 文件不在就算了 */ }
    return { path: file, bytes };
  },

  /** 复制最后一条回答（终端 `/copy`）：pi 已经把“跳过中断且无内容的消息”这层逻辑做完了。 */
  'GET /v1/last-text': async () => {
    const w = getWorker();
    const data = await w.call('get_last_assistant_text', {}, 60000);
    return { text: data?.text ?? '' };
  },

  // 内置 slash 命令（继承 pi 的交互模式，不手抄）
  'GET /v1/slash': async () => {
    const c = readSlashCommands();
    return { file: c.file, commands: [...c.names.entries()].map(([name, args]) => {
      const rpc = SLASH_RPC[name];
      return {
        name: '/' + name,
        args,
        argsHint: args ? '<参数>' : '',
        rpc: rpc ? rpc.rpc : null,
        description: rpc ? rpc.label : 'pi 终端专属（app 里不可用）',
        available: Boolean(rpc),
      };
    }) };
  },
  'GET /v1/commands': async () => {
    const w = getWorker();
    try { return await w.call('get_commands', {}, 60000); } catch (e) { return { commands: [], warning: e.message }; }
  },

  // 完整执行面：走 pi 官方的 RPC `bash`（输出会流式回在 SSE 的 bash_execution_update 上）
  'POST /v1/bash': async (body) => {
    const command = String(body.command ?? '');
    if (!command.trim()) throw new Error('empty command');
    const w = getWorker();
    const id = body.id || `bash-${Date.now()}`;
    const params = { id, command };
    if (body.excludeFromContext) params.excludeFromContext = true;
    const data = await w.call('bash', params, 600000);
    return { ...data, id };
  },

  'POST /v1/bash/abort': async () => getWorker().call('abort_bash', {}, 30000),

  /**
   * agent 主动发一条手机通知（app 会话里注入的 `pocket_notify` 工具走这里）。
   * 只在 Android 上真的发广播；其它平台如实回 `sent:false`，不报错（桌面/CI 跑桥很正常）。
   */
  'POST /v1/notify': async (body) => {
    const payload = customNotifyPayload(body);
    if (!payload) throw new Error('通知内容为空（title/text 至少给一个）');
    const file = typeof body?.file === 'string' ? body.file : (getWorker().sessionFile || null);
    const via = notifyPhone({ title: payload.title, text: payload.text, file, kind: payload.kind });
    return { sent: Boolean(via), via: via || null, kind: payload.kind, title: payload.title, text: payload.text };
  },

  // 扩展 UI 的回应通道：{id, response, file?}（response 形状见 pi 的 rpc-extension-ui.md，桥只透传）
  //
  // ⚠️ `file` = 发出请求的那个会话：pi 的扩展 UI 请求是 **per-worker** 的，
  // 以前一律 `getWorker()`（= app 当前在看的会话）⇒ 在 A 会话弹的 confirm、
  // 用户在 B 会话点“确定”，应答就写进了 B 的 pi 进程：A 那边一直等到超时，B 那边收到个无效 id。
  // 现在：带了 file 就只给那个会话的活 worker；那个 worker 没了就如实报错（不默默写错地方）。
  'POST /v1/ui': async (body) => {
    if (!body || typeof body.id !== 'string') throw new Error('id is required');
    const file = typeof body.file === 'string' && body.file ? body.file : null;
    let w;
    if (file) {
      w = workers.get(file);
      if (!w || !w.alive) return { sent: false, reason: `那个会话的 pi 进程不在了（${path.basename(file)}）` };
    } else {
      w = getWorker();
    }
    w.sendRaw({ type: 'extension_ui_response', id: body.id, ...(body.response || {}) });
    return { sent: true, file: w.sessionFile || null };
  },

  // ── 远端文件（走 Termux 的 ~/.ssh/config；别名 local/termux = 本机）────────────
  'GET /v1/ssh/hosts': async () => {
    const { file, hosts } = sshConfigHosts();
    return {
      file,
      hosts: [
        { alias: 'local', hostName: '127.0.0.1', user: os.userInfo().username, port: 0, label: '本机（Termux）' },
        ...hosts.map((h) => ({ ...h, label: `${h.alias} · ${h.user || '?'}@${h.hostName || h.alias}` })),
      ],
    };
  },

  'POST /v1/fs': async (body) => {
    const alias = String(body.target || 'local').trim() || 'local';
    const op = String(body.op || 'list');
    const p = String(body.path || '');
    if (op !== 'home' && !p) throw new Error('缺少 path');

    if (op === 'home') {
      const out = await runRemote(alias, 'printf %s "$HOME"', { timeoutMs: 15000 });
      return { home: out.toString().trim() };
    }
    if (op === 'list') {
      // GNU find（Termux / Linux 都有）：类型、大小、mtime、名字一次拿全
      const cmd = `cd ${qpath(p)} && LC_ALL=C find . -mindepth 1 -maxdepth 1 -printf '%y\t%s\t%T@\t%f\n' 2>/dev/null`;
      const out = (await runRemote(alias, cmd, { timeoutMs: 30000 })).toString();
      const entries = out.split('\n').filter(Boolean).map((line) => {
        const parts = line.split('\t');
        const name = parts.slice(3).join('\t');
        return {
          name,
          path: (p.endsWith('/') ? p : p + '/') + name,
          isDir: parts[0] === 'd',
          size: Number(parts[1]) || 0,
          mtime: Math.round((Number(parts[2]) || 0) * 1000),
        };
      }).filter((e) => e.name && e.name !== '.' && e.name !== '..')
        .sort((a, b) => (a.isDir === b.isDir ? a.name.localeCompare(b.name) : a.isDir ? -1 : 1));
      return { path: p, entries };
    }
    if (op === 'read') {
      const sizeOut = (await runRemote(alias, `stat -c %s ${qpath(p)} 2>/dev/null || wc -c < ${qpath(p)}`, { timeoutMs: 15000 })).toString();
      const size = Number(sizeOut.trim()) || 0;
      if (size > (4 << 20)) throw new Error(`文件 ${(size / 1048576).toFixed(1)}MB > 4MB，请在终端里取`);
      const out = await runRemote(alias, `base64 < ${qpath(p)}`, { timeoutMs: 40000, maxBytes: 16 << 20 });
      return { path: p, size, base64: out.toString().replace(/\s+/g, '') };
    }
    if (op === 'write') {
      const b64 = String(body.base64 || '').replace(/\s+/g, '');
      await runRemote(alias, `base64 -d > ${qpath(p)}`, { input: b64, timeoutMs: 90000 });
      return { path: p, bytes: Buffer.from(b64, 'base64').length };
    }
    if (op === 'mkdir') {
      await runRemote(alias, `mkdir -p ${qpath(p)}`, { timeoutMs: 15000 });
      return { path: p };
    }
    if (op === 'remove') {
      await runRemote(alias, `rm -rf ${qpath(p)}`, { timeoutMs: 30000 });
      return { path: p };
    }
    if (op === 'rename') {
      await runRemote(alias, `mv -- ${qpath(p)} ${qpath(String(body.to || ''))}`, { timeoutMs: 15000 });
      return { path: body.to };
    }
    throw new Error(`未知 op: ${op}`);
  },
};

// ── 桥重启后别丢「当前会话」───────────────────────────────────────────────
// 用户 2026-10-19 报的 bug：桥重启后，在 app 里「原地」发一条消息，结果变成了一个新会话。
// 原因：桥一重启内存里的 activeFile 就没了，app 又不会在发消息前重开会话 ⇒ getWorker() 拉起一个
// 没绑会话的 pi ⇒ pi 自己新建一个空会话，消息就落进去了（app 侧只看到会话列表多出一条）。
// 对策：activeFile 落盘、启动时读回；worker 用 `pi --session <file>` 起，重启后第一条消息仍落在原会话。
(function restoreActiveFile() {
  const file = readJsonSafe(STATE_FILE)?.activeFile;
  if (!file) return;
  if (fs.existsSync(file)) {
    activeFile = file;
    log(`restored active session: ${file}`);
  } else {
    log(`active session file is gone, ignoring: ${file}`);
  }
})();

/**
 * 模型那一行怎么显示 —— **照 pi 终端 footer 的算法**（dist/modes/interactive/components/footer.js）：
 *
 *   `(provider) <model.id> • <thinkingLevel>`，虚拟模型再补 ` → <最新一次真正应答的 model.id>`
 *
 * pi 的 `→` 来自内部状态 `session.routedModel`，RPC `get_state` 不给 —— 所以从会话文件里取
 * **最后一条 assistant 消息**的 provider/model（语义就是"最新一次应答实际落在哪个模型"，
 * 与 pi footer 一致，session-manager 也是这么找当前模型的）。
 *
 * 桥算好文本、app 只负责画（HANDOFF §14 的 display 契约）。
 */
function lastResponseModel(file) {
  if (!file) return null;
  let fd;
  try {
    const st = fs.statSync(file);
    const len = Math.min(st.size, 256 * 1024);
    const buf = Buffer.alloc(len);
    fd = fs.openSync(file, 'r');
    fs.readSync(fd, buf, 0, len, st.size - len);
    const lines = buf.toString('utf8').split(String.fromCharCode(10));
    for (let i = lines.length - 1; i >= 0; i--) {
      if (!lines[i].includes('"provider"')) continue;
      let obj;
      try { obj = JSON.parse(lines[i]); } catch { continue; }
      const msg = obj?.message;
      if (msg?.role !== 'assistant' || !msg.provider || !msg.model) continue;
      return { provider: msg.provider, modelId: msg.model, at: obj.timestamp || null };
    }
  } catch { /* 读不到就算了 */ } finally {
    if (fd !== undefined) { try { fs.closeSync(fd); } catch { /* ignore */ } }
  }
  return null;
}

function modelDisplayOf(state, w) {
  const model = state?.model;
  if (!model) return null;
  const thinking = state?.thinkingLevel || 'off';
  let text = model.id || 'no-model';
  if (model.reasoning) text += ` • ${thinking === 'off' ? 'thinking off' : thinking}`;
  const routed = lastResponseModel(state?.sessionFile || w?.sessionFile);
  const used = routed && (routed.modelId !== model.id || routed.provider !== model.provider);
  if (used) text += ` → ${routed.modelId}`;
  // 虚拟模型（auto-provider）：真正常在干活的是它路由到的那个模型（`effective`）。
  // 注意：这里**不自己编显示规则** —— 显示串就是 pi footer 那一条（`text`），
  // `virtual` / `effective` / `routed` 只是事实，给客户端需要时用。
  const virtual = model.api === 'pi-virtual' || model.provider === 'auto-provider';
  const effective = used ? routed.modelId : (model.id || '');
  return {
    id: model.id || '',
    provider: model.provider || '',
    name: model.name || '',
    reasoning: Boolean(model.reasoning),
    thinkingLevel: thinking,
    virtual,
    effective,
    routed: used ? routed : null,
    text,
  };
}

const server = http.createServer(async (req, res) => {
  try {
    // Host is caller-controlled; parsing must be covered by the error boundary.
    const url = new URL(req.url, 'http://localhost');
    if (TOKEN) {
      const bearer = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
      const supplied = bearer || url.searchParams.get('token') || '';
      if (supplied !== TOKEN) return json(res, 401, { ok: false, error: 'unauthorized' });
    }

    if (url.pathname === '/v1/events') {
      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache, no-transform',
        connection: 'keep-alive',
        'x-accel-buffering': 'no',
      });
      res.write('retry: 3000\n\n');
      res.write(`data: ${JSON.stringify({ kind: 'bridge', event: 'hello', worker: Boolean(activeFile ? workers.get(activeFile)?.alive : workers.size > 0), at: Date.now() })}\n\n`);
      subscribers.add(res);
      // 保活：以前写的是 SSE **注释**（`: ping`）—— OkHttp 的 SSE 解析器会把注释丢掉，
      // 客户端**看不到**任何东西 ⇒ 它无法判断“连接还在不在”。
      // 后果（用户 2026-10-22「app 老是掉桥」的另一半）：桥跟 app 的 HTTP 都正常，
      // 但 SSE 早就僵了（桥重启/网络切换） ⇒ app 顶着“已连接”什么都收不到，
      // 只能靠人手点一下才恢复。现在发**真事件**：app 收到就刷新 lastEventAt，
      // 超过阈值没收到就自己重连（见 AppState 的心跳看门狗）。
      const keepAlive = setInterval(() => {
        if (!writeEvent(res, `event: ping\ndata: {"at":${Date.now()}}\n\n`)) subscribers.delete(res);
      }, 20000);
      req.on('close', () => { clearInterval(keepAlive); subscribers.delete(res); });
      return;
    }

    const key = `${req.method} ${url.pathname}`;
    const handler = routes[key];
    if (!handler) return json(res, 404, { ok: false, error: `no route: ${key}` });
    const body = req.method === 'POST' ? await readBody(req) : {};
    const data = await handler(body, url);
    getWorker().touch();
    return json(res, 200, { ok: true, data });
  } catch (err) {
    log(`error on ${req.method}:`, err.message);
    return json(res, 500, { ok: false, error: err.message });
  }
});

if (HOST !== '127.0.0.1' && HOST !== 'localhost' && !TOKEN) {
  log(`refusing to bind ${HOST} without PI_POCKET_TOKEN (anyone on the LAN could drive pi)`);
  process.exit(2);
}

server.listen(PORT, HOST, () => {
  log(`listening on http://${HOST}:${server.address().port}  (pi=${PI_BIN}, cwd=${DEFAULT_CWD}, host=${os.hostname()})`);
  log(`sessions: ${SESSIONS_ROOT}`);
  if (!TOKEN) log('auth: disabled (loopback only)');
  else log('auth: bearer token required');
  if (HOST === '0.0.0.0') log('LAN/tailnet mode: 其它机器可用 http://<本机地址>:' + PORT + ' 连（需带 TOKEN）');
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    log(`got ${sig}, shutting down`);
    for (const w of workers.values()) { try { w.stop(sig); } catch { /* ignore */ } }
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref?.();
  });
}
