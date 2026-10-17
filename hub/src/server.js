// Entry point: reads the environment, opens the store, listens on the kid and parent ports.

import { createServer } from 'node:http';
import { join, resolve } from 'node:path';
import { createApp } from './app.js';
import { createFcmSender, loadServiceAccount } from './fcm.js';
import { createLogger } from './log.js';

const log = createLogger();
const dataDir = resolve(process.env.DATA_DIR || '/data');
const kidPort = Number(process.env.KID_PORT || 8774);
const parentPort = Number(process.env.PARENT_PORT || 8775);
const keyFile = process.env.FIREBASE_KEY_FILE || join(dataDir, 'secrets', 'firebase.json');

const account = loadServiceAccount(keyFile, log);
const fcm = account ? createFcmSender(account) : null;
log(account ? `alerts on (Firebase project ${account.project_id})` : `alerts are off: no usable Firebase key at ${keyFile}`);

const app = createApp({ dataDir, fcm, log });

function listen(handler, port, name) {
  const server = createServer(handler);
  // Longer than cloudflared's 90 s idle keep-alive, so the hub never closes a connection the tunnel
  // is about to reuse (which shows up as a random 502).
  server.keepAliveTimeout = 100_000;
  server.headersTimeout = 105_000;
  server.requestTimeout = 120_000;
  server.listen(port, () => log(`${name} port ${port} ready`));
  return server;
}

const servers = [listen(app.kidHandler, kidPort, 'kid'), listen(app.parentHandler, parentPort, 'parent')];
log(`hub ${app.hub.state.hubId} started, data in ${dataDir}`);

let stopping = false;
function stop(signal) {
  if (stopping) return;
  stopping = true;
  log(`${signal}: shutting down`);
  try {
    app.close(); // answers waiting long-polls and saves state
  } catch (err) {
    log(`could not save state on shutdown: ${err.message}`);
  }
  let open = servers.length;
  for (const server of servers) {
    server.close(() => {
      if (--open === 0) process.exit(0);
    });
    server.closeIdleConnections();
  }
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGTERM', () => stop('SIGTERM'));
process.on('SIGINT', () => stop('SIGINT'));
