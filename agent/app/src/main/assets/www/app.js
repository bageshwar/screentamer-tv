// ScreenTamer parent dashboard — vanilla JS, on-demand fetch (no push).
// Three views: Report (default) / Activity / Settings.
// Served by the agent's embedded server (primary), server-relay, or dev-server (mock).

const $ = (sel) => document.querySelector(sel);

let state = { devices: {}, usage: {} };
let view = 'report';

// Reports
let history = null;        // /api/history payload
let reportDeviceId = null; // device shown across all views
let reportDayIndex = -1;   // -1 = today, -2 = yesterday, ... 0 = oldest of the window

const PASSWORD_KEY = 'screentamer_password';
const password = () => localStorage.getItem(PASSWORD_KEY) || '';

function api(path, opts = {}) {
  const headers = new Headers(opts.headers || {});
  headers.set('x-parent-password', password());
  return fetch(path, { ...opts, headers });
}

function fmtDuration(ms) {
  const mins = Math.round(ms / 60000);
  if (mins < 1) return '<1m';
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function fmtShortDuration(ms) {
  const mins = Math.round(ms / 60000);
  if (mins < 1) return '<1m';
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h > 0 ? `${h}h` : `${m}m`;
}

function fmtTime(ts) {
  if (!ts) return '';
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function fmtDateTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** yyyy-mm-dd in the browser's local timezone (never UTC). */
function localDateKey(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function todayKey() {
  return localDateKey(Date.now());
}

function fmtDay(dateKey) {
  if (!dateKey) return '';
  const d = new Date(`${dateKey}T00:00:00`);
  // The server buckets history by its own local date and tells us what "today"
  // is; trust that over the browser clock so day labels always match the data.
  const today = history?.today || todayKey();
  const isToday = dateKey === today;
  const yesterday = localDateKey(new Date(`${today}T00:00:00`).getTime() - 86400000);
  const isYesterday = dateKey === yesterday;
  const label = d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
  if (isToday) return `${label} (today)`;
  if (isYesterday) return `${label} (yesterday)`;
  return label;
}

/** One-line agent health summary (observability): start count, ticks, failures. */
function healthLine(d) {
  const h = d.health;
  if (!h || typeof h !== 'object' || !h.startCount) return 'agent health: not reported yet';
  const parts = [`service started ${h.startCount}×`];
  if (h.lastStartAt) parts.push(`last start ${fmtTime(h.lastStartAt)}`);
  if (h.lastTickAt) parts.push(`last tick ${fmtTime(h.lastTickAt)}`);
  parts.push(`tick failures ${h.tickFailures || 0}`);
  const err = h.lastError;
  let line = parts.join(' · ');
  if (err && err.ts) line += ` · last error ${fmtTime(err.ts)}: ${escapeHtml(err.msg || '')}`;
  return line;
}

// ---------------------------------------------------------------------------
// App names + icons
// ---------------------------------------------------------------------------

const KNOWN = {
  'com.google.android.youtube.tv': 'YouTube',
  'com.google.android.apps.youtube.tvunplugged': 'YouTube TV',
  'com.google.android.apps.youtube.music': 'YouTube Music',
  'com.google.android.videos': 'Google TV',
  'com.netflix.ninja': 'Netflix',
  'com.amazon.amazonvideo.livingroom': 'Prime Video',
  'com.amazon.venezia': 'Prime Video',
  'com.amazon.avod.thirdpartyclient': 'Prime Video',
  'com.disney.disneyplus': 'Disney+',
  'com.hulu.livingroomplus': 'Hulu',
  'com.hbomax': 'Max',
  'com.hbo.hbonow': 'HBO Now',
  'com.peacocktv.brownstone': 'Peacock',
  'com.paramountplus.livingroom': 'Paramount+',
  'com.apple.appletv': 'Apple TV+',
  'com.cbs.ott': 'Paramount+',
  'com.nbcuni.nbc.comcasttv.android.tvlauncher': 'NBC',
  'com.turner.cnvideoapp': 'CNN',
  'com.pluto.tv': 'Pluto TV',
  'com.tubitv': 'Tubi',
  'com.roku.web.trc.testapp': 'Roku Channel',
  'com.vudu.airplay': 'Vudu',
  'com.vudu.tv': 'Vudu',
  'com.fandangonow': 'Vudu',
  'com.fubo.tv': 'Fubo',
  'com.amazon.imdb.tv.android.app': 'IMDb TV',
  'com.plexapp.android': 'Plex',
  'com.plexapp.web': 'Plex Web',
  'org.xbmc.kodi': 'Kodi',
  'com.crunchyroll.crunchyroll': 'Crunchyroll',
  'tv.twitch.android.app': 'Twitch',
  'org.videolan.vlc': 'VLC',
  'com.stremio.one': 'Stremio',
  'com.mxtech.videoplayer.ad': 'MX Player',
  'com.amazon.tv.launcher': 'Fire TV Home',
  'com.amazon.tv.mediabrowser': 'Fire TV',
  'com.amazon.tv.purchase': 'Amazon Store',
  'com.amazon.firetv.android.leanbacklauncher': 'Fire TV Home',
  'com.spotify.tv': 'Spotify',
  'com.amazon.mp3': 'Amazon Music',
  'tv.pandora.firetv': 'Pandora',
  'com.sling': 'Sling TV',
  'com.google.android.gms': 'Google Play Services',
  'com.amazon.device.messaging': 'System',
  'com.amazon.tv.settings': 'Fire TV Settings',
  'com.amazon.tv.settings.v2': 'Fire TV Settings',
};

/** package -> bundled brand icon (static/icons/<key>.svg). */
const ICONS = {
  'com.google.android.youtube.tv': 'youtube',
  'com.google.android.apps.youtube.tvunplugged': 'youtubetv',
  'com.google.android.apps.youtube.music': 'youtubemusic',
  'com.google.android.videos': 'googletv',
  'com.netflix.ninja': 'netflix',
  'com.amazon.amazonvideo.livingroom': 'primevideo',
  'com.amazon.venezia': 'primevideo',
  'com.amazon.avod.thirdpartyclient': 'primevideo',
  'com.disney.disneyplus': 'disneyplus',
  'com.hulu.livingroomplus': 'hulu',
  'com.hbomax': 'max',
  'com.hbo.hbonow': 'hbo',
  'com.peacocktv.brownstone': 'peacock',
  'com.paramountplus.livingroom': 'paramountplus',
  'com.cbs.ott': 'paramountplus',
  'com.apple.appletv': 'appletv',
  'com.pluto.tv': 'pluto',
  'com.tubitv': 'tubi',
  'com.plexapp.android': 'plex',
  'com.plexapp.web': 'plex',
  'org.xbmc.kodi': 'kodi',
  'com.crunchyroll.crunchyroll': 'crunchyroll',
  'tv.twitch.android.app': 'twitch',
  'org.videolan.vlc': 'vlc',
  'com.stremio.one': 'stremio',
  'com.spotify.tv': 'spotify',
  'com.sling': 'sling',
  'com.amazon.tv.launcher': 'firetv',
  'com.amazon.firetv.android.leanbacklauncher': 'firetv',
  'com.amazon.tv.settings': 'settings',
  'com.amazon.tv.settings.v2': 'settings',
};

/** Friendly app name: known map first, then package-name heuristics. */
function appName(pkg) {
  if (KNOWN[pkg]) return KNOWN[pkg];
  const parts = pkg.split('.');
  const generic = new Set(['tv', 'app', 'apps', 'android', 'client', 'player',
    'livingroom', 'leanback', 'debug', 'test', 'staging', 'release', 'video',
    'firetv', 'amazon', 'v2', 'ui', 'launcher', 'core', 'service', 'services',
    'mobile', 'tablet', 'media', 'activity']);
  let name = '';
  for (let i = parts.length - 1; i >= 0; i--) {
    const seg = parts[i];
    if (!generic.has(seg.toLowerCase()) && !/^\d+$/.test(seg)) { name = seg; break; }
  }
  if (!name) name = parts[parts.length - 1];
  return name
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .split(/[._-]+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

/** Deterministic colored letter avatar for a package (final fallback). */
function avatarHTML(pkg) {
  let hue = 0;
  for (const ch of pkg) hue = (hue * 31 + ch.charCodeAt(0)) % 360;
  return `<span class="app-icon" title="${escapeHtml(appName(pkg))}" style="background:hsl(${hue},50%,40%)">${escapeHtml(appName(pkg).charAt(0).toUpperCase())}</span>`;
}

/**
 * App icon markup with graceful degradation:
 *   1. the device's real icon (GET /api/icon?pkg=… — only when the agent's
 *      embedded server advertises iconEndpoint in /api/state; absent on the
 *      relay and dev server)
 *   2. a bundled brand SVG for well-known apps (static/icons/*.svg)
 *   3. a deterministic letter avatar
 */
// Icon fallback handler. The img carries data-pkg (and data-bundled for the
// device->bundled->avatar chain). Detached guard: a re-render may have removed
// the img mid-load; if so, do nothing (the replacement markup renders its own
// fallback).
function iconError(img) {
  if (!img.parentNode) return;
  const pkg = img.dataset.pkg || '';
  if (img.dataset.bundled && img.getAttribute('src') !== img.dataset.bundled) {
    img.onerror = iconError;
    img.src = img.dataset.bundled;
    return;
  }
  img.outerHTML = avatarHTML(pkg);
}

function iconHTML(pkg, device) {
  const useDevice = !!(device && device.iconEndpoint);
  const key = ICONS[pkg];
  const bundled = key ? `/static/icons/${key}.svg` : null;
  const escaped = escapeHtml(pkg);

  if (useDevice) {
    const deviceUrl = `/api/icon?pkg=${encodeURIComponent(pkg)}`;
    if (!bundled) {
      return `<img class="app-icon" alt="" data-pkg="${escaped}" src="${deviceUrl}" onerror="iconError(this)">`;
    }
    return `<img class="app-icon" alt="" data-pkg="${escaped}" data-bundled="${bundled}" src="${deviceUrl}" onerror="iconError(this)">`;
  }
  if (!bundled) return avatarHTML(pkg);
  return `<img class="app-icon" alt="" data-pkg="${escaped}" src="${bundled}" onerror="iconError(this)">`;
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

async function login() {
  const pw = $('#password').value.trim();
  if (!pw) return;
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: pw }),
  });
  if (res.ok) {
    localStorage.setItem(PASSWORD_KEY, pw);
    $('#login').classList.add('hidden');
    $('#app').classList.remove('hidden');
    console.log('[dashboard] login ok');
    loadData();
  } else {
    console.warn('[dashboard] login rejected', res.status);
    $('#loginError').classList.remove('hidden');
  }
}

function logout() {
  localStorage.removeItem(PASSWORD_KEY);
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
}

// ---------------------------------------------------------------------------
// View switching
// ---------------------------------------------------------------------------

function switchView(v) {
  view = v;
  $('#tabs').querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.view === v));
  $('#view-report').classList.toggle('hidden', v !== 'report');
  $('#view-activity').classList.toggle('hidden', v !== 'activity');
  $('#view-settings').classList.toggle('hidden', v !== 'settings');
}

// ---------------------------------------------------------------------------
// Data (on demand — fetched on load, on Refresh, and after actions)
// ---------------------------------------------------------------------------

function setConn(text, cls) {
  $('#connText').textContent = text;
  $('#connDot').className = `dot ${cls}`;
}

let loading = false;
async function loadData() {
  if (loading) return;
  loading = true;
  try {
    // State first: render() resolves the selected device, then history is
    // fetched with a real deviceId (the relay 400s on an empty one).
    const stateRes = await api('/api/state');
    if (!stateRes.ok) throw new Error(`state ${stateRes.status}`);
    state = await stateRes.json();
    render();
    const deviceId = reportDeviceId;
    if (deviceId) {
      const historyRes = await api(`/api/history?deviceId=${encodeURIComponent(deviceId)}&days=14`);
      if (historyRes.ok) {
        history = await historyRes.json();
        if (reportDayIndex < -history.history.length || reportDayIndex > -1) reportDayIndex = -1;
        $('#chartHint').textContent = `Select a bar to inspect that day · updated ${fmtTime(Date.now())}`;
        renderReport();
        renderActivity();
      }
    }
    setConn('connected', 'on');
    console.log(`[dashboard] state+history loaded (devices=${Object.keys(state.devices || {}).length}, history=${history?.history?.length || 0})`);
  } catch (e) {
    console.error('[dashboard] loadData failed:', e.message);
    setConn('offline — refresh to retry', 'err');
  } finally {
    loading = false;
  }
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

function deviceList() {
  return Object.values(state.devices || {});
}

function selectedDevice() {
  return state.devices?.[reportDeviceId] || null;
}

function render() {
  const devices = deviceList();
  $('#noDevices').classList.toggle('hidden', devices.length > 0);

  const select = $('#deviceSelect');
  if (!reportDeviceId || !devices.some((d) => d.id === reportDeviceId)) {
    reportDeviceId = devices[0]?.id || null;
  }
  const options = devices.map((d) => `<option value="${escapeHtml(d.id)}">${escapeHtml(d.name)}</option>`).join('');
  if (select.innerHTML !== options || select.value !== reportDeviceId) {
    select.innerHTML = options;
    select.value = reportDeviceId;
  }

  const device = selectedDevice();
  renderStatus('status', device);
  renderStatus('act', device);
  renderStatus('set', device);
  renderSettings();

  if (!bannerTimer) {
    const b = $('#banner');
    if (device && device.update && device.update.hasUpdate) {
      const dlLink = device.update.downloadUrl || 'https://github.com/bageshwar/screentamer-tv/releases';
      b.innerHTML = `<div style="display:flex;align-items:center;justify-content:space-between;width:100%;flex-wrap:wrap;gap:8px">
        <span>★ <strong>Update Available:</strong> Version <code>${escapeHtml(device.update.latestVersion)}</code> is ready to install.</span>
        <a href="${dlLink}" class="btn sm primary" target="_blank" style="text-decoration:none;display:inline-block">Download APK</a>
      </div>`;
      b.classList.remove('hidden');
    } else {
      b.classList.add('hidden');
    }
  }
}

/** Fill a status strip: (status|act|set)Dot/Name/Meta/NowPlaying/Badges. */
function renderStatus(prefix, device) {
  const online = !!device?.online;
  $(`#${prefix}Dot`).className = `dot ${online ? 'on' : 'off'}`;
  $(`#${prefix}Name`).textContent = device ? device.name : '—';
  $(`#${prefix}Meta`).textContent = device
    ? `${device.model || 'Fire TV'} · Fire OS ${device.version || '?'} · last seen ${fmtTime(device.lastSeen)}`
    : '—';

  const now = $('#nowPlaying');
  if (device?.currentApp) now.innerHTML = iconHTML(device.currentApp, device) + `<strong>${escapeHtml(appName(device.currentApp))}</strong>`;
  else now.innerHTML = '<strong>—</strong>';
  if (prefix !== 'status') $(`#${prefix}NowPlaying`).innerHTML = now.innerHTML;

  const badges = [
    device?.online ? '<span class="badge online">online</span>' : '<span class="badge">offline</span>',
    device?.locked ? '<span class="badge locked">🔒 locked</span>' : '',
    device?.policy?.blacklist?.length ? `<span class="badge warn">${device.policy.blacklist.length} blacklisted</span>` : '',
  ].join(' ');
  $(`#${prefix}Badges`).innerHTML = badges;
}

// ---------------------------------------------------------------------------
// Report view
// ---------------------------------------------------------------------------

function reportDay() {
  if (!history || history.history.length === 0) return null;
  const idx = history.history.length + reportDayIndex; // -1 => last (today)
  return history.history[Math.max(0, Math.min(idx, history.history.length - 1))];
}

function renderReport() {
  if (!history) return;
  const days = history.history || [];
  const day = reportDay();
  if (!day) return;

  const isToday = reportDayIndex === -1;
  $('#reportDayTitle').textContent = isToday ? 'Today\u2019s report' : 'Daily report';
  $('#reportDaySub').textContent = `${fmtDay(day.date)} · ${fmtDuration(day.totalMs)} of screen time`;

  // Summary stats (7-day window, including today).
  const last7 = days.slice(-7);
  const today = days[days.length - 1];
  const yesterday = days[days.length - 2] || null;
  const weekTotal = last7.reduce((s, d) => s + d.totalMs, 0);
  const avg = last7.length ? weekTotal / last7.length : 0;
  $('#statTodayLabel').textContent = isToday ? 'Today' : 'This day';
  $('#statToday').textContent = fmtDuration(day.totalMs);
  $('#statYesterday').textContent = yesterday ? fmtDuration(yesterday.totalMs) : '—';
  $('#statWeek').textContent = fmtDuration(weekTotal);
  $('#statAvg').textContent = fmtDuration(avg);

  // Daily bar chart (click a bar to inspect that day).
  drawDailyChart(days);

  // Per-app breakdown for the selected day.
  const label = $('#appDayLabel');
  if (day) label.textContent = fmtDay(day.date);
  drawAppBreakdown(day.apps, day.date);
}

function drawDailyChart(days) {
  const canvas = $('#dailyChart');
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const isTv = document.body.classList.contains('tv-mode');
  const width = canvas.clientWidth || canvas.parentElement.clientWidth || 600;
  const height = canvas.clientHeight || (isTv ? 300 : 220);
  canvas.width = width * dpr;
  canvas.height = height * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const padL = 44;
  const padR = 8;
  const padT = 18;
  const padB = 26;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;
  const max = Math.max(...days.map((d) => d.totalMs), 1);

  // Y gridlines (0 / 50% / 100%).
  const baseFont = isTv ? 18 : window.innerWidth < 700 ? 12 : 11;
  ctx.font = `${baseFont}px system-ui, sans-serif`;
  ctx.fillStyle = '#8b93a5';
  for (const frac of [0, 0.5, 1]) {
    const y = padT + plotH * (1 - frac);
    ctx.strokeStyle = 'rgba(255,255,255,0.06)';
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(width - padR, y);
    ctx.stroke();
    ctx.textAlign = 'right';
    ctx.fillText(fmtShortDuration(max * frac), padL - 6, y + 4);
  }

  const slot = plotW / days.length;
  const barW = Math.max(isTv ? 10 : 4, Math.min(isTv ? 46 : 30, slot * 0.6));
  days.forEach((d, i) => {
    const x = padL + slot * i + (slot - barW) / 2;
    const h = Math.max(d.totalMs > 0 ? 2 : 0, (d.totalMs / max) * plotH);
    const y = padT + plotH - h;

    const isSelected = i === days.length + reportDayIndex;
    const isToday = i === days.length - 1;
    const grad = ctx.createLinearGradient(0, y, 0, y + h);
    grad.addColorStop(0, isSelected ? '#5ec8e5' : '#3b82f6');
    grad.addColorStop(1, isSelected ? '#2f8fb5' : '#1d4ed8');
    ctx.fillStyle = grad;
    ctx.fillRect(x, y, barW, h);
    if (isSelected) {
      // Strong focus ring so the inspected day is unmistakable (D-pad friendly).
      ctx.strokeStyle = 'rgba(94, 200, 229, 0.9)';
      ctx.lineWidth = 1.5;
      ctx.strokeRect(x - 1.5, y - 1.5, barW + 3, h + 3);
    }
    if (isToday) {
      ctx.fillStyle = '#f59e0b';
      ctx.fillRect(x - 1, y - 3, barW + 2, 2);
    }

    // Value + label.
    if (h > 0) {
      ctx.fillStyle = isSelected ? '#9ae3ff' : '#cbd5e1';
      ctx.textAlign = 'center';
      ctx.fillText(fmtShortDuration(d.totalMs), x + barW / 2, y - 4);
    }
    ctx.fillStyle = i % 2 === 0 ? '#8b93a5' : '#5c6478';
    const lbl = new Date(`${d.date}T00:00:00`).toLocaleDateString([], { weekday: 'short' });
    ctx.save();
    ctx.translate(x + barW / 2, padT + plotH + 12);
    ctx.rotate(-Math.PI / 5);
    ctx.textAlign = 'right';
    ctx.fillText(lbl, 0, 0);
    ctx.restore();

    // Click to inspect this day.
    canvas._slots = canvas._slots || [];
    canvas._slots[i] = [x - slot * 0.1, padT, slot, plotH];
  });

  canvas.onclick = (e) => {
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    const slots = canvas._slots || [];
    for (let i = 0; i < slots.length; i++) {
      const [sx, sy, sw, sh] = slots[i];
      if (mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh) {
        reportDayIndex = i - days.length;
        renderReport();
        return;
      }
    }
  };
}

// Breakdown: cap rows per client (laptop 8, phone 6 with a "show more"; TV 10).
let breakdownExpanded = false;
let breakdownRenderedDay = null;

/** Curated, color-blind-safe palette for per-app usage bars (deterministic per package). */
const BAR_COLORS = ['#4f8df7', '#5ec8e5', '#3fb950', '#d29922', '#f85149', '#b57edc', '#e58f3c', '#7bd6c9', '#f0a8c8', '#a3c65e'];
function barColor(pkg) {
  let h = 0;
  for (const ch of pkg) h = (h * 31 + ch.charCodeAt(0)) % 997;
  return BAR_COLORS[h % BAR_COLORS.length];
}

function drawAppBreakdown(apps, date) {
  const host = $('#appBreakdown');
  const entries = Object.entries(apps).sort((a, b) => b[1] - a[1]);
  if (entries.length === 0) {
    host.innerHTML = '<div class="muted">no usage recorded for this day</div>';
    return;
  }
  if (breakdownRenderedDay !== date) {
    breakdownRenderedDay = date;
    breakdownExpanded = false;
  }

  const isNarrow = window.matchMedia('(max-width: 700px)').matches;
  const isTv = document.body.classList.contains('tv-mode');
  const limit = isTv ? 10 : isNarrow ? 6 : 8;
  const showAll = entries.length <= limit || breakdownExpanded;
  const top = entries.slice(0, showAll ? entries.length : limit);
  const restTotal = showAll ? 0 : entries.slice(limit).reduce((s, [, ms]) => s + ms, 0);
  const rows = [...top];
  if (restTotal > 0) rows.push(['__other__', restTotal]);
  const maxMs = Math.max(...rows.map(([, ms]) => ms), 1);

  host.innerHTML = rows
    .map(([pkg, ms]) => `
      <div class="app-row">
        ${pkg === '__other__' ? '<span class="app-icon other">…</span>' : iconHTML(pkg, selectedDevice())}
        <span class="app-name" title="${escapeHtml(pkg)}">${pkg === '__other__' ? `… ${entries.length - limit} more apps` : escapeHtml(appName(pkg))}</span>
        <div class="app-bar-wrap"><div class="app-bar" style="width:${Math.max(2, (ms / maxMs) * 100)}%;background:${pkg === '__other__' ? 'var(--muted)' : barColor(pkg)}"></div></div>
        <span class="app-ms">${fmtDuration(ms)}</span>
      </div>`).join('')
    + `<div class="breakdown-total">Total: ${fmtDuration(entries.reduce((s, [, ms]) => s + ms, 0))} — ${escapeHtml(date)}</div>`
    + (!showAll ? `<button class="btn sm show-more" id="breakdownMore">Show ${entries.length - limit} more apps</button>` : '');

  host.querySelector('#breakdownMore')?.addEventListener('click', () => {
    breakdownExpanded = true;
    renderReport();
  });
}

// ---------------------------------------------------------------------------
// Activity view
// ---------------------------------------------------------------------------

function renderActivity() {
  const device = selectedDevice();
  if (!device) return;
  const log = device.log || [];
  const list = $('#activityLog');

  if (log.length === 0) {
    list.innerHTML = '<div class="muted">no activity yet</div>';
  } else {
    // Newest first; collapse consecutive "usage reported" spam.
    const rows = [];
    let last = null;
    for (const l of [...log].reverse()) {
      if (l.msg === 'usage reported') {
        if (last === 'usage reported') continue;
        last = 'usage reported';
        rows.push({ ...l, msg: 'usage reported (heartbeat)' });
      } else {
        last = null;
        rows.push(l);
      }
    }
    list.innerHTML = rows
      .map((l) => `<div><time title="${fmtDateTime(l.ts)}">${fmtTime(l.ts)}</time><span class="log-msg">${escapeHtml(l.msg)}</span></div>`)
      .join('');
  }

  $('#healthBox').innerHTML = `
    <div class="health-line">${healthLine(device)}</div>
    <div class="health-grid">
      <div><div class="muted label">Service starts</div><div class="health-num">${device.health?.startCount ?? '—'}</div></div>
      <div><div class="muted label">Last tick</div><div class="health-num">${fmtTime(device.health?.lastTickAt)}</div></div>
      <div><div class="muted label">Tick failures</div><div class="health-num">${device.health?.tickFailures ?? 0}</div></div>
      <div><div class="muted label">Last error</div><div class="health-num">${device.health?.lastError ? fmtTime(device.health.lastError.ts) : 'none'}</div></div>
    </div>
    ${device.health?.lastError?.msg ? `<div class="health-error">${escapeHtml(device.health.lastError.msg)}</div>` : ''}`;
}

// ---------------------------------------------------------------------------
// Settings view
// ---------------------------------------------------------------------------

function renderSettings() {
  const device = selectedDevice();
  if (!device) return;
  const policy = device.policy || {};

  $('#settingsSub').textContent = `Limits, curfew & controls — ${device.name}`;

  const stopSelect = $('#stopSelect');
  const apps = Object.entries(state.usage?.[device.id] || {}).sort((a, b) => b[1] - a[1]);
  const stopOptions = apps
    .map(([pkg]) => `<option value="${escapeHtml(pkg)}">${escapeHtml(appName(pkg))}</option>`)
    .join('');
  stopSelect.innerHTML = '<option value="">Force-stop app…</option>' + stopOptions;

  const q = (sel) => document.querySelector(sel);
  const limit = q('[data-policy="limit"]');
  if (document.activeElement !== limit) limit.value = ((policy.dailyLimitMs || 0) / 3600000).toFixed(1).replace(/\.0$/, '');
  q('[data-policy="curfewOn"]').checked = !!policy.curfew?.enabled;
  q('[data-policy="curfewStart"]').value = policy.curfew?.start || '20:00';
  q('[data-policy="curfewEnd"]').value = policy.curfew?.end || '06:00';

  $('#blacklistChips').innerHTML = (policy.blacklist || []).map((pkg) => `
    <span class="chip">${iconHTML(pkg, device)}<span class="chip-name">${escapeHtml(appName(pkg))}</span><button data-act="unblacklist" data-pkg="${escapeHtml(pkg)}">×</button></span>
  `).join('') || '<span class="muted" style="font-size:12px">none</span>';

  const blacklisted = new Set(policy.blacklist || []);
  const recent = Object.entries(state.usage?.[device.id] || {})
    .filter(([pkg]) => !blacklisted.has(pkg))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6);
  $('#blacklistQuick').innerHTML = recent.length
    ? recent.map(([pkg]) => `
        <button class="chip quick" data-act="blacklistQuick" data-pkg="${escapeHtml(pkg)}" title="Add ${escapeHtml(appName(pkg))} (${escapeHtml(pkg)})">
          ${iconHTML(pkg, device)}<span class="chip-name">${escapeHtml(appName(pkg))}</span><span class="plus">+</span>
        </button>`).join('')
    : '<span class="muted" style="font-size:12px">no recent apps — type a package above</span>';

  $('#deviceInfo').innerHTML = `
    <div><span class="label">Device ID</span> ${escapeHtml(device.id)}</div>
    <div><span class="label">Model</span> ${escapeHtml(device.model || '?')}</div>
    <div><span class="label">Fire OS</span> ${escapeHtml(device.version || '?')}</div>
    <div><span class="label">Last seen</span> ${fmtDateTime(device.lastSeen)}</div>
    ${device.serverPort ? `<div><span class="label">Dashboard port</span> ${escapeHtml(device.serverPort)}</div>` : ''}`;
}

function readPolicy() {
  const policy = selectedDevice()?.policy || {};
  const limit = Number($('[data-policy="limit"]').value) * 3600000;
  return {
    dailyLimitMs: Math.round(limit),
    curfew: {
      enabled: $('[data-policy="curfewOn"]').checked,
      start: $('[data-policy="curfewStart"]').value,
      end: $('[data-policy="curfewEnd"]').value,
    },
    blacklist: policy.blacklist || [],
    lockdown: policy.lockdown || false,
  };
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

async function sendCommand(deviceId, command) {
  const res = await api('/api/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: password(), deviceId, command }),
  });
  if (!res.ok) showBanner('Command failed — check device connection');
  else console.log(`[dashboard] command sent: ${command.type} -> ${deviceId}`);
  loadData();
}

async function savePolicy(deviceId, policy) {
  const res = await api('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: password(), deviceId, policy }),
  });
  if (!res.ok) showBanner('Failed to save policy');
  else {
    console.log(`[dashboard] policy saved -> ${deviceId}: limit=${policy.dailyLimitMs} curfew=${policy.curfew?.enabled} blacklist=${(policy.blacklist || []).length}`);
    $('#policySaved').textContent = 'Saved ✓';
    setTimeout(() => { $('#policySaved').textContent = ''; }, 3000);
  }
  loadData();
}

let bannerTimer = null;
function showBanner(msg) {
  const b = $('#banner');
  b.textContent = msg;
  b.classList.remove('hidden');
  clearTimeout(bannerTimer);
  bannerTimer = setTimeout(() => b.classList.add('hidden'), 4000);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[c]);
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

// TV mode (in-app WebView, ?tv=1): bigger cards, big D-pad focus states.
if (new URLSearchParams(location.search).has('tv')) {
  document.body.classList.add('tv-mode');
}

$('#loginBtn').onclick = login;
$('#password').addEventListener('keydown', (e) => e.key === 'Enter' && login());
$('#logoutBtn').onclick = logout;
$('#refreshBtn').onclick = loadData;

$('#tabs').addEventListener('click', (e) => {
  const tab = e.target.closest('.tab');
  if (tab) switchView(tab.dataset.view);
});

$('#deviceSelect').onchange = (e) => {
  reportDeviceId = e.target.value;
  reportDayIndex = -1;
  history = null;
  breakdownRenderedDay = null;
  loadData();
};

$('#dayPrev').onclick = () => {
  const min = -(history?.history?.length || 1);
  reportDayIndex = Math.max(min, Math.min(-1, reportDayIndex - 1));
  renderReport();
};
$('#dayNext').onclick = () => {
  reportDayIndex = Math.max(-(history?.history?.length || 1), reportDayIndex + 1);
  renderReport();
};
$('#dayToday').onclick = () => {
  reportDayIndex = -1;
  renderReport();
};

// Settings actions (delegated — inputs re-render on every loadData).
$('#view-settings').addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const deviceId = reportDeviceId;
  const act = el.dataset.act;
  const policy = selectedDevice()?.policy || {};

  if (act === 'checkUpdate') {
    showBanner('Checking for updates on the TV...');
    sendCommand(deviceId, { type: 'checkUpdate' });
    setTimeout(loadData, 3000);
    return;
  }
  if (act === 'stopApp') {
    const pkg = $('#stopSelect').value;
    if (pkg) sendCommand(deviceId, { type: 'stopApp', pkg });
    return;
  }
  if (act === 'blacklist') {
    const input = $('#blacklistInput');
    const pkg = input.value.trim();
    if (!pkg) return;
    const list = new Set(policy.blacklist || []);
    list.add(pkg);
    input.value = '';
    savePolicy(deviceId, { ...policy, blacklist: [...list] });
    return;
  }
  if (act === 'blacklistQuick') {
    const pkg = el.dataset.pkg;
    const list = new Set(policy.blacklist || []);
    list.add(pkg);
    savePolicy(deviceId, { ...policy, blacklist: [...list] });
    return;
  }
  if (act === 'unblacklist') {
    const pkg = el.dataset.pkg;
    savePolicy(deviceId, { ...policy, blacklist: (policy.blacklist || []).filter((p) => p !== pkg) });
    return;
  }
  if (act === 'savePolicy') {
    savePolicy(deviceId, readPolicy());
    return;
  }
  sendCommand(deviceId, { type: act });
});

if (password()) {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  loadData();
}
