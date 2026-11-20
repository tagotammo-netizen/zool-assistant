package org.jarvis.assistant

/**
 * The word games (Sudanese riddles, a Quran quiz) and small rules for translation and voice messages,
 * kept apart from the service so they can be tested.
 */
object Fun {

    /** [answers]: any of these in what was said counts as right; [reveal]: the answer said aloud. */
    data class Question(val ask: String, val answers: List<String>, val reveal: String)

    /** Riddles in Sudanese Arabic, in the old way: "حجيتك ما بجيتك". */
    val RIDDLES = listOf(
        Question("حاجة كل ما تشيل منها تكبر؟", listOf("حفره", "الحفره", "نقره"), "الحفرة"),
        Question("عندها سنون وما بتعضّي؟", listOf("مشط", "المشط", "مشاط"), "المشط"),
        Question("بتجري وما عندها كرعين، وعندها عقارب وما بتلدغ؟", listOf("ساعه", "الساعه"), "الساعة"),
        Question("بيت ما عندو باب ولا شباك؟", listOf("بيضه", "البيضه", "بيض"), "البيضة"),
        Question("ليها عين واحدة وما بتشوف بيها؟", listOf("ابره", "الابره", "ابر"), "الإبرة"),
        Question("تاكل وما تشبع، وكان شربت الموية تموت؟", listOf("نار", "النار"), "النار"),
        Question("ليها رقبة وما عندها راس؟", listOf("قزازه", "القزازه", "زجاجه", "الزجاجه", "قاروره", "القاروره", "ازازه"), "القزازة"),
        Question("كل ما يزيد بينقص؟", listOf("عمر", "العمر"), "العمر"),
        Question("مليانة خروم وبتشيل الموية؟", listOf("اسفنجه", "الاسفنجه", "اسفنج", "الاسفنج", "سفنجه"), "الإسفنجة"),
        Question("حاجة لو قلت اسمها بتنكسر؟", listOf("سكوت", "السكوت", "صمت", "الصمت", "سكات"), "السكوت"),
        Question("إخوان كتار ساكنين في بيت واحد، وكل واحد راسو أحمر؟", listOf("كبريت", "الكبريت", "كبريته", "الكبريته", "عود"), "الكبريت"),
        Question("بتشيلك وإنت بتشيلها؟", listOf("مركوب", "المركوب", "جزمه", "الجزمه", "حذاء", "الحذاء", "نعال", "سفنجه", "شبشب"), "المركوب"),
        Question("بيكتب وما بيقرا؟", listOf("قلم", "القلم"), "القلم"),
        Question("بيبكي لمن يولّعوهو، وكل ما يبكي يقصر؟", listOf("شمعه", "الشمعه", "شمع"), "الشمعة"),
        Question("شجرة ليها اتناشر فرع، وكل فرع فيهو تلاتين ورقة؟", listOf("سنه", "السنه", "العام", "عام", "شهور"), "السنة وشهورها"),
        Question("حاجة حقتك، بس الناس بستعملوها أكتر منك؟", listOf("اسم", "اسمي", "الاسم", "اسمك"), "اسمك"),
        Question("بتجي بالليل من غير ما تناديها، وبتمشي الصباح من غير ما تسرق؟", listOf("نجوم", "النجوم", "نجم", "النجمه", "نجمه"), "النجوم"),
        Question("بيجري ما عندو كرعين، وعندو خشم وما بيتكلم؟", listOf("نيل", "النيل", "بحر", "البحر", "نهر", "النهر", "خور"), "النيل"),
        Question("بيمشي معاك في الشمس، ولمن تدخل الضل بيختفي؟", listOf("ضل", "الضل", "ظل", "الظل", "خيال"), "الضل"),
        Question("ما عندو جناحات وبيطير، وما عندو عيون وبيبكي؟", listOf("سحاب", "السحاب", "سحابه", "السحابه", "غيم", "الغيم", "مطر"), "السحاب"),
    )

    /** Facts about the Quran that every quiz answer can be checked against. */
    private val QURAN_FACTS = listOf(
        Question("ياتو سورة هي أطول سورة في القرآن؟", listOf("بقره", "البقره"), "سورة البقرة"),
        Question("ياتو سورة هي أقصر سورة في القرآن؟", listOf("كوثر", "الكوثر"), "سورة الكوثر"),
        Question("ياتو سورة ما بتبدأ بالبسملة؟", listOf("توبه", "التوبه", "براءه"), "سورة التوبة"),
        Question("ياتو سورة فيها البسملة مرتين؟", listOf("نمل", "النمل"), "سورة النمل"),
        Question("ياتو سورة مسمية على امرأة؟", listOf("مريم"), "سورة مريم"),
        Question("سورة الفاتحة كم آية؟", listOf("سبعه", "سبع", "7"), "سبعة آيات"),
        Question("سورة الإخلاص كم آية؟", listOf("اربعه", "اربع", "4"), "أربعة آيات"),
        Question("سورة الناس كم آية؟", listOf("سته", "ست", "6"), "ستة آيات"),
        Question("سورة الكوثر كم آية؟", listOf("تلاته", "ثلاثه", "تلاتة", "ثلاث", "تلات", "3"), "تلاتة آيات"),
        Question("سورة البقرة كم آية؟", listOf("286", "ميتين سته وتمانين", "مئتين وسته وثمانين"), "286 آية"),
    )

    private val NUMBER_WORDS = mapOf(
        1 to "الأولى", 2 to "التانية", 3 to "التالتة", 4 to "الرابعة", 5 to "الخامسة", 6 to "السادسة", 7 to "السابعة",
        8 to "التامنة", 9 to "التاسعة", 10 to "العاشرة",
    )

    /**
     * A quiz question: a fact from the list, or a surah's place in the Quran ("السورة رقم 18 اسمها شنو؟"),
     * from the surah list Zol already uses for playing the Quran.
     */
    fun quranQuestion(random: java.util.Random): Question {
        if (random.nextInt(3) == 0) return QURAN_FACTS[random.nextInt(QURAN_FACTS.size)]
        val n = 1 + random.nextInt(114)
        val name = Quran.name(n)
        val key = Commands.normalize(name)
        val bare = key.removePrefix("ال")
        return if (random.nextBoolean()) {
            val order = NUMBER_WORDS[n] ?: "رقم $n"
            val spoken = when (n) { 38 -> listOf("صاد"); 50 -> listOf("قاف"); 36 -> listOf("ياسين"); 20 -> listOf("طاها"); else -> emptyList() }
            Question("السورة $order في المصحف اسمها شنو؟", (listOf(key) + listOf(bare).filter { it.length > 1 && !it.startsWith(" ") } + spoken).distinct(),
                "سورة $name")
        } else {
            Question("سورة $name ترتيبها كم في المصحف؟", listOf(n.toString(), *(numberSpoken(n)).toTypedArray()), "رقم $n")
        }
    }

    /** "سبعه" for 7, so a number said in words also counts (1..10 only; larger ones come as digits). */
    private fun numberSpoken(n: Int): List<String> = when (n) {
        1 -> listOf("واحد", "الاولي"); 2 -> listOf("اتنين", "اثنين", "التانيه"); 3 -> listOf("تلاته", "ثلاثه", "التالته")
        4 -> listOf("اربعه", "الرابعه"); 5 -> listOf("خمسه", "الخامسه"); 6 -> listOf("سته", "السادسه")
        7 -> listOf("سبعه", "السابعه"); 8 -> listOf("تمانيه", "ثمانيه", "التامنه"); 9 -> listOf("تسعه", "التاسعه")
        10 -> listOf("عشره", "العاشره"); else -> emptyList()
    }

    /** Is the answer in what was said? Compared word by word, so "الكهف" does not count inside another word. */
    fun isRight(q: Question, heard: String): Boolean {
        val said = Commands.normalize(heard)
        val words = said.split(" ").filter { it.isNotEmpty() }
        val bare = words.map { it.removePrefix("ال").removePrefix("و") }
        return q.answers.any { a ->
            val n = Commands.normalize(a)
            if (n.contains(" ")) said.contains(n) else n in words || n.removePrefix("ال") in bare
        }
    }

    private val GIVE_UP = listOf("ما عارف", "ما بعرف", "ما عارفه", "ما بعرفها", "قول الاجابه", "الاجابه شنو", "استسلم", "وريني",
        "اديني الاجابه", "قول لي", "غلبتني", "غلبني", "i give up", "tell me", "dont know", "don't know")
    private val SKIP = listOf("الجاي", "سؤال تاني", "غيرو", "غيره", "لغز تاني", "next")
    private val STOP = listOf("خلاص", "كفايه", "وقف اللعب", "اقيف", "بس كده", "بس كدا", "انتهينا", "stop", "enough")

    fun givesUp(heard: String) = Commands.normalize(heard).let { h -> GIVE_UP.any { h.contains(Commands.normalize(it)) } }
    fun skips(heard: String) = Commands.normalize(heard).let { h -> SKIP.any { h.contains(Commands.normalize(it)) } }
    fun stops(heard: String) = Commands.normalize(heard).let { h -> STOP.any { h == Commands.normalize(it) || h.startsWith(Commands.normalize(it) + " ") } }

    /** How the score is said at the end. */
    fun score(right: Int, asked: Int): String = when {
        asked == 0 -> "ما لعبنا ولا سؤال. نلعب مرة تانية."
        right == asked -> "جبت $right من $asked. ما شاء الله، كلها صاح!"
        right == 0 -> "جبت صفر من $asked. ما مشكلة، المرة الجاية أحسن."
        else -> "جبت $right من $asked. شغل عديل."
    }

    private val FOUND = listOf("لقيت", "لقيتك", "لقيتو", "خلاص", "اسكت", "بس", "تمام", "هنا", "ok", "found", "stop", "enough")

    /** While the phone rings to be found: only words that mean "found it" stop it (the ring itself is heard too). */
    fun foundPhone(heard: String): Boolean {
        val w = Commands.normalize(heard).split(" ").filter { it.isNotEmpty() }.map { it.removePrefix("و") }
        return w.any { it in FOUND || it.startsWith("لقيت") }
    }

    // ---------------------------------------------------------------- voice messages

    /** A WhatsApp notification for a voice message ("🎤 رسالة صوتية (0:12)", "🎤 Voice message (0:12)"). */
    fun isVoiceNote(text: String): Boolean {
        if (text.contains("🎤")) return true
        val n = Commands.normalize(text)
        return listOf("رساله صوتيه", "مقطع صوتي", "تسجيل صوتي", "voice message", "voice note").any { n.contains(it) }
    }

    /** Words said before a name: "سجل لي رسالة صوتية لأمي" → "أمي". */
    fun voiceTarget(rest: String): String {
        val w = rest.trim().split(" ").filter { it.isNotEmpty() }
        val particles = setOf("ل", "الي", "الى", "لي", "عند", "to", "for")
        val name = if (w.size > 1 && Commands.normalize(w[0]) in particles) w.drop(1) else w
        return name.joinToString(" ")
    }

    // ---------------------------------------------------------------- translation

    /** Into which language: English for Arabic, Arabic for anything else. */
    fun translateTo(text: String): String = if (Commands.isArabic(text)) "English" else "Sudanese Arabic"
}
