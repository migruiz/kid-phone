import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MINUTE, START, setupFamily, startHub, until } from './helpers.js';

const NUMBER = '0871234567';
const ask = (h, kidToken, number = NUMBER, contactName = 'Pablo') =>
  h.kid('POST', '/kid/requests', { token: kidToken, body: { contactName, number } });
const answer = (h, token, id, minutes) => h.parent('POST', `/parent/requests/${id}/answer`, { token, body: { minutes } });
const kidState = async (h, kidToken) => (await h.kid('GET', '/kid/state', { token: kidToken })).body;
const tokensCalled = (h) => h.fcm.calls.map((c) => c.token).sort();

test('a new request alerts every parent; a duplicate returns the same one without alerting', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h);

  const created = await ask(h, kidToken);
  assert.equal(created.status, 200);
  assert.match(created.body.id, /^r_[0-9a-f]{12}$/);
  assert.deepEqual(created.body, { id: created.body.id, number: NUMBER, status: 'pending' });
  assert.deepEqual(tokensCalled(h), ['fcm-Miguel', 'fcm-Sonia']);
  assert.deepEqual(h.fcm.calls[0].data, {
    type: 'call_request',
    id: created.body.id,
    childName: 'Alejandro',
    contactName: 'Pablo',
    number: NUMBER,
    expiresAt: String(START + 5 * MINUTE),
  });

  const duplicate = await ask(h, kidToken, '087 123 4567'); // same number, spaced differently
  assert.equal(duplicate.body.id, created.body.id);
  assert.equal(duplicate.body.status, 'pending');
  assert.equal(h.fcm.calls.length, 2, 'no second alert');

  assert.deepEqual((await kidState(h, kidToken)).requests, [{ id: created.body.id, number: NUMBER, status: 'pending' }]);
  assert.equal((await h.kid('POST', '/kid/requests', { token: kidToken, body: { contactName: 'Nobody' } })).status, 400);
});

test('the first answer wins; every parent gets request_closed; the child is woken', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel, Sonia } = await setupFamily(h);
  const { id } = (await ask(h, kidToken)).body;
  const { version } = await kidState(h, kidToken);
  h.fcm.calls.length = 0;

  const poll = h.kid('GET', `/kid/state?since=${version}&wait=30`, { token: kidToken });
  await until(() => h.hub.waiterCount() === 1);

  const first = await answer(h, Miguel, id, 30);
  assert.equal(first.status, 200);
  assert.deepEqual(first.body, {
    request: { id, contactName: 'Pablo', number: NUMBER, status: 'approved', createdAt: START, decidedBy: 'Miguel', minutes: 30, remainingSeconds: 1800 },
    alreadyAnswered: false,
  });
  const woken = (await poll).body;
  assert.equal(woken.version, version + 1);
  assert.deepEqual(woken.requests, [{ id, number: NUMBER, status: 'approved', remainingSeconds: 1800, minutes: 30 }]);

  await h.hub.idle();
  assert.deepEqual(tokensCalled(h), ['fcm-Miguel', 'fcm-Sonia']);
  for (const call of h.fcm.calls) {
    assert.deepEqual(call.data, { type: 'request_closed', id, status: 'approved', minutes: '30', by: 'Miguel' });
  }

  const second = await answer(h, Sonia, id, 0);
  assert.equal(second.status, 200);
  assert.equal(second.body.alreadyAnswered, true);
  assert.equal(second.body.request.status, 'approved');
  assert.equal(second.body.request.decidedBy, 'Miguel');
  assert.equal(h.fcm.calls.length, 2, 'a late answer sends nothing');
  assert.equal((await kidState(h, kidToken)).version, woken.version);
});

test('approved time is relative; the same number reuses it without an alert; the end bumps the version', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h);
  const { id } = (await ask(h, kidToken)).body;
  await answer(h, Miguel, id, 10);

  h.clock.advance(4 * MINUTE);
  const state = await kidState(h, kidToken);
  assert.deepEqual(state.requests, [{ id, number: NUMBER, status: 'approved', remainingSeconds: 360, minutes: 10 }]);
  const parentView = (await h.parent('GET', '/parent/state', { token: Miguel })).body.requests[0];
  assert.equal(parentView.remainingSeconds, 360);

  h.fcm.calls.length = 0;
  const reused = await ask(h, kidToken);
  assert.deepEqual(reused.body, { id, number: NUMBER, status: 'approved', remainingSeconds: 360, minutes: 10 });
  assert.equal(h.fcm.calls.length, 0, 'approved with time left: nobody is alerted');

  const poll = h.kid('GET', `/kid/state?since=${state.version}&wait=30`, { token: kidToken });
  await until(() => h.hub.waiterCount() === 1);
  h.clock.advance(6 * MINUTE); // the window ends while the phone is waiting
  const ended = (await poll).body;
  assert.equal(ended.version, state.version + 1);
  assert.deepEqual(ended.requests, []);

  const fresh = await ask(h, kidToken);
  assert.notEqual(fresh.body.id, id);
  assert.equal(fresh.body.status, 'pending');
  assert.equal(h.fcm.calls.length, 2);
});

test('a pending request expires after 5 minutes and the parents are told', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h);
  const { id } = (await ask(h, kidToken)).body;
  h.fcm.calls.length = 0;

  h.clock.advance(5 * MINUTE - 1);
  assert.equal(h.hub.state.requests[0].status, 'pending');
  const { version } = await kidState(h, kidToken);
  h.clock.advance(1);
  assert.equal(h.hub.state.requests[0].status, 'expired', 'expired by the timer, without any request');
  assert.equal(h.hub.state.version, version + 1);
  await h.hub.idle();
  assert.deepEqual(tokensCalled(h), ['fcm-Miguel', 'fcm-Sonia']);
  assert.deepEqual(h.fcm.calls[0].data, { type: 'request_closed', id, status: 'expired', minutes: '0', by: '' });
  assert.deepEqual((await kidState(h, kidToken)).requests, [{ id, number: NUMBER, status: 'expired' }]);

  const late = await answer(h, Miguel, id, 30);
  assert.equal(late.body.alreadyAnswered, true);
  assert.equal(late.body.request.status, 'expired');

  // Finished requests stay on the child's list for 2 minutes.
  h.clock.advance(2 * MINUTE);
  assert.equal(h.hub.state.version, version + 2);
  assert.deepEqual((await kidState(h, kidToken)).requests, []);
});

test('rejecting and cancelling', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Sonia } = await setupFamily(h);

  const a = (await ask(h, kidToken)).body;
  const rejected = await answer(h, Sonia, a.id, 0);
  assert.deepEqual(rejected.body.request, {
    id: a.id, contactName: 'Pablo', number: NUMBER, status: 'rejected', createdAt: START, decidedBy: 'Sonia', minutes: 0,
  });
  await h.hub.idle();
  assert.deepEqual(h.fcm.calls.at(-1).data, { type: 'request_closed', id: a.id, status: 'rejected', minutes: '0', by: 'Sonia' });
  assert.deepEqual((await kidState(h, kidToken)).requests, [{ id: a.id, number: NUMBER, status: 'rejected' }]);

  const b = (await ask(h, kidToken, '0861111111', 'Granny')).body;
  h.fcm.calls.length = 0;
  assert.deepEqual((await h.kid('POST', `/kid/requests/${b.id}/cancel`, { token: kidToken })).body, { ok: true });
  await h.hub.idle();
  assert.deepEqual(h.fcm.calls.map((c) => c.data.status), ['cancelled', 'cancelled']);
  assert.equal(h.fcm.calls[0].data.by, '');
  assert.deepEqual((await h.kid('POST', `/kid/requests/${b.id}/cancel`, { token: kidToken })).body, { ok: true });
  assert.equal(h.fcm.calls.length, 2, 'cancelling twice changes nothing');
  assert.equal((await h.kid('POST', '/kid/requests/r_nope/cancel', { token: kidToken })).status, 404);

  const again = (await ask(h, kidToken)).body;
  assert.notEqual(again.id, a.id, 'after a rejection the child may ask again');
  assert.equal(again.status, 'pending');
});

test('bad answers are refused', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  const { id } = (await ask(h, kidToken)).body;
  for (const minutes of [15, '30', -10, null, undefined, 30.5]) {
    const res = await answer(h, Miguel, id, minutes);
    assert.equal(res.status, 400, `minutes ${minutes}`);
    assert.equal(res.body.error, 'bad_minutes');
  }
  assert.equal((await answer(h, Miguel, 'r_missing', 30)).status, 404);
  assert.equal((await answer(h, undefined, id, 30)).status, 401);
  assert.equal(h.hub.state.requests[0].status, 'pending');
});

test('the parent list shows pending requests and the last 20 others, newest first', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  for (let i = 0; i < 22; i++) {
    const { id } = (await ask(h, kidToken, `08700000${String(i).padStart(2, '0')}`)).body;
    await answer(h, Miguel, id, 0);
    h.clock.advance(1000);
  }
  const pending = (await ask(h, kidToken, '0899999999')).body;
  const { requests } = (await h.parent('GET', '/parent/state', { token: Miguel })).body;
  assert.equal(requests.length, 21);
  assert.equal(requests[0].id, pending.id);
  assert.equal(requests[1].number, '0870000021');
  assert.equal(requests[20].number, '0870000002');
  assert.ok(requests.every((r, i) => i === 0 || r.createdAt <= requests[i - 1].createdAt));
});

test('a token FCM reports as unregistered is removed; other failures keep it', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h);
  h.fcm.failWith('fcm-Sonia', { ok: false, status: 404, error: 'UNREGISTERED', unregistered: true });
  h.fcm.failWith('fcm-Miguel', { ok: false, status: 500, error: 'INTERNAL', unregistered: false });

  await ask(h, kidToken);
  await h.hub.idle();
  const [miguel, sonia] = h.hub.state.parents;
  assert.equal(miguel.fcmToken, 'fcm-Miguel');
  assert.equal(sonia.fcmToken, null);
  assert.ok(h.logs.some((l) => /to Sonia: failed \(UNREGISTERED\), token removed/.test(l)));

  h.fcm.calls.length = 0;
  await ask(h, kidToken, '0861111111');
  assert.deepEqual(tokensCalled(h), ['fcm-Miguel']);
});

test('without a Firebase key requests still work', async (t) => {
  const h = await startHub({ fcm: null });
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  const { id } = (await ask(h, kidToken)).body;
  assert.ok(h.logs.some((l) => l.includes('alerts are off')));
  assert.equal((await answer(h, Miguel, id, 60)).body.request.remainingSeconds, 3600);
});
