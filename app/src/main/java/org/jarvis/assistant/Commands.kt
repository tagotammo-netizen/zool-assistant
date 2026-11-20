package org.jarvis.assistant

/** One understood command. The service decides how to carry it out. */
sealed class Cmd {
    data class Global(val what: String) : Cmd()      // back, home, recents, notifications, lock, screenshot
    data class Scroll(val down: Boolean) : Cmd()
    data class Tap(val label: String) : Cmd()
    data class Type(val text: String) : Cmd()
    data class OpenApp(val name: String) : Cmd()
    data class Call(val target: String) : Cmd()      // a phone number, or "emergency"
    data class Search(val query: String) : Cmd()
    data class Volume(val up: Boolean) : Cmd()
    data class Message(val name: String, val body: String) : Cmd()
    data class Numbers(val show: Boolean) : Cmd()
    data class Home(val device: String, val on: Boolean) : Cmd()   // smart home: light, fan, door...
    data class Swipe(val toRight: Boolean) : Cmd()
    data class Flashlight(val on: Boolean) : Cmd()
    data class Alarm(val hour: Int, val minute: Int) : Cmd()
    data class Timer(val minutes: Int) : Cmd()
    data class Bubble(val show: Boolean) : Cmd()
    data class Chat(val kind: String) : Cmd()        // hello, thanks, who, how
    object ReadScreen : Cmd()
    object Battery : Cmd()
    object Today : Cmd()
    object Help : Cmd()
    object ClearText : Cmd()
    object Send : Cmd()
    data class WhatsApp(val name: String, val body: String) : Cmd()
    data class Reply(val body: String) : Cmd()
    data class SearchIn(val app: String, val query: String) : Cmd()
    data class Describe(val whereOnly: Boolean) : Cmd()
    object ReadLast : Cmd()
    object Repeat : Cmd()
    object Answer : Cmd()
    object HangUp : Cmd()
    data class Speaker(val on: Boolean) : Cmd()
    object ReadNotifs : Cmd()
    data class Scene(val name: String) : Cmd()
    data class Grid(val show: Boolean) : Cmd()
    object GridHere : Cmd()
    data class Remind(val hour: Int, val minute: Int, val text: String, val daily: Boolean) : Cmd()
    object ReadLong : Cmd()
    data class HomeStatus(val device: String) : Cmd()
    object Caregiver : Cmd()
    data class Media(val kind: String) : Cmd()               // playpause, next, prev
    data class PlayVideo(val query: String) : Cmd()          // "شغل في اليوتيوب ..." : search and play the first video
    data class Seek(val forward: Boolean) : Cmd()            // ten seconds on or back
    data class SeeScreen(val question: String) : Cmd()       // a picture of the screen goes to a model that can see
    data class Photo(val seconds: Int) : Cmd()
    data class OpenChat(val name: String) : Cmd()
    data class Dictation(val start: Boolean) : Cmd()
    object DeleteWord : Cmd()
    data class Replace(val from: String, val to: String) : Cmd()
    data class Mode(val name: String) : Cmd()                // normal, car, night
    data class Agent(val goal: String) : Cmd()
    data class AskScreen(val question: String) : Cmd()
    data class Remember(val text: String) : Cmd()
    data class Recall(val query: String) : Cmd()
    data class Forget(val query: String) : Cmd()
    object Time : Cmd()
    data class Head(val on: Boolean) : Cmd()                 // pointer moved by the head through the front camera
    object HeadCenter : Cmd()
    object LongHere : Cmd()                                  // long press where the head pointer is
    data class HeadPause(val on: Boolean) : Cmd()
    object ListReminders : Cmd()
    data class DeleteReminder(val name: String) : Cmd()
    data class PrayerTime(val prayer: String?) : Cmd()       // null: the next one; "all": today's list; else "MAGHRIB"…
    data class Quran(val surahs: List<Int>, val reciter: String?) : Cmd()   // empty: carry on from the last surah
    data class QuranCtl(val what: String) : Cmd()                // stop, resume, next, prev
    data class Azkar(val kind: String?) : Cmd()                  // morning, evening, sleep; null: by the time of day
    object Qibla : Cmd()
    data class HeadKeyboard(val show: Boolean) : Cmd()
    data class QuranDownload(val surahs: List<Int>, val reciter: String?) : Cmd()   // keep on the phone for no internet
    data class Board(val show: Boolean) : Cmd()
    object CheckUpdate : Cmd()                  // the talking board           // the keyboard typed with the head pointer
    object Train : Cmd()                                     // "دربني": voice training of the person's own dialect
    data class Teach(val phrase: String, val meaning: String) : Cmd()   // "لما اقول X يعني Y"
    object Hush : Cmd()                                      // "اسكت": stop talking / reading
    data class Mute(val on: Boolean) : Cmd()
    object Emergency : Cmd()
    object StopListening : Cmd()
}

/** Keyword command parser: Arabic, Sudanese dialect and English. No model, works offline, instant. */
/** The assistant's name. Package and class names keep the old "jarvis" so existing installs update in place. */
object Brand {
    const val NAME = "زول"
    const val NAME_EN = "Zol"
}

object Commands {
    private val WAKE = setOf("جوني", "جونى", "جونيي", "جوني", "جونيه", "جون", "جني", "جوناي", "جونيا", "johnny", "jonny", "johnie", "joni", "jony", "johny", "جارفيس", "جارفس", "جرفيس", "جارفيز", "جافيس", "جارفز", "jarvis")
    /** Ways the recogniser writes "زول". */
    private val ZOL = setOf("زول", "زوول", "زولل", "زل", "zol", "zool", "zoul", "zole")

    /** The word the person calls the assistant by, as shown in hints. Set from settings ("wake_word"). */
    @Volatile var wakeWord: String = "زول"
        private set

    /** Wake words chosen in settings. They also count only at the start or end of a sentence (see [stripWake]). */
    @Volatile private var chosenWake: Set<String> = ZOL

    /** "زول", "جوني", or any word the person typed. "جوني" and the old names always keep working. */
    fun setWake(choice: String?) {
        val c = choice?.trim().orEmpty()
        when {
            c.isEmpty() || normalize(c) in ZOL -> { wakeWord = "زول"; chosenWake = ZOL }
            normalize(c) in WAKE -> { wakeWord = "جوني"; chosenWake = emptySet() }
            else -> { wakeWord = c; chosenWake = setOf(normalize(c).replace(" ", "")) }
        }
    }

    private val WAKE_LEAD = setOf("يا", "ياخ", "هاي", "هي", "طيب", "ممكن", "اها", "يلا", "hey", "hi", "ok", "okay")

    private val EMERGENCY = listOf("ساعدوني", "ساعدني", "النجده", "الحقوني", "الحقني", "طوارئ", "help me", "emergency")

    /** Arabic names people say -> words found in app labels. */
    val APP_ALIASES = mapOf(
        "واتساب" to "whatsapp", "واتس" to "whatsapp", "يوتيوب" to "youtube", "فيسبوك" to "facebook",
        "فيس" to "facebook", "تلغرام" to "telegram", "تليجرام" to "telegram", "تيليجرام" to "telegram",
        "كروم" to "chrome", "كاميرا" to "camera", "خرائط" to "maps", "رسائل" to "messages",
        "هاتف" to "phone", "تلفون" to "phone", "اعدادات" to "settings", "ضبط" to "settings",
        "صور" to "photos", "معرض" to "gallery", "حاسبه" to "calculator", "ساعه" to "clock",
        "جيميل" to "gmail", "متجر" to "play store", "تيك توك" to "tiktok", "انستقرام" to "instagram",
        "انستغرام" to "instagram", "ملفات" to "files", "تقويم" to "calendar", "جهات الاتصال" to "contacts",
        "استوديو" to "gallery", "متصفح" to "chrome", "نت" to "chrome", "انترنت" to "chrome", "بريد" to "gmail",
        "ايميل" to "gmail", "موسيقي" to "music", "اغاني" to "music", "منبه" to "clock", "ملاحظات" to "notes",
        "مذكره" to "notes", "خريطه" to "maps", "قوقل" to "google", "جوجل" to "google", "انستا" to "instagram",
        "فيديو" to "video", "مسجل" to "recorder", "راديو" to "radio", "طقس" to "weather", "ايمو" to "imo",
    )

    private val NUMBER_WORDS = mapOf(
        "واحد" to 1, "اثنين" to 2, "اتنين" to 2, "اثنان" to 2, "ثلاثه" to 3, "تلاته" to 3, "اربعه" to 4,
        "خمسه" to 5, "سته" to 6, "سبعه" to 7, "ثمانيه" to 8, "تمانيه" to 8, "تسعه" to 9, "عشره" to 10,
        "احدعشر" to 11, "حداشر" to 11, "اثناعشر" to 12, "اتناشر" to 12, "one" to 1, "two" to 2, "three" to 3,
        "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12,
        "تلات" to 3, "اربع" to 4, "خمس" to 5, "ست" to 6, "سبع" to 7, "تمن" to 8, "تسع" to 9, "عشر" to 10,
    )
    /** Sudanese / colloquial command verbs -> the standard verb the parser knows (first two words only). */
    private val DIALECT = mapOf(
        "جيب" to "افتح", "جيبي" to "افتح", "هات" to "افتح", "اديني" to "افتح", "وريني" to "افتح", "ورني" to "افتح",
        "فتحلي" to "افتح", "دقس" to "دوس", "كبس" to "دوس", "اكبس" to "دوس", "ضغط" to "اضغط",
        "رسل" to "ارسل", "ابعتلي" to "ارسل", "كتب" to "اكتب", "فتش" to "ابحث", "دور" to "ابحث", "دورلي" to "ابحث",
        "كلمني" to "اتصل", "رن" to "اتصل", "رنلي" to "اتصل",
        "دقلي" to "اتصل", "اضربلي" to "اتصل", "ضربلي" to "اتصل", "اكلم" to "اتصل", "نكلم" to "اتصل",
        "نزلي" to "انزل", "طلعلي" to "اطلع", "رجعلي" to "ارجع", "ورينا" to "افتح", "افتحلي" to "افتح",
        "ولعلي" to "ولع", "طفيلي" to "طفي", "اكتبلي" to "اكتب", "اقرالي" to "اقرا", "قريلي" to "اقرا", "قرالي" to "اقرا",
        "رسلي" to "ارسل", "ابعت" to "ارسل", "رسلو" to "ارسل",
    )

    /** Minutes of the day of a prayer at the person's place, so "ذكرني بعد المغرب" works. Set by the service. */
    @Volatile var prayerMinutes: ((PrayerTimes.Prayer) -> Int?)? = null

    /** Home devices from settings (normalised, without "ال"), so "شغل المروحة" switches the fan instead of looking for an app. */
    @Volatile var homeDevices: Set<String> = emptySet()

    /** Words that often trail a command and are not part of a name: "افتح اليوتيوب ياخ". */
    private val TRAILING = setOf("ياخ", "ياخي", "زول", "يازول", "بالله", "هسي", "هسه", "الان", "please", "now", "سريع", "بسرعه")
    private val TRAILING_PAIRS = listOf(listOf("عليك", "الله"), listOf("لو", "سمحت"), listOf("من", "فضلك"), listOf("يا", "زول"), listOf("يا", "جوني"))

    private fun trimTail(name: String): String {
        val w = words(name).toMutableList()
        var changed = true
        while (changed && w.size > 1) {
            changed = false
            val pair = TRAILING_PAIRS.firstOrNull { p -> w.size > p.size && w.takeLast(p.size).map { normalize(it) } == p }
            if (pair != null) { repeat(pair.size) { w.removeAt(w.size - 1) }; changed = true }
            else if (normalize(w.last()) in TRAILING) { w.removeAt(w.size - 1); changed = true }
        }
        return w.joinToString(" ")
    }

    /** Words that start a message body in the dialect and are not part of it: "قول ليهو أنا جاي". */
    private val BODY_LEAD = setOf("ليهو", "ليها", "ليهم", "ليه", "له", "لها", "ليك", "ليكي", "ليهن")

    private fun dropBodyLead(body: String) = words(body).dropWhile { normalize(it) in BODY_LEAD }.joinToString(" ")

    private val WANT = setOf("داير", "دايره", "دايرين", "عايز", "عاوز", "اريد", "ابغي", "ابي", "بدي", "وديني", "ودني", "خش", "ادخل", "want")

    /** Extra words the user taught the app in settings ("كلمة=الكلمة القياسية", one per line). Set by the service. */
    @Volatile var userDict: Map<String, String> = emptyMap()

    fun parseDict(text: String): Map<String, String> =
        text.lines().mapNotNull { line ->
            val parts = line.split("=")
            if (parts.size != 2) return@mapNotNull null
            val k = normalize(parts[0]).replace(" ", "")
            val v = parts[1].trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()

    private fun applyDialect(w: List<String>, everywhere: Boolean = false): List<String> {
        if (w.isEmpty()) return w
        val out = ArrayList<String>()
        for ((i, word) in w.withIndex()) {
            val n = normalize(word)
            val swap = if (i < 2 || everywhere) userDict[n] ?: DIALECT[n] else null
            out.add(swap ?: word)
        }
        return out
    }

    private val ANSWER = setOf("اجب", "رد علي المكالمه", "رد علي الاتصال", "رد علي الخط", "استقبل", "استقبل المكالمه", "افتح الخط",
        "اجب علي المكالمه", "اجب المكالمه", "ارفع المكالمه", "ارفع الخط", "رد علي التلفون", "رد علي التليفون", "استلم المكالمه",
        "ارفعها", "رد عليهو", "رد عليها", "answer", "answer the call", "answer call", "pick up", "pick up the call")
    private val HANGUP = setOf("انهي المكالمه", "انه المكالمه", "اقفل المكالمه", "سكر المكالمه", "اقفل الخط", "سكر الخط",
        "انهي الاتصال", "ارفض", "ارفض المكالمه", "ارفض الاتصال", "رفض", "ما داير ارد", "ما بدور ارد", "ما برد", "ارفضها",
        "اقفل الخط دا", "قفل الخط", "قفل المكالمه", "hang up", "end call", "end the call", "reject", "decline")
    private val SPEAKER_ON = setOf("شغل المكبر", "فعل المكبر", "افتح المكبر", "مكبر الصوت", "شغل مكبر الصوت", "شغل السماعه", "شغل الاسبيكر", "افتح الاسبيكر", "الاسبيكر", "السبيكر", "شغل السبيكر",
        "السماعه الخارجيه", "كبر الصوت في المكالمه", "speaker on", "loudspeaker", "speakerphone")
    /** Whole sentence only: "وقف" inside "وقف المروحه" must not silence Zol. */
    private val HUSH = setOf("اسكت", "اسكتي", "سكت", "بس", "بس خلاص", "خلاص", "خلاص بس", "كفايه", "وقف الكلام", "بطل كلام", "وقف", "اوقف",
        "اسكت شويه", "اسكت شوي", "اسكت ياخ", "اسكت يا زول", "خلاص كفايه", "كفايه كلام", "ارح", "اصبر", "اصبر شويه",
        "shut up", "be quiet", "stop talking", "quiet")
    private val SPEAKER_OFF = setOf("طفي المكبر", "اطفي المكبر", "اغلق المكبر", "اقفل المكبر", "قفل المكبر", "speaker off")

    /** Whole sentences the person taught the app ("جملة=الأمر القياسي"). Set by the service. */
    /** Ready-made routines: one phrase runs a chain. The person can write their own in settings. */
    private val DEFAULT_ROUTINES by lazy { mapOf(
        "صباح الخير" to "كم الساعه ثم التاريخ ثم البطاريه ثم اقرا الاشعارات",
        "روتين الصباح" to "كم الساعه ثم التاريخ ثم البطاريه ثم اقرا الاشعارات",
        "good morning" to "what time ثم date ثم battery ثم read notifications",
    ).mapKeys { normalize(it.key).trim() } }

    @Volatile var userPhrases: Map<String, String> = emptyMap()

    fun parsePhrases(text: String): Map<String, String> =
        text.lines().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at < 1) return@mapNotNull null
            val k = normalize(line.substring(0, at)).trim()
            val v = line.substring(at + 1).trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()

    private val PRONOUNS = setOf("به", "بيه", "بها", "بيها", "عليه", "عليها", "ليه", "له", "لها", "ليها", "him", "her", "them", "back")
    private val WA_WORDS = setOf("واتساب", "الواتساب", "واتس", "الواتس", "وتساب", "الوتساب", "whatsapp")
    private val WA_FILL = setOf("علي", "عبر", "في", "ب", "via", "on", "through", "by", "to", "ل")

    fun isPronoun(name: String) = normalize(name) in PRONOUNS

    private val MESSAGE_SEPARATORS = setOf("تقول", "قول", "يقول", "نصها", "مفادها", "saying", "say")

    /** "7", "٧", "سبعة", "رقم 7", "number seven" -> 7 */
    fun toNumber(text: String): Int? {
        val t = normalize(text).removePrefix("رقم ").removePrefix("الرقم ").removePrefix("number ").trim()
        if (t.isNotEmpty() && t.length <= 3 && t.all { it.isDigit() }) return t.toInt()
        return NUMBER_WORDS[t.replace(" ", "")]
    }

    /** Short answer to "shall I send it?": true = yes, false = no, null = something else. */
    fun yesNo(text: String): Boolean? {
        val t = normalize(text)
        if (t.isEmpty() || t.split(" ").size > 3) return null
        return when {
            has(t, "لا", "لالا", "الغ", "الغي", "الغاء", "no", "cancel") -> false
            has(t, "نعم", "ايوه", "ايوا", "اي", "اها", "تمام", "اكيد", "ارسل", "ارسلها", "yes", "yeah", "send", "ok") -> true
            else -> null
        }
    }

    /** Spoken name -> forms to look for in contacts ("بأحمد" / "لأحمد" also try "احمد"). */
    fun nameCandidates(spoken: String): List<String> {
        val n = normalize(spoken)
        val out = mutableListOf(n)
        if (n.length > 2 && (n.startsWith("ب") || n.startsWith("ل"))) out.add(n.drop(1))
        return out.filter { it.isNotEmpty() }.distinct()
    }

    private val CONNECTORS = setOf("ثم", "بعدين", "وبعدين", "بعدها", "وبعدها", "then", "and")

    /** Fixed phrases, in priority order: the first group that appears in the sentence wins. */
    private val FIXED: List<Pair<List<String>, Cmd>> = listOf(
        // Sudanese everyday phrasings, checked before the general ones below
        listOf("الرسايل", "رسايلي", "شنو الرسايل", "في رسايل", "رسايل جديده", "الرسايل الجات", "الرسايل الجديده", "قريني الرسايل",
            "اقرا الرسايل", "منو اتصل", "مين اتصل", "منو الاتصل", "منو ضرب", "المكالمات الفايته", "فاتتني مكالمه", "في زول اتصل") to Cmd.ReadNotifs,
        listOf("امسح الكلمه دي", "امسح الكلمه", "امسح اخر كلمه", "شيل الكلمه", "شيل اخر كلمه", "احذف الكلمه") to Cmd.DeleteWord,
        listOf("صور لي", "شيل صوره", "اشيل صوره", "خد صوره", "اخد صوره", "ارفع صوره", "صورنا") to Cmd.Photo(3),
        listOf("نقص الصوت", "قلل الصوت شويه", "صغر الصوت", "الصوت عالي") to Cmd.Volume(false),
        listOf("كبر الصوت", "الصوت واطي", "ما سامع") to Cmd.Volume(true),
        listOf("الغنيه الجايه", "الاغنيه الجايه", "الغنيه الجاية", "الفيديو الجاي") to Cmd.Media("next"),
        listOf("الغنيه الفاتت", "الاغنيه الفاتت", "الفيديو الفات") to Cmd.Media("prev"),
        listOf("شغل الغنا", "شغل الاغاني", "شغل اغنيه", "شغل الموسيقي", "كمل الغنيه", "واصل الغنيه") to Cmd.Media("playpause"),
        listOf("يوم شنو", "الليله يوم", "نحن في ياتو يوم", "اليوم ياتو يوم", "الشهر كم", "التاريخ الليله") to Cmd.Today,
        listOf("شنو المكتوب هنا", "شنو هنا", "شنو في الشاشه", "الشاشه فيها شنو", "اقرا المكتوب") to Cmd.ReadScreen,
        listOf("اقرا الاشعارات", "اقرا لي الاشعارات", "شنو الاشعارات", "ايش الاشعارات", "ماذا في الاشعارات", "ما هي الاشعارات",
            "اخر الاشعارات", "read notifications", "my notifications", "read my notifications") to Cmd.ReadNotifs,
        listOf("اخف الارقام", "اخفي الارقام", "شيل الارقام", "hide numbers") to Cmd.Numbers(false),
        listOf("الارقام", "ارقام", "show numbers", "numbers") to Cmd.Numbers(true),
        listOf("اخف الزر", "اخفي الزر", "شيل الزر", "hide button", "hide the button") to Cmd.Bubble(false),
        listOf("اظهر الزر", "ورني الزر", "رجع الزر", "show button", "show the button") to Cmd.Bubble(true),
        listOf("وقف الاستماع", "توقف عن الاستماع", "اوقف الاستماع", "stop listening") to Cmd.StopListening,
        listOf("اقفل الشاشه", "قفل الشاشه", "اقفل التلفون", "اقفل الهاتف", "اقفل الجوال", "lock screen", "lock the screen") to Cmd.Global("lock"),
        listOf("صور الشاشه", "لقطه شاشه", "سكرين شوت", "screenshot") to Cmd.Global("screenshot"),
        listOf("وقف التحكم بالراس", "اوقف التحكم بالراس", "اقفل التحكم بالراس", "الغ التحكم بالراس", "وقف الماوس", "اقفل الماوس",
            "شيل المؤشر", "اخف المؤشر", "وقف مؤشر الراس", "head control off", "stop head control") to Cmd.Head(false),
        listOf("التحكم بالراس", "تحكم بالراس", "الماوس بالراس", "مؤشر الراس", "شغل الماوس", "شغل المؤشر", "اظهر المؤشر",
            "head control", "head mouse", "head pointer") to Cmd.Head(true),
        listOf("اضغط مطول هنا", "اضغط مطولا هنا", "ضغطه مطوله هنا", "ضغطه مطوله", "اضغط مطول", "دوس مطول", "long press here", "long press") to Cmd.LongHere,
        listOf("وقف الضغط", "اوقف الضغط", "ايقاف الضغط", "pause clicking") to Cmd.HeadPause(true),
        listOf("شغل الضغط", "رجع الضغط", "استانف الضغط", "resume clicking") to Cmd.HeadPause(false),
        listOf("وسط المؤشر", "وسط الماوس", "رجع المؤشر", "اضبط المؤشر", "عاير المؤشر", "center pointer", "recenter") to Cmd.HeadCenter,
        listOf("شنو تذكيراتي", "ما هي تذكيراتي", "تذكيراتي", "اقرا التذكيرات", "التذكيرات", "مواعيدي", "my reminders", "read reminders") to Cmd.ListReminders,
        listOf("دربني", "دربيني", "دربنى", "تدريب اللهجه", "درب جوني", "درب زول", "ابدا التدريب", "ابدا تدريب", "علم جوني", "علم زول", "train me", "start training") to Cmd.Train,
        listOf("سكت الصوت", "اكتم الصوت", "كتم الصوت", "اقفل الصوت", "الصامت", "mute") to Cmd.Mute(true),
        listOf("رجع الصوت", "شغل الصوت", "الغ الكتم", "فك الكتم", "unmute") to Cmd.Mute(false),
        listOf("ولع البطاريه", "شغل البطاريه", "افتح البطاريه") to Cmd.Flashlight(true),
        listOf("طفي البطاريه", "اطفي البطاريه", "اقفل البطاريه", "قفل البطاريه") to Cmd.Flashlight(false),
        listOf("ولع الكشاف", "شغل الكشاف", "افتح الكشاف", "ولع الفلاش", "شغل الفلاش", "flashlight on", "torch on", "turn on the flashlight") to Cmd.Flashlight(true),
        listOf("طفي الكشاف", "اطفي الكشاف", "اقفل الكشاف", "قفل الكشاف", "سكر الكشاف", "قفل الفلاش", "طفي الفلاش", "اطفي الفلاش", "flashlight off", "torch off", "turn off the flashlight") to Cmd.Flashlight(false),
        listOf("الاشعارات", "notifications") to Cmd.Global("notifications"),
        listOf("التطبيقات الاخيره", "التطبيقات المفتوحه", "بدل التطبيق", "recent apps", "recents") to Cmd.Global("recents"),
        listOf("الشاشه الرئيسيه", "الرئيسيه", "للرئيسيه", "الهوم", "اطلع من التطبيق", "اخرج من التطبيق", "اقفل التطبيق", "اغلق التطبيق",
            "سكر التطبيق", "close app", "close the app", "go home", "home") to Cmd.Global("home"),
        listOf("ارجع", "رجوع", "رجعني", "للخلف", "الخلف", "go back", "back") to Cmd.Global("back"),
        listOf("علي الصوت", "ارفع الصوت", "زود الصوت", "volume up", "louder") to Cmd.Volume(true),
        listOf("وطي الصوت", "اخفض الصوت", "نزل الصوت", "قلل الصوت", "volume down", "quieter") to Cmd.Volume(false),
        listOf("الساعه كم", "كم الساعه", "الزمن كم", "الوقت كم", "what time", "the time") to Cmd.Time,
        listOf("التاريخ", "تاريخ اليوم", "اليوم كم", "اليوم شنو", "النهارده شنو", "الليله كم", "كم في الشهر", "التاريخ كم", "الليله شنو", "what day", "the date", "date") to Cmd.Today,
        listOf("البطاريه", "الشحن كم", "نسبه الشحن", "بطاريتي", "شحني", "كم الشحن", "شحن التلفون", "battery") to Cmd.Battery,
        listOf("اخر رساله", "اقرا اخر رساله", "اقرا الرساله", "الرساله الاخيره", "اقرا الرساله الاخيره", "ايش الرساله", "شنو الرساله",
            "read last message", "last message", "read the message") to Cmd.ReadLast,
        listOf("اقرا المقال", "اقرا كل الشاشه", "اقرا كله", "اقرا الكل", "اقرا النص كامل", "اقرا الصفحه", "اقرا بصوت عالي", "read all", "read the article", "read aloud") to Cmd.ReadLong,
        listOf("اخف الشبكه", "شيل الشبكه", "الغ الشبكه", "اقفل الشبكه", "hide grid") to Cmd.Grid(false),
        listOf("الشبكه", "شبكه", "اظهر الشبكه", "show grid", "grid") to Cmd.Grid(true),
        listOf("اضغط هنا", "دوس هنا", "اكبس هنا", "tap here", "click here") to Cmd.GridHere,
        listOf("ابلغ المرافق", "ابلغ اهلي", "نبه المرافق", "خبر المرافق", "ارسل للمرافق", "notify caregiver", "tell my caregiver") to Cmd.Caregiver,
        listOf("افتحه", "افتحها", "افتح التطبيق مره ثانيه", "open it", "open it again") to Cmd.OpenApp("به"),
        listOf("وصف الصوره", "وصف لي الصوره", "اوصف الصوره", "اوصف لي الصوره", "صف الصوره", "صف لي الصوره", "شنو في الصوره",
            "الصوره فيها شنو", "شنو الصوره دي", "الصوره دي شنو", "شوف الصوره", "شوف لي الصوره", "شوف الشاشه", "شوف لي الشاشه",
            "وصف الشاشه بالتفصيل", "وصف لي الشاشه بالتفصيل", "describe the picture", "describe the image", "describe the photo",
            "what is in the picture", "what is in the image", "look at the screen") to Cmd.SeeScreen(""),
        listOf("قدم شويه", "قدم الفيديو", "قدم لقدام", "قدم عشره ثواني", "قدم عشر ثواني", "قدمو شويه", "اتقدم شويه",
            "skip forward", "fast forward", "forward ten seconds") to Cmd.Seek(true),
        listOf("رجع شويه", "رجع الفيديو", "رجع لورا", "رجع عشره ثواني", "رجع عشر ثواني", "رجعو شويه",
            "rewind", "back ten seconds") to Cmd.Seek(false),
        listOf("اقرا الشاشه", "اقرا لي الشاشه", "اقرا لي", "اقرا", "شنو المكتوب", "المكتوب شنو", "read screen", "read the screen", "read") to Cmd.ReadScreen,
        listOf("وين انا", "اين انا", "انا وين", "اي تطبيق", "شنو التطبيق ده", "where am i") to Cmd.Describe(true),
        listOf("ماذا علي الشاشه", "شنو في الشاشه", "شنو علي الشاشه", "صف الشاشه", "صف لي الشاشه", "وصف الشاشه",
            "اقرا الازرار", "شنو الازرار", "ماذا امامي", "describe screen", "describe the screen", "what is on screen", "what is on the screen") to Cmd.Describe(false),
        listOf("كرر", "اعد", "اعيد", "كررها", "كرر الامر", "مره ثانيه", "مره تانيه", "تاني", "repeat", "again", "do it again") to Cmd.Repeat,
        listOf("ماذا تذكر", "شنو تذكر", "what do you remember") to Cmd.Recall(""),
        listOf("لخص الشاشه", "لخص الصفحه", "لخص الرساله", "لخص المقال", "summarize the screen", "summarize this") to Cmd.AskScreen("لخص ما على الشاشة"),
        listOf("امسح الكلمه الاخيره", "احذف الكلمه الاخيره", "delete last word", "delete the last word") to Cmd.DeleteWord,
        listOf("انتهي الاملاء", "خلص الاملاء", "وقف الاملاء", "انهي الاملاء", "خلصت الكتابه", "stop dictation", "end dictation") to Cmd.Dictation(false),
        listOf("ابدا الاملاء", "وضع الكتابه", "وضع الاملاء", "املي", "start dictation", "dictation mode", "dictate") to Cmd.Dictation(true),
        listOf("ايقاف مؤقت", "وقف الفيديو", "وقف الاغنيه", "وقف الموسيقي", "pause video", "pause music", "pause", "resume video", "resume music", "استئناف", "كمل الفيديو", "كمل الاغنيه") to Cmd.Media("playpause"),
        listOf("الاغنيه التاليه", "الفيديو التالي", "غير الاغنيه", "next song", "next video", "next track", "skip song") to Cmd.Media("next"),
        listOf("الاغنيه السابقه", "الفيديو السابق", "previous song", "previous track", "previous video") to Cmd.Media("prev"),
        listOf("امسح النص", "امسح الكتابه", "امسح الكلام", "امسح", "clear text", "clear") to Cmd.ClearText,
        listOf("مساعده", "ماذا تستطيع", "بتقدر تعمل شنو", "بتعمل شنو", "الاوامر", "help", "what can you do") to Cmd.Help,
        listOf("شكرا", "مشكور", "تسلم", "thanks", "thank you") to Cmd.Chat("thanks"),
        listOf("من انت", "انت منو", "اسمك شنو", "ما اسمك", "who are you") to Cmd.Chat("who"),
        listOf("كيف حالك", "كيفك", "اخبارك", "اخبارك شنو", "how are you") to Cmd.Chat("how"),
        listOf("السلام عليكم", "مرحبا", "اهلا", "hello", "hi") to Cmd.Chat("hello"),
        listOf("يمين", "لليمين", "swipe right", "right") to Cmd.Swipe(true),
        listOf("يسار", "شمال", "لليسار", "swipe left", "left") to Cmd.Swipe(false),
        listOf("انزل", "نزل", "لتحت", "تحت", "كمل", "scroll down", "down") to Cmd.Scroll(true),
        listOf("اطلع", "طلع", "لفوق", "فوق", "scroll up", "up") to Cmd.Scroll(false),
        listOf("ارسل", "ارسلها", "ارسله", "رسلها", "رسله", "رسلو", "ابعتها", "ابعته", "ارسل الرساله", "رسل الرساله", "send", "send it") to Cmd.Send,
    )

    private val STATE_WORDS = setOf("مفتوح", "مفتوحه", "مقفول", "مقفوله", "مغلق", "مغلقه", "شغال", "شغاله", "مشغل", "مطفي", "مطفيه",
        "مضاء", "open", "closed", "on", "off", "locked")
    private val WAKE_SOUNDS = setOf("krfs", "krfz", "krbs")
    private val FILLERS = setOf("ممكن", "لو", "سمحت", "من", "فضلك", "يا", "ياخ", "ياخي", "داير", "دايره", "عايز", "عاوز",
        "اريد", "ابغي", "هسه", "هسي", "يلا", "بالله", "رجاء", "لحظه", "تكرم", "طيب", "عليك", "الله", "زول", "ابي", "بدي", "دايرين", "امش", "امشي", "ودني", "وديني", "قوم", "قومي", "اها", "اسمع", "هوي", "يازول", "please", "can", "you", "could", "hey")
    private val APP_WORDS = setOf("تطبيق", "برنامج", "التطبيق", "البرنامج", "app", "application", "the")

    /**
     * How a word sounds, written with a few Latin consonants, so that "واتساب", "واتس اب" and "WhatsApp",
     * or "قوقل", "جوجل" and "Google", come out the same. Vowels are dropped and similar sounds are merged.
     */
    fun skeleton(text: String): String {
        val s = normalize(text).replace(" ", "")
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            var out = ""
            when (c) {
                'ب', 'پ', 'b', 'p' -> out = if (c == 'p' && next == 'h') { i++; "f" } else "b"
                'ت', 'ط' -> out = "t"
                't' -> { out = "t"; if (next == 'h') i++ }
                'ث', 'س', 'ص', 'ش' -> out = "s"
                's' -> { out = "s"; if (next == 'h') i++ }
                'ج', 'خ', 'غ', 'ق', 'ك', 'گ', 'چ', 'g', 'j', 'k', 'q' -> out = "k"
                'c' -> out = when (next) { 'e', 'i', 'y' -> "s"; 'h', 'k' -> { i++; "k" } else -> "k" }
                'x' -> out = "ks"
                'د', 'ض', 'd' -> out = "d"
                'ذ', 'ز', 'ظ', 'z' -> out = "z"
                'ر', 'r' -> out = "r"
                'ف', 'ڤ', 'f', 'v' -> out = "f"
                'ل', 'l' -> out = "l"
                'م', 'm' -> out = "m"
                'ن', 'n' -> out = "n"
                else -> {}                              // vowels, و ي ا ه ح ع, digits: ignored
            }
            for (ch in out) if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length)
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Which installed app did the person mean? Returns the index in [labels], or null. */
    fun matchApp(spoken: String, labels: List<String>): Int? {
        val names = appCandidates(spoken)
        if (names.isEmpty()) return null
        val sounds = names.map { skeleton(it) }.filter { it.length >= 2 }.distinct()
        // single words of a longer name ("جوجل كروم" -> "كروم") count too, but for less
        val wordSounds = normalize(spoken).split(" ").filter { it.length >= 3 }
            .flatMap { listOfNotNull(skeleton(it.removePrefix("ال")), APP_ALIASES[it.removePrefix("ال")]?.let { a -> skeleton(a) }) }
            .filter { it.length >= 3 && it !in sounds }.distinct()
        var best = -1
        var bestScore = 0
        var bestLength = Int.MAX_VALUE
        for ((index, raw) in labels.withIndex()) {
            val label = normalize(raw)
            if (label.isEmpty()) continue
            val whole = skeleton(label)
            val parts = label.split(" ").map { skeleton(it) }
            var score = 0
            for (n in names) score = maxOf(score, when {
                label == n -> 100
                " $label ".contains(" $n ") -> 90
                n.length >= 3 && label.contains(n) -> 80
                label.length >= 3 && n.contains(label) -> 60
                else -> 0
            })
            for (k in sounds) score = maxOf(score, when {
                whole == k -> 85
                k.length >= 3 && parts.any { it == k } -> 75
                k.length >= 3 && whole.startsWith(k) -> 65
                k.length >= 4 && whole.isNotEmpty() && whole[0] == k[0] && distance(whole, k) <= (if (k.length >= 7) 2 else 1) -> 55
                k.length >= 4 && parts.any { it.length >= 4 && it[0] == k[0] && distance(it, k) <= 1 } -> 50
                parts.any { it == k } -> 45
                else -> 0
            })
            for (k in wordSounds) score = maxOf(score, when {
                whole == k -> 40
                parts.any { it == k } -> 35
                else -> 0
            })
            if (score > bestScore || (score == bestScore && score > 0 && label.length < bestLength)) {
                best = index
                bestScore = score
                bestLength = label.length
            }
        }
        return if (bestScore > 0) best else null
    }

    fun isArabic(s: String) = s.any { it in '؀'..'ۿ' }

    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            when {
                ch in 'ً'..'ْ' || ch == 'ـ' -> {}          // tashkeel, tatweel
                ch == 'أ' || ch == 'إ' || ch == 'آ' -> sb.append('ا')
                ch == 'ى' -> sb.append('ي')
                ch == 'ة' -> sb.append('ه')
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun words(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun has(t: String, vararg phrases: String) = phrases.any { " $t ".contains(" $it ") }

    fun isEmergency(text: String) = has(normalize(text), *EMERGENCY.toTypedArray())

    /** Returns the command part if the wake word was said ("" if only the wake word), else null. */
    fun stripWake(text: String): String? {
        val w = words(text)
        // a chosen everyday word like "زول" wakes only at the start ("زول افتح…", "يا زول افتح…") or at the end,
        // so "شفت زول ساكن هناك" said to someone else does not wake it
        val chosen = chosenWake
        fun everyday(i: Int) = i == 0 || i == w.size - 1 || (i == 1 && normalize(w[0]) in WAKE_LEAD)
        var i = w.indices.firstOrNull { idx ->
            val n = normalize(w[idx])
            n in WAKE || skeleton(w[idx]) in WAKE_SOUNDS || (n in chosen && everyday(idx))
        } ?: -1
        var size = 1
        if (i < 0) {                                   // written as two words, e.g. "جار فيس"
            i = (0 until w.size - 1).firstOrNull {
                skeleton(w[it] + w[it + 1]) in WAKE_SOUNDS || normalize(w[it] + w[it + 1]) in WAKE ||
                    (normalize(w[it] + w[it + 1]) in chosen && everyday(it))
            } ?: -1
            size = 2
        }
        if (i < 0) return null
        val after = w.drop(i + size)
        if (after.isNotEmpty()) return after.joinToString(" ")
        val before = w.take(i).toMutableList()
        if (before.isNotEmpty() && normalize(before.last()) in setOf("يا", "hey", "هاي", "هي")) before.removeAt(before.size - 1)
        return before.joinToString(" ")
    }

    /** If the sentence starts with one of the phrases, returns the rest of it (original spelling). */
    private fun rest(w: List<String>, vararg phrases: String): String? {
        for (p in phrases.sortedByDescending { it.length }) {
            val pw = p.split(" ")
            if (w.size > pw.size && pw.indices.all { normalize(w[it]) == pw[it] })
                return w.drop(pw.size).joinToString(" ")
        }
        return null
    }

    fun parse(text: String): Cmd? {
        if (isEmergency(text)) return Cmd.Emergency
        teachOf(text)?.let { return it }
        sudaneseSpecial(text)?.let { return it }
        val cmd = parseCore(text, false) ?: parseCore(text, true) ?: wanted(text)
        return when (cmd) {
            null -> null
            is Cmd.OpenApp -> trimTail(cmd.name).let { n -> deviceOf(n)?.let { Cmd.Home(it, true) } ?: Cmd.OpenApp(n) }
            is Cmd.Call -> Cmd.Call(trimTail(cmd.target))
            is Cmd.Home -> Cmd.Home(trimTail(cmd.device), cmd.on)
            is Cmd.Tap -> Cmd.Tap(trimTail(cmd.label))
            is Cmd.Scene -> Cmd.Scene(trimTail(cmd.name))
            is Cmd.OpenChat -> Cmd.OpenChat(trimTail(cmd.name))
            is Cmd.HomeStatus -> Cmd.HomeStatus(trimTail(cmd.device))
            is Cmd.Message -> Cmd.Message(cmd.name, dropBodyLead(cmd.body))
            is Cmd.WhatsApp -> Cmd.WhatsApp(cmd.name, dropBodyLead(cmd.body))
            is Cmd.Reply -> Cmd.Reply(dropBodyLead(cmd.body).ifEmpty { cmd.body })
            is Cmd.Type -> Cmd.Type(words(cmd.text).dropWhile { normalize(it) in BODY_LEAD || normalize(it) == "لي" }.joinToString(" ").ifEmpty { cmd.text })
            else -> cmd
        }
    }

    /**
     * Sudanese ways of saying things that do not start with a command verb:
     * "ادي ابوي رنة" (call), "الشحن فاضل كم" (battery), "كتب لي رسالة لعلي قول ليهو…" (message).
     */
    private fun sudaneseSpecial(text: String): Cmd? {
        val w = words(text).dropWhile { normalize(it) in FILLERS }
        if (w.isEmpty()) return null
        val t = normalize(w.joinToString(" "))
        // "ادي ابوي رنة" / "اضرب لي لعمر رنة"
        if (w.size >= 3 && normalize(w.last()) in setOf("رنه", "رنات", "مسكول", "مسد") &&
            normalize(w[0]) in setOf("ادي", "اديله", "اضرب", "اعمل", "ارسل", "رسل")) {
            val who = w.subList(1, w.size - 1).filter { normalize(it) !in setOf("لي", "له", "ل") }
            if (who.isNotEmpty()) return Cmd.Call(who.joinToString(" "))
        }
        // "الشحن فاضل كم", "فضلت كم في الشحن", "البطارية فاضلة قدر شنو"
        if ((t.contains("شحن") || t.contains("بطاري")) && !t.contains("ولع") && !t.contains("طفي") &&
            listOf("كم", "فاضل", "فضل", "فضلت", "قدر شنو", "قدر ايه").any { t.contains(it) }) return Cmd.Battery
        // "كتب لي رسالة لعلي قول ليهو تعال" -> a message, not typing
        val writer = setOf("اكتب", "كتب", "اكتبلي", "كتبلي")
        if (normalize(w[0]) in writer) {
            val rest = w.drop(1).dropWhile { normalize(it) in setOf("لي", "ليك") }
            if (rest.size >= 2 && normalize(rest[0]) in setOf("رساله", "مسج", "مسيج")) {
                val again = parseCore("ارسل رساله " + rest.drop(1).joinToString(" "), false)
                if (again is Cmd.Message || again is Cmd.WhatsApp) return again
            }
        }
        return null
    }

    /** A spoken name that is one of the home devices in settings: "المروحه" -> "المروحه". */
    private fun deviceOf(name: String): String? {
        val n = normalize(name).removePrefix("ال").replace(" ", "")
        return if (n.isNotEmpty() && homeDevices.any { it.replace(" ", "") == n }) name else null
    }

    /** "داير الواتساب" / "عايز اليوتيوب": the person wants an app, said without a verb. */
    private fun wanted(text: String): Cmd? {
        val w = words(text)
        val hadWant = w.takeWhile { normalize(it) in FILLERS }.any { normalize(it) in WANT }
        val rest = w.dropWhile { normalize(it) in FILLERS }
        if (rest.isEmpty() || rest.size > 3) return null
        var name = trimTail(rest.joinToString(" "))
        val n0 = normalize(name)
        if (hadWant && n0.startsWith("لل")) name = "ال" + n0.removePrefix("لل")
        else if (hadWant && n0.startsWith("ل") && n0.length > 3 && !n0.startsWith("لا")) name = n0.removePrefix("ل")
        if (hadWant && normalize(name).startsWith("علي ")) name = normalize(name).removePrefix("علي ")
        val known = appCandidates(name).any { it in APP_ALIASES.values || APP_ALIASES.containsKey(it) }
        return if (hadWant || known) Cmd.OpenApp(name) else null
    }

    /** "لما اقول جيب الواتس يعني افتح الواتساب": the person teaches a sentence by voice. */
    private val YOUTUBE = setOf("اليوتيوب", "يوتيوب", "youtube", "اليوتوب", "يوتوب")

    /**
     * "شغل في اليوتيوب عثمان حسين" / "شغل عثمان حسين في اليوتيوب" / "شغل لي فيديو طبخ" / "play cats on youtube":
     * what to look for on YouTube; the first video is then played.
     */
    private fun playVideoOf(w: List<String>): Cmd.PlayVideo? {
        rest(w, "شغل لي في اليوتيوب", "شغل في اليوتيوب", "شغل لي في يوتيوب", "شغل في يوتيوب", "شغل لي من اليوتيوب",
            "شغل من اليوتيوب", "شغل لي فيديو", "شغل فيديو", "شغل لي مقطع", "شغل مقطع", "افتح فيديو", "play on youtube", "play video")
            ?.let { q -> trimTail(q).takeIf { it.isNotBlank() }?.let { return Cmd.PlayVideo(it) } }
        val lead = rest(w, "شغل لي", "شغل", "شغلي", "play") ?: return null
        val rw = words(lead)
        if (rw.size < 3 || normalize(rw.last()) !in YOUTUBE) return null
        if (normalize(rw[rw.size - 2]) !in setOf("في", "علي", "من", "on", "in")) return null
        val q = rw.dropLast(2).joinToString(" ").trim()
        return if (q.isEmpty()) null else Cmd.PlayVideo(q)
    }

    fun teachOf(text: String): Cmd.Teach? {
        val w = words(text)
        val lead = rest(w, "لما اقول", "لما قلت", "لو قلت", "اذا قلت", "متين ما اقول", "كل ما اقول", "when i say", "if i say") ?: return null
        val rw = words(lead)
        val sep = rw.indexOfFirst { normalize(it) in setOf("يعني", "اقصد", "معناها", "معناه", "تعني", "قصدي", "means", "mean") }
        if (sep < 1 || sep >= rw.size - 1) return null
        var meaning = rw.drop(sep + 1)
        if (normalize(meaning.first()) in setOf("انك", "تعمل", "انو")) meaning = meaning.drop(1)
        if (meaning.isEmpty()) return null
        return Cmd.Teach(rw.take(sep).joinToString(" "), meaning.joinToString(" "))
    }

    private fun parseCore(text: String, wide: Boolean): Cmd? {
        val w = applyDialect(words(text).dropWhile { normalize(it) in FILLERS }, wide)
        val t = normalize(w.joinToString(" "))
        if (t.isEmpty()) return null

        // calls: checked first, because "رد على المكالمة" would otherwise look like a chat reply
        if (t in ANSWER) return Cmd.Answer
        if (t in HANGUP) return Cmd.HangUp
        if (t in SPEAKER_ON) return Cmd.Speaker(true)
        if (t in SPEAKER_OFF) return Cmd.Speaker(false)
        if (t in HUSH) return Cmd.Hush
        if (t == "هنا" || t == "here") return Cmd.GridHere
        modeOf(t)?.let { return Cmd.Mode(it) }
        devotional(t)?.let { return it }
        rest(w, "نفذ لي", "نفذ", "كلفك", "agent", "do this", "carry out")?.let { return Cmd.Agent(it) }
        rest(w, "لخص", "لخصي", "summarize", "summarise")?.let { return Cmd.AskScreen("لخص " + it) }
        rest(w, "اسال", "سؤال", "ask")?.let { return Cmd.AskScreen(it) }
        rest(w, "ماذا تذكر عن", "شنو تذكر عن", "شنو عارف عن", "what do you remember about", "what do you know about")?.let { return Cmd.Recall(it) }
        rest(w, "تذكر ان", "تذكر", "remember that", "remember")?.let { return Cmd.Remember(it) }
        rest(w, "امسح تذكير", "احذف تذكير", "الغ تذكير", "شيل تذكير", "وقف تذكير", "delete reminder", "remove reminder")
            ?.let { return Cmd.DeleteReminder(it) }
        rest(w, "انس", "انسي", "forget")?.let { return Cmd.Forget(it) }
        if (has(t, "التقط صوره", "خذ صوره", "صورني", "سيلفي", "take a photo", "take photo", "take a picture", "selfie"))
            return Cmd.Photo(numbersIn(t).firstOrNull()?.takeIf { it in 0..30 } ?: 3)
        rest(w, "افتح محادثه مع", "افتح محادثه", "افتح شات", "افتح دردشه", "open chat with", "open chat")?.let { return Cmd.OpenChat(it) }
        if (w.size in 4..8 && normalize(w[0]) in setOf("غير", "بدل", "استبدل", "change", "replace")) {
            val sep = (1 until w.size - 1).firstOrNull { normalize(w[it]) in setOf("الي", "ب", "to", "with") }
            if (sep != null && sep > 1) return Cmd.Replace(w.subList(1, sep).joinToString(" "), w.drop(sep + 1).joinToString(" "))
        }
        // home scenes: "شغل مشهد النوم"
        rest(w, "شغل مشهد", "فعل مشهد", "شغل وضع", "فعل وضع", "مشهد", "run scene", "scene")?.let { return Cmd.Scene(it) }

        playVideoOf(w)?.let { return it }
        // "ابحث في اليوتيوب عن قطط" / "search in youtube for cats"
        rest(w, "ابحث في", "ابحث علي", "ابحث ب", "search in", "search on")?.let { r ->
            val rw = words(r)
            val at = rw.indexOfFirst { normalize(it) in setOf("عن", "for") }
            if (at > 0 && at < rw.size - 1)
                return Cmd.SearchIn(rw.take(at).joinToString(" "), rw.drop(at + 1).joinToString(" "))
        }
        // "واتساب أحمد تقول وصلت" / "whatsapp ali saying hi"
        rest(w, "واتساب", "الواتساب", "واتس", "whatsapp")?.let { r ->
            val rw = words(r)
            val sep = rw.indexOfFirst { normalize(it) in MESSAGE_SEPARATORS }
            if (sep > 0 && sep < rw.size - 1) {
                val name = rw.take(sep).filter { normalize(it) !in WA_FILL }.joinToString(" ")
                if (name.isNotEmpty()) return Cmd.WhatsApp(name, rw.drop(sep + 1).joinToString(" "))
            }
        }
        // reply in the chat on screen: "رد عليها بكذا" / "reply thanks"
        rest(w, "رد عليها ب", "رد عليه ب", "رد عليهو ب", "رد عليهم ب", "رد عليها", "رد عليه", "رد عليهو", "رد عليهم", "رد ب", "رد", "reply with", "reply")?.let { r ->
            val rw = words(r).dropWhile { normalize(it) in MESSAGE_SEPARATORS || normalize(it) == "ب" }
            if (rw.isNotEmpty()) return Cmd.Reply(rw.joinToString(" "))
        }

        rest(w, "اكتب", "type", "write")?.let { return Cmd.Type(it.trimStart(':', '،', ',', ' ')) }
        rest(w, "ابحث لي عن", "ابحث عن", "ابحث", "فتش عن", "search for", "search", "google")?.let { return Cmd.Search(it) }

        rest(w, "ارسل رساله الي", "ارسل رساله ل", "ارسل رساله", "رسل رساله ل", "رسل رساله", "ابعت رساله ل",
            "ابعت رساله", "ارسل ل", "رسل ل", "ارسل", "رسل", "ابعت", "send a message to", "send message to", "send a text to", "text")?.let { r ->
            val rw = words(r)
            val sep = rw.indexOfFirst { normalize(it) in MESSAGE_SEPARATORS }
            val nameLen = if (sep > 0) sep else if (normalize(rw.first()) in WA_WORDS && rw.size > 1) 2 else 1
            val namePart = rw.take(nameLen)
            val body = if (sep > 0) rw.drop(sep + 1).joinToString(" ") else rw.drop(nameLen).joinToString(" ")
            if (namePart.any { normalize(it) in WA_WORDS }) {            // through WhatsApp, not SMS
                val name = namePart.filter { normalize(it) !in WA_WORDS && normalize(it) !in WA_FILL }.joinToString(" ")
                if (name.isNotEmpty()) return Cmd.WhatsApp(name, body)
            }
            return Cmd.Message(namePart.joinToString(" "), body)
        }
        toNumber(t)?.let { return Cmd.Tap(it.toString()) }      // a bare number: press that numbered item
        // "وقف المروحه" / "شغل النور": only for devices the person added, so "وقف الاغنيه" is not taken for a device
        rest(w, "وقف", "اوقف", "ابطل", "stop")?.let { r -> deviceOf(trimTail(r))?.let { return Cmd.Home(it, false) } }
        rest(w, "شغل", "شغل لي", "افتح", "فتح")?.let { r -> deviceOf(trimTail(r))?.let { return Cmd.Home(it, true) } }

        prayerQuestion(t)?.let { return it }

        // short fixed phrases (checked before "open X" / "tap X" so that "افتح الاشعارات" works)
        if (w.size <= 6) {
            for ((phrases, cmd) in FIXED) if (phrases.any { " $t ".contains(" $it ") }) return cmd
            alarmOrTimer(t)?.let { return it }
        } else if (w.size <= 12 && has(t, "ذكرني", "ذكريني", "remind me", "صحيني", "نبهني")) {
            alarmOrTimer(t)?.let { return it }
        }

        rest(w, "شنو حاله", "ايش حاله", "ما حاله", "حاله", "status of", "state of")?.let { return Cmd.HomeStatus(it) }
        if (w.size in 3..5 && normalize(w[0]) in setOf("هل", "is", "هلا") && normalize(w.last()) in STATE_WORDS)
            return Cmd.HomeStatus(w.drop(1).dropLast(1).joinToString(" "))
        rest(w, "اتصل علي", "اتصل ب", "اتصل", "اضرب لي", "اضرب ل", "اضرب علي", "اضرب", "ضرب لي", "ضرب علي", "ضرب",
            "دق لي علي", "دق علي", "دق لي", "دق ل", "دق", "رن علي", "رن لي", "اتكلم مع", "كلم لي", "كلم", "call")?.let { r ->
            val digits = normalize(r).filter { it.isDigit() }
            return when {
                digits.length >= 3 -> Cmd.Call(digits)
                listOf("طوارئ", "مرافق", "emergency").any { normalize(r).contains(it) } -> Cmd.Call("emergency")
                else -> Cmd.Call(r)
            }
        }
        rest(w, "اضغط علي", "اضغط", "انقر علي", "انقر", "دوس علي", "دوس", "tap on", "tap", "click on", "click", "press")
            ?.let { return Cmd.Tap(toNumber(it)?.toString() ?: it) }
        rest(w, "ولع لي", "ولع", "ولعي", "turn on the", "turn on", "switch on the", "switch on")?.let { return Cmd.Home(it, true) }
        rest(w, "طفي لي", "طفي", "اطفي", "اطفئ", "اقفل لي", "اقفل", "قفل", "سكر", "turn off the", "turn off", "switch off the", "switch off")
            ?.let { return Cmd.Home(it, false) }
        rest(w, "افتح لي", "افتح", "افتحي", "فتح", "تفتح لي", "تفتح", "تشغل لي", "تشغل", "شغل لي", "شغل", "ادخل علي", "ادخل", "خش علي", "خش", "open the", "open", "launch", "start")?.let { r ->
            val name = words(r).dropWhile { normalize(it) in APP_WORDS }.joinToString(" ")
            if (name.isNotEmpty()) return Cmd.OpenApp(name)
        }
        return nearest(t, w.size)
    }

    /**
     * Quran, adhkar, the qibla and the head keyboard. Checked early: "اكتب بالراس" is not dictation,
     * and "اقرا سورة الملك" is not reading the screen.
     */
    private fun devotional(t: String): Cmd? {
        if (has(t, "ذكرني", "ذكريني", "نبهني", "صحيني", "remind")) return null
        if (has(t, "اكتب بالراس", "اكتب براسي", "الكتابه بالراس", "كيبورد الراس", "لوحه المفاتيح", "الكيبورد", "كيبورد",
                "الكي بورد", "keyboard"))
            return Cmd.HeadKeyboard(!has(t, "اقفل", "قفل", "اخفي", "اخف", "شيل", "سكر", "ابعد", "وقف", "hide", "close"))
        if (has(t, "القبله", "qibla") || (has(t, "قبله") && has(t, "وين", "اتجاه", "وريني", "ناحيه", "جهه")))
            return Cmd.Qibla
        if (has(t, "اذكار", "الاذكار", "ازكار", "الازكار", "اذكاري", "adhkar", "azkar"))
            return Cmd.Azkar(when {
                has(t, "الصباح", "صباح", "الصبح", "morning") -> "morning"
                has(t, "المسا", "مسا", "المساء", "مساء", "العصر", "المغرب", "evening") -> "evening"
                has(t, "النوم", "نوم", "sleep") -> "sleep"
                else -> null
            })
        if (has(t, "حدث زول", "حدث التطبيق", "في تحديث", "فيه تحديث", "تحديث زول", "تحديث التطبيق", "اخر اصدار", "update zol", "check for updates"))
            return Cmd.CheckUpdate
        if (has(t, "لوحه الكلام", "لوحه التواصل", "لوحه الحكي", "talking board"))
            return Cmd.Board(!has(t, "اقفل", "قفل", "اخفي", "شيل", "سكر", "close"))
        if (has(t, "نزل", "حمل", "download")) {
            val wanted = if (has(t, "جزء عم", "جزء عمه", "السور القصيره", "السور المهمه")) Quran.ESSENTIAL else Quran.surahsIn(t)
            if (wanted.isNotEmpty()) return Cmd.QuranDownload(wanted, Quran.reciterOf(t))
        }
        val quranWord = has(t, "القران", "قران", "التلاوه", "تلاوه", "السوره", "quran")
        if (quranWord && has(t, "وقف", "اوقف", "اقفل", "قفل", "اسكت", "طفي", "بطل", "stop")) return Cmd.QuranCtl("stop")
        if (has(t, "السوره الجايه", "السوره البعدها", "السوره التاليه", "السوره البعدها", "next surah")) return Cmd.QuranCtl("next")
        if (has(t, "السوره القبلها", "السوره السابقه", "السوره الفاتت", "previous surah")) return Cmd.QuranCtl("prev")
        if (quranWord && has(t, "كمل", "واصل", "رجع", "ارجع", "resume", "continue")) return Cmd.QuranCtl("resume")
        val surahs = Quran.surahsIn(t)
        if (surahs.isNotEmpty()) return Cmd.Quran(surahs, Quran.reciterOf(t))
        if (quranWord && has(t, "شغل", "سمعني", "اسمع", "قرا", "اقرا", "اتلو", "play"))
            return Cmd.Quran(emptyList(), Quran.reciterOf(t))
        return null
    }

    private fun modeOf(t: String): String? = when {
        has(t, "وضع السياره", "وضع القياده", "car mode", "driving mode") -> "car"
        has(t, "وضع الليل", "وضع الهدوء", "night mode", "quiet mode") -> "night"
        has(t, "وضع عادي", "الوضع العادي", "وضع طبيعي", "normal mode") -> "normal"
        else -> null
    }

    private fun numbersIn(t: String) = t.split(" ").mapNotNull { toNumber(it) }

    /** "متين المغرب", "الصلاة الجاية", "مواقيت الصلاة", "كم فاضل للعصر" */
    private fun prayerQuestion(t: String): Cmd? {
        if (has(t, "ذكرني", "ذكريني", "صحيني", "نبهني", "منبه", "remind", "alarm", "اتصل", "ارسل", "اكتب")) return null
        if (has(t, "مواقيت الصلاه", "اوقات الصلاه", "مواعيد الصلاه", "اوقات الاذان", "مواقيت اليوم", "prayer times"))
            return Cmd.PrayerTime("all")
        val asking = has(t, "متين", "متي", "امتي", "وقت", "موعد", "ميعاد", "كم فاضل", "كم باقي", "فاضل كم", "باقي كم",
            "بيأذن", "بياذن", "يأذن", "ياذن", "حيأذن", "الاذان", "اذان", "when", "time")
        val p = PrayerTimes.prayerOf(t)
        if (p != null && (asking || words(t).size <= 2)) return Cmd.PrayerTime(p.name)
        if (has(t, "الصلاه الجايه", "الصلاه الجاية", "الصلاه القادمه", "الاذان الجاي", "متين الاذان", "متي الاذان",
                "الصلاه الياتها", "next prayer")) return Cmd.PrayerTime(null)
        return null
    }

    /** "منبه الساعة 7 ونص", "صحيني 6 30", "مؤقت 5 دقائق", "timer 10 minutes", "صحيني سبعة إلا ربع", "ذكرني بعد المغرب" */
    private fun alarmOrTimer(t: String): Cmd? {
        val n = numbersIn(t)
        val spoken = SpokenTime.clock(t, prayerMinutes?.let { f -> { p: PrayerTimes.Prayer -> f(p) } })
        if (!has(t, "موقت", "مؤقت", "تايمر", "عداد", "timer")) {
            SpokenTime.span(t)?.takeIf { it in 1..600 }?.let { return Cmd.Timer(it) }
        }
        if (has(t, "موقت", "مؤقت", "تايمر", "عداد", "timer")) {
            val minutes = when {
                has(t, "ساعتين") -> 120
                has(t, "نص ساعه", "نصف ساعه", "half an hour") -> 30
                has(t, "ربع ساعه") -> 15
                n.isEmpty() -> if (has(t, "ساعه", "hour")) 60 else return null
                has(t, "ساعه", "ساعات", "hour", "hours") -> n[0] * 60
                else -> n[0]
            }
            return if (minutes in 1..600) Cmd.Timer(minutes) else null
        }
        // "نبهني بعد 10 دقائق" / "remind me in an hour": a countdown, not a clock time
        if (has(t, "بعد", "after", "in") && has(t, "دقيقه", "دقيقتين", "دقايق", "دقائق", "ساعه", "ساعتين", "ساعات", "minute", "minutes", "hour", "hours")) {
            val minutes = when {
                has(t, "ساعتين") -> 120
                has(t, "دقيقتين") -> 2
                n.isEmpty() -> if (has(t, "ساعه", "hour")) 60 else return null
                has(t, "ساعه", "ساعات", "hour", "hours") -> n[0] * 60
                else -> n[0]
            }
            if (minutes in 1..600) return Cmd.Timer(minutes)
        }
        if (n.isEmpty() && spoken == null) return null
        if (has(t, "ذكرني", "ذكريني", "remind me")) {
            var hour = n.firstOrNull() ?: spoken!!.first
            var minute = when {
                n.size > 1 -> n[1]
                has(t, "ونص", "نص", "half") -> 30
                has(t, "وربع", "ربع", "quarter") -> 15
                else -> 0
            }
            if (hour < 12 && has(t, "مساء", "المساء", "العصر", "الليل", "بالليل", "pm")) hour += 12
            spoken?.let { hour = it.first; minute = it.second }
            val daily = has(t, "كل يوم", "يوميا", "every day", "daily", "كل صباح", "كل مساء", "كل ليله", "كل يوم بعد")
            val stop = setOf("الساعه", "ساعه", "عند", "كل", "at", "في", "يوميا", "daily", "every", "بعد", "قبل", "مع", "وقت",
                "بكره", "الصباح", "بالليل", "الليله")
            val label = words(t).drop(if (t.startsWith("remind me")) 2 else 1).dropWhile { it == "to" }.takeWhile { it !in stop && toNumber(it) == null }.joinToString(" ").trim()
            val what = label.let { if (it.startsWith("بال")) it.removePrefix("ب") else it }
            return if (hour in 0..23 && minute in 0..59) Cmd.Remind(hour, minute, what.ifEmpty { "تذكير" }, daily) else null
        }
        if (has(t, "منبه", "المنبه", "صحيني", "نبهني", "alarm", "wake me")) {
            var hour = n.firstOrNull() ?: spoken!!.first
            var minute = when {
                n.size > 1 -> n[1]
                has(t, "ونص", "نص", "half") -> 30
                has(t, "وربع", "ربع", "quarter") -> 15
                else -> 0
            }
            if (hour < 12 && has(t, "مساء", "المساء", "العصر", "الليل", "بالليل", "pm")) hour += 12
            spoken?.let { hour = it.first; minute = it.second }
            return if (hour in 0..23 && minute in 0..59) Cmd.Alarm(hour, minute) else null
        }
        return null
    }

    /** Last resort for a short sentence: a known phrase that sounds almost the same (one sound different). */
    private fun nearest(t: String, wordCount: Int): Cmd? {
        if (wordCount > 3) return null
        val k = skeleton(t)
        if (k.length < 4) return null
        for ((phrases, cmd) in FIXED) for (p in phrases) {
            if (cmd is Cmd.Emergency || cmd is Cmd.StopListening || cmd is Cmd.Send || cmd is Cmd.ClearText || cmd is Cmd.Mute ||
                cmd is Cmd.Train || cmd is Cmd.Bubble || cmd is Cmd.Head) continue
            val pk = skeleton(p)
            if (pk.length >= 4 && pk[0] == k[0] && distance(pk, k) <= 1) return cmd
        }
        return null
    }

    /**
     * Several commands in one sentence: "افتح الواتساب وانزل تحت", "ارجع ثم ارجع", "open camera then go back".
     * Returns an empty list when nothing is understood.
     */
    fun parseAll(text: String): List<Cmd> {
        (userPhrases[normalize(text).trim()] ?: DEFAULT_ROUTINES[normalize(text).trim()])?.let { taught -> if (normalize(taught).trim() != normalize(text).trim()) return parseAll(taught) }
        val w = words(text)
        val parts = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for ((i, token) in w.withIndex()) {
            val n = normalize(token)
            val tail = w.drop(i + 1)
            when {
                current.isNotEmpty() && n in CONNECTORS && tail.isNotEmpty() &&
                    parse(current.joinToString(" ")) != null && parse(tail.joinToString(" ")) != null -> {
                    parts.add(current)
                    current = ArrayList()
                }
                current.isNotEmpty() && n.length > 2 && n.startsWith("و") &&
                    parse(current.joinToString(" ")).let { it != null && it !is Cmd.Type && it !is Cmd.Search && it !is Cmd.Message } &&
                    parse((listOf(token.trim().drop(1)) + tail).joinToString(" ")) != null -> {
                    parts.add(current)
                    current = arrayListOf(token.trim().drop(1))
                }
                else -> current.add(token)
            }
        }
        if (current.isNotEmpty()) parts.add(current)
        if (parts.size in 2..4) {
            val all = parts.map { parse(it.joinToString(" ")) }
            if (all.all { it != null }) return all.filterNotNull()
        }
        return listOfNotNull(parse(text))
    }

    /**
     * A known command that the sentence nearly matches ("ممكن تنزل لي الشاشه" ~ "انزل"). Never run by itself:
     * the service asks "تقصد ...؟" first. Null when nothing is close enough.
     */
    fun suggest(text: String): Pair<String, Cmd>? {
        val t = normalize(text).trim()
        val tWords = words(t)
        if (t.length < 4 || tWords.size > 6) return null
        // sounds of each word without a leading verb letter, so that "تنزل" matches "انزل"
        fun stem(x: String): String { val k = skeleton(x); return if (k.length >= 4 && k[0] in "tyn") k.drop(1) else k }
        val tSk = tWords.map { stem(it) }.filter { it.length >= 2 }
        var best: Pair<String, Cmd>? = null
        var bestScore = 0.0
        for ((phrases, cmd) in FIXED) {
            if (cmd is Cmd.Emergency || cmd is Cmd.StopListening || cmd is Cmd.Send || cmd is Cmd.ClearText || cmd is Cmd.Mute || cmd is Cmd.Train) continue   // never by guess
            for (p in phrases) {
                if (p.length < 4 || isArabic(p) != isArabic(t)) continue
                val pSk = words(p).map { stem(it) }.filter { it.length >= 2 }
                if (pSk.isEmpty()) continue
                val matched = pSk.count { a -> a.length >= 3 && tSk.any { b -> a == b || (a.length >= 4 && b.length >= 4 && distance(a, b) <= 1) } }
                val tokenScore = matched.toDouble() / pSk.size * (if (tWords.size > pSk.size + 3) 0.7 else 0.9)
                val lev = 1.0 - distance(t, p).toDouble() / maxOf(t.length, p.length)
                val score = maxOf(tokenScore, lev)
                if (score > bestScore) { bestScore = score; best = p to cmd }
            }
        }
        return if (bestScore >= 0.78) best else null
    }

    /** Words to look for in installed app labels for a spoken app name. */
    fun appCandidates(name: String): List<String> {
        val full = normalize(name)
        val n = full.removePrefix("ال").removePrefix("the ").trim()
        return listOfNotNull(n, full, n.replace(" ", ""), APP_ALIASES[n], APP_ALIASES[full])
            .filter { it.isNotEmpty() }.distinct()
    }
}
