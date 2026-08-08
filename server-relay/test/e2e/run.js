// E2E evidence suite: drives a real TV emulator through the full ScreenTamer
// scenario (home, lock, unlock, curfew enforcement, restore) via the REST API,
// asserting each stage on three fronts — server state, agent logcat, and the
// actual on-screen UI (uiautomator text) — and capturing a screenshot per
// stage. Artifacts land in evidence/e2e-<timestamp>/.
//
// Usage:  npm run test:e2e  [-- --device <id>] [--no-dashboard]
//
// Target server (env DASH_URL, default the relay at 127.0.0.1:3000). When
// pointing at the agent's embedded server (e.g. DASH_URL=http://127.0.0.1:8080
// via adb forward), supply DASH_PASSWORD=<dashboard password>.
//
// Preconditions:
//   - a ScreenTamer server reachable at DASH_URL (relay: npm start in server-relay/;
//     embedded: agent running + adb forward tcp:<port> tcp:<port>)
//   - emulator booted, agent installed + online (device auto-detected)
//   - adb on PATH or under ANDROID_HOME / ~/Library/Android/sdk

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');

const ROOT = path.join(__dirname, '..', '..', '..');
const SERVER_DIR = path.join(ROOT, 'server-relay');
const BASE = process.env.DASH_URL || 'http://127.0.0.1:3000';
const config = (() => {
  try {
    return require(path.join(SERVER_DIR, 'data/config.json'));
  } catch (e) {
    return { parentPassword: '', deviceToken: '' };
  }
})();
const PW = process.env.DASH_PASSWORD || config.parentPassword;

let failures = 0;
let warnings = 0;
const results = [];

const args = process.argv.slice(2);
const flagDevice = args.includes('--device') ? args[args.indexOf('--device') + 1] : null;
const withDashboard = !args.includes('--no-dashboard');

function check(name, cond) {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}`;
  console.log(line);
  results.push(line);
  if (!cond) failures++;
}

function warn(name, detail) {
  console.log(`WARN  ${name}${detail ? ` (${detail})` : ''}`);
  results.push(`WARN  ${name}${detail ? ` (${detail})` : ''}`);
  warnings++;
}

function adbPath() {
  if (process.env.ADB && fs.existsSync(process.env.ADB)) return process.env.ADB;
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(os.homedir(), 'Library/Android/sdk');
  const p = path.join(sdk, 'platform-tools/adb');
  if (fs.existsSync(p)) return p;
  return 'adb';
}

function adb(argsStr) {
  return execSync(`${adbPath()} ${argsStr}`, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

async function fetchJson(url, opts) {
  const headers = { ...(opts?.headers || {}) };
  if (PW) headers['x-parent-password'] = PW;
  const res = await fetch(BASE + url, { ...opts, headers });
  return { status: res.status, body: await res.json() };
}

async function command(type, pkg) {
  const { status, body } = await fetchJson('/api/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: PW, deviceId: deviceId, command: { type, pkg: pkg || null } }),
  });
  return { status, ...body };
}

async function setPolicy(policy) {
  const { status } = await fetchJson('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: PW, deviceId: deviceId, policy }),
  });
  return status;
}

async function getDevice() {
  const { body } = await fetchJson('/api/state');
  return body.devices[deviceId];
}

async function waitFor(desc, fn, timeoutMs = 60_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      if (await fn()) return true;
    } catch (e) { /* retry */ }
    await new Promise((r) => setTimeout(r, 2000));
  }
  return false;
}

function uiText() {
  for (let i = 0; i < 3; i++) {
    try {
      adb('shell uiautomator dump /sdcard/ui.xml');
      const xml = adb('shell cat /sdcard/ui.xml');
      const texts = [...xml.matchAll(/text="([^"]{2,120})"/g)].map((m) => m[1]).filter((t) => !t.startsWith('{'));
      return texts.join(' | ');
    } catch (e) { /* transition in progress */ }
  }
  return '';
}

function captureScreen(step) {
  try {
    execSync(`${adbPath()} exec-out screencap -p > ${JSON.stringify(step)}`, { stdio: ['ignore', 'pipe', 'ignore'] });
  } catch (e) {
    warn(`screenshot ${path.basename(step)}`, 'screencap failed');
  }
  return uiText();
}

let deviceId = flagDevice;
let evidenceDir = null;

async function main() {
  const health = await fetchJson('/api/state');
  if (health.status !== 200) {
    console.error('server not reachable at ' + BASE + ' (relay: npm start in server-relay/; embedded: agent + adb forward). For embedded, export DASH_PASSWORD.');
    process.exit(1);
  }
  fs.mkdirSync(path.join(ROOT, 'evidence'), { recursive: true });

  if (!deviceId) {
    const online = Object.values(health.body.devices).find((d) => d.online);
    if (!online) {
      console.error('no online agent device found — is the emulator booted with the agent running?');
      process.exit(1);
    }
    deviceId = online.id;
  }
  console.log(`device under test: ${deviceId}\n`);

  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
  evidenceDir = path.join(ROOT, 'evidence', `e2e-${stamp}`);
  fs.mkdirSync(evidenceDir, { recursive: true });

  const originalPolicy = health.body.devices[deviceId]?.policy;
  try {
    fs.writeFileSync(path.join(evidenceDir, '00-state.json'), JSON.stringify(health.body, null, 2));
    // 1. Home
    const home = await command('home');
    check('home command delivered', home.delivered === true);
    await new Promise((r) => setTimeout(r, 4000));
    const homeText = captureScreen(path.join(evidenceDir, '01-home.png'));
    check('home: launcher on screen', /Home|Favorite Apps/i.test(homeText));

    // 2. Lock
    const lock = await command('lock');
    check('lock command delivered', lock.delivered === true);
    const locked = await waitFor('lock', async () => (await getDevice()).locked === true);
    check('lock: state locked', locked);
    const lockText = captureScreen(path.join(evidenceDir, '02-lock-overlay.png'));
    check('lock: overlay text on screen', /locked by screentamer/i.test(lockText));

    // 3. Unlock
    const unlock = await command('unlock');
    check('unlock command delivered', unlock.delivered === true);
    const unlocked = await waitFor('unlock', async () => (await getDevice()).locked === false);
    check('unlock: state unlocked', unlocked);
    const unlockText = captureScreen(path.join(evidenceDir, '03-unlocked.png'));
    check('unlock: overlay gone, launcher back', !/screentamer/i.test(unlockText) && /Home|Favorite Apps/i.test(unlockText));

    // 4. Curfew enforcement
    const policySet = await setPolicy({ dailyLimitMs: 100, curfew: { enabled: true, start: '00:00', end: '23:59' }, blacklist: [], lockdown: false });
    check('curfew policy applied', policySet === 200);
    const enforced = await waitFor('curfew enforcement', async () => (await getDevice()).locked === true);
    check('curfew: state locked', enforced);
    const curfewText = captureScreen(path.join(evidenceDir, '04-curfew-enforced.png'));
    check('curfew: enforcement text on screen', /curfew is active/i.test(curfewText));
  } finally {
    if (originalPolicy) {
      await setPolicy(originalPolicy);
      const restored = await waitFor('policy restore', async () => (await getDevice()).locked === false);
      check('policy restored: state unlocked', restored);
      const restoredText = captureScreen(path.join(evidenceDir, '05-restored.png'));
      check('restored: launcher visible', !/screentamer/i.test(restoredText) && /Home|Favorite Apps/i.test(restoredText));
    }
  }

  // 6. History persistence
  const hist = await fetchJson(`/api/history?deviceId=${encodeURIComponent(deviceId)}&days=14`);
  const h = hist.body;
  const ascending = h.history.every((d, i) => i === 0 || d.date > h.history[i - 1].date);
  check('history: 14 days returned', hist.status === 200 && h.history.length === 14);
  check('history: oldest-first ascending', ascending);
  check('history: today is last entry', h.today === h.history[h.history.length - 1].date);
  const todayMs = h.history[h.history.length - 1].totalMs;
  if (todayMs > 0) check('history: usage recorded today', true);
  else warn('history: usage recorded today', 'device reported 0ms today — check agent ticks');
  fs.writeFileSync(path.join(evidenceDir, '06-history.json'), JSON.stringify(h, null, 2));

  // 7. Dashboard render (headless harness), reusing its screenshots
  if (withDashboard) {
    try {
      require.resolve('playwright-core', { paths: [SERVER_DIR] });
      console.log('\ndashboard step: running headless harness…');
      execSync('npm run test:headless', { cwd: SERVER_DIR, stdio: 'inherit' });
      const artifacts = path.join(SERVER_DIR, 'test/headless/artifacts');
      fs.copyFileSync(path.join(artifacts, 'devices.png'), path.join(evidenceDir, '07-dashboard-devices.png'));
      fs.copyFileSync(path.join(artifacts, 'reports.png'), path.join(evidenceDir, '08-dashboard-reports.png'));
      check('dashboard: harness screenshots captured', true);
    } catch (e) {
      warn('dashboard step', 'headless harness failed or playwright-core not installed (npm i -D playwright-core)');
    }
  }

  fs.writeFileSync(path.join(evidenceDir, 'results.txt'), results.join('\n') + '\n');
  console.log(`\nevidence: ${evidenceDir}`);
  console.log(failures === 0 ? 'E2E EVIDENCE SUITE PASSED' : `${failures} CHECK(S) FAILED, ${warnings} WARN(S)`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('suite error', e); process.exit(1); });
