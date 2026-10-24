// What the hub knows and does: the child's phone, parent phones, apps, call requests, the kid state
// version (with long-poll waiters), timers, and FCM alerts. The route files translate HTTP to these calls.

import { createHash, randomBytes, randomInt } from 'node:crypto';
import { HttpError } from './http.js';

const MINUTE = 60_000;

export const LIMITS = {
  pendingMs: 5 * MINUTE, // a pending request expires after this
  pairingCodeMs: 10 * MINUTE,
  replaceKidMs: 10 * MINUTE,
  lockoutMs: 10 * MINUTE,
  maxWrongCodes: 5,
  finishedShownMs: 2 * MINUTE, // the child's phone still sees rejected/expired/cancelled this long
  keepFinished: 50, // finished requests kept in state.json
  parentFinishedShown: 20,
};

const ANSWER_MINUTES = [0, 10, 30, 60];
// An app can be switched on for a while: any whole number of minutes up to a day.
export const MAX_APP_MINUTES = 24 * 60;

// Battery alerts to the parents, most severe first: one alert per line as the battery drops below it.
export const BATTERY_LINES = [
  { below: 10, level: 'critical' },
  { below: 15, level: 'very_low' },
  { below: 25, level: 'low' },
];
// A line alerts again only after the phone has charged back up to it. A phone charged away from
// home isn't heard from while charging, so being this many points above a line also counts.
const BATTERY_REARM_POINTS = 3;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const MAX_ICON_SIDE = 192;

export const newToken = () => randomBytes(32).toString('base64url');
export const sha256 = (data) => createHash('sha256').update(data).digest('hex');
const newId = (prefix) => prefix + randomBytes(6).toString('hex');
const normalizeNumber = (n) => n.replace(/[^\d+*#]/g, '');

/** Decoded PNG, or null when it isn't a PNG of at most 192×192. */
function decodeIcon(base64) {
  const png = Buffer.from(base64, 'base64');
  if (png.length < 33 || png.length > 512 * 1024) return null;
  if (!png.subarray(0, 8).equals(PNG_SIGNATURE) || png.toString('latin1', 12, 16) !== 'IHDR') return null;
  const width = png.readUInt32BE(16);
  const height = png.readUInt32BE(20);
  return width >= 1 && height >= 1 && width <= MAX_ICON_SIDE && height <= MAX_ICON_SIDE ? png : null;
}

export function createHub({ store, clock, fcm = null, log = () => {} }) {
  const state = store.state;
  const waiters = new Set(); // long-polling GET /kid/state requests
  const sending = new Set(); // FCM sends in flight
  let parentsByHash = new Map();
  let timer = null;
  let kidFingerprint = null;
  let closed = false;

  const indexParents = () => {
    parentsByHash = new Map(state.parents.map((p) => [p.tokenHash, p]));
  };
  indexParents();

  const findRequest = (id) => {
    const request = state.requests.find((r) => r.id === id);
    if (!request) throw new HttpError(404, 'not_found', 'No such request');
    return request;
  };

  const secondsUntil = (t, now) => Math.max(0, Math.ceil((t - now) / 1000));
  const remainingSeconds = (r, now) => secondsUntil(r.approvedUntil, now);
  const isOpen = (r, now) => r.status === 'pending' || (r.status === 'approved' && r.approvedUntil > now);

  // ---------- the kid state answer and its version ----------

  function allowedPackages() {
    return Object.entries(state.apps)
      .filter(([, app]) => app.allowed)
      .map(([pkg]) => pkg)
      .sort();
  }

  /** Apps switched on for a while (allowedUntil set), as [package, app], by package. */
  function timedApps() {
    return Object.entries(state.apps)
      .filter(([, app]) => app.allowed && app.allowedUntil)
      .sort(([a], [b]) => a.localeCompare(b));
  }

  function appTimes(now) {
    return timedApps().map(([pkg, app]) => ({ package: pkg, remainingSeconds: secondsUntil(app.allowedUntil, now), minutes: app.minutes }));
  }

  function kidRequests(now) {
    return state.requests
      .filter((r) => isOpen(r, now) || (r.finishedAt !== null && now - r.finishedAt < LIMITS.finishedShownMs))
      .sort((a, b) => b.createdAt - a.createdAt);
  }

  function kidRequest(r, now) {
    const out = { id: r.id, number: r.number, status: r.status };
    if (r.status === 'approved') {
      out.remainingSeconds = remainingSeconds(r, now);
      out.minutes = r.minutes;
    }
    return out;
  }

  // Everything in the kid answer except the ticking remainingSeconds. When it changes, the version goes up.
  function fingerprint(now) {
    return JSON.stringify([
      allowedPackages(),
      timedApps().map(([pkg, app]) => [pkg, app.allowedUntil, app.minutes]),
      kidRequests(now).map((r) => [r.id, r.status, r.minutes, r.approvedUntil]),
    ]);
  }

  /** After any change: bump the version if the kid answer changed, save, wake long-polls, re-arm the timer. */
  function commit(dirty = true) {
    const fp = fingerprint(clock.now());
    const bumped = fp !== kidFingerprint;
    if (bumped) {
      kidFingerprint = fp;
      state.version += 1;
    }
    if (dirty || bumped) store.save();
    if (bumped) for (const w of [...waiters]) w.done();
    arm();
  }

  function expireDue(now) {
    let changed = false;
    for (const r of state.requests) {
      if (r.status === 'pending' && r.expiresAt <= now) {
        r.status = 'expired';
        r.finishedAt = r.expiresAt;
        changed = true;
        log(`request ${r.id} expired without an answer`);
        notifyParents(closedMessage(r));
      }
    }
    for (const [pkg, app] of timedApps()) {
      if (app.allowedUntil <= now) {
        switchApp(app, false, null, now);
        changed = true;
        log(`${pkg} switched off: its time is up`);
      }
    }
    return changed;
  }

  /** Switches an app on (for `minutes`, or with no time limit when null) or off. */
  function switchApp(app, allowed, minutes, now) {
    app.allowed = allowed;
    app.allowedUntil = allowed && minutes ? now + minutes * MINUTE : null;
    app.minutes = allowed && minutes ? minutes : null;
  }

  /** Brings everything up to date with the clock (in case a timer is late or the hub was off). */
  const refresh = () => commit(expireDue(clock.now()));

  // One timer for the next moment the kid answer changes by itself: an expiry, the end of an approved
  // window, or a finished request dropping off the child's list. Absolute times in state.json mean
  // this is simply recomputed after a restart.
  function arm() {
    if (timer) clock.clearTimer(timer);
    timer = null;
    if (closed) return;
    const now = clock.now();
    let next = Infinity;
    for (const r of state.requests) {
      if (r.status === 'pending') next = Math.min(next, r.expiresAt);
      else {
        const t = r.status === 'approved' ? r.approvedUntil : r.finishedAt + LIMITS.finishedShownMs;
        if (t > now) next = Math.min(next, t);
      }
    }
    for (const [, app] of timedApps()) next = Math.min(next, app.allowedUntil);
    if (next !== Infinity) {
      timer = clock.setTimer(() => {
        timer = null;
        try {
          refresh();
        } catch (err) {
          log(`timer error: ${err.message}`); // e.g. disk full; the next request retries the save
        }
      }, Math.max(0, next - now));
    }
  }

  function waitForChange(ms, signal) {
    return new Promise((resolve) => {
      if (signal?.aborted) return resolve();
      const waiter = {
        done() {
          if (!waiters.delete(waiter)) return;
          clock.clearTimer(waitTimer);
          signal?.removeEventListener('abort', waiter.done);
          resolve();
        },
      };
      const waitTimer = clock.setTimer(waiter.done, ms);
      signal?.addEventListener('abort', waiter.done);
      waiters.add(waiter);
    });
  }

  // ---------- FCM ----------

  function closedMessage(r) {
    const by = r.status === 'ended' ? r.endedBy : r.decidedBy;
    return { type: 'request_closed', id: r.id, status: r.status, minutes: String(r.minutes ?? 0), by: by ?? '' };
  }

  /** Sends a data message to every parent phone; returns how many were targeted. Never blocks the caller. */
  function notifyParents(data) {
    if (!fcm) return 0;
    const targets = state.parents.filter((p) => p.fcmToken);
    for (const parent of targets) {
      const token = parent.fcmToken;
      const what = `fcm ${data.type} ${data.id ?? data.level} to ${parent.name}`;
      const job = Promise.resolve()
        .then(() => fcm.send(token, data))
        .then((result) => {
          if (result.ok) return log(`${what}: sent`);
          const remove = result.unregistered && parent.fcmToken === token;
          log(`${what}: failed (${result.error})${remove ? ', token removed' : ''}`);
          if (remove) {
            parent.fcmToken = null;
            store.save();
          }
        })
        .catch((err) => log(`${what}: error (${err.message})`)) // never an unhandled rejection
        .finally(() => sending.delete(job));
      sending.add(job);
    }
    return targets.length;
  }

  // ---------- auth ----------

  function authKid(token, appVersion) {
    if (!token || !state.kid.tokenHash || sha256(token) !== state.kid.tokenHash) return null;
    state.kid.lastSeen = clock.now();
    if (appVersion !== null) state.kid.appVersion = appVersion;
    store.saveSoon();
    return { kind: 'kid' };
  }

  function authParent(token, appVersion) {
    const parent = token ? parentsByHash.get(sha256(token)) : undefined;
    if (!parent) return null;
    if (appVersion !== null && parent.appVersion !== appVersion) {
      parent.appVersion = appVersion;
      store.saveSoon();
    }
    return parent;
  }

  // ---------- kid side ----------

  function register({ childName, token, appVersion }) {
    const now = clock.now();
    const kid = state.kid;
    let how;
    if (!kid.tokenHash) how = 'first time';
    else if (typeof token === 'string' && sha256(token) === kid.tokenHash) how = 'again';
    else if (now < state.replaceKidUntil) how = 'as a replacement';
    else throw new HttpError(403, 'already_registered', 'This hub already has a child phone');

    if (how === 'as a replacement') state.replaceKidUntil = 0; // the window lets one phone in
    const newKidToken = newToken();
    kid.tokenHash = sha256(newKidToken);
    kid.registeredAt = now;
    kid.lastSeen = now;
    if (childName) kid.name = childName;
    if (appVersion !== null) kid.appVersion = appVersion;
    commit();
    log(`child phone registered ${how}${kid.name ? ` (${kid.name})` : ''}`);
    return { token: newKidToken };
  }

  const ping = () => ({ ok: true, hubId: state.hubId, time: clock.now() });

  function newPairingCode() {
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
    state.pairing.codeHash = sha256(code);
    state.pairing.expiresAt = clock.now() + LIMITS.pairingCodeMs;
    commit();
    log('pairing code issued (valid 10 minutes)');
    return { code, expiresInSeconds: LIMITS.pairingCodeMs / 1000 };
  }

  /** apps: [{ package, label, iconPng? }] already checked by the route. */
  function putApps({ childName, apps }) {
    const listed = new Set();
    let added = 0;
    let iconsSaved = 0;
    let badIcons = 0;
    for (const item of apps) {
      listed.add(item.package);
      let app = Object.hasOwn(state.apps, item.package) ? state.apps[item.package] : null;
      if (!app) {
        app = state.apps[item.package] = { label: item.label, allowed: false, installed: true, icon: null };
        added++;
      }
      app.label = item.label;
      app.installed = true;
      if (item.iconPng) {
        const png = decodeIcon(item.iconPng);
        if (!png) {
          badIcons++;
        } else {
          const hash = sha256(png).slice(0, 16);
          if (hash !== app.icon) {
            store.writeIcon(item.package, png);
            app.icon = hash;
            iconsSaved++;
          }
        }
      }
    }
    let gone = 0;
    for (const [pkg, app] of Object.entries(state.apps)) {
      if (!listed.has(pkg) && app.installed) {
        app.installed = false;
        gone++;
      }
    }
    if (childName) state.kid.name = childName;
    commit();
    log(`apps updated: ${listed.size} installed, ${added} new, ${gone} gone, ${iconsSaved} icons saved` +
      (badIcons ? `, ${badIcons} icons ignored (not a PNG up to ${MAX_ICON_SIDE}x${MAX_ICON_SIDE})` : ''));
    return { ok: true };
  }

  function currentVersion() {
    refresh();
    return state.version;
  }

  function kidState() {
    refresh();
    const now = clock.now();
    return {
      version: state.version,
      allowedPackages: allowedPackages(),
      appTimes: appTimes(now),
      requests: kidRequests(now).map((r) => kidRequest(r, now)),
    };
  }

  function createRequest({ contactName, number }) {
    refresh();
    const now = clock.now();
    const key = normalizeNumber(number);
    const open = state.requests.find((r) => isOpen(r, now) && normalizeNumber(r.number) === key);
    if (open) return kidRequest(open, now);

    const r = {
      id: newId('r_'),
      contactName,
      number,
      status: 'pending',
      createdAt: now,
      expiresAt: now + LIMITS.pendingMs,
      decidedBy: null,
      decidedAt: null,
      minutes: null,
      approvedUntil: null,
      finishedAt: null,
    };
    state.requests.push(r);
    prune(now);
    commit();
    const alerted = fcm ? state.parents.filter((p) => p.fcmToken).length : 0;
    log(`request ${r.id} created for ${contactName || 'an unnamed contact'}; alerting ${alerted} parent phone(s)${fcm ? '' : ' (alerts are off)'}`);
    notifyParents({
      type: 'call_request',
      id: r.id,
      childName: state.kid.name,
      contactName,
      number,
      expiresAt: String(r.expiresAt),
    });
    return kidRequest(r, now);
  }

  function cancelRequest(id) {
    refresh();
    const r = findRequest(id);
    if (r.status === 'pending') {
      r.status = 'cancelled';
      r.finishedAt = clock.now();
      commit();
      log(`request ${r.id} cancelled by the child's phone`);
      notifyParents(closedMessage(r));
    }
    return { ok: true };
  }

  /**
   * The child's phone reports its battery whenever the level or the plug changes (only possible on
   * the home Wi-Fi). Below 25%, 15% and 10% the parents get an alert, once per line, never while
   * it is plugged in. Plugging it in or out while an alert is out tells the parents too.
   */
  function reportBattery({ percent, charging }) {
    const now = clock.now();
    const before = state.battery;
    let alerted = before?.alerted ?? null; // the most severe line alerted in this drop, or null

    // Lines the phone has charged back up to can alert again.
    const rearmed = (below) => (charging && percent >= below) || percent >= below + BATTERY_REARM_POINTS;
    while (alerted !== null && rearmed(alerted)) {
      const next = BATTERY_LINES.find((l) => l.below > alerted);
      alerted = next ? next.below : null;
    }

    const line = charging ? null : BATTERY_LINES.find((l) => percent < l.below);
    const alert = line && (alerted === null || line.below < alerted) ? line : null;
    if (alert) alerted = alert.below;

    state.battery = { percent, charging, at: now, alerted };
    if (alerted !== (before?.alerted ?? null)) store.save(); // never alert twice for a line, even after a restart
    else store.saveSoon(); // only the reading changed

    const message = (level) => ({ type: 'battery', level, percent: String(percent), childName: state.kid.name });
    if (alert) {
      log(`battery at ${percent}%: ${alert.level} alert to the parents${fcm ? '' : ' (alerts are off)'}`);
      notifyParents(message(alert.level));
    } else if (before !== null && charging !== before.charging) {
      log(`battery at ${percent}%: ${charging ? 'plugged in' : 'unplugged'}`);
      // While an alert is out, the parents' notification follows the plug (quietly, in the app).
      if (before.alerted !== null) notifyParents(message(charging ? 'charging' : 'unplugged'));
    }
    return { ok: true };
  }

  function prune(now) {
    const finished = state.requests.filter((r) => !isOpen(r, now)).sort((a, b) => b.createdAt - a.createdAt);
    if (finished.length <= LIMITS.keepFinished) return;
    const drop = new Set(finished.slice(LIMITS.keepFinished));
    state.requests = state.requests.filter((r) => !drop.has(r));
  }

  // ---------- parent side ----------

  function pair({ code, name, fcmToken, appVersion }) {
    const now = clock.now();
    const p = state.pairing;
    if (now < p.lockedUntil) throw new HttpError(429, 'too_many_tries', 'Too many wrong codes. Try again in 10 minutes.');

    const right = typeof code === 'string' && p.codeHash !== null && now < p.expiresAt && sha256(code.trim()) === p.codeHash;
    if (!right) {
      p.wrongCodes += 1;
      if (p.wrongCodes >= LIMITS.maxWrongCodes) {
        p.wrongCodes = 0;
        p.lockedUntil = now + LIMITS.lockoutMs;
        store.save();
        log(`pairing locked for 10 minutes after ${LIMITS.maxWrongCodes} wrong codes`);
        throw new HttpError(429, 'too_many_tries', 'Too many wrong codes. Try again in 10 minutes.');
      }
      store.save();
      log(`pairing refused: wrong or expired code (${p.wrongCodes} of ${LIMITS.maxWrongCodes})`);
      throw new HttpError(400, 'bad_code', 'That code is wrong or has expired');
    }

    p.wrongCodes = 0;
    p.codeHash = null;
    p.expiresAt = 0;
    // The same phone pairing again (same FCM token) replaces its old entry instead of alerting twice.
    if (fcmToken) state.parents = state.parents.filter((x) => x.fcmToken !== fcmToken);
    const token = newToken();
    state.parents.push({ name, tokenHash: sha256(token), fcmToken: fcmToken || null, appVersion, pairedAt: now });
    indexParents();
    commit();
    log(`parent phone paired: ${name}${fcmToken ? '' : ' (no FCM token yet)'}`);
    return { token, childName: state.kid.name };
  }

  function setFcmToken(parent, fcmToken) {
    let changed = false;
    for (const p of state.parents) {
      const want = p === parent ? fcmToken : p.fcmToken === fcmToken ? null : p.fcmToken; // one phone, one entry
      if (p.fcmToken !== want) {
        p.fcmToken = want;
        changed = true;
      }
    }
    if (changed) {
      store.save();
      log(`FCM token updated for ${parent.name}`);
    }
    return { ok: true };
  }

  function parentRequest(r, now) {
    const out = {
      id: r.id,
      contactName: r.contactName,
      number: r.number,
      status: r.status,
      createdAt: r.createdAt,
      decidedBy: r.decidedBy,
      minutes: r.minutes,
    };
    if (r.status === 'approved') out.remainingSeconds = remainingSeconds(r, now);
    if (r.status === 'ended') out.endedBy = r.endedBy ?? null;
    return out;
  }

  function parentState(parent) {
    refresh();
    const now = clock.now();
    const apps = Object.entries(state.apps)
      .map(([pkg, a]) => {
        const out = { package: pkg, label: a.label, allowed: a.allowed, installed: a.installed, hasIcon: Boolean(a.icon) };
        if (a.allowed && a.allowedUntil) {
          out.minutes = a.minutes;
          out.remainingSeconds = secondsUntil(a.allowedUntil, now);
        }
        return out;
      })
      .sort((x, y) => Number(y.installed) - Number(x.installed) ||
        x.label.localeCompare(y.label, undefined, { sensitivity: 'base' }) ||
        x.package.localeCompare(y.package));
    const newestFirst = [...state.requests].sort((a, b) => b.createdAt - a.createdAt);
    const shown = new Set([
      ...newestFirst.filter((r) => r.status === 'pending'),
      ...newestFirst.filter((r) => r.status !== 'pending').slice(0, LIMITS.parentFinishedShown),
    ]);
    return {
      child: {
        name: state.kid.name,
        paired: state.kid.tokenHash !== null,
        lastSeen: state.kid.lastSeen,
        appVersion: state.kid.appVersion,
        battery: state.battery && { percent: state.battery.percent, charging: state.battery.charging, at: state.battery.at },
      },
      apps,
      requests: newestFirst.filter((r) => shown.has(r)).map((r) => parentRequest(r, now)),
      parents: state.parents.map((p) => ({ name: p.name, me: p === parent })),
      time: now,
    };
  }

  const readIcon = (pkg) => store.readIcon(pkg);

  /**
   * Switches an app on or off. On with `minutes` means for that long, counted from now (again
   * for an app already on: an hour can become ten minutes from now); without, no time limit.
   */
  function setAllowed(parent, pkg, allowed, minutes = null) {
    if (minutes !== null && !(Number.isInteger(minutes) && minutes >= 1 && minutes <= MAX_APP_MINUTES)) {
      throw new HttpError(400, 'bad_minutes', `minutes must be a whole number from 1 to ${MAX_APP_MINUTES}`);
    }
    refresh();
    const app = Object.hasOwn(state.apps, pkg) ? state.apps[pkg] : null;
    if (!app) throw new HttpError(404, 'not_found', 'No such app');
    const timed = allowed ? minutes : null;
    if (app.allowed !== allowed || timed !== null || (app.allowedUntil ?? null) !== null) {
      switchApp(app, allowed, timed, clock.now());
      commit();
      log(`${pkg} ${allowed ? (timed ? `allowed for ${timed} minutes` : 'allowed') : 'blocked'} by ${parent.name}`);
    }
    return { ok: true };
  }

  /**
   * Changes a call time that is running: 10, 30 or 60 more minutes from now, or 0 to stop it now
   * (status "ended"). The child's phone hears it through its long-poll.
   */
  function changeWindow(who, id, minutes) {
    if (!ANSWER_MINUTES.includes(minutes)) throw new HttpError(400, 'bad_minutes', 'minutes must be 0, 10, 30 or 60');
    refresh();
    const now = clock.now();
    const r = findRequest(id);
    if (!(r.status === 'approved' && r.approvedUntil > now)) {
      throw new HttpError(409, 'not_running', 'That call time is not running any more');
    }
    if (minutes === 0) {
      r.status = 'ended';
      r.endedBy = who.name;
      r.approvedUntil = now;
      r.finishedAt = now;
      log(`call time ${r.id} stopped by ${who.name}`);
    } else {
      r.minutes = minutes;
      r.approvedUntil = now + minutes * MINUTE;
      log(`call time ${r.id} changed to ${minutes} more minutes by ${who.name}`);
    }
    commit();
    if (minutes === 0) notifyParents(closedMessage(r));
    return { request: parentRequest(r, now) };
  }

  function answer(parent, id, minutes) {
    if (!ANSWER_MINUTES.includes(minutes)) throw new HttpError(400, 'bad_minutes', 'minutes must be 0, 10, 30 or 60');
    refresh();
    const now = clock.now();
    const r = findRequest(id);
    if (r.status !== 'pending') return { request: parentRequest(r, now), alreadyAnswered: true };

    r.decidedBy = parent.name;
    r.decidedAt = now;
    r.minutes = minutes;
    if (minutes === 0) {
      r.status = 'rejected';
      r.finishedAt = now;
    } else {
      r.status = 'approved';
      r.approvedUntil = now + minutes * MINUTE;
    }
    commit();
    log(`request ${r.id} ${minutes ? `approved for ${minutes} minutes` : 'rejected'} by ${parent.name}`);
    notifyParents(closedMessage(r));
    return { request: parentRequest(r, now), alreadyAnswered: false };
  }

  function replaceKid(parent) {
    state.replaceKidUntil = clock.now() + LIMITS.replaceKidMs;
    store.save();
    log(`${parent.name} allowed a new child phone to register in the next 10 minutes`);
    return { ok: true };
  }

  function unpair(parent) {
    state.parents = state.parents.filter((p) => p !== parent);
    indexParents();
    store.save();
    log(`parent phone removed: ${parent.name}`);
    return { ok: true };
  }

  // ---------- lifecycle ----------

  function start() {
    refresh(); // expires what ran out while the hub was off, bumps the version, arms the timer
  }

  function close() {
    closed = true;
    if (timer) clock.clearTimer(timer);
    timer = null;
    for (const w of [...waiters]) w.done();
    store.save();
  }

  /** Resolves when no FCM send is in flight (for tests). */
  async function idle() {
    while (sending.size) await Promise.allSettled([...sending]);
  }

  return {
    state,
    authKid,
    authParent,
    register,
    ping,
    newPairingCode,
    putApps,
    currentVersion,
    kidState,
    waitForChange,
    createRequest,
    cancelRequest,
    reportBattery,
    pair,
    setFcmToken,
    parentState,
    readIcon,
    setAllowed,
    answer,
    changeWindow,
    replaceKid,
    unpair,
    start,
    close,
    idle,
    waiterCount: () => waiters.size,
  };
}
