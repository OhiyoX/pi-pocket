#!/usr/bin/env node
/**
 * 会话树快路的**离线基准**（不碰任何设备，不需要 pi）。
 *
 *   node tools/tree-bench.mjs            # 默认造 3000 条
 *   node tools/tree-bench.mjs 10000      # 自定义条数
 *
 * 造一份形状真实的会话文件（user/assistant/toolResult/thinking/model/compaction/label…），
 * 然后量 `treeFromSessionFile()` 的耗时与产出体积。
 *
 * 门槛：3000 条约 200~400KB 会话文件 ⇒ 应在 **150ms 内**（pi 的 get_tree 要 10.9s@2160 条）。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { treeFromSessionFile } from '../termux/tree-from-file.mjs'

const count = Number(process.argv[2] || 3000)
const tmp = path.join(os.tmpdir(), `tree-bench-${Date.now()}.jsonl`)

const lines = []
lines.push(JSON.stringify({ type: 'session_meta', cwd: '/tmp/bench', createdAt: new Date(0).toISOString() }))

let parent = null
const userIds = []
for (let i = 0; i < count; i++) {
  const id = `e${i.toString().padStart(5, '0')}`
  const ts = new Date(1_700_000_000_000 + i * 1000).toISOString()
  const kind = i % 7
  let entry
  if (kind === 0) {
    entry = { type: 'message', id, parentId: parent, timestamp: ts, message: { role: 'user', content: `第 ${i} 条用户消息，用来量树投影的耗时` } }
    userIds.push(id)
  } else if (kind === 1) {
    entry = {
      type: 'message', id, parentId: parent, timestamp: ts,
      message: {
        role: 'assistant',
        content: [
          { type: 'text', text: `第 ${i} 条回复，带一点正文让投影有东西可截。` },
          { type: 'toolCall', id: `c${i}`, name: 'read', arguments: { path: `/tmp/f${i}.md` } },
        ],
      },
    }
  } else if (kind === 2) {
    entry = { type: 'message', id, parentId: parent, timestamp: ts, message: { role: 'toolResult', toolCallId: `c${i - 1}`, toolName: 'read', content: [{ type: 'text', text: 'x'.repeat(2000) }] } }
  } else if (kind === 3) {
    entry = { type: 'thinking_level_change', id, parentId: parent, timestamp: ts, thinkingLevel: 'high' }
  } else if (kind === 4) {
    entry = { type: 'model_change', id, parentId: parent, timestamp: ts, provider: 'p', modelId: 'm' }
  } else if (kind === 5) {
    entry = { type: 'compaction', id, parentId: parent, timestamp: ts, summary: 'summary', tokensBefore: 12345 }
  } else {
    // 给某个早期用户消息打标签（验证 label 回填）
    entry = { type: 'label', id, parentId: parent, timestamp: ts, targetId: userIds[Math.max(0, userIds.length - 3)] ?? 'e00000', label: '里程碑' }
  }
  lines.push(JSON.stringify(entry))
  parent = id
}

fs.writeFileSync(tmp, lines.join('\n') + '\n')
const size = fs.statSync(tmp).size

const t0 = process.hrtime.bigint()
const snap = treeFromSessionFile(tmp)
const ms = Number(process.hrtime.bigint() - t0) / 1e6
const bytes = Buffer.byteLength(JSON.stringify(snap))

const labeled = snap.nodes.filter((n) => n.label).length
console.log(`条目 ${count} → 文件 ${(size / 1024).toFixed(0)}KB`)
console.log(`投影耗时 ${ms.toFixed(1)}ms，输出 ${(bytes / 1024).toFixed(0)}KB，nodes=${snap.nodes.length}，labeled=${labeled}，leaf=${String(snap.leafId).slice(0, 8)}`)

fs.unlinkSync(tmp)

const budget = 150
if (ms > budget) {
  console.error(`❌ 太慢：${ms.toFixed(1)}ms > ${budget}ms（手机/模拟器上会明显卡）`)
  process.exit(1)
}
if (!snap.leafId) {
  console.error('❌ leafId 为空')
  process.exit(1)
}
console.log(`✅ 在预算内（< ${budget}ms）`)
