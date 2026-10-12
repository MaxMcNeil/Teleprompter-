package com.tele.prompter

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioManager
import android.graphics.PixelFormat
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service(), RecognitionListener {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var tv: TextView
    private lateinit var sv: ScrollView
    private lateinit var mic: TextView
    private lateinit var play: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var voice = false
    private var auto = false
    private var speed = 2
    private var size = 28f
    private var lang = "fr-FR"
    private val words = ArrayList<String>()
    private val st = ArrayList<Int>()
    private val en = ArrayList<Int>()
    private var pos = 0
    private val hl = ForegroundColorSpan(0xFFFFEB3B.toInt())
    private val mAccent = Regex("\\p{M}")
    private val mJunk = Regex("[^\\p{L}\\p{N}]")
    private val scroller = object : Runnable {
        override fun run() { if (auto) { sv.scrollBy(0, speed); h.postDelayed(this, 40) } }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun norm(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(mAccent, "").lowercase().replace(mJunk, "")

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("t", "Prompteur", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "t").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Prompteur actif").addAction(0, "Fermer", stop).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (::root.isInitialized) return START_NOT_STICKY

        val p = getSharedPreferences("t", 0)
        val text = p.getString("text", "") ?: ""
        lang = p.getString("lang", "fr-FR") ?: "fr-FR"
        Regex("\\S+").findAll(text).forEach { words.add(norm(it.value)); st.add(it.range.first); en.add(it.range.last + 1) }
        if (words.isEmpty()) { Toast.makeText(this, "Texte vide", Toast.LENGTH_SHORT).show(); stopSelf(); return START_NOT_STICKY }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val dp = dm.density
        fun btn(t: String, a: () -> Unit) = TextView(this).apply {
            this.text = t; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { a() }
        }
        val drag = btn("✥") {}
        mic = btn("🎤") { toggleVoice() }
        play = btn("▶") { if (voice) toggleVoice(); auto = !auto; play.text = if (auto) "⏸" else "▶"; if (auto) h.post(scroller) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF3A0000.toInt())
            addView(drag)
            addView(btn("A−") { size = max(14f, size - 3); tv.textSize = size })
            addView(btn("A+") { size = min(90f, size + 3); tv.textSize = size })
            addView(mic); addView(play)
            addView(btn("🐢") { speed = max(1, speed - 1) })
            addView(btn("🐇") { speed = min(12, speed + 1) })
            addView(btn("✕") { stopSelf() })
        }
        tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = size; setLineSpacing(0f, 1.2f)
            setPadding(24, 24, 24, 500); isClickable = true
            setText(SpannableString(text), TextView.BufferType.SPANNABLE)
        }
        var tx = 0f; var ty = 0f; var tt = 0L
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) { tx = e.x; ty = e.y; tt = e.eventTime }
            else if (e.action == MotionEvent.ACTION_UP && abs(e.x - tx) < 20 && abs(e.y - ty) < 20 && e.eventTime - tt < 300) {
                val off = tv.getOffsetForPosition(e.x, e.y)
                pos = max(0, st.indexOfLast { it <= off }); show()
            }
            false
        }
        sv = ScrollView(this).apply { addView(tv); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val grip = TextView(this).apply {
            this.text = "◢"; setTextColor(Color.WHITE); textSize = 26f
            setPadding((24 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END }
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF0600000.toInt())
            addView(bar); addView(sv); addView(grip)
        }
        lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92).toInt(), (dm.heightPixels * 0.4).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (dm.widthPixels * 0.04).toInt(); y = (dm.heightPixels * 0.1).toInt() }

        var dx = 0f; var dy = 0f; var a = 0; var b = 0
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; a = lp.x; b = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = a + (e.rawX - dx).toInt(); lp.y = b + (e.rawY - dy).toInt(); wm.updateViewLayout(root, lp) }
            }
            true
        }
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; a = lp.width; b = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = max(350, a + (e.rawX - dx).toInt()); lp.height = max(350, b + (e.rawY - dy).toInt())
                    wm.updateViewLayout(root, lp)
                }
            }
            true
        }
        wm.addView(root, lp)
        show()
        return START_NOT_STICKY
    }

    private fun show() {
        val n = words.size
        if (n == 0) return
        val a = min(pos, n - 1); val b = min(pos + 5, n - 1)
        (tv.text as Spannable).setSpan(hl, st[a], en[b], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val l = tv.layout ?: return
        sv.smoothScrollTo(0, max(0, l.getLineTop(l.getLineForOffset(st[a])) - sv.height / 3))
    }

    private fun muteBeep(on: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        val d = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (st in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM))
            try { am.adjustStreamVolume(st, d, 0) } catch (_: Exception) {}
    }

    private fun toggleVoice() {
        voice = !voice
        mic.setTextColor(if (voice) 0xFFFFEB3B.toInt() else Color.WHITE)
        if (voice) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Toast.makeText(this, "Reconnaissance vocale indisponible", Toast.LENGTH_LONG).show(); voice = false
                mic.setTextColor(Color.WHITE); return
            }
            auto = false; play.text = "▶"
            muteBeep(true)
            listen()
        } else { sr?.cancel(); muteBeep(false) }
    }

    private fun listen() {
        if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        sr!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang))
    }

    private fun restart() { if (voice) h.postDelayed({ if (voice) listen() }, 150) }

    private fun heard(b: Bundle?) {
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onHeard(it) }
    }

    // Retrouve la position : compare les 3 derniers mots entendus au texte (saut de mots/phrases OK)
    private fun onHeard(s: String) {
        val hw = s.split(" ").map { norm(it) }.filter { it.isNotEmpty() }
        val k = min(3, hw.size)
        if (k < 2) return
        val t = hw.takeLast(k); val n = words.size
        fun find(a: Int, b: Int): Int {
            var best = -1; var bd = Int.MAX_VALUE
            for (i in a..b - k) {
                var ok = true
                for (j in 0 until k) if (words[i + j] != t[j]) { ok = false; break }
                if (ok && abs(i - pos) < bd) { bd = abs(i - pos); best = i + k - 1 }
            }
            return best
        }
        var r = find(max(0, pos - 15), min(n, pos + (if (k == 3) 400 else 60)))
        if (r < 0 && k == 3) r = find(0, n)
        if (r >= 0) { pos = r + 1; show() }
    }

    override fun onReadyForSpeech(p: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(r: Float) {}
    override fun onBufferReceived(b: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(t: Int, b: Bundle?) {}
    override fun onError(e: Int) = restart()
    override fun onPartialResults(b: Bundle?) = heard(b)
    override fun onResults(b: Bundle?) { heard(b); restart() }

    override fun onDestroy() {
        if (voice) muteBeep(false)
        voice = false; auto = false
        h.removeCallbacksAndMessages(null)
        sr?.destroy()
        if (::root.isInitialized) wm.removeView(root)
        super.onDestroy()
    }
}
