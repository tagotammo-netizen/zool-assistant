package org.jarvis.assistant

/**
 * شخصيات زول: how Zol talks. The persona shapes what the smart server writes (the tone of answers and of the
 * secretary's replies) and how the voice sounds (speed and pitch). Kept apart so it can be tested.
 */
object Personas {

    data class Persona(val id: String, val name: String, val prompt: String, val rate: Float, val pitch: Float)

    val ALL = listOf(
        Persona("friend", "صاحب ونّاس", "Talk like a warm Sudanese friend: friendly, a little light humour, everyday Sudanese words.", 1.0f, 1.0f),
        Persona("formal", "رسمي ومهذّب", "Talk politely and formally, in clear simple Arabic, with no jokes.", 0.95f, 1.0f),
        Persona("calm", "هادي وقليل الكلام", "Talk calmly and very briefly: the fewest words that answer.", 0.85f, 0.95f),
        Persona("elder", "زي الكبار", "Talk like a respectful Sudanese elder: kind, patient, with short blessings (الله يحفظك).", 0.9f, 0.9f),
    )

    fun get(id: String) = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun prompt(id: String) = get(id).prompt

    /** "صاحب" → friend, "رسمي" → formal, "هادي" → calm, "الكبار" → elder. */
    fun find(said: String): Persona? {
        val s = Commands.normalize(said)
        return when {
            s.contains("صاحب") || s.contains("ونس") || s.contains("friend") -> ALL[0]
            s.contains("رسمي") || s.contains("مهذب") || s.contains("formal") -> ALL[1]
            s.contains("هادي") || s.contains("قليل") || s.contains("calm") -> ALL[2]
            s.contains("كبار") || s.contains("كبير") || s.contains("شيخ") || s.contains("elder") -> ALL[3]
            else -> null
        }
    }
}
