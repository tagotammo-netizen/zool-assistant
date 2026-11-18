package org.jarvis.assistant

/**
 * Turns Zol's own standard-Arabic replies into Sudanese: "لم أجد" → "ما لقيت", "سأذكّرك" → "حأذكّرك",
 * "الآن" → "هسي". Only Zol's wording changes: text inside «» (names, message bodies, what the person said)
 * is left exactly as it was.
 */
object Sudani {

    /** Whole phrases first (longest first), then single words. Keys are matched on word boundaries. */
    private val PHRASES = listOf(
        "لم أفهم" to "ما فهمتك", "لم افهم" to "ما فهمتك", "لم أجد" to "ما لقيت", "لم اجد" to "ما لقيت",
        "لم أستطع" to "ما قدرت", "لم استطع" to "ما قدرت", "لا أستطيع" to "ما بقدر", "لا استطيع" to "ما بقدر",
        "لا يوجد" to "ما في", "لا توجد" to "ما في", "لا أرى" to "ما شايف", "لا ارى" to "ما شايف",
        "لا أعرف" to "ما عارف", "لا اعرف" to "ما عارف", "هل فعلتها؟" to "عملتها؟", "هل فعلتها" to "عملتها",
        "هل تقصد" to "قصدك", "تقصد" to "قصدك", "ماذا تريد" to "داير شنو", "ماذا" to "شنو",
        "لم يستجب" to "ما رد", "ليس" to "ما", "ليست" to "ما", "لم يتم" to "ما اتعمل",
        "حسناً" to "تمام", "حسنا" to "تمام", "ممتاز" to "تمام", "جيد" to "كويس",
        "الآن" to "هسي", "الان" to "هسي", "هذا" to "دا", "هذه" to "دي", "هنا" to "هنا", "هناك" to "هناك",
        "أين" to "وين", "اين" to "وين", "لماذا" to "ليه", "كثيراً" to "شديد", "قليلاً" to "شوية", "قليلا" to "شوية",
        "أريد" to "داير", "اريد" to "داير", "يوجد" to "في", "توجد" to "في",
        "سأذكّرك" to "حأذكّرك", "سأذكرك" to "حأذكّرك", "سأنفذ" to "حأنفّذ", "سأسألك" to "حأسألك", "سأبلغ" to "حأبلّغ",
        "أوقفت" to "وقّفت", "اوقفت" to "وقّفت", "أطفأت" to "طفيت", "اطفات" to "طفيت", "أشعلت" to "ولّعت",
        "اتصلت" to "اتصلت", "أرسلت" to "رسّلت", "ارسلت" to "رسّلت", "أبلغت" to "بلّغت", "ابلغت" to "بلّغت",
        "انتظر" to "استنى", "أعد" to "عيد", "قل" to "قول", "حاول" to "جرّب",
    )

    private val ORDERED = PHRASES.sortedByDescending { it.first.length }

    private val LETTER = Regex("[\\p{L}\\p{M}]")

    /** Sudanese version of a reply. English text and anything inside «» are returned unchanged. */
    fun convert(text: String): String {
        if (!Commands.isArabic(text)) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('«', i)
            if (open < 0) { out.append(swap(text.substring(i))); break }
            out.append(swap(text.substring(i, open)))
            val close = text.indexOf('»', open)
            if (close < 0) { out.append(text.substring(open)); break }
            out.append(text, open, close + 1)
            i = close + 1
        }
        return out.toString()
    }

    private fun swap(part: String): String {
        var s = part
        for ((from, to) in ORDERED) {
            if (from == to || !s.contains(from)) continue
            val sb = StringBuilder()
            var at = 0
            while (true) {
                val k = s.indexOf(from, at)
                if (k < 0) { sb.append(s.substring(at)); break }
                val before = if (k > 0) s[k - 1].toString() else ""
                val after = if (k + from.length < s.length) s[k + from.length].toString() else ""
                val whole = (before.isEmpty() || !LETTER.matches(before)) && (after.isEmpty() || !LETTER.matches(after))
                sb.append(s, at, k).append(if (whole) to else from)
                at = k + from.length
            }
            s = sb.toString()
        }
        // "سأفعل" → "حأفعل": the future "س" on a first-person verb
        s = Regex("(^|[\\s،.؟!])س(أ[\\p{L}]{2,})").replace(s) { m -> m.groupValues[1] + "ح" + m.groupValues[2] }
        return s
    }
}
