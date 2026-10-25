// Port 8774: the child's phone and his PC, home network only (see net.js). Each has its own token,
// and each route takes only its own (kind 'kid' for the phone, 'pc' for the PC).

import { HttpError, MB, appSwitch, optionalString, requiredString } from './http.js';
import { isValidPackage, isValidPcAppId } from './store.js';

function checkApps(body) {
  if (!Array.isArray(body.apps)) throw new HttpError(400, 'bad_request', '"apps" must be a list');
  const byPackage = new Map(); // a package listed twice: the last one wins
  for (const item of body.apps) {
    if (item === null || typeof item !== 'object') throw new HttpError(400, 'bad_request', 'Each app must be an object');
    if (!isValidPackage(item.package)) {
      throw new HttpError(400, 'bad_package', `Not a valid Android package name: ${String(item.package).slice(0, 80)}`);
    }
    const label = typeof item.label === 'string' && item.label.trim() ? item.label.trim().slice(0, 100) : item.package;
    const iconPng = typeof item.iconPng === 'string' && item.iconPng ? item.iconPng : undefined;
    byPackage.set(item.package, { package: item.package, label, iconPng });
  }
  return [...byPackage.values()];
}

function checkPcApps(body) {
  if (!Array.isArray(body.apps)) throw new HttpError(400, 'bad_request', '"apps" must be a list');
  const byId = new Map(); // an id listed twice: the last one wins
  for (const item of body.apps) {
    if (item === null || typeof item !== 'object') throw new HttpError(400, 'bad_request', 'Each app must be an object');
    if (!isValidPcAppId(item.id)) {
      throw new HttpError(400, 'bad_id', `Not a valid app id (lower case letters, digits, dots and dashes): ${String(item.id).slice(0, 80)}`);
    }
    const label = typeof item.label === 'string' && item.label.trim() ? item.label.trim().slice(0, 100) : item.id;
    const iconPng = typeof item.iconPng === 'string' && item.iconPng ? item.iconPng : undefined;
    byId.set(item.id, { id: item.id, label, iconPng });
  }
  return [...byId.values()];
}

export function kidRoutes(hub) {
  /** A state answer, held back (long-poll) while `since` is the current version, up to `wait` seconds. */
  const longPoll = (answer) => async ({ url, res }) => {
    const params = url.searchParams;
    const wait = Math.min(30, Math.max(0, Math.floor(Number(params.get('wait')) || 0)));
    if (wait > 0 && params.has('since') && Number(params.get('since')) === hub.currentVersion()) {
      const gone = new AbortController();
      const onClose = () => gone.abort();
      res.on('close', onClose); // the phone hung up (or lost Wi-Fi) while we were holding the request
      await hub.waitForChange(wait * 1000, gone.signal);
      res.off('close', onClose);
      if (gone.signal.aborted) return undefined;
    }
    return answer();
  };

  const phone = [
    {
      method: 'POST',
      path: '/kid/register',
      auth: false,
      handle: ({ body, appVersion }) =>
        hub.register({ childName: optionalString(body, 'childName', 40), token: body.token, appVersion }),
    },
    { method: 'GET', path: '/kid/ping', handle: () => hub.ping() },
    { method: 'POST', path: '/kid/pairing-code', handle: () => hub.newPairingCode() },
    {
      method: 'PUT',
      path: '/kid/apps',
      limit: 8 * MB,
      handle: ({ body }) => hub.putApps({ childName: optionalString(body, 'childName', 40), apps: checkApps(body) }),
    },
    {
      // A parent switching an app on or off in Kid Phone's own parent menu (behind its PIN).
      method: 'PUT',
      path: '/kid/apps/:package',
      handle: ({ params, body }) => {
        const { allowed, minutes } = appSwitch(body);
        return hub.setAllowed({ name: "the child's phone" }, params.package, allowed, minutes);
      },
    },
    { method: 'GET', path: '/kid/state', handle: longPoll(hub.kidState) },
    {
      method: 'POST',
      path: '/kid/requests',
      handle: ({ body }) =>
        hub.createRequest({ contactName: optionalString(body, 'contactName', 80) ?? '', number: requiredString(body, 'number', 40) }),
    },
    { method: 'POST', path: '/kid/requests/:id/cancel', handle: ({ params }) => hub.cancelRequest(params.id) },
    {
      method: 'PUT',
      path: '/kid/battery',
      handle: ({ body }) => {
        const { percent, charging } = body;
        if (!Number.isInteger(percent) || percent < 0 || percent > 100) {
          throw new HttpError(400, 'bad_request', '"percent" must be a whole number from 0 to 100');
        }
        if (typeof charging !== 'boolean') throw new HttpError(400, 'bad_request', '"charging" must be true or false');
        return hub.reportBattery({ percent, charging });
      },
    },
    {
      // A parent who is with the child answers on the child's phone, after typing its PIN there.
      method: 'POST',
      path: '/kid/requests/:id/answer',
      handle: ({ params, body }) => hub.answer({ name: "A parent on the child's phone" }, params.id, body.minutes),
    },
    {
      // The same parent changes or stops a running call time (behind the PIN on the child's phone).
      method: 'POST',
      path: '/kid/requests/:id/change',
      handle: ({ params, body }) => hub.changeWindow({ name: "A parent on the child's phone" }, params.id, body.minutes),
    },
  ];

  const pc = [
    {
      method: 'POST',
      path: '/pc/register',
      auth: false,
      handle: ({ body, appVersion }) => hub.registerPc({ token: body.token, appVersion }),
    },
    {
      method: 'PUT',
      path: '/pc/apps',
      limit: 2 * MB,
      handle: ({ body }) => hub.putPcApps({ apps: checkPcApps(body) }),
    },
    { method: 'GET', path: '/pc/state', handle: longPoll(hub.pcState) },
  ];

  return [...phone.map((r) => ({ kind: 'kid', ...r })), ...pc.map((r) => ({ kind: 'pc', ...r }))];
}
