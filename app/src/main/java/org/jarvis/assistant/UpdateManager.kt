package org.jarvis.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the GitHub Releases page: looks for a newer version, asks, downloads the APK and hands it to
 * Android's installer (which asks the person once more). Android only installs it over the current app when
 * both were signed with the same key.
 */
class UpdateManager(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs get() = activity.getSharedPreferences("jarvis", Activity.MODE_PRIVATE)

    private fun current(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    /** [quiet]: the daily check by itself, which says nothing unless there is an update. */
    fun check(quiet: Boolean) {
        if (quiet) {
            val last = prefs.getLong("update_checked", 0L)
            if (System.currentTimeMillis() - last < 20 * 3_600_000L) return
        }
        prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply()
        if (!quiet) toast("بشوف لو في تحديث…")
        Thread {
            val release = try {
                val con = URL(Updates.LATEST).openConnection() as HttpURLConnection
                con.connectTimeout = 10_000
                con.readTimeout = 15_000
                con.setRequestProperty("Accept", "application/vnd.github+json")
                con.setRequestProperty("User-Agent", "Zol-Android")
                val code = con.responseCode
                val body = if (code in 200..299) con.inputStream.bufferedReader().readText() else null
                con.disconnect()
                body?.let { Updates.parse(it) }
            } catch (_: Exception) { null }
            main.post {
                if (activity.isFinishing) return@post
                when {
                    release == null -> if (!quiet) toast("ما قدرت أعرف. اتأكد من النت، أو مافي إصدار منشور لسه.")
                    !Updates.newer(release.version, current()) -> if (!quiet) toast("زول عندك آخر إصدار (${current()}).")
                    release.version == prefs.getString("update_skipped", "") && quiet -> {}
                    else -> offer(release)
                }
            }
        }.start()
    }

    private fun offer(r: Updates.Release) {
        AlertDialog.Builder(activity)
            .setTitle("في إصدار جديد من زول: ${r.version}")
            .setMessage("عندك ${current()}.\n\n" + r.notes.ifBlank { "تحسينات وإصلاحات." } + "\n\nأنزّلو وأثبّتو؟")
            .setPositiveButton("أيوه، نزّلو") { _, _ -> download(r) }
            .setNeutralButton("بعدين", null)
            .setNegativeButton("تخطّى الإصدار ده") { _, _ -> prefs.edit().putString("update_skipped", r.version).apply() }
            .show()
    }

    private fun apkFile() = File(activity.cacheDir, "updates/zol.apk")

    private fun download(r: Updates.Release) {
        val job = Downloads.start("update", "تحديث زول ${r.version}")
        Thread {
            val target = apkFile()
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            var error: String? = null
            val ok = try {
                var url = URL(r.apk)
                var con = url.openConnection() as HttpURLConnection
                var hops = 0
                // GitHub sends the file from another address
                while (con.responseCode in 300..399 && hops++ < 5) {
                    url = URL(con.getHeaderField("Location"))
                    con.disconnect()
                    con = url.openConnection() as HttpURLConnection
                }
                con.connectTimeout = 15_000
                con.readTimeout = 60_000
                if (con.responseCode !in 200..299) { error = "الخادم رد بـ ${con.responseCode}"; false } else {
                    job.total = con.contentLengthLong.coerceAtLeast(0)
                    con.inputStream.use { input -> part.outputStream().use { out -> copy(input, out, job) } }
                    con.disconnect()
                    if (job.cancelled) false else part.length() > 1_000_000 && (target.delete() || true) && part.renameTo(target)
                }
            } catch (e: Exception) { error = e.message; false }
            if (!ok) part.delete()
            Downloads.finish(job, when { ok -> Downloads.State.DONE; job.cancelled -> Downloads.State.CANCELLED; else -> Downloads.State.FAILED }, error)
            main.post {
                if (activity.isFinishing) return@post
                if (ok) install() else if (!job.cancelled) toast("التنزيل ما كمل. جرّب تاني لما النت يكون أحسن.")
            }
        }.start()
    }

    companion object {
        /** Copy while counting, stopping when the person cancels. */
        fun copy(input: java.io.InputStream, out: java.io.OutputStream, job: Downloads.Job) {
            val buf = ByteArray(64 * 1024)
            while (!job.cancelled) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                job.done += n
                Downloads.changed(job)
            }
        }
    }

    /** Hand the APK to Android. The first time, Android asks to allow Zol to install apps. */
    fun install() {
        val file = apkFile()
        if (!file.exists()) return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            prefs.edit().putBoolean("update_waiting", true).apply()
            toast("اسمح لزول يثبّت التحديث، وبعدين ارجع")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        prefs.edit().putBoolean("update_waiting", false).apply()
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Back from the "install unknown apps" screen with permission: carry on. */
    fun resumeIfWaiting() {
        if (prefs.getBoolean("update_waiting", false) && (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls())) install()
    }
}
