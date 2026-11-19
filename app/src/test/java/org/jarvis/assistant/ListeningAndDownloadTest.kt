package org.jarvis.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zol's own ear (asking the recogniser only when someone talks) and the download progress text. */
class ListeningAndDownloadTest {

    private fun run(gate: SpeechGate, from: Long, to: Long, level: Double): Int {
        var starts = 0
        var t = from
        while (t < to) { if (gate.feed(level, t)) starts++; t += 20 }
        return starts
    }

    @Test fun quietRoomAsksNothing() {
        val g = SpeechGate()
        assertEquals(0, run(g, 0, 60_000, 150.0))          // a minute of room noise: no request at all
        assertEquals(0, run(g, 60_000, 60_060, 3000.0))    // a click (60 ms)
        assertEquals(1, run(g, 61_000, 62_500, 3000.0))    // someone speaks: exactly one request
    }

    @Test fun followsANoisyRoom() {
        val g = SpeechGate()
        run(g, 0, 120_000, 900.0)                          // a fan: the room gets louder
        assertTrue(g.threshold() > 900)
        assertEquals(0, run(g, 120_000, 130_000, 1100.0))  // the fan itself does not open requests
        assertEquals(1, run(g, 130_000, 131_000, 5000.0))  // a voice over it does
    }

    @Test fun quietMicrophone() {
        // some phones (Honor) give speech at only ~80 RMS on the recognition input
        val g = SpeechGate()
        assertEquals(0, run(g, 0, 30_000, 15.0))
        assertEquals(1, run(g, 30_000, 31_000, 84.0))
        assertEquals(16.0, SpeechGate.gainFor(84.0), 0.01)
        assertEquals(1.0, SpeechGate.gainFor(5000.0), 0.01)
        val pcm = byteArrayOf(0x64, 0x00, 0x9C.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)   // 100, -100, 32767
        SpeechGate.amplify(pcm, pcm.size, 10.0)
        assertEquals(1000.0, SpeechGate.level(pcm.copyOf(2), 2), 0.01)
        assertEquals(-1000, ((pcm[3].toInt() shl 8) or (pcm[2].toInt() and 0xFF)).toShort().toInt())
        assertEquals(32767, ((pcm[5].toInt() shl 8) or (pcm[4].toInt() and 0xFF)).toShort().toInt())   // clipped, not wrapped
    }

    @Test fun micTestNumbers() {
        val levels = List(80) { 20.0 } + List(20) { 900.0 }
        val (noise, speech) = SpeechGate.stats(levels)
        assertEquals(20.0, noise, 0.0)
        assertEquals(900.0, speech, 0.0)
        assertEquals(0.0 to 0.0, SpeechGate.stats(emptyList()))
    }

    @Test fun whisperNoiseIsDropped() {
        assertEquals("زول كم الساعة", whisperText("", listOf("زول كم" to 0.1, " الساعة" to 0.2)))
        assertEquals("زول كم", whisperText("", listOf("زول كم" to 0.1, "ههه" to 0.9)))
        assertEquals("", whisperText("ترجمة نانسي قنقر", null))
        assertEquals("", whisperText("شكرا للمشاهدة", null))
        assertEquals("افتح واتساب", whisperText("«افتح واتساب»", null))
    }

    @Test fun loudness() {
        val pcm = ByteArray(4)
        pcm[0] = 0xE8.toByte(); pcm[1] = 0x03        // 1000
        pcm[2] = 0x18; pcm[3] = 0xFC.toByte()        // -1000
        assertEquals(1000.0, SpeechGate.level(pcm, 4), 0.01)
        assertEquals(0.0, SpeechGate.level(pcm, 0), 0.0)
    }

    @Test fun keepsTheStartOfTheSentence() {
        val p = PreRoll(4)
        p.add(byteArrayOf(1, 2, 3), 3)
        p.add(byteArrayOf(4, 5, 6), 3)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), p.take())   // the last moments, oldest first
        assertEquals(0, p.take().size)
    }

    @Test fun progressText() {
        val j = Downloads.Job("t", "تحديث")
        assertEquals(-1, Downloads.percent(j))
        j.total = 10 * 1_048_576L
        j.done = 5 * 1_048_576L
        assertEquals(50, Downloads.percent(j))
        val d = Downloads.detail(j, j.started + 5_000)
        assertTrue(d, d.contains("5.0 من 10.0 ميقا") && d.contains("1.0 ميقا/ث") && d.contains("فاضل 5 ث"))
        val q = Downloads.Job("q", "سور")
        q.items = 4; q.item = 3; q.total = 100; q.done = 50
        assertEquals(62, Downloads.percent(q))
        assertTrue(Downloads.detail(q, q.started + 1000).startsWith("3 من 4"))
        q.state = Downloads.State.DONE
        assertEquals(100, Downloads.percent(q))
        assertEquals("خلص ✓", Downloads.detail(q, 0))
        val seen = ArrayList<Downloads.State>()
        val l: (Downloads.Job) -> Unit = { seen.add(it.state) }
        Downloads.listen(l)
        val k = Downloads.start("k", "x")
        assertEquals(listOf(k), Downloads.running().filter { it.id == "k" })
        Downloads.cancel("k")
        assertTrue(k.cancelled)
        Downloads.finish(k, Downloads.State.CANCELLED)
        Downloads.unlisten(l)
        assertEquals(listOf(Downloads.State.RUNNING, Downloads.State.CANCELLED), seen)
        assertFalse(Downloads.running().any { it.id == "k" })
    }
}
