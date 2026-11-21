package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperFeaturesTest {

    @Test fun commands() {
        assertEquals(Cmd.VoicePrint("learn"), Commands.parse("زول اعرف صوتي"))
        assertEquals(Cmd.VoicePrint("off"), Commands.parse("زول اسمع كل الناس"))
        assertEquals(Cmd.Correct("افتح الواتساب"), Commands.parse("زول لا قصدي افتح الواتساب"))
        assertEquals(Cmd.OfflineBrain, Commands.parse("زول نزل العقل بدون نت"))
        assertEquals(Cmd.AskMemory("احمد قال لي شنو امبارح"), Commands.parse("زول احمد قال لي شنو امبارح"))
        assertEquals(Cmd.Secretary(true), Commands.parse("زول رد بدالي"))
        assertEquals(Cmd.SecretaryReport, Commands.parse("زول شنو الحصل"))
        assertEquals(Cmd.Persona("calm"), Commands.parse("زول اتكلم زي الهادي"))
        assertEquals(Cmd.Persona(""), Commands.parse("زول شخصيات زول"))
        assertEquals(Cmd.NextVoice, Commands.parse("زول غير الصوت"))
        assertEquals(Cmd.SendPhoto("أمي"), Commands.parse("زول ارسل اخر صورة لـ أمي".replace("لـ ", "ل ")))
        assertEquals(Cmd.FindPhotos("العيد"), Commands.parse("زول وريني صور العيد"))
        assertEquals(Cmd.Tv("volup"), Commands.parse("زول علي التلفزيون"))
        assertEquals(Cmd.Tv("youtube"), Commands.parse("زول شغل يوتيوب في التلفزيون"))
        assertEquals(Cmd.Tv("chup"), Commands.parse("زول القناة الجاية في التلفزيون"))
        assertEquals(Cmd.Tv("find"), Commands.parse("زول لقي التلفزيون"))
        assertEquals(Cmd.Pc("on"), Commands.parse("زول وصل التلفزيون"))           // still the Bluetooth one
        assertTrue(Commands.parse("زول علي الصوت") !is Cmd.Tv)
    }

    @Test fun voices() {
        val a = floatArrayOf(1f, 0f, 0f, 0f)
        assertEquals(1f, Speaker.cosine(a, a), 1e-6f)
        assertEquals(0f, Speaker.cosine(a, floatArrayOf(0f, 1f, 0f, 0f)), 1e-6f)
        val same = listOf(floatArrayOf(1f, 0.1f, 0f), floatArrayOf(0.9f, 0.2f, 0f), floatArrayOf(1f, 0f, 0.1f))
        assertTrue(Speaker.consistent(same))
        assertFalse(Speaker.consistent(same + listOf(floatArrayOf(0f, 0f, 1f))))
        val v = FloatArray(192) { it / 192f }
        assertEquals(192, Speaker.decode(Speaker.encode(v))!!.size)
        assertEquals(1f, Speaker.cosine(v, Speaker.decode(Speaker.encode(v))!!), 1e-3f)
        assertEquals(2, Speaker.floats(byteArrayOf(0, 0x40, 0, -0x40)).size)
    }

    @Test fun lifeLog() {
        val now = 1_800_000_000_000L
        val day = 24 * 3600_000L
        val list = listOf(
            LifeLog.Entry(now - day - 3600_000, "wa", "أحمد", "العزومة بكرة الساعة 8"),
            LifeLog.Entry(now - 3 * day, "wa", "أحمد", "كيفك"),
            LifeLog.Entry(now - 3600_000, "wa", "أمي", "اشتريت الدوا"),
        )
        assertEquals("العزومة بكرة الساعة 8", LifeLog.search(list, "احمد قال لي شنو امبارح", now).first().text)
        assertEquals("اشتريت الدوا", LifeLog.search(list, "امي قالت شنو عن الدوا", now).first().text)
        assertEquals(3, LifeLog.decode(list.joinToString("\n") { LifeLog.encode(it) }).size)
        assertTrue(LifeLog.isQuestion("احمد قال لي شنو"))
        assertFalse(LifeLog.isQuestion("افتح الواتساب"))
        assertTrue(LifeLog.looksLikePlan("بكرة الساعة 5 عندنا عزومة في البيت"))
        assertFalse(LifeLog.looksLikePlan("كيفك يا زول"))
        assertEquals(3, LifeLog.trim(list + LifeLog.Entry(now - 40 * day, "wa", "x", "قديم"), now).size)
    }

    @Test fun photos() {
        assertEquals(listOf("عيد"), Photos.terms("وريني صور العيد"))
        assertTrue(Photos.score("أطفال في العيد، Eid children", Photos.terms("العيد")) > 0)
        val c = Photos.parseCaptions("1: عرس في صالة، wedding hall\n2. حديقة البيت، garden\nكلام زايد", 3)
        assertEquals("عرس في صالة، wedding hall", c[0])
        assertEquals("حديقة البيت، garden", c[1])
        assertNull(c[2])
        assertEquals(mapOf(5L to "x"), Photos.decode(Photos.encode(mapOf(5L to "x"))))
    }

    @Test fun tv() {
        assertEquals("power", TvRemote.action("ولع التلفزيون"))
        assertEquals("voldown", TvRemote.action("وطي التلفزيون"))
        assertEquals("mute", TvRemote.action("اسكت التلفزيون"))
        assertNull(TvRemote.action("علي الصوت"))
        assertNull(TvRemote.action("وصل التلفزيون"))
        assertEquals("roku", TvRemote.identify("HTTP/1.1 200 OK\r\nST: roku:ecp\r\nLOCATION: http://192.168.1.5:8060/", "192.168.1.5")?.brand)
        assertEquals("samsung", TvRemote.identify("SERVER: Samsung-Linux/4.1 UPnP/1.0 Samsung TV", "1.2.3.4")?.brand)
        assertEquals("lg", TvRemote.identify("SERVER: WebOS/4.1.0 UPnP/1.0", "1.2.3.5")?.brand)
        assertNull(TvRemote.identify("SERVER: Linux UPnP/1.0 router", "1.2.3.1"))
        assertEquals("VolumeUp", TvRemote.rokuKey("volup"))
        assertTrue(TvRemote.samsungMessage("KEY_MUTE").contains("\"DataOfCmd\":\"KEY_MUTE\""))
        assertEquals("abc123", TvRemote.lgClientKey("""{"type":"registered","payload":{"client-key":"abc123"}}"""))
        assertTrue(TvRemote.lgRegister("k").contains("\"client-key\":\"k\""))
        val f = TvRemote.frame("hi")
        assertEquals(0x81.toByte(), f[0]); assertEquals((0x80 or 2).toByte(), f[1]); assertEquals(8, f.size)
        val big = TvRemote.frame("x".repeat(300))
        assertEquals((0x80 or 126).toByte(), big[1])
    }

    @Test fun personasAndAgent() {
        assertEquals("formal", Personas.find("رسمي")?.id)
        assertEquals("elder", Personas.find("زي الكبار")?.id)
        assertNull(Personas.find("معاي"))
        val s = Agent.parseStep("""{"action":"tap_xy","x":500,"y":250,"text":"زر البحث"}""")
        assertEquals(500, s?.x); assertEquals(250, s?.y)
        assertNull(Agent.parseStep("""{"action":"tap_xy","x":5000,"y":250}"""))
    }

    @Test fun brainFile() {
        assertEquals("b.task", OfflinePack.pickTask(listOf("a.task" to 2_000_000_000L, "b.task" to 1_500_000_000L, "c.bin" to 10L, "tiny.task" to 10L)))
        assertNull(OfflinePack.pickTask(listOf("README.md" to 100L)))
    }
}
