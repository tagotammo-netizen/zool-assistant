package org.jarvis.assistant

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * The first-run guide: one step per screen, in big letters, read aloud, with one big button that does the step.
 * A step ticks itself off when it is really done (checked again each time the person comes back to the app).
 */
class SetupWizard(private val activity: Activity, private val steps: () -> List<Step>, private val onFinish: () -> Unit) {

    class Step(
        val title: String,
        val explain: String,
        val button: String,
        val done: () -> Boolean,
        val optional: Boolean = false,
        val confirm: String? = null,          // a step Android cannot check: the person says it is done
        val onConfirm: () -> Unit = {},
        val action: () -> Unit,
    )

    private val d = activity.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var index = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var spoken = -1
    private val confirmed = HashSet<String>()

    val shown get() = root != null

    fun show() {
        if (root != null) return
        val list = steps()
        index = list.indexOfFirst { !it.done() }.coerceAtLeast(0)
        val box = FrameLayout(activity).apply {
            setBackgroundColor(0xFF081222.toInt())
            isClickable = true                       // nothing behind it is pressed by mistake
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root = box
        (activity.window.decorView as ViewGroup).addView(box, ViewGroup.LayoutParams(-1, -1))
        tts = TextToSpeech(activity) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) { tts?.language = Locale.forLanguageTag("ar"); speak() }
        }
        render()
    }

    fun hide() {
        root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root = null
        tts?.shutdown()
        tts = null
    }

    /** Back in the app: a step that got done moves the guide on. */
    fun refresh() {
        if (root == null) return
        val list = steps()
        val cur = list.getOrNull(index)
        if (cur != null && isDone(cur)) {
            val next = list.indexOfFirst { !isDone(it) }
            if (next < 0) { finish(); return }
            index = next
        }
        render()
    }

    private fun isDone(s: Step) = s.done() || s.title in confirmed

    private fun finish() { hide(); onFinish() }

    private fun px(v: Int) = (v * d).toInt()

    private fun speak() {
        val s = steps().getOrNull(index) ?: return
        if (!ttsReady || spoken == index) return
        spoken = index
        tts?.speak(s.title + ". " + s.explain, TextToSpeech.QUEUE_FLUSH, null, "setup")
    }

    private fun big(text: String, color: Int, click: () -> Unit) = TextView(activity).apply {
        this.text = text
        textSize = 21f
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(color); cornerRadius = 20 * d }
        isClickable = true
        isFocusable = true
        setPadding(px(16), px(14), px(16), px(14))
        setOnClickListener { click() }
    }

    private fun render() {
        val box = root ?: return
        box.removeAllViews()
        val list = steps()
        val s = list.getOrNull(index) ?: run { finish(); return }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(48), px(24), px(28))
        }
        val count = TextView(activity).apply {
            text = "خطوة ${index + 1} من ${list.size}" + if (s.optional) " • اختيارية" else ""
            textSize = 16f
            setTextColor(0xFF90A4AE.toInt())
        }
        col.addView(count)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = list.size
            progress = list.count { isDone(it) }
        }
        col.addView(bar, LinearLayout.LayoutParams(-1, px(10)).apply { topMargin = px(10) })
        col.addView(TextView(activity).apply {
            text = (if (isDone(s)) "✓ " else "") + s.title
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(30) })
        col.addView(TextView(activity).apply {
            text = s.explain
            textSize = 20f
            setTextColor(0xFFCFD8DC.toInt())
            setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(18) })
        col.addView(big(s.button, 0xFF1E88E5.toInt()) { s.action() }, LinearLayout.LayoutParams(-1, px(76)))
        s.confirm?.let { label ->
            col.addView(big(label, 0xFF2E7D32.toInt()) { confirmed.add(s.title); s.onConfirm(); refresh() },
                LinearLayout.LayoutParams(-1, px(64)).apply { topMargin = px(10) })
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(big("🔊 اسمع تاني", 0x33FFFFFF) { spoken = -1; speak() }, LinearLayout.LayoutParams(0, px(60), 1f))
        val last = index >= list.size - 1
        row.addView(big(if (last) "خلاص" else if (isDone(s) || s.optional) "التالي ←" else "تخطّي", 0x33FFFFFF) {
            if (last) finish() else { index++; render() }
        }, LinearLayout.LayoutParams(0, px(60), 1f).apply { marginStart = px(10) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        col.addView(big("اقفل المعالج", 0x00000000) { finish() }, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(6) })
        box.addView(col, FrameLayout.LayoutParams(-1, -1))
        speak()
    }
}
