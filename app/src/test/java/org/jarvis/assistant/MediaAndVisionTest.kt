package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAndVisionTest {

    @Test fun playOnYoutube() {
        assertEquals(Cmd.PlayVideo("عثمان حسين"), Commands.parse("زول شغل في اليوتيوب عثمان حسين"))
        assertEquals(Cmd.PlayVideo("عثمان حسين"), Commands.parse("زول شغل لي عثمان حسين في اليوتيوب"))
        assertEquals(Cmd.PlayVideo("طبخ عصيده"), Commands.parse("زول شغل لي فيديو طبخ عصيده"))
        assertEquals(Cmd.PlayVideo("cats"), Commands.parse("play cats on youtube"))
    }

    @Test fun openingYoutubeIsStillOpening() {
        assertFalse(Commands.parse("زول افتح اليوتيوب") is Cmd.PlayVideo)
        assertFalse(Commands.parse("زول شغل اليوتيوب") is Cmd.PlayVideo)
    }

    @Test fun seekAndPicture() {
        assertEquals(Cmd.Seek(true), Commands.parse("زول قدم شويه"))
        assertEquals(Cmd.Seek(false), Commands.parse("زول رجع الفيديو"))
        assertEquals(Cmd.SeeScreen(""), Commands.parse("زول وصف لي الصورة"))
        assertEquals(Cmd.SeeScreen(""), Commands.parse("زول شنو في الصورة"))
        assertEquals(Cmd.Media("playpause"), Commands.parse("زول وقف الفيديو"))
    }

    @Test fun youtubeResults() {
        assertTrue(MediaHelp.isVideo("أغاني سودانية - ‏٤ دقائق و١٢ ثانية - الانتقال إلى القناة - ‏١٫٢ مليون مشاهدة - قبل سنتين - تشغيل الفيديو"))
        assertTrue(MediaHelp.isVideo("Cats compilation - 10 minutes - Go to channel - 3M views - 1 year ago - play video"))
        assertFalse(MediaHelp.isVideo("Sponsored - Buy now - 30 seconds - 1M views"))
        assertFalse(MediaHelp.isVideo("بحث"))
    }

    @Test fun visionModel() {
        assertEquals("meta-llama/llama-4-scout-17b-16e-instruct", MediaHelp.bestVisionModel(listOf("whisper-large-v3",
            "meta-llama/llama-guard-4-12b", "meta-llama/llama-4-scout-17b-16e-instruct", "openai/gpt-oss-120b")))
        assertEquals(null, MediaHelp.bestVisionModel(listOf("whisper-large-v3", "openai/gpt-oss-120b")))
    }
}
