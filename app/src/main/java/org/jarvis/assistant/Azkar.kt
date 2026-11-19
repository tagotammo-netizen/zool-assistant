package org.jarvis.assistant

/**
 * Morning, evening and sleep adhkar, read aloud by Zol. Only the supplications are read by the voice engine;
 * for the Quran (Ayat al-Kursi, the muawwidhat) Zol offers to play a real recitation instead.
 * A dhikr said many times is read once, with how many times it is said.
 */
object Azkar {

    data class Dhikr(val text: String, val times: Int = 1)

    private val ISTIGHFAR = Dhikr("اللهم أنت ربي لا إله إلا أنت، خلقتني وأنا عبدك، وأنا على عهدك ووعدك ما استطعت، " +
        "أعوذ بك من شر ما صنعت، أبوء لك بنعمتك علي، وأبوء بذنبي فاغفر لي، فإنه لا يغفر الذنوب إلا أنت")

    private val SHARED = listOf(
        Dhikr("اللهم إني أسألك العفو والعافية في الدنيا والآخرة، اللهم إني أسألك العفو والعافية في ديني ودنياي وأهلي ومالي، " +
            "اللهم استر عوراتي وآمن روعاتي، اللهم احفظني من بين يدي ومن خلفي وعن يميني وعن شمالي ومن فوقي، " +
            "وأعوذ بعظمتك أن أغتال من تحتي"),
        Dhikr("اللهم عافني في بدني، اللهم عافني في سمعي، اللهم عافني في بصري، لا إله إلا أنت", 3),
        Dhikr("بسم الله الذي لا يضر مع اسمه شيء في الأرض ولا في السماء وهو السميع العليم", 3),
        Dhikr("رضيت بالله ربا، وبالإسلام دينا، وبمحمد صلى الله عليه وسلم نبيا", 3),
        Dhikr("يا حي يا قيوم برحمتك أستغيث، أصلح لي شأني كله، ولا تكلني إلى نفسي طرفة عين"),
        Dhikr("حسبي الله لا إله إلا هو، عليه توكلت وهو رب العرش العظيم", 7),
    )

    val MORNING = listOf(
        Dhikr("أصبحنا وأصبح الملك لله، والحمد لله، لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير. " +
            "رب أسألك خير ما في هذا اليوم وخير ما بعده، وأعوذ بك من شر ما في هذا اليوم وشر ما بعده. " +
            "رب أعوذ بك من الكسل وسوء الكبر، رب أعوذ بك من عذاب في النار وعذاب في القبر"),
        Dhikr("اللهم بك أصبحنا، وبك أمسينا، وبك نحيا، وبك نموت، وإليك النشور"),
        ISTIGHFAR,
    ) + SHARED + listOf(
        Dhikr("لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير", 10),
        Dhikr("سبحان الله وبحمده", 100),
    )

    val EVENING = listOf(
        Dhikr("أمسينا وأمسى الملك لله، والحمد لله، لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير. " +
            "رب أسألك خير ما في هذه الليلة وخير ما بعدها، وأعوذ بك من شر ما في هذه الليلة وشر ما بعدها. " +
            "رب أعوذ بك من الكسل وسوء الكبر، رب أعوذ بك من عذاب في النار وعذاب في القبر"),
        Dhikr("اللهم بك أمسينا، وبك أصبحنا، وبك نحيا، وبك نموت، وإليك المصير"),
        ISTIGHFAR,
    ) + SHARED + listOf(
        Dhikr("أعوذ بكلمات الله التامات من شر ما خلق", 3),
        Dhikr("سبحان الله وبحمده", 100),
    )

    val SLEEP = listOf(
        Dhikr("باسمك اللهم أموت وأحيا"),
        Dhikr("اللهم قني عذابك يوم تبعث عبادك", 3),
        Dhikr("سبحان الله ثلاثا وثلاثين، والحمد لله ثلاثا وثلاثين، والله أكبر أربعا وثلاثين"),
    )

    fun of(kind: String) = when (kind) { "evening" -> EVENING; "sleep" -> SLEEP; else -> MORNING }

    fun title(kind: String) = when (kind) { "evening" -> "أذكار المسا"; "sleep" -> "أذكار النوم"; else -> "أذكار الصباح" }

    /** "3 مرات", "مرتين", "100 مرة" */
    fun timesText(n: Int) = when {
        n == 2 -> "مرتين"
        n in 3..10 -> "$n مرات"
        else -> "$n مرة"
    }

    /**
     * What Zol says, one entry per dhikr. A dhikr said up to [maxRepeat] times is read that many times;
     * one said more often is read once, followed by how many times it is said.
     */
    fun script(kind: String, maxRepeat: Int = 3): List<String> {
        val lines = ArrayList<String>()
        lines.add(title(kind) + ".")
        for (d in of(kind)) {
            lines.add(when {
                d.times <= 1 -> d.text + "."
                d.times <= maxRepeat -> List(d.times) { d.text }.joinToString(". ") + "."
                else -> d.text + ". " + "تتقال ${timesText(d.times)}."
            })
        }
        if (kind != "sleep") lines.add("وما تنسى آية الكرسي والمعوذات. لو دايرها، قول: شغّل المعوذات.")
        return lines
    }

    /** Morning from dawn until Asr, evening after it. [minutes] is the time of day. */
    fun kindAt(minutes: Int, fajr: Int, asr: Int) = if (minutes in fajr until asr) "morning" else "evening"
}
