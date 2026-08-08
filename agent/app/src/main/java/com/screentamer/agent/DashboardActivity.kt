package com.screentamer.agent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * In-TV dashboard — the app's launch screen. Renders the parent dashboard
 * (served by the agent's own embedded server on 127.0.0.1:port) inside a
 * WebView, in a big TV-optimized layout (?tv=1). A Settings button opens the
 * configuration screen.
 */
class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScreenTamer/Dashboard"
    }

    private var webView: WebView? = null
    private var serverRetry = 0
    private var serverRetryDelayMs = 1500L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentService.start(this)
        val url = "http://127.0.0.1:${Prefs.serverPort(this)}/?tv=1"
        Log.i(TAG, "opening in-TV dashboard: $url")

        val root = FrameLayout(this)
        val wv = WebView(this)
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                serverRetry = 0
                serverRetryDelayMs = 1500L
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val desc = error?.description?.toString().orEmpty()
                val refused = request?.isForMainFrame == true &&
                    (error?.errorCode == -6 || desc.contains("ERR_CONNECTION_REFUSED"))
                if (refused && serverRetry < 20) {
                    serverRetry++
                    Log.i(TAG, "embedded server not up yet — retry #$serverRetry in ${serverRetryDelayMs}ms")
                    wv.postDelayed({ wv.reload() }, serverRetryDelayMs)
                    serverRetryDelayMs = (serverRetryDelayMs * 2).coerceAtMost(5000L)
                } else {
                    Log.w(TAG, "webview error: ${error?.description} (${error?.errorCode})")
                }
            }
        }
        root.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        wv.loadUrl(url)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Fire TV remote: back returns through the dashboard history, double
        // back (or back at root) closes the activity.
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (webView?.canGoBack() == true) {
                webView?.goBack()
                return true
            }
            Log.i(TAG, "closing in-TV dashboard")
        }
        return super.onKeyDown(keyCode, event)
    }
}
