// 启动器行为测试：env 持久化矩阵 + bind/auth 不匹配时重启。
// 每个用例用独立 HOME；桥本体用假脚本代替，不会启动真桥。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import net from 'node:net';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const START = path.join(root, 'termux', 'pi-pocket-start');
const SH = process.platform === 'win32' ? 'sh' : '/bin/sh';
const PORT = '18999';
// Git Bash 里 HOME 等必须是 POSIX 路径
const shPath = p => process.platform === 'win32'
  ? p.replace(/\\/g, '/').replace(/^([A-Za-z]):/, (_, d) => '/' + d.toLowerCase())
  : p;

function sandbox() {
  const home = mkdtempSync(path.join(tmpdir(), 'pipocket-start-'));
  const bin = path.join(home, 'fake-bridge');
  const dumpFile = path.join(home, 'bridge-env.txt');
  writeFileSync(bin, '#!/bin/sh\nprintf "token=%s\\nhost=%s\\n" "$PI_POCKET_TOKEN" "$PI_POCKET_HOST" > "$PI_POCKET_DUMP"\n');
  chmodSync(bin, 0o755);
  return {
    home,
    envFile: path.join(home, '.pi', 'agent', 'pi-pocket.env'),
    logFile: path.join(home, '.pi', 'agent', 'pi-pocket-start.log'),
    dumpFile,
    // 必须是异步 spawn：spawnSync 会阻塞本进程事件循环，同进程的假健康服务器无法应答
    run(extra = {}) {
      return new Promise(resolve => {
        const child = spawn(SH, [START], {
          env: {
            PATH: process.env.PATH,
            HOME: shPath(home),
            PI_POCKET_BIN: shPath(bin),
            PI_POCKET_DUMP: shPath(dumpFile),
            PI_POCKET_CWD: shPath(home),
            PI_POCKET_PORT: PORT,
            ...extra,
          },
        });
        let stdout = '';
        let stderr = '';
        child.stdout.on('data', chunk => { stdout += chunk; });
        child.stderr.on('data', chunk => { stderr += chunk; });
        child.on('close', status => resolve({ status, stdout, stderr }));
      });
    },
    dump() {
      if (!existsSync(dumpFile)) return null;
      const out = {};
      for (const line of readFileSync(dumpFile, 'utf8').split('\n')) {
        const i = line.indexOf('=');
        if (i > 0) out[line.slice(0, i)] = line.slice(i + 1);
      }
      return out;
    },
    log() {
      return existsSync(this.logFile) ? readFileSync(this.logFile, 'utf8') : '';
    },
    cleanup() {
      rmSync(home, { recursive: true, force: true });
    },
  };
}

test('无 env 且无文件：不创建 env 文件，桥以回环启动', async () => {
  const box = sandbox();
  try {
    const r = await box.run();
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.equal(existsSync(box.envFile), false);
    const d = box.dump();
    assert.equal(d.token, '');
    assert.equal(d.host, '127.0.0.1');
    assert.match(box.log(), /没有 token/);
  } finally {
    box.cleanup();
  }
});

test('只传 token：写入 token，host 默认回环', async () => {
  const box = sandbox();
  try {
    const r = await box.run({ PI_POCKET_TOKEN: 'abc123' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(readFileSync(box.envFile, 'utf8'), /^PI_POCKET_TOKEN=abc123$/m);
    assert.equal(box.dump().host, '127.0.0.1');
  } finally {
    box.cleanup();
  }
});

test('只传 host：保留已有 token，并持久化 host', async () => {
  const box = sandbox();
  try {
    mkdirSync(path.dirname(box.envFile), { recursive: true });
    writeFileSync(box.envFile, 'PI_POCKET_TOKEN=oldtoken\n');
    const r = await box.run({ PI_POCKET_HOST: '0.0.0.0' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const text = readFileSync(box.envFile, 'utf8');
    assert.match(text, /^PI_POCKET_TOKEN=oldtoken$/m);
    assert.match(text, /^PI_POCKET_HOST=0\.0\.0\.0$/m);
    const d = box.dump();
    assert.equal(d.token, 'oldtoken');
    assert.equal(d.host, '0.0.0.0');
  } finally {
    box.cleanup();
  }
});

test('更新 token：保留 host，其它键不丢', async () => {
  const box = sandbox();
  try {
    mkdirSync(path.dirname(box.envFile), { recursive: true });
    writeFileSync(box.envFile, 'PI_POCKET_TOKEN=old\nPI_POCKET_HOST=0.0.0.0\nPI_POCKET_IDLE_MS=123456\n');
    const r = await box.run({ PI_POCKET_TOKEN: 'newtoken' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const text = readFileSync(box.envFile, 'utf8');
    assert.match(text, /^PI_POCKET_TOKEN=newtoken$/m);
    assert.match(text, /^PI_POCKET_HOST=0\.0\.0\.0$/m);
    assert.match(text, /^PI_POCKET_IDLE_MS=123456$/m);
  } finally {
    box.cleanup();
  }
});

test('什么都不传：从 env 文件读取 token/host', async () => {
  const box = sandbox();
  try {
    mkdirSync(path.dirname(box.envFile), { recursive: true });
    writeFileSync(box.envFile, 'PI_POCKET_TOKEN=filedtoken\nPI_POCKET_HOST=0.0.0.0\n');
    const r = await box.run();
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const d = box.dump();
    assert.equal(d.token, 'filedtoken');
    assert.equal(d.host, '0.0.0.0');
  } finally {
    box.cleanup();
  }
});

test('非法 token 被拒绝：不写文件、不启动桥', async () => {
  const box = sandbox();
  try {
    const r = await box.run({ PI_POCKET_TOKEN: 'tok en' });
    assert.equal(r.status, 2);
    assert.equal(existsSync(box.dumpFile), false);
    assert.equal(existsSync(box.envFile), false);
  } finally {
    box.cleanup();
  }
});

test('非 loopback 绑定但没有 token：拒绝启动', async () => {
  const box = sandbox();
  try {
    const r = await box.run({ PI_POCKET_HOST: '0.0.0.0' });
    assert.equal(r.status, 2);
    assert.equal(existsSync(box.dumpFile), false);
  } finally {
    box.cleanup();
  }
});

test('写 env 前留 .prev 备份', async () => {
  const box = sandbox();
  try {
    mkdirSync(path.dirname(box.envFile), { recursive: true });
    writeFileSync(box.envFile, 'PI_POCKET_TOKEN=old\nPI_POCKET_HOST=127.0.0.1\n');
    const r = await box.run({ PI_POCKET_TOKEN: 'newtoken' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.equal(readFileSync(box.envFile + '.prev', 'utf8'), 'PI_POCKET_TOKEN=old\nPI_POCKET_HOST=127.0.0.1\n');
    assert.match(box.log(), /旧 env 备份/);
  } finally {
    box.cleanup();
  }
});

test('不可绑定的 host：预检拒绝，不写 env、不启动桥', async () => {
  const box = sandbox();
  try {
    const r = await box.run({ PI_POCKET_TOKEN: 'tok123', PI_POCKET_HOST: '10.9.9.9' });
    assert.equal(r.status, 2, r.stdout + r.stderr);
    assert.match(box.log(), /无法绑定 10\.9\.9\.9:/);
    assert.equal(existsSync(box.envFile), false);
    assert.equal(existsSync(box.dumpFile), false);
  } finally {
    box.cleanup();
  }
});

test('不可绑定的 host：不动健康桥，已有 env 不变', { skip: process.platform === 'win32' }, async () => {
  const box = sandbox();
  const live = await fakeBridge(pid => ({ pid, bind: `127.0.0.1:${PORT}`, auth: false }));
  try {
    mkdirSync(path.dirname(box.envFile), { recursive: true });
    const before = 'PI_POCKET_TOKEN=oldtoken\nPI_POCKET_HOST=127.0.0.1\n';
    writeFileSync(box.envFile, before);
    const r = await box.run({ PI_POCKET_HOST: '10.9.9.9' });
    assert.equal(r.status, 2, r.stdout + r.stderr);
    assert.equal(live.proc.exitCode, null);
    assert.equal(live.proc.signalCode, null);
    assert.equal(readFileSync(box.envFile, 'utf8'), before);
    assert.equal(existsSync(box.envFile + '.prev'), false);
  } finally {
    await live.close();
    box.cleanup();
  }
});

test('env 文件权限为 600', { skip: process.platform === 'win32' }, async () => {
  const box = sandbox();
  try {
    const r = await box.run({ PI_POCKET_TOKEN: 'abc123' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.equal(statSync(box.envFile).mode & 0o777, 0o600);
  } finally {
    box.cleanup();
  }
});

async function fakeBridge(health) {
  const proc = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', 'pi-pocket-bridge'], {
    stdio: 'ignore',
    // 桥的身份包含它自己的端口：启动器只停 PI_POCKET_PORT 等于自己 PORT 的桥
    env: { ...process.env, PI_POCKET_PORT: PORT },
  });
  const server = createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ ok: true, data: health(proc.pid) }));
  });
  await new Promise(resolve => server.listen(Number(PORT), '127.0.0.1', resolve));
  return {
    proc,
    async close() {
      await new Promise(resolve => server.close(resolve));
      if (proc.exitCode == null) proc.kill('SIGKILL');
    },
  };
}

async function hungBridge() {
  const proc = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', 'pi-pocket-bridge'], {
    stdio: 'ignore',
    env: { ...process.env, PI_POCKET_PORT: PORT },
  });
  const sockets = new Set();
  // 接受连接但永不应答：fetch 走 2s AbortSignal.timeout → probe=000，
  // 但端口确实被一个 pi-pocket 桥进程占着（正是 FORCE=1 要换掉的那种卡死桥）。
  const server = net.createServer(socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {});
  });
  await new Promise(resolve => server.listen(Number(PORT), '127.0.0.1', resolve));
  return {
    proc,
    async close() {
      for (const socket of sockets) socket.destroy();
      await new Promise(resolve => server.close(resolve));
      if (proc.exitCode == null) proc.kill('SIGKILL');
    },
  };
}

test('FORCE=1 对探不到的卡死桥也重启（probe=000 不是「没桥」）', { skip: process.platform === 'win32' }, async () => {
  const box = sandbox();
  const hung = await hungBridge();
  try {
    const r = await box.run({ FORCE: '1' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(box.log(), /FORCE=1 → 无条件重启（probe=000）/);
    assert.ok(hung.proc.exitCode !== null || hung.proc.signalCode !== null, '卡死桥应被停掉');
    assert.equal(box.dump().host, '127.0.0.1', '新桥应真的起来了');
  } finally {
    await hung.close();
    box.cleanup();
  }
});

test('FORCE=1 不碰别的端口上的桥（端口域内匹配）', { skip: process.platform === 'win32' }, async () => {
  // 2026-10-23 真实事故回归：host-a 上用 18999 跑测试，却把 8789 上的正式桥停了。
  const box = sandbox();
  const decoy = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', 'pi-pocket-bridge'], {
    stdio: 'ignore',
    env: { ...process.env, PI_POCKET_PORT: '18888' },
  });
  const hung = await hungBridge();
  try {
    const r = await box.run({ FORCE: '1' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.ok(hung.proc.exitCode !== null || hung.proc.signalCode !== null, '本端口的卡死桥应被停掉');
    assert.equal(decoy.exitCode, null, '别的端口的桥不得被动');
    assert.equal(decoy.signalCode, null, '别的端口的桥不得被动');
    assert.equal(box.dump().host, '127.0.0.1');
  } finally {
    await hung.close();
    decoy.kill('SIGKILL');
    box.cleanup();
  }
});

test('已有桥 bind/auth 不匹配：停掉旧进程并重启', { skip: process.platform === 'win32' }, async () => {
  const box = sandbox();
  const live = await fakeBridge(pid => ({ pid, bind: `127.0.0.1:${PORT}`, auth: false }));
  try {
    const r = await box.run({ PI_POCKET_HOST: '0.0.0.0', PI_POCKET_TOKEN: 'tok123' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(box.log(), /配置不匹配/);
    assert.equal(box.dump().host, '0.0.0.0');
    // 被信号终止时 exitCode 为 null，signalCode 才是 SIGTERM
    assert.ok(live.proc.exitCode !== null || live.proc.signalCode !== null);
  } finally {
    await live.close();
    box.cleanup();
  }
});

test('已有桥 bind/auth 匹配：不重启', { skip: process.platform === 'win32' }, async () => {
  const box = sandbox();
  const live = await fakeBridge(pid => ({ pid, bind: `0.0.0.0:${PORT}`, auth: true }));
  try {
    const r = await box.run({ PI_POCKET_HOST: '0.0.0.0', PI_POCKET_TOKEN: 'tok123' });
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(box.log(), /不重启/);
    assert.equal(existsSync(box.dumpFile), false);
    assert.equal(live.proc.exitCode, null);
  } finally {
    await live.close();
    box.cleanup();
  }
});

// Android 的 linker 比 glibc 严：0 字节的 preload 会让**所有**子进程 CANNOT LINK
// （2026-10-24 在 phone 实测），所以在 Termux 上用真的 libtermux-exec.so 复制一份；
// PC / CI 没有这个库，继续用空文件。
test('Termux compatibility preload is restored and not duplicated', {skip:process.platform==='win32'}, async () => {
  const box=sandbox();
  try {
    const lib=path.join(box.home,'lib','libtermux-exec.so');
    mkdirSync(path.dirname(lib));
    const real=path.join(process.env.PREFIX||'/data/data/com.termux/files/usr','lib','libtermux-exec.so');
    if(existsSync(real)) copyFileSync(real,lib); else writeFileSync(lib,'');
    const fake=path.join(box.home,'preload-bridge');
    writeFileSync(fake,'#!/bin/sh\nprintf "%s" "$LD_PRELOAD" > "$PI_POCKET_DUMP"\n'); chmodSync(fake,0o755);
    for(const inherited of ['',lib]) {
      const r=await box.run({PREFIX:box.home,LD_PRELOAD:inherited,PI_POCKET_BIN:fake});
      assert.equal(r.status,0,r.stderr);
      assert.equal(readFileSync(box.dumpFile,'utf8'),lib);
    }
  } finally {box.cleanup();}
});
