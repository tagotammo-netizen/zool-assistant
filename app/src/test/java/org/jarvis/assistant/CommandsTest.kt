package org.jarvis.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser tests: they run on the computer with `gradlew test`, no phone needed. */
class CommandsTest {

    @After fun reset() { Commands.userPhrases = emptyMap() }

    private fun one(text: String): Cmd {
        val all = Commands.parseAll(text)
        assertEquals("«$text» should give exactly one command, got $all", 1, all.size)
        return all[0]
    }

    @Test fun navigation() {
        assertEquals(Cmd.Scroll(true), one("انزل"))
        assertEquals(Cmd.Scroll(true), one("scroll down"))
        assertEquals(Cmd.Global("back"), one("ارجع"))
        assertEquals(Cmd.Global("back"), one("go back"))
        assertEquals(Cmd.Global("home"), one("الرئيسية"))
        assertEquals(Cmd.Global("notifications"), one("الاشعارات"))
    }

    @Test fun appsAndSearch() {
        assertEquals(Cmd.OpenApp("youtube"), one("open youtube"))
        assertEquals(Cmd.Search("قطط"), one("ابحث عن قطط"))
    }

    @Test fun chainedCommands() {
        assertEquals(listOf(Cmd.OpenApp("الواتساب"), Cmd.Scroll(true)), Commands.parseAll("افتح الواتساب وانزل"))
    }

    @Test fun calls() {
        for (s in listOf("اجب", "رد على المكالمة", "answer")) assertEquals(s, Cmd.Answer, one(s))
        for (s in listOf("أنهِ المكالمة", "ارفض", "hang up")) assertEquals(s, Cmd.HangUp, one(s))
        assertEquals(Cmd.Speaker(true), one("شغل المكبر"))
    }

    @Test fun replyIsNotConfusedWithAnsweringACall() {
        assertEquals(Cmd.Reply("بشكرا"), one("رد بشكرا"))
        assertEquals(Cmd.Answer, one("رد على المكالمة"))
    }

    @Test fun notificationsAndScenes() {
        assertEquals(Cmd.ReadNotifs, one("اقرأ الإشعارات"))
        assertEquals(Cmd.ReadNotifs, one("read notifications"))
        assertEquals(Cmd.Scene("النوم"), one("شغل مشهد النوم"))
    }

    @Test fun flashlightAndHomeDevices() {
        assertEquals(Cmd.Flashlight(true), one("شغل الكشاف"))
        assertEquals(Cmd.Flashlight(false), one("قفل الكشاف"))
        assertEquals(Cmd.Home("المروحة", false), one("طفي المروحة"))
        assertEquals(Cmd.Home("الضو", true), one("ولع الضو"))
    }

    @Test fun messagingAndTimers() {
        assertEquals(Cmd.WhatsApp("لأحمد", "وصلت"), one("ارسل لأحمد واتساب تقول وصلت"))
        assertEquals(Cmd.Timer(10), one("نبهني بعد 10 دقائق"))
        assertEquals(Cmd.ReadLast, one("اقرأ آخر رسالة"))
        assertEquals(Cmd.Repeat, one("كرر"))
    }

    @Test fun wakeWordJohnny() {
        for (w in listOf("جوني", "جونى", "جون", "Johnny", "jonny", "يا جوني", "جو ني"))
            assertEquals(w, "افتح الواتساب", Commands.stripWake("$w افتح الواتساب")?.trim()?.let { it })
        assertEquals("", Commands.stripWake("جوني"))
        assertNull(Commands.stripWake("افتح الواتساب"))
    }

    @Test fun gridReaderRemindersAndHome() {
        assertEquals(Cmd.Grid(true), one("الشبكة"))
        assertEquals(Cmd.Grid(false), one("اخف الشبكة"))
        assertEquals(Cmd.GridHere, one("هنا"))
        assertEquals(Cmd.ReadLong, one("اقرأ المقال"))
        assertEquals(Cmd.ReadScreen, one("اقرأ"))
        assertEquals(Cmd.Caregiver, one("ابلغ المرافق"))
        assertEquals(Cmd.OpenApp("به"), one("افتحه"))
        assertEquals(Cmd.HomeStatus("المروحة"), one("حالة المروحة"))
        assertEquals(Cmd.HomeStatus("الباب"), one("هل الباب مفتوح"))
        assertEquals(Cmd.Remind(20, 0, "الدواء", true), one("ذكرني بالدواء كل يوم الساعة 8 مساء"))
        assertEquals(Cmd.Remind(9, 0, "الدواء", false), one("ذكرني بالدواء الساعة 9"))
        assertEquals(Cmd.Timer(10), one("نبهني بعد 10 دقائق"))
        assertEquals(Cmd.Alarm(7, 0), one("نبهني الساعة 7"))
    }

    @Test fun mediaCameraChatDictationModes() {
        assertEquals(Cmd.Media("playpause"), one("ايقاف مؤقت"))
        assertEquals(Cmd.Media("next"), one("الأغنية التالية"))
        assertEquals(Cmd.Media("prev"), one("الأغنية السابقة"))
        assertEquals(Cmd.Photo(3), one("التقط صورة"))
        assertEquals(Cmd.Photo(5), one("التقط صورة بعد 5 ثواني"))
        assertEquals(Cmd.OpenChat("أحمد"), one("افتح محادثة أحمد"))
        assertEquals(Cmd.Dictation(true), one("ابدأ الإملاء"))
        assertEquals(Cmd.Dictation(false), one("انتهى الإملاء"))
        assertEquals(Cmd.DeleteWord, one("امسح الكلمة الأخيرة"))
        assertEquals(Cmd.ClearText, one("امسح"))
        assertEquals(Cmd.Replace("سلام", "مرحبا"), one("غير سلام الى مرحبا"))
        assertEquals(Cmd.Mode("car"), one("وضع السيارة"))
        assertEquals(Cmd.Mode("car"), one("شغل وضع السيارة"))
        assertEquals(Cmd.Mode("night"), one("وضع الليل"))
        assertEquals(Cmd.Mode("normal"), one("وضع عادي"))
        assertEquals(Cmd.OpenApp("الكاميرا"), one("افتح الكاميرا"))
    }

    @Test fun agentMemoryAndScreenQuestions() {
        assertEquals(Cmd.Agent("ارسل لأحمد أنني تأخرت عشر دقائق"), one("نفذ ارسل لأحمد أنني تأخرت عشر دقائق"))
        assertEquals(Cmd.AskScreen("لخص الرسالة"), one("لخص الرسالة"))
        assertEquals(Cmd.AskScreen("كم سعر هذا المنتج"), one("اسأل كم سعر هذا المنتج"))
        assertEquals(Cmd.Remember("أمي هي فاطمة"), one("تذكر أن أمي هي فاطمة"))
        assertEquals(Cmd.Recall("أمي"), one("ماذا تذكر عن أمي"))
        assertEquals(Cmd.Forget("أمي"), one("انس أمي"))
    }

    @Test fun agentProtocol() {
        assertEquals(Agent.Step("tap", 4), Agent.parseStep("```json\n{\"action\":\"tap\",\"n\":4}\n```"))
        assertEquals(Agent.Step("type", 2, "مرحبا"), Agent.parseStep("<think>hmm</think>{\"action\":\"type\",\"n\":2,\"text\":\"مرحبا\"}"))
        assertEquals(Agent.Step("done", 0, "تم"), Agent.parseStep("{\"action\": \"done\", \"text\": \"تم\"}"))
        assertNull(Agent.parseStep("{\"action\":\"tap\"}"))               // a tap needs an element number
        assertNull(Agent.parseStep("{\"action\":\"format_disk\"}"))        // only whitelisted actions
        assertNull(Agent.parseStep("no json here"))
        assertTrue(Agent.isRisky("Send") && Agent.isRisky("إرسال") && Agent.isRisky("Pay now"))
        assertTrue(!Agent.isRisky("Next") && !Agent.isRisky("Search"))
    }

    @Test fun smartModeReply() {
        assertEquals(Agent.Reply("افتح الواتساب", "", ""), Agent.parseReply("{\"command\":\"افتح الواتساب\",\"reply\":\"\",\"agent\":\"\"}"))
        assertEquals("احجز لي موعدا", Agent.parseReply("{\"command\":\"\",\"reply\":\"\",\"agent\":\"احجز لي موعدا\"}").agent)
        assertEquals("مرحبا بك", Agent.parseReply("مرحبا بك").reply)
    }

    @Test fun nicknamesAndNotes() {
        assertEquals("أمي" to "فاطمة", Agent.aliasOf("أمي هي فاطمة"))
        assertNull(Agent.aliasOf("دوائي الصباحي حبة بيضاء"))
        assertTrue(Agent.matches("أمي=فاطمة", "أمي"))
        assertTrue(!Agent.matches("الدكتور علي", "أمي"))
    }

    @Test fun morningRoutineRunsSeveralCommands() {
        assertEquals(listOf(Cmd.Time, Cmd.Today, Cmd.Battery, Cmd.ReadNotifs), Commands.parseAll("صباح الخير"))
    }

    /** The same table is checked on the PC by test_shared_cases.py, so both assistants understand the same phrases. */
    @Test fun sharedPhraseTable() {
        val text = javaClass.classLoader!!.getResourceAsStream("commands_cases.tsv")!!.bufferedReader().readText()
        var checked = 0
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (want, phrase, platforms) = line.split("\t")
            if (platforms == "pc") continue
            val got = when (val c = Commands.parseAll(phrase).singleOrNull()) {
                is Cmd.Scroll -> if (c.down) "scroll_down" else "scroll_up"
                is Cmd.Volume -> if (c.up) "volume_up" else "volume_down"
                is Cmd.Search -> "search:" + c.query
                Cmd.Time -> "time"
                else -> c.toString()
            }
            assertEquals(phrase, want, got)
            checked++
        }
        assertTrue(checked > 10)
    }

    @Test fun emergencyNeedsNoWakeWord() {
        assertEquals(Cmd.Emergency, one("ساعدوني"))
    }

    @Test fun nonsenseIsNotACommand() {
        assertTrue(Commands.parseAll("blabla qwerty zzz").isEmpty())
        assertNull(Commands.suggest("blabla qwerty zzz"))
    }

    @Test fun teachingASentenceWorks() {
        Commands.userPhrases = Commands.parsePhrases("شغل لي الواتس=افتح الواتساب")
        assertEquals(listOf(Cmd.OpenApp("الواتساب")), Commands.parseAll("شغل لي الواتس"))
    }

    @Test fun fuzzySuggestionAsksInsteadOfActing() {
        val guess = Commands.suggest("ممكن تنزل لي الشاشة")
        assertNotNull(guess)
        assertEquals(Cmd.Scroll(true), guess!!.second)
    }

    @Test fun unrelatedSentenceGivesNoSuggestion() {
        assertNull(Commands.suggest("كيف الطقس اليوم"))
    }

    // ---- Sudanese dialect

    @Test fun sudaneseCallWords() {
        assertEquals(Cmd.Call("أحمد"), Commands.parse("دق لي على أحمد"))
        assertEquals(Cmd.Call("ابوي"), Commands.parse("دق لي على ابوي عليك الله"))
        assertEquals(Cmd.Call("أمي"), Commands.parse("داير أكلم أمي"))
    }

    @Test fun politeWordsAroundAppNames() {
        assertEquals(Cmd.OpenApp("الواتساب"), Commands.parse("عليك الله افتح الواتساب"))
        assertEquals(Cmd.OpenApp("اليوتيوب"), Commands.parse("افتح لي اليوتيوب ياخ"))
        assertEquals(Cmd.OpenApp("الواتساب"), Commands.parse("داير الواتساب"))
    }

    @Test fun messageBodyLosesDialectLead() {
        assertEquals(Cmd.Message("لأحمد", "أنا جاي"), Commands.parse("رسل لأحمد قول ليهو أنا جاي"))
        assertEquals(Cmd.Reply("شكرا"), Commands.parse("رد عليهو قول ليهو شكرا"))
        assertEquals(Cmd.Type("انا جاي ان شاء الله"), Commands.parse("اكتب لي انا جاي ان شاء الله"))
    }

    @Test fun knownDevicesAreSwitchedNotOpened() {
        Commands.homeDevices = setOf("مروحه")
        try {
            assertEquals(Cmd.Home("المروحة", true), Commands.parse("شغل المروحة يا زول"))
            assertEquals(Cmd.Home("المروحة", false), Commands.parse("وقف المروحة"))
        } finally { Commands.homeDevices = emptySet() }
    }

    @Test fun sudaneseBatteryIsTheFlashlight() {
        assertEquals(Cmd.Flashlight(true), Commands.parse("ولع البطارية"))
        assertEquals(Cmd.Battery, Commands.parse("بطاريتي كم"))
    }

    @Test fun hushOnlyAsWholeSentence() {
        assertEquals(Cmd.Hush, Commands.parse("خلاص"))
        assertEquals(Cmd.Mute(true), Commands.parse("سكت الصوت"))
        assertEquals(Cmd.Global("home"), Commands.parse("امش للرئيسية"))
    }

    @Test fun teachingByVoice() {
        assertEquals(Cmd.Teach("جيب الواتس", "افتح الواتساب"), Commands.parse("لما أقول جيب الواتس يعني افتح الواتساب"))
        assertEquals(Cmd.Train, Commands.parse("دربني"))
        assertNull(Commands.teachOf("يعني افتح الواتساب"))
    }
}
