// Builds the hub and its two request handlers. server.js listens; tests use this directly.

import { realClock } from './clock.js';
import { createHandler } from './http.js';
import { createHub } from './hub.js';
import { createLogger } from './log.js';
import { kidRefusal } from './net.js';
import { kidRoutes } from './routes-kid.js';
import { parentRoutes } from './routes-parent.js';
import { openStore } from './store.js';

export function createApp({ dataDir, clock = realClock, fcm = null, log = createLogger() }) {
  const store = openStore(dataDir, { log });
  const hub = createHub({ store, clock, fcm, log });
  hub.start();
  return {
    hub,
    store,
    kidHandler: createHandler({ routes: kidRoutes(hub), authenticate: hub.authKid, guard: kidRefusal, log }),
    parentHandler: createHandler({ routes: parentRoutes(hub), authenticate: hub.authParent, log }),
    close: () => hub.close(),
  };
}
