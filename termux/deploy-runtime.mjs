#!/usr/bin/env node
// Project-owned bridge deployment. Preview by default; apply requires an exact PID.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';

const args = process.argv.slice(2);
const arg = name => args[args.indexOf(name) + 1];
const pid = Number(arg('--pid'));
const entry = arg('--entry');
const repo = arg('--repo');
const ref = arg('--ref');
const apply = args.includes('--apply');
if (!Number.isInteger(pid) || pid < 2 || !entry?.startsWith('/') || !repo?.startsWith('/') || !/^[a-f0-9]{40}$/.test(ref || '')) {
  throw new Error('Usage: node deploy-runtime.mjs --pid PID --entry /absolute/bin/pi-pocket-bridge --repo /absolute/repo --ref FULL_SHA [--apply]');
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function proc(id) {
  try {
    const raw = fs.readFileSync(`/proc/${id}/stat`, 'utf8');
    const fields = raw.slice(raw.lastIndexOf(')') + 2).split(' ');
    return { pid: id, state: fields[0], parent: Number(fields[1]), start: fields[19] };
  } catch { return null; }
}
const alive = item => {
  const now = proc(item.pid);
  return now?.start === item.start && now.state !== 'Z';
};
function signal(item, name) {
  if (!alive(item)) return false;
  try { process.kill(item.pid, name); return true; }
  catch (error) { if (error.code === 'ESRCH') return false; throw error; }
}
function descendants(root) {
  const all = fs.readdirSync('/proc').filter(s => /^\d+$/.test(s)).map(s => proc(Number(s))).filter(Boolean);
  const found = new Map([[root.pid, root]]);
  let changed;
  do {
    changed = false;
    for (const p of all) if (found.has(p.parent) && !found.has(p.pid)) { found.set(p.pid, p); changed = true; }
  } while (changed);
  return found;
}
async function stopTree(root, onRootTerm = () => {}, onSnapshot = () => {}) {
  // Freeze the exact tree before reparenting can hide descendants. All identities
  // include /proc starttime so recycled PIDs can never be signaled.
  const targets = new Map([[root.pid, root]]);
  const frozen = new Set();
  try {
    for (let pass = 0; pass < 20; pass++) {
      for (const p of targets.values()) if (alive(p)) {
        signal(p, 'SIGSTOP');
        frozen.add(p.pid);
      }
      await sleep(10);
      const next = descendants(root);
      let added = false;
      for (const [id, p] of next) if (!targets.has(id)) { targets.set(id, p); added = true; }
      if (!added && [...targets.values()].every(p => !alive(p) || proc(p.pid)?.state === 'T')) break;
      if (pass === 19) throw new Error('Target tree did not stabilize');
    }
    onSnapshot(targets);
    // Resume only with termination already pending, descendants before root.
    for (const p of [...targets.values()].reverse()) if (alive(p)) {
      if (signal(p, 'SIGTERM') && p.pid === root.pid) onRootTerm();
      signal(p, 'SIGCONT');
      frozen.delete(p.pid);
    }
  } finally {
    for (const id of frozen) {
      const p = targets.get(id);
      if (p && alive(p)) { try { signal(p, 'SIGCONT'); } catch { /* report through original failure */ } }
    }
  }
  for (let i = 0; i < 50 && [...targets.values()].some(alive); i++) await sleep(100);
  for (const p of targets.values()) if (alive(p)) signal(p, 'SIGKILL');
  for (let i = 0; i < 20 && [...targets.values()].some(alive); i++) await sleep(100);
  const remaining = [...targets.values()].filter(alive).map(p => p.pid);
  if (remaining.length) throw new Error(`Target tree still alive: ${remaining.join(',')}`);
  console.log(JSON.stringify({ stoppedRoot: root.pid, inspected: targets.size, remaining: 0 }));
  return targets;
}

const root = proc(pid);
if (!root || !alive(root)) throw new Error('Target PID is not alive');
const argv = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0').filter(Boolean);
if (argv[1] !== entry) throw new Error('PID command does not exactly match the requested entry');
const exe = fs.readlinkSync(`/proc/${pid}/exe`);
const cwd = fs.readlinkSync(`/proc/${pid}/cwd`);
// Secrets stay in memory, never serialized, printed or put on the command line.
const env = Object.fromEntries(fs.readFileSync(`/proc/${pid}/environ`, 'utf8').split('\0').filter(Boolean).map(s => {
  const index = s.indexOf('='); return [s.slice(0, index), s.slice(index + 1)];
}));
const oldEnv = { ...env };
const agent = env.PI_CODING_AGENT_DIR || path.join(os.homedir(), '.pi/agent');
// Node resolves the entry symlink before evaluating import.meta.url. Pin the old
// effective profile roots before changing that realpath; preserve selection/device state.
const oldDir = path.dirname(fs.realpathSync(entry));
env.PI_POCKET_APP_PROFILE_DIR ||= fs.existsSync(path.join(oldDir, 'app-profile'))
  ? path.join(oldDir, 'app-profile') : path.join(agent, 'pi-pocket-app-profile');
env.PI_POCKET_SKILLS ||= fs.existsSync(path.join(oldDir, 'skills'))
  ? path.join(oldDir, 'skills') : path.join(agent, 'pi-pocket-app-profile/skills');
env.PI_POCKET_APP_PROMPT ||= path.join(env.PI_POCKET_APP_PROFILE_DIR, 'append-system.md');
env.PI_POCKET_APP_EXTENSION ||= path.join(env.PI_POCKET_APP_PROFILE_DIR, 'extension.mjs');
env.PI_POCKET_MCP_PRESETS ||= path.join(env.PI_POCKET_APP_PROFILE_DIR, 'mcp-presets.json');
env.PI_POCKET_APP_SELECTION ||= path.join(env.PI_POCKET_APP_PROFILE_DIR, 'selection.json');
env.PI_POCKET_SOURCE_SHA = ref;
const port = Number(env.PI_POCKET_PORT || 8789);
const base = `http://127.0.0.1:${port}`;
const headers = env.PI_POCKET_TOKEN ? { authorization: `Bearer ${env.PI_POCKET_TOKEN}` } : {};
async function health() {
  const response = await fetch(base + '/v1/health', { headers, signal: AbortSignal.timeout(2500) });
  if (!response.ok) throw new Error(`health HTTP ${response.status}`);
  const body = await response.json();
  if (!body.ok || !body.data?.pi) throw new Error('Not a bridge health response');
  return body.data;
}
let before = await health();
const beforeBytes = fs.readFileSync(entry);
const beforeHash = sha(beforeBytes);
const files = ['pi-pocket-bridge.mjs', 'tree-from-file.mjs', 'rpc-channel.mjs', 'event-stream.mjs', 'notify-payload.mjs',
  'tool-render/index.mjs', 'tool-render/adapters/web-access.mjs', 'tool-render/adapters/unified-exec.mjs'];
const bundle = files.map(file => {
  const bytes = execFileSync('git', ['-C', repo, 'show', `${ref}:termux/${file}`], { maxBuffer: 8 << 20 });
  return { file, bytes, sourceSha256: sha(bytes) };
});
console.log(JSON.stringify({ mode: apply ? 'apply' : 'preview', pid, ref, entry, beforeSha256: beforeHash,
  descendants: descendants(root).size - 1, bind: before.bind, auth: before.auth, workers: before.workers, files: files.length }));
if (!apply) process.exit(0);

const runtime = path.join(agent, 'pi-pocket-runtime');
const oldLink = fs.lstatSync(entry).isSymbolicLink() ? fs.realpathSync(entry) : null;
fs.mkdirSync(runtime, { recursive: true, mode: 0o700 });
const lock = path.join(runtime, 'deploy.lock');
fs.mkdirSync(lock); // Atomic project/host lock. Never steal an existing deployment lock.
let newRoot;
let switched = false;
let stopped = false;
let oldTargets = new Map([[root.pid, root]]);
let backup;
let release;
const stateFile = env.PI_POCKET_STATE || path.join(agent, 'pi-pocket-state.json');
let stateBefore;
let stateWritten = false;
const writeState = bytes => {
  const next = stateFile + `.next-${process.pid}`;
  try {
    fs.writeFileSync(next, bytes, { mode: 0o600 });
    fs.renameSync(next, stateFile);
  } finally { if (fs.existsSync(next)) fs.unlinkSync(next); }
};
const replaceEntry = target => {
  const next = entry + `.next-${process.pid}`;
  try { fs.symlinkSync(target, next); fs.renameSync(next, entry); }
  finally { if (fs.existsSync(next)) fs.unlinkSync(next); }
};
const restoreEntry = () => {
  if (oldLink) return replaceEntry(oldLink);
  if (!fs.lstatSync(entry).isSymbolicLink() && sha(fs.readFileSync(entry)) === beforeHash) return;
  const next = entry + `.next-${process.pid}`;
  try {
    fs.copyFileSync(path.join(backup, 'pi-pocket-bridge'), next);
    fs.chmodSync(next, fs.statSync(path.join(backup, 'pi-pocket-bridge')).mode & 0o777);
    fs.renameSync(next, entry);
  } finally { if (fs.existsSync(next)) fs.unlinkSync(next); }
};
const launch = (launchEnv = env) => {
  const log = fs.openSync(path.join(runtime, 'runtime.log'), 'a', 0o600);
  try {
    const child = spawn(exe, [entry, ...argv.slice(2)], { cwd, env: launchEnv, detached: true, stdio: ['ignore', log, log] });
    child.on('error', () => {});
    child.unref();
    return child;
  } finally { fs.closeSync(log); }
};
async function awaitStarted(child, oldStartedAt, requireIdentity = true) {
  for (let i = 0; i < 40; i++) {
    if (child.exitCode != null || child.signalCode != null) throw new Error('New bridge exited');
    try {
      const h = await health();
      const running = proc(child.pid);
      if (h.startedAt !== oldStartedAt && running && alive(running) &&
          (!requireIdentity || (h.pid === child.pid && h.sourceSha === ref))) return h;
    } catch { /* bounded startup wait */ }
    await sleep(250);
  }
  throw new Error('New bridge did not become healthy');
}
try {
  // Verify identity and deployment drift again under the lock before any mutation.
  if (!alive(root) || sha(fs.readFileSync(entry)) !== beforeHash) throw new Error('Deployment drift');
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  backup = path.join(env.PI_POCKET_BACKUP_ROOT || path.join(os.homedir(), 'backups'), `pi-pocket-${stamp}`);
  fs.mkdirSync(backup, { recursive: true, mode: 0o700 });
  fs.copyFileSync(entry, path.join(backup, 'pi-pocket-bridge'));
  fs.chmodSync(path.join(backup, 'pi-pocket-bridge'), fs.statSync(entry).mode & 0o777);
  const manifest = { ref, oldPid: pid, entry, beforeSha256: beforeHash, files: [] };
  for (const file of files.slice(1)) {
    const old = path.join(oldDir, file);
    if (!fs.existsSync(old)) continue;
    const output = path.join(backup, file);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.copyFileSync(old, output);
    manifest.files.push({ file, sha256: sha(fs.readFileSync(output)) });
  }
  fs.writeFileSync(path.join(backup, 'MANIFEST.json'), JSON.stringify(manifest, null, 2), { mode: 0o600 });
  fs.writeFileSync(path.join(backup, 'SHA256.txt'), [beforeHash + '  pi-pocket-bridge', ...manifest.files.map(f => f.sha256 + '  ' + f.file)].join('\n') + '\n');

  release = path.join(runtime, ref);
  if (fs.existsSync(release)) throw new Error('Immutable runtime version already exists');
  fs.mkdirSync(release, { mode: 0o700 });
  for (const item of bundle) {
    const output = path.join(release, item.file);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    let bytes = item.bytes;
    if (item.file === 'pi-pocket-bridge.mjs' && env.PREFIX) {
      bytes = Buffer.from(bytes.toString().replace(/^#![^\n]+/, `#!${env.PREFIX}/bin/env node`));
    }
    fs.writeFileSync(output, bytes, { mode: item.file === 'pi-pocket-bridge.mjs' ? 0o755 : 0o644 });
    item.deployedSha256 = sha(bytes);
  }
  fs.writeFileSync(path.join(release, 'MANIFEST.json'), JSON.stringify({ ref, files: bundle.map(({ bytes, ...m }) => m) }, null, 2));
  execFileSync(exe, ['--check', path.join(release, 'pi-pocket-bridge.mjs')]);
  const latest = await health();
  if (latest.startedAt !== before.startedAt || !alive(root)) throw new Error('Runtime identity drift');
  before = latest;
  stateBefore = fs.existsSync(stateFile) ? fs.readFileSync(stateFile) : null;
  const stoppedTargets = await stopTree(root, () => { stopped = true; }, targets => { oldTargets = targets; });
  stopped = true;
  // Preserve current session for legacy bridges without an on-disk pointer.
  if (before.activeFile && fs.existsSync(before.activeFile)) {
    writeState(JSON.stringify({ activeFile: before.activeFile, updatedAt: new Date().toISOString() }));
    stateWritten = true;
  }
  replaceEntry(path.join(release, 'pi-pocket-bridge.mjs'));
  switched = true;
  const child = launch();
  await sleep(50);
  newRoot = proc(child.pid);
  const after = await awaitStarted(child, before.startedAt);
  if (before.activeFile && after.activeFile !== before.activeFile) throw new Error('Active session was not preserved');
  const report = { ref, oldPid: pid, newPid: child.pid, backup, release, auth: after.auth, bind: after.bind,
    sessionPreserved: !before.activeFile || after.activeFile === before.activeFile, startedAt: after.startedAt,
    entrySha256: sha(fs.readFileSync(entry)), oldTreeRemaining: [...stoppedTargets.values()].filter(alive).length };
  fs.writeFileSync(path.join(release, 'DEPLOYED.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} catch (error) {
  if (stopped || switched) {
    if (newRoot && alive(newRoot)) await stopTree(newRoot);
    for (const p of oldTargets.values()) if (alive(p)) await stopTree(p);
    restoreEntry();
    // A rollback entry resolves relative imports beside its complete backup.
    if (stateWritten) {
      if (stateBefore) writeState(stateBefore);
      else fs.rmSync(stateFile, { force: true });
    }
    const restored = launch(oldEnv);
    await awaitStarted(restored, before.startedAt, false);
    console.log(JSON.stringify({ rolledBack: true, backup, newPid: restored.pid }));
  }
  throw error;
} finally { fs.rmdirSync(lock); }
