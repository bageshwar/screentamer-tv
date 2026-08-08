// Smoke test for the ScreenTamer server: HTTP auth + WS agent + WS parent + command relay.
const { WebSocket } = require('ws');

const BASE = 'http://127.0.0.1:3000';
const config = require('../data/config.json');
const PW = config.parentPassword;
const TOKEN = config.deviceToken;

// Server buckets usage by its own local date — compute it the same way the
// server does (store.toDateKey) so tests survive day rollovers.
const now = new Date();
const TODAY = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;

let failures = 0;
const check = (name, cond) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}`);
  if (!cond) failures++;
};

async function main() {
  // 1. REST: wrong password rejected
  let res = await fetch(`${BASE}/api/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: 'nope' }) });
  check('login rejects wrong password', res.status === 401);

  // 2. REST: correct password
  res = await fetch(`${BASE}/api/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: PW }) });
  const login = await res.json();
  check('login accepts correct password', res.status === 200 && login.ok && login.deviceToken === TOKEN);

  // 3. Agent connects over WS with token
  const agent = new WebSocket('ws://127.0.0.1:3000/ws');
  const agentHello = new Promise((r) => {
    agent.on('message', (raw) => {
      const m = JSON.parse(raw);
      if (m.type === 'welcome') r(m);
    });
  });
  await new Promise((r) => agent.on('open', r));
  agent.send(JSON.stringify({ type: 'hello', role: 'agent', token: TOKEN, deviceId: 'ST-TEST-1', name: 'Test Stick', model: 'Fire TV Stick 4K', version: '8.1' }));
  const welcome = await agentHello;
  check('agent gets welcome with policy', welcome.type === 'welcome' && !!welcome.policy && typeof welcome.policy.dailyLimitMs === 'number');

  // 4. Parent connects over WS and receives state including the new device
  const parent = new WebSocket('ws://127.0.0.1:3000/ws');
  const stateMsgs = [];
  parent.on('message', (raw) => stateMsgs.push(JSON.parse(raw)));
  await new Promise((r) => parent.on('open', r));
  parent.send(JSON.stringify({ type: 'hello', role: 'parent', password: PW }));
  await new Promise((r) => setTimeout(r, 300));
  const firstState = stateMsgs.find((m) => m.type === 'state');
  check('parent receives state with agent device', firstState && firstState.state.devices['ST-TEST-1']?.online === true);

  // 5. Agent reports usage; parent gets pushed update
  agent.send(JSON.stringify({ type: 'usage', deviceId: 'ST-TEST-1', date: TODAY, apps: { 'com.netflix.ninja': 1800000, 'com.google.android.youtube.tv': 900000 }, totalMs: 2700000, currentApp: 'com.netflix.ninja', locked: false }));
  await new Promise((r) => setTimeout(r, 300));
  // Match on the usage entry itself (not totalMs: a stale value from a previous
  // run may persist in state.json and make an earlier push match).
  const usageState = stateMsgs.find((m) => m.type === 'state' && m.state.usage['ST-TEST-1']?.['com.netflix.ninja'] === 1800000);
  check('usage report propagated to dashboard', !!usageState && usageState.state.devices['ST-TEST-1']?.totalMs === 2700000);
  check('per-app breakdown present', usageState.state.usage['ST-TEST-1']?.['com.netflix.ninja'] === 1800000);

  // 6. Parent sends command; agent receives it (auth via /api/command)
  const cmdReceived = new Promise((r) => agent.on('message', (raw) => { const m = JSON.parse(raw); if (m.type === 'command') r(m); }));
  res = await fetch(`${BASE}/api/command`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: PW, deviceId: 'ST-TEST-1', command: { type: 'pause' } }) });
  const cmdRes = await res.json();
  const cmd = await cmdReceived;
  check('command relayed to agent (pause)', res.status === 200 && cmdRes.delivered === true && cmd.command === 'pause');

  // 7. Policy update pushed to agent
  const policyReceived = new Promise((r) => agent.on('message', (raw) => { const m = JSON.parse(raw); if (m.type === 'config') r(m); }));
  res = await fetch(`${BASE}/api/config`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: PW, deviceId: 'ST-TEST-1', policy: { dailyLimitMs: 7200000, curfew: { enabled: true, start: '20:00', end: '06:00' }, blacklist: ['com.netflix.ninja'], lockdown: false } }) });
  const policy = await policyReceived;
  check('policy pushed to agent', res.status === 200 && policy.policy.dailyLimitMs === 7200000 && policy.policy.curfew.enabled === true);

  // 8. Wrong token rejected
  const badAgent = new WebSocket('ws://127.0.0.1:3000/ws');
  const badReply = new Promise((r) => badAgent.on('message', (raw) => r(JSON.parse(raw))));
  await new Promise((r) => badAgent.on('open', r));
  badAgent.send(JSON.stringify({ type: 'hello', role: 'agent', token: 'wrong' }));
  const err = await badReply;
  check('bad token rejected', err.type === 'error');
  badAgent.close();

  // 9. Dashboard HTML served
  const html = await fetch(`${BASE}/`);
  check('dashboard served', html.status === 200 && (await html.text()).includes('ScreenTamer'));

  // 10. History endpoint: shape, days clamp, 400/404
  res = await fetch(`${BASE}/api/history?deviceId=ST-TEST-1&days=14`);
  const hist = await res.json();
  const totalToday = hist.history[hist.history.length - 1].totalMs;
  check('history returns 14 days oldest-first', res.status === 200 && hist.history.length === 14 && hist.history[0].date < hist.history[13].date && hist.today === hist.history[13].date);
  check('history includes usage reported above', totalToday >= 2700000 && hist.history[13].apps['com.netflix.ninja'] === 1800000);
  res = await fetch(`${BASE}/api/history?deviceId=ST-TEST-1&days=999`);
  check('history days clamped to 365', res.status === 200 && (await res.json()).history.length === 365);
  res = await fetch(`${BASE}/api/history?deviceId=ST-TEST-1&days=abc`);
  check('history days falls back to default', res.status === 200 && (await res.json()).days === 14);
  res = await fetch(`${BASE}/api/history`);
  check('history requires deviceId (400)', res.status === 400);
  res = await fetch(`${BASE}/api/history?deviceId=does-not-exist`);
  check('history unknown device (404)', res.status === 404);

  // 11. State endpoint serves today's usage from history files
  res = await fetch(`${BASE}/api/state`);
  const st = await res.json();
  check('state usage reads today from history', st.usage['ST-TEST-1']?.['com.netflix.ninja'] === 1800000);

  agent.close();
  parent.close();

  console.log(failures === 0 ? '\nALL TESTS PASSED' : `\n${failures} TEST(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('test error', e); process.exit(1); });
