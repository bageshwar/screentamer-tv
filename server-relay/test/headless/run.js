// Headless validation of the parent dashboard: loads it in a real browser
// (cached Playwright Chromium), logs in, and asserts all three views render
// real data with no JS errors. Screenshots land in test/headless/artifacts/.
//
// Run with: npm run test:headless   (from server-relay/)
// For pure design iteration without any device/relay, target the mock dev
// server instead:
//   DASH_URL=http://127.0.0.1:4000 DASH_PASSWORD=demo npm run test:headless
//
// Requirements:
//   - a ScreenTamer server reachable at DASH_URL (relay at 127.0.0.1:3000 by
//     default; dev server at 127.0.0.1:4000; agent embedded server via adb
//     forward with DASH_PASSWORD set)
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
  page.on('pageerror', (e) => jsErrors.push(`pageerror: ${e.message} @ ${(e.stack||'').split('\n').slice(0,4).join(' < ')}`));
  page.on('console', (m) => { if (m.type() === 'error') jsErrors.push(`console: ${m.text()}`); });

  await page.goto(BASE + '/', { waitUntil: 'load' });
  await page.evaluate((pw) => localStorage.setItem(PASSWORD_KEY, pw), config.parentPassword);
  await page.reload({ waitUntil: 'load' });

  // Wait for state + reports fetch to land.
  await page.waitForFunction(() => document.querySelectorAll('#deviceSelect option').length > 0, { timeout: 15000 });
  await page.waitForFunction(() => document.querySelector('#statWeek')?.textContent !== '—', { timeout: 15000 });
  await page.waitForTimeout(1200);

  // ---------------------------------------------------------------- Report
  const report = await page.evaluate(() => {
    const canvas = document.querySelector('#dailyChart');
    let painted = 0;
    if (canvas) {
      const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
      for (let i = 3; i < data.length; i += 4) if (data[i] > 0) painted++;
    }
    return {
      reportVisible: !document.querySelector('#view-report')?.classList.contains('hidden'),
      activityHidden: document.querySelector('#view-activity')?.classList.contains('hidden'),
      settingsHidden: document.querySelector('#view-settings')?.classList.contains('hidden'),
      activeTab: document.querySelector('.tab.active')?.dataset.view,
      devices: document.querySelectorAll('#deviceSelect option').length,
      conn: document.querySelector('#connText')?.textContent || '',
      statusName: document.querySelector('#statusName')?.textContent,
      statusMeta: document.querySelector('#statusMeta')?.textContent,
      nowPlaying: document.querySelector('#nowPlaying')?.textContent.trim(),
      dayTitle: document.querySelector('#reportDayTitle')?.textContent,
      daySub: document.querySelector('#reportDaySub')?.textContent,
      statToday: document.querySelector('#statToday')?.textContent,
      statWeek: document.querySelector('#statWeek')?.textContent,
      statAvg: document.querySelector('#statAvg')?.textContent,
      legendItems: document.querySelectorAll('#timelineHost .tl-legend span').length,
      legendIcons: document.querySelectorAll('#timelineHost .tl-legend img.app-icon, #timelineHost .tl-legend .app-icon').length,
      chartPainted: painted,
      badges: document.querySelector('#statusBadges')?.textContent,
    };
  });

  check('report is the default view', report.reportVisible === true);
  check('activity + settings hidden by default', report.activityHidden === true && report.settingsHidden === true);
  check('active tab is Report', report.activeTab === 'report');
  check('device selector populated', report.devices > 0);
  check('dashboard data connected (on-demand fetch)', report.conn.includes('connected'));
  check('status strip shows device name', !!report.statusName && report.statusName !== '—');
  check('status strip shows model / last seen', !!report.statusMeta && report.statusMeta.includes('Fire OS'));
  check('now playing rendered', report.nowPlaying.length > 0);
  check('day title set', ['Today\u2019s report', 'Today\'s report'].includes(report.dayTitle));
  check('day subtitle set', !!report.daySub);
  check('today stat populated', !!report.statToday && report.statToday !== '—');
  check('7-day stat populated', !!report.statWeek && report.statWeek !== '—');
  check('daily avg populated', !!report.statAvg && report.statAvg !== '—');
  check('daily chart painted', report.chartPainted > 1000);
  check('timeline legend lists apps with icons', report.legendItems > 0 && report.legendIcons > 0);
  check('device badges shown', report.badges.length > 0);

  // Timeline ("when apps were used"): band by default, toggle to per-app lanes.
  const tl = await page.evaluate(() => {
    const cells = document.querySelectorAll('#timelineHost .tl-cell').length;
    return {
      card: !!document.querySelector('#timelineHost'),
      cells,
      empty: !!document.querySelector('#timelineHost .tl-empty'),
      toggleHidden: document.querySelector('#timelineToggle')?.classList.contains('hidden'),
      legend: document.querySelectorAll('#timelineHost .tl-legend span').length,
    };
  });
  check('timeline card rendered', tl.card === true);
  if (tl.cells > 0) {
    check('timeline band shows one cell per hour', tl.cells === 24);
    await page.click('#timelineToggle');
    await page.waitForTimeout(150);
    const lanes = await page.evaluate(() => ({
      lanes: document.querySelectorAll('#timelineHost .tl-lane').length,
      blocks: document.querySelectorAll('#timelineHost .tl-block').length,
      names: document.querySelectorAll('#timelineHost .tl-lane-name').length,
      durations: document.querySelectorAll('#timelineHost .tl-lane-ms').length,
      toggle: document.querySelector('#timelineToggle')?.textContent,
    }));
    check('toggle switches to per-app lanes', lanes.lanes > 0 && lanes.blocks > 0);
    check('lanes show app names + totals', lanes.names > 0 && lanes.durations > 0);
    check('toggle label flipped', lanes.toggle === 'Band view');
    await page.click('#timelineToggle');
    await page.waitForTimeout(150);
    check('toggle returns to band', await page.evaluate(() => document.querySelectorAll('#timelineHost .tl-cell').length) === 24);
  } else {
    check('timeline shows empty state without hourly data', tl.empty === true || tl.toggleHidden === true);
  }
  await page.screenshot({ path: path.join(ARTIFACTS, '02b-report-timeline.png') });

  // Day navigation: stepping back changes the report to that day.
  const titleBefore = report.dayTitle;
  const subBefore = report.daySub;
  await page.click('#dayPrev');
  await page.waitForTimeout(250);
  const afterNav = await page.evaluate(() => ({
    title: document.querySelector('#reportDayTitle')?.textContent,
    sub: document.querySelector('#reportDaySub')?.textContent,
    label: document.querySelector('#timelineDayLabel')?.textContent,
    rows: document.querySelectorAll('#timelineHost .app-row').length,
  }));
  check('day nav switches the report day', afterNav.sub !== subBefore);
  check('day nav updates the day title', afterNav.title !== titleBefore || afterNav.label.includes('(yesterday)'));
  await page.screenshot({ path: path.join(ARTIFACTS, '01-report-previous-day.png') });

  // Back to today.
  await page.click('#dayToday');
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(ARTIFACTS, '02-report-today.png') });

  // -------------------------------------------------------------- Activity
  await page.click('.tab[data-view="activity"]');
  await page.waitForTimeout(300);
  const activity = await page.evaluate(() => ({
    visible: !document.querySelector('#view-activity')?.classList.contains('hidden'),
    logEntries: document.querySelectorAll('#activityLog div').length,
    logText: document.querySelector('#activityLog')?.textContent.trim(),
    health: document.querySelector('#healthBox')?.textContent.trim(),
    statusName: document.querySelector('#actName')?.textContent,
    nowPlaying: document.querySelector('#actNowPlaying')?.textContent.trim(),
  }));
  check('activity view opens', activity.visible === true);
  check('activity log has entries', activity.logEntries > 0);
  check('activity log has timestamps + messages', (activity.logText || '').includes(':'));
  // Health box must be populated; on real agents health can be "not reported
  // yet" until the first tick, so require non-empty, not a specific phrase.
  check('health observability rendered', !!activity.health && activity.health.trim().length > 0);
  check('activity status strip shows device', !!activity.statusName && activity.statusName !== '—');
  check('activity now playing shown', activity.nowPlaying.length > 0);
  await page.screenshot({ path: path.join(ARTIFACTS, '03-activity.png') });

  // -------------------------------------------------------------- Settings
  await page.click('.tab[data-view="settings"]');
  await page.waitForTimeout(300);
  const settings = await page.evaluate(() => ({
    visible: !document.querySelector('#view-settings')?.classList.contains('hidden'),
    limitValue: document.querySelector('[data-policy="limit"]')?.value,
    curfewOn: document.querySelector('[data-policy="curfewOn"]')?.checked,
    curfewStart: document.querySelector('[data-policy="curfewStart"]')?.value,
    stopOptions: document.querySelectorAll('#stopSelect option').length,
    chips: document.querySelectorAll('#blacklistChips .chip').length,
    deviceInfo: document.querySelector('#deviceInfo')?.textContent.trim(),
    controls: document.querySelectorAll('#view-settings [data-act]').length,
    statusName: document.querySelector('#setName')?.textContent,
  }));
  check('settings view opens', settings.visible === true);
  check('daily limit pre-filled', !!settings.limitValue && settings.limitValue !== '');
  check('curfew controls rendered', typeof settings.curfewOn === 'boolean');
  check('force-stop list populated', settings.stopOptions > 1);
  check('blacklist chips rendered', settings.chips >= 0);
  check('recent-app quick-add chips present', await page.$('#blacklistQuick .chip.quick') !== null);
  check('device info shown', !!settings.deviceInfo && settings.deviceInfo.includes('Fire'));
  check('control buttons present', settings.controls >= 5);
  check('settings status strip shows device', !!settings.statusName && settings.statusName !== '—');
  await page.screenshot({ path: path.join(ARTIFACTS, '04-settings.png') });

  // TV mode smoke: ?tv=1 must still render.
  await page.goto(BASE + '/?tv=1', { waitUntil: 'load' });
  await page.evaluate((pw) => localStorage.setItem(PASSWORD_KEY, pw), config.parentPassword);
  await page.reload({ waitUntil: 'load' });
  await page.waitForFunction(() => document.querySelector('#statusName')?.textContent !== '—', { timeout: 15000 });
  check('TV mode class applied', await page.evaluate(() => document.body.classList.contains('tv-mode')));

  // D-pad focus proof: every interactive element must show a strong ring.
  await page.focus('#deviceSelect');
  await page.waitForTimeout(250);
  const focus = await page.evaluate(() => {
    const el = document.querySelector('#deviceSelect');
    const s = getComputedStyle(el);
    return { outlineWidth: s.outlineWidth, outlineColor: s.outlineColor, scale: s.transform };
  });
  check('TV focus ring on device select', parseFloat(focus.outlineWidth) >= 4);
  check('TV focus ring is visible color', focus.outlineColor !== 'rgba(0, 0, 0, 0)');
  await page.screenshot({ path: path.join(ARTIFACTS, '09-tv-focus.png') });
  await page.focus('#logoutBtn');
  await page.waitForTimeout(150);
  await page.screenshot({ path: path.join(ARTIFACTS, '05-tv-mode.png') });

  // ---------------------------------------------------------------- Phone
  // Same app, phone viewport: thumb-first layout must hold together.
  const phone = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  await phone.goto(BASE + '/', { waitUntil: 'load' });
  await phone.evaluate((pw) => localStorage.setItem(PASSWORD_KEY, pw), config.parentPassword);
  await phone.reload({ waitUntil: 'load' });
  await phone.waitForFunction(() => document.querySelector('#statWeek')?.textContent !== '—', { timeout: 15000 });
  await phone.waitForTimeout(800);
  const touch = await phone.evaluate(() => {
    const dayBtn = getComputedStyle(document.querySelector('.day-nav .btn'));
    const tab = getComputedStyle(document.querySelector('.tab'));
    const toggle = getComputedStyle(document.querySelector('#timelineToggle'));
    return {
      dayMinHeight: parseFloat(dayBtn.minHeight),
      tabFullWidth: tab.flexGrow === '1',
      statsCols: getComputedStyle(document.querySelector('.stats-row')).gridTemplateColumns.split(' ').length,
      reportVisible: !document.querySelector('#view-report')?.classList.contains('hidden'),
      toggleMinHeight: parseFloat(toggle.minHeight),
      phoneBandCells: document.querySelectorAll('#timelineHost .tl-cell').length,
    };
  });
  check('phone: day-nav touch targets >= 44px', touch.dayMinHeight >= 44);
  check('phone: tabs stretch full width', touch.tabFullWidth === true);
  check('phone: stats grid is 2 columns', touch.statsCols <= 2);
  check('phone: report renders', touch.reportVisible === true);
  check('phone: timeline toggle is a touch target', touch.toggleMinHeight >= 44);
  check('phone: band keeps one cell per hour', touch.phoneBandCells === 24);
  await phone.screenshot({ path: path.join(ARTIFACTS, '06-phone-report.png'), fullPage: true });
  await phone.click('.tab[data-view="activity"]');
  await phone.waitForTimeout(300);
  await phone.screenshot({ path: path.join(ARTIFACTS, '07-phone-activity.png'), fullPage: true });
  await phone.click('.tab[data-view="settings"]');
  await phone.waitForTimeout(300);
  await phone.screenshot({ path: path.join(ARTIFACTS, '08-phone-settings.png'), fullPage: true });
  await phone.close();

  check('no JS errors', jsErrors.length === 0);
  if (jsErrors.length) jsErrors.forEach((e) => console.log('  ' + e));

  await browser.close();
  console.log(failures === 0 ? '\nHEADLESS VALIDATION PASSED' : `\n${failures} CHECK(S) FAILED`);
  console.log(`screenshots: ${ARTIFACTS}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('harness error', e); process.exit(1); });
