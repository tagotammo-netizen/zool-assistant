package org.jarvis.assistant

/**
 * Dialect packs: the words and sentences people taught Zol (الكلمات: a word heard → the word meant;
 * العبارات: a sentence → the command it means), shared as plain text over WhatsApp, and the community packs
 * kept in Zol's repository, which anyone can add to.
 */
object Dialects {

    const val HEADER = "# زول: حزمة لهجة"
    const val BASE = "https://raw.githubusercontent.com/tagotammo-netizen/zool-assistant/main/dialects/"
    const val CONTRIBUTE = "https://github.com/tagotammo-netizen/zool-assistant/issues/new"

    data class Pack(val name: String, val words: Map<String, String>, val phrases: Map<String, String>)

    /** One line in the community list: "id|name|what it is". */
    data class Entry(val id: String, val name: String, val about: String)

    fun format(name: String, words: Map<String, String>, phrases: Map<String, String>): String = buildString {
        appendLine(HEADER)
        appendLine("# الاسم: $name")
        appendLine("[كلمات]")
        words.forEach { (k, v) -> appendLine("$k=$v") }
        appendLine("[عبارات]")
        phrases.forEach { (k, v) -> appendLine("$k=$v") }
    }

    fun isPack(text: String) = text.trimStart().startsWith(HEADER)

    fun parse(text: String): Pack? {
        if (!isPack(text)) return null
        var name = ""
        var section = ""
        val words = LinkedHashMap<String, String>()
        val phrases = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("# الاسم:") -> name = line.substringAfter(":").trim()
                line.startsWith("#") || line.isEmpty() -> {}
                line == "[كلمات]" -> section = "w"
                line == "[عبارات]" -> section = "p"
                '=' in line -> {
                    val k = line.substringBefore('=').trim()
                    val v = line.substringAfter('=').trim()
                    if (k.isEmpty() || v.isEmpty() || k.length > 80 || v.length > 200) continue
                    if (section == "w") words[k] = v else if (section == "p") phrases[k] = v
                }
            }
        }
        return if (words.isEmpty() && phrases.isEmpty()) null else Pack(name.ifEmpty { "لهجة" }, words, phrases)
    }

    /** A pack added to what the person has; their own words win, nothing of theirs is replaced. */
    fun merge(mine: String, theirs: Map<String, String>, key: (String) -> String): Pair<String, Int> {
        val have = mine.lines().filter { '=' in it }.map { key(it.substringBefore('=')) }.toSet()
        val add = theirs.filter { key(it.key) !in have }
        if (add.isEmpty()) return mine to 0
        val joined = (mine.trimEnd().lines().filter { it.isNotBlank() } + add.map { "${it.key}=${it.value}" }).joinToString("\n")
        return joined to add.size
    }

    fun parseIndex(text: String): List<Entry> = text.lines().mapNotNull { l ->
        val p = l.split('|')
        if (p.size < 2 || l.trimStart().startsWith("#")) null
        else Entry(p[0].trim(), p[1].trim(), p.getOrNull(2)?.trim().orEmpty()).takeIf { it.id.matches(Regex("[a-z0-9-]{2,40}")) }
    }

    /** "دارفور" finds the pack named "لهجة دارفور". */
    fun find(list: List<Entry>, said: String): Entry? {
        val s = Commands.normalize(said).removePrefix("لهجه ").trim()
        if (s.isEmpty()) return null
        return list.firstOrNull { Commands.normalize(it.name).contains(s) || it.id == said.trim() }
    }
}
