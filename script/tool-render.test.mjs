// 通用「本地扩展」枚举测试：装法各机不同（npm / settings 路径 / extensions 目录），
// 枚举必须可预测 —— 否则通用捕获会漏插件或指到不存在的入口。只测纯逻辑，不 import 真插件。
//
// 注意：Windows 上 path.resolve 会带盘符（D:\home\…），所以夹具路径也一律用 path.resolve 生成，
// 不要手写 '/home/…' —— 否则比较必然不相等（这是本文件第一版踩过的坑）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const MOD = pathToFileURL(path.join(here, '..', 'termux', 'tool-render', 'index.mjs')).href;
const { collectLocalExtensionSources } = await import(MOD);

const HOME = path.resolve('/home/tester');
const AGENT = path.join(HOME, '.pi/agent');

/** 伪造文件系统：files = 存在的文件，dirs = 存在的目录，settings = settings.json 内容。 */
function fake({ files = [], dirs = [], settings = null, extDir = null } = {}) {
  const fileSet = new Set(files.map((f) => path.normalize(f)));
  const dirSet = new Set(dirs.map((d) => path.normalize(d)));
  return {
    exists: (p) => fileSet.has(path.normalize(p)) || dirSet.has(path.normalize(p)),
    readFile: (p) => {
      if (path.normalize(p) === path.normalize(path.join(AGENT, 'settings.json')) && settings != null) return settings;
      throw new Error('ENOENT ' + p);
    },
    listDir: (p) => {
      if (extDir != null && path.normalize(p) === path.normalize(path.join(AGENT, 'extensions'))) return extDir;
      throw new Error('ENOENT ' + p);
    },
  };
}

test('settings 里的包路径（host-a 的 ../../projects/intercom/pi）→ 按 settings 目录解析出 index.ts', () => {
  const dir = path.resolve(AGENT, '../../projects/intercom/pi');
  const entry = path.join(dir, 'index.ts');
  const fsx = fake({
    dirs: [dir],
    files: [entry],
    settings: JSON.stringify({ packages: ['npm:pi-web-access', '../../projects/intercom/pi'] }),
  });
  assert.deepEqual(collectLocalExtensionSources({ home: HOME, ...fsx }), [{ id: 'local:pi', entry, dir }]);
});

test('包目录没有 index.ts 时按 package.json 的 pi.extensions 找入口', () => {
  const dir = path.resolve(HOME, 'projects/foo-ext');
  const entry = path.resolve(dir, 'dist/extension.js');
  const pkg = path.join(dir, 'package.json');
  const fsx = {
    exists: (p) => [entry, pkg, dir].some((x) => path.normalize(x) === path.normalize(p)),
    readFile: (p) => {
      if (path.normalize(p) === path.normalize(path.join(AGENT, 'settings.json'))) {
        return JSON.stringify({ packages: [dir] });
      }
      if (path.normalize(p) === path.normalize(pkg)) return JSON.stringify({ pi: { extensions: ['./dist/extension.js'] } });
      throw new Error('ENOENT');
    },
    listDir: () => [],
  };
  const sources = collectLocalExtensionSources({ home: HOME, ...fsx });
  assert.equal(sources.length, 1);
  assert.equal(sources[0].entry, entry);
});

test('settings 里的相对路径：先在 settings 目录找，找不到再按 home 找', () => {
  const dir = path.resolve(HOME, 'extras/my-ext');
  const entry = path.join(dir, 'index.ts');
  const fsx = fake({
    dirs: [dir],
    files: [entry],
    settings: JSON.stringify({ packages: ['extras/my-ext'] }),
  });
  assert.deepEqual(collectLocalExtensionSources({ home: HOME, ...fsx }).map((s) => s.entry), [entry]);
});

test('~/.pi/agent/extensions 下的单文件扩展也算（跳过 .d.ts 与非脚本）', () => {
  const extDir = path.join(AGENT, 'extensions');
  const fileTs = path.join(extDir, 'pi-notify.ts');
  const fileMjs = path.join(extDir, 'mailbox.mjs');
  const fsx = fake({
    files: [fileTs, fileMjs, path.join(extDir, 'types.d.ts'), path.join(extDir, 'README.md')],
    dirs: [extDir],
    extDir: ['pi-notify.ts', 'types.d.ts', 'mailbox.mjs', 'README.md'],
  });
  const sources = collectLocalExtensionSources({ home: HOME, ...fsx });
  assert.deepEqual(sources.map((s) => s.entry).sort(), [fileMjs, fileTs].sort());
  assert.ok(sources.every((s) => s.id.startsWith('local:')));
});

test('npm: 装的包不在通用捕获范围（它们有专门的适配器）', () => {
  const fsx = fake({ settings: JSON.stringify({ packages: ['npm:pi-unified-exec', 'npm:pi-web-access'] }) });
  assert.deepEqual(collectLocalExtensionSources({ home: HOME, ...fsx }), []);
});

test('同一入口被两条路指到只算一次（settings 指向 extensions 里的文件）', () => {
  const extDir = path.join(AGENT, 'extensions');
  const file = path.join(extDir, 'task-mailbox.ts');
  const fsx = fake({
    files: [file],
    dirs: [extDir],
    extDir: ['task-mailbox.ts'],
    settings: JSON.stringify({ packages: ['./extensions/task-mailbox.ts'] }),
  });
  const sources = collectLocalExtensionSources({ home: HOME, ...fsx });
  assert.equal(sources.length, 1);
  assert.equal(sources[0].entry, file);
});

test('没有 settings 也没有 extensions 目录：空列表，不报错', () => {
  assert.deepEqual(collectLocalExtensionSources({ home: HOME, ...fake({}) }), []);
});
