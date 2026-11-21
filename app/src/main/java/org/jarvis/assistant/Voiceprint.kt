package org.jarvis.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * "زول يعرف صوتك إنت بس": a small model (3D-Speaker CAM++, 28 MB, downloaded once, runs on the phone) turns a
 * voice into 192 numbers that stay about the same for the same person. The person says three sentences once;
 * after that a sentence in another voice (someone else, the TV) is not taken as a command.
 */
class Voiceprint(private val context: Context) {

    private var extractor: SpeakerEmbeddingExtractor? = null
    private val prefs get() = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun dir() = File(context.filesDir, "voiceprint").apply { mkdirs() }
    fun model() = File(dir(), MODEL)
    fun ready() = model().length() > 1_000_000

    /** The person's voice, once they said their sentences. */
    fun enrolled(): FloatArray? = Speaker.decode(prefs.getString("voiceprint", "").orEmpty())
    fun on() = prefs.getBoolean("voiceprint_on", false) && enrolled() != null && ready()

    fun download(done: (String?) -> Unit) {
        val job = Downloads.start("voiceprint", "بصمة الصوت (28 ميقا)")
        Thread {
            job.items = 1; job.item = 1
            val error = OfflinePack.fetch(URL, model(), job)
            Downloads.finish(job, if (error == null) Downloads.State.DONE else Downloads.State.FAILED, error)
            done(error)
        }.start()
    }

    /** Null when the model cannot run on this phone. */
    @Synchronized
    fun embed(pcm: ByteArray): FloatArray? {
        if (!ready()) return null
        return try {
            val e = extractor ?: SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = model().absolutePath, numThreads = 2))
                .also { extractor = it }
            val stream = e.createStream()
            stream.acceptWaveform(Speaker.floats(pcm), 16_000)
            stream.inputFinished()
            val v = if (e.isReady(stream)) e.compute(stream) else null
            stream.release()
            v
        } catch (_: Throwable) { null }
    }

    fun save(voice: FloatArray) = prefs.edit().putString("voiceprint", Speaker.encode(voice)).putBoolean("voiceprint_on", true).apply()

    fun forget() = prefs.edit().remove("voiceprint").putBoolean("voiceprint_on", false).apply()

    companion object {
        const val MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$MODEL"
    }
}

/** The arithmetic of voices, kept apart so it can be tested. */
object Speaker {
    /** At or above this, it is the same person (the model gives about 0.8 for the same voice, 0.1–0.3 for others). */
    const val SAME = 0.45f

    fun floats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) out[i] = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / Math.sqrt(na * nb)).toFloat()
    }

    /** The person's voice from a few sentences: their average, each made the same length first. */
    fun average(list: List<FloatArray>): FloatArray? {
        if (list.isEmpty()) return null
        val out = FloatArray(list[0].size)
        for (v in list) {
            if (v.size != out.size) return null
            val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: return null
            for (i in out.indices) out[i] += v[i] / n
        }
        return out
    }

    /** The sentences said when learning the voice must sound like the same person, or it starts again. */
    fun consistent(list: List<FloatArray>): Boolean {
        for (i in list.indices) for (j in i + 1 until list.size) if (cosine(list[i], list[j]) < SAME) return false
        return true
    }

    fun encode(v: FloatArray) = v.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }
    fun decode(s: String): FloatArray? = s.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size >= 64 }?.toFloatArray()
}
