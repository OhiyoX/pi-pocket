import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
const exec = promisify(execFile);
const repo = fileURLToPath(new URL('..', import.meta.url));
const ref = execFileSync('git', ['-C', repo, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const script = fileURLToPath(new URL('../termux/deploy-runtime.mjs', import.meta.url));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function identity(pid) {
  try {
    const raw = await fs.readFile(`/proc/${pid}/stat`, 'utf8');
    const fields = raw.slice(raw.lastIndexOf(')') + 2).split(' ');
    return fields[0] === 'Z' ? null : fields[19];
  } catch { return null; }
}

for (const rollback of [false, true]) test(`isolated deployment ${rollback ? 'rolls back a refused bind' : 'switches a full runtime and preserves session'}`,
  { skip: process.platform !== 'linux', timeout: 30000 }, async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'pi-pocket-deploy-'));
  const entry = path.join(dir, 'bin/pi-pocket-bridge');
  const agent = path.join(dir, 'agent');
  await fs.mkdir(path.dirname(entry), { recursive: true });
  await fs.mkdir(agent);
  const session = path.join(dir, 'fixture.jsonl');
  await fs.writeFile(session, JSON.stringify({ type: 'session', cwd: dir }) + '\n');
  const listener = net.createServer();
  listener.listen(0, '127.0.0.1');
  await new Promise(resolve => listener.once('listening', resolve));
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  const fixture = `import http from 'node:http'; import {spawn} from 'node:child_process';
const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)']);
const startedAt=new Date().toISOString();
const server=http.createServer((req,res)=>res.end(JSON.stringify({ok:true,data:{pi:'fixture',pid:process.pid,fixtureChild:child.pid,
activeFile:${JSON.stringify(session)},startedAt,auth:false,workers:1,bind:'127.0.0.1:${port}'}})));
server.listen(${port},'127.0.0.1');
process.on('SIGTERM',()=>{child.kill();server.close(()=>process.exit(0));});`;
  await fs.writeFile(entry, fixture, { mode: 0o755 });
  const old = spawn(process.execPath, [entry], { cwd: dir, env: { ...process.env,
    PI_POCKET_PORT: String(port), PI_POCKET_HOST: rollback ? '0.0.0.0' : '127.0.0.1', PI_POCKET_TOKEN: '',
    PI_CODING_AGENT_DIR: agent, PI_POCKET_CWD: dir, PI_POCKET_STATE: path.join(agent, 'state.json'),
    PI_POCKET_SESSIONS: path.join(dir, 'sessions'), PI_POCKET_APP_PROFILE: '0', PI_POCKET_IDLE_MS: '0',
    PI_POCKET_BACKUP_ROOT: path.join(dir, 'backups'), PI_POCKET_PI: path.join(dir, 'missing-pi'),
  }, stdio: 'ignore' });
  let currentPid = old.pid;
  let fixtureChild;
  const targets = new Map();
  const health = async () => (await (await fetch(`http://127.0.0.1:${port}/v1/health`, { signal: AbortSignal.timeout(1000) })).json()).data;
  try {
    let before;
    for (let i = 0; i < 30; i++) { try { before = await health(); break; } catch { await delay(50); } }
    assert.equal(before.pid, old.pid);
    fixtureChild = before.fixtureChild;
    targets.set(old.pid, await identity(old.pid));
    targets.set(fixtureChild, await identity(fixtureChild));
    const command = [script, '--pid', String(old.pid), '--entry', entry, '--repo', repo, '--ref', ref, '--apply'];
    let output;
    try {
      output = (await exec(process.execPath, command, { timeout: 20000 })).stdout;
      assert.equal(rollback, false, 'Refused candidate must report failure even after recovery');
    } catch (error) {
      if (!rollback) throw error;
      output = error.stdout;
      assert.match(output, /"rolledBack":true/);
    }
    const after = await health();
    currentPid = after.pid;
    targets.set(currentPid, await identity(currentPid));
    if (after.fixtureChild) targets.set(after.fixtureChild, await identity(after.fixtureChild));
    assert.notEqual(currentPid, old.pid);
    assert.equal(after.activeFile, session);
    const lines = output.trim().split('\n').map(line => JSON.parse(line));
    assert.ok(lines.some(item => item.stoppedRoot === old.pid && item.remaining === 0 && item.inspected === 2));
    if (rollback) {
      assert.equal(after.pi, 'fixture');
      await assert.rejects(fs.stat(path.join(agent, 'state.json')), /ENOENT/);
    } else {
      assert.equal(after.sourceSha, ref);
      assert.equal(await fs.realpath(entry), path.join(agent, 'pi-pocket-runtime', ref, 'pi-pocket-bridge.mjs'));
      assert.equal(JSON.parse(await fs.readFile(path.join(agent, 'state.json'), 'utf8')).activeFile, session);
    }
    await assert.rejects(fs.stat(path.join(agent, 'pi-pocket-runtime/deploy.lock')), /ENOENT/);
  } finally {
    // The only targets are identities returned by our own isolated fixture/deployer.
    try {
      const h = await health();
      for (const id of [h.pid, h.fixtureChild]) if (id) targets.set(id, await identity(id));
    } catch { /* no listener */ }
    const live = async () => {
      const result = [];
      for (const [id, start] of targets) if (start && await identity(id) === start) result.push(id);
      return result;
    };
    for (const id of await live()) { try { process.kill(id, 'SIGTERM'); } catch { /* concurrent exit */ } }
    for (let i = 0; i < 40 && (await live()).length; i++) await delay(50);
    for (const id of await live()) { try { process.kill(id, 'SIGKILL'); } catch { /* concurrent exit */ } }
    for (let i = 0; i < 40 && (await live()).length; i++) await delay(50);
    assert.deepEqual(await live(), [], `Keep live process workspace: ${dir}`);
    await fs.rm(dir, { recursive: true, force: true });
  }
});
