/**
 * 适配器：pi-unified-exec（`exec_command` / `write_stdin` / `kill_session` / `list_sessions` / …）
 *
 * 取渲染器的方式：**exports**（直接 import 包里的 `src/render.ts`，用 pi 自带的 jiti 处理 TS）。
 * 该文件的依赖只有 `@earendil-works/pi-tui` + 本包内相对文件，不需要 typebox，所以这条路最稳。
 *
 * 升级后如果这里报错（`/v1/term/adapters` 里看到 status.ok=false）：
 *   1. `ls ~/.pi/agent/npm/node_modules/pi-unified-exec/src/` 看 `render.ts` 还在不在；
 *   2. `grep -n "^export function render" .../render.ts` 看函数有没有改名；
 *   3. 只改下面 exports 里的映射即可（工具名 → 函数名）。
 */
export default {
  id: "pi-unified-exec",
  package: "pi-unified-exec",
  mode: "exports",
  exports: {
    exec_command: { module: "src/render.ts", renderCall: "renderExecCommandCall", renderResult: "renderProcessResult" },
    write_stdin: { module: "src/render.ts", renderCall: "renderWriteStdinCall", renderResult: "renderProcessResult" },
    kill_session: { module: "src/render.ts", renderCall: "renderKillSessionCall", renderResult: "renderKillSessionResult" },
    list_sessions: { module: "src/render.ts", renderCall: "renderListSessionsCall", renderResult: "renderListSessionsResult" },
    set_on_exit: { module: "src/render.ts", renderCall: "renderSetOnExitCall", renderResult: "renderSetOnExitResult" },
  },
};
