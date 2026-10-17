# Kid Phone hub

A small Node.js service that runs in Docker on the home Raspberry Pi. It sits between Kid Phone
(the child's phone) and Kid Phone Parent (the parents' phones). It keeps the child's app list and
which apps are allowed, holds "may I call this number?" requests and the parents' answers, and
alerts the parents' phones through Firebase Cloud Messaging. The full contract is in
[API.md](API.md).

- Port **8774** is for the child's phone and only answers on the home network.
- Port **8775** is for the parents and is reachable from the internet through the Cloudflare tunnel
  at `https://kidphone.tenjo.ovh`.

It has no npm dependencies: plain Node 24 (`node:http`, `node:crypto`, `node:fs`).

## Tests

```
cd hub
node --test
```

Needs Node 22 or later. The tests use a fake clock and a fake FCM sender, so they finish in a couple
of seconds and never contact Google.

## Running it on a PC

```
cd hub
DATA_DIR=./tmp node src/server.js
```

In PowerShell: `$env:DATA_DIR='./tmp'; node src/server.js`. Then try
`curl http://127.0.0.1:8774/health`. `tmp/` is git-ignored.

## Settings (environment variables)

| Variable | Default | What it is |
|----------|---------|------------|
| `DATA_DIR` | `/data` | Where `state.json`, `icons/` and `secrets/` live |
| `KID_PORT` | `8774` | Port for the child's phone |
| `PARENT_PORT` | `8775` | Port for the parents' phones |
| `FIREBASE_KEY_FILE` | `$DATA_DIR/secrets/firebase.json` | Firebase service-account key. If it is missing, the hub logs that alerts are off and carries on |
| `TZ` | (UTC) | Time zone for log lines; the compose file sets `Europe/Dublin` |

## Code

- `src/server.js`: reads the settings, starts both ports, saves state on `docker stop`
- `src/app.js`: puts the pieces together (the tests use it too)
- `src/hub.js`: pairing, apps, call requests, timers, the long-poll, and FCM alerts
- `src/routes-kid.js`, `src/routes-parent.js`: the HTTP routes for each port
- `src/store.js`: `state.json` (written atomically) and the icon files
- `src/net.js`: the "home network only, not through Cloudflare" check for the kid port
- `src/fcm.js`: sends FCM messages with a self-signed Google service-account token
- `src/http.js`, `src/clock.js`, `src/log.js`: small helpers

## Deploying to the Pi

1. **Build and push** an arm64 image from this folder:

   ```
   docker buildx build --platform linux/arm64 -t migruiz/kidphone-hub:latest --push .
   ```

2. **Prepare the data folder** on the Pi as the `pi` user:

   ```
   mkdir -p /home/pi/kidphone/secrets
   ```

   The container runs as the `node` user, which is uid 1000, the same as `pi`. So the hub can write
   to a folder that `pi` owns. If the folder was created by root, run
   `sudo chown -R 1000:1000 /home/pi/kidphone`.

3. **Firebase key** (for alerts): in the Firebase console, open *Project settings → Service
   accounts → Generate new private key*. Copy the file to
   `/home/pi/kidphone/secrets/firebase.json` and run `chmod 600` on it. Never put it in git or in the
   image. Restart the container after adding it. The log should say `alerts on`.

4. **Portainer:** *Stacks → Add stack*, paste [docker-compose.yml](docker-compose.yml), and deploy.
   To update later, push a new image and use *Update the stack* with *Re-pull image*.

5. **Cloudflare tunnel:** add the public hostname `kidphone.tenjo.ovh` with the service
   `http://192.168.0.11:8775`. Never add a hostname for port 8774. Don't forward either port on the
   router.

Everything the hub knows is in `/home/pi/kidphone/state.json` plus `icons/`. Back that folder up if
you like. Deleting it means pairing everything again.
