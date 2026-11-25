package com.ultron.ai

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Brain {
    fun run(cmd: String) {
        UltronState.log("You: $cmd")
        try {
            if (!Local.handle(cmd)) Agent.handle(cmd)
        } catch (e: Exception) {
            UltronState.log("Error: ${e.message}")
            Speaker.speak(if ((e.message ?: "").startsWith("No API key")) "Add an API key in settings first." else "Something went wrong.")
        }
    }
}

/** Instant, no-AI commands so common actions feel immediate. */
object Local {
    fun handle(cmd: String): Boolean {
        val c = cmd.lowercase().trim().trimEnd('.', '!', '?')
        if (c.contains(" and ") || c.contains(" then ")) return false
        val acc = UltronAccessibility.instance
        fun say(s: String) { UltronState.log("Ultron: $s"); Speaker.speak(s) }
        fun g(name: String, reply: String): Boolean {
            if (acc == null) say("Enable my accessibility permission first.") else { acc.global(name); say(reply) }
            return true
        }
        val am = Apps.ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            c.startsWith("open ") || c.startsWith("launch ") -> {
                val n = c.substringAfter(' ').trim()
                if (Apps.open(n)) say("Opening $n") else say("I can't find $n.")
                true
            }
            c == "go home" || c == "home" -> g("home", "Home.")
            c == "go back" || c == "back" -> g("back", "Back.")
            c == "recents" || c == "recent apps" -> g("recents", "Recents.")
            c.contains("notifications") && c.length < 24 -> g("notifications", "Notifications.")
            c.contains("screenshot") -> g("screenshot", "Screenshot taken.")
            c.contains("lock the phone") || c == "lock screen" || c == "lock phone" -> g("lock", "Locking.")
            c.startsWith("scroll down") -> { if (acc == null) say("Enable my accessibility permission first.") else acc.scroll("down"); true }
            c.startsWith("scroll up") -> { if (acc == null) say("Enable my accessibility permission first.") else acc.scroll("up"); true }
            c.contains("volume up") -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); true }
            c.contains("volume down") -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); true }
            c.contains("what time") -> { say("It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())); true }
            c == "stop" || c == "be quiet" || c == "quiet" -> { Speaker.stop(); true }
            else -> false
        }
    }
}

object Agent {

    private const val SYS = """You are Ultron, a hands-free voice assistant living on the user's Android phone with full control of it. You are fast, sharp, confident and slightly futuristic. Replies are spoken aloud, so keep them to one or two short sentences, with no markdown, no emojis, no lists.

You have two reply modes:
1) Conversation: just reply in plain text.
2) Actions: reply with exactly the word ACTIONS: followed by ONE JSON object and nothing else:
{"say":"short spoken line","continue":false,"actions":[ ... ]}
Set "continue":true when you need to see results (for example after read_screen) before deciding the next step. Use it to run multi-step tasks, up to 10 rounds.

Actions (each is an object with a "do" field):
open_app {name} | open_url {url} | click {text} | tap {x,y} | type {text} | enter | scroll {dir:"down"|"up"} | swipe {x1,y1,x2,y2} | global {name:"home"|"back"|"recents"|"notifications"|"quicksettings"|"screenshot"|"lock"} | wait {ms} | read_screen | remember {key,value} | termux {cmd} | kali_cmd {cmd} | kali_setup | qemu_setup | kali_shell | github_push {repo,message,pages,android_workflow,files:[{path,content}]}

Rules:
- To operate an app: open_app, then read_screen with continue:true, then click/type/scroll based on what you see. Re-read the screen after each step that changes it.
- remember stores durable facts about the user (name, preferences, routines) in your database. Use it when the user tells you something worth keeping.
- To build a website: write complete, polished files and use github_push with pages:true. To build an Android app: write a complete Gradle Kotlin project and use github_push with android_workflow:true. A bare repo name means the user's GitHub account. Keep each response under about 3500 tokens; for large projects push in several rounds with continue:true.
- termux runs a shell command in Termux. kali_cmd runs a command inside the Kali environment. kali_setup installs Kali CLI in Termux, qemu_setup installs QEMU, kali_shell opens an interactive Kali shell.
- If accessibility is off, tell the user to enable Ultron in Accessibility settings.
- Never claim an action worked unless the results say so."""

    private class Resp(val actions: Boolean, val text: String)

    private fun say(s: String) {
        val t = s.trim()
        if (t.isNotEmpty()) { UltronState.log("Ultron: $t"); Speaker.speak(t) }
    }

    private fun system(): String = SYS +
        "\n\nNow: " + SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date()) +
        "\nUser's GitHub account: " + Prefs.get("gh_user", "gehibhani9-spec") +
        "\nSaved memory:\n" + Db.memories()

    /** Streams the reply; plain conversation is spoken sentence by sentence as it arrives. */
    private fun respond(msgs: List<Pair<String, String>>): Resp {
        val buf = StringBuilder()
        var mode = 0 // 0 undecided, 1 chat, 2 actions
        var spoken = 0
        fun flush(last: Boolean) {
            val s = buf.substring(spoken)
            if (last) { say(s); spoken = buf.length; return }
            val idx = s.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
            if (idx >= 0) { say(s.substring(0, idx + 1)); spoken += idx + 1 }
        }
        val full = AiClient.stream(system(), msgs, 4096) { d ->
            buf.append(d)
            if (mode == 0) {
                val t = buf.toString().trimStart()
                if (t.length >= 8) mode = if (t.startsWith("ACTIONS")) 2 else 1
            }
            if (mode == 1) flush(false)
        }
        if (mode == 2 || (mode == 0 && full.trimStart().startsWith("ACTIONS"))) return Resp(true, full)
        flush(true)
        return Resp(false, full)
    }

    private fun parse(text: String): JSONObject? {
        val s = text.trim().removePrefix("ACTIONS:")
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(s.substring(a, b + 1)) } catch (e: Exception) { null }
    }

    fun handle(cmd: String) {
        Db.addHistory("user", cmd)
        val msgs = Db.recent(14).toMutableList()
        var step = 0
        while (step++ < 10) {
            UltronState.mode = Mode.THINKING
            val r = respond(msgs)
            if (!r.actions) { Db.addHistory("assistant", r.text); return }
            val j = parse(r.text)
            if (j == null) { say("I couldn't process that plan."); return }
            say(j.optString("say"))
            val results = StringBuilder()
            val arr = j.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val res = try { exec(a) } catch (e: Exception) { "error: ${e.message}" }
                results.append(a.optString("do")).append(": ").append(res).append('\n')
            }
            if (j.optBoolean("continue")) {
                msgs.add("assistant" to r.text)
                msgs.add("user" to ("RESULTS:\n" + results))
            } else {
                Db.addHistory("assistant", j.optString("say").ifBlank { "Done." })
                val tail = results.toString().lines().firstOrNull { it.startsWith("github_push") }
                if (tail != null) say(tail.substringAfter(": ").take(160))
                return
            }
        }
        say("That took too many steps. Tell me where to continue.")
    }

    private fun exec(a: JSONObject): String {
        val acc = UltronAccessibility.instance
        val off = "Accessibility is off. Enable Ultron in Accessibility settings."
        val ctx = Apps.ctx
        return when (a.optString("do")) {
            "open_app" -> { val ok = Apps.open(a.optString("name")); Thread.sleep(1200); if (ok) "opened" else "app not found" }
            "open_url" -> { Apps.openUrl(a.optString("url")); Thread.sleep(1200); "opened" }
            "click" -> if (acc == null) off else { val ok = acc.clickText(a.optString("text")); Thread.sleep(500); if (ok) "clicked" else "no clickable element with that text" }
            "tap" -> if (acc == null) off else { acc.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat()); Thread.sleep(400); "tapped" }
            "type" -> if (acc == null) off else { val ok = acc.typeText(a.optString("text")); Thread.sleep(300); if (ok) "typed" else "no input field found" }
            "enter" -> if (acc == null) off else { val ok = acc.enter(); Thread.sleep(500); if (ok) "submitted" else "could not submit" }
            "scroll" -> if (acc == null) off else { acc.scroll(a.optString("dir", "down")); Thread.sleep(500); "scrolled" }
            "swipe" -> if (acc == null) off else {
                acc.swipe(a.optDouble("x1").toFloat(), a.optDouble("y1").toFloat(), a.optDouble("x2").toFloat(), a.optDouble("y2").toFloat(), 300)
                Thread.sleep(500); "swiped"
            }
            "global" -> if (acc == null) off else { val ok = acc.global(a.optString("name")); Thread.sleep(500); if (ok) "done" else "failed" }
            "wait" -> { Thread.sleep(a.optLong("ms", 800).coerceIn(0, 5000)); "waited" }
            "read_screen" -> if (acc == null) off else acc.dump()
            "remember" -> { Db.remember(a.optString("key"), a.optString("value")); "saved" }
            "termux" -> if (Termux.run(ctx, a.optString("cmd"), false)) "sent to Termux" else "Termux unavailable"
            "kali_cmd" -> if (Termux.run(ctx, "bash ~/start-kali.sh " + shellQuote(a.optString("cmd")), false)) "sent to Kali" else "Termux unavailable"
            "kali_setup" -> if (Termux.run(ctx, Termux.asset(ctx, "kali_setup.sh"), false)) "Kali install started in Termux" else "Termux unavailable"
            "qemu_setup" -> if (Termux.run(ctx, Termux.asset(ctx, "qemu_setup.sh"), false)) "QEMU install started in Termux" else "Termux unavailable"
            "kali_shell" -> if (Termux.run(ctx, "bash ~/start-kali.sh", false)) "Kali shell opened" else "Termux unavailable"
            "github_push" -> {
                val arr = a.optJSONArray("files") ?: JSONArray()
                val files = ArrayList<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    files.add(f.getString("path") to f.getString("content"))
                }
                GitHub.push(a.optString("repo"), files, a.optString("message", "Update by Ultron"),
                    a.optBoolean("pages"), a.optBoolean("android_workflow"))
            }
            else -> "unknown action"
        }
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
