package org.jarvis.assistant

/**
 * The Arabic keyboard typed with the head pointer: big keys in alphabetical order, a row of word suggestions,
 * and keys for space, delete, send and close. Holding the ring on a key types it; staying on it types it again.
 * Pure logic (no Android), so it is tested on the computer.
 */
object HeadKeys {

    enum class Kind { CHAR, SPACE, DELETE, DELETE_WORD, ENTER, MODE, CLOSE, SUGGEST }

    data class Key(val label: String, val kind: Kind, val weight: Float = 1f, val value: String = label)

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
        val width get() = right - left
        val height get() = bottom - top
    }

    private fun chars(s: String) = s.split(" ").map { Key(it, Kind.CHAR) }

    val LETTERS = listOf(
        chars("ا ب ت ث ج ح خ د"),
        chars("ذ ر ز س ش ص ض ط"),
        chars("ظ ع غ ف ق ك ل م"),
        chars("ن ه و ي ة ى ء أ"),
        chars("إ آ ؤ ئ ، ؟ . !"),
    )

    val NUMBERS = listOf(
        chars("1 2 3 4 5 6 7 8"),
        chars("9 0 + - × ÷ = %"),
        chars("@ # ( ) : ؛ / _"),
        chars("، ؟ . ! ❤ 👍 😂 🙏"),
    )

    private const val PUNCT = "،؟.!؛:"

    fun controls(numbers: Boolean) = listOf(
        Key(if (numbers) "أبت" else "123", Kind.MODE),
        Key("مسافة", Kind.SPACE, 2.5f, " "),
        Key("⌫", Kind.DELETE),
        Key("⌫ كلمة", Kind.DELETE_WORD, 1.5f),
        Key("إرسال", Kind.ENTER, 1.5f),
        Key("✕", Kind.CLOSE),
    )

    /** Rows from top to bottom: three suggestions, the keys, the controls. */
    fun rows(numbers: Boolean, suggestions: List<String>): List<List<Key>> {
        val sug = (0 until 3).map { i -> suggestions.getOrNull(i).orEmpty().let { Key(it, Kind.SUGGEST, 1f, it) } }
        return listOf(sug) + (if (numbers) NUMBERS else LETTERS) + listOf(controls(numbers))
    }

    /**
     * Where each key is drawn. The keyboard fills [width], starts at [top] below a preview line of [previewHeight],
     * and each row is [rowHeight] tall; keys share a row by weight. Arabic reads right to left, so the first key of
     * a row is on the right.
     */
    fun layout(rows: List<List<Key>>, width: Float, top: Float, rowHeight: Float, previewHeight: Float): List<Pair<Key, Box>> {
        val out = ArrayList<Pair<Key, Box>>()
        var y = top + previewHeight
        for (row in rows) {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var right = width
            for (k in row) {
                val w = width * k.weight / total
                out.add(k to Box(right - w, y, right, y + rowHeight))
                right -= w
            }
            y += rowHeight
        }
        return out
    }

    fun height(rows: Int, rowHeight: Float, previewHeight: Float) = previewHeight + rows * rowHeight

    /** The word being typed (empty right after a space). */
    fun lastWord(text: String) = if (text.isEmpty() || text.last().isWhitespace()) "" else text.substringAfterLast(' ').substringAfterLast('\n')

    /** The finished word before the one being typed. */
    fun previousWord(text: String): String {
        val head = text.substring(0, text.length - lastWord(text).length).trimEnd()
        return head.substringAfterLast(' ').substringAfterLast('\n').trim(*PUNCT.toCharArray())
    }

    /** What the text field holds after pressing [key]. Keys that do not change text return [text] unchanged. */
    fun apply(text: String, key: Key): String = when (key.kind) {
        Kind.CHAR ->
            if (key.value.length == 1 && key.value[0] in PUNCT && text.endsWith(" ")) text.trimEnd() + key.value + " "
            else text + key.value
        Kind.SPACE -> if (text.isEmpty() || text.last().isWhitespace()) text else "$text "
        Kind.DELETE -> if (text.isEmpty()) text else text.substring(0, text.offsetByCodePoints(text.length, -1))
        Kind.DELETE_WORD -> {
            val trimmed = text.trimEnd()
            val cut = maxOf(trimmed.lastIndexOf(' '), trimmed.lastIndexOf('\n'))
            if (cut < 0) "" else trimmed.substring(0, cut + 1)
        }
        Kind.SUGGEST -> if (key.value.isEmpty()) text else text.substring(0, text.length - lastWord(text).length) + key.value + " "
        else -> text
    }
}

/**
 * Typing by holding the ring on a key: [dwellMs] on a key types it; staying there types it again after
 * [repeatMs] (so "لل" is easy), a little faster for delete. Moving to another key starts over.
 */
class KeyDwell(var dwellMs: Long = 900L) {
    private var current = -1
    private var since = -1L
    private var typed = false

    fun reset() { current = -1; since = -1L; typed = false }

    /** [index]: the key under the ring, or -1. Returns the fill of the ring (0..1) and whether the key is typed now. */
    fun update(index: Int, t: Long, fastRepeat: Boolean = false): Pair<Float, Boolean> {
        if (index < 0) { reset(); return 0f to false }
        if (index != current) { current = index; since = t; typed = false }
        val need = if (!typed) dwellMs else if (fastRepeat) maxOf(300L, dwellMs / 2) else dwellMs + 300L
        val held = t - since
        if (held >= need) { since = t; typed = true; return 0f to true }
        return (held.toFloat() / need).coerceIn(0f, 1f) to false
    }
}

/**
 * Word suggestions for the head keyboard: everyday Sudanese and Arabic words, the next word of common phrases
 * ("ان شاء" -> "الله"), and the words this person types most, learned on the phone.
 */
object WordPredict {

    val COMMON: List<String> = ("السلام عليكم ورحمة الله وبركاته وعليكم كيفك كيف حالك الحمد لله تمام بخير شكرا كتير " +
        "ان شاء الله ما شاء الله يا جماعة صباح الخير مساء الخير مسا النور صباح النور تعال وين انت انا هسي بكرة الليلة " +
        "امبارح داير عايز ما عندي ممكن لو سمحت معليش خلاص كويس طيب اوكي اتصل رسل الرسالة البيت الشغل الدكتور الدوا " +
        "الاكل الموية نايم صاحي جيت ماشي جاي مستني اتأخرت قريب بعيد كمان شوية بس كدا زول ناس اهلي امي ابوي اخوي " +
        "اختي ولدي بتي حبيبي الله يسلمك يحفظك ربنا جزاك خير مبروك عيد سعيد رمضان كريم جمعة مباركة نعم لا ليه متين " +
        "منو شنو كم قدر وصلت راجع البحر السوق المستشفى الصلاة المسجد عشان لكن برضو يلا").split(" ").distinct()

    private val NEXT = mapOf(
        "السلام" to listOf("عليكم"), "ان" to listOf("شاء"), "شاء" to listOf("الله"), "الحمد" to listOf("لله"),
        "صباح" to listOf("الخير", "النور"), "مساء" to listOf("الخير", "النور"), "مسا" to listOf("الخير", "النور"),
        "كيف" to listOf("حالك", "الحال"), "جزاك" to listOf("الله"), "الله" to listOf("يسلمك", "يحفظك", "خير"),
        "ما" to listOf("شاء", "عندي"), "رمضان" to listOf("كريم"), "جمعة" to listOf("مباركة"), "شكرا" to listOf("كتير"),
        "لو" to listOf("سمحت"), "ورحمة" to listOf("الله"), "عليكم" to listOf("السلام", "ورحمة"), "عيد" to listOf("سعيد"),
        "ربنا" to listOf("يحفظك", "يسلمك"), "يا" to listOf("جماعة"),
    )

    private val STARTERS = listOf("السلام", "كيفك", "تمام")

    private fun n(s: String) = Commands.normalize(s)

    /** Up to [count] words for the text typed so far. [learned]: word -> times typed, and "a b" -> times b followed a. */
    fun suggest(text: String, learned: Map<String, Int>, count: Int = 3): List<String> {
        val prefix = n(HeadKeys.lastWord(text))
        val prev = HeadKeys.previousWord(text)
        val out = LinkedHashSet<String>()
        if (prefix.isEmpty()) {
            if (prev.isNotEmpty()) {
                val np = n(prev)
                learned.filterKeys { it.startsWith("$np ") }.entries.sortedByDescending { it.value }
                    .forEach { out.add(it.key.substringAfter(' ')) }
                NEXT.entries.firstOrNull { n(it.key) == np }?.value?.let { out.addAll(it) }
            } else {
                learned.filterKeys { ' ' !in it }.entries.sortedByDescending { it.value }.take(count).forEach { out.add(it.key) }
                out.addAll(STARTERS)
            }
        } else {
            if (prev.isNotEmpty()) {
                val np = n(prev)
                learned.filterKeys { it.startsWith("$np ") && n(it.substringAfter(' ')).startsWith(prefix) }
                    .entries.sortedByDescending { it.value }.forEach { out.add(it.key.substringAfter(' ')) }
                NEXT.entries.firstOrNull { n(it.key) == np }?.value?.filter { n(it).startsWith(prefix) }?.let { out.addAll(it) }
            }
            learned.filterKeys { ' ' !in it && n(it).startsWith(prefix) && n(it) != prefix }
                .entries.sortedByDescending { it.value }.forEach { out.add(it.key) }
            COMMON.filter { n(it).startsWith(prefix) && n(it) != prefix }.forEach { out.add(it) }
        }
        return out.filter { it.isNotBlank() }.take(count)
    }

    /** Count the words (and word pairs) of a sent text. Keeps the [limit] most used. */
    fun learn(text: String, learned: MutableMap<String, Int>, limit: Int = 400) {
        val words = text.split(Regex("\\s+")).map { it.trim(*"،؟.!؛:\"'()".toCharArray()) }.filter { it.length >= 2 && !it.any(Char::isDigit) }
        for ((i, w) in words.withIndex()) {
            learned[w] = (learned[w] ?: 0) + 1
            if (i > 0) { val pair = n(words[i - 1]) + " " + w; learned[pair] = (learned[pair] ?: 0) + 1 }
        }
        if (learned.size > limit) {
            val keep = learned.entries.sortedByDescending { it.value }.take(limit).map { it.key }.toSet()
            learned.keys.retainAll(keep)
        }
    }

    /** Count only the word just finished (and the pair it ends), as the person types. */
    fun learnLast(text: String, learned: MutableMap<String, Int>, limit: Int = 400) {
        val words = text.trimEnd().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        val clean = { w: String -> w.trim(*"،؟.!؛:\"'()".toCharArray()) }
        val last = clean(words.last())
        if (last.length < 2 || last.any(Char::isDigit)) return
        learned[last] = (learned[last] ?: 0) + 1
        words.getOrNull(words.size - 2)?.let(clean)?.takeIf { it.length >= 2 }?.let { prev ->
            val pair = n(prev) + " " + last
            learned[pair] = (learned[pair] ?: 0) + 1
        }
        if (learned.size > limit) learn("", learned, limit)
    }

    fun encode(learned: Map<String, Int>) = learned.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(s: String): MutableMap<String, Int> {
        val m = HashMap<String, Int>()
        for (line in s.lines()) {
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) continue
            line.substring(tab + 1).toIntOrNull()?.let { m[line.substring(0, tab)] = it }
        }
        return m
    }
}
