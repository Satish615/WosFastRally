package com.example.wosfastrally

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)

        fun label(message: String, size: Float = 16f) {
            layout.addView(TextView(this).apply {
                text = message
                textSize = size
                setPadding(0, 12, 0, 8)
            })
        }

        label("WOS Fast Rally", 22f)
        label("Quick setup")
        label("1. Grant floating overlay and accessibility permissions.\n" +
            "2. Open Whiteout Survival and expand the floating panel.\n" +
            "3. On the game's Deploy formation screen, tap SAVE DEPLOY REF once.\n" +
            "4. Choose F5, F6, or F7 and turn AUTO ON.\n" +
            "5. Collapse or drag the panel out of the way. Auto Deploy stays enabled " +
            "until you switch it off.", 14f)

        val overlay = Button(this).apply { text = "Allow floating overlay" }
        layout.addView(overlay)
        overlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        val accessibility = Button(this).apply { text = "Enable accessibility service" }
        layout.addView(accessibility)
        accessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        label("Auto Deploy requires a saved Deploy-title reference. " +
            "The floating panel is the only place to choose a formation or turn Auto Deploy on/off. " +
            "It does not use the old coordinate settings or dry-run checkbox.", 13f)
    }
}
