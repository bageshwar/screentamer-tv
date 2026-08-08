package com.screentamer.agent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Watchdog: Fire OS aggressively kills background services, but the system
 * keeps accessibility services alive and restarts them. Whenever this service
 * binds, we make sure the agent service is running. It can also return the TV
 * to the home screen without adb when needed.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility connected - watchdog active")
        AgentService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reserved for future app-interception features.
    }

    override fun onInterrupt() {
    }
}
