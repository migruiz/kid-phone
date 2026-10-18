import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { MINUTE, START, setupFamily, startHub } from './helpers.js';

const report = (h, kidToken, percent, charging = false) =>
  h.kid('PUT', '/kid/battery', { token: kidToken, body: { percent, charging } });

/** Reports each reading in turn; returns the battery messages the parents got, as "level percent". */
async function drain(h, kidToken, readings) {
  h.fcm.calls.length = 0;
  for (const r of readings) {
    const [percent, charging] = Array.isArray(r) ? r : [r, false];
    assert.equal((await report(h, kidToken, percent, charging)).status, 200);
  }
  await h.hub.idle();
  return h.fcm.calls.filter((c) => c.token === 'fcm-Miguel').map((c) => `${c.data.level} ${c.data.percent}`);
}

test('below 25, 15 and 10 percent each alert every parent once', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h);

  assert.deepEqual(await drain(h, kidToken, [40, 30, 26, 25]), [], 'nothing at 25% or above');
  assert.deepEqual(await drain(h, kidToken, [24]), ['low 24']);
  assert.deepEqual(h.fcm.calls.map((c) => c.token).sort(), ['fcm-Miguel', 'fcm-Sonia']);
  assert.deepEqual(h.fcm.calls[0].data, { type: 'battery', level: 'low', percent: '24', childName: 'Alejandro' });

  assert.deepEqual(await drain(h, kidToken, [23, 20, 16, 15]), [], 'one alert per line');
  assert.deepEqual(await drain(h, kidToken, [14, 12]), ['very_low 14']);
  assert.deepEqual(await drain(h, kidToken, [10, 9, 5, 1, 0]), ['critical 9']);
});

test('a first report far down sends only the most severe alert', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, ['Miguel']);
  assert.deepEqual(await drain(h, kidToken, [8, 12, 20]), ['critical 8'], 'no "low" after "critical"');
});

test('no alerts while plugged in; unplugging while low alerts', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, ['Miguel']);
  assert.deepEqual(await drain(h, kidToken, [[20, true], [12, true], [8, true]]), []);
  assert.deepEqual(await drain(h, kidToken, [[12, false]]), ['very_low 12']);
});

test('plugging in or out after an alert tells the parents', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, ['Miguel']);
  assert.deepEqual(await drain(h, kidToken, [30, [30, true], 30]), [], 'nothing about the plug without an alert out');
  assert.deepEqual(await drain(h, kidToken, [24, [24, true], [24, true], 24]), ['low 24', 'charging 24', 'unplugged 24']);
  assert.deepEqual(h.fcm.calls[1].data, { type: 'battery', level: 'charging', percent: '24', childName: 'Alejandro' });
  // Charged past the line, the alert is over: unplugging says nothing.
  assert.deepEqual(await drain(h, kidToken, [[26, true], 26]), ['charging 26']);
});

test('a loose cable at 9% alerts once, not on every unplug', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, ['Miguel']);
  assert.deepEqual(
    await drain(h, kidToken, [9, [9, true], 9, [9, true], 9, [10, false], 9]),
    ['critical 9', 'charging 9', 'unplugged 9', 'charging 9', 'unplugged 9'],
  );
});

test('a line alerts again once the phone has charged back up to it', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, ['Miguel']);
  assert.deepEqual(await drain(h, kidToken, [9]), ['critical 9']);
  // Charged to 11%: "critical" is armed again, "very low" and "low" are still out.
  assert.deepEqual(await drain(h, kidToken, [[10, true], [11, true], 11, 10, 9]), ['charging 10', 'unplugged 11', 'critical 9']);
  // Charged to 20%, then down again: "very low" and "critical" again, not "low".
  assert.deepEqual(await drain(h, kidToken, [[20, true], 20, 14, 9]), ['charging 20', 'unplugged 20', 'very_low 14', 'critical 9']);
  // Charged full, then a whole drop again.
  assert.deepEqual(await drain(h, kidToken, [[100, true], 100, 24, 14, 9]), ['charging 100', 'low 24', 'very_low 14', 'critical 9']);
});

test('charged away from home: being well above a line counts as charged', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, ['Miguel']);
  assert.deepEqual(await drain(h, kidToken, [24]), ['low 24']);
  // A wobble of a point or two without a charger doesn't re-arm it.
  assert.deepEqual(await drain(h, kidToken, [26, 24]), []);
  // Next heard of at 80% (charged at a friend's house), then running down again.
  assert.deepEqual(await drain(h, kidToken, [80, 24]), ['low 24']);
});

test('the parent state shows the last report; bad reports are refused', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  const battery = async () => (await h.parent('GET', '/parent/state', { token: Miguel })).body.child.battery;

  assert.equal(await battery(), null, 'nothing reported yet');
  await report(h, kidToken, 57, true);
  h.clock.advance(5 * MINUTE);
  assert.deepEqual(await battery(), { percent: 57, charging: true, at: START });

  for (const body of [{ percent: 101, charging: false }, { percent: -1, charging: false }, { percent: 5.5, charging: false },
    { percent: '50', charging: false }, { percent: 50 }, { percent: 50, charging: 'no' }]) {
    const res = await h.kid('PUT', '/kid/battery', { token: kidToken, body });
    assert.equal(res.status, 400, JSON.stringify(body));
  }
  assert.equal((await h.kid('PUT', '/kid/battery', { body: { percent: 50, charging: false } })).status, 401);
  assert.equal((await h.parent('PUT', '/kid/battery', { token: Miguel, body: { percent: 50, charging: false } })).status, 404);
  assert.equal((await battery()).percent, 57);
});

test('what was alerted survives a hub restart', async (t) => {
  const first = await startHub();
  t.after(first.stop);
  const { kidToken } = await setupFamily(first, ['Miguel']);
  assert.deepEqual(await drain(first, kidToken, [14]), ['very_low 14']);
  await first.stop();
  assert.equal(JSON.parse(readFileSync(join(first.dataDir, 'state.json'), 'utf8')).battery.alerted, 15);

  const second = await startHub({ dataDir: first.dataDir });
  t.after(second.stop);
  assert.deepEqual(await drain(second, kidToken, [13, 12]), [], 'not alerted again');
  assert.deepEqual(await drain(second, kidToken, [9]), ['critical 9']);
});
