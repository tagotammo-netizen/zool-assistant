package org.jarvis.assistant

/**
 * Control with one or two buttons (a Bluetooth switch, a keyboard, or the phone's volume keys).
 * A frame moves over what can be pressed on the screen, then over a strip of actions (back, home, scroll...).
 * Two buttons: one moves the frame, the other presses. One button: the frame moves by itself and the button presses.
 * Pure logic; the service draws the frame and does the pressing.
 */
object SwitchScan {

    enum class Key { NEXT, SELECT, NONE }

    enum class Strip(val label: String) {
        BACK("↩ رجوع"), HOME("⌂ الرئيسية"), DOWN("▼ انزل"), UP("▲ اطلع"), NOTIFS("🔔 الإشعارات"), LISTEN("🎤 اسمعني"), BOARD("💬 لوحة الكلام")
    }

    data class Item(val left: Int, val top: Int, val right: Int, val bottom: Int, val label: String, val strip: Strip? = null) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
    }

    // Android key codes, so this file needs no Android classes
    const val KEYCODE_SPACE = 62
    const val KEYCODE_TAB = 61
    const val KEYCODE_ENTER = 66
    const val KEYCODE_NUMPAD_ENTER = 160
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_HEADSETHOOK = 79
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_1 = 188

    private val NEXT_KEYS = setOf(KEYCODE_SPACE, KEYCODE_TAB, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_DOWN, KEYCODE_MEDIA_NEXT)
    private val SELECT_KEYS = setOf(KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER, KEYCODE_DPAD_CENTER, KEYCODE_MEDIA_PLAY_PAUSE,
        KEYCODE_HEADSETHOOK, KEYCODE_BUTTON_A, KEYCODE_BUTTON_1)

    /** What a key press means. [oneSwitch]: every switch key presses. [volume]: the volume keys are switches too. */
    fun keyOf(code: Int, oneSwitch: Boolean, volume: Boolean): Key {
        val next = code in NEXT_KEYS || (volume && code == KEYCODE_VOLUME_DOWN)
        val select = code in SELECT_KEYS || (volume && code == KEYCODE_VOLUME_UP)
        return when {
            oneSwitch && (next || select) -> Key.SELECT
            next -> Key.NEXT
            select -> Key.SELECT
            else -> Key.NONE
        }
    }

    /**
     * Screen items in reading order for Arabic: rows from the top, and right to left in a row.
     * Items whose centres are within [rowSlack] pixels up or down share a row. Very small or duplicate boxes are dropped.
     */
    fun order(items: List<Item>, rowSlack: Int, minSize: Int): List<Item> {
        val usable = items.filter { it.right - it.left >= minSize && it.bottom - it.top >= minSize }
            .distinctBy { listOf(it.left / 8, it.top / 8, it.right / 8, it.bottom / 8) }
            .sortedBy { it.cy }
        val rows = ArrayList<MutableList<Item>>()
        for (item in usable) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(row.first().cy - item.cy) <= rowSlack) row.add(item) else rows.add(mutableListOf(item))
        }
        return rows.flatMap { r -> r.sortedByDescending { it.cx } }
    }

    /** The strip of actions along the bottom of the screen, [height] tall. */
    fun strip(width: Int, screenHeight: Int, height: Int, withBoard: Boolean): List<Item> {
        val kinds = Strip.values().filter { withBoard || it != Strip.BOARD }
        val w = width / kinds.size
        return kinds.mapIndexed { i, s ->
            val right = width - i * w
            Item(right - w, screenHeight - height, right, screenHeight, s.label, s)
        }
    }

    /** Moving on: after the last item, back to the first. */
    fun next(index: Int, count: Int) = if (count <= 0) -1 else (index + 1) % count
}
