package com.iips.launcher.aai.monitor

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AaiAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                // We only log metadata, absolutely NO content or text is extracted.
                Log.d("AaiAccessibility", "Window changed to package: \$packageName")
                // In a full implementation, this would notify the ActivitySessionCoordinator
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Signal InteractionMonitor that interaction happened
                // No node text, no coordinates, no content recorded.
                InteractionMonitor.recordInteraction()
            }
        }
    }

    override fun onInterrupt() {
        Log.d("AaiAccessibility", "Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AaiAccessibility", "Service Connected - Accessibility metadata tracking active")
    }
}
