# pi-pocket-bridge（Termux 侧）

把 **pi 官方的 `--mode rpc` 协议**包成 **本地 HTTP + SSE** 网关，给同机上的 Pi Pocket app 用。

## 为什么

Android app 不能直接开 Termux 里的进程；而 Termux 内的 `127.0.0.1` 与同一台手机上的其它 app 共用
loopback，所以「Termux 里跑本桥 + app 连 `127.0.0.1:8789`」是**手机本地**（不是遥控 PC）最省事的一条路。
协议细节全部来自 pi 自带文档（`docs/rpc.md`、`docs/rpc-commands.md`、`docs/json.md`），本桥只做搬运。

## 装与跑

```sh
./termux/install.sh                     # → $PREFIX/bin/pi-pocket-bridge + pi-pocket-start
termux-wake-lock
nohup pi-pocket-bridge >> ~/.pi/agent/pi-pocket-bridge.log 2>&1 &
curl -s localhost:8789/v1/health
```

`pi-pocket-start` 是给 **app 的 `com.termux.RUN_COMMAND`**（以及手工/SSH）用的启动器：占 wake lock → 加载并持久化
token/bind → 设默认 cwd → `exec pi-pocket-bridge`，日志在 `~/.pi/agent/pi-pocket-start.log`。app 侧需要两件事才能用这条路：
1. Termux `~/.termux/termux.properties` 里 `allow-external-apps=true`（默认关；改完 `termux-reload-settings`）；
2. 运行时授予 app `com.termux.permission.RUN_COMMAND`（dangerous 权限，只在 manifest 里声明不够）。

环境变量：`PI_POCKET_PORT`（默认 8789）、`PI_POCKET_HOST`（默认 127.0.0.1）、`PI_POCKET_CWD`（新会话默认目录，
建议设成 `~/projects`）、`PI_POCKET_PI`（pi 可执行文件）、`PI_POCKET_IDLE_MS`（默认 900000，闲置回收 pi 子进程）、
`PI_POCKET_SESSIONS`（默认 `~/.pi/agent/sessions`）、`PI_POCKET_TOKEN`（非 loopback 绑定必须设）。

### 远端机器（host-a 等）

桥要给**别的设备上的 app** 用时，必须绑 `0.0.0.0` 并设 token（桥源码强制：非 loopback 必须带 token）：

```sh
PI_POCKET_HOST=0.0.0.0 PI_POCKET_TOKEN=<token> nohup sh pi-pocket-start >> ~/.pi/agent/pi-pocket-start.log 2>&1 &
```

`pi-pocket-start` 会把收到的 token/host 持久化到 `~/.pi/agent/pi-pocket.env`（600，其余键保留；写入前把上一版复制成
`pi-pocket.env.prev`，改坏了好回退），之后手工/app/SSH 拉起都沿用；不传就用 env 文件里的值，都没有则是回环。
写入 env 之前先做 **bind 预检**（用 node 试绑 `$HOST:$PORT`，`EADDRINUSE` 视为正常）：地址不可绑（如把 `0.0.0.0`
打成 `10.9.9.9`）时直接 `exit 2`，**不写 env、也不动正在跑的健康桥**。
已在跑的桥如果 bind/auth 与期望不一致，会按健康响应里的 **精确 PID 停掉后重启**；`FORCE=1` 是**无条件**重启
（安装/更新用，probe 拿不到 `200` 也照样换掉卡死的旧进程）。app 的 SSH 拉起命令也走这个启动器
（显式 `sh`，因为它的 shebang 是 Termux 路径）。

远端主机上的启动器必须与仓库同版（`termux/install.sh` 或随更新下发）；只更新 app 不会改远端主机上的启动器。
token 只允许 `A-Z a-z 0-9 . _ ~ + / = -`（app 生成的是 32 hex），host 只允许字母/数字/`.`/`:`/`-`，否则启动器拒绝写入。

**安全**：`0.0.0.0` 对所有网卡开放，token 在局域网里走明文 HTTP。只在可信网络（家用 LAN / Tailscale）使用，
或用防火墙限制来源；也可以只绑 Tailscale 地址，但 app 的拉起健康检查硬编码 `127.0.0.1:8789`，那条路会误报失败。

回到「仅本机」：删掉 env 文件里的 `PI_POCKET_HOST` 行，再 `FORCE=1 sh pi-pocket-start` 重启桥。

启动器行为测试：`node --test script/pi-pocket-start.test.mjs`（POSIX/dash 上 14 项全跑；Windows 上 5 项
`/proc` 相关跳过）。

## 端点（全部 `/v1`，返回 `{ok,data}` 或 `{ok:false,error}`）

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/v1/health` | 桥自身状态（pi 路径、cwd、订阅数、是否开鉴权、worker 是否活着） |
| GET | `/v1/events` | **SSE**：pi 会话事件（`message_update` 增量、工具、`agent_settled`…）+ 桥事件 |
| GET | `/v1/info` | `get_state` + `get_session_stats` + 可用思考等级 |
| GET | `/v1/sessions?limit=` | 扫盘列会话（文件、id、cwd、名字、mtime、大小） |
| POST | `/v1/sessions/open` | `{file}` → `switch_session` |
| POST | `/v1/sessions/new` | `{cwd?}` → `new_session` |
| GET | `/v1/messages?full=1` | `get_messages`（默认把历史图片 base64 换成占位） |
| POST | `/v1/prompt` | `{text, images:[{mimeType,data(base64)}], steer?, streamingBehavior?}` |
| POST | `/v1/abort` | 中止当前回合 |
| GET | `/v1/models` | `get_available_models` |
| POST | `/v1/model` | `{provider,model}` 或 `{cycle:true}` |
| POST | `/v1/thinking` | `{level}` |
| POST | `/v1/compact` | `{instructions?}` |
| POST | `/v1/rename` | `{name}` |
| GET | `/v1/commands` | `get_commands`（斜杠命令清单） |
| POST | `/v1/ui` | `{id, response, file?}` → 回原 worker 的扩展 UI 请求（confirm/select 等）；应提供请求所属会话 file，避免切会话后串台 |
| POST | `/v1/bash` | `{command, excludeFromContext?, id?}` → 走 pi RPC `bash`；输出另经 SSE `bash_execution_update` 流回（执行面） |
| POST | `/v1/bash/abort` | 中止正在跑的 `bash` |

## 怎么验（一手命令）

```sh
curl -s localhost:8789/v1/health
curl -s localhost:8789/v1/sessions | head -c 300
curl -sN localhost:8789/v1/events &                       # 另开一个 shell 看流
curl -s -X POST localhost:8789/v1/prompt -H 'content-type: application/json' \
     -d '{"text":"只回复：POCKET-OK"}'
```

图片（验证模型真收到图）：把任意 PNG 转 base64 放进 `images`，问「这是什么颜色」——
2026-10-18 实测：64×64 纯红 PNG → 回答「红色」，thinking 里报出 `#E61414`。

## 边界（写死，免得下次重踩）

- 桥**只做搬运**：不自己存会话、不改 pi 的会话文件；会话列表是扫盘读的（pi 没有「列会话」RPC）。
- **每个会话独立 pi worker**；sessions/open 切 activeFile，不中断其它运行会话；闲置回收跳过正在运行的一轮。
- 图片走 JSON body 的 base64（body 上限 48 MB）；是否压缩由 pi 的 `images.*` 设置决定。
- 默认 loopback + 无 token ⇒ 同机其它 app 也能连（Android 上 loopback 不是 app 隔离边界）；远端绑定必须带 token。
- **Android 上 `#!/usr/bin/env` 不存在**：安装时会把桥的 shebang 改指 `$PREFIX/bin/env node`，否则 `exec` 报 `not found`（踩过）。
- 停止须使用核实的精确 PID/进程树并独立复核残留。现有启动/自更新脚本仍有名称匹配技术债，不作为安全停止正式进程的操作建议。
- 路由层要求：GET 带 query 的处理器签名是 `(body, url)`，POST 是 `(body)` —— 改代码时别再把 `req` 加回去
  （2026-10-18 踩过两次：少一个参数就 500 / `empty prompt`）。

## 后续增加的端点（2026-10-23 核对）

以下与上面的初始端点表合起来覆盖当前 routes；每条仍返回统一 ok/data 包装。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET/POST | /v1/app-profile | 查询/选择 app 专属 skill、MCP 与附加提示 |
| GET/POST | /v1/device | 读取/上报设备画布信息，繁忙 worker 延迟回收 |
| GET | /v1/file | 按需读取工具产物、图片和 HTML |
| GET | /v1/config/machines | 读取 Termux SSH/机器配置，不返回 token 值 |
| POST | /v1/restart | 请求外部启动脚本重启，尚有名称匹配停止债务 |
| POST | /v1/reload | 回收指定/当前会话 worker 并重载 |
| GET | /v1/term/adapters | 工具渲染适配器状态 |
| GET/POST | /v1/providers | 读取/写入模型 provider 配置 |
| POST | /v1/providers/remove | 移除 provider 配置 |
| POST | /v1/queue/clear | 清空队列并返回待处理文本 |
| POST | /v1/thinking/cycle | 下一个思考等级 |
| GET | /v1/tree | 会话树投影，文件快路/RPC 回退 |
| GET | /v1/fork-messages | 可分叉用户消息 |
| POST | /v1/fork、/v1/clone | 分叉/复制并重绑 worker 身份 |
| GET/POST | /v1/behavior | 行为开关与排队模式 |
| POST | /v1/retry/abort | 中止自动重试 |
| POST | /v1/export | 导出 HTML，返回产物路径 |
| GET | /v1/last-text | 最后助手正文 |
| GET | /v1/slash | 从 pi 交互源码提取命令表 |
| GET | /v1/ssh/hosts | 文件页的 SSH 别名 |
| POST | /v1/fs | target/op/path 驱动文件操作 |

SSE 的 pi 记录带 sessionFile；保活为 event ping + JSON data。异步工具显示保持单 worker 顺序。
health 增加 pid 与 sourceSha（由部署环境 PI_POCKET_SOURCE_SHA 提供）；部署需绑定新PID与不可变源码版本。
PI_POCKET_APP_PROFILE_DIR 可显式固定原profile目录，避免版本化入口改变默认配置位置。
请求关联由 rpc-channel.mjs 管理，事件投递由 event-stream.mjs 管理；安装不能只复制桥主文件。
完整协议覆盖和未实现项见 [协议表](../docs/PI-PROTOCOL-GAPS.md)。
