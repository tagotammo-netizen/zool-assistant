package org.jarvis.assistant

import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.URL

/**
 * ريموت التلفزيون بالواي فاي: Zol finds the TV on the same Wi-Fi and sends it the remote's keys.
 * Roku (its plain web remote), Samsung (2016 and later) and LG webOS (both through a WebSocket the TV opens;
 * the first time the TV asks to allow Zol). The brand rules and messages are pure, so they can be tested.
 */
object TvRemote {

    data class Tv(val brand: String, val ip: String, val name: String = "")

    /** What the person wants from the TV, from what they said ("علّي التلفزيون" → volup). */
    fun action(t: String): String? {
        val n = Commands.normalize(t)
        if (!(n.contains("تلفزيون") || n.contains("التلفاز") || n.contains("الشاشه الكبيره") || n.contains(" tv") || n.startsWith("tv"))) return null
        fun has(vararg w: String) = w.any { n.contains(it) }
        return when {
            has("بالواي فاي", "لقي التلفزيون", "دور علي التلفزيون", "فتش علي التلفزيون", "find the tv") -> "find"
            has("وصل", "افصل", "اتحكم", "اكتب") -> null                       // the Bluetooth mouse and keyboard
            has("يوتيوب", "youtube") -> "youtube"
            has("نتفلكس", "netflix") -> "netflix"
            has("ولع", "شغل التلفزيون", "اقفل", "طفي", "turn on", "turn off", "power") -> "power"
            has("اسكت", "كتم", "mute") -> "mute"
            has("علي", "زيد", "ارفع", "volume up", "louder") -> "volup"
            has("وطي", "نزل", "قلل", "خفض", "volume down", "quieter") -> "voldown"
            has("القناه الجايه", "غير القناه", "القناه البعدها", "next channel", "channel up") -> "chup"
            has("القناه الفاتت", "القناه القبلها", "previous channel", "channel down") -> "chdown"
            has("رجع", "back") -> "back"
            has("الرئيسيه", "home") -> "home"
            has("اوكي", "اختار", "ok") -> "ok"
            has("فوق", "up") -> "up"
            has("تحت", "down") -> "down"
            has("يمين", "right") -> "right"
            has("شمال", "يسار", "left") -> "left"
            has("وقف", "pause") -> "pause"
            has("كمل", "play") -> "play"
            else -> null
        }
    }

    /** An SSDP answer (the headers a device sends when asked "who is on the Wi-Fi?") → a TV Zol knows how to drive. */
    fun identify(response: String, ip: String): Tv? {
        val h = response.lowercase()
        return when {
            h.contains("roku:ecp") || h.contains("roku") -> Tv("roku", ip, "Roku")
            h.contains("samsung") && (h.contains("tv") || h.contains("tizen") || h.contains("remotecontrol")) -> Tv("samsung", ip, "Samsung")
            h.contains("webos") || h.contains("lge") || h.contains("lg electronics") -> Tv("lg", ip, "LG")
            else -> null
        }
    }

    private val ROKU = mapOf("power" to "Power", "volup" to "VolumeUp", "voldown" to "VolumeDown", "mute" to "VolumeMute",
        "chup" to "ChannelUp", "chdown" to "ChannelDown", "home" to "Home", "back" to "Back", "ok" to "Select", "up" to "Up",
        "down" to "Down", "left" to "Left", "right" to "Right", "play" to "Play", "pause" to "Play")
    private val SAMSUNG = mapOf("power" to "KEY_POWER", "volup" to "KEY_VOLUP", "voldown" to "KEY_VOLDOWN", "mute" to "KEY_MUTE",
        "chup" to "KEY_CHUP", "chdown" to "KEY_CHDOWN", "home" to "KEY_HOME", "back" to "KEY_RETURN", "ok" to "KEY_ENTER",
        "up" to "KEY_UP", "down" to "KEY_DOWN", "left" to "KEY_LEFT", "right" to "KEY_RIGHT", "play" to "KEY_PLAY", "pause" to "KEY_PAUSE")
    private val LG = mapOf("power" to "ssap://system/turnOff", "volup" to "ssap://audio/volumeUp", "voldown" to "ssap://audio/volumeDown",
        "chup" to "ssap://tv/channelUp", "chdown" to "ssap://tv/channelDown", "play" to "ssap://media.controls/play",
        "pause" to "ssap://media.controls/pause")
    private val ROKU_APPS = mapOf("youtube" to "837", "netflix" to "12")
    private val LG_APPS = mapOf("youtube" to "youtube.leanback.v4", "netflix" to "netflix")

    fun rokuKey(action: String) = ROKU[action]
    fun samsungKey(action: String) = SAMSUNG[action]

    fun samsungMessage(key: String) =
        """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$key","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""

    fun lgRegister(clientKey: String?) = """{"type":"register","id":"reg0","payload":{"forcePairing":false,"pairingType":"PROMPT",""" +
        (if (clientKey != null) """"client-key":"$clientKey",""" else "") +
        """"manifest":{"manifestVersion":1,"appVersion":"1.1","signed":{"appId":"org.jarvis.assistant","vendorId":"zol",""" +
        """"permissions":["LAUNCH","CONTROL_AUDIO","CONTROL_POWER","CONTROL_INPUT_TV","CONTROL_INPUT_MEDIA_PLAYBACK"]},""" +
        """"permissions":["LAUNCH","CONTROL_AUDIO","CONTROL_POWER","CONTROL_INPUT_TV","CONTROL_INPUT_MEDIA_PLAYBACK"]}}}"""

    fun lgRequest(uri: String, payload: String? = null) =
        """{"type":"request","id":"z${System.nanoTime() % 100000}","uri":"$uri"""" + (if (payload != null) ""","payload":$payload""" else "") + "}"

    fun lgUri(action: String): Pair<String, String?>? = when {
        action == "mute" -> "ssap://audio/setMute" to """{"mute":true}"""
        LG_APPS.containsKey(action) -> "ssap://system.launcher/launch" to """{"id":"${LG_APPS[action]}"}"""
        else -> LG[action]?.let { it to null }
    }

    /** The client key LG sends after the person allows Zol on the TV. */
    fun lgClientKey(message: String) = Regex("\"client-key\"\\s*:\\s*\"([^\"]+)\"").find(message)?.groupValues?.get(1)

    // ---------------------------------------------------------------- the network (not used in tests)

    /** Asks the Wi-Fi who is there for [ms] and keeps the TVs that answer. */
    fun discover(ms: Int = 3_000): List<Tv> {
        val found = LinkedHashMap<String, Tv>()
        val ask = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
        MulticastSocket(null as java.net.SocketAddress?).use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(0))
            s.soTimeout = 400
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { s.send(DatagramPacket(ask, ask.size, group, 1900)) }
            val end = System.currentTimeMillis() + ms
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val ip = p.address.hostAddress ?: continue
                if (found.containsKey(ip)) continue
                identify(String(p.data, 0, p.length), ip)?.let { found[ip] = it }
            }
        }
        return found.values.toList()
    }

    /** Roku: a POST to its web remote. */
    fun roku(ip: String, action: String): Boolean {
        val path = ROKU_APPS[action]?.let { "launch/$it" } ?: rokuKey(action)?.let { "keypress/$it" } ?: return false
        val con = URL("http://$ip:8060/$path").openConnection() as java.net.HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 3000; con.readTimeout = 3000
        con.outputStream.use { }
        val ok = con.responseCode in 200..299
        con.disconnect()
        return ok
    }

    /** One message over a WebSocket; [answers] reads what the TV sends back for [readMs] (LG's client key). */
    fun ws(host: String, port: Int, path: String, messages: List<String>, readMs: Int = 0, answers: (String) -> Unit = {}): Boolean {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), 3000)
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            val key = android.util.Base64.encodeToString(ByteArray(16).also { java.util.Random().nextBytes(it) }, android.util.Base64.NO_WRAP)
            out.write(("GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            val head = readHead(input)
            if (!head.startsWith("HTTP/1.1 101")) return false
            for (m in messages) {
                out.write(frame(m))
                out.flush()
                if (readMs > 0) {
                    val until = System.currentTimeMillis() + readMs
                    while (System.currentTimeMillis() < until) {
                        val text = try { readFrame(input) } catch (_: java.net.SocketTimeoutException) { null } ?: break
                        answers(text)
                        if (text.contains("\"registered\"")) break
                    }
                } else Thread.sleep(150)
            }
            return true
        }
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; sb.append(c.toChar()) }
        return sb.toString()
    }

    /** A text frame from a client: masked, as the WebSocket rules want. */
    fun frame(text: String): ByteArray {
        val data = text.toByteArray()
        val mask = ByteArray(4).also { java.util.Random().nextBytes(it) }
        val head = java.io.ByteArrayOutputStream()
        head.write(0x81)
        when {
            data.size < 126 -> head.write(0x80 or data.size)
            data.size < 65536 -> { head.write(0x80 or 126); head.write(data.size shr 8); head.write(data.size and 0xFF) }
            else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        head.write(mask)
        for (i in data.indices) head.write(data[i].toInt() xor mask[i % 4].toInt())
        return head.toByteArray()
    }

    private fun readFrame(input: InputStream): String? {
        val b0 = input.read(); if (b0 < 0) return null
        val b1 = input.read(); if (b1 < 0) return null
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
        val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.read(it) } else null
        val data = ByteArray(len.toInt())
        var got = 0
        while (got < data.size) { val n = input.read(data, got, data.size - got); if (n < 0) break; got += n }
        if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return if (b0 and 0x0F == 1) String(data) else ""
    }
}
