package com.example.wosfastrally

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** v0.3: explicit, one-tap preset test. No automatic screen detection or Deploy. */
class RallyService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val slotViews = mutableMapOf<Int, TextView>()
    private var status: TextView? = null
    private var testButton: TextView? = null
    private var selected = 7
    private var lastEventMs = 0L
    private var testing = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun background(color: Int, radius: Int = 12): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        removePanel()
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        selected = prefs.getInt("activeFormation", 7).takeIf { it in 5..7 } ?: 7
        if (!Settings.canDrawOverlays(this)) {
            Log.w("WosFastRally", "Overlay permission missing")
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = background(Color.rgb(24, 37, 47))
        }
        val handle = TextView(this).apply {
            text = "☰  WOS  •  drag here"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(8))
        }
        root.addView(handle)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (slot in 5..7) {
            val view = TextView(this).apply {
                text = "F$slot"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                contentDescription = "Select formation F$slot"
                setOnClickListener {
                    selected = slot
                    prefs.edit().putInt("activeFormation", slot).apply()
                    refreshSelection()
                    Log.i("WosFastRally", "Selected F$slot at ${SystemClock.elapsedRealtimeNanos()}")
                }
            }
            val lp = LinearLayout.LayoutParams(dp(62), dp(52))
            if (slot != 5) lp.leftMargin = dp(6)
            row.addView(view, lp)
            slotViews[slot] = view
        }
        root.addView(row)
        val test = TextView(this).apply {
            text = "TEST F$selected preset tap"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = background(Color.rgb(46, 96, 150))
            isClickable = true
            setOnClickListener { testPresetTap() }
        }
        root.addView(test, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        testButton = test
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(status)
        refreshSelection()
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("overlayX", dp(12))
            y = prefs.getInt("overlayY", dp(160))
        }
        params = windowParams
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = windowParams.x; startY = windowParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = (startX + event.rawX - downRawX).roundToInt().coerceAtLeast(0)
                    windowParams.y = (startY + event.rawY - downRawY).roundToInt().coerceAtLeast(0)
                    wm?.updateViewLayout(root, windowParams)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("overlayX", windowParams.x).putInt("overlayY", windowParams.y).apply()
                    true
                }
                else -> false
            }
        }
        try { wm?.addView(root, windowParams); panel = root }
        catch (e: Exception) { Log.e("WosFastRally", "Unable to add overlay", e) }
    }

    private fun refreshSelection() {
        slotViews.forEach { (slot, view) ->
            val active = slot == selected
            view.background = background(if (active) Color.rgb(22, 147, 78) else Color.rgb(75, 87, 99))
            view.alpha = if (active) 1f else 0.92f
            view.isSelected = active
        }
        testButton?.text = "TEST F$selected preset tap"
        status?.text = "F$selected • manual test only • no Deploy"
    }

    private fun testPresetTap() {
        if (testing) return
        // Explicit user initiation only. Avoid tapping another application.
        val foregroundPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (!foregroundPackage.contains("whiteout", true) &&
            !foregroundPackage.contains("centurygame", true)) {
            status?.text = "Not in WOS; no tap sent"
            return
        }
        val display = resources.displayMetrics
        val width = display.widthPixels.toFloat()
        val height = display.heightPixels.toFloat()
        // From the user's 709x1536 portrait screenshot; normalized icon centers.
        val xFraction = when (selected) { 5 -> 0.50f; 6 -> 0.60f; else -> 0.705f }
        val x = width * xFraction
        val y = height * 0.119f
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
            .build()
        testing = true
        status?.text = "Testing F$selected preset..."
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                testing = false
                status?.text = "F$selected tap sent • check selected preset"
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                testing = false
                status?.text = "Tap cancelled • no Deploy"
            }
        }, Handler(Looper.getMainLooper()))
        if (!accepted) {
            testing = false
            status?.text = "Gesture rejected • no Deploy"
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.contains("whiteout", true) && !pkg.contains("centurygame", true)) return
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastEventMs == 0L) 0L else now - lastEventMs
        lastEventMs = now
        Log.d("WosFastRally", "event=${event.eventType} package=$pkg sinceLast=${delta}ms")
    }
    override fun onInterrupt() {}
    private fun removePanel() {
        panel?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        panel = null
        slotViews.clear()
        status = null
        testButton = null
    }
    override fun onDestroy() { removePanel(); super.onDestroy() }
}
