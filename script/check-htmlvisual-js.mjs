#!/usr/bin/env node
/**
 * 语法自检：把 HtmlVisual.kt 里内联的宿主页 <script> 抽出来（4 种 light/fullscreen 组合），
 * 用 `new Function` 解析一遍。踩过的坑：改这段 JS 时删掉了 fixCharts，页面直接抛
 * `Uncaught ReferenceError` ⇒ 整块退回代码块（真机才看出来）。这个脚本在跑测之前就能拦住。
 *
 * 用法：node script/check-htmlvisual-js.mjs
 */
import fs from 'node:fs';

const SRC = new URL('../app/src/main/java/id/harness/pipocket/ui/HtmlVisual.kt', import.meta.url);
const src = fs.readFileSync(SRC, 'utf8');
const start = src.indexOf('internal fun htmlVisualPage(');
if (start < 0) {
  console.error('找不到 htmlVisualPage');
  process.exit(2);
}
const chunk = src.slice(start, start + 20000);
const open = chunk.indexOf('<script>') + '<script>'.length;
const close = chunk.indexOf('</script>');
let js = chunk.slice(open, close);

let bad = 0;
for (const fullscreen of [false, true]) {
  for (const light of [false, true]) {
    const rendered = js
      .replace(/\$\{if \(fullscreen\) "true" else "false"\}/g, String(fullscreen))
      .replace(/\$\{if \(light\) "true" else "false"\}/g, String(light));
    try {
      new Function(rendered);
      console.log(`ok  fullscreen=${fullscreen} light=${light}`);
    } catch (e) {
      bad++;
      console.error(`FAIL fullscreen=${fullscreen} light=${light}: ${e.message}`);
    }
  }
}
process.exit(bad === 0 ? 0 : 1);
