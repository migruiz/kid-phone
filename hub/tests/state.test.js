import assert from 'node:assert/strict';
import { test } from 'node:test';
import { setupFamily, startHub, until } from './helpers.js';

async function withApps(h) {
  const family = await setupFamily(h, ['Miguel']);
  await h.kid('PUT', '/kid/apps', {
    token: family.kidToken,
    body: { apps: [{ package: 'com.duolingo', label: 'Duolingo' }, { package: 'com.spotify.music', label: 'Spotify' }] },
  });
  return family;
}

test('toggling an app bumps the version and wakes a waiting long-poll', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await withApps(h);
  const before = (await h.kid('GET', '/kid/state', { token: kidToken })).body;
  assert.deepEqual(before.allowedPackages, []);
  assert.deepEqual(before.requests, []);

  const poll = h.kid('GET', `/kid/state?since=${before.version}&wait=30`, { token: kidToken });
  await until(() => h.hub.waiterCount() === 1);

  const toggle = await h.parent('PUT', '/parent/apps/com.duolingo', { token: Miguel, body: { allowed: true } });
  assert.deepEqual(toggle.body, { ok: true });
  const woken = (await poll).body;
  assert.equal(woken.version, before.version + 1);
  assert.deepEqual(woken.allowedPackages, ['com.duolingo']);
  assert.equal(h.hub.waiterCount(), 0);

  // Setting the same value again changes nothing, so no new version.
  await h.parent('PUT', '/parent/apps/com.duolingo', { token: Miguel, body: { allowed: true } });
  assert.equal((await h.kid('GET', '/kid/state', { token: kidToken })).body.version, woken.version);
  assert.equal((await h.parent('PUT', '/parent/apps/com.duolingo', { token: Miguel, body: { allowed: 'yes' } })).status, 400);
});

test('a long-poll answers after `wait` seconds when nothing changes', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await withApps(h);
  const { version } = (await h.kid('GET', '/kid/state', { token: kidToken })).body;

  const poll = h.kid('GET', `/kid/state?since=${version}&wait=5`, { token: kidToken });
  await until(() => h.hub.waiterCount() === 1);
  h.clock.advance(4999);
  assert.equal(h.hub.waiterCount(), 1);
  h.clock.advance(1);
  const res = await poll;
  assert.equal(res.status, 200);
  assert.equal(res.body.version, version);
});

test('wait is capped at 30 seconds; a different `since` answers at once', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await withApps(h);
  const { version } = (await h.kid('GET', '/kid/state', { token: kidToken })).body;

  const stale = await h.kid('GET', `/kid/state?since=${version - 1}&wait=30`, { token: kidToken });
  assert.equal(stale.body.version, version);
  assert.equal((await h.kid('GET', `/kid/state?wait=30`, { token: kidToken })).body.version, version, 'no since: no wait');

  const poll = h.kid('GET', `/kid/state?since=${version}&wait=600`, { token: kidToken });
  await until(() => h.hub.waiterCount() === 1);
  h.clock.advance(30_000);
  assert.equal((await poll).body.version, version);
});

test('a long-poll whose phone hangs up is cleaned up', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await withApps(h);
  const { version } = (await h.kid('GET', '/kid/state', { token: kidToken })).body;

  const hangUp = new AbortController();
  const poll = h.kid('GET', `/kid/state?since=${version}&wait=30`, { token: kidToken, signal: hangUp.signal });
  await until(() => h.hub.waiterCount() === 1);
  hangUp.abort();
  await assert.rejects(poll);
  await until(() => h.hub.waiterCount() === 0);
});

test('the child state tracks lastSeen and the app version', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await withApps(h);
  h.clock.advance(60_000);
  await h.kid('GET', '/kid/ping', { token: kidToken, headers: { 'x-app-version': '10203' } });
  const { child, time } = (await h.parent('GET', '/parent/state', { token: Miguel })).body;
  assert.deepEqual(child, { name: 'Alejandro', paired: true, lastSeen: h.clock.now(), appVersion: 10203 });
  assert.equal(time, h.clock.now());
});
