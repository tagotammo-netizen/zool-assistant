package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quran, adhkar, the qibla, and the keyboard typed with the head. */
class QuranAndKeyboardTest {

    @Test fun surahsByNameAndNumber() {
        assertEquals(114, Quran.NAMES.size)
        assertEquals(listOf(18), Quran.surahsIn("شغل سورة الكهف"))
        assertEquals(listOf(36), Quran.surahsIn("سورة يس"))
        assertEquals(listOf(36), Quran.surahsIn("سوره ياسين بصوت نورين"))
        assertEquals(listOf(3), Quran.surahsIn("اقرا سورة آل عمران"))
        assertEquals(listOf(67), Quran.surahsIn("سورة تبارك"))
        assertEquals(listOf(17), Quran.surahsIn("سورة الإسراء"))
        assertEquals(listOf(34), Quran.surahsIn("سورة سبأ"))
        assertEquals(listOf(89), Quran.surahsIn("سورة الفجر"))
        assertEquals(listOf(18), Quran.surahsIn("سورة رقم 18"))
        assertEquals(Quran.MUAWWIDHAT, Quran.surahsIn("شغل المعوذات"))
        assertEquals(emptyList<Int>(), Quran.surahsIn("متين الفجر"))
        for (i in 1..114) assertEquals(Quran.NAMES[i - 1], listOf(i), Quran.surahsIn("سورة " + Quran.NAMES[i - 1]))
    }

    @Test fun recitersAndUrls() {
        assertEquals("nourin", Quran.reciterOf("سورة يس بصوت الشيخ نورين"))
        assertEquals("basit", Quran.reciterOf("بصوت عبد الباسط"))
        assertEquals("afs", Quran.reciterOf("العفاسي"))
        assertEquals(null, Quran.reciterOf("سورة الملك"))
        assertEquals("https://server8.mp3quran.net/afs/018.mp3", Quran.url(18, "afs"))
        assertEquals("https://server16.mp3quran.net/nourin_siddig/Rewayat-Aldori-A-n-Abi-Amr/103.mp3", Quran.url(103, "nourin"))
        assertEquals("https://server7.mp3quran.net/basit/014.mp3", Quran.url(14, "basit"))
    }

    @Test fun quranCommands() {
        assertEquals(Cmd.Quran(listOf(18), null), Commands.parse("شغل سورة الكهف"))
        assertEquals(Cmd.Quran(listOf(36), "nourin"), Commands.parse("شغل سورة يس بصوت نورين"))
        assertEquals(Cmd.Quran(listOf(67), null), Commands.parse("اقرا سورة الملك"))
        assertEquals(Cmd.Quran(Quran.MUAWWIDHAT, null), Commands.parse("شغل المعوذات"))
        assertEquals(Cmd.Quran(emptyList(), null), Commands.parse("شغل القران"))
        assertEquals(Cmd.QuranCtl("stop"), Commands.parse("وقف القران"))
        assertEquals(Cmd.QuranCtl("next"), Commands.parse("السورة الجاية"))
        assertEquals(Cmd.QuranCtl("resume"), Commands.parse("كمل القران"))
        assertEquals(Cmd.Azkar("morning"), Commands.parse("اذكار الصباح"))
        assertEquals(Cmd.Azkar("evening"), Commands.parse("أذكار المسا"))
        assertEquals(Cmd.Azkar(null), Commands.parse("الاذكار"))
        assertEquals(Cmd.Qibla, Commands.parse("وين القبلة"))
        assertEquals(Cmd.Qibla, Commands.parse("القبلة وين"))
        assertEquals(Cmd.HeadKeyboard(true), Commands.parse("اكتب بالراس"))
        assertEquals(Cmd.HeadKeyboard(false), Commands.parse("اقفل الكيبورد"))
        // nothing old broken
        assertEquals(Cmd.Type("مرحبا"), Commands.parse("اكتب مرحبا"))
        assertEquals("Photo", Commands.parse("التقط صورة")?.javaClass?.simpleName)
        assertEquals(Cmd.PrayerTime("FAJR"), Commands.parse("متين الفجر"))
        assertEquals(Cmd.ReadScreen, Commands.parse("اقرا الشاشة"))
        assertEquals("Remind", Commands.parse("ذكرني بالاذكار الساعة 6")?.javaClass?.simpleName)
    }

    @Test fun adhkar() {
        val m = Azkar.script("morning")
        assertEquals("أذكار الصباح.", m.first())
        assertTrue(m.any { it.startsWith("أصبحنا وأصبح الملك لله") })
        assertTrue(m.any { it.contains("سبحان الله وبحمده. تتقال 100 مرة") })
        val three = m.first { it.startsWith("بسم الله الذي") }
        assertEquals(3, three.split("بسم الله الذي").size - 1)
        assertTrue(Azkar.script("evening").any { it.startsWith("أمسينا") })
        assertEquals("مرتين", Azkar.timesText(2))
        assertEquals("morning", Azkar.kindAt(8 * 60, 4 * 60 + 30, 15 * 60))
        assertEquals("evening", Azkar.kindAt(17 * 60, 4 * 60 + 30, 15 * 60))
        assertEquals("evening", Azkar.kindAt(2 * 60, 4 * 60 + 30, 15 * 60))
        // read word for word: the reading queue is spoken as it is, never through the Sudanese conversion
        assertTrue(m.first { it.startsWith("أصبحنا") }.contains("هذا اليوم"))
    }

    @Test fun qibla() {
        assertEquals(136.0, Qibla.bearing(30.0444, 31.2357), 1.0)          // Cairo
        val khartoum = Qibla.bearing(15.5007, 32.5599)
        assertTrue("$khartoum", khartoum in 35.0..50.0)
        assertEquals("الشمال الشرقي", Qibla.compassWord(khartoum))
        assertEquals(Qibla.Step.AHEAD, Qibla.step(Qibla.turn(40.0, 45.0)))
        assertEquals(Qibla.Step.SLIGHT_RIGHT, Qibla.step(Qibla.turn(10.0, 45.0)))
        assertEquals(Qibla.Step.LEFT, Qibla.step(Qibla.turn(140.0, 45.0)))
        assertEquals(Qibla.Step.BEHIND, Qibla.step(Qibla.turn(225.0, 45.0)))
        assertEquals(Qibla.Step.SLIGHT_LEFT, Qibla.step(Qibla.turn(350.0, 330.0)))
    }

    private fun key(label: String) = HeadKeys.LETTERS.flatten().first { it.label == label }

    @Test fun typing() {
        assertEquals("سل", HeadKeys.apply("س", key("ل")))
        assertEquals("سلام ", HeadKeys.apply("سلام", HeadKeys.Key("مسافة", HeadKeys.Kind.SPACE, value = " ")))
        assertEquals("سلام ", HeadKeys.apply("سلام ", HeadKeys.Key("مسافة", HeadKeys.Kind.SPACE, value = " ")))
        assertEquals("سلا", HeadKeys.apply("سلام", HeadKeys.Key("⌫", HeadKeys.Kind.DELETE)))
        assertEquals("كيفك ", HeadKeys.apply("كيفك يا", HeadKeys.Key("⌫ كلمة", HeadKeys.Kind.DELETE_WORD)))
        assertEquals("", HeadKeys.apply("كيفك", HeadKeys.Key("⌫ كلمة", HeadKeys.Kind.DELETE_WORD)))
        assertEquals("السلام عليكم ", HeadKeys.apply("السلام عل", HeadKeys.Key("عليكم", HeadKeys.Kind.SUGGEST)))
        assertEquals("تمام؟ ", HeadKeys.apply("تمام ", key("؟")))
        assertEquals("👍".length - 2, HeadKeys.apply("👍", HeadKeys.Key("⌫", HeadKeys.Kind.DELETE)).length)
    }

    @Test fun keyboardLayout() {
        val rows = HeadKeys.rows(false, listOf("a", "b", "c"))
        assertEquals(7, rows.size)
        val boxes = HeadKeys.layout(rows, 1080f, 100f, 150f, 120f)
        assertEquals(rows.sumOf { it.size }, boxes.size)
        // the first letter, alif, is on the right of the first letter row
        val alif = boxes.first { it.first.label == "ا" }.second
        assertEquals(1080f, alif.right, 0.5f)
        assertEquals(100f + 120f + 150f, alif.top, 0.5f)
        for (row in rows) assertTrue(row.size <= 8)
        assertEquals(36, HeadKeys.LETTERS.flatten().count { it.label.length == 1 && it.label[0].code in 0x621..0x64A })
        // keys do not overlap
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i].second; val b = boxes[j].second
            assertFalse(a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f)
        }
    }

    @Test fun holdingOnAKey() {
        val d = KeyDwell(900)
        assertEquals(false, d.update(3, 0).second)
        assertEquals(0.5f, d.update(3, 450).first, 0.01f)
        assertEquals(true, d.update(3, 900).second)        // typed
        assertEquals(false, d.update(3, 1500).second)      // not again at once
        assertEquals(true, d.update(3, 2100).second)       // staying on it types it again ("لل")
        assertEquals(false, d.update(4, 2200).second)      // moving on starts over
        assertEquals(true, d.update(4, 3100).second)
        assertEquals(0f, d.update(-1, 3200).first)
    }

    @Test fun suggestions() {
        assertTrue(WordPredict.suggest("السلام ", emptyMap()).contains("عليكم"))
        assertEquals("الله", WordPredict.suggest("ان شاء ", emptyMap()).first())
        assertTrue(WordPredict.suggest("كي", emptyMap()).any { it.startsWith("كي") })
        val learned = HashMap<String, Int>()
        WordPredict.learn("وصلت البيت يا امي", learned)
        WordPredict.learn("وصلت البيت", learned)
        assertEquals("وصلت", WordPredict.suggest("وص", learned).first())
        assertEquals("البيت", WordPredict.suggest("وصلت ", learned).first())
        WordPredict.learnLast("مرحب عصام ", learned)
        assertEquals(1, learned["عصام"])
        assertEquals(1, learned["مرحب عصام"])
        assertEquals(learned, WordPredict.decode(WordPredict.encode(learned)))
        assertTrue(WordPredict.suggest("", emptyMap()).isNotEmpty())
    }
}
