package org.jarvis.assistant

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Live captions: everything said near the phone, written in big letters as it is heard, for someone who does not
 * hear well (a talk, a doctor, the family). No wake word; Zol does not act on what it writes here.
 */
class CaptionsActivity : AppCompatActivity() {

    private lateinit var lines: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private val d get() = resources.displayMetrics.density
    private var size = 30f
    private val listener: (String) -> Unit = { line -> runOnUiThread { add(line) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        size = getSharedPreferences("jarvis", MODE_PRIVATE).getFloat("caption_size", 30f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(px(14), px(30), px(14), px(14))
        }
        status = TextView(this).apply { textSize = 15f; setTextColor(0xFF8FD9B6.toInt()); text = "بكتب أي كلام بسمعو…" }
        root.addView(status)
        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(lines) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun button(label: String, click: () -> Unit) = Button(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(0xFF1E3A5F.toInt()) }
            setOnClickListener { click() }
        }
        bar.addView(button("أكبر") { resize(4f) }, LinearLayout.LayoutParams(0, px(58), 1f).apply { marginEnd = px(6) })
        bar.addView(button("أصغر") { resize(-4f) }, LinearLayout.LayoutParams(0, px(58), 1f).apply { marginEnd = px(6) })
        bar.addView(button("امسح") { lines.removeAllViews() }, LinearLayout.LayoutParams(0, px(58), 1f).apply { marginEnd = px(6) })
        bar.addView(button("وقّف") { finish() }, LinearLayout.LayoutParams(0, px(58), 1f))
        root.addView(bar)
        setContentView(root)
    }

    private val closer = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) = finish()
    }

    override fun onResume() {
        super.onResume()
        androidx.core.content.ContextCompat.registerReceiver(this, closer, android.content.IntentFilter(JarvisService.ACTION_CLOSE_SCREENS),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        try { unregisterReceiver(closer) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        val s = JarvisService.instance
        if (s == null) { status.text = "خدمة زول مقفولة: شغّلها من إمكانية الوصول."; return }
        s.captions.forEach { add(it) }
        s.startCaptions(listener)
    }

    override fun onStop() {
        JarvisService.instance?.stopCaptions(listener)
        super.onStop()
    }

    private fun add(line: String) {
        // the newest line is white and big; older ones fade a little
        for (i in 0 until lines.childCount) (lines.getChildAt(i) as? TextView)?.setTextColor(0xFFB0B0B0.toInt())
        lines.addView(TextView(this).apply {
            text = line
            textSize = size
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(6), 0, px(10))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        })
        while (lines.childCount > 60) lines.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resize(by: Float) {
        size = (size + by).coerceIn(18f, 64f)
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putFloat("caption_size", size).apply()
        for (i in 0 until lines.childCount) (lines.getChildAt(i) as? TextView)?.textSize = size
    }

    private fun px(v: Int) = (v * d).toInt()
}
