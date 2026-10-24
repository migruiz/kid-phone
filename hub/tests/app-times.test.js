import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MINUTE, START, fakeClock, setupFamily, startHub, tempDir, until } from './helpers.js';

const CHESS = 'com.chess';

async function withChess(h) {
  const family = await setupFamily(h, ['Miguel']);
  await h.kid('PUT', '/kid/apps', {
    token: family.kidToken,
    body: { apps: [{ package: CHESS, label: 'Chess' }, { package: 'com.duolingo', label: 'Duolingo' }] },
  });
  return family;
}

const setApp = (h, token, body, pkg = CHESS) => h.parent('PUT', `/parent/apps/${pkg}`, { token, body });
const kidState = async (h, kidToken) => (await h.kid('GET', '/kid/state', { token: kidToken })).body;
const parentApp = async (h, token, pkg = CHESS) =>
  (await h.parent('GET', '/parent/state', { token })).body.apps.find((a) => a.package === pkg);

test('an app switched on for an hour is on for an hour, then off by itself', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await withChess(h);

  assert.deepEqual((await setApp(h, Miguel, { allowed: true, minutes: 60 })).body, { ok: true });
  const on = await kidState(h, kidToken);
  assert.deepEqual(on.allowedPackages, [CHESS]);
  assert.deepEqual(on.appTimes, [{ package: CHESS, remainingSeconds: 3600, minutes: 60 }]);
  assert.deepEqual(await parentApp(h, Miguel), {
    package: CHESS, label: 'Chess', allowed: true, installed: true, hasIcon: false, minutes: 60, remainingSeconds: 3600,
  });
  assert.ok(h.logs.includes(`${CHESS} allowed for 60 minutes by Miguel`));

  // The time ticks without a new version: the phone counts down by itself.
  h.clock.advance(45 * MINUTE);
  const later = await kidState(h, kidToken);
  assert.equal(later.version, on.version);
  assert.deepEqual(later.appTimes, [{ package: CHESS, remainingSeconds: 900, minutes: 60 }]);

  // The end wakes a waiting phone with the app switched off.
  const poll = h.kid('GET', `/kid/state?since=${later.version}&wait=30`, { token: kidToken });
  await until(() => h.hub.waiterCount() === 1);
  h.clock.advance(15 * MINUTE);
  const off = (await poll).body;
  assert.equal(off.version, later.version + 1);
  assert.deepEqual(off.allowedPackages, []);
  assert.deepEqual(off.appTimes, []);
  assert.deepEqual(await parentApp(h, Miguel), { package: CHESS, label: 'Chess', allowed: false, installed: true, hasIcon: false });
  assert.ok(h.logs.includes(`${CHESS} switched off: its time is up`));
});

test('a time can be changed from now, made unlimited, or stopped', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await withChess(h);

  await setApp(h, Miguel, { allowed: true, minutes: 60 });
  h.clock.advance(50 * MINUTE);
  const before = await kidState(h, kidToken);

  // Ten minutes were left; now it is thirty from now, and the phone hears about it.
  await setApp(h, Miguel, { allowed: true, minutes: 30 });
  const changed = await kidState(h, kidToken);
  assert.ok(changed.version > before.version);
  assert.deepEqual(changed.appTimes, [{ package: CHESS, remainingSeconds: 1800, minutes: 30 }]);

  // On with no time: the time goes, the app stays on, and it doesn't switch off later.
  await setApp(h, Miguel, { allowed: true });
  const unlimited = await kidState(h, kidToken);
  assert.ok(unlimited.version > changed.version);
  assert.deepEqual(unlimited.allowedPackages, [CHESS]);
  assert.deepEqual(unlimited.appTimes, []);
  assert.equal((await parentApp(h, Miguel)).remainingSeconds, undefined);
  h.clock.advance(5 * 60 * MINUTE);
  assert.deepEqual((await kidState(h, kidToken)).allowedPackages, [CHESS]);

  // Timed again, then switched off before the end: off, and no time left behind.
  await setApp(h, Miguel, { allowed: true, minutes: 15 });
  await setApp(h, Miguel, { allowed: false, minutes: 15 }); // minutes mean nothing when switching off
  const off = await kidState(h, kidToken);
  assert.deepEqual(off.allowedPackages, []);
  assert.deepEqual(off.appTimes, []);
  h.clock.advance(20 * MINUTE);
  assert.equal((await kidState(h, kidToken)).version, off.version, 'nothing left to end');
});

test('only whole minutes from 1 to a day are accepted', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await withChess(h);

  for (const bad of [0, -5, 1.5, '60', 24 * 60 + 1, true]) {
    const res = await setApp(h, Miguel, { allowed: true, minutes: bad });
    assert.equal(res.status, 400, `minutes ${JSON.stringify(bad)}`);
    assert.equal(res.body.error, 'bad_minutes');
  }
  assert.deepEqual((await kidState(h, kidToken)).allowedPackages, [], 'a refused change changes nothing');
  assert.equal((await setApp(h, Miguel, { allowed: true, minutes: 24 * 60 })).status, 200);
  assert.equal((await setApp(h, Miguel, { allowed: true, minutes: null })).status, 200); // null: no time limit
  assert.deepEqual((await kidState(h, kidToken)).appTimes, []);
});

test("the child's phone can give an app a time too (behind its PIN)", async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await withChess(h);

  const res = await h.kid('PUT', `/kid/apps/${CHESS}`, { token: kidToken, body: { allowed: true, minutes: 30 } });
  assert.deepEqual(res.body, { ok: true });
  assert.deepEqual((await kidState(h, kidToken)).appTimes, [{ package: CHESS, remainingSeconds: 1800, minutes: 30 }]);
  assert.equal((await parentApp(h, Miguel)).remainingSeconds, 1800);
  assert.ok(h.logs.includes(`${CHESS} allowed for 30 minutes by the child's phone`));
  assert.equal((await h.kid('PUT', `/kid/apps/${CHESS}`, { token: kidToken, body: { allowed: true, minutes: 0 } })).status, 400);
});

test('two timed apps end separately, each at its own time', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await withChess(h);
  await setApp(h, Miguel, { allowed: true, minutes: 10 });
  await setApp(h, Miguel, { allowed: true, minutes: 20 }, 'com.duolingo');
  assert.deepEqual((await kidState(h, kidToken)).appTimes, [
    { package: CHESS, remainingSeconds: 600, minutes: 10 },
    { package: 'com.duolingo', remainingSeconds: 1200, minutes: 20 },
  ]);
  h.clock.advance(10 * MINUTE);
  assert.deepEqual((await kidState(h, kidToken)).allowedPackages, ['com.duolingo']);
  h.clock.advance(10 * MINUTE);
  assert.deepEqual((await kidState(h, kidToken)).allowedPackages, []);
});

test('a time survives a restart, and one that ran out while the hub was off is over', async (t) => {
  const dataDir = tempDir();
  const h1 = await startHub({ dataDir });
  const { kidToken, Miguel } = await withChess(h1);
  await setApp(h1, Miguel, { allowed: true, minutes: 30 });
  await setApp(h1, Miguel, { allowed: true, minutes: 5 }, 'com.duolingo');
  await h1.stop();

  const h2 = await startHub({ dataDir, clock: fakeClock(START + 10 * MINUTE) });
  t.after(h2.stop);
  const after = await kidState(h2, kidToken);
  assert.deepEqual(after.allowedPackages, [CHESS], 'Duolingo ran out while the hub was off');
  assert.deepEqual(after.appTimes, [{ package: CHESS, remainingSeconds: 1200, minutes: 30 }]);

  // The end was re-armed from the stored absolute time.
  h2.clock.advance(20 * MINUTE);
  assert.deepEqual((await kidState(h2, kidToken)).allowedPackages, []);
});
