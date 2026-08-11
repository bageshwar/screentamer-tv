// ScreenTamer design/dev server.
//
// Serves the dashboard from public/ with a full mock dataset (no real agents,
// no Android build, no compile loop) and the same REST contract as the relay:
//   /api/state  /api/history  /api/login  /api/config  /api/command
//
// It also hot-reloads the browser whenever a file under public/ changes.
//
// Run:  npm run dev   ->   http://127.0.0.1:4000

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.DEV_PORT || 4000);
const PUBLIC_DIR = path.join(__dirname, 'public');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const PASSWORD = 'demo';
const DAY = 86400000;

// ---------------------------------------------------------------------------
// Mock dataset (seeded -> stable across reloads, still "random-looking")
// ---------------------------------------------------------------------------

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rng = mulberry32(20260808);
const rand = (min, max) => min + rng() * (max - min);
const pick = (arr) => arr[Math.floor(rng() * arr.length)];
const chance = (p) => rng() < p;

const POOL = [
  ['com.google.android.youtube.tv', 'youtube'],
  ['com.google.android.apps.youtube.tvunplugged', 'youtubetv'],
  ['com.netflix.ninja', 'netflix'],
  ['com.amazon.amazonvideo.livingroom', 'primevideo'],
  ['com.disney.disneyplus', 'disneyplus'],
  ['com.hulu.livingroomplus', 'hulu'],
  ['com.hbomax', 'max'],
  ['com.peacocktv.brownstone', 'peacock'],
  ['com.paramountplus.livingroom', 'paramountplus'],
  ['com.apple.appletv', 'appletv'],
  ['com.pluto.tv', 'pluto'],
  ['com.tubitv', 'tubi'],
  ['com.plexapp.android', 'plex'],
  ['org.xbmc.kodi', 'kodi'],
  ['com.crunchyroll.crunchyroll', 'crunchyroll'],
  ['tv.twitch.android.app', 'twitch'],
  ['org.videolan.vlc', 'vlc'],
  ['com.spotify.tv', 'spotify'],
  ['com.sling', 'sling'],
  ['com.amazon.tv.launcher', 'firetv'],
  ['com.amazon.tv.settings', 'settings'],
  ['com.bigbuck.bunnyplayer', null],
  ['com.family.cartoonhunt', null],
  ['com.unknown.castplayer', null],
];

const APPS = POOL.map(([pkg, icon]) => ({ pkg, icon }));

const DEVICES = [
  {
    id: 'ST-TEST-1',
    name: 'Test Stick',
    model: 'Fire TV Stick 4K',
    version: '8.1',
    appVersion: '1.0.2',
    online: true,
    currentApp: 'com.netflix.ninja',
    locked: false,
    policy: {
      dailyLimitMs: 7200000,
      curfew: { enabled: true, start: '20:00', end: '06:00' },
      blacklist: ['com.netflix.ninja'],
      lockdown: false,
    },
    seed: 11,
  },
  {
    id: '9d4a2d25e2f1bff2',
    name: 'Emulator Fire TV',
    model: 'sdk_google_atv64_arm64',
    version: '13',
    appVersion: '1.0.2',
    online: true,
    currentApp: 'com.google.android.youtube.tv',
    locked: false,
    policy: {
      dailyLimitMs: 0,
      curfew: { enabled: false, start: '23:00', end: '07:00' },
      blacklist: [],
      lockdown: false,
    },
    seed: 23,
  },
  {
    id: 'UPSTAIRS-1',
    name: 'Upstairs TV',
    model: 'Fire TV Cube',
    version: '7.1',
    appVersion: '1.0.2',
    online: false,
    currentApp: null,
    locked: false,
    policy: {
      dailyLimitMs: 3600000,
      curfew: { enabled: true, start: '19:30', end: '06:30' },
      blacklist: [],
      lockdown: true,
    },
    seed: 37,
  },
];

function toDateKey(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const today = toDateKey(Date.now());

/** One day of per-app usage for a device. Deterministic per device+date. */
function dayUsage(device, daysAgo) {
  const ms = Date.now() - daysAgo * DAY;
  const day = new Date(ms);
  const dow = day.getDay();
  const weekend = dow === 0 || dow === 6;
  const local = mulberry32(device.seed * 100000 + daysAgo * 7919);
  const r = () => local();

  const total = (weekend ? rand(5.5, 9.5) : rand(1.5, 5.5)) * 3600000;
  let remaining = total;
  const apps = {};
  const n = Math.floor(rand(3, 7));
  const used = new Set();
  for (let i = 0; i < n && remaining > 60000; i++) {
    const app = APPS.find((a) => !used.has(a.pkg)) || pick(APPS);
    if (!app) break;
    used.add(app.pkg);
    const share = i === n - 1 ? remaining : total * rand(0.05, 0.45);
    apps[app.pkg] = Math.round(Math.min(remaining, share));
    remaining -= apps[app.pkg];
  }
  if (r() > 0.75) apps['com.google.android.tvlauncher'] = Math.round(total * rand(0.03, 0.1));
  if (daysAgo === 0) {
    // "Today" is a partial day: scale everything back.
    const scale = rand(0.25, 0.7);
    for (const pkg of Object.keys(apps)) apps[pkg] = Math.round(apps[pkg] * scale);
  }
  return apps;
}

/**
 * Per-hour per-app usage for a day: { "<hour 0-23>": { "<pkg>": ms } }.
 * Deterministic per device+date; distributes the app's daily total exactly
 * into 1-3 blocks between 7a and 11p. On "today" hours are clamped so the
 * future never shows usage.
 */
function dayHourly(device, daysAgo, apps) {
  const local = mulberry32(device.seed * 100000 + daysAgo * 7919 + 97);
  const r = () => local();
  const maxHour = daysAgo === 0 ? new Date().getHours() : 23;
  const hourly = {};
  for (const [pkg, totalMs] of Object.entries(apps)) {
    const blocks = 1 + Math.floor(r() * 3);
    const hours = new Set();
    let guard = 0;
    while (hours.size < blocks && guard++ < 30) hours.add(7 + Math.floor(r() * 17)); // 7a..11p
    const hs = [...hours].filter((h) => h <= maxHour);
    if (hs.length === 0) continue;
    let remaining = totalMs;
    hs.forEach((h, i) => {
      const share = i === hs.length - 1 ? remaining : Math.round(totalMs * (0.15 + r() * 0.4));
      (hourly[h] = hourly[h] || {})[pkg] = Math.max(0, Math.min(remaining, share));
      remaining -= hourly[h][pkg];
    });
  }
  return hourly;
}

function historyFor(device, days) {
  const out = [];
  for (let i = days - 1; i >= 0; i--) {
    const date = toDateKey(Date.now() - i * DAY);
    const apps = dayUsage(device, i);
    out.push({ date, totalMs: Object.values(apps).reduce((s, v) => s + v, 0), apps, hourly: dayHourly(device, i, apps) });
  }
  return out;
}

function logFor(device) {
  const entries = [
    ['agent connected', 2],
    ['usage reported', 1],
    ['usage reported', 1],
    ['policy updated from dashboard: limit=120min curfew=true', 5],
    ['command sent: pause (device offline)', 4],
    ['blocking blacklisted app Netflix', 3],
    ['command: instant lockdown', 2],
    ['curfew is active — screen time is paused', 3],
    ['command: go home (adb ok)', 1],
    ['agent went offline (no heartbeat)', 2],
    ['command from dashboard: unlock', 2],
    ['failed dashboard login attempt', 1],
    ['usage reported', 1],
    ['command: force-stop Hulu (adb ok)', 1],
    ['connected to relay server', 1],
    ['agent connected', 2],
  ];
  const out = [];
  let ts = Date.now() - 2 * DAY;
  const rng2 = mulberry32(device.seed * 31 + 7);
  for (let i = 0; i < 40; i++) {
    const [msg, weight] = entries[Math.floor(rng2() * entries.length)];
    const step = 5 + Math.floor(rng2() * 220) * 60000;
    ts += step * (rng2() > 0.5 ? 1 : 1.8);
    out.push({ ts, msg });
  }
  return out;
}

function healthFor(device) {
  return {
    startCount: 3 + (device.seed % 13),
    lastStartAt: Date.now() - (device.online ? rand(0, 3) : rand(2, 30)) * 3600000,
    lastTickAt: Date.now() - (device.online ? 4000 : rand(1, 20) * 60000),
    tickFailures: device.seed % 5,
    lastError: device.seed % 3 === 0
      ? { ts: Date.now() - rand(2, 40) * 3600000, msg: 'tick failed: UsageStatsManager threw (retrying)' }
      : null,
  };
}

function deviceState(device) {
  const apps = dayUsage(device, 0);
  const health = healthFor(device);
  return {
    id: device.id,
    name: device.name,
    model: device.model,
    version: device.version,
    appVersion: device.appVersion,
    online: device.online,
    lastSeen: device.online ? Date.now() - rand(0, 40) * 1000 : Date.now() - rand(2, 20) * 3600000,
    currentApp: device.currentApp,
    locked: device.locked,
    totalMs: Object.values(apps).reduce((s, v) => s + v, 0),
    policy: device.policy,
    log: logFor(device),
    health,
    iconEndpoint: true,
  };
}

function state() {
  const devices = {};
  const usage = {};
  for (const d of DEVICES) {
    devices[d.id] = deviceState(d);
    usage[d.id] = dayUsage(d, 0);
  }
  return { defaultPolicy: DEVICES[0].policy, devices, usage };
}

// ---------------------------------------------------------------------------
// Live reload (SSE pushed when public/ changes)
// ---------------------------------------------------------------------------

let reloadClients = new Set();
fs.watch(PUBLIC_DIR, { recursive: true }, (_e, file) => {
  if (!file) return;
  const text = `data: ${JSON.stringify({ file })}\n\n`;
  for (const res of reloadClients) {
    try { res.write(text); } catch (_) { reloadClients.delete(res); }
  }
  console.log(`[dev] ${file} changed -> reloading browsers`);
});

// ---------------------------------------------------------------------------
// HTTP server
// ---------------------------------------------------------------------------

function send(res, code, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(code, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function sendJson(res, code, obj) {
  send(res, code, JSON.stringify(obj), 'application/json; charset=utf-8');
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => { try { resolve(JSON.parse(data)); } catch (_) { resolve({}); } });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (req.method === 'GET' && url.pathname === '/__reload') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    res.write('retry: 1000\n\n');
    reloadClients.add(res);
    req.on('close', () => reloadClients.delete(res));
    return;
  }

  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
    const html = fs.readFileSync(path.join(PUBLIC_DIR, 'index.html'), 'utf8');
    const inject = `<script>new EventSource('/__reload').onmessage=()=>location.reload()</script>`;
    return send(res, 200, html.replace('</body>', inject + '</body>'), MIME['.html']);
  }

  if (req.method === 'GET' && url.pathname.startsWith('/static/')) {
    const file = path.join(PUBLIC_DIR, path.basename(url.pathname));
    if (!fs.existsSync(file)) return send(res, 404, 'not found');
    return send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] || 'application/octet-stream');
  }

  if (req.method === 'GET' && url.pathname === '/favicon.ico') {
    res.writeHead(302, { 'Location': '/static/favicon.svg' });
    return res.end();
  }

  // Mimic the agent's real device-icon endpoint: known apps resolve to a
  // bundled brand SVG (the agent would return the actual PNG here), unknown
  // packages 404 so the dashboard falls back to the letter avatar.
  if (req.method === 'GET' && url.pathname === '/api/icon') {
    const pkg = url.searchParams.get('pkg') || '';
    const icon = POOL.find(([p, iconKey]) => p === pkg && iconKey)?.[1];
    if (!icon) return send(res, 404, 'not found');
    const file = path.join(PUBLIC_DIR, 'icons', `${icon}.svg`);
    if (!fs.existsSync(file)) return send(res, 404, 'not found');
    return send(res, 200, fs.readFileSync(file), MIME['.svg']);
  }

  if (req.method === 'POST' && url.pathname === '/api/login') {
    const body = await readBody(req);
    if (body.password !== PASSWORD) return sendJson(res, 401, { ok: false, error: 'wrong password' });
    return sendJson(res, 200, { ok: true });
  }

  if (req.method === 'GET' && url.pathname === '/api/state') {
    return sendJson(res, 200, state());
  }

  if (req.method === 'GET' && url.pathname === '/api/history') {
    const deviceId = url.searchParams.get('deviceId');
    const days = Math.min(365, Math.max(1, Number(url.searchParams.get('days')) || 14));
    const device = DEVICES.find((d) => d.id === deviceId);
    if (!device) return sendJson(res, 404, { ok: false, error: 'unknown device' });
    return sendJson(res, 200, {
      deviceId,
      days,
      today,
      history: historyFor(device, days),
    });
  }

  if (req.method === 'POST' && url.pathname === '/api/config') {
    const body = await readBody(req);
    if (body.password !== PASSWORD) return sendJson(res, 401, { ok: false, error: 'wrong password' });
    const device = DEVICES.find((d) => d.id === body.deviceId);
    if (device && body.policy) device.policy = body.policy;
    return sendJson(res, 200, { ok: true });
  }

  if (req.method === 'POST' && url.pathname === '/api/command') {
    const body = await readBody(req);
    if (body.password !== PASSWORD) return sendJson(res, 401, { ok: false, error: 'wrong password' });
    const device = DEVICES.find((d) => d.id === body.deviceId);
    if (!device) return sendJson(res, 404, { ok: false, error: 'unknown device' });
    const cmd = (body.command || {}).type;
    const delivered = device.online;
    if (cmd === 'lock') device.locked = true;
    if (cmd === 'unlock') device.locked = false;
    device.lastSeen = Date.now();
    return sendJson(res, 200, { ok: true, delivered });
  }

  send(res, 404, 'not found');
});

server.listen(PORT, () => {
  console.log('============================================');
  console.log('  ScreenTamer DEV server (mock data)');
  console.log('--------------------------------------------');
  console.log(`  Dashboard:  http://127.0.0.1:${PORT}/`);
  console.log(`  Password:   ${PASSWORD}`);
  console.log('  Live reload: yes (watch public/)');
  console.log('============================================');
});
