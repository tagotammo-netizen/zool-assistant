package org.jarvis.assistant

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The talking board on top of every app. Big cards, two per row, so they are easy to reach with a finger,
 * the head pointer or a switch. [onCard] gets the card and whether it should go to the caregiver instead of being said.
 */
class AacBoard(
    private val context: Context,
    private val pages: List<Aac.Page>,
    private val onCard: (Aac.Card, Boolean) -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = context.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var page = 0
    private var toCaregiver = false

    val shown get() = root != null

    fun show() {
        if (root != null) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xF5081222.toInt())
            setPadding(px(12), px(28), px(12), px(20))
        }
        root = box
        render()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(box, params) } catch (_: Exception) { root = null }
    }

    fun hide() {
        val r = root ?: return
        root = null
        try { wm.removeView(r) } catch (_: Exception) {}
    }

    /** Everything that can be pressed, for switch scanning: where it is on the screen and what it does. */
    fun targets(): List<Pair<Rect, View>> {
        val out = ArrayList<Pair<Rect, View>>()
        fun walk(v: View) {
            if (v.isClickable && v.isShown) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                out.add(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height) to v)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        root?.let { walk(it) }
        return out
    }

    private fun px(v: Int) = (v * d).toInt()

    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = radius * d }

    private fun button(text: String, color: Int, size: Float, click: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        background = rounded(color)
        isClickable = true
        isFocusable = true
        contentDescription = text
        setPadding(px(10), px(8), px(10), px(8))
        setOnClickListener { click() }
    }

    private fun render() {
        val box = root ?: return
        box.removeAllViews()
        // top: what happens to a card, and close
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(button(if (toCaregiver) "📩 بيترسل للمرافق" else "🔊 بيتقال بصوت عالي",
            if (toCaregiver) 0xFF6A1B9A.toInt() else 0xFF1565C0.toInt(), 17f) {
            toCaregiver = !toCaregiver; render()
        }, LinearLayout.LayoutParams(0, px(58), 1f))
        top.addView(button("✕ اقفل", 0xFFC62828.toInt(), 17f) { onClose() },
            LinearLayout.LayoutParams(px(110), px(58)).apply { marginStart = px(8) })
        box.addView(top)
        // the pages
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, p) in pages.withIndex()) {
            tabs.addView(button(p.title, if (i == page) 0xFF00897B.toInt() else 0x33FFFFFF, 16f) { page = i; render() },
                LinearLayout.LayoutParams(0, px(52), 1f).apply { marginStart = if (i == 0) 0 else px(6) })
        }
        box.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(10) })
        // the cards, two per row
        val cards = pages.getOrNull(page)?.cards.orEmpty()
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (row in cards.chunked(2)) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((j, card) in row.withIndex()) {
                val view = button("${card.emoji}\n${card.text}", if (card.urgent) 0xFFB71C1C.toInt() else 0xFF263445.toInt(), 19f) {
                    onCard(card, toCaregiver)
                }
                view.setLineSpacing(0f, 1.15f)
                line.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .apply { marginStart = if (j == 0) 0 else px(10) })
            }
            if (row.size == 1) line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(10) })
            grid.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(10) })
        }
        box.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(4) })
    }
}
