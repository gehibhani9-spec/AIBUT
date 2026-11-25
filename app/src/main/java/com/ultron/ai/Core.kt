package com.ultron.ai

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class UltronApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.init(this)
        Apps.ctx = applicationContext
        Speaker.init(this)
    }
}

enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

object UltronState {
    @Volatile var mode = Mode.IDLE
    private val lines = ArrayList<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized fun log(s: String) {
        lines.add(s)
        while (lines.size > 40) lines.removeAt(0)
        listeners.forEach { it() }
    }

    @Synchronized fun text(n: Int = 8): String = lines.takeLast(n).joinToString("\n")
}

object Prefs {
    private lateinit var sp: android.content.SharedPreferences
    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("ultron", Context.MODE_PRIVATE)
    }
    fun get(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }
}

object Db {
    private lateinit var h: Helper

    private class Helper(c: Context) : SQLiteOpenHelper(c, "ultron.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, role TEXT, text TEXT)")
            db.execSQL("CREATE TABLE memory(k TEXT PRIMARY KEY, v TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    fun init(c: Context) { if (!::h.isInitialized) h = Helper(c.applicationContext) }

    fun addHistory(role: String, text: String) {
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("role", role); put("text", text)
        }
        h.writableDatabase.insert("history", null, cv)
    }

    /** Last n messages, merged so roles alternate and the first one is from the user. */
    fun recent(n: Int): List<Pair<String, String>> {
        val raw = ArrayList<Pair<String, String>>()
        h.readableDatabase.rawQuery("SELECT role,text FROM history ORDER BY id DESC LIMIT $n", null).use { c ->
            while (c.moveToNext()) raw.add(c.getString(0) to c.getString(1))
        }
        raw.reverse()
        val res = ArrayList<Pair<String, String>>()
        for (m in raw) {
            if (res.isEmpty()) { if (m.first == "user") res.add(m) }
            else if (res.last().first == m.first) res[res.size - 1] = m.first to (res.last().second + "\n" + m.second)
            else res.add(m)
        }
        return res
    }

    fun remember(k: String, v: String) {
        val cv = ContentValues().apply { put("k", k); put("v", v) }
        h.writableDatabase.insertWithOnConflict("memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun memories(): String {
        val sb = StringBuilder()
        h.readableDatabase.rawQuery("SELECT k,v FROM memory", null).use { c ->
            while (c.moveToNext()) sb.append("- ").append(c.getString(0)).append(": ").append(c.getString(1)).append('\n')
        }
        return if (sb.isEmpty()) "(nothing yet)" else sb.toString()
    }
}

object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending = 0
    var onIdle: (() -> Unit)? = null

    fun init(c: Context) { if (tts == null) tts = TextToSpeech(c.applicationContext, this) }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts?.language = Locale.getDefault()
        tts?.setSpeechRate(1.12f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { UltronState.mode = Mode.SPEAKING }
            override fun onDone(id: String?) { done() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { done() }
        })
    }

    @Synchronized fun speak(t: String) {
        if (!ready || t.isBlank()) return
        pending++
        tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun done() {
        var idle = false
        synchronized(this) { pending--; if (pending <= 0) { pending = 0; idle = true } }
        if (idle) onIdle?.invoke()
    }

    @Synchronized fun isSpeaking() = pending > 0

    fun stop() {
        tts?.stop()
        synchronized(this) { pending = 0 }
        onIdle?.invoke()
    }
}

object Apps {
    lateinit var ctx: Context

    fun open(name: String): Boolean {
        val pm = ctx.packageManager
        val q = name.lowercase().trim()
        if (q.isEmpty()) return false
        val list = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
        fun label(r: android.content.pm.ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        val m = list.firstOrNull { label(it) == q }
            ?: list.firstOrNull { label(it).contains(q) }
            ?: list.firstOrNull { label(it).length > 2 && q.contains(label(it)) }
            ?: return false
        val i = pm.getLaunchIntentForPackage(m.activityInfo.packageName) ?: return false
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = UltronAccessibility.instance ?: ctx
        c.startActivity(i)
        return true
    }

    fun openUrl(url: String) {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = UltronAccessibility.instance ?: ctx
        c.startActivity(i)
    }
}
