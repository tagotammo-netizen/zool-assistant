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
 * ring still taps there. Everything runs on the phone (ML Kit face detection); no picture is saved or sent.
 */
class HeadControl(
    private val context: Context,
    private val onTap: (Float, Float) -> Unit,
    private val onStatus: (String) -> Unit,
) : LifecycleOwner {

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

    var running = false
        private set

    /** Settings are read each time it starts, so a change in the app applies at the next start. */
    fun start(speed: Float, dwellMs: Long, dwellOn: Boolean, blinkTap: Boolean, invertX: Boolean) {
        if (running) return
        running = true
        val dm = context.resources.displayMetrics
        tracker = HeadTracker(dm.widthPixels, dm.heightPixels, dm.density).also {
            it.speed = speed; it.dwellMs = dwellMs; it.dwellOn = dwellOn; it.blinkTap = blinkTap; it.invertX = invertX
        }
        showCursor()
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // eyes open / closed
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
                onStatus("انظر إلى الأمام لحظة… ثم حرّك رأسك لتحريك الدائرة، وثبّتها لتضغط.")
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
                    face?.leftEyeOpenProbability, face?.rightEyeOpenProbability, SystemClock.elapsedRealtime())
                    ?: return@addOnSuccessListener
                main.post { show(frame) }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun show(frame: HeadTracker.Frame) {
        if (!running) return
        cursor?.update(frame.x, frame.y, frame.progress, frame.face)
        if (!frame.face && !warnedNoFace) { warnedNoFace = true; onStatus("لا أرى وجهك. ضع الهاتف أمامك.") }
        if (frame.face) warnedNoFace = false
        if (frame.tap) {
            cursor?.flash()
            onTap(frame.x, frame.y)
        }
    }

    private fun showCursor() {
        val view = CursorView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(view, lp); cursor = view } catch (e: Exception) { onStatus("تعذّر إظهار المؤشر: ${e.message}") }
    }

    /** The ring: blue while following the head, a growing arc while waiting to tap, grey when no face is seen. */
    private class CursorView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8 * d; color = 0x66000000 }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFB74D.toInt() }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private var cx = -1f
        private var cy = -1f
        private var progress = 0f
        private var face = true
        private var flashUntil = 0L

        fun update(x: Float, y: Float, p: Float, seen: Boolean) {
            cx = x; cy = y; progress = p; face = seen
            invalidate()
        }

        fun flash() { flashUntil = SystemClock.elapsedRealtime() + 250; invalidate() }

        override fun onDraw(canvas: Canvas) {
            if (cx < 0) return
            val r = 18 * d
            val flashing = SystemClock.elapsedRealtime() < flashUntil
            val color = when { flashing -> 0xFF66BB6A.toInt(); face -> 0xFF4FC3F7.toInt(); else -> Color.GRAY }
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
}
