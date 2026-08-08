// Headless validation of the parent dashboard: loads it in a real browser
// (cached Playwright Chromium), logs in, and asserts the live devices view
// and Reports section render real data with no JS errors.
//
// Run with: npm run test:headless   (from server-relay/)
// Artifacts (screenshots) land in test/headless/artifacts/.
//
// Requirements:
//   - a ScreenTamer server reachable at DASH_URL (relay at 127.0.0.1:3000 by
//     default; set DASH_URL=http://127.0.0.1:8080 for the agent's embedded
//     server via adb forward, with DASH_PASSWORD=<dashboard password>)
//   - data/config.json exists when targeting the relay (generated on first
//     relay run); DASH_PASSWORD overrides it
//   - Chromium available. Resolved in this order:
//       1. PLAYWRIGHT_CHROMIUM_PATH env var
//       2. Playwright browser cache (~/Library/Caches/ms-playwright on macOS,
//          ~/.cache/ms-playwright on Linux). If missing, run:
//          npx playwright-core install chromium

const fs = require('fs');
const path = require('path');
const os = require('os');
const { chromium } = require('playwright-core');

const BASE = process.env.DASH_URL || 'http://127.0.0.1:3000';
const SERVER_DIR = path.join(__dirname, '..', '..');
const ARTIFACTS = path.join(__dirname, 'artifacts');
const PASSWORD_KEY = 'screentamer_password';
const DASH_PASSWORD = process.env.DASH_PASSWORD;

let failures = 0;
const check = (name, cond) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}`);
  if (!cond) failures++;
};

function findChromium() {
  if (process.env.PLAYWRIGHT_CHROMIUM_PATH) return process.env.PLAYWRIGHT_CHROMIUM_PATH;
  const caches = [
    path.join(os.homedir(), 'Library/Caches/ms-playwright'),
    path.join(os.homedir(), '.cache/ms-playwright'),
  ];
  const binaries = [
    (root) => path.join(root, 'chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'),
    (root) => path.join(root, 'chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'),
    (root) => path.join(root, 'headless_shell-linux-x64/headless_shell'),
    (root) => path.join(root, 'chrome-linux/chrome'),
  ];
  for (const cache of caches) {
    if (!fs.existsSync(cache)) continue;
    for (const dir of fs.readdirSync(cache).sort().reverse()) {
      const root = path.join(cache, dir);
      for (const make of binaries) {
        const exe = make(root);
        if (fs.existsSync(exe)) return exe;
      }
    }
  }
  return null;
}

async function main() {
  const exe = findChromium();
  if (!exe) {
    console.error('Chromium not found. Run: npx playwright-core install chromium');
    process.exit(1);
  }
  fs.mkdirSync(ARTIFACTS, { recursive: true });

  const config = DASH_PASSWORD
    ? { parentPassword: DASH_PASSWORD }
    : JSON.parse(fs.readFileSync(path.join(SERVER_DIR, 'data/config.json'), 'utf8'));

  const browser = await chromium.launch({ headless: true, executablePath: exe });
  const page = await browser.newPage({ viewport: { width: 1280, height: 2000 } });
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') jsErrors.push(`console: ${m.text()}`); });

  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await page.evaluate((pw) => localStorage.setItem(PASSWORD_KEY, pw), config.parentPassword);
  await page.reload({ waitUntil: 'networkidle' });

  // Wait for live WS state and the reports fetch to both land.
  await page.waitForFunction(() => document.querySelectorAll('#devices .card').length > 0, { timeout: 15000 });
  await page.waitForFunction(() => document.querySelector('#statWeek')?.textContent !== '—', { timeout: 15000 });
  await page.waitForTimeout(1500);

  const checks = await page.evaluate(() => {
    const canvas = document.querySelector('#dailyChart');
    let painted = 0;
    if (canvas) {
      const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
      for (let i = 3; i < data.length; i += 4) if (data[i] > 0) painted++;
    }
    return {
      deviceCards: document.querySelectorAll('#devices .card').length,
      conn: document.querySelector('#connText')?.textContent || '',
      statToday: document.querySelector('#statToday')?.textContent,
      statWeek: document.querySelector('#statWeek')?.textContent,
      statAvg: document.querySelector('#statAvg')?.textContent,
      dayLabel: document.querySelector('#appDayLabel')?.textContent,
      appRows: document.querySelectorAll('#appBreakdown .app-row').length,
      chartPainted: painted,
      reportsHidden: document.querySelector('#reports')?.classList.contains('hidden'),
    };
  });

  check('devices render', checks.deviceCards > 0);
  check('dashboard data connected (on-demand fetch)', checks.conn.includes('connected'));
  check('today stat populated', !!checks.statToday && checks.statToday !== '—');
  check('7-day stat populated', !!checks.statWeek && checks.statWeek !== '—');
  check('daily avg populated', !!checks.statAvg && checks.statAvg !== '—');
  check('daily chart painted', checks.chartPainted > 1000);
  // Today may legitimately be empty (device idle or first day); rows are
  // asserted on a data-bearing day after navigation below.
  check('per-app breakdown renders', checks.appRows > 0 || (await page.$('#appBreakdown .muted')) !== null);
  check('day label set', !!checks.dayLabel);
  check('reports section visible', checks.reportsHidden === false);

  // Interaction: day navigation changes the breakdown label and shows real
  // app rows for a day that has data.
  const before = checks.dayLabel;
  await page.click('#dayPrev');
  await page.waitForTimeout(200);
  const after = await page.$eval('#appDayLabel', (el) => el.textContent);
  check('day navigation works', !!before && before !== after);
  const rowsAfterNav = await page.$$eval('#appBreakdown .app-row', (els) => els.length);
  if (rowsAfterNav === 0) {
    // If the previous day is empty too, step further back until a data day
    // appears (capped at the window size)…
    let rows = rowsAfterNav;
    for (let i = 0; i < 12 && rows === 0; i++) {
      await page.click('#dayPrev');
      await page.waitForTimeout(150);
      rows = await page.$$eval('#appBreakdown .app-row', (els) => els.length);
    }
    if (rows === 0) {
      // …then step forward back to today: a freshly-paired device only has
      // data on the current day, so this is the authoritative data-day check.
      for (let i = 0; i < 14; i++) {
        await page.click('#dayNext');
        await page.waitForTimeout(150);
        rows = await page.$$eval('#appBreakdown .app-row', (els) => els.length);
        if (rows > 0) break;
      }
    }
    check('per-app rows render for a data day', rows > 0);
  } else {
    check('per-app rows render for a data day', true);
  }

  // Human-readable render summary (textual evidence).
  const summary = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#appBreakdown .app-row')].map((r) => ({
      name: r.querySelector('.app-name')?.textContent,
      ms: r.querySelector('.app-ms')?.textContent,
    }));
    return {
      today: document.querySelector('#statToday')?.textContent,
      yesterday: document.querySelector('#statYesterday')?.textContent,
      week: document.querySelector('#statWeek')?.textContent,
      avg: document.querySelector('#statAvg')?.textContent,
      range: document.querySelector('#reportRange')?.textContent,
      day: document.querySelector('#appDayLabel')?.textContent,
      rows,
    };
  });
  console.log('\nrender summary:');
  console.log(JSON.stringify(summary, null, 2));

  await page.screenshot({ path: path.join(ARTIFACTS, 'reports.png'), fullPage: false });
  await page.evaluate(() => document.querySelector('#devices').scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(ARTIFACTS, 'devices.png'), fullPage: false });

  check('no JS errors', jsErrors.length === 0);
  if (jsErrors.length) jsErrors.forEach((e) => console.log('  ' + e));

  await browser.close();
  console.log(failures === 0 ? '\nHEADLESS VALIDATION PASSED' : `\n${failures} CHECK(S) FAILED`);
  console.log(`screenshots: ${ARTIFACTS}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('harness error', e); process.exit(1); });
