package org.jarvis.assistant

/**
 * The "agent" brain: the language model sees a numbered list of what is on the screen and answers with ONE next step.
 * Kept free of Android classes so it can be tested on the computer.
 */
object Agent {

    data class Item(val n: Int, val kind: String, val label: String)
    data class Step(val action: String, val n: Int = 0, val text: String = "", val direction: String = "down",
                    val x: Int = -1, val y: Int = -1)
    data class Reply(val command: String, val reply: String, val agent: String)

    const val MAX_STEPS = 10

    val SYSTEM = """
        You operate an Android phone for a person who cannot use their hands. You work step by step towards the user's goal.
        Each turn you get the foreground app, a numbered list of screen elements (button / edit / text) and what you already did.
        Answer with ONE JSON object and nothing else, using exactly one of these actions:
        {"action":"tap","n":<number>}                  press an element
        {"action":"type","n":<number>,"text":"..."}   write text into an edit element
        {"action":"scroll","direction":"down|up"}
        {"action":"open","text":"<app name as the user would say it>"}
        {"action":"back"} | {"action":"home"} | {"action":"wait"}
        {"action":"say","text":"..."}                 tell the user something and stop (also use it to ask a question)
        {"action":"done","text":"<short summary of what was done>"}
        Rules: never invent element numbers; if the goal is finished answer done; if you are stuck answer say.
        Speak to the user in their own language and dialect (Arabic or English), briefly.
    """.trimIndent()

    private val RISKY = listOf("send", "ارسال", "إرسال", "delete", "حذف", "remove", "pay", "دفع", "buy", "شراء", "order", "تاكيد الطلب",
        "تأكيد الطلب", "call", "اتصال", "transfer", "تحويل", "post", "نشر", "confirm", "تأكيد", "submit", "اشتر")

    /** A button the person should confirm out loud before it is pressed. */
    fun isRisky(label: String): Boolean {
        val l = label.lowercase()
        return RISKY.any { l == it || l.contains(it) }
    }

    /** What the model reads each turn. */
    /** Added when a picture of the screen goes with the list (a screen with few readable elements). */
    const val PICTURE = "A screenshot of the screen is attached. If what you need is not in the list, you may also answer " +
        "{\"action\":\"tap_xy\",\"x\":<0-1000>,\"y\":<0-1000>,\"text\":\"<what is there>\"} to press a point of the picture " +
        "(0,0 is the top left, 1000,1000 the bottom right)."

    fun observation(app: String, goal: String, items: List<Item>, done: List<String>, step: Int): String = buildString {
        appendLine("Goal: $goal")
        appendLine("Step $step of $MAX_STEPS. Foreground app: $app")
        if (done.isNotEmpty()) appendLine("Already done: " + done.takeLast(6).joinToString("; "))
        appendLine("Screen:")
        for (it in items) appendLine("${it.n}. [${it.kind}] ${it.label.take(60)}")
    }

    /** The model's answer is one flat JSON object; this reads it without any library (so it also runs in plain unit tests). */
    private fun flatJson(raw: String): Map<String, String>? {
        val content = raw.substringAfterLast("</think>")
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val s = content.substring(start + 1, end)
        val out = HashMap<String, String>()
        var i = 0
        fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun string(): String? {
            if (i >= s.length || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append(' ')
                        'u' -> if (i + 4 < s.length) { sb.append(s.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: '?'); i += 4 }
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        while (true) {
            skip()
            if (i >= s.length) break
            val key = string() ?: return if (out.isEmpty()) null else out
            skip()
            if (i >= s.length || s[i] != ':') return if (out.isEmpty()) null else out
            i++
            skip()
            if (i < s.length && s[i] == '"') out[key] = string().orEmpty()
            else {
                val from = i
                while (i < s.length && s[i] != ',' && s[i] != '}') i++
                out[key] = s.substring(from, i).trim()
            }
        }
        return out
    }

    /** One step from the model's answer, or null if it is not a usable step. */
    fun parseStep(raw: String): Step? {
        val json = flatJson(raw) ?: return null
        val action = json["action"].orEmpty().trim().lowercase()
        if (action !in setOf("tap", "type", "scroll", "open", "back", "home", "wait", "say", "done", "tap_xy")) return null
        val n = json["n"]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        if ((action == "tap" || action == "type") && n < 1) return null
        val x = json["x"]?.trim()?.toDoubleOrNull()?.toInt() ?: -1
        val y = json["y"]?.trim()?.toDoubleOrNull()?.toInt() ?: -1
        if (action == "tap_xy" && (x !in 0..1000 || y !in 0..1000)) return null
        return Step(action, n, json["text"].orEmpty().trim(), json["direction"].orEmpty().ifBlank { "down" }.lowercase(), x, y)
    }

    /** The normal "smart mode" answer: {"command": "...", "reply": "...", "agent": "..."} */
    fun parseReply(raw: String): Reply {
        val json = flatJson(raw)
        if (json == null) return Reply("", raw.substringAfterLast("</think>").trim(), "")
        return Reply(json["command"].orEmpty().trim(), json["reply"].orEmpty().trim(), json["agent"].orEmpty().trim())
    }

    // ---- what the phone remembers about the person (nicknames and notes)

    /** "mom is Fatima" -> ("mom", "Fatima"); null when the sentence is a plain note. */
    fun aliasOf(text: String): Pair<String, String>? {
        val words = text.trim().split(Regex("\\s+"))
        val at = words.indexOfFirst { Commands.normalize(it) in setOf("هي", "هو", "اسمها", "اسمه", "is", "=") }
        if (at !in 1..3 || at >= words.size - 1) return null
        val from = words.subList(0, at).joinToString(" ")
        val to = words.drop(at + 1).joinToString(" ")
        return if (to.split(" ").size <= 3) from to to else null
    }

    fun matches(line: String, query: String): Boolean {
        val q = Commands.normalize(query).split(" ").filter { it.length > 1 }
        if (q.isEmpty()) return true
        val l = Commands.normalize(line)
        return q.all { l.contains(it) }
    }
}
