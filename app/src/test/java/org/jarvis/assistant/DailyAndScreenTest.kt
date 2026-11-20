package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyAndScreenTest {

    private fun calc(s: String) = Fun.calc(s)?.let { Fun.number(it) }

    @Test fun calculator() {
        assertEquals("7500", calc("كم 2500 في 3"))
        assertEquals("7500", calc("زول 2500 ضرب 3"))
        assertEquals("6000", calc("15 في المية من 40 الف"))
        assertEquals("350", calc("1200 على 4 زائد 50"))
        assertEquals("2.5", calc("5 على 2"))
        assertEquals("7", calc("1 + 2 × 3"))
        assertEquals("1500", calc("احسب لي 2000 ناقص 500"))
        assertEquals("30000", calc("كم 3 الف في 10"))
        assertEquals("ما بتنقسم على صفر", calc("5 على 0"))
        assertEquals("0.33", calc("1 على 3"))
    }

    @Test fun notASum() {
        assertNull(Fun.calc("كم الساعة"))
        assertNull(Fun.calc("ذكرني الساعة 9 في الصباح"))
        assertNull(Fun.calc("اتصل ب 0912345678"))
        assertNull(Fun.calc("شغل سورة 18"))
        assertTrue(Commands.parse("زول كم الساعة") is Cmd.Time)
        assertEquals(Cmd.Calc("كم 2500 في 3"), Commands.parse("كم 2500 في 3"))
    }

    @Test fun beads() {
        assertEquals(mapOf("سبحان الله" to 3), Fun.countDhikr("سبحان الله سبحان الله سبحانالله"))
        assertEquals(mapOf("الحمد لله" to 1, "الله أكبر" to 2), Fun.countDhikr("الحمد لله، الله أكبر الله اكبر"))
        assertEquals(mapOf("لا إله إلا الله" to 1), Fun.countDhikr("لا إله إلا الله"))
        assertTrue(Fun.countDhikr("زول كم الساعة").isEmpty())
    }

    @Test fun commands() {
        assertEquals(Cmd.Tasbeeh(true), Commands.parse("زول المسبحة"))
        assertEquals(Cmd.Sleep(30), Commands.parse("زول نومني"))
        assertEquals(Cmd.Sleep(60), Commands.parse("زول نومني ساعة"))
        assertEquals(Cmd.Sleep(15), Commands.parse("زول نومني ربع ساعة"))
        assertEquals(Cmd.Sleep(20), Commands.parse("زول نومني 20 دقيقة"))
        assertEquals(Cmd.Daily("dua"), Commands.parse("زول دعاء اليوم"))
        assertEquals(Cmd.Daily("hadith"), Commands.parse("زول حديث اليوم"))
        assertEquals(Cmd.Brightness("up"), Commands.parse("زول علي الاضاءة"))
        assertEquals(Cmd.Brightness("max"), Commands.parse("زول الاضاءة كاملة"))
        assertEquals(Cmd.Dim(true), Commands.parse("زول غمق الشاشة"))
        assertEquals(Cmd.Rotate(false), Commands.parse("زول وقف التدوير"))
        assertEquals(Cmd.KeepScreen(true), Commands.parse("زول خلي الشاشة ما تقفل"))
        assertEquals(Cmd.KeepScreen(false), Commands.parse("زول خلي الشاشة تقفل"))
    }

    @Test fun oldOnesStay() {
        assertFalse(Commands.parse("زول اذكار النوم") is Cmd.Daily)
        assertTrue(Commands.parse("زول وضع الليل") is Cmd.Mode)
        assertTrue(Commands.parse("زول اقفل الشاشة") !is Cmd.KeepScreen)
    }

    @Test fun daily() {
        val a = Fun.dailyPick("hadith", 100)
        assertEquals(a, Fun.dailyPick("hadith", 100))
        assertTrue(Fun.dailyText("hadith", a).startsWith("حديث اليوم"))
        assertTrue(Fun.dailyText("dua", Fun.dailyPick("dua", 5)).contains("سورة"))
        assertEquals(Fun.dailyPick("dua", 0), Fun.dailyPick("dua", Fun.DUAS.size))
    }
}
