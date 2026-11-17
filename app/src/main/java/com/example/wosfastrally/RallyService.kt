package com.example.wosfastrally

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
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

/** v0.8: Target-title matching diagnostics, no automatic taps. */
class RallyService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val slotViews = mutableMapOf<Int, TextView>()
    private var status: TextView? = null
    private var testButton: TextView? = null
    private var deployButton: TextView? = null
    private var bodyPanel: LinearLayout? = null
    private var collapseControl: TextView? = null
    private var collapsed = false
    private var detectionArmed = false
    private var targetAutoArmed = false
    private var targetAutoButton: TextView? = null
    private var targetAutoMatches = 0
    private var screenshotInFlight = false
    private val targetProbeRunnable = Runnable { if (targetAutoArmed) probeFormation("target-auto-retry") }
    private var lastProbeMs = 0L
    private var detectionButton: TextView? = null
    private var referenceButton: TextView? = null
    private var referencePixels: IntArray? = null
    private var targetPixels: IntArray? = null
    private var referenceCaptureInFlight = false
    private var targetReferenceButton: TextView? = null
    private var lastMatchScore = 0f
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observedPackage: String? = null
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
        collapsed = prefs.getBoolean("collapsed", false)
        detectionArmed = false
        // Old Deploy reference was accidentally captured on Target. Invalidate it.
        if (!prefs.getBoolean("invalidDeployReferenceClearedV12", false)) {
            prefs.edit().remove("deployTitleReference")
                .putBoolean("invalidDeployReferenceClearedV12", true).apply()
        }
        referencePixels = loadReference()
        targetPixels = loadTargetReference()
        if (!Settings.canDrawOverlays(this)) {
            Log.w("WosFastRally", "Overlay permission missing")
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = background(Color.rgb(24, 37, 47))
        }
        val handle = TextView(this).apply {
            text = "☰ WOS • drag"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(30), 1f))
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
        header.addView(toggle, LinearLayout.LayoutParams(dp(45), dp(30)))
        collapseControl = toggle
        root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyPanel = body
        root.addView(body)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (slot in 5..7) {
            val view = TextView(this).apply {
                text = "F$slot"
                textSize = 14f
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
            val lp = LinearLayout.LayoutParams(dp(48), dp(38))
            if (slot != 5) lp.leftMargin = dp(4)
            row.addView(view, lp)
            slotViews[slot] = view
        }
        body.addView(row)
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
        body.addView(test, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(6) })
        testButton = test
        val deploy = TextView(this).apply {
            text = "SELECT + DEPLOY (100 ms)"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = background(Color.rgb(132, 80, 35))
            isClickable = true
            setOnClickListener { selectAndDeploy() }
        }
        body.addView(deploy, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(6) })
        deployButton = deploy
        val diagnostics = TextView(this).apply {
            text = "SHOW detected packages"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = background(Color.rgb(70, 82, 94))
            isClickable = true
            setOnClickListener { showDetectedPackages() }
        }
        body.addView(diagnostics, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(6) })
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        body.addView(status)
        val detection = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = background(Color.rgb(46, 96, 150))
            isClickable = true
            setOnClickListener {
                detectionArmed = !detectionArmed
                text = if (detectionArmed) "DETECT: ON (no taps)" else "DETECT: OFF"
                status?.text = if (detectionArmed) "Detection-only: open formation screen"
                    else "Detection stopped"
                if (detectionArmed) probeFormation("manual")
            }
        }
        detectionButton = detection
        body.addView(detection, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(5) })
        val autoTarget = TextView(this).apply {
            text = "AUTO TARGET TEST: OFF"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = background(Color.rgb(140, 65, 40))
            isClickable = true
            setOnClickListener {
                if (targetAutoArmed) {
                    targetAutoArmed = false
                    mainHandler.removeCallbacks(targetProbeRunnable)
                    targetAutoMatches = 0
                    text = "AUTO TARGET TEST: OFF"
                    status?.text = "Auto Target test cancelled"
                } else if (targetPixels == null) {
                    status?.text = "Save a Target reference first"
                } else {
                    targetAutoArmed = true
                    targetAutoMatches = 0
                    detectionArmed = true
                    detectionButton?.text = "DETECT: ON"
                    text = "AUTO TARGET: ARMED (ONE DEPLOY)"
                    status?.text = "Will send one real Target deployment on confirmed match"
                    mainHandler.removeCallbacks(targetProbeRunnable)
                    mainHandler.postDelayed(targetProbeRunnable, 550L)
                }
            }
        }
        targetAutoButton = autoTarget
        body.addView(autoTarget, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(5) })
        detection.text = "DETECT: OFF"
        val reference = TextView(this).apply {
            text = "SAVE DEPLOY TITLE REFERENCE"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = background(Color.rgb(70, 82, 94))
            isClickable = true
            setOnClickListener { captureTitleReference() }
        }
        referenceButton = reference
        body.addView(reference, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(5) })
        val targetReference = TextView(this).apply {
            text = "SAVE TARGET TITLE REFERENCE"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = background(Color.rgb(70, 82, 94))
            isClickable = true
            setOnClickListener { captureTargetReference() }
        }
        targetReferenceButton = targetReference
        body.addView(targetReference, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(5) })
        updateTargetReferenceIndicator()
        refreshSelection()
        updateCollapsed()
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
        status?.text = "F$selected • 100 ms • manual Deploy"
        if (collapsed) collapseControl?.text = "F$selected ▣"
    }

    private fun updateCollapsed() {
        bodyPanel?.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseControl?.text = if (collapsed) "F$selected ▣" else "−"
        collapseControl?.contentDescription = if (collapsed) "Expand overlay, F$selected"
            else "Collapse overlay"
    }

    private fun testPresetTap() {
        if (testing) return
        // Fail closed until the game's actual package has been observed and confirmed.
        val packages = detectedPackages()
        val gamePackage = "com.gof.global"
        if (gamePackage !in packages) {
            status?.text = "WOS ($gamePackage) not detected; no tap sent"
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

    private fun selectAndDeploy() {
        if (testing) return
        // User must explicitly tap SELECT + DEPLOY while the formation screen is visible.
        // This is not an automatic screen detector.
        if ("com.gof.global" !in detectedPackages()) {
            status?.text = "WOS not detected; no taps sent"
            return
        }
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val formation = selected
        testing = true
        status?.text = "Selecting F$formation..."
        sendTap(width * when (formation) { 5 -> 0.50f; 6 -> 0.60f; else -> 0.705f },
            height * 0.119f, onSuccess = {
                // Delay starts only after Android reports the formation gesture completed.
                Handler(Looper.getMainLooper()).postDelayed({
                    if ("com.gof.global" !in detectedPackages()) {
                        testing = false
                        status?.text = "Game not detected; Deploy cancelled"
                        return@postDelayed
                    }
                    status?.text = "Sending Deploy..."
                    sendTap(width * 0.76f, height * 0.95f, onSuccess = {
                        testing = false
                        status?.text = "F$formation + Deploy gestures completed"
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
        observedPackage?.let(found::add)
        return found
    }

    private fun showDetectedPackages() {
        val packages = detectedPackages().filterNot { it == packageName }
        status?.text = if (packages.isEmpty()) "No external packages visible"
            else "Detected: " + packages.joinToString(", ")
        Log.i("WosFastRally", "Detected packages: $packages")
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

    private fun updateTargetReferenceIndicator() {
        targetReferenceButton?.text = if (targetPixels != null)
            "TARGET REFERENCE: SAVED ✓ (tap to replace)"
        else "TARGET REFERENCE: NOT SAVED (tap to save)"
        targetReferenceButton?.background = background(
            if (targetPixels != null) Color.rgb(22, 117, 72)
            else Color.rgb(70, 82, 94))
    }

    private fun loadTargetReference(): IntArray? {
        val encoded = getSharedPreferences("config", MODE_PRIVATE)
            .getString("targetTitleReference", null) ?: return null
        return try {
            encoded.split(",").map { it.toInt() }.toIntArray()
                .takeIf { it.size == 32 * 12 }
        } catch (_: Exception) { null }
    }

    private fun captureTargetReference() {
        if (referenceCaptureInFlight) {
            status?.text = "Reference capture already in progress"
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            rootInActiveWindow?.packageName?.toString() != "com.gof.global") {
            status?.text = "Open WOS Target screen first"
            return
        }
        referenceCaptureInFlight = true
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace)
                            if (bitmap == null) {
                                status?.text = "Target reference unavailable"
                                return
                            }
                            val pixels = titleSamples(bitmap)
                            targetPixels = pixels
                            updateTargetReferenceIndicator()
                            getSharedPreferences("config", MODE_PRIVATE).edit()
                                .putString("targetTitleReference", pixels.joinToString(",")).apply()
                            status?.text = "Target reference saved; test other screens with DETECT ON"
                        } catch (e: Exception) {
                            status?.text = "Target capture error: ${e.javaClass.simpleName}"
                            Log.e("WosFastRally", "Target capture failed", e)
                        } finally {
                            result.hardwareBuffer.close()
                            referenceCaptureInFlight = false
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        referenceCaptureInFlight = false
                        status?.text = "Target screenshot failed: $errorCode"
                    }
                })
        } catch (e: Exception) {
            referenceCaptureInFlight = false
            status?.text = "Target reference failed: ${e.javaClass.simpleName}"
        }
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

    private fun scheduleTargetProbe(delayMs: Long = 550L) {
        mainHandler.removeCallbacks(targetProbeRunnable)
        if (targetAutoArmed) mainHandler.postDelayed(targetProbeRunnable, delayMs.coerceAtLeast(50L))
    }

    private fun probeFormation(trigger: String) {
        if (!detectionArmed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || screenshotInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProbeMs < 550L) {
            if (targetAutoArmed) scheduleTargetProbe(550L - (now - lastProbeMs))
            return
        }
        lastProbeMs = now
        val root = rootInActiveWindow
        val gameActive = root?.packageName?.toString() == "com.gof.global"
        if (!gameActive) {
            status?.text = "Detection: WOS not foreground"
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
        val titleExposed = labels.any { it.trim().equals("Deploy", ignoreCase = true) }
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
                            // Diagnostics only: do not store/upload screenshot or trigger taps.
                            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                            val dimensions = if (bitmap == null) "unavailable"
                                else "${bitmap.width}x${bitmap.height}"
                            val reference = referencePixels
                            val score = if (bitmap != null && reference != null)
                                compareTitle(reference, titleSamples(bitmap)) else null
                            if (score != null) lastMatchScore = score
                            val targetScore = if (bitmap != null && targetPixels != null)
                                compareTitle(targetPixels!!, titleSamples(bitmap)) else null
                            status?.text = "Capture ${elapsed}ms | Target " +
                                (targetScore?.let { "${(it * 100).toInt()}%" } ?: "no ref") +
                                " | Deploy " +
                                (score?.let { "${(it * 100).toInt()}%" } ?: "no ref")
                            if (targetAutoArmed && !testing) {
                                if (targetScore != null && targetScore >= 0.97f) {
                                    targetAutoMatches++
                                    if (targetAutoMatches >= 2) {
                                        targetAutoArmed = false
                                        mainHandler.removeCallbacks(targetProbeRunnable)
                                        targetAutoMatches = 0
                                        targetAutoButton?.text = "AUTO TARGET TEST: OFF"
                                        status?.text = "Target confirmed; deploying F$selected"
                                        selectAndDeploy()
                                    } else {
                                        scheduleTargetProbe()
                                    }
                                } else {
                                    targetAutoMatches = 0
                                    scheduleTargetProbe()
                                }
                            }
                            Log.i("WosFastRally", "v0.6 trigger=${trigger} screenshotMs=${elapsed} titleAccessible=${titleExposed} labels=${labels.size}")
                        } catch (e: Exception) {
                            status?.text = "Detection error: ${e.javaClass.simpleName}"
                            Log.e("WosFastRally", "Detection screenshot failed", e)
                            scheduleTargetProbe()
                        } finally {
                            buffer.close()
                            screenshotInFlight = false
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        screenshotInFlight = false
                        status?.text = "Screenshot failed (${errorCode}); title accessible: ${titleExposed}"
                        Log.w("WosFastRally", "Screenshot failed code=${errorCode}")
                        // Error 3: screenshot interval too short; retry after cooldown.
                        scheduleTargetProbe(if (errorCode == 3) 800L else 550L)
                    }
                })
        } catch (e: Exception) {
            screenshotInFlight = false
            status?.text = "Screenshot unavailable: ${e.javaClass.simpleName}"
            Log.w("WosFastRally", "Screenshot unavailable", e)
            scheduleTargetProbe(800L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != packageName && pkg != "com.android.systemui") observedPackage = pkg
        if (detectionArmed && pkg == "com.gof.global" &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            probeFormation("accessibility-event")
        }
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
        deployButton = null
        bodyPanel = null
        collapseControl = null
        detectionButton = null
        referenceButton = null
        targetReferenceButton = null
        detectionArmed = false
        mainHandler.removeCallbacks(targetProbeRunnable)
        targetAutoArmed = false
        targetAutoButton = null
    }
    override fun onDestroy() { removePanel(); super.onDestroy() }
}
