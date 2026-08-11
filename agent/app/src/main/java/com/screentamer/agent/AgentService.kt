package com.screentamer.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.screentamer.agent.core.AdbClient
import com.screentamer.agent.core.AgentSocket
import com.screentamer.agent.core.PolicyManager
import com.screentamer.agent.data.KnownApps
import com.screentamer.agent.data.Protocol
import com.screentamer.agent.http.DeviceStore
import com.screentamer.agent.http.EmbeddedServer
import com.screentamer.agent.http.MdnsAdvertiser
import com.screentamer.agent.overlay.LockOverlay
import com.screentamer.agent.tracking.UsageTracker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Foreground agent service. Owns:
 *  - the embedded parent dashboard server (primary control plane)
 *  - on-device persistence (per-day usage, log, health)
 *  - enforcement (limits/curfews/blacklists) and command execution via adb
 *  - optional relay: pushes usage/log to a server-relay box when configured
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "ScreenTamer/AgentService"
        private const val CHANNEL_ID = "screentamer"
        private const val NOTIF_ID = 1
        private const val TRACK_INTERVAL_MS = 30_000L
        private const val RETENTION_DAYS = 90
        private const val PRUNE_INTERVAL_MS = 12 * 60 * 60 * 1000L

        const val ACTION_START = "com.screentamer.agent.START"
        const val ACTION_STOP = "com.screentamer.agent.STOP"
        const val ACTION_RECONFIGURE = "com.screentamer.agent.RECONFIGURE"
        const val EXTRA_COMMAND = "command_type"

        fun start(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }

        fun reconfigure(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_RECONFIGURE))
        }

        fun command(context: Context, type: String) {
            context.startService(
                Intent(context, AgentService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_COMMAND, type)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    private lateinit var socket: AgentSocket
    private lateinit var adb: AdbClient
    private lateinit var tracker: UsageTracker
    private lateinit var overlay: LockOverlay
    private lateinit var store: DeviceStore
    private lateinit var server: EmbeddedServer
    private lateinit var mdns: MdnsAdvertiser
    private val policy = PolicyManager()

    @Volatile
    private var currentApp: String? = null

    @Volatile
    private var locked: Boolean = false

    private var lastUpdateCheckTime = 0L
    private var lastPruneTime = 0L

    private val deviceId: String
        get() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "agent service creating (device $deviceId, ${Build.MODEL}, Android ${Build.VERSION.RELEASE})")
        startForeground(NOTIF_ID, buildNotification())
        com.screentamer.agent.core.AdbConfigProvider.get = {
            com.screentamer.agent.core.AdbConfig(
                Prefs.adbMode(it),
                Prefs.adbHost(it),
                Prefs.adbPort(it),
                Prefs.adbTransportId(it)
            )
        }

        overlay = LockOverlay(this)
        tracker = UsageTracker(this)
        adb = AdbClient(this)
        policy.apply(Prefs.policy(this) ?: policy.defaultPolicy())

        store = DeviceStore(File(filesDir, "data"))
        store.bumpServiceStart()
        store.sweep(RETENTION_DAYS)
        // Fire OS reports phantom full-day usage for uninstalled apps (a single
        // day can balloon past 96h). Drop those from persisted history now, then
        // keep pruning every PRUNE_INTERVAL_MS in the tick loop so packages
        // uninstalled mid-day don't linger in history until the next restart.
        // Runs inside startLoop's coroutine before the first tick so cleanup
        // never races the initial recordUsage write.
        lastPruneTime = SystemClock.elapsedRealtime()

        server = EmbeddedServer(Prefs.serverPort(this), object : EmbeddedServer.Handler {
            override fun login(password: String): Boolean {
                val ok = password == Prefs.parentPassword(this@AgentService)
                if (!ok) {
                    Log.w(TAG, "dashboard login rejected (empty=${password.isBlank()})")
                    if (password.isNotBlank()) log("failed dashboard login attempt")
                }
                return ok
            }

            override fun state(): JSONObject = deviceState()

            override fun history(days: Int): JSONObject = JSONObject()
                .put("deviceId", deviceId)
                .put("days", days)
                .put("today", UsageTracker.todayKey())
                .put("history", store.historyFor(days))

            override fun config(policyRaw: JSONObject): String? {
                policy.apply(normalizePolicy(policyRaw))
                Prefs.savePolicy(this@AgentService, policy.policy)
                log("policy updated from dashboard: limit=${policy.policy.optLong("dailyLimitMs") / 60000}min curfew=${policy.policy.optJSONObject("curfew")?.optBoolean("enabled")}")
                return null
            }

            override fun command(type: String, pkg: String?): String? {
                if (type !in commandTypes) {
                    Log.w(TAG, "unknown dashboard command: $type")
                    return "unknown command $type"
                }
                log("command from dashboard: $type${pkg?.let { " ($it)" } ?: ""}")
                scope.launch { executeCommand(type, pkg) }
                return null
            }

            override fun icon(pkg: String): ByteArray? = try {
                val icon = packageManager.getApplicationIcon(pkg)
                val bitmap = when (icon) {
                    is android.graphics.drawable.BitmapDrawable -> icon.bitmap
                    else -> android.graphics.Bitmap.createBitmap(
                        icon.intrinsicWidth, icon.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888
                    ).also {
                        val canvas = android.graphics.Canvas(it)
                        icon.setBounds(0, 0, it.width, it.height)
                        icon.draw(canvas)
                    }
                }
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            } catch (e: Exception) {
                null
            }

            override fun assets(): EmbeddedServer.Assets = object : EmbeddedServer.Assets {
                override fun open(path: String): java.io.InputStream? =
                    try {
                        assets.open("www/$path", android.content.res.AssetManager.ACCESS_BUFFER)
                    } catch (e: java.io.IOException) {
                        null
                    }

                override fun mime(name: String): String = when (name.substringAfterLast('.')) {
                    "html" -> "text/html; charset=utf-8"
                    "js" -> "text/javascript; charset=utf-8"
                    "css" -> "text/css; charset=utf-8"
                    "svg" -> "image/svg+xml"
                    "png" -> "image/png"
                    else -> "application/octet-stream"
                }
            }
        })
        mdns = MdnsAdvertiser(this)
        mdns.start(Prefs.serverPort(this), "ScreenTamer ${Prefs.deviceName(this)}")
        server.start()
        Log.i(TAG, "embedded server on :${Prefs.serverPort(this)}")

        socket = AgentSocket(this, object : AgentSocket.Listener {
            override fun onConnected() {
                Log.i(TAG, "connected to relay server")
                sendHello()
                log("connected to relay server")
            }

            override fun onDisconnected() {
                Log.w(TAG, "disconnected from relay server")
            }

            override fun onMessage(type: String, payload: JSONObject) {
                handleRelayMessage(type, payload)
            }
        })
        if (Prefs.serverUrl(this).isNotBlank()) socket.start()
        startLoop()
    }

    private val commandTypes = setOf(
        Protocol.CMD_PAUSE, Protocol.CMD_PLAY, Protocol.CMD_HOME,
        Protocol.CMD_STOP_APP, Protocol.CMD_LOCK, Protocol.CMD_UNLOCK,
        Protocol.CMD_CHECK_UPDATE
    )

    private fun normalizePolicy(raw: JSONObject): JSONObject {
        val curfew = raw.optJSONObject("curfew") ?: JSONObject()
        return JSONObject()
            .put("dailyLimitMs", raw.optLong("dailyLimitMs", 0).coerceAtLeast(0))
            .put("curfew", JSONObject()
                .put("enabled", curfew.optBoolean("enabled", false))
                .put("start", curfew.optString("start", "20:00"))
                .put("end", curfew.optString("end", "06:00")))
            .put("blacklist", raw.optJSONArray("blacklist") ?: JSONArray())
            .put("lockdown", raw.optBoolean("lockdown", false))
    }

    private fun deviceState(): JSONObject {
        val today = UsageTracker.todayKey()
        val apps = store.usageFor(today)
        val totalMs = if (apps.length() == 0) 0L else apps.keys().asSequence().sumOf { apps.optLong(it as String) }
        val device = JSONObject()
            .put("id", deviceId)
            .put("name", Prefs.deviceName(this))
            .put("model", Build.MODEL)
            .put("version", Build.VERSION.RELEASE)
            .put("online", true)
            .put("lastSeen", System.currentTimeMillis())
            .put("currentApp", currentApp ?: JSONObject.NULL)
            .put("locked", locked)
            .put("totalMs", totalMs)
            .put("policy", policy.policy)
            .put("log", store.readLog())
            .put("health", store.health())
            .put("serverPort", Prefs.serverPort(this))
            .put("iconEndpoint", true)
            .put("mdns", JSONObject()
                .put("hostname", mdns.hostname())
                .put("collision", mdns.collision())
                .put("collisionHost", mdns.collisionHost() ?: JSONObject.NULL)
                .put("collisionPeer", mdns.collisionPeer() ?: JSONObject.NULL)
            )
            .put("update", JSONObject()
                .put("hasUpdate", com.screentamer.agent.core.UpdateManager.hasUpdate)
                .put("latestVersion", com.screentamer.agent.core.UpdateManager.latestVersionName)
                .put("downloadUrl", com.screentamer.agent.core.UpdateManager.latestApkUrl)
            )
        return JSONObject()
            .put("defaultPolicy", policy.defaultPolicy())
            .put("devices", JSONObject().put(deviceId, device))
            .put("usage", JSONObject().put(deviceId, apps))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand: STOP")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                Log.i(TAG, "onStartCommand: START (app launch / watchdog / boot)")
            }
            ACTION_RECONFIGURE -> {
                Log.i(TAG, "onStartCommand: RECONFIGURE (relay url: ${Prefs.serverUrl(this).ifBlank { "<blank>" }})")
                socket.stop()
                if (Prefs.serverUrl(this).isNotBlank()) socket.start()
            }
            null -> Log.i(TAG, "onStartCommand: re-delivered START_STICKY intent")
        }
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        if (command != null) {
            scope.launch {
                executeCommand(command, null)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: stopping loop, relay, embedded server, overlay")
        loopJob?.cancel()
        socket.stop()
        mdns.stop()
        server.stop()
        overlay.hide()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Loop
    // ------------------------------------------------------------------

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch(CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "loop crashed", e)
            store.noteTickFailure(e)
        }) {
            // Serialize with tick writes: prune before the first recordUsage so
            // the startup cleanup can never overwrite a fresher usage write.
            store.pruneUninstalled(tracker.installedPackages())
            while (isActive) {
                tick()
                delay(TRACK_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        try {
            val snap = tracker.snapshot()
            val apps = snap.totals
            val totalMs = apps.values.sum()
            currentApp = tracker.foregroundApp()
            Log.i(TAG, "tick: totalMs=$totalMs apps=${apps.size} delta=${snap.delta?.size ?: 0} authoritative=${snap.authoritative} current=${currentApp?.let { KnownApps.displayName(it) } ?: "—"} locked=$locked")

            // 1. Enforcement (only on an authoritative observation; a failed
            //    query must not count toward a limit or trigger a lock).
            if (snap.authoritative) enforce(policy, apps, totalMs)

            // 2. Persist on-device (hourly buckets: attribute the delta since
            //    the last tick to the hour it was snapshotted in). Non-
            //    authoritative snapshots keep existing totals (no false wipe).
            val hourly = if (snap.delta.isNullOrEmpty() || !snap.authoritative) emptyMap() else mapOf(snap.hour.toString() to snap.delta)
            store.recordUsage(snap.date, apps, hourly, snap.authoritative)
            store.noteTick()

            // Periodically drop history for packages uninstalled since the last
            // prune (Fire OS keeps reporting phantom usage for them).
            if (SystemClock.elapsedRealtime() - lastPruneTime >= PRUNE_INTERVAL_MS) {
                lastPruneTime = SystemClock.elapsedRealtime()
                store.pruneUninstalled(tracker.installedPackages())
            }

            // 3. Relay push (optional; sends the day's full hourly map so a
            // reconnect never loses earlier hours).
            if (socket.connected) {
                socket.send(
                    Protocol.TYPE_USAGE,
                    Protocol.usage(
                        deviceId = deviceId,
                        date = snap.date,
                        apps = apps,
                        hourly = store.loadHourly(snap.date),
                        totalMs = totalMs,
                        currentApp = currentApp,
                        locked = locked,
                    )
                )
            }

            // 4. Update check (once every 24 hours, or immediately on start)
            val now = System.currentTimeMillis()
            if (now - lastUpdateCheckTime > 24 * 60 * 60 * 1000L) {
                lastUpdateCheckTime = now
                com.screentamer.agent.core.UpdateManager.checkForUpdates()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tick failed: ${e.message}", e)
            store.noteTickFailure(e)
        }
    }

    // ------------------------------------------------------------------
    // Enforcement
    // ------------------------------------------------------------------

    private fun enforce(policy: PolicyManager, apps: Map<String, Long>, totalMs: Long) {
        val now = Calendar.getInstance()

        // Blacklisted app in the foreground -> kill it and park on the home screen.
        val app = currentApp
        if (app != null && policy.isBlacklisted(app)) {
            log("blocking blacklisted app ${KnownApps.displayName(app)}")
            scope.launch { adb.inputKeyEvent(3) } // home
            scope.launch { adb.forceStop(app) }
        }

        val shouldLock = policy.shouldLock(now, totalMs)
        if (shouldLock && !locked) {
            locked = true
            val curfewOn = policy.policy.optJSONObject("curfew")?.optBoolean("enabled") == true
            val msg = when {
                policy.isLockedDown() -> "Locked by a parent"
                curfewOn -> "Curfew is active — screen time is paused"
                else -> "Daily screen time limit reached"
            }
            log(msg)
            overlay.show(msg)
            scope.launch { adb.inputKeyEvent(3) } // park on home screen
        } else if (!shouldLock && locked) {
            locked = false
            overlay.hide()
            log("unlocked")
        }
    }

    // ------------------------------------------------------------------
    // Relay server communication (optional; empty server_url disables)
    // ------------------------------------------------------------------

    private fun sendHello() {
        socket.send(
            Protocol.TYPE_HELLO,
            Protocol.hello(
                token = Prefs.pairingToken(this),
                deviceId = deviceId,
                name = Prefs.deviceName(this),
                model = Build.MODEL,
                version = Build.VERSION.RELEASE,
            )
        )
        Log.i(TAG, "hello sent (name=${Prefs.deviceName(this)}, token=${if (Prefs.pairingToken(this).isBlank()) "blank" else "set"})")
    }

    private fun handleRelayMessage(type: String, payload: JSONObject) {
        when (type) {
            Protocol.TYPE_WELCOME, Protocol.TYPE_CONFIG -> {
                val p = payload.optJSONObject("policy") ?: return
                policy.apply(p)
                Prefs.savePolicy(this, p)
                log("policy updated from relay: limit=${p.optLong("dailyLimitMs") / 60000}min curfew=${p.optJSONObject("curfew")?.optBoolean("enabled")}")
            }
            Protocol.TYPE_COMMAND -> {
                val cmd = payload.optString("command", payload.optString("cmd"))
                val pkg = payload.optString("pkg", "").ifBlank { null }
                log("command from relay: $cmd${pkg?.let { " ($it)" } ?: ""}")
                scope.launch { executeCommand(cmd, pkg) }
            }
        }
    }

    private suspend fun executeCommand(cmd: String, pkg: String?) {
        when (cmd) {
            Protocol.CMD_PAUSE -> {
                val ok = adb.inputKeyEvent(127)
                log("command: pause (adb ${if (ok) "ok" else "FAILED"})")
            }
            Protocol.CMD_PLAY -> {
                val ok = adb.inputKeyEvent(126)
                log("command: play (adb ${if (ok) "ok" else "FAILED"})")
            }
            Protocol.CMD_HOME -> {
                val ok = adb.inputKeyEvent(3)
                log("command: go home (adb ${if (ok) "ok" else "FAILED"})")
            }
            Protocol.CMD_STOP_APP -> {
                if (pkg != null) {
                    val ok = adb.forceStop(pkg)
                    log("command: force-stop ${KnownApps.displayName(pkg)} (adb ${if (ok) "ok" else "FAILED"})")
                }
            }
            Protocol.CMD_LOCK -> {
                policy.apply(policy.policy.put("lockdown", true))
                Prefs.savePolicy(this, policy.policy)
                locked = true
                log("command: instant lockdown")
                overlay.show("Locked by a parent")
                adb.inputKeyEvent(3)
            }
            Protocol.CMD_UNLOCK -> {
                policy.apply(policy.policy.put("lockdown", false))
                Prefs.savePolicy(this, policy.policy)
                locked = false
                overlay.hide()
                log("command: unlocked")
            }
            Protocol.CMD_CHECK_UPDATE -> {
                log("command: checking for updates")
                com.screentamer.agent.core.UpdateManager.checkForUpdates { hasUpdate ->
                    log("update check complete: hasUpdate=$hasUpdate version=${com.screentamer.agent.core.UpdateManager.latestVersionName}")
                }
            }
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        store.appendLog(msg)
        if (socket.connected) socket.send(Protocol.TYPE_LOG, Protocol.log(deviceId, msg))
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_app_icon)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_app_icon)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .build()
        }
    }
}
