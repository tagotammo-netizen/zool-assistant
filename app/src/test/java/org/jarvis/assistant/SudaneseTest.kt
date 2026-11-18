package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sudanese dialect: commands, times said the Sudanese way, Zol's replies, prayer times. */
class SudaneseTest {

    private fun type(s: String) = Commands.parse(s)?.javaClass?.simpleName

    @Test fun everydayCommands() {
        assertEquals(Cmd.Call("ابوي"), Commands.parse("ادي ابوي رنة"))
        assertEquals(Cmd.Battery, Commands.parse("الشحن فاضل كم"))
        assertEquals(Cmd.Battery, Commands.parse("فضلت كم في الشحن"))
        assertEquals(Cmd.ReadNotifs, Commands.parse("شنو الرسايل الجات"))
        assertEquals(Cmd.ReadNotifs, Commands.parse("منو اتصل علي"))
        assertEquals(Cmd.Answer, Commands.parse("ارفع المكالمة"))
        assertEquals(Cmd.HangUp, Commands.parse("ما داير ارد"))
        assertEquals(Cmd.Speaker(true), Commands.parse("شغل الاسبيكر"))
        assertEquals(Cmd.Media("next"), Commands.parse("الغنية الجاية"))
        assertEquals(Cmd.DeleteWord, Commands.parse("امسح الكلمة دي"))
        assertEquals(Cmd.Hush, Commands.parse("اسكت شوية"))
        assertEquals(Cmd.OpenApp("الكاميرا"), Commands.parse("قوم افتح الكاميرا"))
        assertEquals("Message", type("كتب لي رسالة لعلي قول ليهو تعال"))
        assertEquals("OpenApp", type("وديني الواتساب"))
    }

    @Test fun hereOnlyWhenSaidAlone() {
        assertEquals(Cmd.GridHere, Commands.parse("هنا"))
        assertEquals(Cmd.ReadScreen, Commands.parse("شنو المكتوب هنا"))
    }

    @Test fun timesSaidTheSudaneseWay() {
        assertEquals(6 to 45, SpokenTime.clock("الساعة سبعة الا ربع"))
        assertEquals(7 to 20, SpokenTime.clock("سبعة وتلت"))
        assertEquals(7 to 25, SpokenTime.clock("سبعة ونص الا خمسة"))
        assertEquals(21 to 0, SpokenTime.clock("تسعة بالليل"))
        assertEquals(13 to 0, SpokenTime.clock("واحدة الضهر"))
        assertEquals(0 to 0, SpokenTime.clock("نص الليل"))
        assertEquals(90, SpokenTime.span("بعد ساعة ونص"))
        assertEquals(20, SpokenTime.span("بعد تلت ساعة"))
        assertEquals(Cmd.Alarm(6, 45), Commands.parse("صحيني الساعة سبعة الا ربع"))
        assertEquals(Cmd.Timer(20), Commands.parse("نبهني بعد تلت ساعة"))
    }

    @Test fun saysTimesTheSudaneseWay() {
        assertEquals("سبعة وربع الصباح", SpokenTime.say(7, 15))
        assertEquals("سبعة إلا ربع الصباح", SpokenTime.say(6, 45))
        assertEquals("ستة ونص المغرب", SpokenTime.say(18, 30))
        assertEquals("تسعة و17 دقيقة بالليل", SpokenTime.say(21, 17))
    }

    @Test fun repliesInSudaneseButQuotesStay() {
        assertEquals("ما لقيت «لم أجد» في جهات الاتصال.", Sudani.convert("لم أجد «لم أجد» في جهات الاتصال."))
        assertEquals("تمام، حأذكّرك بعد 10 دقائق.", Sudani.convert("حسناً، سأذكّرك بعد 10 دقائق."))
        assertEquals("ما في نص في الشاشة.", Sudani.convert("لا يوجد نص في الشاشة."))
        assertEquals("Battery is low", Sudani.convert("Battery is low"))
        assertEquals("الآنسة", Sudani.convert("الآنسة"))                   // only whole words change
    }

    @Test fun prayerTimesMatchPublishedKhartoumTimes() {
        // timesprayer.com, Khartoum, 18 Oct 2026: 4:24 5:42 11:35 2:56 5:27 6:37
        val m = PrayerTimes.minutes(2026, 10, 18, 15.5007, 32.5599, 2.0)
        val want = mapOf(PrayerTimes.Prayer.FAJR to 4 * 60 + 24, PrayerTimes.Prayer.SUNRISE to 5 * 60 + 42,
            PrayerTimes.Prayer.DHUHR to 11 * 60 + 35, PrayerTimes.Prayer.ASR to 14 * 60 + 56,
            PrayerTimes.Prayer.MAGHRIB to 17 * 60 + 27, PrayerTimes.Prayer.ISHA to 18 * 60 + 37)
        for ((p, at) in want) assertTrue("$p ${m[p]} vs $at", kotlin.math.abs(m.getValue(p) - at) <= 2)
    }

    @Test fun prayerQuestionsAndReminders() {
        assertEquals(Cmd.PrayerTime("MAGHRIB"), Commands.parse("متين المغرب"))
        assertEquals(Cmd.PrayerTime("ASR"), Commands.parse("كم فاضل للعصر"))
        assertEquals(Cmd.PrayerTime(null), Commands.parse("الصلاة الجاية"))
        assertEquals(Cmd.PrayerTime("all"), Commands.parse("مواقيت الصلاة"))
        assertEquals("Call", type("اتصل بالمغرب"))
        try {
            Commands.prayerMinutes = { p -> if (p == PrayerTimes.Prayer.MAGHRIB) 17 * 60 + 16 else null }
            assertEquals(Cmd.Remind(17, 31, "الدوا", true), Commands.parse("ذكرني بالدوا كل يوم بعد المغرب"))
        } finally { Commands.prayerMinutes = null }
        assertEquals("الخرطوم", PrayerTimes.city("الخرطوم")?.name)
        assertEquals("بورتسودان", PrayerTimes.city("بورت")?.name)
    }
}
