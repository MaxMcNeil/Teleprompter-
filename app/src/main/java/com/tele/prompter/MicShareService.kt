package com.tele.prompter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

// Service d'accessibilité : donne à l'app la priorité micro (partagé avec la caméra) et repère
// l'écran de live YouTube. Règle de la protection : si 🎤 a été allumé AVANT l'arrivée sur l'écran
// de live, on le coupe ; s'il a été allumé PENDANT le live, on n'y touche jamais.
class MicShareService : AccessibilityService() {
    companion object {
        @Volatile var up = false
        @Volatile var lastYt = ""
        @Volatile var ytEvents = 0
        @Volatile var inLive = false       // l'écran de live YouTube est affiché
        @Volatile var session = 0L         // début de la « séance live » en cours (0 = aucune)
        private var leftAt = 0L            // moment où l'on a quitté l'écran de live (dans YouTube)
    }

    override fun onServiceConnected() { up = true }
    override fun onUnbind(intent: Intent?): Boolean { up = false; return super.onUnbind(intent) }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        val now = SystemClock.uptimeMillis()
        if (pkg == "com.google.android.youtube") {
            lastYt = cls.substringAfterLast('.'); ytEvents++
            if (cls.contains("live", ignoreCase = true)) {
                if (!inLive) {
                    // Nouvelle séance si aucune en cours, ou si l'on était sorti de l'écran de live depuis ≥ 8 s
                    if (session == 0L || (leftAt > 0L && now - leftAt >= 8000)) session = now
                    inLive = true; leftAt = 0L
                }
                try { OverlayService.inst?.ytLive(session) } catch (_: Exception) {}
            } else if (cls.endsWith("Activity")) {
                if (inLive) { inLive = false; leftAt = now }
            }
        } else if (pkg != "com.android.systemui" && cls.endsWith("Activity")) {
            // Autre appli (ou l'app Prompteur) : la séance live est terminée
            inLive = false; session = 0L; leftAt = 0L
        }
    }

    override fun onInterrupt() {}
}
