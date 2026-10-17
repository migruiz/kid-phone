// Port 8775: the parents' phones, reached from the internet through the Cloudflare tunnel.

import { HttpError, optionalString, requiredString } from './http.js';

export function parentRoutes(hub) {
  function sendIcon({ res, params }) {
    const png = hub.readIcon(params.package);
    if (!png) throw new HttpError(404, 'not_found', 'No icon for that app');
    // "private" so Cloudflare (or any shared cache) never keeps this authenticated answer.
    res.writeHead(200, { 'content-type': 'image/png', 'content-length': png.length, 'cache-control': 'private, max-age=86400' });
    res.end(png);
    return undefined;
  }

  return [
    {
      method: 'POST',
      path: '/parent/pair',
      auth: false,
      handle: ({ body, appVersion }) => {
        const code = typeof body.code === 'number' ? String(body.code) : body.code;
        return hub.pair({
          code,
          name: requiredString(body, 'name', 40),
          fcmToken: optionalString(body, 'fcmToken', 4096),
          appVersion,
        });
      },
    },
    {
      method: 'PUT',
      path: '/parent/fcm',
      handle: ({ who, body }) => hub.setFcmToken(who, requiredString(body, 'fcmToken', 4096)),
    },
    { method: 'GET', path: '/parent/state', handle: ({ who }) => hub.parentState(who) },
    { method: 'GET', path: '/parent/apps/:package/icon', handle: sendIcon },
    {
      method: 'PUT',
      path: '/parent/apps/:package',
      handle: ({ who, params, body }) => {
        if (typeof body.allowed !== 'boolean') throw new HttpError(400, 'bad_request', '"allowed" must be true or false');
        return hub.setAllowed(who, params.package, body.allowed);
      },
    },
    {
      method: 'POST',
      path: '/parent/requests/:id/answer',
      handle: ({ who, params, body }) => hub.answer(who, params.id, body.minutes),
    },
    { method: 'POST', path: '/parent/replace-kid', handle: ({ who }) => hub.replaceKid(who) },
    { method: 'DELETE', path: '/parent/me', handle: ({ who }) => hub.unpair(who) },
  ];
}
