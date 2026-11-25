package com.ultron.ai

import android.content.Context
import android.content.Intent
import android.os.Build

object Termux {
    private const val HOME = "/data/data/com.termux/files/home"

    fun asset(ctx: Context, name: String): String = ctx.assets.open(name).bufferedReader().readText()

    /** Runs a bash script in Termux. Needs allow-external-apps=true in ~/.termux/termux.properties. */
    fun run(ctx: Context, script: String, background: Boolean = false): Boolean {
        return try {
            val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            true
        } catch (e: Exception) {
            UltronState.log("Termux error: ${e.message}")
            false
        }
    }
}
