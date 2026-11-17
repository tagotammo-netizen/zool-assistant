package org.jarvis.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * EXPERIMENTAL offline listening: a small voice detector (Silero VAD) cuts out what was said and Whisper
 * (through the sherpa-onnx library) turns it into text. Nothing leaves the phone.
 * Needs the native libraries in jniLibs and the model files in [dir]; see [missing].
 */
class WhisperEngine(private val context: Context) {

    private val rate = 16000
    private val ui = Handler(Looper.getMainLooper())
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var loadedLang = ""

    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    /** The folder the model files must be in (the person copies them there from the app). */
    fun dir(): File = File(context.getExternalFilesDir(null), "whisper").apply { mkdirs() }

    private fun pick(vararg suffixes: String): File? {
        val files = dir().listFiles() ?: return null
        for (s in suffixes) files.firstOrNull { it.name.endsWith(s) }?.let { return it }
        return null
    }

    /** Names of the files still missing; empty means ready. */
    fun missing(): List<String> {
        val out = ArrayList<String>()
        if (pick("encoder.int8.onnx", "encoder.onnx") == null) out.add("*-encoder.int8.onnx")
        if (pick("decoder.int8.onnx", "decoder.onnx") == null) out.add("*-decoder.int8.onnx")
        if (pick("tokens.txt") == null) out.add("*-tokens.txt")
        if (!File(dir(), "silero_vad.onnx").exists()) out.add("silero_vad.onnx")
        return out
    }

    /** Loads the models (a few seconds the first time). Returns an error text, or null when ready. */
    private fun load(lang: String): String? {
        if (recognizer != null && vad != null && loadedLang == lang) return null
        release()
        val absent = missing()
        if (absent.isNotEmpty()) return "ملفات ناقصة: " + absent.joinToString("، ")
        val enc = pick("encoder.int8.onnx", "encoder.onnx")!!
        val dec = pick("decoder.int8.onnx", "decoder.onnx")!!
        val tok = pick("tokens.txt")!!
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = rate, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = enc.absolutePath, decoder = dec.absolutePath,
                        language = lang, task = "transcribe", tailPaddings = 1000,
                    ),
                    tokens = tok.absolutePath, numThreads = 2, provider = "cpu", modelType = "whisper",
                ),
                decodingMethod = "greedy_search",
            ),
        )
        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(dir(), "silero_vad.onnx").absolutePath,
                    threshold = 0.5f, minSilenceDuration = 0.6f, minSpeechDuration = 0.25f,
                    windowSize = 512, maxSpeechDuration = 8f,
                ),
                sampleRate = rate, numThreads = 1, provider = "cpu",
            ),
        )
        loadedLang = lang
        return null
    }

    fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        try { vad?.release() } catch (_: Throwable) {}
        recognizer = null
        vad = null
        loadedLang = ""
    }

    fun cancel() { cancelled = true }

    /**
     * Listens until something is said (or about 9 s of silence), then calls exactly one of the callbacks on the main thread:
     * [onText] with the sentence, [onNothing] when nothing was said, [onError] when this engine cannot work.
     */
    @SuppressLint("MissingPermission")
    fun listen(lang: String, onText: (String) -> Unit, onNothing: () -> Unit, onError: (String) -> Unit) {
        if (running) return
        cancelled = false
        running = true
        Thread {
            var record: AudioRecord? = null
            var text: String? = null
            var failure: String? = null
            try {
                failure = load(lang)
                if (failure == null) {
                    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    record = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate),
                    )
                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        failure = "تعذر فتح الميكروفون"
                    } else {
                        record.startRecording()
                        val v = vad!!
                        v.reset()
                        val buffer = ShortArray(1600)                   // 100 ms
                        val started = System.currentTimeMillis()
                        while (!cancelled) {
                            val n = record.read(buffer, 0, buffer.size)
                            if (n < 0) { failure = "خطأ في قراءة الميكروفون ($n)"; break }
                            if (n == 0) continue
                            v.acceptWaveform(FloatArray(n) { buffer[it] / 32768f })
                            if (!v.empty()) {
                                val segment = v.front()
                                v.pop()
                                text = transcribe(segment.samples)
                                break
                            }
                            if (!v.isSpeechDetected() && System.currentTimeMillis() - started > 9_000) break
                        }
                    }
                }
            } catch (t: Throwable) {                                    // includes UnsatisfiedLinkError: native libraries missing
                failure = t.javaClass.simpleName + ": " + (t.message ?: "")
            } finally {
                try { record?.stop() } catch (_: Throwable) {}
                try { record?.release() } catch (_: Throwable) {}
                running = false
            }
            val result = text
            val error = failure
            ui.post {
                when {
                    cancelled -> {}
                    error != null -> onError(error)
                    result.isNullOrBlank() -> onNothing()
                    else -> onText(result)
                }
            }
        }.start()
    }

    private fun transcribe(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, rate)
            r.decode(stream)
            return r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }
}
