package org.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Plays a surah streamed from the internet, one after another. It pauses for a phone call or another app,
 * gets quieter while Zol speaks, and falls back to Sheikh Mishary when the chosen reciter has no recording of a surah.
 */
class QuranPlayer(private val context: Context, private val onEvent: (Event) -> Unit) {

    sealed class Event {
        data class Started(val surah: Int, val reciter: String, val fellBack: Boolean) : Event()
        object Finished : Event()
        data class Failed(val surah: Int) : Event()
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var queue = ArrayDeque<Int>()
    private var reciter = Quran.DEFAULT_RECITER
    private var fellBack = false
    private var ducked = false
    private var pausedByFocus = false

    /** Go on to the next surah when one ends. */
    var continuous = true

    var current = 0
        private set

    var paused = false
        private set

    val active get() = player != null

    val playing get() = player?.isPlaying == true

    /** Play [surahs] in order (and, if [continuous], what comes after the last one). */
    fun play(surahs: List<Int>, reciterId: String) {
        queue = ArrayDeque(surahs.drop(1))
        reciter = reciterId
        start(surahs.first(), reciterId, false)
    }

    private fun start(surah: Int, reciterId: String, isFallback: Boolean) {
        release(keepFocus = true)
        current = surah
        fellBack = isFallback
        paused = false
        if (!requestFocus()) { onEvent(Event.Failed(surah)); return }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            val local = file(context, surah, reciterId)
            if (local.length() > 0) mp.setDataSource(local.path) else mp.setDataSource(Quran.url(surah, reciterId))
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                if (ducked) it.setVolume(DUCK, DUCK)
                it.start()
                onEvent(Event.Started(surah, reciterId, isFallback))
            }
            mp.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                val next = queue.removeFirstOrNull() ?: if (continuous && surah < 114) surah + 1 else null
                if (next != null) start(next, reciter, false) else { stop(); onEvent(Event.Finished) }
            }
            mp.setOnErrorListener { it, _, _ ->
                if (player !== it) return@setOnErrorListener true
                // this reciter has no recording of this surah: the same surah by Sheikh Mishary
                if (reciterId != Quran.DEFAULT_RECITER) main.post { start(surah, Quran.DEFAULT_RECITER, true) }
                else main.post { stop(); onEvent(Event.Failed(surah)) }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            stop()
            onEvent(Event.Failed(surah))
        }
    }

    fun pause() {
        val mp = player ?: return
        try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}
        paused = true
    }

    /** False when nothing was playing. */
    fun resume(): Boolean {
        val mp = player ?: return false
        if (!requestFocus()) return false
        try { mp.start() } catch (_: Exception) { return false }
        paused = false
        return true
    }

    fun next() { if (current > 0) start(queue.removeFirstOrNull() ?: Quran.next(current), reciter, false) }

    fun prev() { if (current > 0) start(Quran.prev(current), reciter, false) }

    fun stop() {
        release(keepFocus = false)
        queue.clear()
        paused = false
    }

    /** Quieter while Zol talks. */
    fun duck(on: Boolean) {
        ducked = on
        try { player?.setVolume(if (on) DUCK else 1f, if (on) DUCK else 1f) } catch (_: Exception) {}
    }

    private fun release(keepFocus: Boolean) {
        val mp = player
        player = null
        if (mp != null) try { mp.reset(); mp.release() } catch (_: Exception) {}
        if (!keepFocus) focus?.let { audio.abandonAudioFocusRequest(it) }
        if (!keepFocus) focus = null
    }

    private fun requestFocus(): Boolean {
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change -> onFocus(change) }, main)
            .build()
        focus = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> try { player?.setVolume(DUCK, DUCK) } catch (_: Exception) {}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) { pause(); pausedByFocus = true }
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); pausedByFocus = false }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!ducked) try { player?.setVolume(1f, 1f) } catch (_: Exception) {}
                if (pausedByFocus) { pausedByFocus = false; resume() }
            }
        }
    }

    companion object {
        const val DUCK = 0.2f

        /** Where a downloaded surah is kept on the phone. */
        fun file(context: Context, surah: Int, reciterId: String): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "quran/$reciterId/" + String.format(Locale.US, "%03d.mp3", surah))
        }

        fun downloaded(context: Context, reciterId: String) = (1..114).count { file(context, it, reciterId).length() > 0 }

        /**
         * Download [surahs] one after another, in the background. A half-done file is never used: it is written
         * under another name and renamed when complete. [finished] runs on the main thread.
         */
        fun download(context: Context, surahs: List<Int>, reciterId: String, finished: (saved: Int, failed: Int) -> Unit) {
            val main = Handler(Looper.getMainLooper())
            val job = Downloads.start("quran-$reciterId",
                if (surahs.size == 1) "تنزيل ${Quran.title(surahs[0])}" else "تنزيل ${surahs.size} سورة")
            job.items = surahs.size
            Thread {
                var saved = 0
                var failed = 0
                for ((i, n) in surahs.withIndex()) {
                    if (job.cancelled) break
                    job.item = i + 1
                    job.done = 0
                    job.total = 0
                    Downloads.changed(job)
                    val target = file(context, n, reciterId)
                    if (target.length() > 0) { saved++; continue }
                    target.parentFile?.mkdirs()
                    val part = File(target.path + ".part")
                    try {
                        val con = URL(Quran.url(n, reciterId)).openConnection() as HttpURLConnection
                        con.connectTimeout = 15_000
                        con.readTimeout = 30_000
                        if (con.responseCode !in 200..299) { failed++; con.disconnect(); continue }
                        job.total = con.contentLengthLong.coerceAtLeast(0)
                        con.inputStream.use { input -> part.outputStream().use { UpdateManager.copy(input, it, job) } }
                        con.disconnect()
                        if (!job.cancelled && part.length() > 10_000 && part.renameTo(target)) saved++ else { part.delete(); if (!job.cancelled) failed++ }
                    } catch (e: Exception) {
                        part.delete()
                        failed++
                    }
                }
                Downloads.finish(job, when {
                    job.cancelled -> Downloads.State.CANCELLED
                    failed > 0 && saved == 0 -> Downloads.State.FAILED
                    else -> Downloads.State.DONE
                }, if (failed > 0) "$failed ما نزلت" else null)
                main.post { finished(saved, failed) }
            }.start()
        }
    }
}
