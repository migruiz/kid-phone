// State in DATA_DIR/state.json (atomic writes), icons as DATA_DIR/icons/<package>.png.

import { closeSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { randomBytes } from 'node:crypto';

// Android package names: two or more dot-separated parts, each starting with a letter. Checking this
// before using a package as a file name keeps paths inside the icons folder.
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

export function isValidPackage(name) {
  return typeof name === 'string' && name.length <= 255 && PACKAGE_NAME.test(name);
}

function freshState() {
  return {
    schema: 1,
    hubId: 'hub_' + randomBytes(8).toString('hex'),
    version: 0,
    kid: { tokenHash: null, name: '', lastSeen: null, appVersion: null, registeredAt: null },
    replaceKidUntil: 0,
    pairing: { codeHash: null, expiresAt: 0, wrongCodes: 0, lockedUntil: 0 },
    parents: [], // { name, tokenHash, fcmToken, appVersion, pairedAt }
    apps: {}, // package -> { label, allowed, installed, icon (short hash of the PNG) or null }
    requests: [], // see hub.js
  };
}

/** Write to a temp file, fsync it, then rename over the target, so a crash leaves the old or the new file. */
function writeAtomic(file, data, durable) {
  const temp = file + '.tmp';
  const fd = openSync(temp, 'w');
  try {
    writeFileSync(fd, data);
    if (durable) fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  renameSync(temp, file);
  if (durable) {
    // Make the rename itself durable (Linux). Opening a directory fails on Windows; that's fine.
    try {
      const dirFd = openSync(dirname(file), 'r');
      try {
        fsyncSync(dirFd);
      } finally {
        closeSync(dirFd);
      }
    } catch {}
  }
}

function load(file) {
  let text;
  try {
    text = readFileSync(file, 'utf8');
  } catch (err) {
    if (err.code === 'ENOENT') return null;
    throw err;
  }
  try {
    return JSON.parse(text);
  } catch {
    // Atomic writes make this very unlikely. Refuse to start rather than silently forget every pairing.
    throw new Error(`${file} is not valid JSON. Move it aside to start with an empty hub.`);
  }
}

export function openStore(dataDir, { log = () => {} } = {}) {
  const file = join(dataDir, 'state.json');
  const iconsDir = join(dataDir, 'icons');
  mkdirSync(iconsDir, { recursive: true });

  const saved = load(file);
  const fresh = freshState();
  const state = saved
    ? { ...fresh, ...saved, kid: { ...fresh.kid, ...saved.kid }, pairing: { ...fresh.pairing, ...saved.pairing } }
    : fresh;

  let lazyTimer = null;

  function save() {
    if (lazyTimer) {
      clearTimeout(lazyTimer);
      lazyTimer = null;
    }
    writeAtomic(file, JSON.stringify(state, null, 1), true);
  }

  /** For unimportant changes (last seen, app versions): save within `ms`, sparing the SD card. */
  function saveSoon(ms = 60_000) {
    if (lazyTimer) return;
    lazyTimer = setTimeout(() => {
      lazyTimer = null;
      try {
        save();
      } catch (err) {
        log(`could not save state: ${err.message}`);
      }
    }, ms);
    lazyTimer.unref();
  }

  const iconFile = (pkg) => (isValidPackage(pkg) ? join(iconsDir, `${pkg}.png`) : null);

  function writeIcon(pkg, png) {
    const target = iconFile(pkg);
    if (!target) throw new Error('invalid package name');
    writeAtomic(target, png, false); // icons are cosmetic; skip the fsync cost on the SD card
  }

  function readIcon(pkg) {
    const target = iconFile(pkg);
    if (!target) return null;
    try {
      return readFileSync(target);
    } catch (err) {
      if (err.code === 'ENOENT') return null;
      throw err;
    }
  }

  if (!saved) save(); // keep the new hubId from the first start
  return { state, file, save, saveSoon, writeIcon, readIcon };
}
