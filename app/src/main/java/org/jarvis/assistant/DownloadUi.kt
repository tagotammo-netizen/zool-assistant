package org.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Every download is shown while it runs: a notification with a progress bar (seen from anywhere), and a panel on
 * Zol's screen with the bar, sizes, speed, time left and a cancel button.
 */
object DownloadUi {
    private const val CHANNEL = "zol_downloads"
    private val main = Handler(Looper.getMainLooper())
    private val lastDrawn = HashMap<String, Long>()
    private var attached = false

    /** Once for the whole app (from [ZolApp]). */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "التنزيلات", NotificationManager.IMPORTANCE_LOW))
        Downloads.listen { job -> main.post { notify(app, job) } }
    }

    private fun notify(context: Context, j: Downloads.Job) {
        val now = System.currentTimeMillis()
        if (j.state == Downloads.State.RUNNING && now - (lastDrawn[j.id] ?: 0L) < 700) return
        lastDrawn[j.id] = now
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val id = 4000 + (j.id.hashCode() and 0xFFF)
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val pct = Downloads.percent(j)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(j.title)
            .setContentText(Downloads.detail(j, now))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (j.state == Downloads.State.RUNNING) {
            b.setOngoing(true).setProgress(100, maxOf(pct, 0), pct < 0)
        } else {
            b.setSmallIcon(if (j.state == Downloads.State.DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setAutoCancel(true).setTimeoutAfter(if (j.state == Downloads.State.DONE) 15_000 else 60_000)
        }
        try { NotificationManagerCompat.from(context).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    /** The panel at the top of an app screen, shown while something downloads. Remove with [Panel.detach]. */
    class Panel(private val activity: Activity) {
        private val d = activity.resources.displayMetrics.density
        private val rows = LinkedHashMap<String, Triple<View, ProgressBar, TextView>>()
        private val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
        }
        private val listener: (Downloads.Job) -> Unit = { j -> main.post { draw(j) } }

        fun attach() {
            val lp = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                setMargins(px(12), px(56), px(12), 0)
            }
            activity.addContentView(box, lp)
            Downloads.listen(listener)
            for (j in Downloads.running()) draw(j)
        }

        fun detach() {
            Downloads.unlisten(listener)
            (box.parent as? ViewGroup)?.removeView(box)
        }

        private fun px(v: Int) = (v * d).toInt()

        private fun draw(j: Downloads.Job) {
            if (activity.isFinishing) return
            val row = rows.getOrPut(j.id) { makeRow(j) }
            val (view, bar, text) = row
            val pct = Downloads.percent(j)
            bar.isIndeterminate = pct < 0 && j.state == Downloads.State.RUNNING
            if (pct >= 0) bar.progress = pct
            text.text = (if (pct >= 0) "$pct% • " else "") + Downloads.detail(j, System.currentTimeMillis())
            if (j.state != Downloads.State.RUNNING) {
                view.findViewWithTag<View>("cancel")?.visibility = View.GONE
                view.postDelayed({
                    box.removeView(view)
                    rows.remove(j.id)
                    if (rows.isEmpty()) box.visibility = View.GONE
                }, if (j.state == Downloads.State.DONE) 2_500 else 8_000)
            }
            box.visibility = View.VISIBLE
        }

        private fun makeRow(j: Downloads.Job): Triple<View, ProgressBar, TextView> {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), px(10), px(14), px(10))
                background = GradientDrawable().apply { setColor(0xF0102033.toInt()); cornerRadius = 16 * d }
                elevation = 8 * d
            }
            val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(activity).apply {
                text = "⬇ ${j.title}"; textSize = 15f; setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(TextView(activity).apply {
                tag = "cancel"
                text = "إلغاء"
                textSize = 14f
                setTextColor(0xFFFF8A80.toInt())
                setPadding(px(10), px(4), px(10), px(4))
                setOnClickListener { Downloads.cancel(j.id) }
            })
            card.addView(top)
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            card.addView(bar, LinearLayout.LayoutParams(-1, px(10)).apply { topMargin = px(8) })
            val text = TextView(activity).apply { textSize = 13f; setTextColor(0xFFB0BEC5.toInt()) }
            card.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
            return Triple(card, bar, text)
        }
    }
}
