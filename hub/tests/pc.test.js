import assert from 'node:assert/strict';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { MINUTE, pngBytes, setupFamily, startHub, until } from './helpers.js';

const ROBLOX = 'roblox';

async function registerPc(h, body = {}) {
  return h.kid('POST', '/pc/register', { body, headers: { 'x-app-version': '10900' } });
}

/** The family, plus the PC registered with Roblox and Chrome on it. */
async function withPc(h) {
  const family = await setupFamily(h, ['Miguel']);
  const pcToken = (await registerPc(h)).body.token;
  await h.kid('PUT', '/pc/apps', {
    token: pcToken,
    body: { apps: [{ id: ROBLOX, label: 'Roblox', iconPng: pngBytes(64, 64, 7).toString('base64') }, { id: 'chrome', label: 'Google Chrome' }] },
  });
  return { ...family, pcToken };
}

const pcState = async (h, pcToken, query = '') => (await h.kid('GET', `/pc/state${query}`, { token: pcToken })).body;
const parentPc = async (h, token) => (await h.parent('GET', '/parent/state', { token })).body.pc;
const setPcApp = (h, token, body, id = ROBLOX) => h.parent('PUT', `/parent/pc/apps/${id}`, { token, body });

test('the PC registers once, again with its token, and otherwise only after a parent allows a replacement', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { Miguel } = await setupFamily(h, ['Miguel']);
  assert.deepEqual(await parentPc(h, Miguel), { paired: false, lastSeen: null, appVersion: null, apps: [] });

  const first = await registerPc(h);
  assert.equal(first.status, 200);
  assert.ok(h.logs.includes('PC registered first time'));
  const pc = await parentPc(h, Miguel);
  assert.equal(pc.paired, true);
  assert.equal(pc.appVersion, 10900);

  // Anyone else on the home network is turned away.
  const other = await registerPc(h);
  assert.equal(other.status, 403);
  assert.equal(other.body.error, 'already_registered');

  // With its own token: a new token, and the old one stops working.
  const again = await registerPc(h, { token: first.body.token });
  assert.equal(again.status, 200);
  assert.equal((await h.kid('GET', '/pc/state', { token: first.body.token })).status, 401);
  assert.equal((await h.kid('GET', '/pc/state', { token: again.body.token })).status, 200);

  // A parent lets one new PC in, within 10 minutes.
  assert.deepEqual((await h.parent('POST', '/parent/replace-pc', { token: Miguel })).body, { ok: true });
  h.clock.advance(9 * MINUTE);
  const replacement = await registerPc(h);
  assert.equal(replacement.status, 200);
  assert.ok(h.logs.includes('PC registered as a replacement'));
  assert.equal((await registerPc(h)).status, 403, 'the window lets one PC in');
  assert.equal((await h.kid('GET', '/pc/state', { token: again.body.token })).status, 401);

  await h.parent('POST', '/parent/replace-pc', { token: Miguel });
  h.clock.advance(11 * MINUTE);
  assert.equal((await registerPc(h)).status, 403, 'too late');
});

test("the phone's token can't act as the PC, nor the PC's as the phone", async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, pcToken } = await withPc(h);

  assert.equal((await h.kid('GET', '/pc/state', { token: kidToken })).status, 401);
  assert.equal((await h.kid('PUT', '/pc/apps', { token: kidToken, body: { apps: [] } })).status, 401);
  assert.equal((await h.kid('GET', '/kid/state', { token: pcToken })).status, 401);
  assert.equal((await h.kid('PUT', '/kid/apps/com.duolingo', { token: pcToken, body: { allowed: true } })).status, 401);
  assert.equal((await h.kid('POST', '/kid/pairing-code', { token: pcToken })).status, 401);
  assert.equal((await h.kid('GET', '/kid/state', { token: kidToken })).status, 200);
  // The PC's routes are on the kid port only.
  assert.equal((await h.parent('GET', '/pc/state', { token: pcToken })).status, 404);
});

test('the parents see the PC apps and switch Roblox on for an hour; it goes off by itself', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, pcToken, Miguel } = await withPc(h);

  const pc = await parentPc(h, Miguel);
  assert.deepEqual(pc.apps, [
    { id: 'chrome', label: 'Google Chrome', allowed: false, installed: true, hasIcon: false },
    { id: ROBLOX, label: 'Roblox', allowed: false, installed: true, hasIcon: true },
  ]);
  const icon = await h.parent('GET', `/parent/pc/apps/${ROBLOX}/icon`, { token: Miguel });
  assert.equal(icon.status, 200);
  assert.deepEqual(icon.body, pngBytes(64, 64, 7));
  assert.equal((await h.parent('GET', '/parent/pc/apps/chrome/icon', { token: Miguel })).status, 404);
  // A phone app of the same name is a different thing.
  assert.equal((await h.parent('GET', `/parent/apps/${ROBLOX}/icon`, { token: Miguel })).status, 404);

  const off = await pcState(h, pcToken);
  assert.deepEqual(off, { version: off.version, allowed: [], appTimes: [] });

  // A waiting PC hears it at once.
  const poll = h.kid('GET', `/pc/state?since=${off.version}&wait=30`, { token: pcToken });
  await until(() => h.hub.waiterCount() === 1);
  assert.deepEqual((await setPcApp(h, Miguel, { allowed: true, minutes: 60 })).body, { ok: true });
  const on = (await poll).body;
  assert.ok(on.version > off.version);
  assert.deepEqual(on.allowed, [ROBLOX]);
  assert.deepEqual(on.appTimes, [{ id: ROBLOX, remainingSeconds: 3600, minutes: 60 }]);
  assert.ok(h.logs.includes('PC app roblox allowed for 60 minutes by Miguel'));
  assert.deepEqual((await parentPc(h, Miguel)).apps.find((a) => a.id === ROBLOX), {
    id: ROBLOX, label: 'Roblox', allowed: true, installed: true, hasIcon: true, minutes: 60, remainingSeconds: 3600,
  });

  // The phone's apps are untouched.
  const phone = (await h.kid('GET', '/kid/state', { token: kidToken })).body;
  assert.deepEqual(phone.allowedPackages, []);
  assert.deepEqual(phone.appTimes, []);

  // The end wakes a waiting PC with Roblox off.
  h.clock.advance(59 * MINUTE);
  const late = await pcState(h, pcToken);
  assert.deepEqual(late.appTimes, [{ id: ROBLOX, remainingSeconds: 60, minutes: 60 }]);
  const endPoll = h.kid('GET', `/pc/state?since=${late.version}&wait=30`, { token: pcToken });
  await until(() => h.hub.waiterCount() === 1);
  h.clock.advance(MINUTE);
  const ended = (await endPoll).body;
  assert.deepEqual(ended.allowed, []);
  assert.deepEqual(ended.appTimes, []);
  assert.ok(h.logs.includes('PC app roblox switched off: its time is up'));
  assert.equal((await parentPc(h, Miguel)).apps.find((a) => a.id === ROBLOX).allowed, false);
});

test('PC app switches: no time limit, a new time from now, off, and errors', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { pcToken, Miguel } = await withPc(h);

  await setPcApp(h, Miguel, { allowed: true });
  assert.deepEqual(await pcState(h, pcToken).then((s) => [s.allowed, s.appTimes]), [[ROBLOX], []]);
  await setPcApp(h, Miguel, { allowed: true, minutes: 15 });
  assert.deepEqual((await pcState(h, pcToken)).appTimes, [{ id: ROBLOX, remainingSeconds: 900, minutes: 15 }]);
  await setPcApp(h, Miguel, { allowed: false });
  assert.deepEqual((await pcState(h, pcToken)).allowed, []);
  assert.ok(h.logs.includes('PC app roblox blocked by Miguel'));

  assert.equal((await setPcApp(h, Miguel, { allowed: true, minutes: 0 })).body.error, 'bad_minutes');
  assert.equal((await setPcApp(h, Miguel, { allowed: true }, 'nothere')).status, 404);
  assert.equal((await setPcApp(h, Miguel, { allowed: 'yes' })).status, 400);
  assert.equal((await setPcApp(h, null, { allowed: true })).status, 401);
});

test('PC apps missing from the list are kept with their switch; bad ids are refused', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { pcToken, Miguel } = await withPc(h);
  await setPcApp(h, Miguel, { allowed: true });

  await h.kid('PUT', '/pc/apps', { token: pcToken, body: { apps: [{ id: 'chrome', label: 'Google Chrome' }] } });
  assert.deepEqual((await parentPc(h, Miguel)).apps.find((a) => a.id === ROBLOX), {
    id: ROBLOX, label: 'Roblox', allowed: true, installed: false, hasIcon: true,
  });
  assert.ok(h.logs.includes('PC apps updated: 1 installed, 0 new, 1 gone, 0 icons saved'));

  const iconPng = pngBytes().toString('base64');
  for (const bad of ['../../state', '..', '.x', 'Roblox', 'a/b', 'a\\b', '', 'x'.repeat(65), 42, null]) {
    const res = await h.kid('PUT', '/pc/apps', { token: pcToken, body: { apps: [{ id: bad, label: 'Bad', iconPng }] } });
    assert.equal(res.status, 400, `id ${JSON.stringify(bad)}`);
    assert.equal(res.body.error, 'bad_id');
  }
  assert.deepEqual(readdirSync(join(h.dataDir, 'icons-pc')), [`${ROBLOX}.png`]);
  assert.ok(!existsSync(join(h.dataDir, 'state.png')));
  assert.equal((await h.parent('GET', '/parent/pc/apps/..%2F..%2Fstate.json/icon', { token: Miguel })).status, 404);
  assert.equal((await h.kid('PUT', '/pc/apps', { token: pcToken, body: { apps: 'nope' } })).status, 400);
});

test("the PC's token, apps and times survive a restart", async (t) => {
  const first = await startHub();
  const { pcToken, Miguel } = await withPc(first);
  await setPcApp(first, Miguel, { allowed: true, minutes: 30 });
  await first.stop();

  const h = await startHub({ dataDir: first.dataDir, clock: first.clock });
  t.after(h.stop);
  h.clock.advance(10 * MINUTE);
  const s = await pcState(h, pcToken);
  assert.deepEqual(s.allowed, [ROBLOX]);
  assert.deepEqual(s.appTimes, [{ id: ROBLOX, remainingSeconds: 1200, minutes: 30 }]);
  assert.equal((await h.parent('GET', `/parent/pc/apps/${ROBLOX}/icon`, { token: Miguel })).status, 200);
  h.clock.advance(20 * MINUTE);
  assert.deepEqual((await pcState(h, pcToken)).allowed, []);
});

test('an app marked defaultAllowed starts on when new, but keeps the parents\' switch after', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { pcToken, Miguel } = await withPc(h);

  // The tools come with defaultAllowed; they start switched on without a parent touching them.
  await h.kid('PUT', '/pc/apps', {
    token: pcToken,
    body: { apps: [{ id: 'calculator', label: 'Calculator', defaultAllowed: true }, { id: 'chrome', label: 'Google Chrome' }] },
  });
  const apps = await parentPc(h, Miguel);
  assert.equal(apps.apps.find((a) => a.id === 'calculator').allowed, true, 'tool on by default');
  assert.equal(apps.apps.find((a) => a.id === 'chrome').allowed, false, 'a normal app stays off');
  assert.deepEqual((await pcState(h, pcToken)).allowed.sort(), ['calculator']);

  // A parent switches the tool off; a later list (still defaultAllowed) must not switch it back on.
  await setPcApp(h, Miguel, { allowed: false }, 'calculator');
  await h.kid('PUT', '/pc/apps', { token: pcToken, body: { apps: [{ id: 'calculator', label: 'Calculator', defaultAllowed: true }] } });
  assert.equal((await parentPc(h, Miguel)).apps.find((a) => a.id === 'calculator').allowed, false, 'stays off once a parent chose so');
});

test('the PC is last seen when it asks for its state', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { pcToken, Miguel } = await withPc(h);
  h.clock.advance(5 * MINUTE);
  await h.kid('GET', '/pc/state', { token: pcToken, headers: { 'x-app-version': '10901' } });
  const pc = await parentPc(h, Miguel);
  assert.equal(pc.lastSeen, h.clock.now());
  assert.equal(pc.appVersion, 10901);
});
