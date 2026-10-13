package com.tele.prompter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

// Service vide : sa seule présence donne à l'app la priorité micro (partagé avec la caméra).
class MicShareService : AccessibilityService() {
    companion object { @Volatile var up = false }
    override fun onServiceConnected() { up = true }
    override fun onUnbind(intent: Intent?): Boolean { up = false; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
