package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class HeadAndProactiveTest {

    /** A tracker that has finished calibrating with the head straight (0, 0). */
    private fun calibrated(t0: Long = 0L): HeadTracker {
        val h = HeadTracker(1000, 2000, 1f)
        repeat(HeadTracker.CALIBRATION_FRAMES) { h.update(0f, 0f, 1f, 1f, t0 + it * 33L) }
        assertTrue(h.calibrated)
        return h
    }

    @Test fun headStillKeepsPointerStill() {
        val h = calibrated()
        var t = 1000L
        repeat(20) { h.update(2f, -2f, 1f, 1f, t); t += 33 }       // inside the dead zone
        assertEquals(500f, h.x, 0.01f)
        assertEquals(1000f, h.y, 0.01f)
    }

    @Test fun turningRightMovesRightAndLookingUpMovesUp() {
        val h = calibrated()
        var t = 1000L
        // the person turns to their right: ML Kit reports a negative yaw. Looking up: positive pitch.
        repeat(30) { h.update(-15f, 12f, 1f, 1f, t); t += 33 }
        assertTrue("x=${h.x}", h.x > 600f)
        assertTrue("y=${h.y}", h.y < 900f)
    }

    @Test fun invertSwapsHorizontalDirection() {
        val h = calibrated().apply { invertX = true }
        var t = 1000L
        repeat(30) { h.update(-15f, 0f, 1f, 1f, t); t += 33 }
        assertTrue(h.x < 400f)
    }

    @Test fun pointerStaysOnScreen() {
        val h = calibrated()
        var t = 1000L
        repeat(400) { h.update(-40f, 40f, 1f, 1f, t); t += 33 }
        assertEquals(999f, h.x, 0.01f)
        assertEquals(0f, h.y, 0.01f)
    }

    @Test fun holdingStillTapsOnceThenNeedsToMove() {
        val h = calibrated().apply { dwellMs = 1000L }
        var t = 1000L
        repeat(15) { h.update(-15f, 0f, 1f, 1f, t); t += 33 }       // move somewhere first
        var taps = 0
        repeat(150) { if (h.update(0f, 0f, 1f, 1f, t).tap) taps++; t += 33 }   // ~5 s still
        assertEquals(1, taps)
    }

    @Test fun noTapRightAfterCentring() {
        val h = calibrated().apply { dwellMs = 500L }
        var t = 1000L
        var taps = 0
        repeat(100) { if (h.update(0f, 0f, 1f, 1f, t).tap) taps++; t += 33 }
        assertEquals(0, taps)
    }

    @Test fun deliberateBlinkTapsButNaturalBlinkDoesNot() {
        val h = calibrated().apply { dwellOn = false; blinkTap = true }
        var t = 1000L
        fun frames(n: Int, eyes: Float): Int { var taps = 0; repeat(n) { if (h.update(0f, 0f, eyes, eyes, t).tap) taps++; t += 33 }; return taps }
        frames(10, 1f)
        assertEquals(0, frames(4, 0.05f) + frames(3, 1f))     // ~130 ms: a natural blink
        assertEquals(1, frames(18, 0.05f) + frames(3, 1f))    // ~600 ms: on purpose
        assertEquals(0, frames(60, 0.05f) + frames(3, 1f))    // 2 s: resting the eyes
    }

    @Test fun lostFaceIsReportedAfterAMoment() {
        val h = calibrated()
        assertTrue(h.update(null, null, null, null, 1000L).face)
        assertFalse(h.update(null, null, null, null, 3000L).face)
    }

    @Test fun remindersParse() {
        val list = ProactivePlan.parseReminders("08:00=الدواء\n8:30 مساء=دواء الضغط\nخطأ\n25:00=لا")
        assertEquals(listOf(ProactivePlan.Reminder(8, 0, "الدواء"), ProactivePlan.Reminder(20, 30, "دواء الضغط")), list)
        assertEquals("08:00=الدواء", ProactivePlan.format(list[0]))
    }

    @Test fun nextReminderIsTodayOrTomorrow() {
        val now = Calendar.getInstance().apply { set(2026, 10, 17, 9, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val later = Calendar.getInstance().apply { timeInMillis = ProactivePlan.nextAt(now, 20, 0) }
        assertEquals(17, later.get(Calendar.DAY_OF_MONTH))
        val passed = Calendar.getInstance().apply { timeInMillis = ProactivePlan.nextAt(now, 8, 0) }
        assertEquals(18, passed.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun importantPeople() {
        val people = listOf("أمي", "أحمد علي")
        assertTrue(ProactivePlan.isImportant("أمي ❤", people))
        assertTrue(ProactivePlan.isImportant("احمد علي", people))
        assertFalse(ProactivePlan.isImportant("محمد", people))
        assertFalse(ProactivePlan.isImportant("", people))
    }

    @Test fun batteryWarnsOncePerStep() {
        assertEquals(20, ProactivePlan.batteryWarning(19, false, 101))
        assertNull(ProactivePlan.batteryWarning(18, false, 20))
        assertEquals(10, ProactivePlan.batteryWarning(9, false, 20))
        assertNull(ProactivePlan.batteryWarning(9, true, 101))
    }

    @Test fun headAndReminderCommands() {
        assertEquals(Cmd.Head(true), Commands.parse("شغل التحكم بالرأس"))
        assertEquals(Cmd.Head(false), Commands.parse("وقف التحكم بالرأس"))
        assertEquals(Cmd.HeadCenter, Commands.parse("وسط المؤشر"))
        assertEquals(Cmd.GridHere, Commands.parse("اضغط هنا"))
        assertEquals(Cmd.ListReminders, Commands.parse("شنو تذكيراتي"))
        assertEquals(Cmd.DeleteReminder("الدواء"), Commands.parse("امسح تذكير الدواء"))
        assertEquals(Cmd.Remind(20, 0, "بالدواء", true), Commands.parse("ذكرني بالدواء كل يوم الساعة 8 مساء"))
    }
}
