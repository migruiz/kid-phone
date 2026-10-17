// The security boundary for the kid port: only requests from the home network, never through the
// Cloudflare tunnel. We look at the socket address only; X-Forwarded-For is never trusted.

function parseV4(text) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(text);
  if (!m) return null;
  const bytes = m.slice(1).map(Number);
  return bytes.every((b) => b <= 255) ? bytes : null;
}

function isPrivateV4([a, b]) {
  return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

/** Parses an IPv6 address into 8 numbers (16-bit groups), or null. Handles "::" and a dotted IPv4 tail. */
function parseV6(input) {
  let text = input.split('%')[0].toLowerCase(); // drop a zone id such as %eth0
  let tail = [];
  if (text.includes('.')) {
    const cut = text.lastIndexOf(':');
    const v4 = parseV4(text.slice(cut + 1));
    if (!v4) return null;
    tail = [(v4[0] << 8) | v4[1], (v4[2] << 8) | v4[3]];
    text = text.slice(0, cut + 1);
    if (!text.endsWith('::')) text = text.slice(0, -1);
  }
  const halves = text.split('::');
  if (halves.length > 2) return null;
  const groups = (part) => (part === '' ? [] : part.split(':').map((g) => (/^[0-9a-f]{1,4}$/.test(g) ? parseInt(g, 16) : NaN)));
  const head = groups(halves[0]);
  const rest = halves.length === 2 ? groups(halves[1]) : [];
  const count = head.length + rest.length + tail.length;
  let all;
  if (halves.length === 2) {
    if (count > 7) return null;
    all = [...head, ...new Array(8 - count).fill(0), ...rest, ...tail];
  } else {
    if (count !== 8) return null;
    all = [...head, ...tail];
  }
  return all.some(Number.isNaN) ? null : all;
}

/** True for 10/8, 172.16/12, 192.168/16, 127/8, fc00::/7, ::1 and IPv4-mapped forms of the IPv4 ones. */
export function isPrivateAddress(address) {
  if (typeof address !== 'string' || address === '') return false;
  const v4 = parseV4(address);
  if (v4) return isPrivateV4(v4);
  const g = parseV6(address);
  if (!g) return false;
  const zeros = (n) => g.slice(0, n).every((x) => x === 0);
  if (zeros(5) && g[5] === 0xffff) return isPrivateV4([g[6] >> 8, g[6] & 0xff, g[7] >> 8, g[7] & 0xff]);
  if (zeros(7) && g[7] === 1) return true;
  return (g[0] & 0xfe00) === 0xfc00;
}

/** Cloudflare adds cf-ray / cf-connecting-ip (and other cf-* headers, and "CDN-Loop: cloudflare"). */
export function hasCloudflareHeaders(headers) {
  for (const name of Object.keys(headers)) {
    if (name.startsWith('cf-')) return true;
  }
  return /cloudflare/i.test(headers['cdn-loop'] ?? '');
}

/** Why a request may not use the kid routes, or null when it may. */
export function kidRefusal(req) {
  if (hasCloudflareHeaders(req.headers)) return 'Kid routes are not reachable through the internet';
  if (!isPrivateAddress(req.socket?.remoteAddress)) return 'Kid routes are only reachable on the home network';
  return null;
}
