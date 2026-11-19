package org.jarvis.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.OutputStream

/**
 * Zol's own ear. It keeps the microphone open on the phone (nothing leaves the phone), notices when someone starts
 * talking ([SpeechGate]) and then hands that sound — including the moment before, so "زول" is not cut — to the
 * speech recogniser through a pipe. The recogniser is asked once per sentence instead of every few seconds.
 */
class MicGate(
    private val onSpeech: () -> Unit,
    private val onUtterance: (ByteArray) -> Unit = {},
    private val onSilenced: () -> Unit = {},
) {

    companion object {
        const val RATE = 16_000
        private const val FRAME = RATE / 50 * 2          // 20 ms of 16-bit mono
        private const val PRE_ROLL = RATE * 2 * 9 / 10   // 0.9 s
        const val END_SILENCE_MS = 800L
        const val MAX_SENTENCE_MS = 12_000L

        /** A WAV file around 16 kHz mono 16-bit samples. */
        fun wav(pcm: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(pcm.size + 44)
            fun i32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
            fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
            out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
            out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
            return out.toByteArray()
        }
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var sink: OutputStream? = null
    @Volatile private var sinkUntil = 0L
    /** Not listening for speech (Zol is talking, or waiting after "too many requests"). */
    @Volatile var quietUntil = 0L
    @Volatile var muted = false
    /** Last time sound frames arrived: the watchdog checks the ear is alive. */
    @Volatile var lastFrame = 0L
        private set
    /** Android gives only zeros (another app holds the microphone, or Zol is not allowed to hear right now). */
    @Volatile var silenced = false
        private set
    /** The loudest moment in the last seconds, for the status screen. */
    @Volatile var peak = 0.0
        private set
    private var zeroSince = -1L
    /** The loudness of recent speech, slowly forgotten (half after ~15 s): sets the gain for quiet microphones. */
    @Volatile var speechPeak = 0.0
        private set
    val gain get() = SpeechGate.gainFor(speechPeak)
    /** Which microphone input: 0 = for speech recognition, 1 = the plain microphone. */
    var source = 0

    private val gate = SpeechGate()

    /** Record whole sentences here instead of feeding a recogniser (for transcription in the cloud). */
    @Volatile var captureMode = false
    private var capture: java.io.ByteArrayOutputStream? = null
    private var captureStart = 0L
    private var quietSince = -1L
    private var wasQuiet = false
    private val preRoll = PreRoll(PRE_ROLL)

    val active get() = running
    val feeding get() = sink != null

    @SuppressLint("MissingPermission")    // the service only starts it with the microphone permission
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(if (source == 0) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC,
                RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 10))
        } catch (_: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        try { r.startRecording() } catch (_: Exception) { r.release(); return false }
        record = r
        running = true
        thread = Thread({ loop(r) }, "zol-ear").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        detach()
        thread?.join(300)
        thread = null
        record?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        record = null
    }

    /**
     * A pipe for the recogniser: the read end goes in the request, the sound (with what came just before) goes in
     * the write end until [detach] or [maxMs].
     */
    fun openPipe(maxMs: Long = 12_000): ParcelFileDescriptor? {
        detach()
        val (read, write) = try { ParcelFileDescriptor.createPipe() } catch (_: Exception) { return null }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        val pre = preRoll.take()
        SpeechGate.amplify(pre, pre.size, gain)
        try { out.write(pre) } catch (_: Exception) { try { out.close() } catch (_: Exception) {}; read.close(); return null }
        sinkUntil = SystemClock.elapsedRealtime() + maxMs
        sink = out
        return read
    }

    /** End the sentence: closing the pipe tells the recogniser the sound is over. */
    fun detach() {
        val s = sink ?: return
        sink = null
        try { s.close() } catch (_: Exception) {}
        gate.reset()
    }

    private fun loop(r: AudioRecord) {
        val buf = ByteArray(FRAME)
        while (running) {
            val n = try { r.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
            if (n <= 0) { if (n < 0) SystemClock.sleep(50); continue }
            val now = SystemClock.elapsedRealtime()
            lastFrame = now
            val heard = SpeechGate.level(buf, n)
            peak = maxOf(heard, peak * 0.995)
            speechPeak = maxOf(heard, speechPeak * 0.999)
            if (heard < 1.0) {                                   // pure digital silence, not a quiet room
                if (zeroSince < 0) zeroSince = now
                if (!silenced && now - zeroSince > 4_000) { silenced = true; onSilenced() }
            } else { zeroSince = -1L; silenced = false }
            val out = sink
            if (out != null) {
                if (now > sinkUntil) { detach(); continue }
                val loud = buf.copyOf(n)
                SpeechGate.amplify(loud, n, gain)
                try { out.write(loud, 0, n) } catch (_: Exception) { detach() }   // the recogniser closed its end
                continue
            }
            val rec = capture
            if (rec != null) {                         // a sentence is being recorded: until a short silence
                val level = heard
                val loud = buf.copyOf(n)
                SpeechGate.amplify(loud, n, gain)      // a quiet microphone is made loud enough to understand
                rec.write(loud, 0, n)
                if (level < gate.threshold() * 0.7) { if (quietSince < 0) quietSince = now } else quietSince = -1L
                val long = now - captureStart
                if ((quietSince > 0 && now - quietSince > END_SILENCE_MS && long > 600) || long > MAX_SENTENCE_MS) {
                    capture = null
                    gate.reset()
                    if (long >= 500) onUtterance(rec.toByteArray())
                }
                continue
            }
            if (muted || now < quietUntil) { wasQuiet = true; continue }
            if (wasQuiet) { preRoll.take(); gate.reset(); wasQuiet = false }   // never send Zol's own voice
            preRoll.add(buf, n)
            if (gate.feed(SpeechGate.level(buf, n), now)) {
                if (captureMode) {
                    capture = java.io.ByteArrayOutputStream(RATE * 2 * 4).also {
                        val pre = preRoll.take()
                        SpeechGate.amplify(pre, pre.size, gain)
                        it.write(pre)
                    }
                    captureStart = now
                    quietSince = -1L
                } else onSpeech()
            }
        }
    }
}
