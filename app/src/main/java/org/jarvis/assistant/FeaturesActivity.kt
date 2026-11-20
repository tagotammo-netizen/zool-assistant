package org.jarvis.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The features screen, apart from the settings: big tiles in groups, each doing one thing at once.
 * Built in code, so it needs no layout file and cannot break the main screen.
 */
class FeaturesActivity : AppCompatActivity() {

    private val d get() = resources.displayMetrics.density
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF081222.toInt()); isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(px(16), px(36), px(16), px(28))
        }
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun px(v: Int) = (v * d).toInt()

    private fun render() {
        list.removeAllViews()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "ميزات زول"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tileView("✕", "رجوع", "", 0x33FFFFFF) { finish() }, LinearLayout.LayoutParams(px(96), px(56)))
        list.addView(top)
        val service = JarvisService.instance
        if (service == null) list.addView(TextView(this).apply {
            text = "خدمة زول مقفولة. اضغط هنا وفعّلها عشان الميزات تشتغل."
            textSize = 16f
            setTextColor(0xFFFFCC80.toInt())
            setPadding(0, px(12), 0, 0)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        for (group in Features.GROUPS) {
            list.addView(TextView(this).apply {
                text = group.title
                textSize = 17f
                setTextColor(0xFF80CBC4.toInt())
                setPadding(0, px(22), 0, px(8))
            })
            for (row in group.tiles.chunked(2)) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((i, tile) in row.withIndex()) {
                    val on = when (tile.special) {
                        "listen" -> service?.listening == true
                        "head" -> service?.headRunning() == true
                        else -> false
                    }
                    val title = when {
                        tile.special == "listen" -> if (on) "وقّف الاستماع" else "ابدأ الاستماع"
                        tile.special == "head" -> if (on) "وقّف التحكم بالراس" else "التحكم بالراس"
                        else -> tile.title
                    }
                    val color = when {
                        tile.command == "ساعدوني" -> 0xFFB71C1C.toInt()
                        on -> 0xFF00796B.toInt()
                        else -> 0xFF1C2B3C.toInt()
                    }
                    line.addView(tileView(tile.emoji, title, tile.hint, color) { press(tile) },
                        LinearLayout.LayoutParams(0, px(112), 1f).apply { marginStart = if (i == 0) 0 else px(10) })
                }
                if (row.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(10) })
                list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) })
            }
        }
    }

    private fun tileView(emoji: String, title: String, hint: String, color: Int, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(color); cornerRadius = 20 * d }
        isClickable = true
        isFocusable = true
        contentDescription = "$title. $hint"
        setPadding(px(8), px(8), px(8), px(8))
        addView(TextView(context).apply { text = emoji; textSize = 26f; gravity = Gravity.CENTER })
        addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        if (hint.isNotEmpty()) addView(TextView(context).apply {
            text = hint; textSize = 12f; setTextColor(0xFF90A4AE.toInt()); gravity = Gravity.CENTER; maxLines = 1
        })
        setOnClickListener { click() }
    }

    private fun press(tile: Features.Tile) {
        when (tile.special) {
            "wizard", "keepalive", "report", "update", "voice" -> {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, tile.special)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
                return
            }
        }
        val service = JarvisService.instance ?: run {
            Toast.makeText(this, "فعّل خدمة زول أولاً", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        when (tile.special) {
            "listen" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                else if (service.listening) service.stopListening() else service.startListening()
            }
            "pc" -> {
                val need = if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                    else emptyArray()
                if (need.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED })
                    ActivityCompat.requestPermissions(this, need, 3)
                else Toast.makeText(this, service.startPc(), Toast.LENGTH_LONG).show()
            }
            "dialects" -> {
                Toast.makeText(this, "بشوف لهجات المجتمع…", Toast.LENGTH_SHORT).show()
                service.communityDialects(null) { list ->
                    if (isFinishing) return@communityDialects
                    val names = list.map { it.name + if (it.about.isNotEmpty()) " — ${it.about}" else "" } +
                        listOf("📤 شارك لهجتي مع زول", "🌍 رسّل لهجتي للمجتمع")
                    AlertDialog.Builder(this)
                        .setTitle("لهجات المجتمع")
                        .setItems(names.toTypedArray()) { _, i ->
                            when {
                                i < list.size -> service.installCommunity(list[i])
                                i == list.size -> service.handleText("شارك لهجتي")
                                else -> service.handleText("رسل لهجتي للمجتمع")
                            }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            "offline" -> {
                val models = OfflinePack.MODELS
                AlertDialog.Builder(this)
                    .setTitle("الاستماع بدون نت")
                    .setItems(models.map { it.title }.toTypedArray()) { _, i -> service.downloadWhisper(models[i].id) }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
            "mictest" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                else {
                    service.micTest()
                    Toast.makeText(this, "بعد الصفارة قول: زول كم الساعة (مرتين)", Toast.LENGTH_LONG).show()
                }
            }
            "head" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
                else {
                    val msg = if (service.headRunning()) service.stopHead() else service.startHead()
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            else -> if (tile.confirm) AlertDialog.Builder(this)
                .setTitle(tile.title)
                .setMessage("متأكد؟ ${tile.hint}")
                .setPositiveButton("أيوه") { _, _ -> service.handleText(tile.command) }
                .setNegativeButton("لا", null)
                .show()
            else service.handleText(tile.command)
        }
        list.postDelayed({ render() }, 600)
    }
}
