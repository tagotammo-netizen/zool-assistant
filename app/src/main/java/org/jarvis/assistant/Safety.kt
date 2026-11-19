package org.jarvis.assistant

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A fall, from the phone's accelerometer: a moment of free fall (almost no pull), a hard knock right after it,
 * then the phone lying still. Each step must happen in order and in time, so dropping the phone on a bed or
 * shaking it does not count. Values are m/s², times are milliseconds.
 */
class FallDetector {
    private var fallStart = -1L
    private var fallEnd = -1L
    private var impactAt = -1L
    private var stillSince = -1L

    fun reset() { fallStart = -1L; fallEnd = -1L; impactAt = -1L; stillSince = -1L }

    /** True once, when a fall is complete. */
    fun update(x: Float, y: Float, z: Float, t: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z)
        if (impactAt > 0) {
            // after the knock: the phone (and the person) lie still
            if (t - impactAt > IMPACT_TO_STILL_MAX) { reset(); return false }
            if (abs(g - GRAVITY) < STILL_TOLERANCE) {
                if (stillSince < 0) stillSince = t
                if (t - stillSince >= STILL_MS) { reset(); return true }
            } else if (t - impactAt > 600) stillSince = -1L      // bouncing just after the knock is normal
            return false
        }
        if (g < FREE_FALL) {
            if (fallStart < 0) fallStart = t
            fallEnd = t
            return false
        }
        if (fallStart >= 0) {
            val fell = fallEnd - fallStart
            if (g > IMPACT && fell >= FREE_FALL_MS && t - fallEnd <= FALL_TO_IMPACT_MS) { impactAt = t; stillSince = -1L; return false }
            if (t - fallEnd > FALL_TO_IMPACT_MS) { fallStart = -1L; fallEnd = -1L }
        }
        return false
    }

    companion object {
        const val GRAVITY = 9.81f
        const val FREE_FALL = 3.0f
        const val FREE_FALL_MS = 100L
        const val IMPACT = 22f
        const val FALL_TO_IMPACT_MS = 800L
        const val STILL_TOLERANCE = 2.5f
        const val STILL_MS = 2000L
        const val IMPACT_TO_STILL_MAX = 6000L
    }
}

/** When to ask "are you alright?" after a long time with nobody using the phone — never during the night. */
object Inactivity {
    const val NIGHT_FROM = 23
    const val NIGHT_TO = 7

    /** [lastActive] and the result are epoch milliseconds; [hours] 0 means off (null). */
    fun nextCheck(lastActive: Long, hours: Int, now: Long): Long? {
        if (hours <= 0) return null
        var at = maxOf(lastActive + hours * 3_600_000L, now + 60_000L)
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        if (h >= NIGHT_FROM || h < NIGHT_TO) {
            if (h >= NIGHT_FROM) cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, NIGHT_TO); cal.set(Calendar.MINUTE, 30); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            at = cal.timeInMillis
        }
        return at
    }

    /** Still nobody? (a little slack: the alarm may come early) */
    fun idle(lastActive: Long, hours: Int, now: Long) = hours > 0 && now - lastActive >= hours * 3_600_000L - 5 * 60_000L
}
