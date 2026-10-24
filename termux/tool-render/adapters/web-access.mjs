/**
 * 适配器：pi-web-access（`web_search` / `fetch_content` / `get_search_content` / `source_check`）
 *
 * 取渲染器的方式：**capture**（这个包发的是打包好的 `dist/index.js`，渲染器内联在 `registerTool` 里）。
 * 用一个假的 pi API import 一遍扩展，把 `registerTool(...)` 的定义接住，只留 renderCall/renderResult。
 *
 * 升级后如果这里报错：
 *   1. `node -e "const p=require('~/.pi/agent/npm/node_modules/pi-web-access/package.json');console.log(p.pi)"`
 *      看 `pi.extensions` 指向哪个入口（现在是 `./dist`），改下面的 entry；
 *   2. 若包里改成「直接 export 渲染函数」，把它切成 exports 模式（照 unified-exec.mjs 写）。
 */
export default {
  id: "pi-web-access",
  package: "pi-web-access",
  mode: "capture",
  entry: "dist/index.js",
};
