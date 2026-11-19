package org.jarvis.assistant

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The direction of the Kaaba from where the person is, and spoken guidance to turn towards it. */
object Qibla {
    const val KAABA_LAT = 21.4225
    const val KAABA_LON = 39.8262

    /** Degrees clockwise from true north, 0..360. */
    fun bearing(lat: Double, lon: Double): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(KAABA_LAT)
        val dl = Math.toRadians(KAABA_LON - lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** "شمال شرق" for a compass bearing. */
    fun compassWord(deg: Double): String {
        val names = listOf("الشمال", "الشمال الشرقي", "الشرق", "الجنوب الشرقي", "الجنوب", "الجنوب الغربي", "الغرب", "الشمال الغربي")
        return names[((deg + 22.5) / 45.0).toInt() % 8]
    }

    /** How far to turn: positive = to the right, -180..180. */
    fun turn(heading: Double, bearing: Double): Double {
        var d = (bearing - heading) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    /** A step of guidance; equal steps are not said twice. */
    enum class Step { AHEAD, SLIGHT_RIGHT, SLIGHT_LEFT, RIGHT, LEFT, BEHIND }

    fun step(turn: Double): Step = when {
        kotlin.math.abs(turn) <= 12 -> Step.AHEAD
        turn in 12.0..60.0 -> Step.SLIGHT_RIGHT
        turn in -60.0..-12.0 -> Step.SLIGHT_LEFT
        turn in 60.0..140.0 -> Step.RIGHT
        turn in -140.0..-60.0 -> Step.LEFT
        else -> Step.BEHIND
    }

    /** Sudanese: "على يدك اليمين / يدك الشمال" (left is "شمال" in speech). */
    fun say(step: Step, english: Boolean = false): String = if (english) when (step) {
        Step.AHEAD -> "The qibla is straight ahead."
        Step.SLIGHT_RIGHT -> "Turn a little to the right."
        Step.SLIGHT_LEFT -> "Turn a little to the left."
        Step.RIGHT -> "The qibla is on your right."
        Step.LEFT -> "The qibla is on your left."
        Step.BEHIND -> "The qibla is behind you."
    } else when (step) {
        Step.AHEAD -> "القبلة قدامك طوالي."
        Step.SLIGHT_RIGHT -> "لف شوية على يدك اليمين."
        Step.SLIGHT_LEFT -> "لف شوية على يدك الشمال."
        Step.RIGHT -> "القبلة على يدك اليمين."
        Step.LEFT -> "القبلة على يدك الشمال."
        Step.BEHIND -> "القبلة وراك، لف لورا."
    }

    fun degrees(deg: Double) = deg.roundToInt()
}
