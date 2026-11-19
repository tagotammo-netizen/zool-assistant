package org.jarvis.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * Switch scanning on screen: a bright frame over one thing at a time, and a strip of actions at the bottom.
 * [collect] gives what can be pressed now (with what pressing it does); [onStrip] does a strip action.
 * With [autoMs] > 0 the frame moves by itself (one switch).
 */
class SwitchControl(
    private val context: Context,
    private val collect: () -> List<Pair<SwitchScan.Item, () -> Unit>>,
    private val onStrip: (SwitchScan.Strip) -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: FrameView? = null
    private var items: List<Pair<SwitchScan.Item, () -> Unit>> = emptyList()
    private var index = -1
    var autoMs = 0L
        private set

    val running get() = view != null

    private val auto = object : Runnable {
        override fun run() {
            if (view == null || autoMs <= 0) return
            step()
            main.postDelayed(this, autoMs)
        }
    }

    fun start(autoMs: Long) {
        this.autoMs = autoMs
        if (view == null) {
            val v = FrameView(context)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            try { wm.addView(v, lp); view = v } catch (_: Exception) { return }
        }
        refresh()
        main.removeCallbacks(auto)
        if (autoMs > 0) main.postDelayed(auto, autoMs)
    }

    fun stop() {
        main.removeCallbacks(auto)
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        index = -1
    }

    /** What is on screen changed: find the items again and start from the top. */
    fun refresh() {
        val v = view ?: return
        val strip = strip()
        items = collect() + strip.map { item -> item to { onStrip(item.strip!!) } }
        index = -1
        v.show(null, strip)
    }

    /** The "next" switch. */
    fun step() {
        val v = view ?: return
        if (items.isEmpty()) refresh()
        index = SwitchScan.next(index, items.size)
        if (index == 0 && items.size > 1) {            // a new round: the screen may have changed
            val strip = strip()
            items = collect() + strip.map { item -> item to { onStrip(item.strip!!) } }
        }
        v.show(items.getOrNull(index)?.first, strip())
    }

    /** The "press" switch. Before the frame has moved, it starts moving. */
    fun select() {
        if (view == null) return
        val chosen = items.getOrNull(index)
        if (chosen == null) { step(); return }
        view?.flash()
        chosen.second()
        main.postDelayed({ refresh(); if (autoMs > 0) { main.removeCallbacks(auto); main.postDelayed(auto, autoMs) } }, 800)
    }

    /** The strip along the bottom of the whole screen. */
    private fun strip(): List<SwitchScan.Item> {
        val (w, h) = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        return SwitchScan.strip(w, h - (24 * context.resources.displayMetrics.density).toInt(), stripHeight(), true)
    }

    private fun stripHeight() = (64 * context.resources.displayMetrics.density).toInt()

    private class FrameView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var target: SwitchScan.Item? = null
        private var strip: List<SwitchScan.Item> = emptyList()
        private var flashUntil = 0L
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0xFFFFD600.toInt() }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFD600 }
        private val stripBg = Paint().apply { color = 0xE60A1426.toInt() }
        private val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 13 * d }
        private val r = RectF()

        fun show(t: SwitchScan.Item?, s: List<SwitchScan.Item>) { target = t; strip = s; invalidate() }

        fun flash() { flashUntil = System.currentTimeMillis() + 250; invalidate() }

        override fun onDraw(canvas: Canvas) {
            if (strip.isNotEmpty()) {
                canvas.drawRect(0f, strip.first().top.toFloat(), width.toFloat(), height.toFloat(), stripBg)
                for (s in strip) {
                    r.set(s.left + 4 * d, s.top + 6 * d, s.right - 4 * d, s.bottom - 6 * d)
                    canvas.drawRoundRect(r, 10 * d, 10 * d, cell)
                    canvas.drawText(s.label, r.centerX(), r.centerY() - (label.descent() + label.ascent()) / 2, label)
                }
            }
            val t = target ?: return
            r.set(t.left.toFloat(), t.top.toFloat(), t.right.toFloat(), t.bottom.toFloat())
            r.inset(-4 * d, -4 * d)
            if (System.currentTimeMillis() < flashUntil) { canvas.drawRoundRect(r, 12 * d, 12 * d, glow); postInvalidateDelayed(260) }
            canvas.drawRoundRect(r, 12 * d, 12 * d, frame)
        }
    }
}
