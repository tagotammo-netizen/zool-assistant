package org.jarvis.assistant

/**
 * Zol learns the person's habits: a command said on three or more different days at about the same time of day
 * (within 40 minutes) is offered when that time comes again ("الساعة 9، عادةً بتتصل بأمك. أتصل؟").
 * Everything stays on the phone. Kept apart from the service so it can be tested.
 */
object Habits {

    /** A command said at [minute] (minutes since 1970, local time). */
    data class Entry(val text: String, val minute: Long)

    data class Habit(val text: String, val minuteOfDay: Int, val days: Int)

    private const val DAY = 24 * 60

    fun encode(list: List<Entry>) = list.joinToString("\n") { "${it.minute}|${it.text.replace('\n', ' ').replace('|', ' ')}" }

    fun decode(raw: String): List<Entry> = raw.lines().mapNotNull { l ->
        val i = l.indexOf('|')
        if (i <= 0) null else l.substring(0, i).toLongOrNull()?.let { Entry(l.substring(i + 1), it) }
    }

    /** Keep the last two weeks, 400 commands at most. */
    fun add(list: List<Entry>, e: Entry): List<Entry> =
        (list + e).filter { e.minute - it.minute <= 14L * DAY }.takeLast(400)

    /** Circular distance between two times of day, in minutes. */
    private fun apart(a: Int, b: Int): Int { val d = Math.abs(a - b) % DAY; return minOf(d, DAY - d) }

    /** The habits: same words, at about the same time, on 3+ different days of the last two weeks. */
    fun habits(list: List<Entry>, now: Long): List<Habit> {
        val out = ArrayList<Habit>()
        for ((key, group) in list.filter { now - it.minute <= 14L * DAY }.groupBy { Commands.normalize(it.text).trim() }) {
            if (key.isEmpty()) continue
            val times = group.map { (it.minute % DAY).toInt() }
            // the time with the most days around it
            var best: Habit? = null
            for (center in times) {
                val days = group.filter { apart((it.minute % DAY).toInt(), center) <= 40 }.map { it.minute / DAY }.toSet().size
                if (days >= 3 && (best == null || days > best.days)) {
                    val near = times.filter { apart(it, center) <= 40 }
                    best = Habit(group.last().text, near.sorted()[near.size / 2], days)
                }
            }
            best?.let { out.add(it) }
        }
        return out
    }

    /**
     * The habit to offer now: its time is in the next 15 minutes or just passed (10), it was not done
     * today yet, and it was not offered today or turned down 3 times ([offered]: text → day offered,
     * [refused]: text → times said no).
     */
    fun due(list: List<Entry>, now: Long, offered: Map<String, Long>, refused: Map<String, Int>): Habit? {
        val today = now / DAY
        val nowMin = (now % DAY).toInt()
        return habits(list, now).firstOrNull { h ->
            val key = Commands.normalize(h.text).trim()
            val until = ((h.minuteOfDay - nowMin) % DAY + DAY) % DAY       // minutes until its time
            val soon = until <= 15 || DAY - until <= 10
            val doneToday = list.any { it.minute / DAY == today && Commands.normalize(it.text).trim() == key }
            soon && !doneToday && offered[key] != today && (refused[key] ?: 0) < 3
        }
    }

    /** Commands worth learning as habits: calling, messaging, opening, playing; not questions or one-offs. */
    fun worth(c: Cmd): Boolean = c is Cmd.Call || c is Cmd.OpenApp || c is Cmd.Message || c is Cmd.PlayVideo ||
        c is Cmd.RunMacro || c is Cmd.Sleep || c is Cmd.Daily || c is Cmd.Tasbeeh || c is Cmd.Quran
}
