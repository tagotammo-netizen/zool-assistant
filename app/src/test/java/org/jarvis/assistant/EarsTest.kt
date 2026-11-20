package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarsTest {

    @Test fun inputsInOrder() {
        assertEquals(MicSources.CALL, MicSources.order(MicSources.CALL, 0))
        assertEquals(MicSources.PLAIN, MicSources.order(MicSources.CALL, 1))
        assertEquals(MicSources.RECOGNITION, MicSources.order(MicSources.CALL, 2))
        assertEquals(MicSources.CALL, MicSources.order(MicSources.CALL, 3))
        assertEquals(MicSources.PLAIN, MicSources.order(MicSources.PLAIN, 0))
        assertEquals(MicSources.CALL, MicSources.order(MicSources.PLAIN, 1))
    }

    @Test fun callInputWinsWhenNearlyAsClear() {
        // Honor: plain 755 (very clear), recognition 25; the call input a bit less clear but cancels echo
        assertEquals(MicSources.CALL, MicSources.choose(mapOf(
            MicSources.PLAIN to (755.0 to 100.0), MicSources.CALL to (400.0 to 70.0), MicSources.RECOGNITION to (25.0 to 25.0))))
        // the call input far worse: the clearest one
        assertEquals(MicSources.PLAIN, MicSources.choose(mapOf(
            MicSources.PLAIN to (755.0 to 100.0), MicSources.CALL to (20.0 to 4.0))))
        assertEquals(null, MicSources.choose(emptyMap()))
    }

    @Test fun ownVoiceIsNotACommand() {
        val v = OwnVoice()
        v.said("الساعة حداشر و1 دقيقة بالليل", 0)
        assertTrue(v.isEcho("الساعه حداشر ودقيقه بالليل", 2_000))
        assertFalse(v.isEcho("زول كم الساعة", 2_000))
        assertFalse(v.isEcho("الساعه حداشر ودقيقه بالليل", 60_000))      // long ago: not an echo
        v.said("شغّلتو. قول: وقّف، أو قدّم شوية، أو رجّع شوية.", 100_000)
        assertFalse(v.isEcho("زول قدم شويه", 103_000))                    // the person repeating the hint
        assertTrue(v.isEcho("قدم شويه او رجع شويه", 103_000))
        v.said("أستمع الآن. قل: زول ثم الأمر.", 200_000)
        assertTrue(v.isEcho("قل زول ثم الامر", 201_000))
    }

    @Test fun wakeOpensTheSentence() {
        assertTrue(Commands.wakeAtStart("زول افتح الواتساب"))
        assertTrue(Commands.wakeAtStart("يا زول افتح الواتساب"))
        assertFalse(Commands.wakeAtStart("شفت زول ساكن هناك"))
        assertFalse(Commands.wakeAtStart("الليلة يا زول الحبيب"))
    }
}
