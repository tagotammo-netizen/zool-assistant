package org.jarvis.assistant

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The files for listening without internet (Whisper, run on the phone by sherpa-onnx), downloaded inside Zol
 * with a progress bar instead of copied from a computer. "base" is about 160 MB; "small" about 370 MB and
 * understands Arabic better. The voice detector ships inside Zol and is copied next to them.
 */
object OfflinePack {

    data class Model(val id: String, val title: String, val mb: Int)

    val MODELS = listOf(
        Model("base", "الحزمة الصغيرة (أسرع، 160 ميقا)", 160),
        Model("small", "الحزمة الكبيرة (أدق في العربي، 370 ميقا)", 370),
    )

    /** The three files of a model, as sherpa-onnx publishes them. */
    fun files(model: String) = listOf("$model-encoder.int8.onnx", "$model-decoder.int8.onnx", "$model-tokens.txt")

    fun url(model: String, file: String) = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$model/resolve/main/$file"

    /** The voice detector, from inside Zol, where the offline engine looks for it. */
    fun copyDetector(context: Context, dir: File) {
        val target = File(dir, "silero_vad.onnx")
        if (target.length() > 0) return
        try {
            context.assets.open("silero_vad.onnx").use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (_: Exception) {}
    }

    fun installed(dir: File): String? = MODELS.map { it.id }.firstOrNull { m -> files(m).all { File(dir, it).length() > 0 } }

    /**
     * Downloads the model into [dir] (resuming a broken download), then removes the other model so the engine
     * has only one. [done] gets null when it worked, or what went wrong.
     */
    fun download(context: Context, dir: File, model: String, done: (String?) -> Unit) {
        val m = MODELS.firstOrNull { it.id == model } ?: return done("ما في حزمة بالاسم ده")
        val job = Downloads.start("offline-$model", "الاستماع بدون نت: ${m.title}")
        Thread {
            dir.mkdirs()
            copyDetector(context, dir)
            val list = files(model)
            job.items = list.size
            var error: String? = null
            for ((i, name) in list.withIndex()) {
                if (job.cancelled) break
                job.item = i + 1
                job.done = 0
                job.total = 0
                val target = File(dir, name)
                if (target.length() > 0) continue
                error = fetch(url(model, name), target, job)
                if (error != null) break
            }
            val ok = error == null && !job.cancelled && list.all { File(dir, it).length() > 0 }
            if (ok) MODELS.filter { it.id != model }.forEach { other -> files(other.id).forEach { File(dir, it).delete() } }
            Downloads.finish(job, when { ok -> Downloads.State.DONE; job.cancelled -> Downloads.State.CANCELLED; else -> Downloads.State.FAILED }, error)
            done(if (ok) null else error ?: "اتلغى")
        }.start()
    }

    /** One file, carrying on from where a broken download stopped. Null when it worked. */
    private fun fetch(address: String, target: File, job: Downloads.Job): String? {
        val part = File(target.path + ".part")
        return try {
            var url = URL(address)
            var con: HttpURLConnection
            var hops = 0
            while (true) {
                con = url.openConnection() as HttpURLConnection
                con.instanceFollowRedirects = false
                con.connectTimeout = 15_000
                con.readTimeout = 60_000
                if (part.length() > 0) con.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = con.responseCode
                if (code in 300..399 && hops++ < 6) {
                    url = URL(url, con.getHeaderField("Location"))
                    con.disconnect()
                    continue
                }
                break
            }
            val code = con.responseCode
            if (code !in 200..299) { con.disconnect(); return "الخادم رد بـ $code" }
            val resumed = code == 206
            if (!resumed) part.delete()
            job.done = if (resumed) part.length() else 0
            job.total = (if (resumed) part.length() else 0) + con.contentLengthLong.coerceAtLeast(0)
            con.inputStream.use { input -> java.io.FileOutputStream(part, resumed).use { out -> UpdateManager.copy(input, out, job) } }
            con.disconnect()
            if (job.cancelled) return "اتلغى"
            if (job.total > 0 && part.length() < job.total) return "النت قطع في النص. جرّب تاني وبكمّل من وين وقف."
            if (!part.renameTo(target)) return "ما قدرت أحفظ الملف"
            null
        } catch (e: Exception) {
            "مشكلة في النت (${e.message ?: "انقطع"}). جرّب تاني وبكمّل من وين وقف."
        }
    }
}
