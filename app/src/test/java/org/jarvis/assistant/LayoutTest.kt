package org.jarvis.assistant

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards against mistakes in the layout that compile fine but crash the app when it opens. */
class LayoutTest {

    private fun layout(): String {
        val candidates = listOf("src/main/res/layout/activity_main.xml", "app/src/main/res/layout/activity_main.xml")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: File(System.getProperty("layout.path") ?: error("activity_main.xml not found"))
        return file.readText()
    }

    @Test fun everyIdIsUsedOnce() {
        val ids = Regex("android:id=\"@\\+id/(\\w+)\"").findAll(layout()).map { it.groupValues[1] }.toList()
        val dupes = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("IDs used twice: $dupes", dupes.isEmpty())
    }
}
