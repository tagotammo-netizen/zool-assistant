package org.jarvis.assistant

import java.security.SecureRandom

/**
 * Messages from the caregiver, sent with the free ntfy app to a secret channel only they know. Only safe things are
 * allowed from afar: reminders and alarms, asking for the battery, the place or the state, and a message Zol says
 * aloud. Nothing that calls, sends or opens apps.
 */
object Remote {

    sealed class Action {
        data class Run(val cmd: Cmd) : Action()          // a reminder, alarm or timer, done as if the person said it
        data class Say(val text: String) : Action()
        object Battery : Action()
        object Where : Action()
        object Status : Action()
        object Help : Action()
        data class Refused(val why: String) : Action()
    }

    private val SAY = listOf("قول له", "قول ليه", "قول ليها", "قول لها", "قولي", "قول", "say")

    /**
     * [pin]: if set, the message must start with it ("1234 ذكره بالدوا الساعة 9").
     * Null when the message is not for Zol (a wrong pin is ignored quietly).
     */
    fun parse(message: String, pin: String = ""): Action? {
        var text = message.trim()
        if (pin.isNotBlank()) {
            if (!text.startsWith(pin.trim())) return null
            text = text.removePrefix(pin.trim()).trim()
        }
        if (text.isEmpty()) return null
        val n = Commands.normalize(text)
        for (lead in SAY) {
            val l = Commands.normalize(lead)
            if (n == l || n.startsWith("$l ")) {
                val words = text.split(Regex("\\s+"))
                val said = words.drop(l.split(" ").size).joinToString(" ").trim().removePrefix(":").trim()
                return if (said.isEmpty()) Action.Refused("اكتب الكلام بعد «قول».") else Action.Say(said)
            }
        }
        val w = n.split(" ").toSet()
        if (n in setOf("مساعده", "help", "الاوامر", "اوامر", "?", "؟")) return Action.Help
        if (w.any { it in setOf("وين", "الموقع", "موقعه", "موقعها", "مكانه", "location", "where") }) return Action.Where
        if (n in setOf("حاله", "الحاله", "كيفه", "كيفها", "طمني", "status") || w.contains("طمني")) return Action.Status
        if (w.any { it in setOf("البطاريه", "بطاريه", "الشحن", "battery") }) return Action.Battery
        // "ذكره" (remind him) is said the way the person would: "ذكرني"
        val asPerson = text.replace(Regex("(^|\\s)(ذكّره|ذكره|ذكّرها|ذكرها|فكّره|فكره)(?=\\s|$)"), "$1ذكرني")
            .replace(Regex("(^|\\s)(صحّيه|صحيه|صحّيها|صحيها)(?=\\s|$)"), "$1صحيني")
            .replace(Regex("(^|\\s)(نبّهه|نبهه|نبّهها|نبهها)(?=\\s|$)"), "$1نبهني")
        return when (val cmd = Commands.parse(asPerson)) {
            is Cmd.Remind, is Cmd.Alarm, is Cmd.Timer, Cmd.ListReminders, is Cmd.DeleteReminder -> Action.Run(cmd)
            null -> Action.Refused("ما فهمت. اكتب «مساعدة» عشان تشوف الأوامر.")
            else -> Action.Refused("الأمر ده ما مسموح من بعيد.")
        }
    }

    const val HELP = "أوامر زول من بعيد:\n" +
        "• ذكره بالدوا كل يوم الساعة 9 بالليل\n" +
        "• صحيه الساعة 6 الصباح\n" +
        "• شنو تذكيراتي • امسح تذكير الدوا\n" +
        "• قول: أنا جاي بعد شوية\n" +
        "• البطارية • وين • حالة"

    /** A channel name nobody can guess. */
    fun newTopic(random: SecureRandom = SecureRandom()): String {
        val letters = "abcdefghijkmnpqrstuvwxyz23456789"
        return "zol-" + (1..14).map { letters[random.nextInt(letters.length)] }.joinToString("")
    }

    /** One line of ntfy's JSON answer: the message id and text, or null for keepalive and open events. */
    fun ntfyMessage(json: String): Pair<String, String>? {
        if (!json.contains("\"event\":\"message\"")) return null
        val id = Regex("\"id\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val raw = Regex("\"message\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1) ?: return null
        return id to unescape(raw)
    }

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
