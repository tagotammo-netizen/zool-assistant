package org.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zol's Sudanese voice (recorded replies and pronunciation) and updates from GitHub. */
class VoiceAndUpdateTest {

    @Test fun recordedRepliesMatchWhatZolSays() {
        // the recording made for the shown phrase is the one found when Zol says it, punctuation aside
        for (raw in VoicePack.DEFAULTS) {
            val shown = Sudani.convert(raw)
            assertEquals(VoicePack.fileName(shown), VoicePack.fileName("$shown "))
            assertEquals(VoicePack.fileName(shown), VoicePack.fileName(shown.replace(".", "").replace("،", "")))
        }
        assertEquals(VoicePack.fileName("حاضر."), VoicePack.fileName("حاضر"))
        assertNotEquals(VoicePack.fileName("حاضر."), VoicePack.fileName("تمام."))
        assertTrue(VoicePack.fileName("حاضر").matches(Regex("r[0-9a-f]+\\.m4a")))
        assertEquals(VoicePack.DEFAULTS.size, VoicePack.DEFAULTS.map { VoicePack.fileName(it) }.toSet().size)
    }

    @Test fun pronunciation() {
        val fixes = VoicePack.PRONOUNCE + VoicePack.parseFixes("دوا=دَوَا\nخطأ\n = x\nمويه=مُويَة")
        assertEquals("دَوَا", fixes["دوا"])
        assertFalse(fixes.containsKey(""))
        assertEquals("شِنُو الداير تعملو هَسِّي؟", VoicePack.pronounce("شنو الداير تعملو هسي؟", fixes))
        assertEquals("ما لقيت «منو» يا زُول", VoicePack.pronounce("ما لقيت «منو» يا زول", fixes))     // names in «» stay
        assertEquals("Hello شِنُو", VoicePack.pronounce("Hello شنو", fixes))
        assertEquals("الزولة", VoicePack.pronounce("الزولة", fixes))                                  // whole words only
    }

    @Test fun versions() {
        assertTrue(Updates.newer("v1.2", "1.1"))
        assertTrue(Updates.newer("1.10", "1.9.3"))
        assertTrue(Updates.newer("2.0", "1.99"))
        assertFalse(Updates.newer("v1.2", "1.2"))
        assertFalse(Updates.newer("1.1", "1.2"))
        assertFalse(Updates.newer("1.2-beta", "1.2"))
        assertTrue(Updates.newer("1.2.1", "1.2"))
    }

    @Test fun readsTheGitHubRelease() {
        val json = ("""{"tag_name":"v1.2","html_url":"https://github.com/x/y/releases/tag/v1.2","body":"جديد",
            "draft":false,"prerelease":false,"assets":[
            {"name":"zol-v1.2-debug.apk","browser_download_url":"https://e/d.apk"},
            {"name":"zol-v1.2.apk","browser_download_url":"https://e/r.apk"},
            {"name":"notes.txt","browser_download_url":"https://e/n.txt"}]}""")
        val r = Updates.parse(json)!!
        assertEquals("1.2", r.version)
        assertEquals("https://e/r.apk", r.apk)
        assertEquals("جديد", r.notes)
        val onlyDebug = ("""{"tag_name":"v1.3","assets":[{"name":"zol-v1.3-debug.apk","browser_download_url":"https://e/d.apk"}]}""")
        assertEquals("https://e/d.apk", Updates.parse(onlyDebug)!!.apk)
        assertNull(Updates.parse("""{"tag_name":"v1.4","prerelease":true,"assets":[]}"""))
        assertNull(Updates.parse("""{"tag_name":"v1.4","assets":[]}"""))
    }

    @Test fun updateCommand() {
        assertEquals(Cmd.CheckUpdate, Commands.parse("حدث زول"))
        assertEquals(Cmd.CheckUpdate, Commands.parse("في تحديث"))
        assertTrue(Features.ALL.any { it.special == "update" } && Features.ALL.any { it.special == "voice" })
    }
}
