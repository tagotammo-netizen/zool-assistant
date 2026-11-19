package org.jarvis.assistant

/**
 * The problem report the person can send to the developer (by WhatsApp or e-mail): the phone, the app version,
 * which parts work, the last crash and the latest events. No contacts, messages, keys or places go in it.
 */
object Report {

    data class Input(
        val version: String,
        val phone: String,
        val android: String,
        val checks: List<Pair<String, String>>,
        val stopsToday: Int,
        val crash: String?,
        val events: List<String>,
        val settings: Map<String, String>,
    )

    /** Settings that may hold private things are never put in the report. */
    private val PRIVATE = setOf("ai_key", "stt_key", "emergency_number", "ntfy_topic", "remote_topic", "remote_pin", "home_key",
        "home_base", "prayer_lat", "prayer_lon", "secret", "aac_custom", "kb_words", "memory", "phrases")

    private val NUMBER = Regex("\\+?\\d[\\d\\s-]{6,}\\d")
    private val LINK = Regex("https?://\\S+")

    /** Phone numbers and links (maps, servers) in log lines are hidden. */
    fun clean(line: String) = line.replace(LINK, "[رابط]").replace(NUMBER, "[رقم]")

    fun build(i: Input): String {
        val out = StringBuilder()
        out.append("تقرير زول ${i.version}\n")
        out.append("التلفون: ${i.phone} • أندرويد ${i.android}\n")
        if (i.stopsToday > 0) out.append("وقف فجأة اليوم: ${i.stopsToday} مرة\n")
        out.append("\nالحالة:\n")
        for ((name, state) in i.checks) out.append("• $name: $state\n")
        val shown = i.settings.filterKeys { k -> PRIVATE.none { k.startsWith(it) } }
        if (shown.isNotEmpty()) out.append("\nالإعدادات: ").append(shown.entries.joinToString(", ") { "${it.key}=${it.value}" }).append('\n')
        if (!i.crash.isNullOrBlank()) out.append("\nآخر خطأ:\n").append(i.crash.lines().take(25).joinToString("\n")).append('\n')
        if (i.events.isNotEmpty()) {
            out.append("\nآخر الأحداث:\n")
            for (e in i.events.takeLast(40)) out.append("- ").append(clean(e)).append('\n')
        }
        return out.toString().take(12_000)
    }
}
