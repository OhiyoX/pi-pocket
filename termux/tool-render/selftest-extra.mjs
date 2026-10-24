#!/usr/bin/env node
/**
 * 自测：pi 自带的 MCP / codemode 渲染器能不能在**桥的语境**里出终端行。
 *
 *   node termux/tool-render/selftest-extra.mjs [session.jsonl]
 *
 * 为什么要这个：这两类工具的渲染器以前只能靠 pi 进程里的 `pocket-term` 扩展落盘，
 * 而那条路在 `--mode rpc` 下是空转的（`registerToolRenderer` 的解析器只在 TUI 渲染时执行）。
 * 桥现在自己用 pi dist 里的渲染器渲（`extraRenderers()` in pi-pocket-bridge.mjs）。
 *
 * 它从真实会话里捞一条 `mcp__*` 和一条 `codemode` 的调用+结果，渲染成 ANSI 行打印出来。
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const req = createRequire(import.meta.url);
function findDist() {
  const candidates = [];
  try { candidates.push(path.dirname(req.resolve('@earendil-works/pi-coding-agent/package.json'))); } catch {}
  const prefix = process.env.PREFIX || '/data/data/com.termux/files/usr';
  candidates.push(path.join(prefix, 'lib/node_modules/@earendil-works/pi-coding-agent'));
  candidates.push(path.join(os.homedir(), '.local/lib/node_modules/@earendil-works/pi-coding-agent'));
  candidates.push('/usr/local/lib/node_modules/@earendil-works/pi-coding-agent');
  for (const c of candidates) {
    try { if (fs.existsSync(path.join(c, 'dist/modes/interactive/theme/theme.js'))) return path.join(c, 'dist'); } catch {}
  }
  throw new Error('找不到 pi 的 dist（试过：' + candidates.join(', ') + '）');
}
const distRoot = findDist();
const imp = (rel) => import(pathToFileURL(path.join(distRoot, rel)).href);

const sessionArg = process.argv[2];

function newestSessions(n) {
  const root = path.join(os.homedir(), '.pi/agent/sessions');
  const all = [];
  for (const dir of fs.readdirSync(root)) {
    const p = path.join(root, dir);
    let st;
    try { st = fs.statSync(p); } catch { continue; }
    if (!st.isDirectory()) continue;
    for (const f of fs.readdirSync(p)) {
      if (!f.endsWith('.jsonl')) continue;
      const full = path.join(p, f);
      try { all.push({ full, m: fs.statSync(full).mtimeMs }); } catch {}
    }
  }
  all.sort((a, b) => b.m - a.m);
  return all.slice(0, n).map((x) => x.full);
}

const sessionFiles = sessionArg ? [sessionArg] : newestSessions(6);

const themeMod = await imp('modes/interactive/theme/theme.js');
try { themeMod.initTheme('dark'); } catch {}
const theme = themeMod.getThemeByName('dark');
const mcpMod = await imp('extensions/mcp/tools.js');
const codemodeMod = await imp('extensions/codemode/renderer.js');

// 从会话里捞：toolCall(名字/参数) + 对应的 toolResult
// （pi 存盘时 toolResult 是**单独一条 message**：role=toolResult + toolCallId）
const calls = new Map();
const results = new Map();
for (const sessionFile of sessionFiles) {
  for (const line of fs.readFileSync(sessionFile, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    let entry;
    try { entry = JSON.parse(line); } catch { continue; }
    const msg = entry.message || entry;
    if (msg.role === 'toolResult' && msg.toolCallId) { results.set(msg.toolCallId, msg); continue; }
    const content = Array.isArray(msg.content) ? msg.content : [];
    for (const part of content) {
      if (!part || typeof part !== 'object') continue;
      if (part.type === 'toolCall' || part.type === 'tool_use') {
        calls.set(part.id, { name: part.name, args: part.arguments ?? part.input ?? {} });
      }
      if (part.type === 'toolResult' || part.type === 'tool_result') {
        results.set(part.toolCallId || part.tool_use_id, part);
      }
    }
  }
}

const defFor = (name) => {
  const m = /^mcp__(.+?)__(.+)$/.exec(name || '');
  if (m) return mcpMod.createMcpToolRenderers(m[1] + '/' + m[2]);
  if (name === 'codemode') return codemodeMod.codemodeRenderers;
  return null;
};

const plain = (s) => String(s).replace(/\u001b\[[0-9;]*m/g, '');
let shown = 0;
const perKind = {};
for (const [id, call] of calls) {
  const kind = /^mcp__/.test(call.name) ? 'mcp' : call.name === 'codemode' ? 'codemode' : null;
  if (!kind) continue;
  const res = results.get(id);
  if (!res) continue;
  if ((perKind[kind] || 0) >= 2) continue;
  if (shown >= 4) break;
  const def = defFor(call.name);
  if (!def) { console.log(`!! ${call.name}: 没有渲染器`); continue; }
  const width = 47;
  const ctx = (expanded) => ({
    args: call.args, toolCallId: id, invalidate: () => {}, lastComponent: undefined, state: {},
    cwd: process.cwd(), executionStarted: true, argsComplete: true, isPartial: false,
    expanded, showImages: false, isError: Boolean(res.isError), outputPad: 1,
  });
  const out = {};
  try {
    if (def.renderCall) out.call = def.renderCall(call.args, theme, ctx(false)).render(Math.max(width, 100));
  } catch (e) { out.callErr = e.message; }
  const content = Array.isArray(res.content) ? res.content : [{ type: 'text', text: String(res.content ?? '') }];
  const agentResult = { content, details: res.details, isError: Boolean(res.isError) };
  for (const expanded of [false, true]) {
    try {
      out[expanded ? 'expanded' : 'collapsed'] =
        def.renderResult(agentResult, { expanded, isPartial: false }, theme, ctx(expanded)).render(width);
    } catch (e) { out[(expanded ? 'expanded' : 'collapsed') + 'Err'] = e.message; }
  }
  shown++;
  perKind[kind] = (perKind[kind] || 0) + 1;
  console.log('='.repeat(60));
  console.log('tool:', call.name, '  width:', width);
  if (out.callErr) console.log('  call ERROR:', out.callErr);
  if (out.call) console.log('--- call ---\n' + out.call.map(plain).join('\n'));
  if (out.collapsedErr) console.log('  collapsed ERROR:', out.collapsedErr);
  if (out.collapsed) console.log('--- collapsed ---\n' + out.collapsed.map(plain).join('\n'));
  if (out.expandedErr) console.log('  expanded ERROR:', out.expandedErr);
  if (out.expanded) console.log('--- expanded(first 20) ---\n' + out.expanded.slice(0, 20).map(plain).join('\n'));
}
if (!shown) console.log('（这个会话里没找到 mcp__* / codemode 的调用+结果）');
