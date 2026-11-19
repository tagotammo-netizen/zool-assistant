package org.jarvis.assistant

/**
 * The talking board (AAC): big cards with everyday sentences. Choosing a card (by touch, the head pointer or a switch)
 * says it aloud in Zol's voice, or sends it to the caregiver. The sentences are the person's own words, said as they
 * are written, in Sudanese.
 */
object Aac {

    data class Card(val emoji: String, val text: String, val urgent: Boolean = false)

    data class Page(val title: String, val cards: List<Card>)

    val PAGES = listOf(
        Page("محتاج", listOf(
            Card("💧", "أنا عطشان، عايز موية"),
            Card("🍽", "أنا جيعان، عايز آكل"),
            Card("🚻", "داير أمشي الحمام"),
            Card("🛏", "داير أرقد"),
            Card("💊", "عايز الدوا بتاعي"),
            Card("🔄", "قلّبني لو سمحت"),
            Card("❄", "أنا بردان"),
            Card("🔥", "أنا حرّان، شغّل المروحة"),
        )),
        Page("حاسي", listOf(
            Card("🤕", "عندي وجع", urgent = true),
            Card("😮‍💨", "ما قادر أتنفّس كويس", urgent = true),
            Card("😴", "أنا تعبان"),
            Card("😊", "أنا كويس، الحمد لله"),
            Card("😟", "أنا متضايق"),
            Card("🤢", "حاسي بغثيان"),
            Card("🥶", "عندي رجفة"),
            Card("😌", "خلّوني أرتاح شوية"),
        )),
        Page("ناس", listOf(
            Card("👩", "نادوا لي أمي"),
            Card("👨", "نادوا لي أبوي"),
            Card("👨‍⚕️", "داير الدكتور", urgent = true),
            Card("📞", "عايز أتكلم في التلفون"),
            Card("🙋", "تعال لو سمحت"),
            Card("🚪", "اقفل الباب"),
            Card("📺", "شغّل التلفزيون"),
            Card("🕌", "داير أصلّي، ساعدني"),
        )),
        Page("ردود", listOf(
            Card("✅", "أيوه"),
            Card("❌", "لا"),
            Card("🙏", "شكراً كتير"),
            Card("⏳", "استنى شوية"),
            Card("🔁", "قول تاني لو سمحت"),
            Card("🤷", "ما عارف"),
            Card("👋", "السلام عليكم"),
            Card("💚", "بحبّكم"),
        )),
    )

    /** The person's own cards, one per line in settings; a line may start with an emoji. */
    fun custom(lines: String): Page? {
        val cards = lines.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(16).map { line ->
            val first = line.codePointAt(0)
            val isEmoji = Character.getType(first) == Character.OTHER_SYMBOL.toInt() || first >= 0x1F000
            if (isEmoji) {
                val cut = line.indexOf(' ').takeIf { it > 0 } ?: return@map Card("💬", line)
                Card(line.substring(0, cut), line.substring(cut + 1).trim())
            } else Card("💬", line)
        }
        return if (cards.isEmpty()) null else Page("كلامي", cards)
    }

    fun pages(customLines: String) = listOfNotNull(custom(customLines)) + PAGES

    /** What the caregiver receives. */
    fun forCaregiver(card: Card) = (if (card.urgent) "⚠ " else "") + "زول: " + card.text
}
