package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAndTalkTest {

    @Test fun commands() {
        assertEquals(Cmd.FindPhone(true), Commands.parse("زول انت وين"))
        assertEquals(Cmd.FindPhone(true), Commands.parse("زول التلفون وين"))
        assertEquals(Cmd.FindPhone(false), Commands.parse("زول لقيتك"))
        assertEquals(Cmd.Game("riddle"), Commands.parse("زول اديني لغز"))
        assertEquals(Cmd.Game("riddle"), Commands.parse("زول احجيني"))
        assertEquals(Cmd.Game("quran"), Commands.parse("زول مسابقة قران"))
        assertEquals(Cmd.Translate("انا عايز موية"), Commands.parse("زول ترجم لي انا عايز موية بالانجليزي"))
        assertEquals(Cmd.TranslateMode(true), Commands.parse("زول وضع الترجمة"))
        assertEquals(Cmd.TranslateMode(false), Commands.parse("زول وقف الترجمة"))
        assertEquals(Cmd.VoiceNote, Commands.parse("زول اقرا الرسالة الصوتية"))
        assertEquals(Cmd.VoiceMessage("لأمي"), Commands.parse("زول سجل لأمي"))
        assertEquals(Cmd.VoiceMessage("احمد"), Commands.parse("زول ارسل رسالة صوتية لـ احمد"))
        assertEquals(Cmd.VoiceMessage("احمد"), Commands.parse("زول رسالة صوتية ل احمد"))
    }

    @Test fun oldCommandsStillWork() {
        assertEquals(Cmd.ReadNotifs, Commands.parse("زول اقرا الرسايل"))
        assertTrue(Commands.parse("زول وين انا") is Cmd.Describe)
    }

    @Test fun answers() {
        val q = Fun.RIDDLES.first { it.reveal == "المشط" }
        assertTrue(Fun.isRight(q, "المشط"))
        assertTrue(Fun.isRight(q, "دا مشط ياخ"))
        assertFalse(Fun.isRight(q, "الساعة"))
        assertTrue(Fun.givesUp("والله ما عارف"))
        assertTrue(Fun.stops("خلاص"))
        assertFalse(Fun.stops("خلاصة الكلام"))
    }

    @Test fun quranQuiz() {
        val r = java.util.Random(7)
        repeat(200) {
            val q = Fun.quranQuestion(r)
            assertTrue(q.answers.isNotEmpty())
            assertTrue(q.answers.any { a -> Fun.isRight(q, a) })     // its own answer counts as right
        }
        val kahf = Fun.Question("x", listOf("الكهف", "كهف"), "سورة الكهف")
        assertTrue(Fun.isRight(kahf, "سورة الكهف"))
    }

    @Test fun found() {
        assertTrue(Fun.foundPhone("لقيتك"))
        assertTrue(Fun.foundPhone("خلاص خلاص"))
        assertFalse(Fun.foundPhone("موسيقى"))
    }

    @Test fun voiceNotes() {
        assertTrue(Fun.isVoiceNote("🎤 رسالة صوتية (0:12)"))
        assertTrue(Fun.isVoiceNote("🎤 Voice message (0:05)"))
        assertFalse(Fun.isVoiceNote("وصلت البيت"))
        assertEquals("احمد", Fun.voiceTarget("لـ احمد".replace("لـ", "ل")))
        assertEquals("English", Fun.translateTo("انا جيعان"))
        assertEquals("Sudanese Arabic", Fun.translateTo("I am hungry"))
    }
}
