const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');
const P = require('./lib/protocol');
const { loadConfig, makeStore, toDateKey } = require('./lib/store');

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

const config = loadConfig();
const store = makeStore();
const { state, persist, getDevice, addLog, recordUsage, usageFor, historyFor, sweep } = store;

const PRUNED = sweep();
if (PRUNED) console.log(`pruned ${PRUNED} stale history file(s)`);

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

console.log('============================================');
console.log('  ScreenTamer parent server');
console.log('--------------------------------------------');
console.log(`  Dashboard:  http://<this-host>:${config.port}/`);
console.log(`  Agent URL:  ws://<this-host>:${config.port}/ws`);
console.log(`  Device token:     ${config.deviceToken}`);
console.log(`  Parent password:  ${config.parentPassword}`);
console.log('--------------------------------------------');
console.log('  Store these in data/config.json. Do not share the password.');
console.log('============================================');

// ---------------------------------------------------------------------------
// HTTP server (static dashboard + REST)
// ---------------------------------------------------------------------------

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  logHttp(res, code);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

/** Console access log: METHOD path -> status (ms). Called by sendJson/serveFile. */
function logHttp(res, code) {
  const req = res.req;
  if (!req) return;
  const path = req._stPath || (req._stPath = (new URL(req.url, `http://${req.headers.host}`)).pathname);
  const started = req._stStart || (req._stStart = Date.now());
  console.log(`[http] ${req.method} ${path} -> ${code} (${Date.now() - started}ms)`);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function authed(req, body) {
  return body && body.password === config.parentPassword;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // Static dashboard
  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
    return serveFile(path.join(PUBLIC_DIR, 'index.html'), res);
  }
  if (req.method === 'GET' && url.pathname.startsWith('/static/')) {
    return serveFile(path.join(PUBLIC_DIR, path.basename(url.pathname)), res);
  }
  if (req.method === 'GET' && url.pathname === '/favicon.ico') {
    res.writeHead(204);
    return res.end();
  }

  // REST API
  if (req.method === 'POST' && url.pathname === '/api/login') {
    const body = await safeRead(res, req);
    if (!body) return;
    if (authed(req, body)) {
      return sendJson(res, 200, { ok: true, deviceToken: config.deviceToken });
    }
    return sendJson(res, 401, { ok: false, error: 'wrong password' });
  }

  if (req.method === 'GET' && url.pathname === '/api/state') {
    return sendJson(res, 200, publicState());
  }

  if (req.method === 'GET' && url.pathname === '/api/history') {
    const deviceId = url.searchParams.get('deviceId');
    const days = Math.min(365, Math.max(1, Number(url.searchParams.get('days')) || 14));
    if (!deviceId) return sendJson(res, 400, { ok: false, error: 'deviceId required' });
    if (!state.devices[deviceId]) return sendJson(res, 404, { ok: false, error: 'unknown device' });
    return sendJson(res, 200, {
      deviceId,
      days,
      today: toDateKey(new Date()),
      history: historyFor(deviceId, days),
    });
  }

  if (req.method === 'POST' && url.pathname === '/api/config') {
    const body = await safeRead(res, req);
    if (!body) return;
    if (!authed(req, body)) return sendJson(res, 401, { ok: false, error: 'wrong password' });
    const deviceId = body.deviceId;
    if (deviceId && state.devices[deviceId]) {
      const device = state.devices[deviceId];
      device.policy = normalizePolicy(body.policy);
      pushToAgent(deviceId, { type: P.CONFIG, policy: device.policy });
    } else if (!deviceId) {
      // Apply as the default for future devices.
      state.defaultPolicy = normalizePolicy(body.policy);
      for (const id of Object.keys(state.devices)) {
        state.devices[id].policy = { ...JSON.parse(JSON.stringify(state.defaultPolicy)) };
        pushToAgent(id, { type: P.CONFIG, policy: state.devices[id].policy });
      }
    }
    persist();
    broadcastState();
    return sendJson(res, 200, { ok: true });
  }

  if (req.method === 'POST' && url.pathname === '/api/command') {
    const body = await safeRead(res, req);
    if (!body) return;
    if (!authed(req, body)) return sendJson(res, 401, { ok: false, error: 'wrong password' });
    const deviceId = body.deviceId;
    const device = deviceId && state.devices[deviceId];
    if (!device) return sendJson(res, 404, { ok: false, error: 'unknown device' });
    const cmd = body.command || {};
    if (!Object.values(P).includes(cmd.type)) {
      return sendJson(res, 400, { ok: false, error: `unknown command ${cmd.type}` });
    }
    const sent = pushToAgent(deviceId, { type: P.COMMAND, command: cmd.type, pkg: cmd.pkg || null });
    addLog(device, `command sent: ${cmd.type}${cmd.pkg ? ' ' + cmd.pkg : ''}${sent ? '' : ' (device offline)'}`);
    persist();
    broadcastState();
    return sendJson(res, 200, { ok: true, delivered: sent });
  }

  sendJson(res, 404, { ok: false, error: 'not found' });
});

function serveFile(file, res) {
  fs.readFile(file, (err, data) => {
    if (err) {
      logHttp(res, 404);
      res.writeHead(404);
      return res.end('not found');
    }
    logHttp(res, 200);
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}

async function safeRead(res, req) {
  try {
    return await readBody(req);
  } catch (e) {
    sendJson(res, 400, { ok: false, error: 'invalid JSON body' });
    return null;
  }
}

// ---------------------------------------------------------------------------
// WebSocket: agents and parents
// ---------------------------------------------------------------------------

const wss = new WebSocketServer({ server, path: '/ws' });

/** deviceId -> Set<WebSocket> */
const agentSockets = new Map();
/** Set<WebSocket> parent dashboards */
const parentSockets = new Set();

function agentSocketCount() {
  let n = 0;
  for (const set of agentSockets.values()) n += set.size;
  return n;
}

wss.on('connection', (ws) => {
  console.log('[ws] connection opened (agents=%d, parents=%d)', agentSocketCount(), parentSockets.size);
  ws.isAlive = true;
  ws.on('pong', () => (ws.isAlive = true));
  ws.on('error', (e) => console.log('[ws] error:', e.message));
  ws.on('close', () => {
    parentSockets.delete(ws);
    let dropped = null;
    for (const [id, set] of agentSockets) {
      if (set.delete(ws)) {
        if (set.size === 0) {
          agentSockets.delete(id);
          const device = state.devices[id];
          if (device) {
            device.online = false;
            addLog(device, 'agent disconnected');
            persist();
            broadcastState();
          }
          dropped = id;
        }
      }
    }
    console.log('[ws] closed (agents=%d, parents=%d%s)', agentSocketCount(), parentSockets.size, dropped ? `, ${dropped} offline` : '');
  });

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      return ws.send(JSON.stringify({ type: 'error', error: 'bad JSON' }));
    }

    switch (msg.type) {
      case P.HELLO: {
        if (msg.role === 'agent') {
          if (msg.token !== config.deviceToken) {
            console.log('[ws] rejected agent hello: bad device token');
            return ws.send(JSON.stringify({ type: 'error', error: 'bad device token' }));
          }
          const id = msg.deviceId || msg.name;
          const device = getDevice(id);
          device.name = msg.name || device.name;
          device.model = msg.model || device.model;
          device.version = msg.version || device.version;
          device.online = true;
          device.lastSeen = Date.now();
          device.policy = device.policy || { ...state.defaultPolicy } || P.DEFAULT_POLICY();
          if (!agentSockets.has(id)) agentSockets.set(id, new Set());
          agentSockets.get(id).add(ws);
          addLog(device, 'agent connected');
          console.log('[ws] agent hello: %s (%s, %s)', id, msg.model || '?', msg.version || '?');
          ws.send(JSON.stringify({ type: P.WELCOME, policy: device.policy }));
          persist();
          broadcastState();
        } else if (msg.role === 'parent') {
          if (msg.password !== config.parentPassword) {
            console.log('[ws] rejected parent login: wrong password');
            return ws.send(JSON.stringify({ type: 'error', error: 'wrong password' }));
          }
          parentSockets.add(ws);
          console.log('[ws] parent dashboard connected');
          ws.send(JSON.stringify({ type: 'state', state: publicState() }));
        }
        break;
      }

      case P.USAGE: {
        if (msg.role === 'parent') break; // parents can't report usage
        const device = state.devices[msg.deviceId];
        if (!device) break;
        device.online = true;
        device.lastSeen = Date.now();
        device.currentApp = msg.currentApp || null;
        device.locked = !!msg.locked;
        device.totalMs = Number(msg.totalMs) || 0;
        if (msg.date) {
          recordUsage(msg.deviceId, msg.date, msg.apps || {});
        }
        addLog(device, 'usage reported');
        console.log('[ws] usage: %s %s totalMs=%d locked=%s apps=%d', msg.deviceId, msg.date || '?', device.totalMs, device.locked, Object.keys(msg.apps || {}).length);
        persist();
        broadcastState();
        break;
      }

      case P.LOG: {
        const device = state.devices[msg.deviceId];
        if (device) {
          addLog(device, msg.msg || '');
          console.log('[ws] log from %s: %s', msg.deviceId, (msg.msg || '').slice(0, 120));
          broadcastState();
        }
        break;
      }
    }
  });
});

// Liveness sweep: mark agents offline if silent for >45s.
setInterval(() => {
  const now = Date.now();
  for (const [id, set] of agentSockets) {
    const device = state.devices[id];
    if (device && device.online && now - device.lastSeen > 45_000) {
      device.online = false;
      addLog(device, 'agent went offline (no heartbeat)');
      persist();
      broadcastState();
    }
  }
}, 10_000);

// Daily retention sweep (also runs at boot).
setInterval(() => {
  const pruned = sweep();
  if (pruned) console.log(`pruned ${pruned} stale history file(s)`);
}, 6 * 60 * 60 * 1000);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function pushToAgent(deviceId, msg) {
  const set = agentSockets.get(deviceId);
  if (!set || set.size === 0) return false;
  const text = JSON.stringify(msg);
  for (const ws of set) ws.send(text);
  return true;
}

function broadcastState() {
  const snapshot = publicState();
  const text = JSON.stringify({ type: 'state', state: snapshot });
  for (const ws of parentSockets) {
    if (ws.readyState === 1) ws.send(text);
  }
}

/** Strips nothing sensitive (password never stored in state) and keeps size sane. */
function publicState() {
  const { defaultPolicy, devices } = state;
  const trimmed = {};
  const today = toDateKey(new Date());
  for (const [id, device] of Object.entries(devices)) {
    trimmed[id] = {
      id: device.id,
      name: device.name,
      model: device.model,
      version: device.version,
      online: device.online,
      lastSeen: device.lastSeen,
      currentApp: device.currentApp,
      locked: device.locked,
      totalMs: device.totalMs,
      policy: device.policy,
      log: device.log.slice(0, 50),
    };
  }
  const usageToday = {};
  for (const id of Object.keys(devices)) {
    usageToday[id] = usageFor(id, today);
  }
  return { defaultPolicy, devices: trimmed, usage: usageToday };
}

function normalizePolicy(policy) {
  const base = P.DEFAULT_POLICY();
  if (!policy) return base;
  return {
    dailyLimitMs: Math.max(0, Number(policy.dailyLimitMs) || 0),
    curfew: {
      enabled: !!policy.curfew?.enabled,
      start: String(policy.curfew?.start || base.curfew.start),
      end: String(policy.curfew?.end || base.curfew.end),
    },
    blacklist: Array.isArray(policy.blacklist) ? policy.blacklist.filter((x) => typeof x === 'string') : [],
    lockdown: !!policy.lockdown,
  };
}

server.listen(config.port, () => {
  console.log(`Listening on :${config.port}`);
});
