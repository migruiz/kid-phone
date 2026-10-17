// Tiny routing and JSON helpers on top of node:http.

export const KB = 1024;
export const MB = 1024 * KB;
const DEFAULT_BODY_LIMIT = 64 * KB;

export class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function sendJson(res, status, body, headers = {}) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(data),
    'cache-control': 'no-store',
    ...headers,
  });
  res.end(data);
}

/** Reads a JSON object body. An empty body counts as {}. */
export function readJson(req, limit) {
  return new Promise((resolve, reject) => {
    const tooLarge = () => new HttpError(413, 'too_large', `The body may be at most ${limit} bytes`);
    if (Number(req.headers['content-length']) > limit) return reject(tooLarge());
    const chunks = [];
    let size = 0;
    let failed = false;
    req.on('data', (chunk) => {
      if (failed) return;
      size += chunk.length;
      if (size > limit) {
        failed = true;
        return reject(tooLarge());
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (failed) return;
      const text = Buffer.concat(chunks).toString('utf8');
      if (text.trim() === '') return resolve({});
      let value;
      try {
        value = JSON.parse(text);
      } catch {
        return reject(new HttpError(400, 'bad_json', 'The body is not valid JSON'));
      }
      if (value === null || typeof value !== 'object' || Array.isArray(value)) {
        return reject(new HttpError(400, 'bad_json', 'The body must be a JSON object'));
      }
      resolve(value);
    });
    req.on('error', reject);
  });
}

// ---- small input checks used by the route files ----

export function optionalString(body, key, max) {
  const value = body[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string') throw new HttpError(400, 'bad_request', `"${key}" must be a string`);
  const trimmed = value.trim();
  if (trimmed.length > max) throw new HttpError(400, 'bad_request', `"${key}" is too long`);
  return trimmed;
}

export function requiredString(body, key, max) {
  const value = optionalString(body, key, max);
  if (!value) throw new HttpError(400, 'bad_request', `"${key}" is required`);
  return value;
}

// ---- routing ----

function matchPath(pattern, path) {
  const want = pattern.split('/');
  const got = path.split('/');
  if (want.length !== got.length) return null;
  const params = {};
  for (let i = 0; i < want.length; i++) {
    if (want[i].startsWith(':')) {
      try {
        params[want[i].slice(1)] = decodeURIComponent(got[i]);
      } catch {
        return null;
      }
      if (!params[want[i].slice(1)]) return null;
    } else if (want[i] !== got[i]) {
      return null;
    }
  }
  return params;
}

function bearerToken(req) {
  const m = /^Bearer\s+(\S+)\s*$/i.exec(req.headers.authorization ?? '');
  return m ? m[1] : null;
}

function appVersionOf(req) {
  const n = Number(req.headers['x-app-version']);
  return Number.isInteger(n) && n >= 0 ? n : null;
}

/**
 * routes: [{ method, path: '/kid/requests/:id/cancel', auth?: false, limit?: bytes, handle(ctx) }]
 * handle returns an object to send as 200 JSON, or undefined when it answered by itself.
 * authenticate(token, appVersion) returns who is calling, or null.
 * guard(req) returns a reason to refuse the request (403), or null.
 */
export function createHandler({ routes, authenticate, guard, log }) {
  return async function handle(req, res) {
    try {
      let url;
      try {
        url = new URL(req.url, 'http://hub');
      } catch {
        throw new HttpError(404, 'not_found', 'No such route');
      }
      const path = url.pathname;
      if (req.method === 'GET' && path === '/health') return sendJson(res, 200, { ok: true });

      const refusal = guard?.(req);
      if (refusal) throw new HttpError(403, 'forbidden', refusal);

      let route;
      let params;
      for (const r of routes) {
        if (r.method !== req.method) continue;
        params = matchPath(r.path, path);
        if (params) {
          route = r;
          break;
        }
      }
      if (!route) throw new HttpError(404, 'not_found', 'No such route');

      const ctx = { req, res, params, url, appVersion: appVersionOf(req) };
      if (route.auth !== false) {
        ctx.who = authenticate(bearerToken(req), ctx.appVersion);
        if (!ctx.who) throw new HttpError(401, 'unauthorized', 'Missing or wrong token');
      }
      ctx.body = req.method === 'POST' || req.method === 'PUT' ? await readJson(req, route.limit ?? DEFAULT_BODY_LIMIT) : {};

      const result = await route.handle(ctx);
      if (result !== undefined && !res.headersSent && !res.destroyed) sendJson(res, 200, result);
    } catch (err) {
      if (res.headersSent || res.destroyed) return res.destroy();
      // On 413, node discards the unread rest of the upload, so the client can still read the answer.
      if (err instanceof HttpError) return sendJson(res, err.status, { error: err.code, message: err.message });
      log(`error in ${req.method} ${req.url?.split('?')[0]}: ${err?.stack ?? err}`);
      sendJson(res, 500, { error: 'internal', message: 'Something went wrong on the hub' });
    }
  };
}
