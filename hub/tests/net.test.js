import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';
import { hasCloudflareHeaders, isPrivateAddress, kidRefusal } from '../src/net.js';
import { setupFamily, startHub } from './helpers.js';

test('private addresses are recognised, including IPv4-mapped IPv6', () => {
  const ok = [
    '192.168.0.20', '192.168.255.255', '10.0.0.1', '10.255.255.255', '172.16.0.1', '172.31.255.255',
    '127.0.0.1', '127.8.9.10', '::1', '0:0:0:0:0:0:0:1', 'fc00::1', 'fd12:3456::1', 'FD00::ABCD',
    '::ffff:192.168.0.20', '::FFFF:10.1.2.3', '::ffff:127.0.0.1', '::ffff:172.20.0.5',
    '::ffff:c0a8:14', '0:0:0:0:0:ffff:192.168.0.20', 'fd00::1%eth0',
  ];
  for (const address of ok) assert.equal(isPrivateAddress(address), true, address);
});

test('public, link-local and malformed addresses are refused', () => {
  const refused = [
    '8.8.8.8', '1.1.1.1', '172.15.255.255', '172.32.0.1', '192.169.0.1', '11.0.0.1', '100.64.0.1',
    '169.254.1.1', '0.0.0.0', '::ffff:8.8.8.8', '::ffff:172.32.0.1', '::ffff:808:808', '2001:db8::1',
    '2a02:8084:2:3::1', 'fe80::1', 'fe80::1%eth0', 'fb00::1', 'fe00::1', '::', '::2', '::192.168.0.20',
    '::ffff:1:192.168.0.20', '256.1.1.1', '192.168.0', '192.168.0.1.5', '192.168.00.1x', '::ffff:999.1.1.1',
    '1::2::3', 'fc00::1::', '1:2:3:4:5:6:7:8:9', 'localhost', '', undefined, null,
  ];
  for (const address of refused) assert.equal(isPrivateAddress(address), false, String(address));
});

test('Cloudflare headers are detected', () => {
  assert.equal(hasCloudflareHeaders({}), false);
  assert.equal(hasCloudflareHeaders({ 'x-forwarded-for': '8.8.8.8' }), false);
  assert.equal(hasCloudflareHeaders({ 'cf-ray': '8a1b2c3d4e5f-DUB' }), true);
  assert.equal(hasCloudflareHeaders({ 'cf-connecting-ip': '203.0.113.5' }), true);
  assert.equal(hasCloudflareHeaders({ 'cf-ipcountry': 'IE' }), true);
  assert.equal(hasCloudflareHeaders({ 'cdn-loop': 'cloudflare' }), true);
});

test('kidRefusal checks both the source address and Cloudflare headers', () => {
  const req = (remoteAddress, headers = {}) => ({ headers, socket: { remoteAddress } });
  assert.equal(kidRefusal(req('::ffff:192.168.0.20')), null);
  assert.equal(kidRefusal(req('192.168.0.20')), null);
  assert.match(kidRefusal(req('::ffff:192.168.0.20', { 'cf-ray': 'x' })), /internet/);
  assert.match(kidRefusal(req('192.168.0.20', { 'cf-connecting-ip': '203.0.113.5' })), /internet/);
  assert.match(kidRefusal(req('203.0.113.5')), /home network/);
  assert.match(kidRefusal(req('::ffff:203.0.113.5')), /home network/);
  assert.match(kidRefusal(req(undefined)), /home network/); // socket already gone
});

/** Drives a handler with a fake request so we can pretend to come from any address. */
function fakeExchange(handler, { method = 'GET', url, headers = {}, remoteAddress }) {
  return new Promise((resolve) => {
    const req = Object.assign(new EventEmitter(), { method, url, headers, socket: { remoteAddress } });
    const res = {
      headersSent: false,
      destroyed: false,
      writeHead(status) {
        this.status = status;
        this.headersSent = true;
      },
      end(data) {
        resolve({ status: this.status, body: JSON.parse(data) });
      },
      destroy() {},
    };
    handler(req, res);
  });
}

test('the kid port refuses public source addresses before anything else', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken } = await setupFamily(h, []);
  const handler = h.app.kidHandler;
  const auth = { authorization: `Bearer ${kidToken}` };

  const fromInternet = await fakeExchange(handler, { url: '/kid/ping', headers: auth, remoteAddress: '::ffff:203.0.113.5' });
  assert.equal(fromInternet.status, 403);
  assert.equal(fromInternet.body.error, 'forbidden');

  const fromPublicV6 = await fakeExchange(handler, { url: '/kid/state', headers: auth, remoteAddress: '2a02:8084::1' });
  assert.equal(fromPublicV6.status, 403);

  const fromHome = await fakeExchange(handler, { url: '/kid/ping', headers: auth, remoteAddress: '::ffff:192.168.0.20' });
  assert.equal(fromHome.status, 200);
  assert.equal(fromHome.body.ok, true);

  // Health stays open for Docker; the parent port never applies the address rule.
  assert.equal((await fakeExchange(handler, { url: '/health', remoteAddress: '203.0.113.5' })).status, 200);
  assert.equal((await fakeExchange(h.app.parentHandler, { url: '/parent/state', remoteAddress: '203.0.113.5' })).status, 401);
});

test('kid routes refuse requests that came through Cloudflare', async (t) => {
  const h = await startHub();
  t.after(h.stop);

  const viaTunnel = await h.kid('POST', '/kid/register', { body: { childName: 'Mallory' }, headers: { 'cf-connecting-ip': '203.0.113.5' } });
  assert.equal(viaTunnel.status, 403);
  assert.equal(viaTunnel.body.error, 'forbidden');
  assert.equal(h.hub.state.kid.tokenHash, null, 'nobody got registered');

  const { kidToken } = await setupFamily(h, []);
  const ray = await h.kid('GET', '/kid/ping', { token: kidToken, headers: { 'cf-ray': '8a1b2c3d4e5f-DUB' } });
  assert.equal(ray.status, 403);
  assert.equal((await h.kid('GET', '/kid/ping', { token: kidToken })).status, 200);
});

test('each port serves only its own routes', async (t) => {
  const h = await startHub();
  t.after(h.stop);
  const { kidToken, Miguel } = await setupFamily(h, ['Miguel']);
  assert.equal((await h.parent('GET', '/kid/ping', { token: kidToken })).status, 404);
  assert.equal((await h.kid('GET', '/parent/state', { token: Miguel })).status, 404);
  assert.equal((await h.kid('GET', '/nope')).status, 404);
  assert.deepEqual((await h.kid('GET', '/health')).body, { ok: true });
  assert.deepEqual((await h.parent('GET', '/health')).body, { ok: true });
});
