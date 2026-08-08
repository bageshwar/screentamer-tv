package com.screentamer.agent.core

import android.content.Context
import android.util.Log
import com.screentamer.agent.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateManager {
    private const val TAG = "ScreenTamer/UpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/bageshwar/screentamer-tv/releases/latest"

    var hasUpdate: Boolean = false
        private set
    var latestVersionName: String = ""
        private set
    var latestApkUrl: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates(callback: ((Boolean) -> Unit)? = null) {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("User-Agent", "ScreenTamer-TV-Agent")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Failed to check for updates", e)
                callback?.invoke(false)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Update check failed with HTTP code: ${response.code}")
                        callback?.invoke(false)
                        return
                    }

                    try {
                        val body = response.body?.string() ?: ""
                        if (body.isEmpty()) {
                            callback?.invoke(false)
                            return
                        }

                        val json = JSONObject(body)
                        val tagName = json.optString("tag_name", "").trim()
                        if (tagName.isEmpty()) {
                            callback?.invoke(false)
                            return
                        }

                        // Strip leading 'v' if present (e.g., v0.1.0 -> 0.1.0)
                        val latestClean = if (tagName.startsWith("v", ignoreCase = true)) tagName.substring(1) else tagName
                        val currentClean = BuildConfig.VERSION_NAME

                        Log.i(TAG, "Update check: Current version = $currentClean, Latest version = $latestClean")

                        // If different, we assume an update is available (simple approach suitable for dev-defined hashes or tags)
                        if (latestClean != currentClean && latestClean.isNotEmpty()) {
                            // Find the APK download URL from the assets
                            val assets = json.optJSONArray("assets")
                            var apkUrl = ""
                            if (assets != null) {
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    val name = asset.optString("name", "")
                                    if (name.endsWith(".apk")) {
                                        apkUrl = asset.optString("browser_download_url", "")
                                        break
                                    }
                                }
                            }
                            
                            hasUpdate = true
                            latestVersionName = tagName
                            latestApkUrl = apkUrl
                            Log.i(TAG, "New update found: $tagName. Download URL: $apkUrl")
                            callback?.invoke(true)
                        } else {
                            hasUpdate = false
                            latestVersionName = ""
                            latestApkUrl = ""
                            Log.i(TAG, "App is up to date.")
                            callback?.invoke(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing update check response", e)
                        callback?.invoke(false)
                    }
                }
            }
        })
    }

    fun downloadAndInstallApk(context: Context, onProgress: (String) -> Unit, onError: (String) -> Unit) {
        if (latestApkUrl.isEmpty()) {
            onError("No download URL found")
            return
        }
        val request = Request.Builder().url(latestApkUrl).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                onError(e.message ?: "Download failed")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onError("Server returned code ${response.code}")
                        return
                    }
                    try {
                        val file = java.io.File(context.cacheDir, "update.apk")
                        response.body?.byteStream()?.use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        // Launch installer
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        onProgress("Installing...")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error installing update APK", e)
                        onError(e.message ?: "Installation failed")
                    }
                }
            }
        })
    }
}
