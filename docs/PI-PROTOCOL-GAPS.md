# pi 协议面 × pi-pocket 覆盖度（逆向自 pi 源码）

## 当前覆盖（2026-10-23，以本节为准）

本轮亲读 phone 还原源码 `src/modes/rpc/rpc-types.ts:19-75`，该版本 RpcCommand 实际是 **33 个**，旧文档的“35 个/已接 20 个”已失效。
静态对接不等于本次真实 pi 集成验收通过，手机不用于测试。

| 命令 | 桥入口 | 当前状态 |
| --- | --- | --- |
| prompt / steer / follow_up / abort / clear_queue | prompt、abort、queue/clear | 已接；clear+requeue 非原子 |
| new_session / switch_session / get_state / get_session_stats | sessions/new、sessions/open、info | 已接；每会话独立 worker |
| set_model / cycle_model / get_available_models | model、models | 已接 |
| set_thinking_level / cycle_thinking_level / get_available_thinking_levels | thinking、thinking/cycle、info | 已接 |
| set_steering_mode / set_follow_up_mode / set_auto_compaction / set_auto_retry / abort_retry | behavior、retry/abort | 已接；retry 配置另读 settings |
| compact / bash / abort_bash | compact、bash、bash/abort | 已接；bash 保留调用方 id 关联实时事件 |
| export_html / get_last_assistant_text | export、last-text | 已接 |
| fork / clone / get_fork_messages | fork、clone、fork-messages | 已接；fork 后重绑会话身份 |
| get_tree / set_session_name / get_messages / get_commands | tree、rename、messages、commands | 已接；树优先文件投影，RPC 回退 |
| get_entries | 无 | **未接**；没有端到端增量历史同步 |

扩展 UI response 是额外 JSONL 记录，走 ui，不重复计入 RpcCommand。
本轮提取 RpcChannel 处理关联/超时/管道错误/退出，OrderedEvents 保持异步工具显示线序和入队时的 sessionFile。
SSE 真桥/假桥均发 event ping；客户端按连接代次和会话过滤。
health 另给 pid/sourceSha，供部署绑定运行进程与源码；不新增RPC命令。
仍有全局 activeFile、多客户端请求隔离、非原子队列、单槽 outbox 和事务更新缺口，详见 [架构说明](ARCHITECTURE.md) 与 [贡献指南](../CONTRIBUTING.md)。

---

以下是 2026-10-22 分阶段对接记录，完成状态以当前覆盖表为准。

> 2026-10-22 用户：「不仅仅是视觉上的，还有 pi 功能、协议等等、状态传递等等、bridge 支持等等」。
> 协议面的**真源**是 pi 的源码 `src/modes/rpc/rpc-types.ts`（完整请求/响应/事件类型），
> 用 `tools/pi-src-extract.mjs` 从 `.js.map` 的 `sourcesContent` 还原（见 `docs/PI-SOURCE-NOTES.md`）。

## 一、RPC 命令：pi 有 35 个，桥接了 20 个

| 项 | 命令 |
| --- | --- |
| **桥已在用** | `abort`, `abort_bash`, `bash`, `clear_queue`, `compact`, `cycle_model`, `follow_up`, `get_available_models`, `get_available_thinking_levels`, `get_commands`, `get_messages`, `get_session_stats`, `get_state`, `new_session`, `prompt`, `set_model`, `set_session_name`, `set_thinking_level`, `steer`, `switch_session` |
| **没接（缺口）** | `abort_retry`, `clone`, `cycle_thinking_level`, `export_html`, `fork`, `get_entries`, `get_fork_messages`, `get_last_assistant_text`, `get_tree`, `set_auto_compaction`, `set_auto_retry`, `set_follow_up_mode`, `set_steering_mode` |
| 其实已实现（名字里看不出） | `extension_ui_request` / `extension_ui_response` —— 就是 app 的「扩展交互请求」（`PendingPromptSection` + 列表页 banner） |

## 二、缺口分类（按价值）

### A. ✅ 会话树 / 分叉（已完成 2026-10-22，见下）
`get_tree`、`get_entries`、`get_fork_messages`、`fork`、`clone`
- pi 的会话是**树**（一次会话里每个检查点可分叉）；app 现在只当**线性**列表画。
- 对接后能做：会话详情里看分叉树、从任意检查点 fork 出新会话、clone 当前会话。
- 注意：`get_state`/`get_messages` 现在都只取当前分支 ⇒ 状态传递要带上 branch 维度。

### B. 行为开关（终端里能设、app 里看不到）
`set_auto_compaction`、`set_auto_retry`、`abort_retry`、`set_steering_mode`、`set_follow_up_mode`
- 都是「设置」类，值由 `get_state` 回传 ⇒ app 的设置页可直接长出来（+ 状态传递要对上）。
- `abort_retry` 配合 `auto_retry_start` 事件（app 现在只显示"重试 n/m"的提醒，不能中断）。

### C. 会话交付 / 导出
`export_html`、`get_last_assistant_text`
- 导出 HTML 分享；复制最后一条回答（`/copy` 那种）。

### D. ✅ 快切（已完成 2026-10-22）
`cycle_model`、`cycle_thinking_level`
- 桥：`POST /v1/thinking/cycle`（新）、`POST /v1/model {cycle:"forward"|"backward"}`；
  app：思考等级面板「下一个」、模型面板「上一个/下一个」。
- 实测（模拟器 + 假桥）：思考 high → xhigh ✓。

## 三、事件/状态传递（app 侧要对上的）

pi 的事件（`rpc-types.ts` 里的 `session_update`/`response` 家族 + `core/agent-session.ts` 的 emitter）：
- 桥已转发：`agent_start`、`agent_settled`、`message_*`、`tool_execution_*`、`queue_update`、
  `compaction_*`、`auto_retry_*`、`session_info_changed`、`status`、`setTitle` …
- **待核对**：`setAutoCompaction`/`setAutoRetry`/`setSteeringMode` 这类**设置回执**（app 要有对应字段），
  以及 `get_tree` 之后的分支事件（fork/clone 的 session 身份变化）。
- 口径：**状态一律带归属**（`ownerMatchesSession`）—— 现有实现已经是这套，新字段按同一套加。

## 四、已完成：会话树 / 分叉（A 类）

| 能力 | 桥端点 | pi 命令 | app |
| --- | --- | --- | --- |
| 看会话树 | `GET /v1/tree` | `get_tree`（投影成扁平 DFS + `parentId`） | 「会话树 / 分叉」面板 |
| 分叉候选 | `GET /v1/fork-messages` | `get_fork_messages` | 同上（只有用户消息可点） |
| 从某条消息分叉 | `POST /v1/fork` `{entryId}` | `fork`（`position: "before"`） | 点行 → 确认 → 自动切到新会话、消息填回输入框 |
| 复制当前会话 | `POST /v1/clone` | `clone`（= 在 leaf 处 fork） | 面板底部「复制当前会话」 |

实现口径（都照 pi 源码，不靠截图猜）：

- **树的行**：`ui/SessionTree.kt` 的 `buildTreeRows()` 纯函数 —— 过滤档（default / no-tools /
  user-only / labeled-only / all）、连接线（只在「父节点有多个可见孩子」时画 `├─`/`└─`，
  祖先层该画 `│` 由「是不是最后一个孩子」决定）、条目文案，逐条对齐
  `components/tree-selector.ts`。
- **连接线不能画字符**：字符竖线只有字高，行间 padding 一有就断成一段一段（终端里字行紧贴才连得上）。
  app 用 `Canvas` 画（`drawBehind`：祖先层贯穿整行、本层从上一行到中心、`├` 延伸到下一行）。
- **fork 的真实语义**：pi 的 fork = **把这棵树的一个分支写成新会话文件**（`createBranchedSession`）
  然后 rebind。桥必须同步三件事，否则「消息落回分叉前的会话 / 事件算到旧会话」：
  ① `w.sessionFile` 换新文件（事件按它归属）；② `workers` 的 key 换掉；③ `activeFile` 指到新文件。
  app 侧再跟着把 `current` 换成新文件（**不要**再发 `switch_session`，已经就在上面了）。

真机验证（2026-10-22）：真桥 + 真 pi 上 fork 一条历史消息 → 新会话树正好截断在分叉点（78 → 10 条）、
`info.state.sessionFile` 指向新文件、`workers` 数不变（复用同一进程）、clone 同样成立。

## 五、bridge 支持（还要补的端点）

| 想做的事 | 桥要加 | 底层 pi 命令 |
| --- | --- | --- |
| 看会话树 | `GET /v1/tree` | `get_tree` + `get_entries` |
| 从检查点分叉 | `POST /v1/fork`、`POST /v1/clone` | `fork` / `clone`（+ `get_fork_messages` 给候选点） |
| 导出会话 | `POST /v1/export` → 返回 HTML/文件路径 | `export_html` |
| 复制最后回答 | `GET /v1/last-text` | `get_last_assistant_text` |
| 自动压缩/重试开关 | `POST /v1/auto`（字段合并） | `set_auto_compaction` / `set_auto_retry` / `abort_retry` |
| steer/queue 模式 | `POST /v1/mode` | `set_steering_mode` / `set_follow_up_mode` |
| 快切 | `POST /v1/cycle` | `cycle_model` / `cycle_thinking_level` |

> 口径同现有桥：**只做搬运 + 归属**，渲染交给 app（能继承终端的继承终端：tool 渲染仍走 pi 的 render-utils）。

## 2026-10-22 晚追加

- `GET /v1/info` 增加 **`gitBranch`**（照 `footer-data-provider.ts` 读 `.git/HEAD`，含 worktree）。
- `POST /v1/bash` **早已存在**（走 pi 的 `bash` RPC），本轮只是 app 侧开始用：
  输入 `!cmd` / `!!cmd` → 执行并记进会话（`bashExecution` 消息）。
- app 新增渲染两种 pi 消息：`bashExecution`、`branchSummary`（以前解析时被丢弃）。
