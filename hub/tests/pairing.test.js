import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MINUTE, setupFamily, startHub } from './helpers.js';

async function newCode(h, kidToken) {
  const res = await h.kid('POST', '/kid/pairing-code', { token: kidToken });
  assert.equal(res.status, 200);
  assert.match(res.body.code, /^\d{6}$/);
  assert.equal(res.body.expiresInSeconds, 600);
  return res.body.code;
}

const pair = (h, code, name = 'Miguel', fcmToken = `fcm-${name}`) =>
  h.parent('POST', '/parent/pair', { body: { code, name, fcmToken } });

const wrong = (code) => String((Number(code) + 1) % 1_000_000).padStart(6, '0');

test('a pairing code works once', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  const code = await newCode(h, kidToken);

  const first = await pair(h, code, 'Miguel');
  assert.equal(first.status, 200);
  assert.equal(first.body.childName, 'Alejandro');
  assert.match(first.body.token, /^[A-Za-z0-9_-]{43}$/);

  const again = await pair(h, code, 'Sonia');
  assert.equal(again.status, 400);
  assert.equal(again.body.error, 'bad_code');

  const state = await h.parent('GET', '/parent/state', { token: first.body.token });
  assert.deepEqual(state.body.parents, [{ name: 'Miguel', me: true }]);
});

test('a pairing code expires after 10 minutes', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  const code = await newCode(h, kidToken);
  h.clock.advance(10 * MINUTE);
  assert.equal((await pair(h, code)).body.error, 'bad_code');
});

test('a new code replaces the earlier unused one', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  const old = await newCode(h, kidToken);
  let fresh = await newCode(h, kidToken);
  while (fresh === old) fresh = await newCode(h, kidToken);
  assert.equal((await pair(h, old)).status, 400);
  assert.equal((await pair(h, fresh)).status, 200);
});

test('five wrong codes in a row lock pairing for 10 minutes', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  const code = await newCode(h, kidToken);

  for (let i = 1; i <= 4; i++) assert.equal((await pair(h, wrong(code))).status, 400, `wrong code ${i}`);
  const fifth = await pair(h, wrong(code));
  assert.equal(fifth.status, 429);
  assert.equal(fifth.body.error, 'too_many_tries');

  assert.equal((await pair(h, code)).status, 429, 'even the right code is refused while locked');
  h.clock.advance(10 * MINUTE - 1);
  assert.equal((await pair(h, code)).status, 429);
  h.clock.advance(1);
  const fresh = await newCode(h, kidToken); // the first code has expired by now
  assert.equal((await pair(h, fresh)).status, 200);
});

test('a right code resets the wrong-code count', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  let code = await newCode(h, kidToken);
  for (let i = 0; i < 4; i++) await pair(h, wrong(code));
  assert.equal((await pair(h, code)).status, 200);
  code = await newCode(h, kidToken);
  for (let i = 0; i < 4; i++) assert.equal((await pair(h, wrong(code))).status, 400);
});

test('pairing needs a name and a code', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  const code = await newCode(h, kidToken);
  const noName = await h.parent('POST', '/parent/pair', { body: { code } });
  assert.equal(noName.status, 400);
  assert.equal(noName.body.error, 'bad_request');
  assert.equal((await h.parent('POST', '/parent/pair', { body: { name: 'Miguel' } })).body.error, 'bad_code');
  assert.equal((await pair(h, code)).status, 200, 'a request without a name did not use up the code');
});

test('logs never contain pairing codes or tokens', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  const code = await newCode(h, kidToken);
  await pair(h, wrong(code));
  const { token } = (await pair(h, code, 'Miguel', 'fcm-secret-token')).body;
  await h.parent('PUT', '/parent/fcm', { token, body: { fcmToken: 'fcm-rotated-secret' } });
  const all = h.logs.join('\n');
  assert.ok(h.logs.some((l) => l.includes('paired: Miguel')));
  for (const secret of [code, wrong(code), token, kidToken, 'fcm-secret-token', 'fcm-rotated-secret']) {
    assert.ok(!all.includes(secret), `log leaked ${secret}`);
  }
});

test('DELETE /parent/me unpairs; PUT /parent/fcm moves alerts to the new token', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel, Sonia } = await setupFamily(h);

  assert.deepEqual((await h.parent('PUT', '/parent/fcm', { token: Miguel, body: { fcmToken: 'fcm-new' } })).body, { ok: true });
  assert.equal((await h.parent('PUT', '/parent/fcm', { token: Miguel, body: {} })).status, 400);

  assert.deepEqual((await h.parent('DELETE', '/parent/me', { token: Sonia })).body, { ok: true });
  assert.equal((await h.parent('GET', '/parent/state', { token: Sonia })).status, 401);

  await h.kid('POST', '/kid/requests', { token: kidToken, body: { contactName: 'Pablo', number: '0871234567' } });
  assert.deepEqual(h.fcm.calls.map((c) => c.token), ['fcm-new']);
});

test('the same phone pairing again replaces its old entry', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  const code = await newCode(h, kidToken);
  const again = await pair(h, code, 'Miguel', 'fcm-Miguel');
  assert.equal(again.status, 200);
  assert.equal((await h.parent('GET', '/parent/state', { token: Miguel })).status, 401);
  const state = await h.parent('GET', '/parent/state', { token: again.body.token });
  assert.deepEqual(state.body.parents, [{ name: 'Miguel', me: true }]);
});
