import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MINUTE, setupFamily, startHub } from './helpers.js';

const register = (h, body) => h.kid('POST', '/kid/register', { body });

test('the first child phone may register; a second one is refused', async (t) => {
  const h = await startHub();
  t.after(h.stop);

  const first = await register(h, { childName: 'Alejandro' });
  assert.equal(first.status, 200);
  assert.match(first.body.token, /^[A-Za-z0-9_-]{43}$/); // 32 bytes, base64url

  const second = await register(h, { childName: 'Someone else' });
  assert.equal(second.status, 403);
  assert.equal(second.body.error, 'already_registered');
  assert.ok(second.body.message);

  const ping = await h.kid('GET', '/kid/ping', { token: first.body.token });
  assert.equal(ping.status, 200);
  assert.equal(ping.body.ok, true);
  assert.match(ping.body.hubId, /^hub_[0-9a-f]{16}$/);
  assert.equal(ping.body.time, h.clock.now());
  assert.ok(!JSON.stringify(h.hub.state).includes(first.body.token), 'only the hash is stored');
});

test('re-registering with the current token gives a new token and retires the old one', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const old = (await register(h, { childName: 'Alejandro' })).body.token;

  const again = await register(h, { childName: 'Alex', token: old });
  assert.equal(again.status, 200);
  assert.notEqual(again.body.token, old);
  assert.equal(h.hub.state.kid.name, 'Alex');

  assert.equal((await h.kid('GET', '/kid/ping', { token: old })).status, 401);
  assert.equal((await h.kid('GET', '/kid/ping', { token: again.body.token })).status, 200);
  assert.equal((await register(h, { token: old })).status, 403, 'the retired token no longer re-registers');
  assert.equal((await register(h, { token: 'not-the-token' })).status, 403);
});

test('after replace-kid, one new phone may register within 10 minutes', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);

  assert.deepEqual((await h.parent('POST', '/parent/replace-kid', { token: Miguel })).body, { ok: true });
  h.clock.advance(9 * MINUTE);
  const replacement = await register(h, { childName: 'Alejandro' });
  assert.equal(replacement.status, 200);
  assert.equal((await h.kid('GET', '/kid/ping', { token: kidToken })).status, 401, 'the old phone is out');

  assert.equal((await register(h, {})).status, 403, 'the window lets only one phone in');
  // Apps and parents are kept for the new phone.
  assert.equal((await h.parent('GET', '/parent/state', { token: Miguel })).status, 200);
});

test('the replace window closes after 10 minutes', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { Miguel } = await setupFamily(h, ['Miguel']);
  await h.parent('POST', '/parent/replace-kid', { token: Miguel });
  h.clock.advance(10 * MINUTE);
  assert.equal((await register(h, { childName: 'Late' })).status, 403);
});

test('kid routes need the kid token, and parent tokens are not kid tokens', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  const noToken = await h.kid('GET', '/kid/ping');
  assert.equal(noToken.status, 401);
  assert.equal(noToken.body.error, 'unauthorized');
  assert.equal((await h.kid('GET', '/kid/ping', { token: Miguel })).status, 401);
  assert.equal((await h.parent('GET', '/parent/state', { token: kidToken })).status, 401);
  assert.equal((await h.kid('GET', '/kid/state', { headers: { authorization: 'Basic abc' } })).status, 401);
});
