// Port 8775: the parents' phones, reached from the internet through the Cloudflare tunnel.

import { HttpError, appSwitch, optionalString, requiredString } from './http.js';

export function parentRoutes(hub) {
  const sendIcon = (device) => ({ res, params }) => {
    const png = hub.readIcon(params.id, device);
    if (!png) throw new HttpError(404, 'not_found', 'No icon for that app');
    // "private" so Cloudflare (or any shared cache) never keeps this authenticated answer.
    res.writeHead(200, { 'content-type': 'image/png', 'content-length': png.length, 'cache-control': 'private, max-age=86400' });
    res.end(png);
    return undefined;
  };

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
    { method: 'GET', path: '/parent/apps/:id/icon', handle: sendIcon('phone') },
    {
      method: 'PUT',
      path: '/parent/apps/:id',
      handle: ({ who, params, body }) => {
        const { allowed, minutes } = appSwitch(body);
        return hub.setAllowed(who, params.id, allowed, minutes);
      },
    },
    // The child's PC: the same switches and icons for its apps.
    { method: 'GET', path: '/parent/pc/apps/:id/icon', handle: sendIcon('pc') },
    {
      method: 'PUT',
      path: '/parent/pc/apps/:id',
      handle: ({ who, params, body }) => {
        const { allowed, minutes } = appSwitch(body);
        return hub.setAllowed(who, params.id, allowed, minutes, 'pc');
      },
    },
    { method: 'POST', path: '/parent/replace-pc', handle: ({ who }) => hub.replacePc(who) },
    {
      method: 'POST',
      path: '/parent/requests/:id/answer',
      handle: ({ who, params, body }) => hub.answer(who, params.id, body.minutes),
    },
    {
      method: 'POST',
      path: '/parent/requests/:id/change',
      handle: ({ who, params, body }) => hub.changeWindow(who, params.id, body.minutes),
    },
    { method: 'POST', path: '/parent/replace-kid', handle: ({ who }) => hub.replaceKid(who) },
    { method: 'DELETE', path: '/parent/me', handle: ({ who }) => hub.unpair(who) },
  ];
}
