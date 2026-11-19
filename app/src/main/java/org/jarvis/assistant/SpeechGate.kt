package org.jarvis.assistant

import kotlin.math.sqrt

/**
 * Listening without asking Google all the time. Zol hears the microphone itself and only opens a recognition request
 * when someone starts speaking, so a quiet room makes no requests at all ("too many requests" came from asking
 * again every few seconds in silence). This decides "someone started speaking" from loudness alone: louder than the
 * room's own noise for a moment. Pure logic, tested on the computer.
 */
class SpeechGate(
    private val minLevel: Double = 40.0,      // never trigger on anything quieter (16-bit RMS; some phones give speech at ~80)
    private val ratio: Double = 2.5,          // this many times the room's noise
    private val needMs: Long = 140,           // loud for this long = speech, not a click
) {
    /** The room's noise, learnt while nobody speaks. */
    var noise = 200.0
        private set
    private var loudSince = -1L
    private var armed = true

    fun threshold() = maxOf(minLevel, noise * ratio)

    /** One frame of loudness at time [t] (ms). True once, when speech starts (then again only after a pause). */
    fun feed(level: Double, t: Long): Boolean {
        if (level >= threshold()) {
            if (loudSince < 0) loudSince = t
            val loudFor = t - loudSince
            // loud for many seconds without a break is the room (a fan, a TV), not a person: learn it
            if (loudFor > STEADY_MS) noise = (noise * 0.98 + level / ratio * 1.1 * 0.02).coerceIn(8.0, 4000.0)
            if (armed && loudFor >= needMs) { armed = false; return true }
        } else {
            loudSince = -1L
            armed = true
            // follow the room slowly (a fan turning on), faster when it gets quieter
            noise = if (level < noise) noise * 0.9 + level * 0.1 else noise * 0.995 + level * 0.005
            noise = noise.coerceIn(8.0, 4000.0)
        }
        return false
    }

    fun reset() { loudSince = -1L; armed = true }

    companion object {
        const val STEADY_MS = 4_000L

        /** From the loudness of each 20 ms of a recording: (room noise, speech) as the 20th and 97th percentiles. */
        fun stats(levels: List<Double>): Pair<Double, Double> {
            if (levels.isEmpty()) return 0.0 to 0.0
            val sorted = levels.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            return at(0.2) to at(0.97)
        }

        /** How much to amplify a quiet microphone so speech reaches a normal level (about 3000 RMS). */
        fun gainFor(speechPeak: Double) = (3000.0 / maxOf(speechPeak, 60.0)).coerceIn(1.0, 16.0)

        /** Amplify 16-bit little-endian samples in place, without wrapping around (clipped). */
        fun amplify(pcm: ByteArray, length: Int, gain: Double) {
            if (gain <= 1.01) return
            var i = 0
            while (i + 1 < length) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                val a = (v * gain).toInt().coerceIn(-32768, 32767)
                pcm[i] = (a and 0xFF).toByte()
                pcm[i + 1] = (a shr 8 and 0xFF).toByte()
                i += 2
            }
        }

        /** RMS of 16-bit little-endian samples. */
        fun level(pcm: ByteArray, length: Int): Double {
            var sum = 0.0
            var n = 0
            var i = 0
            while (i + 1 < length) {
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
                sum += v * v
                n++
                i += 2
            }
            return if (n == 0) 0.0 else sqrt(sum / n)
        }
    }
}

/** Keeps the last moments of sound, so the start of a sentence ("زول…") is not lost while a request opens. */
class PreRoll(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var pos = 0
    private var filled = 0

    fun add(data: ByteArray, length: Int) {
        for (i in 0 until length) { buf[pos] = data[i]; pos = (pos + 1) % capacity }
        filled = minOf(capacity, filled + length)
    }

    /** Oldest first. */
    fun take(): ByteArray {
        val out = ByteArray(filled)
        val start = (pos - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = buf[(start + i) % capacity]
        filled = 0
        return out
    }
}

/** A download the person can watch: what, how much, how fast, how long left. */
object Downloads {

    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    class Job(val id: String, val title: String) {
        @Volatile var done = 0L
        @Volatile var total = 0L            // 0: not known yet
        @Volatile var item = 0              // for several files: which one (from 1)
        @Volatile var items = 0
        @Volatile var state = State.RUNNING
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        val started = System.currentTimeMillis()
    }

    /** 0..100, or -1 when the size is not known. */
    fun percent(j: Job): Int = when {
        j.state == State.DONE -> 100
        j.items > 1 && j.total <= 0 -> (100 * (j.item - 1) / j.items).coerceIn(0, 99)
        j.total > 0 -> if (j.items > 1) {
            (100.0 * ((j.item - 1) + j.done.toDouble() / j.total) / j.items).toInt().coerceIn(0, 99)
        } else (100 * j.done / j.total).toInt().coerceIn(0, 100)
        else -> -1
    }

    fun mb(bytes: Long) = String.format(java.util.Locale.US, "%.1f", bytes / 1_048_576.0)

    /** "سورة 3 من 42 • 4.2 من 9.8 ميقا • 1.3 ميقا/ث • فاضل 5 ث" */
    fun detail(j: Job, now: Long): String {
        val parts = ArrayList<String>()
        when (j.state) {
            State.DONE -> return "خلص ✓"
            State.FAILED -> return "ما كمل: " + (j.error ?: "مشكلة في النت")
            State.CANCELLED -> return "اتلغى"
            State.RUNNING -> {}
        }
        if (j.items > 1) parts.add("${j.item} من ${j.items}")
        if (j.total > 0) parts.add("${mb(j.done)} من ${mb(j.total)} ميقا") else if (j.done > 0) parts.add("${mb(j.done)} ميقا")
        val secs = (now - j.started) / 1000.0
        if (secs >= 1 && j.done > 0 && j.items <= 1) {
            val speed = j.done / secs
            parts.add("${mb(speed.toLong())} ميقا/ث")
            if (j.total > j.done && speed > 0) {
                val left = ((j.total - j.done) / speed).toLong()
                parts.add("فاضل " + if (left >= 60) "${left / 60} دقيقة" else "$left ث")
            }
        }
        return parts.joinToString(" • ").ifEmpty { "بدأ…" }
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Job) -> Unit>()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun listen(l: (Job) -> Unit) { listeners.add(l) }
    fun unlisten(l: (Job) -> Unit) { listeners.remove(l) }

    fun start(id: String, title: String): Job = Job(id, title).also { jobs[id] = it; changed(it) }

    fun running(): List<Job> = jobs.values.filter { it.state == State.RUNNING }

    fun get(id: String) = jobs[id]

    fun cancel(id: String) { jobs[id]?.cancelled = true }

    /** Called often while downloading; listeners decide how often to redraw. */
    fun changed(j: Job) { for (l in listeners) l(j) }

    fun finish(j: Job, state: State, error: String? = null) {
        j.state = state
        j.error = error
        changed(j)
        if (state != State.RUNNING) jobs.remove(j.id)
    }
}


/** Sentences Whisper makes up from noise or silence (it learned them from subtitles). */
private val whisperInventions = listOf("نانسي قنقر", "اشتركوا في القناة", "اشترك في القناة", "شكرا للمشاهدة", "شكراً للمشاهدة",
    "ترجمة الفيلم", "Thank you for watching", "Subscribe")

/**
 * The words Whisper wrote: [segments] are (text, chance that it was not speech); pieces that were probably not speech
 * and the lines Whisper invents from noise are dropped. Without segments, [text] is used.
 */
fun whisperText(text: String, segments: List<Pair<String, Double>>?): String {
    val words = segments?.filter { it.second < 0.6 }?.joinToString(" ") { it.first } ?: text
    val clean = words.replace(Regex("[\"«»]"), "").replace(Regex("\\s+"), " ").trim()
    if (clean.length < 2 || whisperInventions.any { clean.contains(it, ignoreCase = true) }) return ""
    return clean
}
