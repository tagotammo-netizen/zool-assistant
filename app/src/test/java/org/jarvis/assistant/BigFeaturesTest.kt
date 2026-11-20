package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BigFeaturesTest {

    @Test fun commands() {
        assertEquals(Cmd.OfflinePack("base"), Commands.parse("زول نزل حزمة بدون نت"))
        assertEquals(Cmd.OfflinePack("small"), Commands.parse("زول نزل الحزمة الكبيرة"))
        assertEquals(Cmd.Captions, Commands.parse("زول اكتب الكلام"))
        assertEquals(Cmd.Learn(""), Commands.parse("زول اتعلم"))
        assertEquals(Cmd.Learn("اطلب الغدا"), Commands.parse("زول اتعلم اطلب الغدا"))
        assertEquals(Cmd.LearnDone("اطلب الغدا"), Commands.parse("زول سميها اطلب الغدا"))
        assertEquals(Cmd.LearnDone(""), Commands.parse("زول خلصت"))
        assertEquals(Cmd.English, Commands.parse("زول علمني انجليزي"))
        assertEquals(Cmd.English, Commands.parse("زول اتعلم انجليزي"))
        assertEquals(Cmd.ShareDialect(false), Commands.parse("زول شارك لهجتي"))
        assertEquals(Cmd.ShareDialect(true), Commands.parse("زول رسل لهجتي للمجتمع"))
        assertEquals(Cmd.InstallDialect("دارفور"), Commands.parse("زول نزل لهجة دارفور"))
        assertEquals(Cmd.VoiceBank, Commands.parse("زول بنك الصوت"))
        assertEquals(Cmd.Pc("on"), Commands.parse("زول وصل الكمبيوتر"))
        assertEquals(Cmd.Pc("off"), Commands.parse("زول افصل الكمبيوتر"))
        assertEquals(Cmd.Pc("type", "مرحبا"), Commands.parse("زول اكتب في الكمبيوتر مرحبا"))
        assertEquals(Cmd.Eyes(true), Commands.parse("زول التحكم بالعيون"))
        assertEquals(Cmd.Eyes(false), Commands.parse("زول وقف التحكم بالعيون"))
        Commands.macroNames = setOf("اطلب الغدا")
        assertEquals(Cmd.RunMacro("اطلب الغدا"), Commands.parse("زول اطلب الغدا"))
        Commands.macroNames = emptySet()
    }

    @Test fun macros() {
        val steps = mutableListOf<Macros.Step>()
        Macros.add(steps, Macros.Step("open", "com.whatsapp"))
        Macros.add(steps, Macros.Step("click", "com.whatsapp", "محمد", "", "com.whatsapp:id/name", 10, 20))
        Macros.add(steps, Macros.Step("type", "com.whatsapp", "ج", id = "entry"))
        Macros.add(steps, Macros.Step("type", "com.whatsapp", "جاي", id = "entry"))
        assertEquals(3, steps.size)
        assertEquals("جاي", steps.last().text)
        val back = Macros.decode(Macros.encode(mapOf("رسالة لمحمد" to steps)))
        assertEquals(steps, back["رسالة لمحمد"])
        val click = steps[1]
        assertTrue(Macros.score(click, "com.whatsapp:id/name", "محمد", null) >= Macros.ENOUGH)
        assertTrue(Macros.score(click, "com.whatsapp:id/name", "أحمد", null) < Macros.ENOUGH)   // another row of the same list
        assertTrue(Macros.score(click, null, "محمد", null) >= Macros.ENOUGH)
    }

    @Test fun habits() {
        val day = 24 * 60L
        val nine = 9 * 60L
        val list = listOf(0, 1, 2, 4).map { Habits.Entry("اتصل بامي", it * day + nine + it * 5) } +
            listOf(Habits.Entry("افتح يوتيوب", 1 * day + 600), Habits.Entry("افتح يوتيوب", 3 * day + 1200))
        val h = Habits.habits(list, 5 * day + 8 * 60)
        assertEquals(1, h.size)
        assertEquals("اتصل بامي", h[0].text)
        assertNotNull(Habits.due(list, 5 * day + nine - 5, emptyMap(), emptyMap()))
        assertNull(Habits.due(list, 5 * day + 14 * 60, emptyMap(), emptyMap()))                    // not its time
        assertNull(Habits.due(list, 5 * day + nine - 5, mapOf("اتصل بامي" to 5L), emptyMap()))        // offered today
        assertNull(Habits.due(list, 5 * day + nine - 5, emptyMap(), mapOf("اتصل بامي" to 3)))         // said no 3 times
        val doneToday = list + Habits.Entry("اتصل بامي", 5 * day + nine - 30)
        assertNull(Habits.due(doneToday, 5 * day + nine - 5, emptyMap(), emptyMap()))
    }

    @Test fun dialects() {
        val text = Dialects.format("تجربة", mapOf("هسه" to "الآن"), mapOf("ودي الصوت لفوق" to "علي الصوت"))
        val p = Dialects.parse(text)!!
        assertEquals("تجربة", p.name)
        assertEquals("علي الصوت", p.phrases["ودي الصوت لفوق"])
        assertNull(Dialects.parse("كلام عادي"))
        val (merged, n) = Dialects.merge("ودي الصوت لفوق=وطي الصوت", mapOf("ودي الصوت لفوق" to "علي الصوت", "جديد" to "كم الساعة")) { Commands.normalize(it).trim() }
        assertEquals(1, n)
        assertTrue(merged.contains("ودي الصوت لفوق=وطي الصوت"))       // the person's own stays
        val idx = Dialects.parseIndex("# x\nsudan-everyday|العامية السودانية اليومية|جمل\nbad id|x")
        assertEquals(1, idx.size)
        assertEquals("sudan-everyday", Dialects.find(idx, "العامية")?.id)
    }

    @Test fun communityPackMeansRealCommands() {
        val file = java.io.File("../dialects/sudan-everyday.txt").takeIf { it.exists() } ?: java.io.File("dialects/sudan-everyday.txt")
        val pack = Dialects.parse(file.readText())!!
        for ((_, command) in pack.phrases) assertNotNull("«$command» is not a command", Commands.parse(command))
    }

    @Test fun teacher() {
        assertEquals(1.0, Teacher.grade("How are you?", "how are you").score, 0.0)
        val g = Teacher.grade("I am fine, thank you", "I am fine thanks")
        assertTrue(g.score >= 0.75)
        assertEquals(listOf("you"), g.missing)
        assertEquals("stop", Teacher.control("Stop."))
        assertEquals("stop", Teacher.control("khalas"))
        assertEquals("next", Teacher.control("next"))
        assertNull(Teacher.control("hello"))
        assertTrue(Teacher.LESSONS.all { it.phrases.size == 6 })
    }

    @Test fun keyboardAndMouse() {
        assertEquals(0 to 0x04, Hid.keyFor('a'))
        assertEquals(Hid.SHIFT to 0x04, Hid.keyFor('A'))
        assertEquals(0 to 0x27, Hid.keyFor('0'))
        assertEquals(0 to 0x2C, Hid.keyFor(' '))
        assertEquals(Hid.keyFor('h'), Hid.keyFor('ا'))         // Arabic 101: ا is on H
        assertEquals(Hid.keyFor('f'), Hid.keyFor('ب'))
        assertEquals(Hid.SHIFT to Hid.keyFor('h')!!.second, Hid.keyFor('أ'))
        assertEquals(Hid.SHIFT to 0x1E, Hid.keyFor('!'))
        assertNull(Hid.keyFor('ü'))
        assertEquals(0, Hid.speed(0.05f))
        assertTrue(Hid.speed(1f) == 24 && Hid.speed(-1f) == -24)
        assertTrue(Hid.speed(0.5f) in 1..23)
        assertEquals(4, Hid.mouse(1, 500, -500, 0).size)
        assertEquals(127.toByte(), Hid.mouse(0, 500, 0, 0)[1])
        assertEquals(0xC0.toByte(), Hid.DESCRIPTOR.last())
    }

    @Test fun eyes() {
        val g = EyeGestures()
        var t = 0L
        fun feed(l: Float, r: Float, ms: Long): EyeGestures.Event? { t += ms; return g.feed(l, r, t) }
        // an ordinary blink: nothing
        feed(0.9f, 0.9f, 30); feed(0.1f, 0.1f, 30); feed(0.1f, 0.1f, 60)
        assertNull(feed(0.9f, 0.9f, 60))
        // a wink of the left eye
        feed(0.1f, 0.9f, 30); feed(0.1f, 0.9f, 300)
        assertEquals(EyeGestures.Event.WINK_LEFT, feed(0.9f, 0.9f, 30))
        // a wink of the right eye
        feed(0.9f, 0.1f, 30); feed(0.9f, 0.1f, 400)
        assertEquals(EyeGestures.Event.WINK_RIGHT, feed(0.9f, 0.9f, 30))
        // both eyes closed a while
        feed(0.1f, 0.1f, 30)
        assertNull(feed(0.1f, 0.1f, 700))
        assertEquals(EyeGestures.Event.LONG_CLOSE, feed(0.1f, 0.1f, 900))
        assertNull(feed(0.1f, 0.1f, 500))                     // once
        assertNull(feed(0.9f, 0.9f, 30))
    }

    @Test fun offlinePack() {
        assertEquals(listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"), OfflinePack.files("base"))
        assertTrue(OfflinePack.url("small", "small-tokens.txt").endsWith("/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt"))
    }
}
