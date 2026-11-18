package org.jarvis.assistant

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash so the app can show it on the next start (see [MainActivity]) instead of just closing.
 * The text stays on the phone; the person decides whether to copy and send it.
 */
class ZolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
                getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                    .putString("last_crash", "${Brand.NAME_EN} $version • ${thread.name}\n${trace.take(6000)}")
                    .commit()                                         // must be on disk before the process dies
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }
}
