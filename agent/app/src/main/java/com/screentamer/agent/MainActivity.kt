package com.screentamer.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screentamer.agent.core.AdbClient
import com.screentamer.agent.http.MdnsAdvertiser
import com.screentamer.agent.overlay.LockOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScreenTamer/MainActivity"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etName: EditText
    private lateinit var etAdbPort: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPort: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCheckUpdates: Button
    private lateinit var btnTabDash: Button
    private lateinit var btnTabRelay: Button
    private lateinit var btnTabDevice: Button
    private lateinit var pageDashboard: android.view.View
    private lateinit var pageRelay: android.view.View
    private lateinit var pageDevice: android.view.View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etName = findViewById(R.id.etName)
        etAdbPort = findViewById(R.id.etAdbPort)
        etPassword = findViewById(R.id.etPassword)
        etPort = findViewById(R.id.etPort)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        btnTabDash = findViewById(R.id.btnTabDash)
        btnTabRelay = findViewById(R.id.btnTabRelay)
        btnTabDevice = findViewById(R.id.btnTabDevice)
        pageDashboard = findViewById(R.id.pageDashboard)
        pageRelay = findViewById(R.id.pageRelay)
        pageDevice = findViewById(R.id.pageDevice)

        btnTabDash.setOnClickListener { selectTab(0) }
        btnTabRelay.setOnClickListener { selectTab(1) }
        btnTabDevice.setOnClickListener { selectTab(2) }
        selectTab(0)
        btnStart.requestFocus()

        findViewById<TextView>(R.id.tvAbout).text = getString(R.string.about_text) +
            "\n\nVersion " + BuildConfig.VERSION_NAME

        etUrl.setText(Prefs.serverUrl(this))
        etToken.setText(Prefs.pairingToken(this))
        etName.setText(Prefs.deviceName(this))
        etAdbPort.setText(Prefs.adbPort(this).toString())
        etPassword.setText(Prefs.parentPassword(this))
        etPort.setText(Prefs.serverPort(this).toString())

        // Auto-start: opening the app (by hand, or by the setup script's `am
        // start`) must bring the agent up — no manual "Start Agent" needed.
        AgentService.start(this)
        Log.i(TAG, "app opened — agent auto-start requested")

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.save(
                this,
                etUrl.text.toString(),
                etToken.text.toString(),
                etName.text.toString(),
                etAdbPort.text.toString().toIntOrNull() ?: 5555
            )
            Prefs.saveHttp(
                this,
                etPassword.text.toString(),
                etPort.text.toString().toIntOrNull() ?: 8080
            )
            AgentService.reconfigure(this)
            Log.i(TAG, "settings saved — service reconfigured")
            toast("Saved")
            renderStatus()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            AgentService.start(this)
            Log.i(TAG, "manual start pressed")
            toast("Agent started")
            renderStatus()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AgentService.stop(this)
            Log.i(TAG, "manual stop pressed")
            renderStatus()
        }
        findViewById<Button>(R.id.btnTestAdb).setOnClickListener {
            testAdb()
        }
        findViewById<Button>(R.id.btnLock).setOnClickListener {
            AgentService.command(this, "lock")
            Log.i(TAG, "lock pressed")
            toast("Lock command sent")
        }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            AgentService.command(this, "unlock")
            Log.i(TAG, "unlock pressed")
            toast("Unlock command sent")
        }
        findViewById<Button>(R.id.btnDash).setOnClickListener {
            Log.i(TAG, "opening in-TV dashboard")
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        btnCheckUpdates.setOnClickListener {
            if (com.screentamer.agent.core.UpdateManager.hasUpdate) {
                btnCheckUpdates.isEnabled = false
                btnCheckUpdates.text = "Downloading..."
                com.screentamer.agent.core.UpdateManager.downloadAndInstallApk(
                    this@MainActivity,
                    onProgress = { status ->
                        runOnUiThread {
                            btnCheckUpdates.text = status
                        }
                    },
                    onError = { err ->
                        runOnUiThread {
                            btnCheckUpdates.isEnabled = true
                            btnCheckUpdates.text = "Install Update"
                            toast("Update failed: $err")
                        }
                    }
                )
            } else {
                btnCheckUpdates.isEnabled = false
                btnCheckUpdates.text = "Checking..."
                com.screentamer.agent.core.UpdateManager.checkForUpdates { hasUpdate ->
                    runOnUiThread {
                        btnCheckUpdates.isEnabled = true
                        if (hasUpdate) {
                            btnCheckUpdates.text = "Install Update"
                            toast("New update available: ${com.screentamer.agent.core.UpdateManager.latestVersionName}")
                        } else {
                            btnCheckUpdates.text = "Check for Updates"
                            toast("App is up to date")
                        }
                        renderStatus()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        com.screentamer.agent.core.UpdateManager.checkForUpdates { updated ->
            if (updated) {
                runOnUiThread { renderStatus() }
            }
        }
    }

    private fun selectTab(tab: Int) {
        btnTabDash.isSelected = tab == 0
        btnTabRelay.isSelected = tab == 1
        btnTabDevice.isSelected = tab == 2
        pageDashboard.visibility = if (tab == 0) android.view.View.VISIBLE else android.view.View.GONE
        pageRelay.visibility = if (tab == 1) android.view.View.VISIBLE else android.view.View.GONE
        pageDevice.visibility = if (tab == 2) android.view.View.VISIBLE else android.view.View.GONE
        Log.i(TAG, "settings tab selected: $tab")
    }

    private fun testAdb() {
        tvStatus.text = "Testing local ADB (127.0.0.1:${Prefs.adbPort(this)})..."
        scope.launch {
            val adb = AdbClient(this@MainActivity)
            val ok = withContext(Dispatchers.IO) { adb.runShell("echo screentamer-ok") }
            Log.i(TAG, "adb self-test: ${if (ok) "OK" else "FAILED"}")
            tvStatus.text = if (ok) {
                "ADB: OK — media keys and force-stop available.\n" +
                    "Note: the first connection registers the agent's key with adbd."
            } else {
                "ADB: FAILED — is ADB Debugging ON in Fire TV Developer Options?"
            }
        }
    }

    private fun renderStatus() {
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                val port = Prefs.serverPort(this@MainActivity)
                val host = MdnsAdvertiser(this@MainActivity).hostname()
                if (host != null) "http://$host.local:$port" else "http://<this-device>:$port"
            }
            renderStatus(url)
        }
    }

    private fun renderStatus(dashboardUrl: String) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses?.any { it.processName == packageName && it.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED } == true

        val usageGranted = try {
            val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            !usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 60_000, now).isEmpty()
        } catch (e: SecurityException) {
            false
        }
        val overlayGranted = LockOverlay(this).canDrawOverlays

        val sp = SpannableStringBuilder()
        fun line(text: String, color: Int? = null) {
            val start = sp.length
            sp.append(text).append('\n')
            if (color != null) {
                sp.setSpan(
                    ForegroundColorSpan(color),
                    start, sp.length - 1,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        val ok = ContextCompat.getColor(this, R.color.ok)
        val bad = ContextCompat.getColor(this, R.color.danger)
        val okMark = if (running) "✓" else "✗"
        line("Agent service: ${if (running) "running $okMark" else "stopped $okMark"}",
            if (running) ok else bad)
        val usageMark = if (usageGranted) "✓" else "✗"
        line(
            "Usage stats permission: ${if (usageGranted) "granted $usageMark" else "NOT granted $usageMark — run the setup script"}",
            if (usageGranted) ok else bad
        )
        val overlayMark = if (overlayGranted) "✓" else "✗"
        line(
            "Overlay permission: ${if (overlayGranted) "granted $overlayMark" else "NOT granted $overlayMark — run the setup script"}",
            if (overlayGranted) ok else bad
        )
        line("Server: ${Prefs.serverUrl(this).ifBlank { "<not set>" }}")
        line("Dashboard: $dashboardUrl")
        line("Device ID: ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"}")
        val mdns = MdnsAdvertiser(this)
        if (mdns.collision()) {
            line("")
            line("⚠ Another device is broadcasting \"${mdns.collisionHost()?.removeSuffix(".local.")}\"", bad)
            line("Open Device settings and set a unique Device Name", bad)
        }
        if (com.screentamer.agent.core.UpdateManager.hasUpdate) {
            line("")
            line("★ UPDATE AVAILABLE: ${com.screentamer.agent.core.UpdateManager.latestVersionName}", bad)
            line("To update, open the 'Downloader' app on your TV and type:", ok)
            line("  tinyurl.com/screentamer-latest", ok)
            line("Or visit the release page on a phone/PC:", ok)
            line("  github.com/bageshwar/screentamer-tv/releases", ok)
        }
        tvStatus.text = sp

        // Active-state emphasis: the relevant control is the primary (filled)
        // one. Both stay enabled so the D-pad focus ring never disappears.
        btnStart.background = ContextCompat.getDrawable(
            this,
            if (running) R.drawable.btn_tv else R.drawable.btn_primary_tv
        )
        btnStop.background = ContextCompat.getDrawable(
            this,
            if (running) R.drawable.btn_primary_tv else R.drawable.btn_tv
        )
        btnCheckUpdates.text = if (com.screentamer.agent.core.UpdateManager.hasUpdate) "Install Update" else "Check for Updates"
        btnCheckUpdates.background = ContextCompat.getDrawable(
            this,
            if (com.screentamer.agent.core.UpdateManager.hasUpdate) R.drawable.btn_primary_tv else R.drawable.btn_tv
        )
        Log.i(TAG, "status rendered: service ${if (running) "running" else "stopped"}")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
