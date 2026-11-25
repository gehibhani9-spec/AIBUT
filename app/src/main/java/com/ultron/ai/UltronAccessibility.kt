package com.ultron.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UltronAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: UltronAccessibility? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun dump(): String {
        val root = rootInActiveWindow ?: return "(no window readable)"
        val sb = StringBuilder("app: ${root.packageName}\n")
        walk(root, sb)
        return sb.toString().take(6000)
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        val t = n.text?.toString() ?: n.contentDescription?.toString()
        if (!t.isNullOrBlank() || n.isEditable) {
            sb.append("- ").append(t ?: "")
            if (n.isClickable) sb.append(" [click]")
            if (n.isEditable) sb.append(" [input]")
            sb.append('\n')
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, sb) }
    }

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            var x: AccessibilityNodeInfo? = n
            while (x != null) {
                if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                x = x.parent
            }
        }
        return false
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun enter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return if (Build.VERSION.SDK_INT >= 30)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        else false
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) findScrollable(n.getChild(i))?.let { return it }
        return null
    }

    fun scroll(dir: String): Boolean {
        val node = findScrollable(rootInActiveWindow)
        val down = dir == "down" || dir == "right"
        if (node != null) {
            val ok = node.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (ok) return true
        }
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val a = m.heightPixels * 0.7f
        val b = m.heightPixels * 0.3f
        return if (down) swipe(x, a, x, b, 300) else swipe(x, b, x, a, 300)
    }

    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    fun global(name: String): Boolean {
        val a = when (name) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(a)
    }
}
