package org.jarvis.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * The proactive assistant: Zol speaks first. Daily reminders that wait for "نعم", messages from the people
 * who matter read at once, battery and bedtime warnings, a short morning briefing.
 * The pure parts (parsing and times) are in [ProactivePlan] so they can be tested without a phone.
 */
object ProactivePlan {

    data class Reminder(val hour: Int, val minute: Int, val text: String)

    /** "08:00=الدواء" or "8:30 مساء=الدواء", one per line. Bad lines are skipped. */
    fun parseReminders(text: String): List<Reminder> = text.lines().mapNotNull { line ->
        val at = line.indexOf('=')
        if (at < 1) return@mapNotNull null
        val time = parseTime(line.substring(0, at)) ?: return@mapNotNull null
        val what = line.substring(at + 1).trim()
        if (what.isEmpty()) null else Reminder(time.first, time.second, what)
    }

    /** "8", "08:30", "8.30", "8:30 مساء", "20:15" -> (hour, minute). */
    fun parseTime(s: String): Pair<Int, Int>? {
        val n = Commands.normalize(s)
        val digits = Regex("(\\d{1,2})(?:\\s*[:.\\s]\\s*(\\d{1,2}))?").find(n) ?: return null
        var h = digits.groupValues[1].toInt()
        val m = digits.groupValues[2].ifEmpty { "0" }.toInt()
        val evening = listOf("مساء", "المساء", "بالليل", "الليل", "العصر", "pm").any { n.contains(it) }
        if (evening && h in 1..11) h += 12
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    fun format(r: Reminder) = String.format(java.util.Locale.US, "%02d:%02d=%s", r.hour, r.minute, r.text)

    /** Milliseconds of the next time [hour]:[minute] after [now] (today if still ahead, else tomorrow). */
    fun nextAt(now: Long, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now + 1000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Is this notification title one of the people the person marked as important? */
    fun isImportant(title: String, people: List<String>): Boolean {
        val t = Commands.normalize(title).replace(" ", "")
        if (t.isEmpty()) return false
        return people.map { Commands.normalize(it).replace(" ", "") }.any { it.length >= 2 && (t.contains(it) || it.contains(t)) }
    }

    /** Battery levels worth a word when not charging. */
    val BATTERY_STEPS = listOf(20, 10, 5)

    /** The warning to give now, or null. [warned] = the lowest level already announced since the last charge. */
    fun batteryWarning(pct: Int, plugged: Boolean, warned: Int): Int? {
        if (plugged) return null
        return BATTERY_STEPS.filter { pct <= it && it < warned }.minOrNull()
    }
}

/** Woken by AlarmManager at a reminder or bedtime; hands over to the service. */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 1)
        val service = JarvisService.instance
        if (service != null) service.proactiveFire(kind, text, attempt)
        else schedule(context)          // Zol is off: keep the plan for when he is back
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ATTEMPT = "attempt"
        private const val CODE_NEXT = 7001
        private const val CODE_AGAIN = 7002
        private const val CODE_PRAYER = 7003

        /** Where prayer times are worked out: the city chosen in settings, the phone's saved location, or Khartoum. */
        fun prayerPlace(context: Context): PrayerTimes.City {
            val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            val lat = prefs.getFloat("prayer_lat", Float.NaN)
            val lon = prefs.getFloat("prayer_lon", Float.NaN)
            val name = prefs.getString("prayer_city", "").orEmpty()
            if (!lat.isNaN() && !lon.isNaN()) return PrayerTimes.City(name.ifEmpty { "موقعك" }, lat.toDouble(), lon.toDouble())
            return PrayerTimes.city(name) ?: PrayerTimes.CITIES.first()
        }

        /** Set the alarm for the next adhan (or a few minutes before it), when prayer alerts are on. */
        fun schedulePrayer(context: Context) {
            val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(intent(context, "prayer", "", 1, CODE_PRAYER))
            if (!prefs.getBoolean("prayer_alert", false)) return
            val before = prefs.getInt("prayer_before", 0)
            val place = prayerPlace(context)
            val now = Calendar.getInstance()
            for (day in 0..1) {
                val cal = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, day) }
                val times = PrayerTimes.forDay(cal, place.lat, place.lon)
                for (p in PrayerTimes.FIVE) {
                    val m = times.getValue(p) - before
                    val at = (cal.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        add(Calendar.MINUTE, m)
                    }.timeInMillis
                    if (at > now.timeInMillis + 30_000) {
                        setAlarm(context, at, intent(context, "prayer", p.name, before, CODE_PRAYER))
                        return
                    }
                }
            }
        }

        private fun intent(context: Context, kind: String, text: String, attempt: Int, code: Int): PendingIntent =
            PendingIntent.getBroadcast(context, code,
                Intent(context, ProactiveReceiver::class.java)
                    .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_ATTEMPT, attempt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun setAlarm(context: Context, at: Long, pi: PendingIntent) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)     // may come a few minutes late
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        /** Set the alarm for the earliest coming reminder or bedtime. Call after any change and after each firing. */
        fun schedule(context: Context) {
            schedulePrayer(context)
            val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi0 = intent(context, "", "", 1, CODE_NEXT)
            am.cancel(pi0)
            if (!prefs.getBoolean("pro_on", false)) return
            val now = System.currentTimeMillis()
            var best: Triple<Long, String, String>? = null
            for (r in ProactivePlan.parseReminders(prefs.getString("pro_reminders", "").orEmpty())) {
                val at = ProactivePlan.nextAt(now, r.hour, r.minute)
                if (best == null || at < best.first) best = Triple(at, "remind", r.text)
            }
            val bed = ProactivePlan.parseTime(prefs.getString("pro_bedtime", "").orEmpty().ifBlank { "-" })
            if (bed != null) {
                val at = ProactivePlan.nextAt(now, bed.first, bed.second)
                if (best == null || at < best.first) best = Triple(at, "bedtime", "")
            }
            val next = best ?: return
            setAlarm(context, next.first, intent(context, next.second, next.third, 1, CODE_NEXT))
        }

        fun cancelAgain(context: Context) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intent(context, "again", "", 1, CODE_AGAIN))
        }

        /** Ask again about the same reminder in [minutes]. */
        fun again(context: Context, text: String, attempt: Int, minutes: Int) {
            setAlarm(context, System.currentTimeMillis() + minutes * 60_000L, intent(context, "again", text, attempt, CODE_AGAIN))
        }
    }
}
