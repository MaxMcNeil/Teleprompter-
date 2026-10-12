package com.tele.prompter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import android.widget.*

class MainActivity : Activity() {
    private lateinit var acc: Button
    private lateinit var dl: Button
    private val arUrl = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
    private val langs = listOf("fr-FR", "ar-SA", "en-US", "es-ES")

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
        val srcs = listOf(5, 1, 9)
        val sp2 = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Micro : caméra (CAMCORDER)", "Micro : standard (MIC)", "Micro : non traité (UNPROCESSED)"))
            setSelection(srcs.indexOf(p.getInt("src", 5)).coerceAtLeast(0))
        }
        dl = Button(this).apply { visibility = View.GONE; setOnClickListener { downloadAr() } }
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { refreshDl(sp.selectedItem as String) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val btn = Button(this).apply {
            text = "▶ Lancer le prompteur"
            setOnClickListener {
                p.edit().putString("text", et.text.toString()).putString("lang", sp.selectedItem as String).putInt("src", srcs[sp2.selectedItemPosition]).apply()
                go()
            }
        }
        acc = Button(this).apply { setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); fitsSystemWindows = true
            addView(TextView(context).apply { text = "Langue de la voix :" })
            addView(sp); addView(sp2); addView(dl); addView(et); addView(acc); addView(btn)
        })
    }

    private fun a11yOn() = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)

    override fun onResume() {
        super.onResume()
        if (::acc.isInitialized) acc.text = if (a11yOn()) "✅ Micro partagé actif" else "⚠ Activer le micro partagé (Accessibilité)"
    }

    private fun refreshDl(lang: String) {
        val missing = lang.startsWith("ar") && !File(filesDir, "models/ar.ok").exists()
        dl.visibility = if (missing) View.VISIBLE else View.GONE
        if (dl.isEnabled) dl.text = "⬇ Télécharger le modèle arabe (≈320 Mo, Wi‑Fi conseillé)"
    }

    private fun downloadAr() {
        dl.isEnabled = false; dl.text = "Téléchargement… 0 Mo"
        Thread {
            try {
                File(filesDir, "models/ar.ok").delete()
                val dir = File(filesDir, "models/ar").apply { deleteRecursively(); mkdirs() }
                val c = URL(arUrl).openConnection() as HttpURLConnection
                var done = 0L; var shown = 0L
                val buf = ByteArray(65536)
                ZipInputStream(c.inputStream.buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(dir, e.name)
                        if (!f.canonicalPath.startsWith(dir.canonicalPath)) throw SecurityException("zip")
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { o ->
                                var n = z.read(buf)
                                while (n > 0) {
                                    o.write(buf, 0, n); done += n
                                    if (done - shown > 8_000_000) { shown = done; runOnUiThread { dl.text = "Téléchargement… ${done / 1_000_000} Mo" } }
                                    n = z.read(buf)
                                }
                            }
                        }
                        z.closeEntry(); e = z.nextEntry
                    }
                }
                File(filesDir, "models/ar.ok").writeText("1")
                runOnUiThread { dl.visibility = View.GONE; Toast.makeText(this, "Modèle arabe prêt", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { dl.isEnabled = true; dl.text = "Échec (${e.message}) – réessayer" }
            }
        }.start()
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
