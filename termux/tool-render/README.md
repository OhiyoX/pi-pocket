# 工具渲染器适配器（桥侧）

目标：**app 里的工具卡 = 终端里那一块**，而且**升级 pi / 插件后只需改一个适配器文件**。

## 为什么要这一层

pi 在 `--mode rpc` 下**不会**调用 `pi.registerToolRenderer` 的解析器（实测：扩展里拿到的
`renderers` 是空的，也没有 `getToolDefinition`），所以桥（独立 node 进程）必须自己去包里
把 `renderCall` / `renderResult` 取回来：

```
app 列宽(cols) ──▶ 桥 toolTerm() ──┬─ pi 内置工具：直接 import pi 的 dist/core/tools/*
                                    └─ pi 包里的工具：走本目录的适配器
                                          │
                                          ▼
                          renderCall/renderResult → comp.render(cols) → ANSI 行
                                          │
                          /v1/messages?cols=N 与 SSE tool_execution_end 附带 {"term": {...}}
                                          │
                          app：AnsiText 直接画（等宽 + 颜色 + 固定换行，不再二次排版）
```

## 目录

```
tool-render/
  index.mjs                 加载器：找 pi 根 → 用 **pi 自带 jiti** import 包源码（含 TS）
                            → 依赖 alias 到 pi 的 node_modules → 汇总工具名→渲染器
  adapters/unified-exec.mjs  exec_command / write_stdin / kill_session / list_sessions / set_on_exit
  adapters/web-access.mjs    web_search / fetch_content / get_search_content
```

**本地扩展不用写适配器**：加载器会枚举 `settings.json` 的 `packages`（非 `npm:` 条目）与
`~/.pi/agent/extensions/*.{ts,mjs,js}`，逐个用假 pi API import 一遍、把 `registerTool` 里的
`renderCall`/`renderResult` 接住（例：`pi-intercom` 的 `intercom` 工具）。
`PI_POCKET_CAPTURE_LOCAL_EXT=0` 可关掉这条通用捕获（万一某个扩展在 import 阶段就干活）。

显式适配器仍有两个用处：① npm 包需要 alias/入口知识（jiti alias 到 pi 的依赖）；
② 显式优先于通用捕获（同名工具以适配器为准）。适配器也可提供 `resolve(home)` 自定义包位置。

两种取法（适配器用 `mode` 声明）：

| mode | 适用 | 做法 |
| --- | --- | --- |
| `exports` | 包里有独立的渲染模块（`pi-unified-exec/src/render.ts`） | 用 jiti 直接 import，按名字取函数 |
| `capture` | 包是打包好的单文件、渲染器内联在 `registerTool` 里（`pi-web-access/dist/index.js`） | 造一个假 `pi` API，import 一遍扩展，接住 `registerTool(...)` 的定义 |

`capture` 的前提：**注册阶段没有副作用**。intercom 会在 `session_start` 里 `setTimeout` 连 broker，
而假 API 的 `on()` 是空操作 ⇒ 回调不会跑；实测（2026-10-24）import + 注册 963ms、进程自然退出、没拉起任何 broker。

## 升级 pi / 插件后怎么修（三步）

1. **看状态**：
   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8789/v1/term/adapters | jq .
   ```
   每个适配器都有 `ok` 与 `error`；桥启动日志里也有 `[adapters] …`。
2. **按报错改一个文件**：
   - `Cannot find module 'xxx'` → pi 把依赖搬走了：看 `…/pi-coding-agent/node_modules/` 里有没有，没有就说明该依赖由包自己带（装它）。
   - 函数名对不上 → `grep -n "^export function render" <包>/src/render.ts`，改 `adapters/*.mjs` 里的映射。
   - 入口变了 → 看 `node -e "console.log(require('<包>/package.json').pi)"`，改 `entry`。
3. **重装适配器 + 重启桥**（手机上）：
   ```bash
   cp -r termux/tool-render ~/.pi/agent/pi-pocket/tool-render   # 见下方路径
   FORCE=1 ~/start-bridge.sh
   ```

## 部署位置

桥按顺序找：

1. `<$PREFIX>/bin/tool-render/index.mjs`（与桥同目录）
2. `~/pi-pocket/termux/tool-render/index.mjs`（有仓库时）
3. `~/.pi/agent/pi-pocket/tool-render/index.mjs`（**当前手机用的就是这个**）

## 已知限制

- `web_search` 的 `source_check` 等少数工具在包里没注册渲染器 → 自动退回纯文本（不影响其它工具）。
- 适配器只负责**渲染**，不执行工具；执行永远在 pi 进程里。
- 渲染在桥侧缓存（键=工具名+toolCallId+内容长度+列宽），同一结果不会重复渲染。
