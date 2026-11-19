package org.jarvis.assistant

import java.util.Locale

/**
 * Surah names, the reciters Zol can play, and finding a surah in what the person said:
 * "شغل سورة الكهف", "سورة يس بصوت نورين", "المعوذات", "سورة رقم 18".
 * The recitations are streamed from mp3quran.net (one file per surah).
 */
object Quran {

    val NAMES = listOf(
        "الفاتحة", "البقرة", "آل عمران", "النساء", "المائدة", "الأنعام", "الأعراف", "الأنفال", "التوبة", "يونس",
        "هود", "يوسف", "الرعد", "إبراهيم", "الحجر", "النحل", "الإسراء", "الكهف", "مريم", "طه",
        "الأنبياء", "الحج", "المؤمنون", "النور", "الفرقان", "الشعراء", "النمل", "القصص", "العنكبوت", "الروم",
        "لقمان", "السجدة", "الأحزاب", "سبأ", "فاطر", "يس", "الصافات", "ص", "الزمر", "غافر",
        "فصلت", "الشورى", "الزخرف", "الدخان", "الجاثية", "الأحقاف", "محمد", "الفتح", "الحجرات", "ق",
        "الذاريات", "الطور", "النجم", "القمر", "الرحمن", "الواقعة", "الحديد", "المجادلة", "الحشر", "الممتحنة",
        "الصف", "الجمعة", "المنافقون", "التغابن", "الطلاق", "التحريم", "الملك", "القلم", "الحاقة", "المعارج",
        "نوح", "الجن", "المزمل", "المدثر", "القيامة", "الإنسان", "المرسلات", "النبأ", "النازعات", "عبس",
        "التكوير", "الانفطار", "المطففين", "الانشقاق", "البروج", "الطارق", "الأعلى", "الغاشية", "الفجر", "البلد",
        "الشمس", "الليل", "الضحى", "الشرح", "التين", "العلق", "القدر", "البينة", "الزلزلة", "العاديات",
        "القارعة", "التكاثر", "العصر", "الهمزة", "الفيل", "قريش", "الماعون", "الكوثر", "الكافرون", "النصر",
        "المسد", "الإخلاص", "الفلق", "الناس",
    )

    /** Other names people use for a surah, and the way speech recognition sometimes writes them. */
    private val ALIASES = mapOf(
        "ياسين" to 36, "يسين" to 36, "ياسن" to 36, "بني اسرائيل" to 17, "تبت" to 111, "تبت يدا" to 111, "الدهر" to 76,
        "المؤمن" to 40, "الانشراح" to 94, "الم نشرح" to 94, "براءه" to 9, "تبارك" to 67, "قل هو الله احد" to 112,
        "الكافرين" to 109, "قل يا ايها الكافرون" to 109, "المؤمنين" to 23, "المنافقين" to 63, "الزلزال" to 99,
        "عم" to 78, "عم يتساءلون" to 78, "صاد" to 38, "قاف" to 50, "طاها" to 20, "طاهي" to 20, "لم يكن" to 98,
        "الرحمان" to 55, "اقرا باسم ربك" to 96, "حم السجده" to 41, "الصافات" to 37, "الحمد" to 1, "ام الكتاب" to 1,
        "الفاتحه" to 1, "النبا" to 78, "الملك تبارك" to 67, "الانسان" to 76, "القتال" to 47,
    )

    /** The three last surahs, read together morning and evening. */
    val MUAWWIDHAT = listOf(112, 113, 114)

    /** Kept on the phone for days with no internet: Al-Fatiha, Al-Kahf, Ya-Sin, Al-Waqi'a, Al-Mulk and Juz' Amma. */
    val ESSENTIAL = listOf(1, 18, 36, 56, 67) + (78..114)

    data class Reciter(val id: String, val ar: String, val en: String, val pattern: String, val riwaya: String)

    val RECITERS = listOf(
        Reciter("afs", "مشاري العفاسي", "Mishary Alafasy", "https://server8.mp3quran.net/afs/%03d.mp3", "حفص عن عاصم"),
        Reciter("basit", "عبد الباسط عبد الصمد", "Abdul Basit", "https://server7.mp3quran.net/basit/%03d.mp3", "حفص عن عاصم"),
        Reciter("nourin", "نورين محمد صديق", "Noreen Siddiq",
            "https://server16.mp3quran.net/nourin_siddig/Rewayat-Aldori-A-n-Abi-Amr/%03d.mp3", "الدوري عن أبي عمرو"),
    )

    const val DEFAULT_RECITER = "afs"

    fun reciter(id: String?) = RECITERS.firstOrNull { it.id == id } ?: RECITERS.first()

    fun url(surah: Int, reciterId: String?): String = String.format(Locale.US, reciter(reciterId).pattern, surah)

    fun name(surah: Int) = NAMES.getOrNull(surah - 1) ?: surah.toString()

    /** "سورة الكهف" in replies. */
    fun title(surah: Int) = "سورة ${name(surah)}"

    /** Which reciter was asked for: "بصوت العفاسي", "عبد الباسط", "الشيخ نورين". */
    fun reciterOf(text: String): String? {
        val t = " " + Commands.normalize(text).replace("عبد ال", "عبدال") + " "
        return when {
            t.contains("عفاسي") || t.contains(" مشاري") -> "afs"
            t.contains("عبدالباسط") || t.contains(" الباسط") -> "basit"
            t.contains("نورين") -> "nourin"
            else -> null
        }
    }

    private fun key(s: String): String {
        var k = Commands.normalize(s).replace(" ", "").replace("ء", "").replace("ؤ", "و").replace("ئ", "ي")
        for (lead in listOf("بال", "لل", "ال")) if (k.startsWith(lead) && k.length > lead.length + 1) { k = k.removePrefix(lead); break }
        return k
    }

    private val BY_KEY: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        ALIASES.forEach { (n, i) -> m[key(n)] = i }
        NAMES.forEachIndexed { i, n -> m[key(n)] = i + 1 }          // the proper names win over the aliases
        m
    }

    /** The surah called [words] (one to four words), or null. */
    fun surahNamed(words: String): Int? {
        val w = Commands.normalize(words).split(" ").filter { it.isNotEmpty() }
        for (n in minOf(4, w.size) downTo 1) BY_KEY[key(w.take(n).joinToString(" "))]?.let { return it }
        return null
    }

    private val MARKERS = setOf("سوره", "سورت", "سور")
    private val STOP = setOf("بصوت", "للشيخ", "الشيخ", "مع", "و", "بتاع", "بتاعت", "تاني", "كامله", "من", "في")

    /**
     * The surahs asked for in [text]: after the word "سورة" ("شغل سورة الملك", "سورة 18"), or the muawwidhat.
     * Empty when none was named.
     */
    fun surahsIn(text: String): List<Int> {
        val t = Commands.normalize(text)
        if (Regex("(^| )(المعوذات|المعوذتين|المعوذتان|معوذات)( |$)").containsMatchIn(t)) return MUAWWIDHAT
        val w = t.split(" ").filter { it.isNotEmpty() }
        val at = w.indexOfFirst { it in MARKERS || it == "السوره" }
        if (at < 0) return emptyList()
        var after = w.drop(at + 1).takeWhile { it !in STOP }
        if (after.firstOrNull() == "رقم") after = after.drop(1)
        after.firstOrNull()?.toIntOrNull()?.let { return if (it in 1..114) listOf(it) else emptyList() }
        return surahNamed(after.joinToString(" "))?.let { listOf(it) } ?: emptyList()
    }

    fun next(surah: Int) = if (surah >= 114) 1 else surah + 1
    fun prev(surah: Int) = if (surah <= 1) 114 else surah - 1
}
