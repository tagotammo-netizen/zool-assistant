package org.jarvis.assistant

/**
 * Times the way people say them in Sudan, both ways:
 * understanding "سبعة إلا ربع", "ستة وتلت بالليل", "بعد المغرب", "بكرة الصباح بدري",
 * and saying "الساعة سبعة وربع الصباح" instead of "7:15".
 */
object SpokenTime {

    /** Hour words, Sudanese and standard spellings (already normalised: ة→ه, أ→ا). */
    private val HOURS = mapOf(
        "واحده" to 1, "واحد" to 1, "اتنين" to 2, "اثنين" to 2, "تنين" to 2, "تلاته" to 3, "ثلاثه" to 3, "تلات" to 3,
        "اربعه" to 4, "اربع" to 4, "خمسه" to 5, "خمس" to 5, "سته" to 6, "ست" to 6, "سبعه" to 7, "سبع" to 7,
        "تمانيه" to 8, "ثمانيه" to 8, "تمنيه" to 8, "تسعه" to 9, "تسع" to 9, "عشره" to 10, "عشر" to 10,
        "حداشر" to 11, "احداشر" to 11, "احدعشر" to 11, "اتناشر" to 12, "اثناعشر" to 12, "طناشر" to 12,
        "تلتاشر" to 13, "اربعتاشر" to 14, "خمستاشر" to 15, "ستاشر" to 16, "سبعتاشر" to 17, "تمنتاشر" to 18,
        "تسعتاشر" to 19, "عشرين" to 20,
    )

    /** "و…" after the hour. */
    private val PAST = mapOf("وخمسه" to 5, "وعشره" to 10, "وربع" to 15, "وتلت" to 20, "وثلث" to 20, "ونص" to 30, "ونصف" to 30)
    /** "إلا …" before the next hour. */
    private val TO = mapOf("خمسه" to 5, "عشره" to 10, "ربع" to 15, "تلت" to 20, "ثلث" to 20)

    private val MORNING = listOf("الصباح", "الصبح", "صباحا", "صباح", "بدري", "الفجر", "am")
    private val NOON = listOf("الضهر", "الظهر", "ضهرا", "ظهرا")
    private val AFTERNOON = listOf("العصر", "عصرا", "العصريه")
    private val EVENING = listOf("المغرب", "بالليل", "الليل", "المسا", "مساء", "المساء", "العشا", "العشيه", "pm")

    /** A number said as a word or digits: "7", "٧", "سبعة". */
    fun hourWord(w: String): Int? {
        val n = Commands.normalize(w)
        if (n.isNotEmpty() && n.length <= 2 && n.all { it.isDigit() }) return n.toInt()
        return HOURS[n]
    }

    /**
     * The clock time in a sentence, or null when there is none.
     * [prayer] gives the minutes of the day of a prayer ("بعد المغرب"), when the place is known.
     */
    fun clock(text: String, prayer: ((PrayerTimes.Prayer) -> Int?)? = null): Pair<Int, Int>? {
        val t = Commands.normalize(text)
        val w = t.split(" ").filter { it.isNotEmpty() }

        // "بعد المغرب", "قبل الفجر", "مع الضهر", "وقت العصر": tied to a prayer
        if (prayer != null && w.none { hourWord(it) != null }) {
            val p = PrayerTimes.prayerOf(t)
            if (p != null) {
                val at = prayer(p)
                if (at != null) {
                    val m = when {
                        t.contains("بعد") -> at + 15
                        t.contains("قبل") -> at - 15
                        else -> at
                    }.mod(24 * 60)
                    return m / 60 to m % 60
                }
            }
        }
        if (t.contains("نص الليل") || t.contains("منتصف الليل")) return 0 to 0

        // the hour: digits "7:30" / "7 30", or a word
        Regex("(\\d{1,2})[:.](\\d{2})").find(t)?.let { m ->
            return period(m.groupValues[1].toInt(), m.groupValues[2].toInt(), t)
        }
        val at = w.indexOfFirst { hourWord(it) != null }
        if (at < 0) return null
        var hour = hourWord(w[at])!!
        var minute = 0
        val next = w.getOrNull(at + 1)
        val after = w.getOrNull(at + 2)
        when {
            // "سبعه الا ربع", "7 الا 10"
            next == "الا" && after != null -> {
                val m = TO[after] ?: hourWord(after)?.takeIf { it in 1..29 }
                if (m != null) { hour -= 1; minute = 60 - m; if (hour == 0) hour = 12 }
            }
            next != null && PAST.containsKey(next) -> {
                minute = PAST.getValue(next)
                // "سبعه ونص الا خمسه" = 7:25, "ونص وخمسه" = 7:35
                if (minute == 30 && after == "الا" && w.getOrNull(at + 3) == "خمسه") minute = 25
                if (minute == 30 && after == "وخمسه") minute = 35
            }
            // "7 30", "سبعه و20"
            next != null -> {
                val m = next.removePrefix("و").let { if (it.all { c -> c.isDigit() }) it.toIntOrNull() else hourWord(it) }
                if (m != null && m in 0..59 && (next.startsWith("و") || next.all { it.isDigit() })) minute = m
            }
        }
        return period(hour, minute, t)
    }

    /** Morning / noon / evening words turn 1–12 into 0–23. Without them the hour is left as said. */
    private fun period(h: Int, m: Int, t: String): Pair<Int, Int>? {
        var hour = h
        when {
            MORNING.any { t.contains(it) } -> if (hour == 12) hour = 0
            NOON.any { t.contains(it) } -> if (hour in 1..4) hour += 12
            AFTERNOON.any { t.contains(it) } || EVENING.any { t.contains(it) } -> if (hour in 1..11) hour += 12
        }
        return if (hour in 0..23 && m in 0..59) hour to m else null
    }

    /** Minutes from now in "بعد ساعة ونص", "بعد تلت ساعة", "بعد 10 دقايق". Null when there is no such span. */
    fun span(text: String): Int? {
        val t = Commands.normalize(text)
        if (!t.contains("بعد") && !t.startsWith("in ")) return null
        val hours = when {
            t.contains("ساعتين ونص") -> return 150
            t.contains("ساعتين") -> 2
            t.contains("ساعه ونص") -> return 90
            t.contains("ساعه وربع") -> return 75
            t.contains("نص ساعه") || t.contains("نصف ساعه") -> return 30
            t.contains("تلت ساعه") || t.contains("ثلث ساعه") -> return 20
            t.contains("ربع ساعه") -> return 15
            t.contains("دقيقتين") -> return 2
            t.contains("ساعه") || t.contains("ساعات") -> {
                val n = t.split(" ").mapNotNull { hourWord(it) }.firstOrNull() ?: 1
                n
            }
            else -> null
        }
        if (hours != null) return hours * 60
        if (listOf("دقيقه", "دقايق", "دقائق", "minute").any { t.contains(it) }) {
            val n = t.split(" ").mapNotNull { w -> w.toIntOrNull() ?: hourWord(w) }.firstOrNull() ?: return null
            return n
        }
        return null
    }

    private val SAY_HOURS = arrayOf("اتناشر", "واحدة", "اتنين", "تلاتة", "أربعة", "خمسة", "ستة", "سبعة", "تمانية", "تسعة", "عشرة", "حداشر")

    /** "الساعة سبعة وربع الصباح". [dayPart] adds الصباح / الضهر / العصر / المغرب / بالليل. */
    fun say(hour: Int, minute: Int, dayPart: Boolean = true): String {
        val rounded = if (minute % 5 == 0) minute else -1
        var h = hour
        val words = when (rounded) {
            0 -> ""
            5 -> " وخمسة"
            10 -> " وعشرة"
            15 -> " وربع"
            20 -> " وتلت"
            25 -> " ونص إلا خمسة"
            30 -> " ونص"
            35 -> " ونص وخمسة"
            40 -> { h += 1; " إلا تلت" }
            45 -> { h += 1; " إلا ربع" }
            50 -> { h += 1; " إلا عشرة" }
            55 -> { h += 1; " إلا خمسة" }
            else -> " و$minute دقيقة"
        }
        h = h.mod(24)
        val part = if (!dayPart) "" else " " + when (h) {
            in 0..3 -> "بالليل"
            in 4..5 -> "الفجر"
            in 6..11 -> "الصباح"
            in 12..14 -> "الضهر"
            in 15..17 -> "العصر"
            18 -> "المغرب"
            else -> "بالليل"
        }
        return "${SAY_HOURS[h % 12]}$words$part"
    }
}
