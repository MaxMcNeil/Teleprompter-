package com.tele.prompter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
    private lateinit var et: EditText
    private lateinit var dl: Button
    private val arUrl = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
    private val langs = listOf("fr-FR", "ar-SA", "en-US", "es-ES")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("t", 0)
        if (p.getBoolean("muted", false)) {
            p.edit().putBoolean("muted", false).apply()
            val am = getSystemService(AudioManager::class.java)
            for (st in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM))
                try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        if (intent.action == "com.tele.START") { go(); finish(); return }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        et = EditText(this).apply {
            setText(p.getString("text", "")); hint = "Collez votre texte ici"
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs)
            setSelection(langs.indexOf(p.getString("lang", "fr-FR")).coerceAtLeast(0))
        }
        val srcs = listOf(6, 5, 1, 9)
        val sp2 = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Micro : reconnaissance vocale (conseillé)", "Micro : caméra (CAMCORDER)", "Micro : standard (MIC)", "Micro : non traité (UNPROCESSED)"))
            setSelection(srcs.indexOf(p.getInt("src2", 6)).coerceAtLeast(0))
        }
        dl = Button(this).apply { visibility = View.GONE; setOnClickListener { downloadAr() } }
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { refreshDl(sp.selectedItem as String) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val btn = Button(this).apply {
            text = "▶ Lancer le prompteur"
            setOnClickListener {
                p.edit().putString("text", et.text.toString()).putString("lang", sp.selectedItem as String).putInt("src2", srcs[sp2.selectedItemPosition]).apply()
                go()
            }
        }
        val imp = Button(this).apply {
            text = "📂 Importer un fichier (txt, docx, odt, md)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 42)
            }
        }
        acc = Button(this).apply { setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); fitsSystemWindows = true
            addView(TextView(context).apply { text = "Langue de la voix :" })
            addView(sp); addView(sp2); addView(dl); addView(imp); addView(et); addView(acc); addView(btn)
        })
    }

    private fun a11yOn() = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)

    override fun onResume() {
        super.onResume()
        if (::acc.isInitialized) acc.text = if (!a11yOn()) "⚠ Activer le micro partagé (Accessibilité)" else if (MicShareService.up) "✅ Micro partagé actif" else "⚠ Service activé mais arrêté : désactivez puis réactivez-le"
    }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc != 42 || res != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            try {
                val t = readDoc(uri)
                runOnUiThread {
                    if (t.isBlank()) Toast.makeText(this, "Fichier vide ou illisible", Toast.LENGTH_LONG).show()
                    else {
                        et.setText(t)
                        getSharedPreferences("t", 0).edit().putString("text", t).apply()
                        val warn = if (t.length > 500_000) " (seuls les 500 000 premiers seront lus)" else ""
                        Toast.makeText(this, "Importé : ${t.length} caractères$warn", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Import impossible : ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun readDoc(uri: Uri): String {
        val b = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("lecture impossible")
        if (b.size > 30_000_000) throw Exception("fichier trop gros")
        if (b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "%PDF")
            throw Exception("PDF non pris en charge : enregistrez-le en .txt ou .docx")
        if (b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()) {
            ZipInputStream(b.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml" || e.name == "content.xml") return xmlToText(String(z.readBytes(), Charsets.UTF_8))
                    e = z.nextEntry
                }
            }
            throw Exception("format non reconnu (utilisez .txt, .docx ou .odt)")
        }
        val s = when {
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            else -> {
                val u = String(b, Charsets.UTF_8).removePrefix("\uFEFF")
                if (u.count { it == '\uFFFD' } > u.length / 100) String(b, charset("windows-1252")) else u
            }
        }
        return s.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun xmlToText(x: String): String {
        var t = x.replace(Regex("</w:p>|</text:p>|</text:h>"), "\n\n")
            .replace(Regex("<w:br/>|<w:br [^>]*/>|<text:line-break/>"), "\n")
            .replace(Regex("<w:tab/>|<text:tab/>"), " ")
            .replace(Regex("<[^>]+>"), "")
        t = Regex("&#(\\d+);").replace(t) { String(Character.toChars(it.groupValues[1].toInt())) }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        return t.replace(Regex("\n{3,}"), "\n\n").trim()
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
                c.connectTimeout = 15000; c.readTimeout = 30000
                var done = 0L; var shown = 0L
                val buf = ByteArray(65536)
                ZipInputStream(c.inputStream.buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(dir, e.name)
                        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw SecurityException("zip")
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Autorisez le micro, puis relancez", Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorisez « Afficher par-dessus les autres applis »", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}
