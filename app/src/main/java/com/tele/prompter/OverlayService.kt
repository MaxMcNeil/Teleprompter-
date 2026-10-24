package com.tele.prompter

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.graphics.PixelFormat
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.*
import android.widget.*
import java.io.File
import java.text.Normalizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
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
    private var anchor = 0
    private var model: Model? = null
    @Volatile private var sess = 0
    private var errs = 0
    private var uttBase = -1
    private var lastProg = 0L
    private var pace = 0f
    private var acc = 0f
    private var scrW = 0
    private var scrH = 0
    companion object {
        @Volatile var frModel: Model? = null
        @Volatile var arModel: Model? = null
    }
    private var muted = false
    private var modelFailed = false
    private val hl = BackgroundColorSpan(0xFF1E88E5.toInt())
    private var ai = 1
    private val alphas = intArrayOf(0x30, 0x50, 0x80, 0xB0, 0xF0)
    private val mAccent = Regex("\\p{M}")
    private val mJunk = Regex("[^\\p{L}\\p{N}]")
    private val scroller = object : Runnable {
        override fun run() {
            if (auto && !sv.canScrollVertically(1)) { auto = false; play.text = "▶" }
            if (auto) sv.scrollBy(0, speed)
            else if (voice && speed > 0 && pace > 0f && SystemClock.uptimeMillis() - lastProg < 1200 && sv.scrollY < anchor + sv.height / 5) {
                val ph = tv.layout?.height?.toFloat() ?: 0f
                acc += pace * (ph / max(1, words.size)) * (speed / 2f) * 0.8f * 0.04f
                val d = acc.toInt()
                if (d > 0) { sv.scrollBy(0, d); acc -= d }
            }
            if (auto || voice) h.postDelayed(this, 40)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    // Retire accents / voyelles arabes (tashkeel, hamza), unifie ى/ي et ة/ه, supprime le tatweel
    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(mAccent, "").lowercase()
            .replace('ى', 'ي').replace('ة', 'ه').replace('ٱ', 'ا').replace("ـ", "").replace(mJunk, "")

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("t", "Prompteur", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "t").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Prompteur actif").addAction(0, "Fermer", stop).build()
        try { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) } catch (e: Exception) {
            Toast.makeText(this, "Autorisez le micro à l'app puis relancez (${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
            stopSelf(); return START_NOT_STICKY
        }
        if (::root.isInitialized) teardown()

        val p = getSharedPreferences("t", 0)
        if (p.getBoolean("muted", false)) muteBeep(false)
        val text = (p.getString("text", "") ?: "").take(500_000)
        lang = p.getString("lang", "fr-FR") ?: "fr-FR"
        Regex("[^\\s'’\\-–—]+").findAll(text).forEach { val w = norm(it.value); if (w.isNotEmpty()) { words.add(w); st.add(it.range.first); en.add(it.range.last + 1) } }
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
        play = btn("▶") { if (voice) toggleVoice(); auto = !auto; play.text = if (auto) "⏸" else "▶"; if (auto) { h.removeCallbacks(scroller); h.post(scroller) } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xAA3A0000.toInt())
            addView(drag)
            addView(btn("A−") { size = max(14f, size - 3); tv.textSize = size; tv.post { show() } })
            addView(btn("A+") { size = min(90f, size + 3); tv.textSize = size; tv.post { show() } })
            addView(mic); addView(play)
            addView(btn("🐢") { speed = max(0, speed - 1); Toast.makeText(this@OverlayService, "Vitesse : $speed", Toast.LENGTH_SHORT).show() })
            addView(btn("🐇") { speed = min(12, speed + 1); Toast.makeText(this@OverlayService, "Vitesse : $speed", Toast.LENGTH_SHORT).show() })
            addView(btn("✕") { stopSelf() })
        }
        tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = size; setLineSpacing(0f, 1.2f); setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(24, 24, 24, 500); isClickable = true; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setText(SpannableString(text), TextView.BufferType.SPANNABLE)
        }
        var tx = 0f; var ty = 0f; var tt = 0L
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) { tx = e.x; ty = e.y; tt = e.eventTime }
            else if (e.action == MotionEvent.ACTION_UP && abs(e.x - tx) < 20 && abs(e.y - ty) < 20 && e.eventTime - tt < 300) {
                val off = tv.getOffsetForPosition(e.x, e.y)
                pos = max(0, st.indexOfLast { it <= off }); uttBase = -1; show()
            }
            false
        }
        sv = ScrollView(this).apply { addView(tv); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val grip = TextView(this).apply {
            this.text = "◢"; setTextColor(Color.WHITE); textSize = 26f
            setPadding((24 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END }
        }
        fun arrow(t: String, a: () -> Unit) = TextView(this).apply {
            this.text = t; setTextColor(Color.WHITE); textSize = 24f
            setPadding((22 * dp).toInt(), (6 * dp).toInt(), (22 * dp).toInt(), (6 * dp).toInt()); setOnClickListener { a() }
        }
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(arrow("▲") { move(-1) }); addView(arrow("▼") { move(1) })
            addView(arrow("◐") {
                ai = (ai + 1) % alphas.size
                root.setBackgroundColor((alphas[ai] shl 24) or 0x600000)
                Toast.makeText(this@OverlayService, "Opacité du fond : ${alphas[ai] * 100 / 255} %", Toast.LENGTH_SHORT).show()
            })
            addView(View(this@OverlayService).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            addView(grip)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor((alphas[ai] shl 24) or 0x600000)
            addView(bar); addView(sv); addView(foot)
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
                MotionEvent.ACTION_MOVE -> {
                    val d = resources.displayMetrics
                    lp.x = (a + (e.rawX - dx).toInt()).coerceIn(-lp.width + 120, d.widthPixels - 120)
                    lp.y = (b + (e.rawY - dy).toInt()).coerceIn(0, d.heightPixels - 120)
                    wm.updateViewLayout(root, lp)
                }
            }
            true
        }
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; a = lp.width; b = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    val d = resources.displayMetrics
                    lp.width = (a + (e.rawX - dx).toInt()).coerceIn(350, d.widthPixels)
                    lp.height = (b + (e.rawY - dy).toInt()).coerceIn(350, d.heightPixels)
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> show()
            }
            true
        }
        model = null; modelFailed = false
        val arDir = File(filesDir, "models/ar").listFiles()?.firstOrNull { it.isDirectory }
        if (lang.startsWith("fr")) {
            model = frModel
            if (model == null) StorageService.unpack(this, "model-fr", "model",
                { m -> frModel = m; if (lang.startsWith("fr")) model = m },
                { e -> modelFailed = true; Toast.makeText(this, "Modèle vocal indisponible : ${e.message}", Toast.LENGTH_LONG).show() })
        } else if (lang.startsWith("ar") && File(filesDir, "models/ar.ok").exists() && arDir != null) {
            model = arModel
            if (model == null) Thread {
                try {
                    val m = Model(arDir.absolutePath)
                    arModel = m
                    h.post { if (lang.startsWith("ar")) model = m; Toast.makeText(this, "Modèle arabe prêt", Toast.LENGTH_SHORT).show() }
                } catch (e: Throwable) {
                    h.post { modelFailed = true; Toast.makeText(this, "Modèle arabe indisponible : ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        screen().let { scrW = it.first; scrH = it.second }
        try { wm.addView(root, lp) } catch (e: Exception) {
            Toast.makeText(this, "Fenêtre impossible : autorisez « Afficher par-dessus les autres applis »", Toast.LENGTH_LONG).show()
            stopSelf(); return START_NOT_STICKY
        }
        show()
        return START_NOT_STICKY
    }

    // Taille réelle de l'écran dans l'orientation actuelle
    private fun screen(): Pair<Int, Int> {
        val d = resources.displayMetrics
        var w = d.widthPixels; var hh = d.heightPixels
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land != (w > hh)) { val t = w; w = hh; hh = t }
        return Pair(w, hh)
    }

    // Rotation du téléphone : la fenêtre garde ses proportions et suit l'écran
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (!::root.isInitialized || root.parent == null) return
        h.post { relayout() }
    }

    private fun relayout() {
        if (!::root.isInitialized || root.parent == null) return
        val (w, hh) = screen()
        if (w == scrW && hh == scrH) return
        val l0 = tv.layout
        val topOff = if (l0 != null) l0.getLineStart(l0.getLineForVertical(sv.scrollY)) else 0
        if (scrW > 0 && scrH > 0) {
            lp.width = (lp.width.toLong() * w / scrW).toInt().coerceIn(min(350, w), w)
            lp.height = (lp.height.toLong() * hh / scrH).toInt().coerceIn(min(350, hh), hh)
            lp.x = (lp.x.toLong() * w / scrW).toInt().coerceIn(-lp.width + 120, w - 120)
            lp.y = (lp.y.toLong() * hh / scrH).toInt().coerceIn(0, max(0, hh - 120))
        }
        scrW = w; scrH = hh
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) { return }
        h.postDelayed({
            val l = tv.layout ?: return@postDelayed
            sv.scrollTo(0, l.getLineTop(l.getLineForOffset(min(topOff, tv.text.length))))
            anchor = sv.scrollY
            if (pos > 0) show()
        }, 250)
    }

    private fun teardown() {
        stopVosk(); try { sr?.cancel() } catch (_: Exception) {}
        if (muted) muteBeep(false)
        voice = false; auto = false
        h.removeCallbacksAndMessages(null)
        try { wm.removeView(root) } catch (_: Exception) {}
        words.clear(); st.clear(); en.clear(); pos = 0; pending = -1; anchor = 0
    }

    // Début de la phrase qui contient le mot p (15 mots maximum en arrière)
    private fun sentStart(p: Int): Int {
        val t = tv.text; var i = p
        while (i > 0 && p - i < 15) {
            val c = t[en[i - 1] - 1]
            val gapEnd = if (i < words.size) st[i] else t.length
            if (c == '.' || c == '!' || c == '?' || c == '؟' || c == '…' || t.subSequence(en[i - 1], gapEnd).contains('\n')) break
            i--
        }
        return i
    }

    private fun show() {
        val n = words.size
        if (n == 0) return
        val a = min(pos, n - 1)
        val sp = tv.text as Spannable
        if (pos <= 0) sp.removeSpan(hl) else {
            val last = min(pos, n) - 1
            sp.setSpan(hl, st[sentStart(last)], en[last], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val l = tv.layout ?: return
        val y = max(0, l.getLineTop(l.getLineForOffset(st[a])) - sv.height / 3)
        anchor = y
        if (y > sv.scrollY || y < sv.scrollY - sv.height / 3) sv.smoothScrollTo(0, y)
    }

    private fun muteBeep(on: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        muted = on
        getSharedPreferences("t", 0).edit().putBoolean("muted", on).apply()
        val d = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (st in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM))
            try { am.adjustStreamVolume(st, d, 0) } catch (_: Exception) {}
    }

    private fun move(dir: Int) {
        if (words.isEmpty() || tv.layout == null) return
        val y = max(0, sv.scrollY + dir * sv.height * 6 / 10)
        val off = tv.getOffsetForPosition(24f, (y + sv.height / 3).toFloat())
        pending = -1; uttBase = -1
        pos = max(0, st.indexOfLast { it <= off }); show()
    }

    private fun toggleVoice() {
        voice = !voice
        mic.setTextColor(if (voice) 0xFFFFEB3B.toInt() else Color.WHITE)
        if (!voice) { mic.text = "🎤"; sr?.cancel(); stopVosk(); if (muted) muteBeep(false); return }
        auto = false; play.text = "▶"
        pace = 0f; lastProg = 0L; uttBase = -1; acc = 0f
        anchor = sv.scrollY; h.removeCallbacks(scroller); h.post(scroller)
        if (model != null) { startVosk(); return }
        val expect = lang.startsWith("fr") || (lang.startsWith("ar") && File(filesDir, "models/ar.ok").exists())
        if (expect && !modelFailed) {
            Toast.makeText(this, "Modèle vocal en chargement… réessayez dans quelques secondes", Toast.LENGTH_LONG).show()
            voice = false; mic.setTextColor(Color.WHITE); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Modèle vocal en chargement… réessayez dans quelques secondes", Toast.LENGTH_LONG).show()
            voice = false; mic.setTextColor(Color.WHITE); return
        }
        muteBeep(true); listen()
    }

    // Moteur hors ligne : capture micro de l'app (priorité accessibilité) et reconnaissance Vosk
    private fun startVosk() {
        val my = ++sess
        val src = getSharedPreferences("t", 0).getInt("src2", MediaRecorder.AudioSource.VOICE_RECOGNITION)
        val rate = if (src == MediaRecorder.AudioSource.VOICE_RECOGNITION) 16000 else 48000
        val f = rate / 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try { AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate)) } catch (e: Exception) { null }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Micro indisponible (essayez une autre source micro)", Toast.LENGTH_LONG).show()
            ar?.release(); voice = false; mic.setTextColor(Color.WHITE); return
        }
        val rec = try { Recognizer(model, 16000.0f) } catch (e: Throwable) {
            ar.release(); Toast.makeText(this, "Reconnaissance indisponible : ${e.message}", Toast.LENGTH_LONG).show()
            voice = false; mic.setTextColor(Color.WHITE); return
        }
        fun fail(msg: String) = h.post {
            if (sess == my) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                voice = false; mic.setTextColor(Color.WHITE); mic.text = "🎤"
            }
        }
        Thread {
            val buf = ShortArray(1600 * f)
            val out = ByteArray(3200)
            var quiet = 0; var warned = false
            try {
                ar.startRecording()
                if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    fail("Android refuse le micro à l'app (état ${ar.recordingState})")
                    return@Thread
                }
                while (sess == my) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n < 0) { fail("Erreur micro ($n)"); break }
                    if (n == 0) continue
                    var peak = 0
                    val m = n / f
                    for (i in 0 until m) {
                        var sum = 0
                        for (k in 0 until f) { val x = buf[f * i + k].toInt(); sum += x; if (abs(x) > peak) peak = abs(x) }
                        val v = sum / f
                        out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = (v shr 8).toByte()
                    }
                    if (peak == 0) quiet++ else quiet = 0
                    val lvl = min(4, peak / 1500)
                    val warn = quiet >= 30 && !warned
                    if (warn) warned = true
                    val fin = rec.acceptWaveForm(out, m * 2)
                    val j = if (fin) rec.result else rec.partialResult
                    h.post {
                        if (sess == my) {
                            mic.text = "🎤" + "▁▃▅▇█"[lvl]
                            if (warn) Toast.makeText(this, "Silence total : Android coupe le micro de l'app (la caméra l'occupe). Essayez une autre source micro.", Toast.LENGTH_LONG).show()
                            heardJson(j, if (fin) "text" else "partial")
                        }
                    }
                }
            } catch (e: Throwable) {
                fail("Erreur micro : ${e.message}")
            } finally {
                try { ar.stop() } catch (_: Exception) {}
                ar.release(); rec.close()
            }
        }.start()
    }

    private fun stopVosk() { sess++ }

    private fun heardJson(j: String, key: String) {
        try { val t = JSONObject(j).optString(key); if (t.isNotEmpty()) onHeard(t, key == "text") else if (key == "text") uttBase = -1 } catch (_: Exception) {}
    }

    private fun listen() {
        if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        sr!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang))
    }

    private fun restart() { if (voice) h.postDelayed({ if (voice) listen() }, min(3000L, 150L + errs * 300L)) }

    private fun heard(b: Bundle?, fin: Boolean) {
        errs = 0
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onHeard(it, fin) }
    }

    private var pending = -1
    private val splitter = Regex("[\\s'’\\-–—]+")

    // Suivi : chaque mot entendu est cherché juste après la position actuelle (1 à 3 mots sautés si le mot
    // est assez long) ; un saut plus loin (jusqu'à ~2 phrases) exige 2 mots consécutifs. Jamais de recherche
    // au début/à la fin du texte, jamais de retour en arrière : ce qui est hors texte est ignoré.
    private fun onHeard(s: String, fin: Boolean) {
        val hw = s.split(splitter).map { norm(it) }.filter { it.isNotEmpty() }
        if (hw.isEmpty()) { if (fin) uttBase = -1; return }
        if (uttBase < 0) uttBase = pos
        val n = words.size
        var p = uttBase
        for (i in hw.indices) {
            val w = hw[i]
            var j = -1
            for (d in 0..3) {
                val q = p + d
                if (q >= n) break
                if (words[q] == w && (d == 0 || w.length >= 4)) { j = q; break }
            }
            if (j < 0 && i + 1 < hw.size && w.length + hw[i + 1].length >= 8) {
                var q = p + 1
                while (q < min(n - 1, p + 80)) {
                    if (words[q] == w && words[q + 1] == hw[i + 1]) { j = q; break }
                    q++
                }
            }
            if (j >= 0) p = j + 1
        }
        if (p > pos) {
            val now = SystemClock.uptimeMillis()
            val dt = max(150L, now - lastProg)
            if (lastProg > 0 && dt < 2500 && p - pos <= 8) {
                val inst = (p - pos) * 1000f / dt
                pace = (if (pace == 0f) inst else 0.6f * pace + 0.4f * inst).coerceIn(0.5f, 5f)
            }
            lastProg = now; pos = p; show()
        }
        if (fin) uttBase = -1
    }

    override fun onReadyForSpeech(p: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(r: Float) {}
    override fun onBufferReceived(b: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(t: Int, b: Bundle?) {}
    override fun onError(e: Int) {
        if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Toast.makeText(this, "Micro refusé : autorisez-le dans les réglages de l'app", Toast.LENGTH_LONG).show()
            voice = false; mic.setTextColor(Color.WHITE); if (muted) muteBeep(false); return
        }
        errs++; restart()
    }
    override fun onPartialResults(b: Bundle?) = heard(b, false)
    override fun onResults(b: Bundle?) { heard(b, true); restart() }

    override fun onDestroy() {
        if (muted) muteBeep(false)
        stopVosk()
        voice = false; auto = false
        h.removeCallbacksAndMessages(null)
        try { sr?.destroy() } catch (_: Exception) {}
        if (::root.isInitialized) try { wm.removeView(root) } catch (_: Exception) {}
        super.onDestroy()
    }
}
