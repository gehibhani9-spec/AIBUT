package com.ultron.ai

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 48) }

        fun add(label: String, key: String, def: String = "", secret: Boolean = false) {
            col.addView(TextView(this).apply { text = label; setTextColor(0xFF9FE8FF.toInt()); setPadding(0, 24, 0, 4) })
            val e = EditText(this).apply {
                setText(Prefs.get(key, def)); setTextColor(Color.WHITE); setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
            }
            fields[key] = e
            col.addView(e)
        }

        add("Provider: claude, openai or gemini", "provider", "claude")
        add("Wake words (comma separated)", "wake", "ultron,altron,ultra on")
        add("Claude API key", "key_claude", "", true)
        add("Claude model", "model_claude", "claude-sonnet-5-5")
        add("OpenAI-compatible API key (OpenAI, OpenRouter, Groq...)", "key_openai", "", true)
        add("OpenAI-compatible chat URL", "openai_base", "https://api.openai.com/v1/chat/completions")
        add("OpenAI-compatible model", "model_openai", "gpt-4o-mini")
        add("Gemini API key", "key_gemini", "", true)
        add("Gemini model", "model_gemini", "gemini-2.0-flash")
        add("GitHub username", "gh_user", "gehibhani9-spec")
        add("GitHub token (repo + workflow scopes)", "gh_token", "", true)

        col.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                fields.forEach { (k, v) -> Prefs.put(k, v.text.toString().trim()) }
                finish()
            }
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }
}
