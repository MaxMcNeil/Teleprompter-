package com.tele.prompter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val langs = listOf("fr-FR", "en-US", "ar-SA", "es-ES")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("t", 0)
        if (intent.action == "com.tele.START") { go(); finish(); return }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        val et = EditText(this).apply {
            setText(p.getString("text", "")); hint = "Collez votre texte ici"
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs)
            setSelection(langs.indexOf(p.getString("lang", "fr-FR")).coerceAtLeast(0))
        }
        val btn = Button(this).apply {
            text = "▶ Lancer le prompteur"
            setOnClickListener {
                p.edit().putString("text", et.text.toString()).putString("lang", sp.selectedItem as String).apply()
                go()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); fitsSystemWindows = true
            addView(TextView(context).apply { text = "Langue de la voix :" })
            addView(sp); addView(et); addView(btn)
        })
    }

    private fun go() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorisez « Afficher par-dessus les autres applis »", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}
