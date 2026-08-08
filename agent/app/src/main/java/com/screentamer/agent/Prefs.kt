package com.screentamer.agent

import android.content.Context
import android.content.SharedPreferences
import com.screentamer.agent.core.AdbMode
import org.json.JSONObject

object Prefs {
    private const val FILE = "screentamer_prefs"
    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "pairing_token"
    private const val KEY_NAME = "device_name"
    private const val KEY_ADB_PORT = "adb_port"
    private const val KEY_ADB_MODE = "adb_mode"
    private const val KEY_ADB_HOST = "adb_host"
    private const val KEY_ADB_TRANSPORT = "adb_transport_id"
    private const val KEY_POLICY = "last_policy"
    private const val KEY_PASSWORD = "parent_password"
    private const val KEY_SERVER_PORT = "server_port"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String = sp(context).getString(KEY_URL, "") ?: ""

    fun pairingToken(context: Context): String = sp(context).getString(KEY_TOKEN, "") ?: ""

    fun parentPassword(context: Context): String = sp(context).getString(KEY_PASSWORD, "") ?: ""

    fun serverPort(context: Context): Int = sp(context).getInt(KEY_SERVER_PORT, 8080)

    fun deviceName(context: Context): String {
        val saved = sp(context).getString(KEY_NAME, "") ?: ""
        if (saved.isNotBlank()) return saved
        return android.os.Build.MODEL
    }

    fun adbPort(context: Context): Int = sp(context).getInt(KEY_ADB_PORT, 5555)

    fun adbMode(context: Context): AdbMode =
        if (sp(context).getString(KEY_ADB_MODE, "device") == "host") AdbMode.HOST_BRIDGE else AdbMode.DEVICE

    fun adbHost(context: Context): String = sp(context).getString(KEY_ADB_HOST, "127.0.0.1") ?: "127.0.0.1"

    fun adbTransportId(context: Context): String = sp(context).getString(KEY_ADB_TRANSPORT, "") ?: ""

    fun save(context: Context, url: String, token: String, name: String, adbPort: Int) {
        sp(context).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_NAME, name.trim())
            .putInt(KEY_ADB_PORT, adbPort)
            .apply()
    }

    fun saveHttp(context: Context, password: String, port: Int) {
        sp(context).edit()
            .putString(KEY_PASSWORD, password.trim())
            .putInt(KEY_SERVER_PORT, port)
            .apply()
    }

    fun saveAdb(context: Context, mode: AdbMode, host: String, port: Int, transportId: String) {
        sp(context).edit()
            .putString(KEY_ADB_MODE, if (mode == AdbMode.HOST_BRIDGE) "host" else "device")
            .putString(KEY_ADB_HOST, host.trim())
            .putInt(KEY_ADB_PORT, port)
            .putString(KEY_ADB_TRANSPORT, transportId.trim())
            .apply()
    }

    fun policy(context: Context): JSONObject? {
        val raw = sp(context).getString(KEY_POLICY, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun savePolicy(context: Context, policy: JSONObject) {
        sp(context).edit().putString(KEY_POLICY, policy.toString()).apply()
    }
}
