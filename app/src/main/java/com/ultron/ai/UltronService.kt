package com.ultron.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.Executors

class UltronService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var rec: SpeechRecognizer? = null
    @Volatile private var processing = false
    private var activeUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startFg()
        Speaker.onIdle = { main.post { if (!processing) resume() } }
        main.post { startListening() }
    }

    override fun onDestroy() {
        Speaker.onIdle = null
        rec?.destroy()
        UltronState.mode = Mode.IDLE
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ultron", "Ultron", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ultron")
            .setContentTitle("Ultron is listening")
            .setContentText("Say the wake word")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun resume() {
        UltronState.mode = if (System.currentTimeMillis() < activeUntil) Mode.LISTENING else Mode.IDLE
        startListening()
    }

    private fun startListening() {
        if (processing || Speaker.isSpeaking()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            UltronState.log("Speech recognition is not available on this phone (install the Google app).")
            return
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        rec?.startListening(i)
    }

    private fun wakeWords(): List<String> =
        Prefs.get("wake", "ultron,altron,ultra on").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, b: Bundle?) {}

        override fun onError(error: Int) {
            val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000L else 250L
            main.postDelayed({ startListening() }, delay)
        }

        override fun onPartialResults(p: Bundle?) {
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: return
            if (!processing && wakeWords().any { t.contains(it) }) UltronState.mode = Mode.LISTENING
        }

        override fun onResults(r: Bundle?) {
            val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.lowercase()?.trim()
            if (text.isNullOrEmpty()) { startListening(); return }
            heard(text, list)
        }
    }

    private fun heard(text: String, alts: List<String>?) {
        val now = System.currentTimeMillis()
        var cmd: String? = null
        for (w in wakeWords()) {
            val idx = text.indexOf(w)
            if (idx >= 0) { cmd = text.substring(idx + w.length).trim { it == ' ' || it == ',' || it == '.' }; break }
        }
        if (cmd == null && now < activeUntil) cmd = text
        if (cmd == null) { startListening(); return }
        activeUntil = now + 12000
        if (cmd.isEmpty()) {
            UltronState.mode = Mode.LISTENING
            Speaker.speak("Yes?")
            if (!Speaker.isSpeaking()) resume()
            return
        }
        processing = true
        UltronState.mode = Mode.THINKING
        val c = cmd
        exec.execute {
            Brain.run(c)
            main.post {
                processing = false
                activeUntil = System.currentTimeMillis() + 12000
                if (!Speaker.isSpeaking()) resume()
            }
        }
    }
}
