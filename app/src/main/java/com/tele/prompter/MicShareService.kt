package com.tele.prompter

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// Service vide : sa seule présence donne à l'app la priorité micro (partagé avec la caméra).
class MicShareService : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
