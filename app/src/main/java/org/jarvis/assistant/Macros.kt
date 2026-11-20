package org.jarvis.assistant

/**
 * "علّم زول بالمشاهدة": the person does something once (taps, typing, or saying commands) while Zol watches, gives
 * it a name, and from then on saying the name does it all again. Kept apart from the service so it can be tested.
 */
object Macros {

    /**
     * One step. [kind]: "open" (an app, [pkg]), "click" (a button found again by its [id], [text] or [desc];
     * [x],[y] are where it was, used only when nothing matches), "type" (into the field [id]: [text]),
     * "say" (a spoken command, [text]).
     */
    data class Step(val kind: String, val pkg: String = "", val text: String = "", val desc: String = "",
                    val id: String = "", val x: Int = -1, val y: Int = -1)

    private const val F = '\u001F'   // between fields
    private const val S = '\u001D'   // between steps
    private const val M = '\u001E'   // between routines

    fun encode(all: Map<String, List<Step>>): String = all.entries.joinToString(M.toString()) { (name, steps) ->
        clean(name) + S + steps.joinToString(S.toString()) { s ->
            listOf(s.kind, s.pkg, s.text, s.desc, s.id, s.x.toString(), s.y.toString()).joinToString(F.toString()) { clean(it) }
        }
    }

    fun decode(raw: String): LinkedHashMap<String, List<Step>> {
        val out = LinkedHashMap<String, List<Step>>()
        if (raw.isBlank()) return out
        for (block in raw.split(M)) {
            val parts = block.split(S)
            val name = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: continue
            out[name] = parts.drop(1).mapNotNull { line ->
                val f = line.split(F)
                if (f.size < 7) null else Step(f[0], f[1], f[2], f[3], f[4], f[5].toIntOrNull() ?: -1, f[6].toIntOrNull() ?: -1)
            }
        }
        return out
    }

    private fun clean(s: String) = s.replace(F, ' ').replace(S, ' ').replace(M, ' ')

    /**
     * How well a button on screen is the one tapped when learning: the same id counts most, then the same words.
     * 0 when it is not it.
     */
    fun score(step: Step, id: String?, text: String?, desc: String?): Int {
        var s = 0
        if (step.id.isNotEmpty() && step.id == id) s += 3
        val t = Commands.normalize(text.orEmpty()).trim()
        val d = Commands.normalize(desc.orEmpty()).trim()
        val st = Commands.normalize(step.text).trim()
        val sd = Commands.normalize(step.desc).trim()
        if (st.isNotEmpty() && st == t) s += 3 else if (st.length >= 3 && t.contains(st)) s += 1
        if (sd.isNotEmpty() && sd == d) s += 2 else if (sd.length >= 3 && d.contains(sd)) s += 1
        // an id alone is shared by every row of a list: it needs the words too when the step has words
        if (s == 3 && step.id == id && (st.isNotEmpty() || sd.isNotEmpty())) s = 2
        return s
    }

    /** Good enough to press. */
    const val ENOUGH = 3

    /** Typing in the same field replaces the step before it, so a routine has the finished words only. */
    fun add(steps: MutableList<Step>, step: Step) {
        val last = steps.lastOrNull()
        if (step.kind == "type" && last?.kind == "type" && last.id == step.id && last.pkg == step.pkg) {
            steps[steps.size - 1] = step
            return
        }
        if (step.kind == "open" && last?.kind == "open") { steps[steps.size - 1] = step; return }
        if (step.kind == "open" && last?.pkg == step.pkg) return
        steps.add(step)
    }

    /** What Zol says it learned: "افتح واتساب، اضغط «محمد»، اكتب «جاي»". */
    fun describe(steps: List<Step>, appName: (String) -> String): String = steps.joinToString("، ") { s ->
        when (s.kind) {
            "open" -> "افتح ${appName(s.pkg)}"
            "type" -> "اكتب «${s.text.take(30)}»"
            "say" -> "«${s.text.take(30)}»"
            else -> "اضغط «${(s.text.ifEmpty { s.desc }).take(30).ifEmpty { "زر" }}»"
        }
    }
}
