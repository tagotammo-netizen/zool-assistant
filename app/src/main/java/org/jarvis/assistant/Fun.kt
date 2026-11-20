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

    // ---------------------------------------------------------------- the prayer beads (المسبحة)

    /** The words counted, written without spaces (Whisper writes "الحمدلله" and "الحمد لله"). */
    val DHIKR = listOf(
        "سبحانالله" to "سبحان الله", "الحمدلله" to "الحمد لله", "اللهاكبر" to "الله أكبر",
        "لاالهالاالله" to "لا إله إلا الله", "استغفرالله" to "أستغفر الله", "عليمحمد" to "الصلاة على النبي",
    )

    /** How many times each word was said in one sentence ("سبحان الله سبحان الله" → 2). */
    fun countDhikr(heard: String): Map<String, Int> {
        val joined = Commands.normalize(heard).replace(" ", "")
        val out = LinkedHashMap<String, Int>()
        for ((key, name) in DHIKR) {
            var n = 0
            var at = joined.indexOf(key)
            while (at >= 0) { n++; at = joined.indexOf(key, at + key.length) }
            if (n > 0) out[name] = n
        }
        return out
    }

    // ---------------------------------------------------------------- the daily supplication and hadith

    /** [text], then where it is from. Short, well known, and said word for word. */
    data class Daily(val text: String, val source: String, val meaning: String = "")

    val DUAS = listOf(
        Daily("ربنا آتنا في الدنيا حسنة وفي الآخرة حسنة وقنا عذاب النار", "سورة البقرة، الآية 201"),
        Daily("رب اشرح لي صدري ويسر لي أمري", "سورة طه، الآيتين 25 و26"),
        Daily("رب زدني علما", "سورة طه، الآية 114"),
        Daily("حسبنا الله ونعم الوكيل", "سورة آل عمران، الآية 173"),
        Daily("لا إله إلا أنت سبحانك إني كنت من الظالمين", "سورة الأنبياء، الآية 87"),
        Daily("ربنا لا تزغ قلوبنا بعد إذ هديتنا وهب لنا من لدنك رحمة إنك أنت الوهاب", "سورة آل عمران، الآية 8"),
        Daily("رب إني لما أنزلت إلي من خير فقير", "سورة القصص، الآية 24"),
        Daily("ربنا ظلمنا أنفسنا وإن لم تغفر لنا وترحمنا لنكونن من الخاسرين", "سورة الأعراف، الآية 23"),
        Daily("ربنا هب لنا من أزواجنا وذرياتنا قرة أعين واجعلنا للمتقين إماما", "سورة الفرقان، الآية 74"),
        Daily("رب اغفر لي ولوالدي وللمؤمنين يوم يقوم الحساب", "سورة إبراهيم، الآية 41"),
        Daily("ربنا لا تؤاخذنا إن نسينا أو أخطأنا", "سورة البقرة، الآية 286"),
        Daily("رب ارحمهما كما ربياني صغيرا", "سورة الإسراء، الآية 24"),
    )

    val HADITHS = listOf(
        Daily("إنما الأعمال بالنيات", "متفق عليه", "أجر كل عمل على حسب نيتك فيهو."),
        Daily("الكلمة الطيبة صدقة", "متفق عليه", "الكلام الحلو للناس ليك فيهو أجر زي الصدقة."),
        Daily("من كان يؤمن بالله واليوم الآخر فليقل خيرا أو ليصمت", "متفق عليه", "يا تقول كلام كويس، يا تسكت."),
        Daily("لا يؤمن أحدكم حتى يحب لأخيه ما يحب لنفسه", "متفق عليه", "الإيمان الكامل إنك تحب للناس الخير البتحبو لنفسك."),
        Daily("المسلم من سلم المسلمون من لسانه ويده", "متفق عليه", "المسلم الحق ما بيأذي زول لا بكلامو ولا بإيدو."),
        Daily("الدين النصيحة", "رواه مسلم", "الدين إنك تكون صادق ومخلص مع الناس."),
        Daily("أحب الأعمال إلى الله أدومها وإن قل", "متفق عليه", "العمل القليل الدايم أحسن من الكتير البينقطع."),
        Daily("يسروا ولا تعسروا، وبشروا ولا تنفروا", "متفق عليه", "سهّل على الناس وما تصعّبها، وفرّحهم وما تنفّرهم."),
        Daily("ليس الشديد بالصرعة، إنما الشديد الذي يملك نفسه عند الغضب", "متفق عليه", "القوي الحقيقي البيمسك نفسو وقت الزعل."),
        Daily("كلمتان خفيفتان على اللسان، ثقيلتان في الميزان، حبيبتان إلى الرحمن: سبحان الله وبحمده، سبحان الله العظيم", "متفق عليه"),
        Daily("من سلك طريقا يلتمس فيه علما سهل الله له به طريقا إلى الجنة", "رواه مسلم", "أي زول ماشي يتعلم، الله بسهّل ليهو طريق الجنة."),
        Daily("تبسمك في وجه أخيك لك صدقة", "رواه الترمذي", "ابتسامتك للناس فيها أجر."),
        Daily("خيركم من تعلم القرآن وعلمه", "رواه البخاري", "أحسن الناس البيتعلم القرآن ويعلّمو لغيرو."),
        Daily("لا تغضب", "رواه البخاري", "وصية قصيرة: ما تزعل، لأنو الزعل بيجيب كل شر."),
    )

    /** The same one all day, a new one each day. */
    fun dailyPick(kind: String, dayOfYear: Int): Daily {
        val list = if (kind == "hadith") HADITHS else DUAS
        return list[Math.floorMod(dayOfYear, list.size)]
    }

    fun dailyText(kind: String, d: Daily): String = if (kind == "hadith")
        "حديث اليوم: قال رسول الله صلى الله عليه وسلم: «${d.text}». ${d.source}." + (if (d.meaning.isNotEmpty()) " يعني: ${d.meaning}" else "")
    else "دعاء اليوم: «${d.text}». ${d.source}."

    // ---------------------------------------------------------------- the calculator

    private val PLUS = setOf("زائد", "زايد", "زاد", "plus", "زيد")
    private val MINUS = setOf("ناقص", "minus", "مطروح", "مطروحه", "نقص")
    private val TIMES = setOf("في", "ضرب", "مضروب", "مضروبه", "times", "x")
    private val DIVIDE = setOf("علي", "قسمه", "تقسيم", "مقسوم", "مقسومه", "divided")
    private val FILL = setOf("زول", "كم", "بكم", "احسب", "احسبي", "احسبها", "لي", "يساوي", "يساوو", "تساوي", "بيساوي", "يعني",
        "شنو", "قدر", "قداش", "what", "is", "calculate", "equals", "by", "how", "much", "the", "يا")
    private val PERCENT = setOf("الميه", "المئه", "الميا", "بالميه", "بالمئه", "بالميا", "percent", "المية")
    private val SCALE = mapOf("الف" to 1_000.0, "الاف" to 1_000.0, "الفين" to 2_000.0, "مليون" to 1_000_000.0,
        "ملايين" to 1_000_000.0, "مليار" to 1_000_000_000.0, "ميه" to 100.0, "مية" to 100.0, "thousand" to 1_000.0, "million" to 1_000_000.0)

    /**
     * "كم 2500 في 3" → 7500; "15 في المية من 40 الف" → 6000; "1200 على 4 زائد 50" → 350.
     * Null when it is not plainly a sum: every word must be a number, an operation or a filler word,
     * so "ذكرني الساعة 9 في الصباح" stays a reminder.
     */
    fun calc(text: String): Double? {
        var raw = text.replace(Regex("(\\d),(\\d{3})"), "$1$2")
            .replace('٫', '.').replace(Regex("[٠-٩]")) { (it.value[0] - '٠').toString() }
        raw = raw.replace(Regex("(\\d)\\.(\\d)"), "$1 فاصله $2")
            .replace(Regex("(\\d)\\s*[x×*]\\s*(?=\\d)"), "$1 في ")
            .replace("+", " زائد ").replace("×", " في ").replace("÷", " علي ").replace("/", " علي ").replace("%", " بالميه ")
            .replace(Regex("(\\d)\\s*-\\s*(?=\\d)"), "$1 ناقص ")
        val words = Commands.normalize(raw).split(" ").filter { it.isNotEmpty() }
        // numbers and operations, in order; "%" makes "a % من b" into a * b / 100
        val nums = ArrayList<Double>()
        val ops = ArrayList<Char>()
        var expectNumber = true
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val value = w.toDoubleOrNull() ?: Commands.toNumber(w)?.toDouble() ?: SCALE[w]
            when {
                w in FILL -> {}
                value != null -> {
                    var v: Double = value
                    if (w.toDoubleOrNull() != null && i + 2 < words.size && words[i + 1] == "فاصله") {
                        words[i + 2].toDoubleOrNull()?.let { _ -> v = "$w.${words[i + 2]}".toDouble(); i += 2 }
                    }
                    if (!expectNumber && SCALE.containsKey(w)) {          // "2 الف": scales the number before it
                        nums[nums.size - 1] = nums.last() * SCALE.getValue(w)
                    } else if (!expectNumber) {
                        return null
                    } else {
                        nums.add(v); expectNumber = false
                    }
                }
                w in PERCENT || (w == "في" && i + 1 < words.size && words[i + 1] in PERCENT) -> {
                    if (expectNumber) return null
                    if (w == "في") i++
                    ops.add('%'); expectNumber = true
                    if (i + 1 < words.size && words[i + 1] in setOf("من", "of")) i++
                }
                w in PLUS -> { if (expectNumber) return null; ops.add('+'); expectNumber = true }
                w in MINUS -> { if (expectNumber) return null; ops.add('-'); expectNumber = true }
                w in TIMES -> { if (expectNumber) return null; ops.add('*'); expectNumber = true }
                w in DIVIDE -> { if (expectNumber) return null; ops.add('/'); expectNumber = true }
                else -> return null
            }
            i++
        }
        if (nums.size < 2 || ops.size != nums.size - 1) return null
        // × ÷ and % first, then + −
        val n2 = arrayListOf(nums[0])
        val o2 = ArrayList<Char>()
        for (k in ops.indices) {
            val b = nums[k + 1]
            when (ops[k]) {
                '*' -> n2[n2.size - 1] = n2.last() * b
                '/' -> { if (b == 0.0) return Double.NaN; n2[n2.size - 1] = n2.last() / b }
                '%' -> n2[n2.size - 1] = n2.last() * b / 100.0
                else -> { o2.add(ops[k]); n2.add(b) }
            }
        }
        var r = n2[0]
        for (k in o2.indices) r = if (o2[k] == '+') r + n2[k + 1] else r - n2[k + 1]
        return r
    }

    /** 7500 → "7500", 2.5 → "2.5", 1/3 → "0.33" */
    fun number(x: Double): String {
        if (x.isNaN()) return "ما بتنقسم على صفر"
        val rounded = Math.round(x * 100) / 100.0
        return if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1e15) rounded.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }
}
