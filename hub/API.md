# Kid Phone hub — API

The hub is a small always-on service on the home Raspberry Pi. It sits between Kid Phone (the
child's phone) and Kid Phone Parent (the parents' phones):

- it keeps the list of apps installed on the child's phone and which ones the parents allow;
- it keeps "ask first" call requests and the parents' answers;
- it alerts the parents' phones through Firebase Cloud Messaging (FCM).

## Two ports, two audiences

| Port | Who | How they reach it |
|------|-----|-------------------|
| 8774 | the child's phone (`/kid/...`) | home Wi-Fi only: `http://192.168.0.11:8774` |
| 8775 | the parents' phones (`/parent/...`) | anywhere: `https://kidphone.tenjotech.com` (Cloudflare tunnel → Pi:8775) |

Each port serves only its own routes (anything else is 404). Kid routes also refuse any request
that carries a Cloudflare header (`cf-ray`, `cf-connecting-ip` or any other `cf-*` header, or
`CDN-Loop: cloudflare`) or comes from an address that is not private (10/8, 172.16/12, 192.168/16,
127/8, fc00::/7, ::1, and IPv4-mapped forms of those) with `403 {"error":"forbidden"}`. Only the
socket address counts; `X-Forwarded-For` is ignored. That way nobody on the internet can act as the
child's phone, and the child's phone reaching the hub at all proves it is on the home network.

`GET /health` on both ports → `200 {"ok":true}` without auth (for Docker / Portainer checks).

## Auth

Every route except `/health`, `POST /kid/register` and `POST /parent/pair` needs
`Authorization: Bearer <token>`. Tokens are 32 random bytes, base64url. The hub stores only their
SHA-256. A wrong or missing token → `401 {"error":"unauthorized"}`.

Both apps send `X-App-Version: <versionCode>` on every request; the hub remembers it per device.
Every authenticated kid request also updates the child's `lastSeen`.

All bodies are JSON (`Content-Type: application/json`) except icon PNGs. Errors are
`{"error":"<code>","message":"<human text>"}` with a 4xx/5xx status. Codes besides the ones listed
per route: `bad_request` (a missing or wrong field), `bad_json`, `not_found` (no such route, request
or app), `too_large` (413: bodies may be at most 64 KB, or 8 MB for `PUT /kid/apps`), `internal` (500).

## Pairing

1. **Child's phone joins.** In Kid Phone's parent menu (behind the PIN): *Connect to home hub* →
   `POST /kid/register`. Allowed when no child phone is registered yet, or when the body carries the
   current child token (re-register), or within 10 minutes after a parent called
   `POST /parent/replace-kid`. Registering replaces the previous child phone. Every successful
   registration returns a new token and the old one stops working. The replace window lets one
   phone in, then closes. Apps, switches, parents and requests are kept.
2. **A parent phone joins.** Kid Phone's parent menu → *Add a parent phone* →
   `POST /kid/pairing-code` → a 6-digit code, valid 10 minutes, single use. The parent types it in
   the parent app → `POST /parent/pair`. After 5 wrong codes in a row, pairing is refused for 10
   minutes (`429`; the fifth wrong code already gets it). The count and the lockout survive a hub
   restart. A right code resets the count.

## Kid routes (port 8774)

### `POST /kid/register`
Body: `{"childName":"Alejandro","token":"<current child token, optional>"}`
→ `200 {"token":"..."}` · `403 {"error":"already_registered"}`

### `GET /kid/ping`
→ `200 {"ok":true,"hubId":"<stable random id of this hub>","time":<epoch ms>}`
The child's phone calls this to decide whether it is at home. Updates the child's `lastSeen`.

### `POST /kid/pairing-code`
→ `200 {"code":"483920","expiresInSeconds":600}`
A new code replaces any earlier unused one.

### `PUT /kid/apps`
The full list of launchable apps on the child's phone, sent whenever it changes.
```json
{"childName":"Alejandro",
 "apps":[{"package":"com.duolingo","label":"Duolingo","iconPng":"<base64 PNG, ≤ 192×192, optional>"}]}
```
→ `200 {"ok":true}` · `400 {"error":"bad_package"}` (not a valid Android package name; nothing is
saved)
Apps missing from the list are kept but marked `installed:false` (their allowed switch is kept in
case the app comes back). An app that sends no `iconPng` keeps its earlier icon; so does one whose
`iconPng` is not a PNG of at most 192×192 (the hub logs how many it ignored). New apps start not
allowed. `label` defaults to the package name. `childName` (optional) updates the name shown in the
parent app and alerts.

### `GET /kid/state?since=<version>&wait=<seconds>`
Everything the child's phone needs:
```json
{"version":17,
 "allowedPackages":["com.duolingo"],
 "requests":[{"id":"r_ab12","number":"0871234567","status":"approved",
              "remainingSeconds":1740,"minutes":30}]}
```
- `version` goes up whenever anything in this answer changes (apart from the ticking
  `remainingSeconds`), including when an approved window ends or a finished request drops off the
  list. It also goes up once each time the hub starts.
- `allowedPackages`: every app the parents allow, including ones not installed right now.
- Long-poll: when `since` equals the current version, the hub holds the request until something
  changes or `wait` seconds pass (max 30, default 0 = answer at once), then answers.
- `requests` (newest first): every request that is `pending`, plus `approved` ones with time left,
  plus requests that finished in the last 2 minutes (`rejected`, `expired`, `cancelled`) so the
  phone can show the answer. `remainingSeconds` and `minutes` only on `approved`. Times are relative
  so phone and Pi clocks don't need to agree.
- Updates the child's `lastSeen`.

### `POST /kid/requests`
Body: `{"contactName":"Pablo","number":"0871234567"}`
→ `200 {"id":"r_ab12","number":"0871234567","status":"pending"}` (the same shape as an entry in
`/kid/state` `requests`, so an approved one also has `remainingSeconds` and `minutes`)
`number` is required, `contactName` optional. If a request for the same number is already
`pending`, returns that one. If one is `approved` with time left, returns it (status `approved`)
without alerting anyone. Numbers match when they are equal after removing everything except digits,
`+`, `*` and `#`. Otherwise creates a request and sends the `call_request` FCM message to every
parent. A pending request expires after 5 minutes (status `expired`, `request_closed` sent to the
parents). Expiry and approved windows use absolute times, so they still happen on time after a hub
restart.

### `POST /kid/requests/{id}/cancel`
→ `200 {"ok":true}`. Pending → `cancelled`, `request_closed` sent. Anything else: no change.
Unknown id → `404`.

## Parent routes (port 8775)

### `POST /parent/pair`
Body: `{"code":"483920","name":"Miguel","fcmToken":"..."}`
→ `200 {"token":"...","childName":"Alejandro"}` · `400 {"error":"bad_code"}` · `429 {"error":"too_many_tries"}`
`name` is required (`400 bad_request` without it; that does not count as a wrong code). `fcmToken`
is optional. If another parent entry has the same `fcmToken`, it is the same phone pairing again,
and the old entry is removed so the phone isn't alerted twice.

### `PUT /parent/fcm`
Body: `{"fcmToken":"..."}` → `200 {"ok":true}`. Called when FCM rotates the token.

### `GET /parent/state`
```json
{"child":{"name":"Alejandro","paired":true,"lastSeen":<epoch ms or null>,"appVersion":10100},
 "apps":[{"package":"com.duolingo","label":"Duolingo","allowed":true,"installed":true,"hasIcon":true}],
 "requests":[{"id":"r_ab12","contactName":"Pablo","number":"0871234567","status":"approved",
              "createdAt":<ms>,"decidedBy":"Miguel","minutes":30,"remainingSeconds":1740}],
 "parents":[{"name":"Miguel","me":true},{"name":"Sonia","me":false}],
 "time":<epoch ms>}
```
Apps sorted by label (installed first). `requests`: pending ones and the last 20 others, newest first.
In a request, `decidedBy` and `minutes` are `null` until a parent answers (`minutes` `0` means
rejected). `remainingSeconds` is only on `approved` (`0` once the time is up). `child.paired` is
true once a child phone has registered.

### `GET /parent/apps/{package}/icon`
→ `200 image/png` (with `Cache-Control: private, max-age=86400`, so Cloudflare never caches it) · `404`

### `PUT /parent/apps/{package}`
Body: `{"allowed":true}` → `200 {"ok":true}` · `404` (unknown app). Bumps the kid state version
(wakes its long-poll) when the switch actually changes.

### `POST /parent/requests/{id}/answer`
Body: `{"minutes":30}` — `0` rejects; otherwise one of `10`, `30`, `60` (anything else:
`400 {"error":"bad_minutes"}`; unknown id: `404`).
→ `200 {"request":{...same shape as in /parent/state...},"alreadyAnswered":false}`
The first answer wins. If the request is no longer pending, nothing changes and the current request
comes back with `"alreadyAnswered":true`. On a real answer the hub sends `request_closed` to every
parent (so the other parent's notification goes away) and bumps the kid state version.

### `POST /parent/replace-kid`
→ `200 {"ok":true}` — for 10 minutes a new child phone may register without the old token.

### `DELETE /parent/me`
→ `200 {"ok":true}` — unpairs this parent phone.

## FCM messages to parents (data-only, Android priority `high`)

```json
{"type":"call_request","id":"r_ab12","childName":"Alejandro","contactName":"Pablo",
 "number":"0871234567","expiresAt":"<epoch ms as string>"}
{"type":"request_closed","id":"r_ab12","status":"approved","minutes":"30","by":"Miguel"}
```
(All FCM data values are strings.) In `request_closed`, `status` is `approved`, `rejected`,
`expired` or `cancelled`. `minutes` is `"0"` unless approved, and `by` is `""` when no parent
decided (expired, cancelled). Tokens that FCM reports as `UNREGISTERED` / `NOT_FOUND` are removed
from that parent.

## Storage

`/data/state.json` (written atomically: temp file + fsync + rename), icons in
`/data/icons/<package>.png`, the Firebase service-account key at `/data/secrets/firebase.json`
(never in git or in the image). Without that key the hub still works but logs that alerts are
switched off. The hub keeps the last 50 finished requests. Last-seen times and app versions are
saved within a minute (and on shutdown) to spare the SD card; everything else is saved before the
answer goes out.
