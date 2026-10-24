// 服务化行为测试：runsv run 脚本 + start-bridge.sh 的分流（sv 有则 sv，无则直起）。
// 全部用桩命令（sv / ps / node / setsid / nohup / sleep），不启动真桥、不碰真 $PREFIX。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RUN = path.join(root, 'termux', 'service', 'pi-pocket', 'run');
const START_BRIDGE = path.join(root, 'termux', 'start-bridge.sh');
const WATCHDOG = path.join(root, 'termux', 'pi-pocket-watchdog.sh');
const BOOT = path.join(root, 'termux', 'boot', 'start-pocket');
// Git Bash 里 HOME / PATH 必须是 POSIX 路径
const shPath = p => process.platform === 'win32'
  ? p.replace(/\\/g, '/').replace(/^([A-Za-z]):/, (_, d) => '/' + d.toLowerCase())
  : p;

function stub(dir, name, body) {
  const file = path.join(dir, name);
  writeFileSync(file, body);
  chmodSync(file, 0o755);
  return file;
}

/** 一套沙箱：fake PREFIX（含 bin 桩）、fake SVDIR、fake HOME。 */
function sandbox({ service = true, failFirst = 0, down = false } = {}) {
  const rootDir = mkdtempSync(path.join(tmpdir(), 'pipocket-svc-'));
  const prefix = path.join(rootDir, 'prefix');
  const bin = path.join(prefix, 'bin');
  const svdir = path.join(prefix, 'var', 'service');
  const home = path.join(rootDir, 'home');
  for (const d of [bin, svdir, path.join(home, '.pi', 'agent')]) mkdirSync(d, { recursive: true });
  if (service) {
    mkdirSync(path.join(svdir, 'pi-pocket'), { recursive: true });
    writeFileSync(path.join(svdir, 'pi-pocket', 'run'), '#!/bin/sh\nexit 0\n');
    chmodSync(path.join(svdir, 'pi-pocket', 'run'), 0o755);
    if (down) writeFileSync(path.join(svdir, 'pi-pocket', 'down'), '');
  }

  const svLog = path.join(rootDir, 'sv.log');
  stub(bin, 'sv', `#!/bin/sh\necho "$*" >> "${shPath(svLog)}"\nexit 0\n`);
  // ps：service_ready 用 `ps -eo args | grep '[r]unsvdir /'` 判断监督是否在
  stub(bin, 'ps', service
    ? '#!/bin/sh\necho "  22113     1 /data/data/com.termux/files/usr/bin/runsvdir /data/data/com.termux/files/usr/var/service"\n'
    : '#!/bin/sh\necho " 1 1 bash"\n');
  const counter = path.join(rootDir, 'counter');
  writeFileSync(counter, '0');
  stub(bin, 'node', [
    '#!/bin/sh',
    `c="${shPath(counter)}"`,
    'n=$(cat "$c" 2>/dev/null || echo 0)',
    'n=$((n+1)); echo "$n" > "$c"',
    `if [ "$n" -le ${failFirst} ]; then echo 000; else echo 200; fi`,
    '',
  ].join('\n'));
  stub(bin, 'sleep', '#!/bin/sh\nexit 0\n');
  stub(bin, 'setsid', '#!/bin/sh\nexec "$@"\n');
  stub(bin, 'nohup', '#!/bin/sh\nexec "$@"\n');
  stub(bin, 'termux-wake-lock', '#!/bin/sh\nexit 0\n');
  stub(bin, 'flock', '#!/bin/sh\nexit 0\n');
  const started = path.join(rootDir, 'started.log');
  stub(bin, 'pi-pocket-start', [
    '#!/bin/sh',
    `echo "FORCE=\${FORCE:-unset} PATH_has_prefix=0" >> "${shPath(started)}"`,
    `echo "SHA=\${PI_POCKET_SOURCE_SHA:-unset}" >> "${shPath(started)}"`,
    `case ":$PATH:" in *":${shPath(bin)}:"*) echo "PATH_has_prefix=1" >> "${shPath(started)}";; esac`,
    'exit 0',
    '',
  ].join('\n'));

  return {
    rootDir,
    prefix,
    bin,
    svdir,
    home,
    svLog,
    started,
    run(file, cmd, extraEnv = {}) {
      return new Promise(resolve => {
        const child = spawn(cmd, [file], {
          env: {
            PATH: `${shPath(bin)}:${process.env.PATH}`,
            HOME: shPath(home),
            PREFIX: shPath(prefix),
            SVDIR: shPath(svdir),
            PI_POCKET_PORT: '18991',
            ...extraEnv,
          },
        });
        let out = '';
        child.stdout.on('data', c => { out += c; });
        child.stderr.on('data', c => { out += c; });
        child.on('close', status => resolve({ status, out }));
      });
    },
    svCalls: () => (existsSync(svLog) ? readFileSync(svLog, 'utf8').trim().split('\n').filter(Boolean) : []),
    startLog: () => (existsSync(started) ? readFileSync(started, 'utf8') : ''),
    async waitStarted(ms = 3000) {
      const until = Date.now() + ms;
      while (Date.now() < until) {
        if (existsSync(started)) return readFileSync(started, 'utf8');
        await new Promise(r => setTimeout(r, 50));
      }
      return existsSync(started) ? readFileSync(started, 'utf8') : '';
    },
    cleanup() { rmSync(rootDir, { recursive: true, force: true }); },
  };
}

const SH = process.platform === 'win32'
  ? 'sh'
  : (existsSync('/bin/sh') ? '/bin/sh' : '/data/data/com.termux/files/usr/bin/sh');
const BASH = 'bash';

test('脚本语法：run / start-bridge / watchdog / boot 都能过 shell 解析', async () => {
  for (const [file, shell] of [[RUN, SH], [START_BRIDGE, BASH], [WATCHDOG, BASH], [BOOT, SH]]) {
    const r = await new Promise(resolve => {
      const child = spawn(shell, ['-n', file]);
      let out = '';
      child.stdout.on('data', c => { out += c; });
      child.stderr.on('data', c => { out += c; });
      child.on('close', status => resolve({ status, out }));
    });
    assert.equal(r.status, 0, `${path.basename(file)}: ${r.out}`);
  }
});

test('run 脚本：以 FORCE=1 交给启动器（runsv 每次起都要求独占 8789）', async () => {
  const box = sandbox({ service: true });
  try {
    const r = await box.run(RUN, SH);
    assert.equal(r.status, 0, r.out);
    const log = box.startLog();
    assert.match(log, /FORCE=1/);
    assert.match(log, /PATH_has_prefix=1/);
  } finally { box.cleanup(); }
});

test('run 脚本：缺启动器时不冒充已启动（非 0 退出，让 runsv 重试）', async () => {
  const box = sandbox({ service: true });
  try {
    rmSync(path.join(box.bin, 'pi-pocket-start'));
    // 真机器上 $PREFIX/bin 有真启动器：把它从 PATH 里剔掉，否则 run 脚本会误用它去真起桥。
    // （sh 自身用绝对路径，因此剔掉 $PREFIX/bin 不影响 spawn。）
    const keepPath = (process.env.PATH || '').split(path.delimiter)
      .filter(d => d && !existsSync(path.join(d, 'pi-pocket-start')))
      .join(path.delimiter);
    const r = await box.run(RUN, SH, { PATH: `${shPath(box.bin)}${path.delimiter}${keepPath}` });
    assert.notEqual(r.status, 0, r.out);
    assert.match(r.out, /pi-pocket-start 未安装/);
  } finally { box.cleanup(); }
});

test('run 脚本：从 runtime 软链解析出源码版本，交给桥的 health.sourceSha', async () => {
  const box = sandbox({ service: true });
  try {
    const sha = '148ed38deadbeef';
    const dir = path.join(box.prefix, 'agent', 'pi-pocket-runtime', sha);
    mkdirSync(dir, { recursive: true });
    const target = path.join(dir, 'pi-pocket-bridge.mjs');
    writeFileSync(target, '// bridge\n');
    const entry = path.join(box.prefix, 'bin', 'pi-pocket-bridge');
    try {
      (await import('node:fs')).symlinkSync(target, entry);
    } catch (error) {
      // Windows 需要开发者模式/权限才能建软链：环境不支持就跳过这条
      if (process.platform === 'win32') return;
      throw error;
    }
    const r = await box.run(RUN, SH);
    assert.equal(r.status, 0, r.out);
    assert.match(box.startLog(), new RegExp(`SHA=${sha}`));
  } finally { box.cleanup(); }
});

test('start-bridge：有服务且桥健康时不打扰（exit 0，不发 sv 命令）', async () => {
  const box = sandbox({ service: true, failFirst: 0 });
  try {
    const r = await box.run(START_BRIDGE, BASH);
    assert.equal(r.status, 0, r.out);
    assert.deepEqual(box.svCalls(), []);
    assert.equal(box.startLog(), '');
  } finally { box.cleanup(); }
});

test('start-bridge：有服务但桥不健康 → sv up，等到健康后 exit 0', async () => {
  const box = sandbox({ service: true, failFirst: 1 });
  try {
    const r = await box.run(START_BRIDGE, BASH);
    assert.equal(r.status, 0, r.out);
    assert.ok(box.svCalls().some(c => /^up pi-pocket$/.test(c)), box.svCalls().join('|'));
  } finally { box.cleanup(); }
});

test('start-bridge：FORCE=1 → sv restart（安装/更新后必须真换进程）', async () => {
  const box = sandbox({ service: true, failFirst: 0 });
  try {
    const r = await box.run(START_BRIDGE, BASH, { FORCE: '1' });
    assert.equal(r.status, 0, r.out);
    assert.ok(box.svCalls().some(c => /^restart pi-pocket$/.test(c)), box.svCalls().join('|'));
  } finally { box.cleanup(); }
});

test('start-bridge：`sv-disable`（写了 down 标记）时不得偷偷拉回，FORCE=1 则清标记后重启', async () => {
  const box = sandbox({ service: true, failFirst: 0, down: true });
  const marker = path.join(box.svdir, 'pi-pocket', 'down');
  try {
    const r = await box.run(START_BRIDGE, BASH);
    assert.equal(r.status, 0, r.out);
    assert.deepEqual(box.svCalls(), []);
    assert.equal(box.startLog(), '');
    assert.equal(existsSync(marker), true, '非 FORCE 不该改停用状态');
    // FORCE=1 是安装/更新/显式重启：清掉 down 标记（否则 runsv 会停在 “want down”）
    const forced = await box.run(START_BRIDGE, BASH, { FORCE: '1' });
    assert.equal(forced.status, 0, forced.out);
    assert.ok(box.svCalls().some(c => /^restart pi-pocket$/.test(c)), box.svCalls().join('|'));
    assert.equal(existsSync(marker), false, 'FORCE=1 后不该留 down 标记');
  } finally { box.cleanup(); }
});

test('watchdog：`sv-disable` 停用时直接退出（不探、不拉、不写日志）', async () => {
  const box = sandbox({ service: true, failFirst: 0, down: true });
  try {
    const r = await box.run(WATCHDOG, BASH);
    assert.equal(r.status, 0, r.out);
    assert.deepEqual(box.svCalls(), []);
    assert.equal(box.startLog(), '');
    assert.equal(existsSync(path.join(box.home, '.pi', 'agent', 'pi-pocket-watchdog.log')), false);
  } finally { box.cleanup(); }
});

test('start-bridge：没有 runsvdir 时退回直起 pi-pocket-start', async () => {
  const box = sandbox({ service: false });
  try {
    const r = await box.run(START_BRIDGE, BASH);
    assert.equal(r.status, 0, r.out);
    assert.deepEqual(box.svCalls(), []);
    assert.match(await box.waitStarted(), /FORCE=unset/, '无 FORCE 时不该无条件重启');
  } finally { box.cleanup(); }
});
