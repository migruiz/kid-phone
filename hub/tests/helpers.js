// Shared test setup: a fake clock, a fake FCM sender, and a hub listening on random local ports.

import { mkdtempSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/app.js';

export const START = Date.UTC(2026, 9, 17, 10, 0, 0);
export const MINUTE = 60_000;

/** A clock that only moves when told to; advance() fires due timers in order. */
export function fakeClock(start = START) {
  let now = start;
  let nextId = 1;
  const timers = new Map();
  return {
    now: () => now,
    setTimer(fn, ms) {
      const id = nextId++;
      timers.set(id, { at: now + Math.max(0, ms), fn });
      return id;
    },
    clearTimer(id) {
      timers.delete(id);
    },
    advance(ms) {
      const target = now + ms;
      for (;;) {
        let dueId = null;
        for (const [id, t] of timers) {
          if (t.at <= target && (dueId === null || t.at < timers.get(dueId).at)) dueId = id;
        }
        if (dueId === null) break;
        const { at, fn } = timers.get(dueId);
        timers.delete(dueId);
        now = Math.max(now, at);
        fn();
      }
      now = target;
    },
  };
}

/** Records every send. failWith(token, result) makes sends to that token fail. */
export function fakeFcm() {
  const calls = [];
  const failures = new Map();
  return {
    calls,
    failWith: (token, result) => failures.set(token, result),
    send: async (token, data) => {
      calls.push({ token, data });
      return failures.get(token) ?? { ok: true };
    },
  };
}

export const tempDir = () => mkdtempSync(join(tmpdir(), 'kidhub-'));

/** Bytes that pass the hub's PNG check (signature + IHDR with the given size). */
export function pngBytes(width = 48, height = 48, seed = 1) {
  const buf = Buffer.alloc(64, seed);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(buf, 0);
  buf.writeUInt32BE(13, 8);
  buf.write('IHDR', 12, 'latin1');
  buf.writeUInt32BE(width, 16);
  buf.writeUInt32BE(height, 20);
  return buf;
}

function client(base) {
  return async (method, path, { token, body, headers = {}, signal } = {}) => {
    const res = await fetch(base + path, {
      method,
      signal,
      headers: {
        ...(token && { authorization: `Bearer ${token}` }),
        ...(body !== undefined && { 'content-type': 'application/json' }),
        ...headers,
      },
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    });
    const isJson = (res.headers.get('content-type') ?? '').includes('json');
    return { status: res.status, headers: res.headers, body: isJson ? await res.json() : Buffer.from(await res.arrayBuffer()) };
  };
}

function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
}

export async function startHub({ dataDir = tempDir(), clock = fakeClock(), fcm = fakeFcm() } = {}) {
  const logs = [];
  const app = createApp({ dataDir, clock, fcm, log: (line) => logs.push(line) });
  const kidServer = createServer(app.kidHandler);
  const parentServer = createServer(app.parentHandler);
  await listen(kidServer);
  await listen(parentServer);
  let stopped = false;
  return {
    app,
    hub: app.hub,
    clock,
    fcm,
    dataDir,
    logs,
    kid: client(`http://127.0.0.1:${kidServer.address().port}`),
    parent: client(`http://127.0.0.1:${parentServer.address().port}`),
    async stop() {
      if (stopped) return;
      stopped = true;
      app.close();
      for (const s of [kidServer, parentServer]) {
        s.closeAllConnections();
        await new Promise((resolve) => s.close(resolve));
      }
    },
  };
}

/** Registers the child's phone and pairs parent phones. Returns their tokens. */
export async function setupFamily(h, parentNames = ['Miguel', 'Sonia']) {
  const reg = await h.kid('POST', '/kid/register', { body: { childName: 'Alejandro' }, headers: { 'x-app-version': '10100' } });
  const kidToken = reg.body.token;
  const parents = {};
  for (const name of parentNames) {
    const { body } = await h.kid('POST', '/kid/pairing-code', { token: kidToken });
    const paired = await h.parent('POST', '/parent/pair', { body: { code: body.code, name, fcmToken: `fcm-${name}` } });
    parents[name] = paired.body.token;
  }
  return { kidToken, ...parents };
}

/** Polls until fn() is true (for things that happen on another connection). */
export async function until(fn, ms = 2000) {
  const end = Date.now() + ms;
  while (!fn()) {
    if (Date.now() > end) throw new Error('timed out waiting');
    await new Promise((r) => setTimeout(r, 5));
  }
}
