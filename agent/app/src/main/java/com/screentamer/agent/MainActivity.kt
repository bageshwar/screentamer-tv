package com.screentamer.agent

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screentamer.agent.core.AdbClient
import com.screentamer.agent.overlay.LockOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etName: EditText
    private lateinit var etAdbPort: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPort: EditText
    private lateinit var tvStatus: TextView

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

        etUrl.setText(Prefs.serverUrl(this))
        etToken.setText(Prefs.pairingToken(this))
        etName.setText(Prefs.deviceName(this))
        etAdbPort.setText(Prefs.adbPort(this).toString())
        etPassword.setText(Prefs.parentPassword(this))
        etPort.setText(Prefs.serverPort(this).toString())

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
            toast("Saved")
            renderStatus()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            AgentService.start(this)
            toast("Agent started")
            renderStatus()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AgentService.stop(this)
            renderStatus()
        }
        findViewById<Button>(R.id.btnTestAdb).setOnClickListener {
            testAdb()
        }
        findViewById<Button>(R.id.btnLock).setOnClickListener {
            AgentService.command(this, "lock")
            toast("Lock command sent")
        }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            AgentService.command(this, "unlock")
            toast("Unlock command sent")
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun testAdb() {
        tvStatus.text = "Testing local ADB (127.0.0.1:${Prefs.adbPort(this)})..."
        scope.launch {
            val adb = AdbClient(this@MainActivity)
            val ok = withContext(Dispatchers.IO) { adb.runShell("echo screentamer-ok") }
            tvStatus.text = if (ok) {
                "ADB: OK — media keys and force-stop available.\n" +
                    "Note: the first connection registers the agent's key with adbd."
            } else {
                "ADB: FAILED — is ADB Debugging ON in Fire TV Developer Options?"
            }
        }
    }

    private fun renderStatus() {
        val sb = StringBuilder()

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses?.any { it.processName == packageName && it.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED } == true
        sb.append(if (running) "Agent service: running\n" else "Agent service: stopped\n")

        val usageGranted = try {
            val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            !usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 60_000, now).isEmpty()
        } catch (e: SecurityException) {
            false
        }
        sb.append("Usage stats permission: ${if (usageGranted) "granted" else "NOT granted (run the setup script)"}\n")

        val overlay = LockOverlay(this)
        sb.append("Overlay permission: ${if (overlay.canDrawOverlays) "granted" else "NOT granted (run the setup script)"}\n")

        sb.append("Server: ${Prefs.serverUrl(this).ifBlank { "<not set>" }}\n")
        sb.append("Dashboard: http://<this-device>:${Prefs.serverPort(this)}\n")
        sb.append("Device ID: ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"}")
        tvStatus.text = sb.toString()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
