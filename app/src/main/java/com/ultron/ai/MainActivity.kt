package com.ultron.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var logTv: TextView
    private val refresh: () -> Unit = { runOnUiThread { logTv.text = UltronState.text(8) } }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 11f; isAllCaps = false
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(OrbView(this))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xB0000000.toInt())
        }
        logTv = TextView(this).apply { setTextColor(0xFF9FE8FF.toInt()); textSize = 13f; text = "Say the wake word, or type below." }
        panel.addView(logTv)

        val input = EditText(this).apply {
            hint = "Type a command..."
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        input.setOnEditorActionListener { v, _, _ ->
            val t = v.text.toString()
            if (t.isNotBlank()) { v.text = ""; Thread { Brain.run(t) }.start() }
            true
        }
        panel.addView(input)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("Start") { ensureService() })
        row1.addView(btn("Stop") { stopService(Intent(this, UltronService::class.java)) })
        row1.addView(btn("Hush") { Speaker.stop() })
        row1.addView(btn("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        row2.addView(btn("Install Kali") { Termux.run(this, Termux.asset(this, "kali_setup.sh")) })
        row2.addView(btn("Install QEMU") { Termux.run(this, Termux.asset(this, "qemu_setup.sh")) })
        row2.addView(btn("Kali shell") { Termux.run(this, "bash ~/start-kali.sh") })
        panel.addView(row1)
        panel.addView(row2)

        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        requestPermissions(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            "com.termux.permission.RUN_COMMAND"), 1)
    }

    private fun ensureService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startForegroundService(Intent(this, UltronService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        ensureService()
    }

    override fun onStart() { super.onStart(); UltronState.listeners.add(refresh); ensureService() }
    override fun onStop() { UltronState.listeners.remove(refresh); super.onStop() }
}
