// Firebase Cloud Messaging (HTTP v1) with node:crypto only: a service-account JWT is exchanged for
// an OAuth2 access token, cached until 5 minutes before it expires.

import { createSign } from 'node:crypto';
import { readFileSync } from 'node:fs';

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const REFRESH_MARGIN_MS = 5 * 60_000;
const TIMEOUT_MS = 10_000;

const base64url = (text) => Buffer.from(text).toString('base64url');

/** The service-account key, or null (with the reason logged) when it is missing or unusable. */
export function loadServiceAccount(path, log = () => {}) {
  let text;
  try {
    text = readFileSync(path, 'utf8');
  } catch (err) {
    if (err.code !== 'ENOENT') log(`cannot read Firebase key ${path}: ${err.code}`);
    return null;
  }
  try {
    const account = JSON.parse(text);
    if (account.project_id && account.client_email && account.private_key) return account;
    log(`Firebase key ${path} lacks project_id, client_email or private_key`);
  } catch {
    log(`Firebase key ${path} is not valid JSON`);
  }
  return null;
}

/**
 * send(token, data) resolves to { ok: true } or { ok: false, status, error, unregistered }.
 * It rejects only on network trouble. `fetch` and `now` are injectable for tests.
 */
export function createFcmSender(account, { fetch = globalThis.fetch, now = Date.now } = {}) {
  const tokenUrl = account.token_uri || TOKEN_URL;
  const sendUrl = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`;
  let cached = null; // { token, refreshAt }
  let inFlight = null;

  async function fetchAccessToken() {
    const iat = Math.floor(now() / 1000);
    const header = { alg: 'RS256', typ: 'JWT', ...(account.private_key_id && { kid: account.private_key_id }) };
    const claims = { iss: account.client_email, scope: SCOPE, aud: tokenUrl, iat, exp: iat + 3600 };
    const unsigned = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
    const signature = createSign('RSA-SHA256').update(unsigned).sign(account.private_key, 'base64url');
    const res = await fetch(tokenUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${unsigned}.${signature}`,
      }).toString(),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || !json.access_token) {
      throw new Error(`Google token endpoint answered ${res.status}${json.error ? ` ${json.error}` : ''}`);
    }
    cached = { token: json.access_token, refreshAt: now() + (json.expires_in ?? 3600) * 1000 - REFRESH_MARGIN_MS };
    return cached.token;
  }

  function accessToken() {
    if (cached && now() < cached.refreshAt) return Promise.resolve(cached.token);
    inFlight ??= fetchAccessToken().finally(() => {
      inFlight = null;
    });
    return inFlight;
  }

  async function send(token, data) {
    for (let attempt = 1; ; attempt++) {
      const res = await fetch(sendUrl, {
        method: 'POST',
        headers: { authorization: `Bearer ${await accessToken()}`, 'content-type': 'application/json' },
        body: JSON.stringify({ message: { token, data, android: { priority: 'high' } } }),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (res.ok) return { ok: true };
      const body = await res.json().catch(() => ({}));
      if (res.status === 401 && attempt === 1) {
        cached = null; // access token revoked or expired early: get a new one and try once more
        continue;
      }
      const errorCode = body.error?.details?.find((d) => d.errorCode)?.errorCode;
      const status = body.error?.status;
      return {
        ok: false,
        status: res.status,
        error: errorCode || status || `HTTP ${res.status}`,
        unregistered: res.status === 404 || errorCode === 'UNREGISTERED' || status === 'NOT_FOUND',
      };
    }
  }

  return { send };
}
