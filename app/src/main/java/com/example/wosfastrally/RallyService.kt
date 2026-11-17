package com.example.wosfastrally

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/** Prototype only: selector and event timing. Never sends gameplay gestures. */
class RallyService : AccessibilityService() {
    private var manager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var selected = 7
    private var lastEventMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        selected = getSharedPreferences("config", MODE_PRIVATE).getInt("activeFormation", 7)
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w("WosFastRally", "Overlay permission not granted")
            return
        }
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 22, 46, 60))
            setPadding(8, 8, 8, 8)
        }
        val buttons = LinearLayout(this)
        for (slot in listOf(5, 6, 7)) {
            val button = Button(this).apply {
                text = "F$slot"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    selected = slot
                    getSharedPreferences("config", MODE_PRIVATE).edit().putInt("activeFormation", slot).apply()
                    updateStatus("Selected F$slot — dry run")
                    Log.i("WosFastRally", "Selected preset F$slot at ${SystemClock.elapsedRealtimeNanos()}")
                }
            }
            buttons.addView(button, LinearLayout.LayoutParams(90, 100))
        }
        container.addView(buttons)
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f }
        container.addView(status)
        updateStatus("Selected F$selected — dry run")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 220 }
        manager?.addView(container, params)
        panel = container
    }

    private fun updateStatus(message: String) { status?.text = message }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // Record game events, but do not infer screen identity from generic events.
        if (!pkg.contains("whiteout", ignoreCase = true) &&
            !pkg.contains("centurygame", ignoreCase = true)) return
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastEventMs == 0L) 0L else now - lastEventMs
        lastEventMs = now
        Log.d("WosFastRally", "event=${event.eventType} package=$pkg sinceLast=${delta}ms")
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        panel?.let { try { manager?.removeView(it) } catch (_: Exception) {} }
        panel = null
        super.onDestroy()
    }
}
