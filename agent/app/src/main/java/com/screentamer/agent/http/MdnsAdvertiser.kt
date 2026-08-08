package com.screentamer.agent.http

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the embedded dashboard server over mDNS/Bonjour so parents can
 * open it at http://<device-hostname>.local:<port>/ instead of remembering an
 * IP address. Uses Android's built-in NsdManager (no dependencies).
 *
 * Note: Android's NsdManager advertises the *service* (screentamer._http._tcp)
 * and the device's own hostname in the SRV record — the hostname can't be
 * chosen by an app. The advertised hostname is surfaced via [hostname] so the
 * UI can print the exact `.local` URL to open.
 */
class MdnsAdvertiser(context: Context) {

    private val logTag = "ScreenTamer/MdnsAdvertiser"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registered = false
    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            val host = info.host?.hostName
            Log.i(logTag, "mDNS registered: ${info.serviceName}.${info.serviceType} port=${info.port} host=$host")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(logTag, "mDNS registration failed: $errorCode")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(logTag, "mDNS unregistration failed: $errorCode")
        }
    }

    /** Registers `<serviceName>._http._tcp` on [port]. Safe to call repeatedly. */
    fun start(port: Int, serviceName: String) {
        if (registered) return
        val info = NsdServiceInfo().apply {
            serviceType = "_http._tcp."
            setServiceName(serviceName)
            this.port = port
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(logTag, "mDNS unavailable: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(logTag, "mDNS unregister failed: ${e.message}")
        }
        registered = false
    }

    /** The mDNS hostname of this device (`...` for `http://<name>.local:<port>/`),
     *  or null when it can't be resolved (some devices report "localhost"). */
    fun hostname(): String? = try {
        java.net.InetAddress.getLocalHost().hostName
            ?.takeUnless { it.isBlank() || it.equals("localhost", ignoreCase = true) }
    } catch (e: Exception) {
        null
    }
}
