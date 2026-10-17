import assert from 'node:assert/strict';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { pngBytes, setupFamily, startHub } from './helpers.js';

const putApps = (h, token, body) => h.kid('PUT', '/kid/apps', { token, body });
const appsOf = async (h, token) => (await h.parent('GET', '/parent/state', { token })).body.apps;

test('apps missing from the list become installed:false and keep their switch', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  const icon = pngBytes(96, 96, 7);

  const first = await putApps(h, kidToken, {
    childName: 'Alex',
    apps: [
      { package: 'com.duolingo', label: 'Duolingo', iconPng: icon.toString('base64') },
      { package: 'org.khanacademy.android', label: 'Khan Academy' },
    ],
  });
  assert.deepEqual(first.body, { ok: true });
  assert.equal((await h.parent('GET', '/parent/state', { token: Miguel })).body.child.name, 'Alex');
  assert.deepEqual(await appsOf(h, Miguel), [
    { package: 'com.duolingo', label: 'Duolingo', allowed: false, installed: true, hasIcon: true },
    { package: 'org.khanacademy.android', label: 'Khan Academy', allowed: false, installed: true, hasIcon: false },
  ]);

  await h.parent('PUT', '/parent/apps/com.duolingo', { token: Miguel, body: { allowed: true } });
  await putApps(h, kidToken, { apps: [{ package: 'org.khanacademy.android', label: 'Khan Academy' }] });
  const duolingo = (await appsOf(h, Miguel)).find((a) => a.package === 'com.duolingo');
  assert.deepEqual(duolingo, { package: 'com.duolingo', label: 'Duolingo', allowed: true, installed: false, hasIcon: true });

  // It comes back: still allowed, icon still there.
  await putApps(h, kidToken, { apps: [{ package: 'com.duolingo', label: 'Duolingo' }] });
  const back = (await appsOf(h, Miguel)).find((a) => a.package === 'com.duolingo');
  assert.equal(back.installed, true);
  assert.equal(back.allowed, true);

  const png = await h.parent('GET', '/parent/apps/com.duolingo/icon', { token: Miguel });
  assert.equal(png.status, 200);
  assert.equal(png.headers.get('content-type'), 'image/png');
  assert.match(png.headers.get('cache-control'), /max-age=86400/);
  assert.deepEqual(png.body, icon, 'an app sent without iconPng keeps its earlier icon');
  assert.equal((await h.parent('GET', '/parent/apps/org.khanacademy.android/icon', { token: Miguel })).status, 404);
  assert.equal((await h.parent('GET', '/parent/apps/com.duolingo/icon')).status, 401);
});

test('a new icon replaces the old one; bad icons are ignored', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  await putApps(h, kidToken, { apps: [{ package: 'com.duolingo', label: 'Duolingo', iconPng: pngBytes(48, 48, 1).toString('base64') }] });
  const newer = pngBytes(64, 64, 2);
  await putApps(h, kidToken, { apps: [{ package: 'com.duolingo', label: 'Duolingo', iconPng: newer.toString('base64') }] });
  assert.deepEqual((await h.parent('GET', '/parent/apps/com.duolingo/icon', { token: Miguel })).body, newer);

  await putApps(h, kidToken, {
    apps: [
      { package: 'com.duolingo', label: 'Duolingo', iconPng: pngBytes(256, 256).toString('base64') }, // too big
      { package: 'com.example.notpng', label: 'Not a PNG', iconPng: Buffer.from('GIF89a...').toString('base64') },
    ],
  });
  assert.deepEqual((await h.parent('GET', '/parent/apps/com.duolingo/icon', { token: Miguel })).body, newer, 'kept');
  assert.equal((await appsOf(h, Miguel)).find((a) => a.package === 'com.example.notpng').hasIcon, false);
});

test('invalid package names are refused and never reach the icons folder', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  const iconPng = pngBytes().toString('base64');

  for (const bad of ['../../state', '..', 'com/../../x', 'nodots', 'com.', '.com', 'com..x', '1com.x', 'com.x/y', 'com.x\\y', '', 42, null]) {
    const res = await putApps(h, kidToken, { apps: [{ package: bad, label: 'Bad', iconPng }] });
    assert.equal(res.status, 400, `package ${JSON.stringify(bad)}`);
    assert.equal(res.body.error, 'bad_package');
  }
  assert.deepEqual(readdirSync(join(h.dataDir, 'icons')), []);
  assert.ok(!existsSync(join(h.dataDir, 'state.png')));
  assert.deepEqual(await appsOf(h, Miguel), []);

  assert.equal((await h.parent('GET', '/parent/apps/..%2F..%2Fstate.json/icon', { token: Miguel })).status, 404);
  assert.equal((await h.parent('GET', '/parent/apps/..%2Fstate/icon', { token: Miguel })).status, 404);
  assert.equal((await h.parent('PUT', '/parent/apps/com.unknown', { token: Miguel, body: { allowed: true } })).status, 404);
  assert.equal((await putApps(h, kidToken, { apps: 'nope' })).status, 400);
});

test('apps are sorted installed first, then by label', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  await putApps(h, kidToken, { apps: [{ package: 'com.a.gone', label: 'Aardvark' }] });
  await putApps(h, kidToken, {
    apps: [
      { package: 'com.z', label: 'zebra' },
      { package: 'com.b', label: 'Bee' },
      { package: 'com.nolabel' },
    ],
  });
  const labels = (await appsOf(h, Miguel)).map((a) => a.label);
  assert.deepEqual(labels, ['Bee', 'com.nolabel', 'zebra', 'Aardvark']);
});

test('request bodies are capped: 8 MB for the app list, 64 KB elsewhere', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);

  const big = await h.kid('POST', '/kid/requests', {
    token: kidToken,
    body: { contactName: 'x'.repeat(70 * 1024), number: '0871234567' },
  });
  assert.equal(big.status, 413);
  assert.equal(big.body.error, 'too_large');

  const apps = Array.from({ length: 300 }, (_, i) => ({
    package: `com.example.app${i}`,
    label: `App ${i}`,
    iconPng: pngBytes(48, 48, i % 250).toString('base64'),
  }));
  const overSmallLimit = await putApps(h, kidToken, { apps, pad: 'x'.repeat(70 * 1024) });
  assert.equal(overSmallLimit.status, 200);

  const huge = await h.kid('PUT', '/kid/apps', { token: kidToken, body: JSON.stringify({ apps: [], pad: 'x'.repeat(8 * 1024 * 1024) }) });
  assert.equal(huge.status, 413);

  const notJson = await h.kid('POST', '/kid/requests', { token: kidToken, body: '{oops' });
  assert.equal(notJson.status, 400);
  assert.equal(notJson.body.error, 'bad_json');
});
