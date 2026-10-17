import assert from 'node:assert/strict';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { MINUTE, START, fakeClock, fakeFcm, pngBytes, setupFamily, startHub, tempDir } from './helpers.js';

const ask = (h, kidToken, number = '0871234567') =>
  h.kid('POST', '/kid/requests', { token: kidToken, body: { contactName: 'Pablo', number } });

test('tokens, apps, icons, versions and approval windows survive a restart', async (t) => {
  const dataDir = tempDir();
  const h1 = await startHub({ dataDir });
  const { kidToken, Miguel } = await setupFamily(h1, ['Miguel']);
  const icon = pngBytes(72, 72, 9);
  await h1.kid('PUT', '/kid/apps', {
    token: kidToken,
    body: { apps: [{ package: 'com.duolingo', label: 'Duolingo', iconPng: icon.toString('base64') }] },
  });
  await h1.parent('PUT', '/parent/apps/com.duolingo', { token: Miguel, body: { allowed: true } });
  const { id } = (await ask(h1, kidToken)).body;
  await h1.parent('POST', `/parent/requests/${id}/answer`, { token: Miguel, body: { minutes: 30 } });
  const hubId = (await h1.kid('GET', '/kid/ping', { token: kidToken })).body.hubId;
  const before = (await h1.kid('GET', '/kid/state', { token: kidToken })).body;
  await h1.stop();

  const h2 = await startHub({ dataDir, clock: fakeClock(START + 10 * MINUTE) });
  t.after(h2.stop);
  assert.equal((await h2.kid('GET', '/kid/ping', { token: kidToken })).body.hubId, hubId);
  const after = (await h2.kid('GET', '/kid/state', { token: kidToken })).body;
  assert.ok(after.version > before.version, 'the version only goes up, also across restarts');
  assert.deepEqual(after.allowedPackages, ['com.duolingo']);
  assert.deepEqual(after.requests, [{ id, number: '0871234567', status: 'approved', remainingSeconds: 1200, minutes: 30 }]);

  const parentView = (await h2.parent('GET', '/parent/state', { token: Miguel })).body;
  assert.equal(parentView.apps[0].hasIcon, true);
  assert.equal(parentView.requests[0].decidedBy, 'Miguel');
  assert.deepEqual((await h2.parent('GET', '/parent/apps/com.duolingo/icon', { token: Miguel })).body, icon);

  // The approval window's end was re-armed from the stored absolute time.
  const v = h2.hub.state.version;
  h2.clock.advance(20 * MINUTE);
  assert.equal(h2.hub.state.version, v + 1);
});

test('a pending request still expires on time after a restart', async (t) => {
  const dataDir = tempDir();
  const h1 = await startHub({ dataDir });
  const { kidToken } = await setupFamily(h1, ['Miguel']);
  const { id } = (await ask(h1, kidToken)).body;
  await h1.stop();

  const fcm = fakeFcm();
  const h2 = await startHub({ dataDir, clock: fakeClock(START + 2 * MINUTE), fcm });
  t.after(h2.stop);
  assert.equal(h2.hub.state.requests[0].status, 'pending');
  h2.clock.advance(3 * MINUTE);
  assert.equal(h2.hub.state.requests[0].status, 'expired');
  await h2.hub.idle();
  assert.deepEqual(fcm.calls.map((c) => c.data), [{ type: 'request_closed', id, status: 'expired', minutes: '0', by: '' }]);
});

test('a request that ran out while the hub was off is expired at startup', async (t) => {
  const dataDir = tempDir();
  const h1 = await startHub({ dataDir });
  const { kidToken } = await setupFamily(h1, ['Miguel']);
  const { id } = (await ask(h1, kidToken)).body;
  await h1.stop();

  const fcm = fakeFcm();
  const h2 = await startHub({ dataDir, clock: fakeClock(START + 6 * MINUTE), fcm });
  t.after(h2.stop);
  assert.equal(h2.hub.state.requests[0].status, 'expired');
  assert.equal(fcm.calls.length, 1);
  // It finished at its expiry time (a minute ago), so the child still sees it for one more minute.
  assert.deepEqual((await h2.kid('GET', '/kid/state', { token: kidToken })).body.requests, [{ id, number: '0871234567', status: 'expired' }]);
  h2.clock.advance(MINUTE);
  assert.deepEqual((await h2.kid('GET', '/kid/state', { token: kidToken })).body.requests, []);
});

test('the pairing lockout survives a restart', async (t) => {
  const dataDir = tempDir();
  const h1 = await startHub({ dataDir });
  const { kidToken } = await setupFamily(h1, []);
  const { code } = (await h1.kid('POST', '/kid/pairing-code', { token: kidToken })).body;
  const wrong = code === '000000' ? '111111' : '000000';
  for (let i = 0; i < 5; i++) await h1.parent('POST', '/parent/pair', { body: { code: wrong, name: 'Mallory' } });
  await h1.stop();

  const h2 = await startHub({ dataDir, clock: fakeClock(START + MINUTE) });
  t.after(h2.stop);
  assert.equal((await h2.parent('POST', '/parent/pair', { body: { code, name: 'Miguel' } })).status, 429);
});

test('state.json is replaced atomically, holds no plain tokens, and a corrupt file stops the hub', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  await ask(h, kidToken);
  const files = readdirSync(h.dataDir);
  assert.ok(files.includes('state.json'));
  assert.ok(!files.some((f) => f.endsWith('.tmp')), 'no temp file left behind');
  const text = readFileSync(join(h.dataDir, 'state.json'), 'utf8');
  JSON.parse(text);
  assert.ok(!text.includes(kidToken) && !text.includes(Miguel));

  const broken = tempDir();
  writeFileSync(join(broken, 'state.json'), '{"version": 3, "kid": {');
  assert.throws(() => createApp({ dataDir: broken, clock: fakeClock(), log: () => {} }), /not valid JSON/);
});

test('lastSeen is saved lazily and flushed on shutdown', async () => {
  const dataDir = tempDir();
  const h1 = await startHub({ dataDir });
  const { kidToken } = await setupFamily(h1, []);
  h1.clock.advance(MINUTE);
  await h1.kid('GET', '/kid/ping', { token: kidToken, headers: { 'x-app-version': '10300' } });
  const onDisk = () => JSON.parse(readFileSync(join(dataDir, 'state.json'), 'utf8')).kid;
  assert.equal(onDisk().lastSeen, START, 'not written straight away');
  await h1.stop();
  assert.equal(onDisk().lastSeen, START + MINUTE);
  assert.equal(onDisk().appVersion, 10300);
});
