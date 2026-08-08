// Shared message type constants (mirrors agent/data/Protocol.kt)
module.exports = {
  HELLO: 'hello',
  WELCOME: 'welcome',
  USAGE: 'usage',
  LOG: 'log',
  CONFIG: 'config',
  COMMAND: 'command',

  CMD_PAUSE: 'pause',
  CMD_PLAY: 'play',
  CMD_HOME: 'home',
  CMD_STOP_APP: 'stopApp',
  CMD_LOCK: 'lock',
  CMD_UNLOCK: 'unlock',

  DEFAULT_POLICY: () => ({
    dailyLimitMs: 0,
    curfew: { enabled: false, start: '20:00', end: '06:00' },
    blacklist: [],
    lockdown: false,
  }),
};
