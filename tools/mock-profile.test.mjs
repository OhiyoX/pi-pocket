import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

async function fixture(error, run) {
  const child = spawn(process.execPath, ['tools/mock-bridge.mjs', '--port', '0', ...(error ? ['--profile-save-error'] : [])], { stdio: ['ignore', 'pipe', 'inherit'] });
  try {
    const port = await new Promise((resolve, reject) => {
      let output = '';
      const timeout = setTimeout(() => reject(new Error('mock startup timed out')), 10000);
      child.once('error', error => { clearTimeout(timeout); reject(error); });
      child.stdout.on('data', data => {
        output += data;
        const match = output.match(/http:\/\/0\.0\.0\.0:(\d+)/);
        if (match) { clearTimeout(timeout); resolve(Number(match[1])); }
      });
    });
    const request = async body => {
      const res = await fetch(`http://127.0.0.1:${port}/v1/app-profile`, body === undefined ? {} : { method: 'POST', body: JSON.stringify(body) });
      return { status: res.status, ...await res.json() };
    };
    await run(request);
  } finally {
    const exit = once(child, 'exit');
    child.kill(); // exact owned child PID; never process-name matching
    await exit;
  }
}

test('profile fixture persists individual toggles and reset', () => fixture(false, async request => {
  assert.equal((await request()).data.appendPrompt.enabled, true);
  const saved = await request({ appendPrompt: false, skills: { 'zcode-visualize': false }, mcp: { health: false } });
  assert.equal(saved.data.skills[0].enabled, false);
  assert.equal(saved.data.mcp[0].enabled, false);
  assert.equal((await request()).data.appendPrompt.enabled, false);
  const reset = await request({ reset: true });
  assert.equal(reset.data.appendPrompt.enabled, true);
  assert.equal(reset.data.skills[0].enabled, true);
}));

test('profile failure fixture preserves prior values', () => fixture(true, async request => {
  const failure = await request({ appendPrompt: false });
  assert.equal(failure.status, 500);
  assert.equal(failure.ok, false);
  assert.match(failure.error, /保存失败/);
  assert.equal((await request()).data.appendPrompt.enabled, true);
}));
