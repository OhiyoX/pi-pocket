/**
 * 桥侧「工具渲染器适配器」注册表（用户口径：走 B —— 桥自适应，pi/插件升级后好修）。
 *
 * 为什么需要这一层：`exec_command` / `web_search` 这些工具来自 **pi 包**
 * （pi-unified-exec / pi-web-access），它们的 renderCall/renderResult 只在包内部，
 * 而 pi 在 `--mode rpc` 下**不会**调用 `registerToolRenderer`（实测：解析器为空），
 * 所以桥必须自己去包里把渲染函数拿回来。
 *
 * 设计（升级后要修的只有「一个适配器文件」）：
 *   termux/tool-render/
 *     index.mjs                 ← 本文件：发现 pi、准备 jiti、按 adapters/ 逐个加载、汇总状态
 *     adapters/<package>.mjs    ← 每个 pi 包一个：声明「从哪个文件拿哪些渲染函数」
 *     README.md                 ← 升级排障手册（看 /v1/term/adapters 的 status）
 *
 * 两种取渲染器的方式（适配器自己声明）：
 *   1) `exports`：直接从包里的某个模块 import 具名渲染函数（pi-unified-exec 的 render.ts）；
 *   2) `capture`：把包当作 pi 扩展 import 一遍，用一个**假的 pi API** 接住 registerTool(…)
 *      里的定义（pi-web-access 是打包好的 dist/index.js，渲染器内联在 registerTool 里）。
 *
 * 依赖处理：pi 包里的 `@earendil-works/pi-tui` 等并不在它自己的 node_modules 里，
 * 由 pi 携带；这里用 **pi 自带的 jiti** + alias 指过去（不拷文件、不做 shim 维护）。
 */
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ADAPTER_DIR = path.join(HERE, "adapters");

/** 找 pi 包根（含 node_modules/@earendil-works/pi-tui 与 jiti 的那一份）。 */
export function findPiRoot({ prefix, distRoot }) {
  const candidates = [];
  if (distRoot) candidates.push(path.dirname(distRoot)); // …/pi-coding-agent/dist → 上一级
  if (prefix) candidates.push(path.join(prefix, "lib/node_modules/@earendil-works/pi-coding-agent"));
  candidates.push(path.join(process.env.HOME || "", ".local/lib/node_modules/@earendil-works/pi-coding-agent"));
  candidates.push("/usr/local/lib/node_modules/@earendil-works/pi-coding-agent");
  for (const c of candidates) {
    try {
      if (fs.existsSync(path.join(c, "node_modules/jiti"))) return c;
    } catch { /* 继续试 */ }
  }
  return null;
}

async function makeJiti(piRoot) {
  const entry = path.join(piRoot, "node_modules/jiti/lib/jiti.mjs");
  const mod = await import(pathToFileURL(entry).href);
  const createJiti = mod.default || mod.createJiti;
  // pi 包里的第三方依赖（typebox 等）并不在自己的 node_modules 里，由 pi 携带；
  // 这里把这些名字统一 alias 到 pi 的那份 —— 升级 pi 后不用改这里，除非包换了依赖。
  const alias = {
    "@earendil-works/pi-tui": path.join(piRoot, "node_modules/@earendil-works/pi-tui"),
    "@earendil-works/pi-coding-agent": piRoot,
  };
  const piDeps = path.join(piRoot, "node_modules");
  try {
    for (const name of fs.readdirSync(piDeps)) {
      if (name.startsWith(".")) continue;
      if (name.startsWith("@")) {
        for (const sub of fs.readdirSync(path.join(piDeps, name))) {
          alias[name + "/" + sub] = path.join(piDeps, name, sub);
        }
      } else {
        // 别名要指到**模块入口**而不是目录：有些包（typebox）没有 "." 根导出，
        // 指目录会让解析失败（Cannot find module '…/typebox'）。
        const dir = path.join(piDeps, name);
        let entry = dir;
        try {
          const pj = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8"));
          const root = pj.exports && (pj.exports["."] || pj.exports);
          const target = root && (root.import || root.default || root);
          if (typeof target === "string") entry = path.join(dir, target);
          else if (typeof pj.main === "string") entry = path.join(dir, pj.main);
        } catch { /* 没 package.json 就直接指目录 */ }
        alias[name] = entry;
      }
    }
  } catch { /* 读不了就只留上面两条 */ }
  return createJiti(import.meta.url, { interopDefault: true, moduleCache: false, alias });
}

/** 造一个"够用就好的假 pi"：扩展只在 import/注册阶段调这些；工具执行不在这里发生。 */
function fakePi(captured) {
  const noop = () => {};
  const api = {
    registerTool: (def) => {
      if (def && def.name) captured.set(def.name, def);
      return { dispose: noop };
    },
    // 扩展在注册阶段可能会读事件总线（例如注册完成事件）——不是真的总线，不能让它炸。
    events: { emit: noop, on: () => noop, off: noop },
    getToolDefinition: () => undefined,
    getModel: () => null,
    getSessionId: () => "pi-pocket-adapter-probe",
    registerToolRenderer: noop,
    registerCommand: noop,
    registerFlag: noop,
    registerShortcut: noop,
    registerProvider: noop,
    registerMcpServer: noop,
    unregisterMcpServer: noop,
    registerEntryRenderer: noop,
    registerMessageRenderer: noop,
    registerStatus: noop,
    setActiveTools: noop,
    on: () => noop,
  };
  return new Proxy(api, { get: (t, k) => (k in t ? t[k] : noop) });
}

function pkgDirOf(home, pkgName) {
  const bases = [
    path.join(home, ".pi/agent/npm/node_modules"),
    path.join(home, ".pi/node_modules"),
  ];
  for (const b of bases) {
    const dir = path.join(b, pkgName);
    try { if (fs.existsSync(dir)) return dir; } catch { /* 继续 */ }
  }
  return null;
}

/**
 * 逐个加载适配器。任何一个失败都不影响其它适配器，失败原因进 status（供 /v1/term/adapters 查看）。
 * @returns {Promise<{get:(name:string)=>{renderCall?:Function,renderResult?:Function}|null, status:Array}>}
 */
export async function createAdapterRegistry({ home, prefix, distRoot, log }) {
  const say = typeof log === "function" ? log : () => {};
  const status = [];
  const byTool = new Map();
  const adapterDirs = new Set();

  let piRoot = null;
  let jiti = null;
  try {
    piRoot = findPiRoot({ prefix, distRoot });
    if (!piRoot) throw new Error("找不到 pi 包根（或它没有 jiti）");
    jiti = await makeJiti(piRoot);
  } catch (e) {
    status.push({ id: "*setup*", ok: false, error: e.message });
    say("[adapters] 初始化失败：" + e.message);
    return { get: () => null, status };
  }

  let files = [];
  try {
    files = fs.readdirSync(ADAPTER_DIR).filter((f) => f.endsWith(".mjs")).sort();
  } catch (e) {
    status.push({ id: "*dir*", ok: false, error: e.message });
    return { get: () => null, status };
  }

  for (const file of files) {
    const id = file.replace(/\.mjs$/, "");
    try {
      const mod = await import(pathToFileURL(path.join(ADAPTER_DIR, file)).href);
      const adapter = mod.default || mod;
      // 适配器可以自己找包（本地扩展装在哪，因机器而异）；找不到再回退到 npm 目录
      const pkgDir = (typeof adapter.resolve === "function" ? adapter.resolve(home) : null)
        || pkgDirOf(home, adapter.package);
      if (!pkgDir) throw new Error("没装这个包：" + adapter.package);
      const tools = await loadOne(adapter, { jiti, pkgDir, piRoot });
      const names = Object.keys(tools);
      if (!names.length) throw new Error("包里没找到可用的 renderers");
      for (const [tool, r] of Object.entries(tools)) byTool.set(tool, { ...r, adapter: adapter.id || id });
      status.push({ id: adapter.id || id, ok: true, package: adapter.package, tools: names });
      say("[adapters] " + (adapter.id || id) + " → " + names.join(","));
      if (pkgDir) adapterDirs.add(path.resolve(pkgDir));
    } catch (e) {
      status.push({ id, ok: false, error: e.message });
      say("[adapters] " + id + " 失败：" + e.message);
    }
  }

  // 通用捕获：本机其它本地扩展（不在 adapters/ 里的）也把渲染器取回来，不用一个插件写一个适配器
  if (process.env.PI_POCKET_CAPTURE_LOCAL_EXT !== "0") {
    try {
      const localStatus = await captureLocalExtensions({ home, jiti, byTool, skipDirs: adapterDirs });
      for (const item of localStatus) {
        status.push(item);
        if (item.ok) say("[adapters] " + item.id + " → " + item.tools.join(","));
        else say("[adapters] " + item.id + " 失败：" + item.error);
      }
    } catch (e) {
      status.push({ id: "*local-extensions*", ok: false, error: e.message });
    }
  }

  return { get: (name) => byTool.get(name) || null, status, tools: () => [...byTool.keys()] };
}

function entryOfDir(dir, { exists, readFile }) {
  let pj = null;
  try {
    pj = JSON.parse(readFile(path.join(dir, "package.json")));
  } catch { /* 没 package.json 也能按约定入口找 */ }
  const declared = pj?.pi?.extensions;
  const declaredList = Array.isArray(declared) ? declared : declared ? [declared] : [];
  for (const rel of declaredList) {
    if (typeof rel !== "string") continue;
    const candidate = path.resolve(dir, rel);
    if (exists(candidate)) return candidate;
  }
  const fallbacks = ["index.ts", "index.mjs", "index.js", pj?.main].filter((x) => typeof x === "string");
  for (const rel of fallbacks) {
    const candidate = path.resolve(dir, rel);
    if (exists(candidate)) return candidate;
  }
  return null;
}

/**
 * 枚举本机的**本地扩展**：`settings.json` 的 `packages` 里不是 `npm:` 的条目 + `~/.pi/agent/extensions/*`。
 *
 * 抽成纯函数（注入 exists/readFile/listDir）方便单测：装法各机不同，不能只靠真机碰运气。
 * @returns {Array<{id: string, entry: string, dir?: string}>}
 */
export function collectLocalExtensionSources({
  home,
  exists = (p) => fs.existsSync(p),
  readFile = (p) => fs.readFileSync(p, "utf8"),
  listDir = (p) => fs.readdirSync(p),
}) {
  const agentDir = path.join(home, ".pi/agent");
  const out = [];
  const seen = new Set();
  const add = (id, entry, dir) => {
    if (!entry || seen.has(entry)) return;
    seen.add(entry);
    out.push({ id, entry, dir });
  };

  let settings = null;
  try {
    settings = JSON.parse(readFile(path.join(agentDir, "settings.json")));
  } catch { /* 没 settings 就看 extensions 目录 */ }
  for (const item of Array.isArray(settings?.packages) ? settings.packages : []) {
    if (typeof item !== "string" || item.startsWith("npm:")) continue;
    const bases = path.isAbsolute(item) ? [""] : [agentDir, home];
    for (const base of bases) {
      const dir = base ? path.resolve(base, item) : item;
      if (!exists(dir)) continue;
      const entry = entryOfDir(dir, { exists, readFile });
      if (entry) add("local:" + path.basename(dir), entry, dir);
      break;
    }
  }

  const extDir = path.join(agentDir, "extensions");
  let names = [];
  try {
    names = listDir(extDir);
  } catch { /* 没有这个目录 */ }
  for (const name of names.sort()) {
    if (name.endsWith(".d.ts")) continue;
    if (!/\.(ts|mjs|js)$/.test(name)) continue;
    const file = path.join(extDir, name);
    if (!exists(file)) continue;
    add("local:" + name.replace(/\.(ts|mjs|js)$/, ""), file);
  }
  return out;
}

/**
 * 通用 capture：把每个本地扩展用假 pi import 一遗，接住 `registerTool` 里的 renderCall/renderResult。
 *
 * 安全性：假 API 的 `on()` 是空操作 ⇒ 扩展把“连服务/起定时器”放在 `session_start` 之类回调里的不会跑
 * （intercom 的 broker 就是这样）。但扩展如果在 **import 阶段**就干活，这里拦不住 ——
 * `PI_POCKET_CAPTURE_LOCAL_EXT=0` 可一键关掉这条通用捕获，退回只靠 adapters/ 里的显式适配器。
 */
async function captureLocalExtensions({ home, jiti, byTool, skipDirs }) {
  const sources = collectLocalExtensionSources({ home });
  const status = [];
  if (!sources.length) return status;
  const skipped = skipDirs || new Set();
  for (const src of sources) {
    if (src.dir && skipped.has(path.resolve(src.dir))) continue;
    const captured = new Map();
    try {
      const mod = await jiti.import(src.entry);
      const fn = mod?.default || mod;
      if (typeof fn !== "function") {
        status.push({ id: src.id, ok: false, error: "不是扩展（没有默认导出函数）" });
        continue;
      }
      try {
        await fn(fakePi(captured));
      } catch (e) {
        if (!captured.size) throw e;
      }
      const names = [];
      for (const [name, def] of captured) {
        if (byTool.has(name)) continue; // 显式适配器优先
        if (typeof def.renderCall !== "function" && typeof def.renderResult !== "function") continue;
        byTool.set(name, { renderCall: def.renderCall, renderResult: def.renderResult, adapter: src.id });
        names.push(name);
      }
      if (names.length) status.push({ id: src.id, ok: true, tools: names });
    } catch (e) {
      status.push({ id: src.id, ok: false, error: e.message });
    }
  }
  return status;
}

async function loadOne(adapter, { jiti, pkgDir, piRoot }) {
  const out = {};
  if (adapter.mode === "capture") {
    const entry = path.join(pkgDir, adapter.entry || "dist/index.js");
    const captured = new Map();
    const mod = await jiti.import(entry);
    const fn = mod?.default || mod;
    if (typeof fn === "function") {
      // 注册中途报错也要把已经收到的工具定义留下（例如扩展装了一个这里用不到的依赖）
      try {
        await fn(fakePi(captured));
      } catch (e) {
        if (!captured.size) throw e;
      }
    }
    for (const [name, def] of captured) {
      if (typeof def.renderCall === "function" || typeof def.renderResult === "function") {
        out[name] = { renderCall: def.renderCall, renderResult: def.renderResult };
      }
    }
    return out;
  }
  // exports 模式：adapter.exports = { toolName: { module: "src/render.ts", renderCall: "fn", renderResult: "fn" } }
  const cache = new Map();
  for (const [tool, spec] of Object.entries(adapter.exports || {})) {
    const file = path.join(pkgDir, spec.module);
    if (!cache.has(file)) {
      const monkey = await jiti.import(file);
      cache.set(file, monkey);
    }
    const m = cache.get(file);
    const rc = spec.renderCall ? m[spec.renderCall] : undefined;
    const rr = spec.renderResult ? m[spec.renderResult] : undefined;
    if (typeof rc !== "function" && typeof rr !== "function") continue;
    out[tool] = { renderCall: rc, renderResult: rr };
  }
  return out;
}
