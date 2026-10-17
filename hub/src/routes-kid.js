// Port 8774: the child's phone, home network only (see net.js).

import { HttpError, MB, optionalString, requiredString } from './http.js';
import { isValidPackage } from './store.js';

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

export function kidRoutes(hub) {
  async function getState({ url, res }) {
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
    return hub.kidState();
  }

  return [
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
        if (typeof body.allowed !== 'boolean') throw new HttpError(400, 'bad_request', '"allowed" must be true or false');
        return hub.setAllowed({ name: "the child's phone" }, params.package, body.allowed);
      },
    },
    { method: 'GET', path: '/kid/state', handle: getState },
    {
      method: 'POST',
      path: '/kid/requests',
      handle: ({ body }) =>
        hub.createRequest({ contactName: optionalString(body, 'contactName', 80) ?? '', number: requiredString(body, 'number', 40) }),
    },
    { method: 'POST', path: '/kid/requests/:id/cancel', handle: ({ params }) => hub.cancelRequest(params.id) },
  ];
}
