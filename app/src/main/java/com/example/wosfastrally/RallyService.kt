package com.example.wosfastrally

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Path
import android.os.Handler
import android.os.Looper
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

/** Minimal persistent Auto Deploy overlay. */
class RallyService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val slotViews = mutableMapOf<Int, TextView>()
    private var status: TextView? = null
    private var bodyPanel: LinearLayout? = null
    private var collapseControl: TextView? = null
    private var collapsed = false
    private var detectionArmed = false
    private var targetAutoArmed = false
    private var targetAutoButton: TextView? = null
    private var waitingForDeployExit = false
    private var deployExitMisses = 0
    private var lastAutoDeployNs = 0L
    private var latencyReport: TextView? = null
    private var screenshotInFlight = false
    private val targetRetry = Runnable { if (targetAutoArmed) probeFormation("target-retry") }
    private var lastProbeMs = 0L
    private var referenceButton: TextView? = null
    private var referencePixels: IntArray? = null
    private var referenceCaptureInFlight = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var selected = 7
    private var testing = false
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun background(color: Int, radius: Int = 12): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        removePanel()
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        selected = prefs.getInt("activeFormation", 7).takeIf { it in 5..7 } ?: 7
        collapsed = prefs.getBoolean("collapsed", false)
        detectionArmed = false
        // Old Deploy reference was accidentally captured on Target. Invalidate it.
        if (!prefs.getBoolean("invalidDeployReferenceClearedV12", false)) {
            prefs.edit().remove("deployTitleReference")
                .putBoolean("invalidDeployReferenceClearedV12", true).apply()
        }
        referencePixels = loadReference()
        targetAutoArmed = prefs.getBoolean("persistentAutoDeploy", false) && referencePixels != null
        detectionArmed = targetAutoArmed
        waitingForDeployExit = false
        deployExitMisses = 0
        if (!Settings.canDrawOverlays(this)) {
            Log.w("WosFastRally", "Overlay permission missing")
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = background(Color.rgb(24, 37, 47))
        }
        val handle = TextView(this).apply {
            text = "☰ WOS"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(28), 1f))
        val toggle = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            setOnClickListener {
                collapsed = !collapsed
                prefs.edit().putBoolean("collapsed", collapsed).apply()
                updateCollapsed()
            }
        }
        header.addView(toggle, LinearLayout.LayoutParams(dp(34), dp(28)))
        collapseControl = toggle
        root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyPanel = body
        root.addView(body)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (slot in 5..7) {
            val view = TextView(this).apply {
                text = "F$slot"
                textSize = 12f
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
            val lp = LinearLayout.LayoutParams(dp(49), dp(30))
            if (slot != 5) lp.leftMargin = dp(3)
            row.addView(view, lp)
            slotViews[slot] = view
        }
        body.addView(row)
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        body.addView(status)
        val autoTarget = TextView(this).apply {
            text = if (targetAutoArmed) "AUTO: ON" else "AUTO: OFF"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = background(if (targetAutoArmed) Color.rgb(22, 117, 72) else Color.rgb(140, 65, 40))
            isClickable = true
            setOnClickListener {
                if (targetAutoArmed) {
                    targetAutoArmed = false
                    detectionArmed = false
                    waitingForDeployExit = false
                    deployExitMisses = 0
                    mainHandler.removeCallbacks(targetRetry)
                    getSharedPreferences("config", MODE_PRIVATE).edit()
                        .putBoolean("persistentAutoDeploy", false).apply()
                    text = "AUTO: OFF"
                    background = background(Color.rgb(140, 65, 40))
                    status?.text = "Auto Deploy disabled"
                } else if (referencePixels == null) {
                    status?.text = "Save a Deploy reference first"
                } else {
                    targetAutoArmed = true
                    waitingForDeployExit = false
                    deployExitMisses = 0
                    detectionArmed = true
                    getSharedPreferences("config", MODE_PRIVATE).edit()
                        .putBoolean("persistentAutoDeploy", true).apply()
                    text = "AUTO: ON"
                    background = background(Color.rgb(22, 117, 72))
                    status?.text = "Auto Deploy ON • Deploy match ≥96%"
                    probeFormation("auto-deploy-enabled")
                }
            }
        }
        val timingView = TextView(this).apply {
            text = "Last auto timing: awaiting deployment"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        latencyReport = timingView
        // Timing stays available in logcat without taking overlay space.
        targetAutoButton = autoTarget
        if (targetAutoArmed) mainHandler.postDelayed(targetRetry, 400L)
        body.addView(autoTarget, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(3) })
        val reference = TextView(this).apply {
            text = if (referencePixels != null) "REF: SAVED ✓" else "SAVE DEPLOY REF"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = background(Color.rgb(70, 82, 94))
            isClickable = true
            setOnClickListener { captureTitleReference() }
        }
        referenceButton = reference
        body.addView(reference, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(3) })
        refreshSelection()
        updateCollapsed()
        val windowParams = WindowManager.LayoutParams(
            dp(170),
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
        status?.text = "F$selected • Auto ${if (targetAutoArmed) "ON" else "OFF"}"
        if (collapsed) collapseControl?.text = "F$selected ▣"
    }

    private fun updateCollapsed() {
        bodyPanel?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseControl?.text = if (collapsed) "F$selected ▣" else "−"
        collapseControl?.contentDescription = if (collapsed) "Expand overlay, F$selected"
            else "Collapse overlay"
    }

    private data class DeployTiming(val screenshotStartNs: Long, val screenshotEndNs: Long, val matchEndNs: Long)
    private fun ms(a: Long, b: Long) = (b - a).coerceAtLeast(0L) / 1_000_000L
    private fun selectAndDeploy(timing: DeployTiming? = null) {
        if (testing) return
        if ("com.gof.global" !in detectedPackages()) {
            status?.text = "WOS not detected; no taps sent"
            return
        }
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val formation = selected
        testing = true
        val selectStartNs = SystemClock.elapsedRealtimeNanos()
        status?.text = "Selecting F$formation..."
        sendTap(width * when (formation) { 5 -> 0.50f; 6 -> 0.60f; else -> 0.705f },
            height * 0.119f, onSuccess = {
                val selectDoneNs = SystemClock.elapsedRealtimeNanos()
                // Delay starts only after Android reports the formation gesture completed.
                Handler(Looper.getMainLooper()).postDelayed({
                    if ("com.gof.global" !in detectedPackages()) {
                        testing = false
                        status?.text = "Game not detected; Deploy cancelled"
                        return@postDelayed
                    }
                    val deployStartNs = SystemClock.elapsedRealtimeNanos()
                    status?.text = "Sending Deploy..."
                    sendTap(width * 0.76f, height * 0.95f, onSuccess = {
                        testing = false
                        status?.text = "F$formation + Deploy gestures completed"
                        val doneNs = SystemClock.elapsedRealtimeNanos()
                        if (timing != null) {
                            val report = "Capture ${ms(timing.screenshotStartNs, timing.screenshotEndNs)}ms" +
                                " | Match ${ms(timing.screenshotEndNs, timing.matchEndNs)}ms" +
                                " | Select ${ms(selectStartNs, selectDoneNs)}ms" +
                                " | Wait ${ms(selectDoneNs, deployStartNs)}ms" +
                                " | Deploy ${ms(deployStartNs, doneNs)}ms" +
                                " | Total ${ms(timing.screenshotStartNs, doneNs)}ms"
                            latencyReport?.text = report
                            Log.i("WosFastRally", "LATENCY $report")
                        }
                    }, onFailure = {
                        testing = false
                        status?.text = "Deploy gesture cancelled/rejected"
                    })
                }, 100L)
            }, onFailure = {
                testing = false
                status?.text = "Formation gesture failed; Deploy skipped"
            })
    }

    private fun sendTap(x: Float, y: Float, onSuccess: () -> Unit, onFailure: () -> Unit) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(
                Path().apply { moveTo(x, y) }, 0L, 65L))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onSuccess()
            override fun onCancelled(gestureDescription: GestureDescription?) = onFailure()
        }, Handler(Looper.getMainLooper()))
        if (!accepted) onFailure()
    }

    private fun detectedPackages(): Set<String> {
        val found = linkedSetOf<String>()
        rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let(found::add)
        windows?.forEach { window ->
            window.root?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let(found::add)
        }
        return found
    }

    // Fixed-size normalized crop of the top-left title, excluding the back arrow.
    // A reference must be captured on this device on the actual Deploy-title screen.
    private fun titleSamples(bitmap: android.graphics.Bitmap): IntArray {
        val readable = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE)
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Cannot copy screenshot")
        else bitmap
        try {
        val result = IntArray(32 * 12)
        for (y in 0 until 12) for (x in 0 until 32) {
            val px = ((0.125f + (x + 0.5f) / 32f * 0.29f) * readable.width)
                .toInt().coerceIn(0, readable.width - 1)
            val py = ((0.039f + (y + 0.5f) / 12f * 0.047f) * readable.height)
                .toInt().coerceIn(0, readable.height - 1)
            result[y * 32 + x] = readable.getPixel(px, py) and 0x00ffffff
        }
        return result
        } finally { if (readable !== bitmap) readable.recycle() }
    }

    private fun compareTitle(reference: IntArray, current: IntArray): Float {
        if (reference.size != current.size) return 0f
        var difference = 0L
        for (i in reference.indices) {
            val a = reference[i]
            val b = current[i]
            difference += kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255))
            difference += kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255))
            difference += kotlin.math.abs((a and 255) - (b and 255))
        }
        return 1f - difference.toFloat() / (reference.size * 765f)
    }

    private fun loadReference(): IntArray? {
        val encoded = getSharedPreferences("config", MODE_PRIVATE)
            .getString("deployTitleReference", null) ?: return null
        return try {
            encoded.split(",").map { it.toInt() }.toIntArray()
                .takeIf { it.size == 32 * 12 }
        } catch (_: Exception) { null }
    }

    private fun captureTitleReference() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            rootInActiveWindow?.packageName?.toString() != "com.gof.global") {
            status?.text = "Open WOS Deploy-title screen first"
            return
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace)
                            if (bitmap == null) {
                                status?.text = "Reference capture unavailable"
                                return
                            }
                            val pixels = titleSamples(bitmap)
                            referencePixels = pixels
                            referenceButton?.text = "REF: SAVED ✓"
                            getSharedPreferences("config", MODE_PRIVATE).edit()
                                .putString("deployTitleReference", pixels.joinToString(",")).apply()
                            status?.text = "Reference saved (confirm title was Deploy)"
                        } catch (e: Exception) {
                            status?.text = "Reference capture error: ${e.javaClass.simpleName}"
                            Log.e("WosFastRally", "Deploy capture failed", e)
                        } finally { result.hardwareBuffer.close() }
                    }
                    override fun onFailure(errorCode: Int) {
                        status?.text = "Reference screenshot failed: $errorCode"
                    }
                })
        } catch (e: Exception) {
            status?.text = "Reference failed: ${e.javaClass.simpleName}"
        }
    }

    private fun scheduleTargetRetry(delayMs: Long = 350L) {
        mainHandler.removeCallbacks(targetRetry)
        if (targetAutoArmed) mainHandler.postDelayed(targetRetry, delayMs)
    }

    private fun probeFormation(trigger: String) {
        if (!detectionArmed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || screenshotInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProbeMs < 350L) {
            scheduleTargetRetry(350L - (now - lastProbeMs) + 20L)
            return
        }
        lastProbeMs = now
        val root = rootInActiveWindow
        val gameActive = root?.packageName?.toString() == "com.gof.global"
        if (!gameActive) {
            status?.text = "Detection: WOS not foreground"
            scheduleTargetRetry(400L)
            return
        }
        // Unity may not expose the title in its accessibility hierarchy.
        // Inspect only accessible labels, never assume package == formation screen.
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || labels.size > 150) return
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, depth + 1) }
        }
        root?.let { collect(it, 0) }
        val started = SystemClock.elapsedRealtimeNanos()
        screenshotInFlight = true
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                buffer, result.colorSpace)
                            // Sample Deploy title only; screenshots are not stored or uploaded.
                            val screenshotDoneNs = SystemClock.elapsedRealtimeNanos()
                            val elapsed = (screenshotDoneNs - started) / 1_000_000
                            val reference = referencePixels
                            val score = if (bitmap != null && reference != null)
                                compareTitle(reference, titleSamples(bitmap)) else null
                            status?.text = "Deploy " +
                                (score?.let { "${(it * 100).toInt()}%" } ?: "reference missing")
                            if (targetAutoArmed) {
                                val matchesDeploy = score != null && score >= 0.96f
                                if (waitingForDeployExit) {
                                    // Never deploy twice on the same visible formation.
                                    // Require two non-matching screenshots after the gesture.
                                    if (!testing && !matchesDeploy) deployExitMisses++
                                    else if (matchesDeploy) deployExitMisses = 0
                                    if (deployExitMisses >= 2) {
                                        waitingForDeployExit = false
                                        deployExitMisses = 0
                                        status?.text = "Deploy screen exited • ready for next rally"
                                    }
                                    scheduleTargetRetry(400L)
                                } else if (!testing && matchesDeploy &&
                                    SystemClock.elapsedRealtimeNanos() - lastAutoDeployNs > 1_500_000_000L) {
                                    waitingForDeployExit = true
                                    deployExitMisses = 0
                                    lastAutoDeployNs = SystemClock.elapsedRealtimeNanos()
                                    status?.text = "Deploy ${(score!! * 100).toInt()}%; selecting F$selected"
                                    selectAndDeploy(DeployTiming(started, screenshotDoneNs,
                                        SystemClock.elapsedRealtimeNanos()))
                                    scheduleTargetRetry(400L)
                                } else {
                                    scheduleTargetRetry(400L)
                                }
                            }
                            Log.i("WosFastRally", "v0.6 trigger=${trigger} screenshotMs=${elapsed} labels=${labels.size}")
                        } catch (e: Exception) {
                            status?.text = "Detection error: ${e.javaClass.simpleName}"
                            Log.e("WosFastRally", "Detection screenshot failed", e)
                            scheduleTargetRetry(500L)
                        } finally {
                            buffer.close()
                            screenshotInFlight = false
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        screenshotInFlight = false
                        status?.text = "Screenshot failed ($errorCode)"
                        Log.w("WosFastRally", "Screenshot failed code=${errorCode}")
                        scheduleTargetRetry(if (errorCode == 3) 650L else 400L)
                    }
                })
        } catch (e: Exception) {
            screenshotInFlight = false
            status?.text = "Screenshot unavailable: ${e.javaClass.simpleName}"
            Log.w("WosFastRally", "Screenshot unavailable", e)
            scheduleTargetRetry(500L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (detectionArmed && pkg == "com.gof.global" &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            probeFormation("accessibility-event")
        }
    }
    override fun onInterrupt() {}
    private fun removePanel() {
        panel?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        panel = null
        slotViews.clear()
        status = null
        bodyPanel = null
        collapseControl = null
        referenceButton = null
        detectionArmed = false
        targetAutoArmed = false
        mainHandler.removeCallbacks(targetRetry)
        targetAutoButton = null
    }
    override fun onDestroy() { removePanel(); super.onDestroy() }
}
