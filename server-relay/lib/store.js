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
// Only the current day's file is rewritten (small, bounded writes); past days
// are immutable and pruned by retention.
// ---------------------------------------------------------------------------

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
   * Current-day bucket: read from the history file, apply the delta, and
   * persist the day file (debounced). Returns the merged bucket.
   */
  function recordUsage(deviceId, date, apps) {
    const bucket = readDay(deviceId, date);
    let dirty = false;
    for (const [pkg, ms] of Object.entries(apps)) {
      const prev = Number(bucket[pkg] || 0);
      const next = Number(ms);
      if (Number.isFinite(next) && next >= 0 && next !== prev) {
        bucket[pkg] = next;
        dirty = true;
      }
    }
    if (dirty) writeDay(deviceId, date, bucket);
    return bucket;
  }

  /** Per-app usage for one device on one day (from history files). */
  function usageFor(deviceId, date) {
    return readDay(deviceId, date);
  }

  /**
   * Aggregated history for a device over the last N days (including today).
   * Returns [{ date, totalMs, apps }] oldest first.
   */
  function historyFor(deviceId, days) {
    const out = [];
    const now = new Date();
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(now);
      d.setDate(now.getDate() - i);
      const key = toDateKey(d);
      const apps = readDay(deviceId, key);
      if (Object.keys(apps).length === 0) {
        out.push({ date: key, totalMs: 0, apps: {} });
        continue;
      }
      const totalMs = Object.values(apps).reduce((a, b) => a + Number(b || 0), 0);
      out.push({ date: key, totalMs, apps });
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
