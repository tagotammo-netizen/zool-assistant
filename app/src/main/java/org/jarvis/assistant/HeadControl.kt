package org.jarvis.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Head pointer: the front camera follows the head, a ring on the screen follows the camera, and holding the
 * ring still taps there. A dock of buttons on one edge of the screen is pressed the same way: scroll up and
 * down (held, it keeps scrolling), back, home, notifications, long press, and pause.
 * Everything runs on the phone (ML Kit face detection); no picture is saved or sent.
 */
class HeadControl(
    private val context: Context,
    private val onTap: (Float, Float) -> Unit,
    private val onLongPress: (Float, Float) -> Unit,
    private val onAction: (Action, Float, Float) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onKey: (HeadKeys.Key) -> Unit = {},
) : LifecycleOwner {

    /** Dock buttons. [x], [y] passed with an action are the last point aimed at outside the dock. */
    enum class Action(val glyph: String, val label: String) {
        SCROLL_UP("▲", "فوق"), SCROLL_DOWN("▼", "تحت"), BACK("↩", "رجوع"), HOME("⌂", "الرئيسية"),
        NOTIFICATIONS("🔔", "الإشعارات"), LONG_PRESS("✋", "مطوّل"), PAUSE("⏸", "إيقاف"),
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var provider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null
    private var executor: ExecutorService? = null
    private var cursor: CursorView? = null
    private var tracker: HeadTracker? = null
    private var warnedNoFace = false

    /** Dock side: "left", "right" or "off". */
    private var dockSide = "left"
    private var paused = false
    private var longNext = false
    private var lastX = -1f
    private var lastY = -1f

    // the head keyboard: shown over the half of the screen away from the text field
    private var dwellWanted = true
    private val keyDwell = KeyDwell()
    var keyboardShown = false
        private set

    var running = false
        private set

    /** Settings are read each time it starts, so a change in the app applies at the next start. */
    fun start(speed: Float, dwellMs: Long, dwellOn: Boolean, blinkTap: Boolean, smileTap: Boolean, invertX: Boolean, dock: String) {
        if (running) return
        running = true
        paused = false; longNext = false
        dockSide = dock
        dwellWanted = dwellOn
        keyboardShown = false
        val dm = context.resources.displayMetrics
        tracker = HeadTracker(dm.widthPixels, dm.heightPixels, dm.density).also {
            it.speed = speed; it.dwellMs = dwellMs; it.dwellOn = dwellOn; it.blinkTap = blinkTap
            it.smileTap = smileTap; it.invertX = invertX
        }
        lastX = dm.widthPixels / 2f; lastY = dm.heightPixels / 2f
        showCursor()
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // eyes open / closed, smiling
                .setMinFaceSize(0.25f)
                .build())
        executor = Executors.newSingleThreadExecutor()
        registry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running) return@addListener
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor!!) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                registry.currentState = Lifecycle.State.RESUMED
                onStatus("انظر إلى الأمام لحظة… ثم حرّك رأسك لتحريك الدائرة، وثبّتها لتضغط." +
                    if (dockSide != "off") " أزرار التمرير والرجوع على حافة الشاشة." else "")
            } catch (e: Exception) {
                onStatus("تعذّر تشغيل الكاميرا الأمامية: ${e.message}")
                stop()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        if (!running) return
        running = false
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
        registry.currentState = Lifecycle.State.CREATED
        try { detector?.close() } catch (_: Exception) {}
        detector = null
        executor?.shutdown()
        executor = null
        cursor?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cursor = null
        tracker = null
    }

    fun recenter() { tracker?.recenter() }

    /** Show the keyboard at the top or bottom of the screen; [dwellMs]: how long the ring stays on a key to type it. */
    fun showKeyboard(atTop: Boolean, dwellMs: Long) {
        keyDwell.dwellMs = dwellMs
        keyDwell.reset()
        keyboardShown = true
        cursor?.keyboard(CursorView.Keys(atTop, false, "", emptyList()))
    }

    /** What the text field holds, and the word suggestions. */
    fun keyboardText(text: String, suggestions: List<String>) {
        val k = cursor?.keys ?: return
        cursor?.keyboard(k.copy(preview = text, suggestions = suggestions))
    }

    fun keyboardNumbers(toggle: Boolean = true) {
        val k = cursor?.keys ?: return
        keyDwell.reset()
        cursor?.keyboard(k.copy(numbers = if (toggle) !k.numbers else false))
    }

    fun hideKeyboard() {
        keyboardShown = false
        keyDwell.reset()
        tracker?.dwellOn = dwellWanted
        cursor?.keyboard(null)
    }

    /** Pause or resume tapping by dwell (the pointer still moves, and the pause button still works). */
    fun setPaused(on: Boolean) {
        paused = on
        cursor?.state(paused, longNext)
    }

    val isPaused get() = paused

    /** Where the ring is now, for "اضغط هنا". */
    fun position(): Pair<Float, Float>? = tracker?.let { it.x to it.y }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val det = detector
        if (media == null || det == null || !running) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        det.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() }
                val frame = tracker?.update(face?.headEulerAngleY, face?.headEulerAngleX,
                    face?.leftEyeOpenProbability, face?.rightEyeOpenProbability, SystemClock.elapsedRealtime(),
                    face?.smilingProbability)
                    ?: return@addOnSuccessListener
                main.post { show(frame) }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun show(frame: HeadTracker.Frame) {
        if (!running) return
        val view = cursor
        if (keyboardShown && view != null && typeOnKeyboard(view, frame)) return
        val action = view?.actionAt(frame.x, frame.y)
        if (action == null) { lastX = frame.x; lastY = frame.y }
        val shownProgress = if (paused && action == null) 0f else frame.progress
        view?.update(frame.x, frame.y, shownProgress, frame.face, action)
        if (!frame.face && !warnedNoFace) { warnedNoFace = true; onStatus("لا أرى وجهك. ضع الهاتف أمامك.") }
        if (frame.face) warnedNoFace = false
        if (!frame.tap) return
        if (action != null) {
            view?.flash()
            press(action)
            return
        }
        if (paused) return
        view?.flash()
        if (longNext) {
            longNext = false
            view?.state(paused, false)
            onLongPress(frame.x, frame.y)
        } else {
            onTap(frame.x, frame.y)
        }
    }

    /** True when the ring is over the keyboard: the frame is used there and nothing else is pressed. */
    private fun typeOnKeyboard(view: CursorView, frame: HeadTracker.Frame): Boolean {
        val inside = view.inKeyboard(frame.x, frame.y)
        // the tracker's own dwell would tap the app under the keyboard: keys are typed by [keyDwell] instead
        tracker?.dwellOn = dwellWanted && !inside
        if (!inside) { keyDwell.reset(); view.hoverKey(-1); return false }
        val index = view.keyAt(frame.x, frame.y)
        val key = view.keyOf(index)
        val usable = key != null && (key.kind != HeadKeys.Kind.SUGGEST || key.value.isNotEmpty())
        val (progress, typed) = if (dwellWanted && usable)
            keyDwell.update(index, SystemClock.elapsedRealtime(), key?.kind == HeadKeys.Kind.DELETE) else 0f to false
        view.hoverKey(if (usable) index else -1)
        view.update(frame.x, frame.y, progress, frame.face, null)
        if ((typed || frame.tap) && usable && key != null) {
            if (frame.tap) keyDwell.reset()
            view.flash()
            onKey(key)
        }
        return true
    }

    private fun press(action: Action) {
        when (action) {
            Action.PAUSE -> { setPaused(!paused); onStatus(if (paused) "أوقفت الضغط. ثبّت على ⏸ لتعيده." else "رجع الضغط.") }
            Action.LONG_PRESS -> { longNext = !longNext; cursor?.state(paused, longNext) }
            Action.SCROLL_UP, Action.SCROLL_DOWN -> {
                onAction(action, lastX, lastY)
                tracker?.repeatIn(SystemClock.elapsedRealtime(), SCROLL_REPEAT_MS)   // keep scrolling while held here
            }
            else -> onAction(action, lastX, lastY)
        }
    }

    private fun showCursor() {
        val view = CursorView(context, dockSide)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(view, lp); cursor = view } catch (e: Exception) { onStatus("تعذّر إظهار المؤشر: ${e.message}") }
    }

    /** Put the ring back on top, after another overlay (the talking board) was opened over it. */
    fun raise() {
        val view = cursor ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        try { wm.removeView(view); wm.addView(view, lp) } catch (_: Exception) {}
    }

    /**
     * The ring and the dock, drawn on one transparent layer that lets every touch through.
     * Ring: blue while following the head, a growing arc while waiting to tap, grey when no face is seen,
     * amber while paused, purple when the next tap is a long press.
     */
    private class CursorView(context: Context, private val side: String) : View(context) {
        /** The keyboard on screen: where, which set of keys, the field's text and the suggestions. */
        data class Keys(val atTop: Boolean, val numbers: Boolean, val preview: String, val suggestions: List<String>)

        var keys: Keys? = null
            private set
        private var keyBoxes: List<Pair<HeadKeys.Key, HeadKeys.Box>> = emptyList()
        private val kbArea = RectF()
        private var hovered = -1
        private val kbPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF20A1426.toInt() }
        private val keyBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val keyText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        private val previewText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3F2FD.toInt(); textAlign = Paint.Align.RIGHT }
        private val kbRect = RectF()

        private val d = context.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8 * d; color = 0x66000000 }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFB74D.toInt() }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC0A1426.toInt() }
        private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 20 * d }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCDEBFA.toInt(); textAlign = Paint.Align.CENTER; textSize = 9 * d }
        private val box = RectF()
        private val cells = ArrayList<Pair<Action, RectF>>()
        private var cx = -1f
        private var cy = -1f
        private var progress = 0f
        private var face = true
        private var hover: Action? = null
        private var paused = false
        private var longNext = false
        private var flashUntil = 0L

        fun keyboard(k: Keys?) {
            keys = k
            hovered = -1
            layoutKeys()
            invalidate()
        }

        fun inKeyboard(x: Float, y: Float) = keys != null && kbArea.contains(x, y)

        fun keyAt(x: Float, y: Float) = keyBoxes.indexOfFirst { it.second.contains(x, y) }

        fun keyOf(index: Int) = keyBoxes.getOrNull(index)?.first

        fun hoverKey(index: Int) { if (index != hovered) { hovered = index; invalidate() } }

        private fun layoutKeys() {
            val k = keys
            if (k == null || width == 0) { keyBoxes = emptyList(); kbArea.setEmpty(); return }
            val rows = HeadKeys.rows(k.numbers, k.suggestions)
            val preview = 40 * d
            val rowH = minOf(58 * d, (height * 0.52f - preview) / rows.size)
            val total = HeadKeys.height(rows.size, rowH, preview)
            val top = if (k.atTop) 26 * d else height - total - 26 * d
            kbArea.set(0f, top, width.toFloat(), top + total)
            keyBoxes = HeadKeys.layout(rows, width.toFloat(), top, rowH, preview)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            layoutKeys()
            cells.clear()
            if (side == "off") return
            val size = 56 * d
            val gap = 4 * d
            val all = Action.values()
            val total = all.size * size + (all.size - 1) * gap
            var top = (h - total) / 2f
            val left = if (side == "right") w - size - 6 * d else 6 * d
            for (a in all) {
                cells.add(a to RectF(left, top, left + size, top + size))
                top += size + gap
            }
        }

        /** The dock button under the pointer, made a little larger than drawn so it is easy to hold. */
        fun actionAt(x: Float, y: Float): Action? {
            if (keys != null) return null                     // the dock is hidden while typing
            val slack = 6 * d
            return cells.firstOrNull { (_, r) -> x >= r.left - slack && x <= r.right + slack && y >= r.top - slack && y <= r.bottom + slack }?.first
        }

        fun update(x: Float, y: Float, p: Float, seen: Boolean, over: Action?) {
            cx = x; cy = y; progress = p; face = seen; hover = over
            invalidate()
        }

        fun state(isPaused: Boolean, isLong: Boolean) { paused = isPaused; longNext = isLong; invalidate() }

        fun flash() { flashUntil = SystemClock.elapsedRealtime() + 250; invalidate() }

        private fun drawKeyboard(canvas: Canvas, k: Keys) {
            canvas.drawRoundRect(kbArea, 20 * d, 20 * d, kbPanel)
            // the field's text, its end visible (Arabic runs right to left)
            previewText.textSize = 17 * d
            val room = kbArea.width() - 32 * d
            var shown = k.preview.replace('\n', ' ')
            if (previewText.measureText(shown) > room) {
                while (shown.isNotEmpty() && previewText.measureText("…$shown") > room) shown = shown.drop(1)
                shown = "…$shown"
            }
            canvas.drawText(if (shown.isEmpty()) "اكتب هنا…" else "$shown|", kbArea.right - 16 * d, kbArea.top + 27 * d, previewText)
            for ((i, pair) in keyBoxes.withIndex()) {
                val (key, b) = pair
                kbRect.set(b.left + 3 * d, b.top + 3 * d, b.right - 3 * d, b.bottom - 3 * d)
                keyBg.color = when {
                    i == hovered -> 0xFF1E88E5.toInt()
                    key.kind == HeadKeys.Kind.SUGGEST -> if (key.value.isEmpty()) 0x14FFFFFF else 0x4426A69A
                    key.kind == HeadKeys.Kind.CHAR -> 0x33FFFFFF
                    key.kind == HeadKeys.Kind.ENTER -> 0xFF2E7D32.toInt()
                    key.kind == HeadKeys.Kind.CLOSE -> 0x55E53935
                    else -> 0x22FFFFFF
                }
                canvas.drawRoundRect(kbRect, 12 * d, 12 * d, keyBg)
                keyText.textSize = if (key.kind == HeadKeys.Kind.CHAR) 23 * d else 15 * d
                val label = key.label
                if (label.isEmpty()) continue
                val y = kbRect.centerY() - (keyText.descent() + keyText.ascent()) / 2
                canvas.drawText(label, kbRect.centerX(), y, keyText)
            }
        }

        override fun onDraw(canvas: Canvas) {
            keys?.let { drawKeyboard(canvas, it) }
            if (cells.isNotEmpty() && keys == null) {
                val first = cells.first().second
                val last = cells.last().second
                box.set(first.left - 4 * d, first.top - 4 * d, first.right + 4 * d, last.bottom + 4 * d)
                canvas.drawRoundRect(box, 18 * d, 18 * d, panel)
                for ((a, r) in cells) {
                    val active = (a == Action.PAUSE && paused) || (a == Action.LONG_PRESS && longNext)
                    cell.color = when {
                        a == hover -> 0xFF1E88E5.toInt()
                        active -> 0xFFFFB74D.toInt()
                        else -> 0x33FFFFFF
                    }
                    canvas.drawRoundRect(r, 14 * d, 14 * d, cell)
                    canvas.drawText(if (a == Action.PAUSE && paused) "▶" else a.glyph, r.centerX(), r.centerY() + 2 * d, glyph)
                    canvas.drawText(a.label, r.centerX(), r.bottom - 6 * d, small)
                }
            }
            if (cx < 0) return
            val r = 18 * d
            val flashing = SystemClock.elapsedRealtime() < flashUntil
            val color = when {
                flashing -> 0xFF66BB6A.toInt()
                !face -> Color.GRAY
                longNext -> 0xFFB388FF.toInt()
                paused && hover == null -> 0xFFFFB74D.toInt()
                else -> 0xFF4FC3F7.toInt()
            }
            ring.color = color
            dot.color = color
            canvas.drawCircle(cx, cy, r, halo)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, 3 * d, dot)
            if (progress > 0f) {
                box.set(cx - r - 7 * d, cy - r - 7 * d, cx + r + 7 * d, cy + r + 7 * d)
                canvas.drawArc(box, -90f, 360f * progress, false, arc)
            }
            if (flashing) postInvalidateDelayed(260)
        }
    }

    companion object {
        const val SCROLL_REPEAT_MS = 650L
    }
}
