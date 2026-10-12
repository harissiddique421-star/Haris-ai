package com.harisai.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Optional, user-enabled UI automation. WhatsApp UI changes can make this fail. */
class WhatsAppAutoSendService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAttemptAt = 0L
    private var attemptScheduled = false

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 250
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val prefs = getSharedPreferences("haris_ai", MODE_PRIVATE)
        val enabled = prefs.getBoolean("whatsapp_auto_send", false)
        val armed = prefs.getBoolean("auto_send_armed", false)
        if (!enabled || !armed || attemptScheduled || System.currentTimeMillis() - lastAttemptAt < 5000) return
        // Only proceed for an explicitly armed launch from Haris AI, and only if the exact expected draft is visible.
        val expectedMessage = prefs.getString("expected_message", "").orEmpty().trim()
        if (expectedMessage.isBlank()) return
        attemptScheduled = true
        handler.postDelayed({
            try {
                val root = rootInActiveWindow
                if (root != null && containsExactText(root, expectedMessage)) {
                    val sendNode = findSendNode(root)
                    if (sendNode != null && sendNode.isEnabled) {
                        val clicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            lastAttemptAt = System.currentTimeMillis()
                            prefs.edit().putBoolean("auto_send_armed", false).apply()
                        }
                    }
                }
            } finally {
                attemptScheduled = false
            }
        }, 1800)
    }

    private fun containsExactText(node: AccessibilityNodeInfo, expected: String): Boolean {
        val visible = node.text?.toString()?.trim().orEmpty()
        if (visible == expected) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsExactText(child, expected)) return true
        }
        return false
    }

    private fun findSendNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labels = listOf("send", "send message")
        for (label in labels) {
            val byText = root.findAccessibilityNodeInfosByText(label)
            for (node in byText) {
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                val text = node.text?.toString()?.lowercase().orEmpty()
                if ((text == label || desc == label) && node.isClickable) return node
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) return parent
                    parent = parent.parent
                }
            }
        }
        // WhatsApp often exposes the send icon with content description only.
        return findByDescription(root, labels)
    }

    private fun findByDescription(node: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (desc in labels && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findByDescription(it, labels) }
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() { }
}
