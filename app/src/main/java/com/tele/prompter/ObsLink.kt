package com.tele.prompter

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pilotage d'OBS (War Room) par obs-websocket v5 (intégré à OBS 28+ : Outils → Paramètres du serveur WebSocket).
 *  - source média « WarRoom »        : reçoit l'écran du dossier en cours (wr-00.mp4, wr-01.mp4 … wr-fin.mp4), en boucle
 *  - source média « WarRoom-Alerte » : la transition ALERTE (alerte.webm, fond transparent), rejouée à chaque nouveau dossier
 * Jamais bloquant : OBS absent / mauvais mot de passe / Wi-Fi coupé → le prompteur continue normalement, l'état est
 * affiché, et la connexion est retentée toute seule toutes les 4 s. À la reconnexion, l'écran en cours est renvoyé.
 */
class ObsLink(
    private val ip: String, private val port: Int, private val pw: String, private val dir: String,
    private val onState: (String, Boolean) -> Unit,
) {
    private val h = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closed = false        // plus de nouvel essai
    @Volatile private var stopped = false       // prompteur fermé
    @Volatile private var want: String? = null       // écran à afficher (renvoyé à chaque (re)connexion)
    private var reqN = 0

    fun start() { connect() }

    fun stop() {
        stopped = true; closed = true; ready = false
        h.removeCallbacksAndMessages(null)
        try { ws?.close(1000, null) } catch (_: Exception) {}
        try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
    }

    /** Affiche l'écran `clip` (ex. « wr-03.mp4 ») ; alerte = jouer d'abord la transition ALERTE. */
    fun show(clip: String, alerte: Boolean) {
        want = clip
        if (!ready) return
        if (alerte) {
            req("TriggerMediaInputAction", JSONObject().put("inputName", "WarRoom-Alerte")
                .put("mediaAction", "OBS_WEBSOCKET_MEDIA_INPUT_ACTION_RESTART"))
            h.postDelayed({ if (want == clip) setClip(clip) }, 350)      // l'écran change sous le flash rouge
        } else setClip(clip)
    }

    private fun path(clip: String): String {
        val d = dir.trim().trimEnd('/', '\\')
        val win = d.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(d)
        return if (d.isEmpty()) clip else d + (if (win) "\\" else "/") + clip
    }

    private fun setClip(clip: String) {
        req("SetInputSettings", JSONObject().put("inputName", "WarRoom").put("overlay", true).put("inputSettings",
            JSONObject().put("is_local_file", true).put("local_file", path(clip)).put("looping", true)
                .put("restart_on_activate", true).put("close_when_inactive", false)))
    }

    private fun req(type: String, data: JSONObject) {
        val m = JSONObject().put("op", 6).put("d", JSONObject().put("requestType", type).put("requestId", "p${++reqN}").put("requestData", data))
        try { ws?.send(m.toString()) } catch (_: Exception) {}
    }

    private fun state(s: String, ok: Boolean) = h.post { if (!stopped) onState(s, ok) }

    private fun connect() {
        if (closed) return
        if (ip.isBlank()) { state("OBS : adresse IP du PC non réglée", false); return }
        state("OBS : connexion…", false)
        ws = client.newWebSocket(Request.Builder().url("ws://${ip.trim()}:$port").build(), object : WebSocketListener() {
            override fun onMessage(w: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (_: Exception) { return }
                val d = j.optJSONObject("d") ?: JSONObject()
                when (j.optInt("op", -1)) {
                    0 -> {      // Hello → Identify (avec mot de passe si OBS en demande un)
                        val id = JSONObject().put("rpcVersion", 1)
                        d.optJSONObject("authentication")?.let { a ->
                            val secret = b64(sha(pw + a.optString("salt")))
                            id.put("authentication", b64(sha(secret + a.optString("challenge"))))
                        }
                        w.send(JSONObject().put("op", 1).put("d", id).toString())
                    }
                    2 -> {      // Identified
                        ready = true; state("OBS ✓ connecté", true)
                        want?.let { c -> h.post { if (ready) setClip(c) } }
                    }
                    7 -> {      // réponse à une requête
                        val st = d.optJSONObject("requestStatus") ?: return
                        if (!st.optBoolean("result", true)) {
                            val code = st.optInt("code")
                            val msg = when {
                                code == 600 && d.optString("requestType") == "TriggerMediaInputAction" -> "source « WarRoom-Alerte » introuvable dans OBS"
                                code == 600 -> "source « WarRoom » introuvable dans OBS"
                                else -> st.optString("comment", "erreur $code")
                            }
                            state("OBS ⚠ $msg", false)
                        }
                    }
                }
            }
            override fun onClosing(w: WebSocket, code: Int, reason: String) {
                ready = false
                // 4009 = mot de passe refusé : inutile de réessayer en boucle
                if (code == 4009) { state("OBS ✗ mot de passe refusé (réglages du Prompteur)", false); closed = true }
                try { w.close(1000, null) } catch (_: Exception) {}
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) { retry("OBS ✗ déconnecté") }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { retry("OBS ✗ injoignable (${ip.trim()}:$port)") }
        })
    }

    private fun retry(s: String) {
        ready = false
        if (closed) return
        state("$s · nouvel essai…", false)
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ connect() }, 4000)
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
