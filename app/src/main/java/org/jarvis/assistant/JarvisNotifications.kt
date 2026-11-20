package org.jarvis.assistant

import android.app.Notification
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads new messages (and tells about an incoming call) aloud through Jarvis, and lets the person answer
 * a message by voice without opening the app. Works only after the person grants "notification access".
 */
class JarvisNotifications : NotificationListenerService() {

    private var lastText = ""
    private var lastAt = 0L
    private val repeats = RepeatFilter()

    private val messagingApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.viber.voip",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val service = JarvisService.instance ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val important = prefs.getBoolean("pro_on", false) &&
            ProactivePlan.isImportant(title, prefs.getString("pro_people", "").orEmpty().lines().filter { it.isNotBlank() })
        val speak = important || prefs.getBoolean("read_notifs", false) || prefs.getString("mode", "normal") == "car"

        // an incoming call: the phone is ringing
        if (n.category == Notification.CATEGORY_CALL) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            if (speak && audio.mode == AudioManager.MODE_RINGTONE) {
                service.announce("اتصال وارد من ${title.ifEmpty { "رقم غير معروف" }}. قل: أجب، أو: ارفض.", true, urgent = true)
            }
            return
        }
        if (sbn.isOngoing) return
        if (n.category != Notification.CATEGORY_MESSAGE && sbn.packageName !in messagingApps) return
        if (text.isEmpty()) return
        val line = text.take(160)
        val now = System.currentTimeMillis()
        if (line == lastText && now - lastAt < 4_000) return          // the same message updated again
        lastText = line; lastAt = now

        if (RepeatFilter.isStatus(title) || !repeats.allow(sbn.packageName + "/" + title, now)) return
        val who = title.ifEmpty { "رسالة" }
        service.noteNotification("$who: $line")
        val action = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        service.noteReplyTarget(who, action, action?.remoteInputs?.firstOrNull())
        // a WhatsApp voice message: written out and read, instead of "🎤 رسالة صوتية (0:12)"
        if (Fun.isVoiceNote(line) && sbn.packageName.startsWith("com.whatsapp") && prefs.getBoolean("vn_transcribe", false)) {
            service.voiceNoteFrom(who, sbn.postTime)
            return
        }
        if (speak) {
            val hint = if (action != null) " قل: رد بكذا، للرد." else ""
            service.announce("رسالة من $who: $line.$hint", true, urgent = important)
        }
    }
}
