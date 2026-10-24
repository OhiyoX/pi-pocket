#!/usr/bin/env node
/**
 * 会话文件 → 树投影（`GET /v1/tree` 的快路）。
 *
 * 为什么不用 pi 的 `get_tree`：它会把**每个条目的完整消息**（图片 base64、工具输出…）
 * 通过 stdio 全程传一遍再被桥上丢掉 —— 实测 2160 条的真实会话要 **10.9 秒**。
 * 会话文件本身就是逐行追加的条目（`SessionEntry`，带 `id`/`parentId`），
 * 直接读文件按同样规则投影，**几百毫秒**就完了。
 *
 * 单独成模块也是为了能脱离设备测：`node tools/tree-bench.mjs`。
 *
 * 与 pi 的 `get_tree` 对齐的部分：
 *  · 树形状完全来自 `parentId`；
 *  · `label` 条目对它**之前**的条目生效（后写的覆盖先写的）；
 *  · `leafId` = 文件里**最后一条**条目的 id（RPC 里没有 navigate，叶子就是最后写入的那条）。
 */
import fs from 'node:fs'

const clip = (s, n) => (typeof s === 'string' ? s.replace(/\s+/g, ' ').trim().slice(0, n) : '')

/** 消息里的纯文本（content 可能是字符串，也可能是 part 数组）。 */
export function messageTextOf(msg) {
  const content = msg?.content
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .filter((p) => p && p.type === 'text' && typeof p.text === 'string')
    .map((p) => p.text)
    .join('\n')
}

/** assistant 消息里的工具调用（`{id, name, argsText}`），给 toolResult 行配对用。 */
export function toolCallsOf(msg) {
  const content = msg?.content
  if (!Array.isArray(content)) return []
  return content
    .filter((p) => p && (p.type === 'toolCall' || p.type === 'tool_use'))
    .map((p) => {
      let args = ''
      try { args = JSON.stringify(p.arguments ?? p.input ?? {}) } catch { /* ignore */ }
      return { id: p.id ?? null, name: p.name ?? '', argsText: args.slice(0, 200) }
    })
}

/**
 * 一条 SessionEntry → 树上要用的字段（`core/session-manager.ts:183` 的 union）。
 *
 * 只留画树要用的：文本截断到 300 字（一行树也放不下更多），
 * 图片/工具正文一律不传（文件里可能有几 MB 的 base64）。
 */
export function projectTreeEntry(entry) {
  const out = {
    id: entry.id,
    parentId: entry.parentId ?? null,
    type: entry.type,
    timestamp: entry.timestamp ?? null,
  }

  switch (entry.type) {
    case 'message': {
      const msg = entry.message || {}
      out.role = msg.role || null
      out.text = clip(messageTextOf(msg), 300)
      if (msg.stopReason) out.stopReason = msg.stopReason
      if (msg.errorMessage) out.errorMessage = clip(msg.errorMessage, 200)
      if (msg.toolCallId) out.toolCallId = msg.toolCallId
      if (msg.toolName) out.toolName = msg.toolName
      if (msg.command) out.command = clip(msg.command, 200)   // bashExecution
      const calls = toolCallsOf(msg)
      if (calls.length) out.toolCalls = calls
      break
    }
    case 'compaction':
      out.tokensBefore = entry.tokensBefore ?? 0
      out.summary = clip(entry.summary, 200)
      break
    case 'branch_summary':
      out.summary = clip(entry.summary, 300)
      out.fromId = entry.fromId ?? null
      break
    case 'model_change':
      out.provider = entry.provider ?? null
      out.modelId = entry.modelId ?? null
      break
    case 'thinking_level_change':
      out.thinkingLevel = entry.thinkingLevel ?? null
      break
    case 'custom':
      out.customType = entry.customType ?? null
      break
    case 'custom_message': {
      out.customType = entry.customType ?? null
      const c = entry.content
      out.text = clip(typeof c === 'string' ? c : messageTextOf({ content: c }), 300)
      break
    }
    case 'context_edit':
      out.targetId = entry.targetId ?? null
      out.omitted = entry.replacement === null
      break
    case 'usage':
      break
    default:
      break
  }
  return out
}

/**
 * 从会话文件构建树。
 *
 * @param {string} file 会话 `.jsonl` 路径
 * @param {{maxBytes?: number, maxNodes?: number}} [opts]
 * @returns {{leafId: string|null, nodes: object[]}}
 */
export function treeFromSessionFile(file, opts = {}) {
  const maxBytes = opts.maxBytes ?? 128 * 1024 * 1024
  if (!file || !fs.existsSync(file)) throw new Error('当前会话还没有文件（先发一条消息）')
  const st = fs.statSync(file)
  if (st.size > maxBytes) throw new Error(`会话文件太大（${Math.round(st.size / 1048576)}MB）`)

  const raw = fs.readFileSync(file, 'utf8')
  const nodes = []
  const labels = new Map()     // targetId -> { label, timestamp }
  let leafId = null

  for (const line of raw.split('\n')) {
    if (!line || line[0] !== '{') continue
    let entry
    try { entry = JSON.parse(line) } catch { continue }
    if (!entry || !entry.id || !entry.type) continue      // header 行（session_meta）没有 id

    if (entry.type === 'label') {
      // label 自己也是一条条目：树里要保留它的形状（"全部"档会显示），
      // 但它同时会给目标条目加标签。
      labels.set(entry.targetId, { label: entry.label ?? null, timestamp: entry.timestamp ?? null })
      const proj = projectTreeEntry(entry)
      proj.targetId = entry.targetId ?? null
      proj.entryLabel = entry.label ?? null
      nodes.push(proj)
      leafId = entry.id
      continue
    }

    const proj = projectTreeEntry(entry)
    if (entry.type === 'session_info') proj.sessionName = entry.name ?? null
    nodes.push(proj)
    leafId = entry.id
  }

  // 标签回填：后写的优先（Map 天然如此）；没标签的给 null（app 侧靠 label != null 过滤）
  for (const n of nodes) {
    const hit = labels.get(n.id)
    n.label = hit ? hit.label : (n.label ?? null)
    if (hit) n.labelTimestamp = hit.timestamp
  }

  return { leafId, nodes }
}
