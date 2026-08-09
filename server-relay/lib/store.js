const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { DEFAULT_POLICY } = require('./protocol');

const DATA_DIR = path.join(__dirname, '..', 'data');
const HISTORY_DIR = path.join(DATA_DIR, 'history');
const CONFIG_FILE = path.join(DATA_DIR, 'config.json');
const STATE_FILE = path.join(DATA_DIR, 'state.json');

const RETENTION_DAYS = 90;

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

/** Atomic write: temp file + rename, so a crash can never leave a corrupt file. */
function writeAtomic(file, data) {
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, data);
  fs.renameSync(tmp, file);
}

function readJson(file, fallback, label) {
  if (!fs.existsSync(file)) return fallback;
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    console.error(`${label} is corrupt; using fallback`, e.message);
    return fallback;
  }
}

// ---------------------------------------------------------------------------
// Config (device token + parent password). Generated on first run.
// ---------------------------------------------------------------------------

function loadConfig() {
  ensureDir(DATA_DIR);
  const existing = readJson(CONFIG_FILE, null, 'config.json');
  if (existing) return existing;
  const config = {
    deviceToken: crypto.randomBytes(12).toString('hex'),
    parentPassword: crypto.randomBytes(6).toString('base64url'),
    port: Number(process.env.PORT) || 3000,
  };
  writeAtomic(CONFIG_FILE, JSON.stringify(config, null, 2));
  return config;
}

// ---------------------------------------------------------------------------
// Per-day usage history: data/history/<deviceId>/<yyyy-mm-dd>.json
// Format: { "<pkg>": ms, ..., "_hourly": { "<hour 0-23>": { "<pkg>": ms } } }
// Only the current day's file is rewritten (small, bounded writes); past days
// are immutable and pruned by retention.
// ---------------------------------------------------------------------------

/** Namespaced key holding per-hour per-app usage inside a day bucket. */
const HOURLY_KEY = '_hourly';

function stripHourly(bucket) {
  const out = { ...bucket };
  delete out[HOURLY_KEY];
  return out;
}

function dayFile(deviceId, date) {
  return path.join(HISTORY_DIR, safeSegment(deviceId), `${date}.json`);
}

function safeSegment(id) {
  return String(id).replace(/[^a-zA-Z0-9._-]/g, '_');
}

function readDay(deviceId, date) {
  return readJson(dayFile(deviceId, date), {}, `history/${safeSegment(deviceId)}/${date}.json`);
}

function writeDay(deviceId, date, bucket) {
  const file = dayFile(deviceId, date);
  ensureDir(path.dirname(file));
  writeAtomic(file, JSON.stringify(bucket, null, 2));
}

/** Migrate the old `state.usage` map into per-day history files, then drop it. */
function migrateUsage(state) {
  if (!state.usage || typeof state.usage !== 'object') return;
  let moved = 0;
  for (const [deviceId, byDate] of Object.entries(state.usage)) {
    for (const [date, apps] of Object.entries(byDate)) {
      if (!apps || typeof apps !== 'object') continue;
      const existing = readDay(deviceId, date);
      const merged = { ...existing, ...apps };
      writeDay(deviceId, date, merged);
      moved++;
    }
  }
  delete state.usage;
  if (moved) console.log(`migrated ${moved} day(s) of usage into per-day history files`);
}

/** Remove history files older than RETENTION_DAYS. Returns number pruned. */
function sweepRetention() {
  if (!fs.existsSync(HISTORY_DIR)) return 0;
  const cutoff = Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000;
  let pruned = 0;
  for (const deviceDir of fs.readdirSync(HISTORY_DIR)) {
    const full = path.join(HISTORY_DIR, deviceDir);
    if (!fs.statSync(full).isDirectory()) continue;
    for (const file of fs.readdirSync(full)) {
      const filePath = path.join(full, file);
      try {
        if (fs.statSync(filePath).mtimeMs < cutoff) {
          fs.unlinkSync(filePath);
          pruned++;
        }
      } catch (e) {
        // ignore races
      }
    }
  }
  return pruned;
}

// ---------------------------------------------------------------------------
// State: devices + policies + logs. Small, bounded, persisted atomically.
// ---------------------------------------------------------------------------

function loadState() {
  ensureDir(DATA_DIR);
  const state = readJson(STATE_FILE, null, 'state.json') || { devices: {} };
  if (!state.devices) state.devices = {};
  migrateUsage(state);
  return state;
}

function makeStore() {
  const state = loadState();
  let saveTimer = null;

  function persist() {
    if (saveTimer) return;
    saveTimer = setTimeout(() => {
      saveTimer = null;
      try {
        writeAtomic(STATE_FILE, JSON.stringify(state, null, 2));
      } catch (e) {
        console.error('failed to persist state', e);
      }
    }, 1000);
  }

  function getDevice(id) {
    if (!state.devices[id]) {
      state.devices[id] = {
        id,
        name: 'Unnamed Fire TV',
        model: 'unknown',
        version: 'unknown',
        online: false,
        lastSeen: 0,
        currentApp: null,
        locked: false,
        totalMs: 0,
        policy: DEFAULT_POLICY(),
        log: [],
      };
    }
    return state.devices[id];
  }

  function addLog(device, msg) {
    device.log.unshift({ ts: Date.now(), msg });
    if (device.log.length > 200) device.log.length = 200;
  }

  /**
   * Current-day bucket: read from the history file, apply the absolute values,
   * and persist the day file (debounced). Returns the merged bucket.
   *
   * `apps` is the cumulative per-package total since midnight (absolute, so
   * overwriting is safe). `hourly` is per-hour per-package foreground ms:
   *   { "<hour>": { "<pkg>": ms } }
   * merged under the day's `_hourly` key (also absolute per hour).
   */
  function recordUsage(deviceId, date, apps, hourly) {
    const bucket = readDay(deviceId, date);
    let dirty = false;
    for (const [pkg, ms] of Object.entries(apps || {})) {
      const prev = Number(bucket[pkg] || 0);
      const next = Number(ms);
      if (Number.isFinite(next) && next >= 0 && next !== prev) {
        bucket[pkg] = next;
        dirty = true;
      }
    }
    const hourlyIn = hourly && typeof hourly === 'object' ? hourly : {};
    if (Object.keys(hourlyIn).length > 0) {
      const dayHourly = (bucket[HOURLY_KEY] && typeof bucket[HOURLY_KEY] === 'object') ? bucket[HOURLY_KEY] : {};
      for (const [hour, byPkg] of Object.entries(hourlyIn)) {
        if (!byPkg || typeof byPkg !== 'object') continue;
        const slot = (dayHourly[hour] && typeof dayHourly[hour] === 'object') ? dayHourly[hour] : (dayHourly[hour] = {});
        for (const [pkg, ms] of Object.entries(byPkg)) {
          const next = Number(ms);
          if (Number.isFinite(next) && next >= 0 && Number(slot[pkg] || 0) !== next) {
            slot[pkg] = next;
            dirty = true;
          }
        }
      }
      bucket[HOURLY_KEY] = dayHourly;
    }
    if (dirty) writeDay(deviceId, date, bucket);
    return bucket;
  }

  /** Per-app usage for one device on one day (from history files; no hourly). */
  function usageFor(deviceId, date) {
    return stripHourly(readDay(deviceId, date));
  }

  /**
   * Aggregated history for a device over the last N days (including today).
   * Returns [{ date, totalMs, apps, hourly }] oldest first, where hourly is
   * the day's per-hour map (may be {} for days before hourly tracking).
   */
  function historyFor(deviceId, days) {
    const out = [];
    const now = new Date();
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(now);
      d.setDate(now.getDate() - i);
      const key = toDateKey(d);
      const bucket = readDay(deviceId, key);
      const apps = stripHourly(bucket);
      const hourly = (bucket[HOURLY_KEY] && typeof bucket[HOURLY_KEY] === 'object') ? bucket[HOURLY_KEY] : {};
      const totalMs = Object.values(apps).reduce((a, b) => a + Number(b || 0), 0);
      out.push({ date: key, totalMs, apps, hourly });
    }
    return out;
  }

  function sweep() {
    return sweepRetention();
  }

  return {
    state,
    persist,
    getDevice,
    addLog,
    recordUsage,
    usageFor,
    historyFor,
    sweep,
  };
}

/** yyyy-mm-dd in the server's local timezone. */
function toDateKey(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

module.exports = { loadConfig, makeStore, toDateKey, RETENTION_DAYS };
