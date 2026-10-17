import assert from 'node:assert/strict';
import { createVerify, generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { createFcmSender, loadServiceAccount } from '../src/fcm.js';
import { START, tempDir } from './helpers.js';

const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});

const account = {
  type: 'service_account',
  project_id: 'kid-phone-test',
  private_key_id: 'key-1',
  private_key: privateKey,
  client_email: 'hub@kid-phone-test.iam.gserviceaccount.com',
  token_uri: 'https://oauth2.googleapis.com/token',
};

const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

/** A stand-in for Google: never touches the network. sendAnswer(n) answers the n-th FCM send. */
function fakeGoogle(sendAnswer = () => json(200, { name: 'projects/kid-phone-test/messages/1' }), tokenAnswer = null) {
  const tokenCalls = [];
  const sendCalls = [];
  const fetch = async (url, init) => {
    if (url === 'https://oauth2.googleapis.com/token') {
      tokenCalls.push(init);
      return tokenAnswer ?? json(200, { access_token: `at-${tokenCalls.length}`, expires_in: 3600, token_type: 'Bearer' });
    }
    sendCalls.push({ url, init });
    return sendAnswer(sendCalls.length);
  };
  return { fetch, tokenCalls, sendCalls };
}

test('signs an RS256 JWT for Google and sends an FCM v1 data message', async () => {
  const google = fakeGoogle();
  const sender = createFcmSender(account, { fetch: google.fetch, now: () => START });
  assert.deepEqual(await sender.send('device-token', { type: 'call_request', id: 'r_1' }), { ok: true });

  const tokenCall = google.tokenCalls[0];
  assert.equal(tokenCall.method, 'POST');
  assert.equal(tokenCall.headers['content-type'], 'application/x-www-form-urlencoded');
  const form = new URLSearchParams(tokenCall.body);
  assert.equal(form.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  const [header, claims, signature] = form.get('assertion').split('.');
  assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'RS256', typ: 'JWT', kid: 'key-1' });
  assert.deepEqual(JSON.parse(Buffer.from(claims, 'base64url')), {
    iss: account.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: START / 1000,
    exp: START / 1000 + 3600,
  });
  assert.ok(createVerify('RSA-SHA256').update(`${header}.${claims}`).verify(publicKey, signature, 'base64url'));

  const send = google.sendCalls[0];
  assert.equal(send.url, 'https://fcm.googleapis.com/v1/projects/kid-phone-test/messages:send');
  assert.equal(send.init.headers.authorization, 'Bearer at-1');
  assert.deepEqual(JSON.parse(send.init.body), {
    message: { token: 'device-token', data: { type: 'call_request', id: 'r_1' }, android: { priority: 'high' } },
  });
});

test('the access token is cached until 5 minutes before it expires, and shared by concurrent sends', async () => {
  let now = START;
  const google = fakeGoogle();
  const sender = createFcmSender(account, { fetch: google.fetch, now: () => now });
  await Promise.all([sender.send('a', {}), sender.send('b', {}), sender.send('c', {})]);
  assert.equal(google.tokenCalls.length, 1);

  now += 55 * 60_000 - 1;
  await sender.send('a', {});
  assert.equal(google.tokenCalls.length, 1);
  now += 1;
  await sender.send('a', {});
  assert.equal(google.tokenCalls.length, 2);
  assert.equal(google.sendCalls.at(-1).init.headers.authorization, 'Bearer at-2');
});

test('404 UNREGISTERED is reported as unregistered; other errors are not', async () => {
  const unregistered = fakeGoogle(() =>
    json(404, {
      error: {
        code: 404,
        message: 'Requested entity was not found.',
        status: 'NOT_FOUND',
        details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'UNREGISTERED' }],
      },
    }));
  assert.deepEqual(await createFcmSender(account, { fetch: unregistered.fetch }).send('old', {}), {
    ok: false, status: 404, error: 'UNREGISTERED', unregistered: true,
  });

  const invalid = fakeGoogle(() => json(400, { error: { code: 400, status: 'INVALID_ARGUMENT', message: 'bad' } }));
  assert.deepEqual(await createFcmSender(account, { fetch: invalid.fetch }).send('x', {}), {
    ok: false, status: 400, error: 'INVALID_ARGUMENT', unregistered: false,
  });

  const outage = fakeGoogle(() => new Response('<html>oops</html>', { status: 503 }));
  assert.deepEqual(await createFcmSender(account, { fetch: outage.fetch }).send('x', {}), {
    ok: false, status: 503, error: 'HTTP 503', unregistered: false,
  });
});

test('a 401 gets a fresh access token and one retry, never a loop', async () => {
  const once = fakeGoogle((n) => (n === 1 ? json(401, { error: { status: 'UNAUTHENTICATED' } }) : json(200, {})));
  assert.deepEqual(await createFcmSender(account, { fetch: once.fetch }).send('x', {}), { ok: true });
  assert.equal(once.tokenCalls.length, 2);
  assert.equal(once.sendCalls[1].init.headers.authorization, 'Bearer at-2');

  const always = fakeGoogle(() => json(401, { error: { status: 'UNAUTHENTICATED' } }));
  const result = await createFcmSender(account, { fetch: always.fetch }).send('x', {});
  assert.equal(result.ok, false);
  assert.equal(result.status, 401);
  assert.equal(always.sendCalls.length, 2);
});

test('a refused token request makes the send fail', async () => {
  const google = fakeGoogle(undefined, json(400, { error: 'invalid_grant' }));
  await assert.rejects(createFcmSender(account, { fetch: google.fetch }).send('x', {}), /400 invalid_grant/);
  assert.equal(google.sendCalls.length, 0);
});

test('loadServiceAccount: missing, broken, incomplete and good keys', () => {
  const dir = tempDir();
  const logs = [];
  const log = (l) => logs.push(l);
  assert.equal(loadServiceAccount(join(dir, 'missing.json'), log), null);
  assert.equal(logs.length, 0, 'a missing key is normal; server.js logs that alerts are off');

  writeFileSync(join(dir, 'broken.json'), '{nope');
  assert.equal(loadServiceAccount(join(dir, 'broken.json'), log), null);
  writeFileSync(join(dir, 'partial.json'), JSON.stringify({ project_id: 'p' }));
  assert.equal(loadServiceAccount(join(dir, 'partial.json'), log), null);
  assert.equal(logs.length, 2);
  assert.ok(!logs.join().includes('PRIVATE KEY'));

  writeFileSync(join(dir, 'good.json'), JSON.stringify(account));
  assert.equal(loadServiceAccount(join(dir, 'good.json'), log).client_email, account.client_email);
});
