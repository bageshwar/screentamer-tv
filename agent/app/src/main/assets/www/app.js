// ScreenTamer parent dashboard — vanilla JS, on-demand fetch (no push).
// Served by the agent's embedded server (primary) or server-relay (optional).

const $ = (sel) => document.querySelector(sel);

let state = { devices: {}, usage: {} };

// Reports
let history = null;        // /api/history payload
let reportDeviceId = null; // device shown in reports
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

function fmtTime(ts) {
  if (!ts) return '';
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function fmtDateTime(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
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

/** yyyy-mm-dd in the browser's local timezone (never UTC). */
function localDateKey(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function todayKey() {
  return localDateKey(Date.now());
}

function appName(pkg) {
  const KNOWN = {
    'com.google.android.youtube.tv': 'YouTube',
    'com.netflix.ninja': 'Netflix',
    'com.amazon.amazonvideo.livingroom': 'Prime Video',
    'com.disney.disneyplus': 'Disney+',
    'com.hulu.livingroomplus': 'Hulu',
    'com.hbomax': 'Max',
    'com.peacocktv.brownstone': 'Peacock',
    'com.apple.appletv': 'Apple TV+',
    'com.tubitv': 'Tubi',
    'com.pluto.tv': 'Pluto TV',
    'com.spotify.tv': 'Spotify',
    'com.sling': 'Sling TV',
    'com.paramountplus.livingroom': 'Paramount+',
  };
  return KNOWN[pkg] || pkg;
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
    const [stateRes, historyRes] = await Promise.all([
      api('/api/state'),
      api(`/api/history?deviceId=${encodeURIComponent(reportDeviceId || '')}&days=14`),
    ]);
    if (!stateRes.ok) throw new Error(`state ${stateRes.status}`);
    state = await stateRes.json();
    render();
    if (historyRes.ok) {
      history = await historyRes.json();
      if (reportDayIndex < -history.history.length || reportDayIndex > -1) reportDayIndex = -1;
      renderReportsContent();
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

function render() {
  const devices = Object.values(state.devices || {});
  const host = $('#devices');
  host.innerHTML = '';
  $('#noDevices').classList.toggle('hidden', devices.length > 0);
  for (const device of devices) host.appendChild(deviceCard(device));
  renderReports(devices);
}

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

function renderReports(devices) {
  const section = $('#reports');
  section.classList.toggle('hidden', devices.length === 0);
  if (devices.length === 0) return;

  const select = $('#reportDevice');
  if (!reportDeviceId || !devices.some((d) => d.id === reportDeviceId)) {
    reportDeviceId = devices[0].id;
  }
  const current = select.value;
  const options = devices.map((d) => `<option value="${escapeHtml(d.id)}">${escapeHtml(d.name)}</option>`).join('');
  if (select.innerHTML !== options || current !== reportDeviceId) {
    select.innerHTML = options;
    select.value = reportDeviceId;
  }
  renderReportsContent();
}

async function loadHistory(deviceId) {
  if (!deviceId) return;
  try {
    const res = await api(`/api/history?deviceId=${encodeURIComponent(deviceId)}&days=14`);
    if (!res.ok) return;
    const data = await res.json();
    history = data;
    if (reportDayIndex < -data.history.length || reportDayIndex > -1) reportDayIndex = -1;
    renderReportsContent();
  } catch (e) {
    /* on-demand; next refresh retries */
  }
}

function reportDay() {
  if (!history || history.history.length === 0) return null;
  const idx = history.history.length + reportDayIndex; // -1 => last (today)
  return history.history[Math.max(0, Math.min(idx, history.history.length - 1))];
}

function renderReportsContent() {
  if (!history) return;
  const days = history.history || [];
  $('#reportRange').textContent = `${fmtDay(days[0].date)} — ${fmtDay(days[days.length - 1].date)}`;

  // Summary stats (7-day window, including today).
  const last7 = days.slice(-7);
  const today = days[days.length - 1];
  const yesterday = days[days.length - 2] || null;
  const weekTotal = last7.reduce((s, d) => s + d.totalMs, 0);
  const avg = last7.length ? weekTotal / last7.length : 0;
  $('#statToday').textContent = fmtDuration(today.totalMs);
  $('#statYesterday').textContent = yesterday ? fmtDuration(yesterday.totalMs) : '—';
  $('#statWeek').textContent = fmtDuration(weekTotal);
  $('#statAvg').textContent = fmtDuration(avg);

  // Daily bar chart.
  drawDailyChart(days);

  // Per-app breakdown for the selected day.
  const day = reportDay();
  const label = $('#appDayLabel');
  if (day) label.textContent = fmtDay(day.date);
  drawAppBreakdown(day ? day.apps : {}, day ? day.date : '');
}

function drawDailyChart(days) {
  const canvas = $('#dailyChart');
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const width = canvas.clientWidth || canvas.parentElement.clientWidth || 600;
  const height = canvas.clientHeight || 220;
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
  ctx.font = '11px system-ui, sans-serif';
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
  const barW = Math.max(4, Math.min(30, slot * 0.6));
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
        renderReportsContent();
        return;
      }
    }
  };
}

function fmtShortDuration(ms) {
  const mins = Math.round(ms / 60000);
  if (mins < 1) return '<1m';
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h > 0 ? `${h}h` : `${m}m`;
}

function drawAppBreakdown(apps, date) {
  const host = $('#appBreakdown');
  const entries = Object.entries(apps).sort((a, b) => b[1] - a[1]);
  if (entries.length === 0) {
    host.innerHTML = '<div class="muted">no data for this day</div>';
    return;
  }
  const top = entries.slice(0, 8);
  const rest = entries.slice(8);
  const restTotal = rest.reduce((s, [, ms]) => s + ms, 0);
  const rows = [...top];
  if (restTotal > 0) rows.push(['__other__', restTotal]);
  const maxMs = Math.max(...rows.map(([, ms]) => ms), 1);

  host.innerHTML = rows
    .map(([pkg, ms]) => `
      <div class="app-row">
        <span class="app-name" title="${escapeHtml(pkg)}">${pkg === '__other__' ? `… ${rows.length - top.length} more apps` : escapeHtml(appName(pkg))}</span>
        <div class="app-bar-wrap"><div class="app-bar" style="width:${Math.max(2, (ms / maxMs) * 100)}%"></div></div>
        <span class="app-ms">${fmtDuration(ms)}</span>
      </div>`).join('')
    + `<div class="muted" style="font-size:12px;margin-top:8px">Total: ${fmtDuration(rows.reduce((s, [, ms]) => s + ms, 0))} — ${escapeHtml(date)}</div>`;
}

function deviceCard(d) {
  const card = document.createElement('div');
  card.className = 'card';

  const usage = (state.usage || {})[d.id] || {};
  const apps = Object.entries(usage).sort((a, b) => b[1] - a[1]);
  const total = d.totalMs || apps.reduce((s, [, ms]) => s + ms, 0);
  const maxMs = Math.max(...apps.map(([, ms]) => ms), 1);
  const policy = d.policy || {};

  const chips = [
    d.online ? '<span class="badge online">online</span>' : '<span class="badge">offline</span>',
    d.locked ? '<span class="badge locked">locked</span>' : '',
  ].join(' ');

  card.innerHTML = `
    <div class="device-head">
      <span class="dot ${d.online ? 'on' : 'off'}"></span>
      <h2></h2>
      ${chips}
    </div>
    <div class="device-meta muted">${escapeHtml(d.model || 'Fire TV')} · Fire OS ${escapeHtml(d.version || '?')} · last seen ${fmtTime(d.lastSeen)}</div>
    <div class="current-app">Now playing: <strong>${escapeHtml(d.currentApp ? appName(d.currentApp) : '—')}</strong></div>

    <div class="grid-2">
      <div>
        <div class="muted">Screen time today</div>
        <div class="usage-total">${fmtDuration(total)}</div>
        ${apps.length === 0 ? '<div class="muted" style="font-size:13px">No usage recorded yet — agent will report every 30s.</div>' : apps.map(([pkg, ms]) => `
          <div class="app-row">
            <span class="app-name" title="${escapeHtml(pkg)}">${escapeHtml(appName(pkg))}</span>
            <div class="app-bar-wrap"><div class="app-bar" style="width:${Math.max(2, (ms / maxMs) * 100)}%"></div></div>
            <span class="app-ms">${fmtDuration(ms)}</span>
          </div>`).join('')}

        <div class="controls">
          <button class="btn" data-act="lock">Lock now</button>
          <button class="btn" data-act="unlock">Unlock</button>
          <button class="btn" data-act="pause">Pause</button>
          <button class="btn" data-act="play">Play</button>
          <button class="btn" data-act="home">Go home</button>
          <select class="btn" id="stopSelect-${d.id}">
            <option value="">Force-stop app…</option>
            ${apps.map(([pkg]) => `<option value="${escapeHtml(pkg)}">${escapeHtml(appName(pkg))}</option>`).join('')}
          </select>
          <button class="btn sm" data-act="stopApp" data-device="${escapeHtml(d.id)}">Stop</button>
        </div>
      </div>

      <div class="policy">
        <h3>Limits & curfew</h3>
        <div class="field">
          <label>Daily limit</label>
          <input type="number" min="0" step="0.5" value="${(policy.dailyLimitMs || 0) / 3600000}" data-policy="limit" />
          <span class="muted">hours (0 = none)</span>
        </div>
        <div class="field">
          <label>Curfew</label>
          <input type="checkbox" data-policy="curfewOn" ${policy.curfew?.enabled ? 'checked' : ''} />
          <input type="time" data-policy="curfewStart" value="${escapeHtml(policy.curfew?.start || '20:00')}" />
          <span class="muted">to</span>
          <input type="time" data-policy="curfewEnd" value="${escapeHtml(policy.curfew?.end || '06:00')}" />
        </div>
        <div class="field">
          <label>Blacklist</label>
        </div>
        <div class="blacklist-chips">
          ${(policy.blacklist || []).map((pkg) => `
            <span class="chip">${escapeHtml(appName(pkg))}<button data-act="unblacklist" data-pkg="${escapeHtml(pkg)}">×</button></span>
          `).join('') || '<span class="muted" style="font-size:12px">none</span>'}
        </div>
        <div class="add-blacklist">
          <input type="text" placeholder="package, e.g. com.netflix.ninja" />
          <button class="btn sm" data-act="blacklist">Add</button>
        </div>
        <button class="btn primary" data-act="savePolicy" style="margin-top:12px">Save policy</button>
      </div>
    </div>

    <div class="log">
      <h3>Activity</h3>
      <div class="log-list">
        ${(d.log || []).map((l) => `<div><time title="${fmtDateTime(l.ts)}">${fmtTime(l.ts)}</time>${escapeHtml(l.msg)}</div>`).join('') || '<div>no activity yet</div>'}
      </div>
      <div class="health muted" style="margin-top:8px;font-size:12px">${healthLine(d)}</div>
    </div>
  `;

  card.querySelector('.device-head h2').textContent = d.name || 'Unnamed Fire TV';
  const stopBtn = card.querySelector('[data-act="stopApp"]');
  if (stopBtn) {
    stopBtn.onclick = () => {
      const sel = card.querySelector(`#stopSelect-${CSS.escape(d.id)}`);
      const pkg = sel && sel.value;
      if (pkg) sendCommand(d.id, { type: 'stopApp', pkg });
    };
  }

  card.querySelectorAll('[data-act]').forEach((el) => {
    const act = el.dataset.act;
    if (act === 'stopApp') return;
    el.onclick = () => {
      if (act === 'lock') return sendCommand(d.id, { type: 'lock' });
      if (act === 'unlock') return sendCommand(d.id, { type: 'unlock' });
      if (act === 'pause') return sendCommand(d.id, { type: 'pause' });
      if (act === 'play') return sendCommand(d.id, { type: 'play' });
      if (act === 'home') return sendCommand(d.id, { type: 'home' });
      if (act === 'blacklist') {
        const input = el.closest('.add-blacklist').querySelector('input');
        const pkg = input.value.trim();
        if (!pkg) return;
        const list = new Set(policy.blacklist || []);
        list.add(pkg);
        savePolicy(d.id, { ...policy, blacklist: [...list] }, input);
      }
      if (act === 'unblacklist') {
        const pkg = el.dataset.pkg;
        savePolicy(d.id, { ...policy, blacklist: (policy.blacklist || []).filter((p) => p !== pkg) });
      }
      if (act === 'savePolicy') savePolicy(d.id, readPolicy(card, policy));
    };
  });

  return card;
}

function readPolicy(card, current) {
  const limit = Number(card.querySelector('[data-policy="limit"]').value) * 3600000;
  return {
    dailyLimitMs: Math.round(limit),
    curfew: {
      enabled: card.querySelector('[data-policy="curfewOn"]').checked,
      start: card.querySelector('[data-policy="curfewStart"]').value,
      end: card.querySelector('[data-policy="curfewEnd"]').value,
    },
    blacklist: current.blacklist || [],
    lockdown: current.lockdown || false,
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

async function savePolicy(deviceId, policy, focusInput) {
  const res = await api('/api/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: password(), deviceId, policy }),
  });
  if (!res.ok) showBanner('Failed to save policy');
  else console.log(`[dashboard] policy saved -> ${deviceId}: limit=${policy.dailyLimitMs} curfew=${policy.curfew?.enabled} blacklist=${(policy.blacklist || []).length}`);
  if (focusInput) focusInput.value = '';
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

$('#loginBtn').onclick = login;
$('#password').addEventListener('keydown', (e) => e.key === 'Enter' && login());
$('#logoutBtn').onclick = logout;
$('#refreshBtn').onclick = loadData;

$('#reportDevice').onchange = (e) => {
  reportDeviceId = e.target.value;
  reportDayIndex = -1;
  loadHistory(reportDeviceId);
};
$('#dayPrev').onclick = () => {
  const min = -(history?.history?.length || 1);
  reportDayIndex = Math.max(min, Math.min(-1, reportDayIndex - 1));
  renderReportsContent();
};
$('#dayNext').onclick = () => {
  reportDayIndex = Math.max(-(history?.history?.length || 1), reportDayIndex + 1);
  renderReportsContent();
};
$('#dayToday').onclick = () => {
  reportDayIndex = -1;
  renderReportsContent();
};

if (password()) {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  loadData();
}
