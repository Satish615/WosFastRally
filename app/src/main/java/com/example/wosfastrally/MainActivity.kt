package com.example.wosfastrally

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val p = getSharedPreferences("config", MODE_PRIVATE)
  val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }
  val scroll = ScrollView(this); scroll.addView(layout); setContentView(scroll)
  fun label(t: String) { layout.addView(TextView(this).apply { text=t; textSize=16f; setPadding(0,12,0,8) }) }
  label("WOS Fast Rally v0.2 — formation selector (dry run)")
  label("Select F5, F6, or F7 from the floating panel. Drag using its top handle. No gameplay taps are sent in v0.2. Legacy coordinates below are retained for compatibility but are not used by the selector.")
  val values = linkedMapOf("row1x" to .89f,"row1y" to .26f,"row2x" to .89f,"row2y" to .51f,"row3x" to .89f,"row3y" to .77f,"presetx" to .19f,"presety" to .09f,"deployx" to .77f,"deployy" to .95f)
  val edits = mutableMapOf<String,EditText>()
  for ((key,default) in values) { val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
   line.addView(TextView(this).apply { text=key; width=220 })
   val edit=EditText(this).apply { setSingleLine(true); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(p.getFloat(key,default).toString()) }
   line.addView(edit,LinearLayout.LayoutParams(0,-2,1f)); layout.addView(line); edits[key]=edit }
  val check=CheckBox(this).apply { text="Enable final Deploy tap (OFF for dry run)"; isChecked=p.getBoolean("deployEnabled",false) }; layout.addView(check)
  val save=Button(this).apply { text="Save coordinates and safety settings" }; layout.addView(save)
  save.setOnClickListener { val parsed=edits.mapValues { it.value.text.toString().toFloatOrNull() }
   if (parsed.values.any { it==null || it !in 0f..1f }) { Toast.makeText(this,"All coordinates must be 0..1",Toast.LENGTH_LONG).show(); return@setOnClickListener }
   p.edit().apply { parsed.forEach { (k,v)->putFloat(k,v!!) }; putBoolean("deployEnabled",check.isChecked) }.apply()
   Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show() }
  val overlay=Button(this).apply { text="Allow floating overlay" }; layout.addView(overlay)
  overlay.setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
  val accessibility=Button(this).apply { text="Enable accessibility service" }; layout.addView(accessibility)
  accessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
  label("Overlay requires accessibility and overlay permissions. Drag the top handle; tap F5/F6/F7 to change selection. Green means active. No automatic formation or Deploy taps in v0.2.")
 }
}
