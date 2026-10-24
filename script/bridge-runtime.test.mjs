import test from 'node:test';
import assert from 'node:assert/strict';
import { RpcChannel } from '../termux/rpc-channel.mjs';
import { OrderedEvents, writeEvent } from '../termux/event-stream.mjs';
import http from 'node:http';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';

test('correlation preserves shell IDs, rejects collisions and handles out-of-order replies', async () => {
  const sent = [];
  const channel = new RpcChannel(line => sent.push(JSON.parse(line)));
  const a = channel.request('bash', { id: 'bash-123' });
  assert.equal(sent[0].id, 'bash-123');
  await assert.rejects(channel.request('bash', { id: 'bash-123' }), /duplicate/);
  const b = channel.request('get_messages');
  assert.notEqual(sent[0].id, sent[1].id);
  assert.equal(channel.accept({ type: 'response', id: sent[1].id, data: 2 }), true);
  channel.accept({ type: 'response', id: sent[0].id, data: 1 });
  assert.deepEqual((await Promise.all([a, b])).map(r => r.data), [1, 2]);
  assert.equal(channel.pending.size, 0);
});

test('spawn failure/exit rejects pending immediately and clears all timers', async () => {
  const channel = new RpcChannel(() => {});
  const waiting = channel.request('prompt', {}, 900000);
  const rejected = assert.rejects(waiting, /spawn failed/);
  channel.close(new Error('spawn failed'));
  await rejected;
  assert.equal(channel.pending.size, 0);
  await assert.rejects(channel.request('get_state'), /closed/);
});

test('sync and async broken pipes release requests', async () => {
  const sync = new RpcChannel(() => { throw new Error('EPIPE'); });
  await assert.rejects(sync.request('prompt'), /EPIPE/);
  assert.equal(sync.pending.size, 0);
  const async = new RpcChannel((line, callback) => queueMicrotask(() => callback(new Error('EPIPE'))));
  await assert.rejects(async.request('prompt'), /EPIPE/);
  assert.equal(async.pending.size, 0);
});

test('timeout drops correlation and late reply does not resolve another request', async () => {
  const channel = new RpcChannel(() => {});
  await assert.rejects(channel.request('get_state', {}, 5), /timeout/);
  assert.equal(channel.pending.size, 0);
  assert.equal(channel.accept({ type: 'response', id: 'r1' }), false);
});

test('slow render cannot overtake end/settled and captures original session', async () => {
  const emitted = [];
  const events = new OrderedEvents(record => emitted.push(record));
  let release;
  const render = new Promise(resolve => { release = resolve; });
  events.enqueue({ type: 'tool_execution_start' }, 'A', () => render);
  events.enqueue({ type: 'tool_execution_end' }, 'A');
  const done = events.enqueue({ type: 'agent_settled' }, 'B');
  await Promise.resolve();
  assert.equal(emitted.length, 0);
  release({ display: 'bash' });
  await done;
  assert.deepEqual(emitted.map(e => e.record.type), ['tool_execution_start', 'tool_execution_end', 'agent_settled']);
  assert.deepEqual(emitted.map(e => e.sessionFile), ['A', 'A', 'B']);
});

test('renderer failure falls back to raw event and subsequent records still flow', async () => {
  const emitted = [];
  const events = new OrderedEvents(r => emitted.push(r));
  events.enqueue({ type: 'tool_execution_end' }, 'A', () => { throw new Error('bad renderer'); });
  await events.enqueue({ type: 'agent_settled' }, 'A');
  assert.equal(emitted.length, 2);
});

test('a stuck renderer has a bounded fallback instead of blocking all later events', async () => {
  const emitted = [];
  const events = new OrderedEvents(r => emitted.push(r), 5);
  events.enqueue({ type: 'tool_execution_end' }, 'A', () => new Promise(() => {}));
  await events.enqueue({ type: 'agent_settled' }, 'A');
  assert.equal(emitted.length, 2);
});

test('slow or broken SSE clients are disconnected; writable clients continue', () => {
  let destroyed = false;
  assert.equal(writeEvent({ writableLength: 65 * 1024 * 1024, write: () => false, destroy: () => { destroyed = true; } }, 'data'), false);
  assert.equal(destroyed, true);
  assert.equal(writeEvent({ writableLength: 0, write: () => true }, 'data'), true);
  assert.equal(writeEvent({ writableLength: 128 * 1024, write: () => false }, 'large event'), true);
  assert.equal(writeEvent({ destroyed: true }, 'data'), false);
});

test('healthy HTTP consumer receives a large SSE event despite write(false)', async () => {
  const payload = 'data: ' + 'x'.repeat(262144) + '\n\n';
  const server = http.createServer((req, res) => {
    res.setHeader('content-type', 'text/event-stream');
    assert.equal(writeEvent(res, payload), true);
    res.end();
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}`);
    assert.equal(await response.text(), payload);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('real bridge isolates auth and promptly rejects missing pi executable', { timeout: 15000 }, async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pi-pocket-runtime-'));
  const child = spawn(process.execPath, [fileURLToPath(new URL('../termux/pi-pocket-bridge.mjs', import.meta.url))], {
    cwd: directory,
    env: { ...process.env, PI_POCKET_HOST: '127.0.0.1', PI_POCKET_PORT: '0',
      PI_POCKET_TOKEN: 'runtime-test', PI_POCKET_APP_PROFILE: '0', PI_CODING_AGENT_DIR: path.join(directory, 'agent'),
      PI_POCKET_SESSIONS: path.join(directory, 'sessions'), PI_POCKET_STATE: path.join(directory, 'state.json'),
      PI_POCKET_CWD: directory, PI_POCKET_PI: path.join(directory, 'missing-pi'), PI_POCKET_IDLE_MS: '0' },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  try {
    const base = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('bridge start timeout')), 6000);
      child.on('exit', () => { clearTimeout(timer); reject(new Error('bridge exited before ready')); });
      child.stderr.on('data', data => {
        const match = String(data).match(/listening on (http:\/\/127\.0\.0\.1:\d+)/);
        if (match) { clearTimeout(timer); resolve(match[1]); }
      });
    });
    assert.equal((await fetch(base + '/v1/health')).status, 401);
    const headers = { authorization: 'Bearer runtime-test' };
    const health = await fetch(base + '/v1/health', { headers });
    assert.equal(health.status, 200);
    assert.equal((await health.json()).data.pid, child.pid);
    const result = await fetch(base + '/v1/info', { headers, signal: AbortSignal.timeout(4000) });
    assert.equal(result.status, 500);
    assert.match((await result.json()).error, /ENOENT|closed/);
  } finally {
    const exited = once(child, 'exit');
    child.kill();
    await exited;
    await fs.rm(directory, { recursive: true, force: true });
  }
});
