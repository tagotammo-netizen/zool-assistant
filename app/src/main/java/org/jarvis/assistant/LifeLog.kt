package org.jarvis.assistant

/**
 * ذاكرة حياتك: the messages that came (who, what, when) and voice messages written out, kept on the phone for a
 * month, so the person can ask "أحمد قال لي شنو امبارح؟". Nothing leaves the phone except, when asked, the few
 * lines that answer the question (to the smart server, to say the answer). Kept apart so it can be tested.
 */
object LifeLog {

    data class Entry(val time: Long, val app: String, val who: String, val text: String)

    private const val MONTH = 30L * 24 * 3600 * 1000

    fun encode(e: Entry) = listOf(e.time.toString(), e.app, e.who, e.text).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    fun decode(raw: String): List<Entry> = raw.lines().mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 4) null else p[0].toLongOrNull()?.let { Entry(it, p[1], p[2], p[3]) }
    }

    /** Older than a month, or beyond the last 3000 lines, is forgotten. */
    fun trim(list: List<Entry>, now: Long) = list.filter { now - it.time < MONTH }.takeLast(3000)

    private val STOP = setOf("قال", "قالت", "قالو", "قالوا", "لي", "شنو", "متين", "وين", "رسل", "رسلت", "كتب", "كتبت", "عن", "في",
        "امبارح", "اليوم", "الليله", "النهارده", "الصباح", "قبل", "يوم", "الرسايل", "رسايل", "رساله", "اقرا", "زول", "انا", "ليك",
        "what", "did", "say", "said", "me", "when", "about", "the", "yesterday", "today")

    /**
     * The lines that answer the question: the person named (a sender whose name is in it), the day
     * (امبارح, اليوم...), and the other words; the newest first, at most [max].
     */
    fun search(list: List<Entry>, question: String, now: Long, max: Int = 12): List<Entry> {
        val q = Commands.normalize(question)
        val words = q.split(" ").filter { it.length >= 2 }
        val day = 24L * 3600 * 1000
        val todayStart = now - Math.floorMod(now + java.util.TimeZone.getDefault().getOffset(now), day)
        val range: LongRange? = when {
            words.any { it in setOf("امبارح", "yesterday", "امس") } -> (todayStart - day) until todayStart
            words.any { it in setOf("اليوم", "النهارده", "الليله", "today", "الصباح") } -> todayStart until (todayStart + day)
            else -> null
        }
        val people = list.map { it.who }.distinct().filter { who ->
            val n = Commands.normalize(who)
            n.split(" ").any { part -> part.length >= 3 && words.any { w -> w == part || w.removePrefix("ل") == part || w.removePrefix("ب") == part } }
        }.toSet()
        val topic = words.filter { it !in STOP && people.none { p -> Commands.normalize(p).contains(it) } && it.length >= 3 }
        return list.asReversed().asSequence()
            .filter { range == null || it.time in range }
            .filter { people.isEmpty() || it.who in people }
            .map { e -> e to topic.count { Commands.normalize(e.text).contains(it) } }
            .filter { (_, s) -> topic.isEmpty() || s > 0 || people.isNotEmpty() }
            .sortedByDescending { (e, s) -> s * 1e13 + e.time }
            .map { it.first }
            .take(max)
            .toList()
    }

    /** A question about what was said or sent ("أحمد قال لي شنو امبارح؟", "متين قالو لي العزومة؟"). */
    fun isQuestion(text: String): Boolean {
        val w = Commands.normalize(text).split(" ")
        val verb = w.any { it in setOf("قال", "قالت", "قالو", "قالوا", "رسل", "رسلت", "كتب", "كتبت", "said", "say", "sent") }
        val ask = w.any { it in setOf("شنو", "متين", "وين", "كم", "what", "when", "where") }
        return verb && ask
    }

    /** A message that may hold a plan with a time ("بكرة الساعة 5 عندنا عزومة"): only these go to the smart server. */
    fun looksLikePlan(text: String): Boolean {
        val n = Commands.normalize(text)
        val time = Regex("(الساعه|ساعه|بكره|بكرة|الليله|الصباح|العصر|المغرب|العشاء|الجمعه|السبت|الاحد|الاتنين|التلات|الاربع|الخميس|" +
            "tomorrow|tonight|at \\d|\\d{1,2}[:.]\\d{2}|\\d{1,2} ?(pm|am))").containsMatchIn(n)
        val plan = Regex("(موعد|ميعاد|عزومه|اجتماع|مناسبه|عرس|سمايه|زياره|تعال|نتلاقي|نتقابل|نجي|جاين|حنجي|حاجي|دكتور|مستشفي|" +
            "meeting|appointment|party|dinner|visit|come)").containsMatchIn(n)
        return time && plan && n.length in 8..400
    }

    fun format(e: Entry, now: Long): String {
        val mins = (now - e.time) / 60_000
        val ago = when {
            mins < 60 -> "قبل $mins دقيقة"
            mins < 24 * 60 -> "قبل ${mins / 60} ساعة"
            else -> "قبل ${mins / (24 * 60)} يوم"
        }
        return "${e.who} ($ago): ${e.text}"
    }
}
