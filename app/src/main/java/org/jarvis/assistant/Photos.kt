package org.jarvis.assistant

/**
 * Finding photos by what is in them ("وريني صور العيد"): each photo is described once by the model that can see,
 * the descriptions are kept on the phone, and a search compares words. Kept apart so it can be tested.
 */
object Photos {

    fun encode(m: Map<Long, String>) = m.entries.joinToString("\n") { "${it.key}\t${it.value.replace('\n', ' ').replace('\t', ' ')}" }

    fun decode(raw: String): LinkedHashMap<Long, String> {
        val out = LinkedHashMap<Long, String>()
        raw.lines().forEach { l -> val i = l.indexOf('\t'); if (i > 0) l.substring(0, i).toLongOrNull()?.let { out[it] = l.substring(i + 1) } }
        return out
    }

    private val FILLER = setOf("صور", "صوره", "الصور", "الصوره", "وريني", "ورني", "لقي", "لي", "في", "فيها", "عن", "وين", "فتش", "show",
        "me", "photos", "photo", "of", "the", "pictures", "find", "my", "حق", "بتاع", "بتاعت", "زول")

    /** The words of the question that matter ("وريني صور العيد" → العيد). */
    fun terms(query: String) = Commands.normalize(query).split(" ").filter { it.length >= 2 && it !in FILLER }
        .map { it.removePrefix("ال").ifEmpty { it } }

    /** How many of the words a description has (a word, or the word with "ال"). */
    fun score(caption: String, terms: List<String>): Int {
        val c = Commands.normalize(caption)
        return terms.count { t -> c.contains(t) }
    }

    /** The model's answer for a few photos: one line per photo, "1: …", "2: …". */
    fun parseCaptions(raw: String, count: Int): List<String?> {
        val out = arrayOfNulls<String>(count)
        for (line in raw.substringAfterLast("</think>").lines()) {
            val m = Regex("^\\s*(\\d+)\\s*[:.)\\-]\\s*(.+)$").find(line.trim()) ?: continue
            val i = m.groupValues[1].toInt() - 1
            if (i in 0 until count) out[i] = m.groupValues[2].trim().take(300)
        }
        return out.toList()
    }

    const val PROMPT = "Describe each photo in one line, numbered like \"1: ...\". In each line write the main things, people " +
        "(man, woman, child, group), place (home, garden, street, mosque, wedding hall...), occasion (Eid, wedding, birthday, " +
        "graduation...), and any writing — first in Arabic, then the same keywords in English."
}
