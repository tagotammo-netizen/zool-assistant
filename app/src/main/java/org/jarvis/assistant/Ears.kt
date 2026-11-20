package org.jarvis.assistant

import android.content.res.AssetManager
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * A speech detector that knows a voice from noise (Silero VAD, a small model that ships with Zol and runs on the
 * phone): traffic, a fan, music, dishes and a crowd's hum do not count as speech, a voice does.
 * Fed 16 kHz sound in pieces of any size; [speaking] says whether someone is talking right now.
 */
class VoiceDetector private constructor(private val vad: Vad) {
    private val window = FloatArray(512)
    private var filled = 0

    @Volatile var speaking = false
        private set

    /** 16-bit little-endian samples. */
    fun feed(pcm: ByteArray, len: Int) {
        var i = 0
        while (i + 1 < len) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            window[filled++] = s.toShort() / 32768f
            if (filled == window.size) {
                vad.acceptWaveform(window.copyOf())
                speaking = vad.isSpeechDetected()
                while (!vad.empty()) vad.pop()           // whole sentences are not needed here, only the "now"
                filled = 0
            }
            i += 2
        }
    }

    fun reset() {
        filled = 0
        speaking = false
        try { vad.reset() } catch (_: Throwable) {}
    }

    fun release() = try { vad.release() } catch (_: Throwable) {}

    companion object {
        /** Null when the model or its library cannot load: the loudness gate is used instead. */
        fun create(assets: AssetManager): VoiceDetector? = try {
            VoiceDetector(Vad(assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,              // how sure it is a voice
                    minSilenceDuration = 0.35f,
                    minSpeechDuration = 0.2f,
                    windowSize = 512,
                    maxSpeechDuration = 20f,
                ),
                sampleRate = 16_000,
                numThreads = 1,
            )))
        } catch (_: Throwable) { null }
    }
}

/** The microphone inputs Zol can listen through, best first. */
object MicSources {
    /** 2: the input for calls, which removes the phone's own sound (echo) and the noise. */
    const val CALL = 2
    const val PLAIN = 1
    const val RECOGNITION = 0
    val ALL = listOf(CALL, PLAIN, RECOGNITION)

    fun android(code: Int) = when (code) {
        CALL -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        else -> MediaRecorder.AudioSource.MIC
    }

    fun name(code: Int) = when (code) {
        CALL -> "مدخل المكالمات (بيشيل صوت التلفون والضجة)"
        RECOGNITION -> "مدخل التعرّف"
        else -> "المايك العادي"
    }

    /** The one to try after [tries] silences, starting from the chosen one. */
    fun order(chosen: Int, tries: Int): Int {
        val list = listOf(chosen) + ALL.filter { it != chosen }
        return list[Math.floorMod(tries, list.size)]
    }

    /**
     * The microphone test's choice: the clearest input, but the call input whenever it is nearly as clear
     * (at least 60 %) and loud enough, because it does not hear what the phone itself plays.
     * [clear]: input → (speech level, speech / room).
     */
    fun choose(clear: Map<Int, Pair<Double, Double>>): Int? {
        val ok = clear.filter { it.value.first > 0 }
        val best = ok.maxByOrNull { it.value.second } ?: return null
        val call = ok[CALL]
        return if (call != null && call.first >= 30 && call.second >= best.value.second * 0.6) CALL else best.key
    }
}

/** Sentences Zol said a moment ago: when the microphone hears them back, they are not commands. */
class OwnVoice(private val keepMs: Long = 25_000) {
    private val said = ArrayDeque<Pair<Long, List<String>>>()

    fun said(text: String, now: Long) {
        val w = words(text)
        if (w.isEmpty()) return
        said.addLast(now to w)
        while (said.size > 8) said.removeFirst()
    }

    /**
     * Most of what was heard is in something Zol said lately. A sentence that starts with the wake word is the
     * person repeating a suggestion ("قول: قدّم شوية" → "زول قدم شويه"), unless Zol itself said the wake word.
     */
    fun isEcho(heard: String, now: Long): Boolean {
        while (said.isNotEmpty() && now - said.first().first > keepMs) said.removeFirst()
        val wake = Commands.normalize(Commands.wakeWord)
        val byPerson = Commands.wakeAtStart(heard)
        val h = words(heard).filter { it != wake }
        if (h.isEmpty()) return false
        return said.any { (_, s) ->
            if (byPerson && wake !in s) return@any false
            val set = s.toSet()
            val common = h.count { it in set }
            common >= 2 && common >= h.size * 0.6 || (h.size == 1 && h[0].length >= 4 && h[0] in set && s.size <= 3)
        }
    }

    private fun words(t: String) = Commands.normalize(t).split(" ").filter { it.length >= 2 }
}
