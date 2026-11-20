package org.jarvis.assistant

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.concurrent.Executors

/**
 * The phone as a Bluetooth mouse and keyboard for a computer or a TV (Android 9+): the computer sees an ordinary
 * Bluetooth mouse and keyboard, so nothing is installed on it. Zol moves the mouse with the head and types what
 * is said. The report layout and key codes are in [Hid], kept apart so they can be tested.
 */
@SuppressLint("MissingPermission")    // the service checks Bluetooth permission before starting it
class PcLink(private val context: Context, private val onStatus: (String) -> Unit) {

    private var hid: BluetoothHidDevice? = null
    @Volatile var host: BluetoothDevice? = null
        private set
    @Volatile var registered = false
        private set
    private val executor = Executors.newSingleThreadExecutor()
    private var buttons = 0
    val connected get() = host != null

    private val adapter: BluetoothAdapter? get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (!isRegistered) { host = null; return }
            onStatus("ready")
            // the computer it was paired with last time: connect again by itself
            val last = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("pc_host", null)
            val known = pluggedDevice ?: adapter?.bondedDevices?.firstOrNull { it.address == last }
            if (known != null) try { hid?.connect(known) } catch (_: Exception) {}
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("pc_host", device.address).apply()
                    onStatus("connected:" + (try { device.name } catch (_: Exception) { null } ?: "الجهاز"))
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host?.address == device.address) { host = null; onStatus("disconnected") }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            try { hid?.replyReport(device, type, id, if (id == Hid.MOUSE) ByteArray(4) else ByteArray(8)) } catch (_: Exception) {}
        }
    }

    /** False when this phone cannot be a Bluetooth mouse (old Android, no Bluetooth, or the maker turned it off). */
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val a = adapter ?: return false
        if (!a.isEnabled) { onStatus("bluetooth_off"); return true }
        return a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings("زول", "ماوس وكيبورد زول", "Zol",
                    BluetoothHidDevice.SUBCLASS1_COMBO, Hid.DESCRIPTOR)
                try { if (!h.registerApp(sdp, null, null, executor, callback)) onStatus("unsupported") }
                catch (_: Exception) { onStatus("unsupported") }
            }

            override fun onServiceDisconnected(profile: Int) { hid = null; registered = false; host = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        try { host?.let { hid?.disconnect(it) } } catch (_: Exception) {}
        try { hid?.unregisterApp() } catch (_: Exception) {}
        try { hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } } catch (_: Exception) {}
        hid = null; host = null; registered = false
    }

    private fun send(id: Byte, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        try { h.sendReport(d, id.toInt(), data) } catch (_: Exception) {}
    }

    fun move(dx: Int, dy: Int) { if (dx != 0 || dy != 0) send(Hid.MOUSE, Hid.mouse(buttons, dx, dy, 0)) }

    fun scroll(lines: Int) = send(Hid.MOUSE, Hid.mouse(buttons, 0, 0, lines))

    /** 1: left, 2: right. */
    fun click(button: Int = 1) {
        send(Hid.MOUSE, Hid.mouse(button, 0, 0, 0))
        send(Hid.MOUSE, Hid.mouse(0, 0, 0, 0))
    }

    fun key(modifier: Int, code: Int) {
        send(Hid.KEYBOARD, Hid.keys(modifier, code))
        send(Hid.KEYBOARD, Hid.keys(0, 0))
    }

    /** Types the words; Arabic needs the computer's keyboard set to Arabic. Characters it cannot type are skipped. */
    fun type(text: String) {
        executor.execute {
            for (c in text) {
                val (mod, code) = Hid.keyFor(c) ?: continue
                key(mod, code)
                Thread.sleep(12)
            }
        }
    }
}

/** HID report layout and key codes (US and Arabic 101 keyboards). */
object Hid {
    const val KEYBOARD: Byte = 1
    const val MOUSE: Byte = 2
    const val SHIFT = 0x02

    /** A keyboard (report 1) and a 3-button mouse with a wheel (report 2). */
    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
    ).map { it.toByte() }.toByteArray()

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int) = byteArrayOf(
        buttons.toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte())

    fun keys(modifier: Int, code: Int) = byteArrayOf(modifier.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0)

    const val ENTER = 0x28
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val ESCAPE = 0x29

    private const val US_PLAIN = "-=[]\\;'`,./"
    private val US_PLAIN_CODES = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
    private const val US_SHIFTED = "_+{}|:\"~<>?"
    private const val DIGIT_SHIFTED = "!@#$%^&*()"

    /** The Arabic (101) keyboard: each letter is on the key of this English letter. */
    private val ARABIC = mapOf(
        'ض' to 'q', 'ص' to 'w', 'ث' to 'e', 'ق' to 'r', 'ف' to 't', 'غ' to 'y', 'ع' to 'u', 'ه' to 'i', 'خ' to 'o',
        'ح' to 'p', 'ج' to '[', 'د' to ']', 'ش' to 'a', 'س' to 's', 'ي' to 'd', 'ب' to 'f', 'ل' to 'g', 'ا' to 'h',
        'ت' to 'j', 'ن' to 'k', 'م' to 'l', 'ك' to ';', 'ط' to '\'', 'ئ' to 'z', 'ء' to 'x', 'ؤ' to 'c', 'ر' to 'v',
        'ى' to 'n', 'ة' to 'm', 'و' to ',', 'ز' to '.', 'ظ' to '/', 'ذ' to '`',
    )
    private val ARABIC_SHIFTED = mapOf('أ' to 'h', 'إ' to 'y', 'آ' to 'n')

    /** (modifier, key code) for a character, or null when it cannot be typed. */
    fun keyFor(c: Char): Pair<Int, Int>? {
        ARABIC[c]?.let { return keyFor(it) }
        ARABIC_SHIFTED[c]?.let { k -> return keyFor(k)?.let { SHIFT to it.second } }
        return when (c) {
            in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
            in 'A'..'Z' -> SHIFT to (0x04 + (c - 'A'))
            in '1'..'9' -> 0 to (0x1E + (c - '1'))
            '0' -> 0 to 0x27
            ' ' -> 0 to 0x2C
            '\n' -> 0 to ENTER
            '\t' -> 0 to TAB
            in '٠'..'٩' -> keyFor('0' + (c - '٠'))
            else -> {
                US_PLAIN.indexOf(c).takeIf { it >= 0 }?.let { return 0 to US_PLAIN_CODES[it] }
                US_SHIFTED.indexOf(c).takeIf { it >= 0 }?.let { return SHIFT to US_PLAIN_CODES[it] }
                DIGIT_SHIFTED.indexOf(c).takeIf { it >= 0 }?.let { return SHIFT to (if (it == 9) 0x27 else 0x1E + it) }
                null
            }
        }
    }

    /**
     * Head position → mouse speed, like a joystick: looking straight does not move it; the further the head turns,
     * the faster it goes. [v] is -1..1 from the centre.
     */
    fun speed(v: Float, max: Int = 24): Int {
        val a = Math.abs(v)
        if (a < 0.12f) return 0
        val s = Math.pow(((a - 0.12f) / 0.88f).toDouble().coerceAtMost(1.0), 1.5) * max
        return (if (v < 0) -s else s).toInt()
    }
}
