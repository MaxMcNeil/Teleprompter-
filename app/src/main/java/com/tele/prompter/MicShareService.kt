package com.tele.prompter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

// Service d'accessibilité : donne à l'app la priorité micro (partagé avec la caméra) et repère
// l'ouverture de l'écran de live YouTube pour couper 🎤 à ce moment-là (une seule fois par live).
class MicShareService : AccessibilityService() {
    companion object { @Volatile var up = false }
    private var inLive = false

    override fun onServiceConnected() { up = true }
    override fun onUnbind(intent: Intent?): Boolean { up = false; return super.onUnbind(intent) }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        if (pkg == "com.google.android.youtube") {
            // Écran de création / diffusion du live (LiveCreationActivity)
            if (!inLive && cls.contains("live", ignoreCase = true)) {
                inLive = true
                try { OverlayService.inst?.ytLive() } catch (_: Exception) {}
            }
        } else if (pkg != packageName && pkg != "com.android.systemui" && cls.endsWith("Activity")) {
            // On a quitté YouTube pour une autre appli : le prochain live sera de nouveau protégé
            inLive = false
        }
    }

    override fun onInterrupt() {}
}
