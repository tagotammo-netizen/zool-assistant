package org.jarvis.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * بنك صوتك: the person records the sentences they say most, in their own voice, while they can. From then on,
 * whenever Zol says one of them (the speech board, "قول بصوتي", a reply), it plays that recording instead of the
 * phone's voice. Sentences not recorded are said by the phone's voice.
 */
class VoiceBankActivity : AppCompatActivity() {

    private val d get() = resources.displayMetrics.density
    private val prefs get() = getSharedPreferences("jarvis", MODE_PRIVATE)
    private lateinit var list: LinearLayout
    private var recorder: MediaRecorder? = null
    private var recording: String? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF081222.toInt()) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(px(16), px(32), px(16), px(24))
        }
        scroll.addView(list)
        setContentView(scroll)
        render()
    }

    override fun onStop() {
        stop(false)
        player?.release(); player = null
        super.onStop()
    }

    private fun px(v: Int) = (v * d).toInt()
    private fun file(text: String) = File(File(filesDir, "voice").apply { mkdirs() }, VoicePack.fileName(text))

    /** The person's own sentences first, then the speech board's. */
    private fun phrases(): List<String> {
        val mine = prefs.getString("bank_phrases", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val board = Aac.pages(prefs.getString("aac_custom", "").orEmpty()).flatMap { p -> p.cards.map { it.text } }
        return (mine + board).distinct()
    }

    private fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
    }

    private fun button(text: String, color: Int, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(color) }
        setOnClickListener { click() }
    }

    private fun render() {
        list.removeAllViews()
        list.addView(label("🎙 بنك صوتك", 26f))
        list.addView(label("سجّل الجمل البتقولها كتير بصوتك إنت. بعدها، أي مرة زول يقول الجملة دي (لوحة الكلام، أو «قول بصوتي»)، " +
            "بيسمّع صوتك إنت بدل صوت التلفون. سجّلها وإنت مرتاح، في مكان هادي.", 14f, 0xFFB8C7D9.toInt()).apply { setPadding(0, px(6), 0, px(14)) })

        // say anything: in the person's voice when it was recorded, the phone's otherwise
        val say = EditText(this).apply {
            hint = "اكتب حاجة وزول يقولها"
            setTextColor(Color.WHITE); setHintTextColor(0xFF7F8FA6.toInt()); textSize = 18f
        }
        list.addView(say)
        val sayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sayRow.addView(button("🔊 قول بصوتي", 0xFF1F7A4D.toInt()) {
            val t = say.text.toString().trim()
            if (t.isNotEmpty()) JarvisService.instance?.sayNow(t) ?: toast("شغّل خدمة زول أولاً")
        }, LinearLayout.LayoutParams(0, px(54), 1f).apply { marginEnd = px(6) })
        sayRow.addView(button("➕ زيدها للبنك", 0xFF1E3A5F.toInt()) {
            val t = say.text.toString().trim()
            if (t.isEmpty()) return@button
            val mine = prefs.getString("bank_phrases", "").orEmpty().lines().filter { it.isNotBlank() }
            if (t !in mine) prefs.edit().putString("bank_phrases", (listOf(t) + mine).joinToString("\n")).apply()
            say.setText("")
            render()
        }, LinearLayout.LayoutParams(0, px(54), 1f))
        list.addView(sayRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(16) })

        val all = phrases()
        val done = all.count { file(it).length() > 0 }
        list.addView(label("سجّلت $done من ${all.size}", 15f, 0xFF8FD9B6.toInt()).apply { setPadding(0, 0, 0, px(8)) })
        for (p in all) {
            val has = file(p).length() > 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(10), px(8), px(10), px(8))
                background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(0xFF12243B.toInt()) }
            }
            row.addView(label((if (has) "✓ " else "") + p, 17f), LinearLayout.LayoutParams(0, -2, 1f))
            val rec = recording == p
            row.addView(button(if (rec) "⏹" else "🎙", if (rec) 0xFFB3261E.toInt() else 0xFF2D4F7C.toInt()) {
                if (rec) stop(true) else start(p)
            }, LinearLayout.LayoutParams(px(58), px(50)).apply { marginStart = px(6) })
            if (has) {
                row.addView(button("▶", 0xFF1F7A4D.toInt()) { play(file(p)) }, LinearLayout.LayoutParams(px(50), px(50)).apply { marginStart = px(6) })
                row.addView(button("🗑", 0xFF5A2A2A.toInt()) { file(p).delete(); render() }, LinearLayout.LayoutParams(px(50), px(50)).apply { marginStart = px(6) })
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
        }
        list.addView(label("صوت كامل يشبهك لأي جملة (مش بس المسجّلة) محتاج خدمة مدفوعة على النت. الجمل المسجّلة هنا بتفضل صوتك الحقيقي، " +
            "وبتشتغل من غير نت.", 13f, 0xFF7F8FA6.toInt()).apply { setPadding(0, px(14), 0, 0) })
    }

    private fun start(phrase: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        stop(false)
        JarvisService.instance?.freeMic()
        val tmp = File(cacheDir, "bank.m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(15_000)
            r.setOutputFile(tmp.path)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) runOnUiThread { stop(true) } }
            r.prepare()
            r.start()
            recorder = r
            recording = phrase
            toast("قول: «$phrase» وبعدين اضغط ⏹")
        } catch (e: Exception) {
            recorder = null; recording = null
            JarvisService.instance?.giveBackMic()
            toast("ما قدرت أسجّل: ${e.message}")
        }
        render()
    }

    private fun stop(keep: Boolean) {
        val r = recorder ?: return
        val phrase = recording
        recorder = null; recording = null
        val ok = try { r.stop(); true } catch (_: Exception) { false }
        r.release()
        JarvisService.instance?.giveBackMic()
        val tmp = File(cacheDir, "bank.m4a")
        if (keep && ok && phrase != null && tmp.length() > 2_000) { tmp.copyTo(file(phrase), true); toast("حفظت صوتك ✓") }
        tmp.delete()
        render()
    }

    private fun play(f: File) {
        player?.release()
        player = try { MediaPlayer().apply { setDataSource(f.path); prepare(); start() } } catch (_: Exception) { null }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
