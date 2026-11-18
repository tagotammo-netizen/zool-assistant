package org.jarvis.assistant

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Prayer times worked out on the phone from the date and the place, with no internet.
 * The method is the Egyptian General Authority of Survey (Fajr 19.5°, Isha 17.5°), the one most apps use in Sudan;
 * Asr by the standard shadow ratio (Maliki and Shafi'i). Follows the well-known PrayTimes.org formulas.
 */
object PrayerTimes {

    enum class Prayer(val ar: String, val en: String) {
        FAJR("الفجر", "Fajr"), SUNRISE("الشروق", "Sunrise"), DHUHR("الضهر", "Dhuhr"),
        ASR("العصر", "Asr"), MAGHRIB("المغرب", "Maghrib"), ISHA("العشا", "Isha"),
    }

    /** The five prayers people are reminded of (sunrise is not a prayer). */
    val FIVE = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

    data class City(val name: String, val lat: Double, val lon: Double)

    /** Places people are likely to pick. Matching is by the start of the name, Arabic spelling normalised. */
    val CITIES = listOf(
        City("الخرطوم", 15.5007, 32.5599), City("أم درمان", 15.6445, 32.4777), City("الخرطوم بحري", 15.6400, 32.5300),
        City("بورتسودان", 19.6158, 37.2164), City("ود مدني", 14.4012, 33.5199), City("كسلا", 15.4510, 36.4000),
        City("القضارف", 14.0349, 35.3834), City("الأبيض", 13.1843, 30.2167), City("نيالا", 12.0489, 24.8800),
        City("الفاشر", 13.6279, 25.3494), City("عطبرة", 17.7022, 33.9864), City("كوستي", 13.1629, 32.6635),
        City("دنقلا", 19.1698, 30.4766), City("سنار", 13.5500, 33.6000), City("الدمازين", 11.7891, 34.3592),
        City("الجنينة", 13.4500, 22.4500), City("كادقلي", 11.0167, 29.7167), City("شندي", 16.6915, 33.4341),
        City("جوبا", 4.8594, 31.5713),
    )

    fun city(name: String): City? {
        val n = Commands.normalize(name).replace(" ", "")
        if (n.isEmpty()) return null
        return CITIES.firstOrNull { Commands.normalize(it.name).replace(" ", "") == n }
            ?: CITIES.firstOrNull { Commands.normalize(it.name).replace(" ", "").startsWith(n) }
    }

    private const val FAJR_ANGLE = 19.5
    private const val ISHA_ANGLE = 17.5
    private const val RISE_SET_ANGLE = 0.833

    /**
     * Minutes after local midnight for each prayer on that day.
     * [tzHours] is the offset from UTC, e.g. 2.0 for Sudan.
     */
    fun minutes(year: Int, month: Int, day: Int, lat: Double, lon: Double, tzHours: Double): Map<Prayer, Int> {
        val jd = julian(year, month, day) - lon / (15.0 * 24.0)
        fun sun(t: Double): Pair<Double, Double> {                      // (declination, equation of time)
            val d = jd + t - 2451545.0
            val g = fixAngle(357.529 + 0.98560028 * d)
            val q = fixAngle(280.459 + 0.98564736 * d)
            val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
            val e = 23.439 - 0.00000036 * d
            val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
            val eqt = q / 15.0 - fixHour(ra)
            val decl = darcsin(dsin(e) * dsin(l))
            return decl to eqt
        }
        fun midDay(t: Double) = fixHour(12 - sun(t).second)
        fun angleTime(angle: Double, t: Double, before: Boolean): Double {
            val decl = sun(t).first
            val noon = midDay(t)
            val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            val h = darccos(x.coerceIn(-1.0, 1.0)) / 15.0
            return noon + if (before) -h else h
        }
        fun asr(t: Double): Double {
            val decl = sun(t).first
            val angle = -darccot(1 + dtan(abs(lat - decl)))
            return angleTime(angle, t, false)
        }
        // first guesses (fraction of the day), then one refinement from those guesses
        var times = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0).map { it / 24.0 }.toDoubleArray()
        repeat(2) {
            times = doubleArrayOf(
                angleTime(FAJR_ANGLE, times[0], true),
                angleTime(RISE_SET_ANGLE, times[1], true),
                midDay(times[2]),
                asr(times[3]),
                angleTime(RISE_SET_ANGLE, times[4], false),
                angleTime(RISE_SET_ANGLE, times[5], false),
                angleTime(ISHA_ANGLE, times[6], false),
            ).let { h -> if (it == 0) h.map { v -> v / 24.0 }.toDoubleArray() else h }
        }
        val shift = tzHours - lon / 15.0
        val hours = times.map { it + shift }
        fun mins(h: Double) = ((h * 60.0) + 0.5).toInt().mod(24 * 60)
        return mapOf(
            Prayer.FAJR to mins(hours[0]), Prayer.SUNRISE to mins(hours[1]), Prayer.DHUHR to mins(hours[2]),
            Prayer.ASR to mins(hours[3]), Prayer.MAGHRIB to mins(hours[5]), Prayer.ISHA to mins(hours[6]),
        )
    }

    /** Today's times for a [Calendar] (its own time zone). */
    fun forDay(cal: Calendar, lat: Double, lon: Double): Map<Prayer, Int> {
        val tz = cal.timeZone.getOffset(cal.timeInMillis) / 3_600_000.0
        return minutes(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), lat, lon, tz)
    }

    /** The next of the five prayers after [now], and the minutes of the day it falls on (may be tomorrow's Fajr). */
    fun next(now: Calendar, lat: Double, lon: Double): Triple<Prayer, Int, Boolean> {
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = forDay(now, lat, lon)
        FIVE.firstOrNull { today.getValue(it) > mins }?.let { return Triple(it, today.getValue(it), false) }
        val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        return Triple(Prayer.FAJR, forDay(tomorrow, lat, lon).getValue(Prayer.FAJR), true)
    }

    /** "المغرب", "صلاة العصر", "maghrib" -> the prayer. */
    fun prayerOf(text: String): Prayer? {
        val t = Commands.normalize(text)
        return when {
            listOf("فجر", "الصبح", "fajr").any { t.contains(it) } -> Prayer.FAJR
            listOf("ظهر", "ضهر", "dhuhr", "zuhr").any { t.contains(it) } -> Prayer.DHUHR
            listOf("عصر", "asr").any { t.contains(it) } -> Prayer.ASR
            listOf("مغرب", "maghrib").any { t.contains(it) } -> Prayer.MAGHRIB
            listOf("عشا", "عشاء", "isha").any { t.contains(it) } -> Prayer.ISHA
            listOf("شروق", "sunrise").any { t.contains(it) } -> Prayer.SUNRISE
            else -> null
        }
    }

    // ---- maths in degrees

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year; var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun rad(d: Double) = d * Math.PI / 180.0
    private fun deg(r: Double) = r * 180.0 / Math.PI
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun darcsin(x: Double) = deg(asin(x))
    private fun darccos(x: Double) = deg(acos(x))
    private fun darctan2(y: Double, x: Double) = deg(atan2(y, x))
    private fun darccot(x: Double) = deg(atan(1 / x))
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixHour(a: Double) = a - 24.0 * floor(a / 24.0)
}
