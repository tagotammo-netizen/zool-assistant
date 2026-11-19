package org.jarvis.assistant

/**
 * A Sudanese voice for Zol. The phone's speech engine only speaks standard Arabic, so:
 * the person (or someone in the family) can record Zol's everyday replies in their own voice, and those recordings
 * are played instead of the engine; and a small pronunciation list helps the engine say Sudanese words right.
 */
object VoicePack {

    /** Replies Zol says most, as written in the code; shown to the person in the reply style they chose. */
    val DEFAULTS = listOf(
        "نعم، تفضل.", "حاضر.", "لحظة.", "جاري الاتصال.", "أرسلت.", "أرسلت الرسالة.", "مسحت.",
        "لم أفهم. قل: مساعدة.", "لم أستطع.", "انتهت القراءة.", "قل كلمة السر.", "وعليكم السلام، أهلاً بك.",
        "الحمد لله على السلامة.", "رسلتها للمرافق.",
    )

    /** Two replies are the same recording when their words are the same (punctuation and diacritics aside). */
    fun key(text: String): String = Commands.normalize(text).replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex(" +"), " ").trim()

    /** A file name that is safe on every phone and the same for the same reply. */
    fun fileName(text: String): String {
        val k = key(text)
        var h = 1125899906842597L
        for (c in k) h = 31 * h + c.code
        return "r" + java.lang.Long.toHexString(h) + ".m4a"
    }

    /** Built-in hints so the engine reads Sudanese words the Sudanese way (diacritics). */
    val PRONOUNCE = mapOf(
        "زول" to "زُول", "شنو" to "شِنُو", "منو" to "مِنُو", "هسي" to "هَسِّي", "كدا" to "كِدَا", "داير" to "دَايِر",
        "عايز" to "عَايِز", "ياخ" to "يَاخ", "قدامك" to "قِدَّامَك", "وراك" to "وَرَاك", "شوية" to "شُوَيَّة", "تمام" to "تَمَام",
        "حأذكّرك" to "حَأذَكِّرَك", "ليك" to "لَيك", "ليهو" to "لَيهُو", "بتاعك" to "بِتَاعَك", "متين" to "مِتِين",
    )

    /** "كلمة=نطقها" lines from settings, on top of the built-in ones. */
    fun parseFixes(lines: String): Map<String, String> = lines.lines().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i).trim().takeIf { it.isNotEmpty() }?.let { it to line.substring(i + 1).trim() }
    }.filter { it.second.isNotEmpty() }.toMap()

    /** Swap whole words only; text inside «» (names, messages) is left as it is. */
    fun pronounce(text: String, fixes: Map<String, String>): String {
        if (fixes.isEmpty()) return text
        val out = StringBuilder()
        var quoted = false
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            val w = word.toString()
            out.append(if (quoted) w else fixes[w] ?: w)
            word.clear()
        }
        for (c in text) {
            if (c.isLetter() || c == 'ّ' || c in 'ً'..'ْ') { word.append(c); continue }
            flush()
            if (c == '«') quoted = true
            if (c == '»') quoted = false
            out.append(c)
        }
        flush()
        return out.toString()
    }
}

/** Is there a newer Zol on GitHub? Pure parsing and comparing; the app does the downloading. */
object Updates {

    const val LATEST = "https://api.github.com/repos/tagotammo-netizen/zool-assistant/releases/latest"

    data class Release(val version: String, val apk: String, val notes: String, val page: String)

    /** "v1.10" > "1.9.3" > "1.9" ; non-numbers count as 0. */
    fun newer(remote: String, local: String): Boolean {
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.')
        .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /**
     * The release from GitHub's answer (read without a JSON library, so it is tested on the computer), with its APK:
     * a signed one before a debug one. Null for drafts, pre-releases and releases without an APK.
     */
    fun parse(json: String): Release? {
        val tag = str(json, "tag_name") ?: return null
        if (Regex("\"(draft|prerelease)\"\\s*:\\s*true").containsMatchIn(json)) return null
        val apks = Regex("\"name\"\\s*:\\s*\"([^\"]+\\.apk)\"[\\s\\S]*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(json).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val pick = apks.firstOrNull { "debug" !in it.first } ?: apks.firstOrNull() ?: return null
        return Release(tag.removePrefix("v"), pick.second, (str(json, "body") ?: "").take(600), str(json, "html_url") ?: "")
    }

    private fun str(json: String, key: String): String? =
        Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)?.let { unescape(it) }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> {}
                    'u' -> if (i + 5 < s.length) { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(e)
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
