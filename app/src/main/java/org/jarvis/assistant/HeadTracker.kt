package org.jarvis.assistant

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns head angles from the front camera into a pointer on the screen, with no Android code so it can be tested.
 *
 * The head works like a joystick: looking straight ahead keeps the pointer still, turning the head moves it,
 * further turns move it faster. Keeping the pointer still for [dwellMs] taps there ("dwell click").
 * Closing both eyes for a short moment can tap too, when [blinkTap] is on.
 */
class HeadTracker(width: Int, height: Int, private val density: Float) {

    /** What the screen should show after one camera frame. */
    data class Frame(val x: Float, val y: Float, val progress: Float, val tap: Boolean, val face: Boolean)

    var width = width
        private set
    var height = height
        private set

    /** 0.5 slow .. 1 normal .. 2 fast. */
    var speed = 1f
    var dwellMs = 1500L
    var dwellOn = true
    var blinkTap = false
    var invertX = false
    var invertY = false

    var x = width / 2f
        private set
    var y = height / 2f
        private set

    private var neutralYaw = 0f
    private var neutralPitch = 0f
    private var calibrating = 0                       // frames still to average for "straight ahead"
    private var sumYaw = 0f
    private var sumPitch = 0f
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var lastT = -1L
    private var lastFace = -1L

    private var anchorX = x
    private var anchorY = y
    private var stillSince = -1L
    private var armed = true                          // after a tap the pointer must move away before the next one

    private var eyesClosedSince = -1L

    init { recenter() }

    fun resize(w: Int, h: Int) {
        width = w; height = h
        x = min(x, w - 1f); y = min(y, h - 1f)
    }

    /** "Straight ahead" is whatever the head does in the next few frames; the pointer goes to the middle. */
    fun recenter() {
        calibrating = CALIBRATION_FRAMES
        sumYaw = 0f; sumPitch = 0f
        x = width / 2f; y = height / 2f
        anchorX = x; anchorY = y
        stillSince = -1L
        armed = false
    }

    val calibrated get() = calibrating == 0

    /**
     * One camera frame. Angles in degrees as ML Kit reports them (null when no face was found);
     * eye values are "open" probabilities 0..1 or null. [t] is a time in milliseconds.
     */
    fun update(yaw: Float?, pitch: Float?, leftEye: Float?, rightEye: Float?, t: Long): Frame {
        val dt = if (lastT < 0) 0f else min((t - lastT).coerceAtLeast(0L), 200L) / 1000f
        lastT = t
        if (yaw == null || pitch == null) {
            stillSince = -1L
            eyesClosedSince = -1L
            val lost = lastFace < 0 || t - lastFace > FACE_GRACE_MS
            return Frame(x, y, 0f, false, !lost)
        }
        lastFace = t

        if (calibrating > 0) {
            sumYaw += yaw; sumPitch += pitch
            calibrating--
            if (calibrating == 0) {
                neutralYaw = sumYaw / CALIBRATION_FRAMES
                neutralPitch = sumPitch / CALIBRATION_FRAMES
                smoothYaw = neutralYaw; smoothPitch = neutralPitch
            }
            return Frame(x, y, 0f, false, true)
        }

        // a blink must not throw the pointer: hold still while the eyes are closed
        val eyesClosed = leftEye != null && rightEye != null && leftEye < EYE_CLOSED && rightEye < EYE_CLOSED
        if (!eyesClosed) {
            smoothYaw += SMOOTH * (yaw - smoothYaw)
            smoothPitch += SMOOTH * (pitch - smoothPitch)
            // ML Kit: positive yaw = face turned to the camera's right, which is the person's left
            val dx = -axis(smoothYaw - neutralYaw) * (if (invertX) -1 else 1)
            val dy = -axis(smoothPitch - neutralPitch) * (if (invertY) -1 else 1)
            val pxPerSecond = BASE_DP_PER_DEGREE * density * speed
            x = (x + dx * pxPerSecond * dt).coerceIn(0f, width - 1f)
            y = (y + dy * pxPerSecond * dt).coerceIn(0f, height - 1f)
        }

        var tap = false
        if (blinkTap) {
            if (eyesClosed) {
                if (eyesClosedSince < 0) eyesClosedSince = t
            } else if (eyesClosedSince >= 0) {
                val closedFor = t - eyesClosedSince
                eyesClosedSince = -1L
                if (closedFor in BLINK_MIN_MS..BLINK_MAX_MS) tap = true   // longer than a natural blink, shorter than resting the eyes
            }
        }

        val radius = DWELL_RADIUS_DP * density
        val moved = hypot(x - anchorX, y - anchorY)
        if (moved > radius) {
            anchorX = x; anchorY = y
            stillSince = t
            if (moved > radius * 2 || !armed) armed = true
        } else if (stillSince < 0) {
            stillSince = t
        }
        var progress = 0f
        if (dwellOn && armed && stillSince >= 0) {
            progress = min(1f, (t - stillSince).toFloat() / max(dwellMs, 300L))
            if (progress >= 1f) tap = true
        }
        if (tap) {
            armed = false
            stillSince = t
            progress = 0f
        }
        return Frame(x, y, progress, tap, true)
    }

    /** Degrees beyond the dead zone, so small movements of a resting head do nothing. */
    private fun axis(deg: Float): Float {
        val a = abs(deg)
        if (a < DEAD_ZONE) return 0f
        val v = min(a - DEAD_ZONE, MAX_DEGREES)
        return if (deg > 0) v else -v
    }

    companion object {
        const val CALIBRATION_FRAMES = 8
        const val DEAD_ZONE = 5f
        const val MAX_DEGREES = 20f
        const val BASE_DP_PER_DEGREE = 28f
        const val SMOOTH = 0.35f
        const val DWELL_RADIUS_DP = 22f
        const val EYE_CLOSED = 0.25f
        const val BLINK_MIN_MS = 350L
        const val BLINK_MAX_MS = 1300L
        const val FACE_GRACE_MS = 1500L
    }
}
