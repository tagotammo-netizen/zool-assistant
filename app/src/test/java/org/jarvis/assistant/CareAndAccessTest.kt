package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Keeping Zol alive, the talking board, falls and quiet hours, the caregiver's messages, switches and the report. */
class CareAndAccessTest {

    @Test fun phoneMakes() {
        assertEquals("Tecno / Infinix / itel", Oem.guide("TECNO MOBILE LIMITED").brand)
        assertEquals("Tecno / Infinix / itel", Oem.guide("INFINIX").brand)
        assertEquals("Xiaomi / Redmi / POCO", Oem.guide("Xiaomi", "Redmi").brand)
        assertEquals("Oppo / Realme", Oem.guide("realme").brand)
        assertEquals("Samsung", Oem.guide("samsung").brand)
        assertEquals("Honor", Oem.guide("HONOR").brand)
        assertTrue(Oem.guide("HONOR").screens.first().first.startsWith("com.hihonor"))
        assertTrue(Oem.aggressive("itel"))
        assertFalse(Oem.aggressive("Google"))
        assertTrue(Oem.guide("Google").steps.isNotEmpty())
    }

    @Test fun noticesWhenAndroidClosedZol() {
        var s = ServiceHealth.State(false, 0, emptyList())
        val boot = 1_000_000L
        s = ServiceHealth.started(s, 2_000_000, boot)
        assertEquals(0, s.stops.size)
        s = ServiceHealth.started(s, 3_000_000, boot + 20_000)       // started again, never stopped: killed
        assertEquals(1, ServiceHealth.stopsToday(s, 3_000_100))
        s = ServiceHealth.stopped(s)
        s = ServiceHealth.started(s, 4_000_000, boot)                // turned off properly first: fine
        assertEquals(1, s.stops.size)
        s = ServiceHealth.started(s, 5_000_000, boot + 3_600_000)    // the phone restarted: fine
        assertEquals(1, s.stops.size)
        assertEquals(s, ServiceHealth.decode(ServiceHealth.encode(s)))
        // an app update stops the service without asking: not counted
        var u = ServiceHealth.started(ServiceHealth.State(false, 0, emptyList()), 1_000, boot, installed = 100)
        u = ServiceHealth.started(u, 2_000, boot, installed = 200)
        assertEquals(0, u.stops.size)
        u = ServiceHealth.started(u, 3_000, boot, installed = 200)
        assertEquals(1, u.stops.size)
        assertEquals(u, ServiceHealth.decode(ServiceHealth.encode(u)))
        assertEquals(ServiceHealth.State(true, 5, listOf(7L), 0), ServiceHealth.decode("true|5|7"))      // saved by 1.2
        assertEquals(0, ServiceHealth.stopsToday(s, 3_000_000 + ServiceHealth.DAY + 1))
    }

    @Test fun talkingBoard() {
        assertTrue(Aac.PAGES.size >= 4)
        assertTrue(Aac.PAGES.all { it.cards.size == 8 })
        val mine = Aac.custom("☕ عايز شاي\nوديني البلكونة\n\n")!!
        assertEquals(Aac.Card("☕", "عايز شاي"), mine.cards[0])
        assertEquals(Aac.Card("💬", "وديني البلكونة"), mine.cards[1])
        assertNull(Aac.custom("  \n "))
        assertEquals("كلامي", Aac.pages("x").first().title)
        assertEquals("⚠ زول: عندي وجع", Aac.forCaregiver(Aac.PAGES[1].cards[0]))
    }

    private fun feed(d: FallDetector, samples: List<Triple<Long, Long, Float>>): Boolean {
        var fell = false
        for ((from, to, g) in samples) {
            var t = from
            while (t < to) { if (d.update(0f, 0f, g, t)) fell = true; t += 20 }
        }
        return fell
    }

    @Test fun falls() {
        // standing, free fall 300 ms, knock, lying still
        assertTrue(feed(FallDetector(), listOf(Triple(0L, 500L, 9.8f), Triple(500L, 800L, 1f), Triple(800L, 840L, 30f),
            Triple(840L, 1200L, 14f), Triple(1200L, 4000L, 9.8f))))
        // a knock with no fall before it (putting the phone down hard)
        assertFalse(feed(FallDetector(), listOf(Triple(0L, 500L, 9.8f), Triple(500L, 540L, 30f), Triple(540L, 4000L, 9.8f))))
        // a fall, but the person keeps moving (caught it and walked on)
        assertFalse(feed(FallDetector(), listOf(Triple(0L, 500L, 9.8f), Triple(500L, 800L, 1f), Triple(800L, 840L, 30f),
            Triple(840L, 8000L, 16f))))
        // a very short dip (a step) then a knock
        assertFalse(feed(FallDetector(), listOf(Triple(0L, 500L, 9.8f), Triple(500L, 540L, 1f), Triple(540L, 580L, 30f),
            Triple(580L, 4000L, 9.8f))))
    }

    private fun at(h: Int, m: Int = 0, day: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 19 + day, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun quietHours() {
        assertNull(Inactivity.nextCheck(at(9), 0, at(9)))
        assertEquals(at(15), Inactivity.nextCheck(at(9), 6, at(9)))
        assertEquals(at(7, 30, 1), Inactivity.nextCheck(at(19), 6, at(19)))         // would be 1 am: next morning
        assertEquals(at(7, 30), Inactivity.nextCheck(at(0), 6, at(1)))              // would be 6 am
        assertTrue(Inactivity.idle(at(9), 6, at(15)))
        assertFalse(Inactivity.idle(at(12), 6, at(15)))
    }

    @Test fun caregiverMessages() {
        assertEquals(Remote.Action.Say("أنا جاي بعد شوية"), Remote.parse("قول: أنا جاي بعد شوية"))
        assertEquals(Remote.Action.Say("اشرب الموية"), Remote.parse("قول ليه اشرب الموية"))
        assertEquals(Remote.Action.Battery, Remote.parse("البطارية"))
        assertEquals(Remote.Action.Where, Remote.parse("وين"))
        assertEquals(Remote.Action.Status, Remote.parse("طمني"))
        assertEquals(Remote.Action.Help, Remote.parse("مساعدة"))
        val r = Remote.parse("ذكره بالدوا كل يوم الساعة 9 بالليل") as Remote.Action.Run
        assertEquals(Cmd.Remind(21, 0, "الدوا", true), r.cmd)
        assertEquals(Remote.Action.Run(Cmd.Alarm(6, 0)), Remote.parse("صحيه الساعة 6 الصباح"))
        assertTrue(Remote.parse("اتصل بأحمد") is Remote.Action.Refused)
        assertTrue(Remote.parse("افتح الواتساب") is Remote.Action.Refused)
        assertNull(Remote.parse("البطارية", pin = "4321"))
        assertEquals(Remote.Action.Battery, Remote.parse("4321 البطارية", pin = "4321"))
        val topic = Remote.newTopic()
        assertTrue(topic.startsWith("zol-") && topic.length == 18)
        assertEquals("aa" to "قول: \"تمام\"\nسطر", Remote.ntfyMessage("""{"id":"aa","time":1,"event":"message","topic":"t","message":"قول: \"تمام\"\nسطر"}"""))
        assertNull(Remote.ntfyMessage("""{"id":"bb","event":"keepalive"}"""))
    }

    @Test fun switches() {
        assertEquals(SwitchScan.Key.NEXT, SwitchScan.keyOf(SwitchScan.KEYCODE_SPACE, false, false))
        assertEquals(SwitchScan.Key.SELECT, SwitchScan.keyOf(SwitchScan.KEYCODE_ENTER, false, false))
        assertEquals(SwitchScan.Key.SELECT, SwitchScan.keyOf(SwitchScan.KEYCODE_SPACE, true, false))
        assertEquals(SwitchScan.Key.NONE, SwitchScan.keyOf(SwitchScan.KEYCODE_VOLUME_DOWN, false, false))
        assertEquals(SwitchScan.Key.NEXT, SwitchScan.keyOf(SwitchScan.KEYCODE_VOLUME_DOWN, false, true))
        assertEquals(SwitchScan.Key.NONE, SwitchScan.keyOf(29, false, true))                 // the letter A
        val items = listOf(
            SwitchScan.Item(0, 100, 100, 160, "يسار فوق"), SwitchScan.Item(900, 110, 1000, 170, "يمين فوق"),
            SwitchScan.Item(0, 500, 1000, 600, "تحت"), SwitchScan.Item(2, 102, 101, 161, "مكرر"),
            SwitchScan.Item(10, 10, 15, 15, "صغير"),
        )
        assertEquals(listOf("يمين فوق", "يسار فوق", "تحت"), SwitchScan.order(items, 40, 20).map { it.label })
        val strip = SwitchScan.strip(1000, 2000, 150, true)
        assertEquals(SwitchScan.Strip.values().size, strip.size)
        assertEquals(1000, strip.first().right)
        assertEquals(0, SwitchScan.next(4, 5))
        assertEquals(-1, SwitchScan.next(0, 0))
    }

    @Test fun everyFeatureTileIsUnderstood() {
        for (tile in Features.ALL) {
            if (tile.special.isNotEmpty()) continue
            assertTrue("${tile.title}: ${tile.command}", Commands.parse(tile.command) != null)
        }
        assertEquals(Cmd.Qibla, Commands.parse(Features.ALL.first { it.title == "القبلة" }.command))
        assertTrue(Features.ALL.map { it.title }.toSet().size == Features.ALL.size)
    }

    @Test fun newCommands() {
        assertEquals(Cmd.Board(true), Commands.parse("لوحة الكلام"))
        assertEquals(Cmd.Board(false), Commands.parse("اقفل لوحة الكلام"))
        assertEquals(Cmd.QuranDownload(listOf(18), null), Commands.parse("نزل سورة الكهف"))
        assertEquals(Cmd.QuranDownload(Quran.ESSENTIAL, null), Commands.parse("نزّل جزء عم"))
        assertEquals(Cmd.Quran(listOf(18), null), Commands.parse("شغل سورة الكهف"))
        assertEquals(Cmd.Scroll(true), Commands.parse("انزل"))
        assertTrue(Commands.parse("نزل الصوت") !is Cmd.QuranDownload)
        assertEquals(42, Quran.ESSENTIAL.size)
    }

    @Test fun reportKeepsPrivateThingsOut() {
        val text = Report.build(Report.Input("1.2", "TECNO KG5", "13", listOf("خدمة زول" to "تعمل"), 2, "java.lang.Boom\n at x",
            listOf("اتصل بـ 0912345678", "موقعي https://maps.google.com/?q=15,32"),
            mapOf("engine" to "google", "ai_key" to "SECRET", "emergency_number" to "0912", "remote_topic" to "zol-abc")))
        assertTrue(text.contains("TECNO KG5") && text.contains("java.lang.Boom") && text.contains("engine=google"))
        assertTrue(text.contains("وقف فجأة اليوم: 2"))
        assertFalse(text.contains("SECRET") || text.contains("0912") || text.contains("zol-abc") || text.contains("maps.google"))
    }
}
