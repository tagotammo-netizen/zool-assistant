package org.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.RemoteInput
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.location.LocationManager
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The heart of Johnny on the phone. As an accessibility service it keeps running over every app,
 * listens for "جوني ..." and controls the screen without touch.
 */
class JarvisService : AccessibilityService() {

    companion object {
        var instance: JarvisService? = null
        var onLog: ((String) -> Unit)? = null
        val history = ArrayList<String>()
        private const val CHANNEL = "jarvis_listening"

        /** Placeholders that chat apps report as if they were typed text (already normalised). */
        private val PLACEHOLDERS = setOf("مراسله", "رساله", "اكتب رساله", "اكتب رسالتك", "الرساله", "رساله نصيه", "بحث", "ابحث",
            "message", "type a message", "text message", "search", "write a message")

        /** Instructions for the optional language model: turn messy speech into one of our commands. */
        private val AI_PROMPT = """
            You are Johnny, a voice assistant on an Android phone for a person who cannot use their hands.
            The user's sentence comes from speech recognition (Arabic, Sudanese dialect or English) and may contain mistakes.
            Sudanese words you will meet: داير/عايز = I want, هسي/هسه = now, شنو = what, منو = who, ياخ/يا زول = filler,
            عليك الله/بالله = please, دق ل/اضرب ل = call, ولع = turn on, طفي = turn off, جيب/وريني = open or show,
            البطاريه often means the flashlight, ليهو/ليها = to him/her, قول ليهو = saying.
            If the user wants the phone to do something, rewrite the request as ONE of these command forms, keeping names as spoken:
            افتح <app> | اتصل ب <name> | ارسل رساله ل <name> تقول <text> | اكتب <text> | ابحث عن <query> | اضغط علي <label> |
            ارجع | الرئيسيه | الاشعارات | انزل | اطلع | الارقام | علي الصوت | وطي الصوت | ولع <device> | طفي <device> |
            ولع الكشاف | طفي الكشاف | اقرا الشاشه | منبه الساعه <hour> <minute> | موقت <n> دقائق | اقفل الشاشه |
            ذكرني ب<text> الساعه <hour> | ارسل ل<name> واتساب تقول <text> | رد ب<text> | الشبكه | اقرا المقال | ايقاف مؤقت |
            الاغنيه التاليه | التقط صوره | افتح محادثه <name> | اجب | ارفض | اقرا الاشعارات | شغل مشهد <name>
            Two commands can be joined with the word ثم.
            If the request needs SEVERAL steps inside apps (for example: find something, fill a form, reply to the last message),
            put the whole goal in "agent" instead of "command".
            Otherwise answer the question briefly (at most two short sentences) in the user's own language and dialect.
            Reply ONLY with JSON: {"command": "<command or empty>", "reply": "<spoken answer or empty>", "agent": "<goal or empty>"}
        """.trimIndent()
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var errors = 0
    private var onDevice = false            // using the phone's own offline recogniser
    private var forceNormal = false         // the offline pack is missing: use the normal recogniser
    private var blocked = false             // the system refused the microphone: wait for the person
    private var lastActivity = 0L
    private val dog = Handler(Looper.getMainLooper())

    // context for follow-up commands
    private var lastCmds: List<Cmd> = emptyList()
    private var lastContact: String? = null
    private var lastApp: String? = null
    private var lastAppAt = 0L
    private var arabic = true
    var listening = false
        private set

    // a question waiting for "yes" / "no" (for example before sending a message)
    private var pending: (() -> String)? = null
    private var pendingUntil = 0L
    private var pendingNo: (() -> String)? = null

    // after the wake word alone ("جوني") the next sentence is the command, no wake word needed
    private var awakeUntil = 0L
    val awake get() = listening && SystemClock.elapsedRealtime() < awakeUntil

    /** For the system check in the app: can Johnny speak? */
    val voiceReady get() = ttsReady

    /** Is an Arabic voice installed? null until the engine has answered. Without it Arabic replies are silent or garbled. */
    @Volatile var arabicVoice: Boolean? = null
        private set
    private var ttsRetried = false
    private var warnedVoice = false

    // numbers drawn over the buttons on screen
    private var overlay: View? = null
    private var numbered: List<AccessibilityNodeInfo> = emptyList()

    // floating button shown over other apps
    private var bubble: ImageView? = null

    private val prefs get() = getSharedPreferences("jarvis", MODE_PRIVATE)

    private fun t(ar: String, en: String) = if (arabic) ar else en

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun log(line: String) {
        main.post {
            history.add(line)
            if (history.size > 100) history.removeAt(0)
            onLog?.invoke(line)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initVoice()
        log("الخدمة تعمل.")
        ProactiveReceiver.schedule(this)
        try { registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) {}
        Commands.userDict = Commands.parseDict(prefs.getString("dialect", "").orEmpty())
        dog.removeCallbacksAndMessages(null)
        dog.postDelayed(watchdog, 15_000)
        main.removeCallbacks(alertsTask)
        main.postDelayed(alertsTask, 20_000)
        try {
            registerReceiver(ecoReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
        } catch (_: Exception) {}
        if (prefs.getBoolean("bubble", true)) showBubble()
        // work in the background: start listening by itself, without opening the app
        if (prefs.getBoolean("auto_listen", false)) main.postDelayed({ if (!listening) startListening(false) }, 1500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun shutdown() {
        dog.removeCallbacksAndMessages(null)
        listening = false
        main.removeCallbacksAndMessages(null)
        hideNumbers()
        hideGrid()
        try { unregisterReceiver(ecoReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        head?.stop()
        head = null
        hideBubble()
        try { recognizer?.cancel() } catch (_: Exception) {}
        instance = null
    }

    // ------------------------------------------------------------------ listening

    /** [byUser] = the person asked for it (button or voice), so remember the choice for next time. */
    fun startListening(byUser: Boolean = true) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            log("لا يوجد إذن للميكروفون.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("خدمة التعرف على الكلام غير متوفرة في هذا الهاتف.")
            return
        }
        if (byUser) { prefs.edit().putBoolean("auto_listen", true).apply(); blocked = false }
        showNotification()
        ensureRecognizer()
        listening = true
        errors = 0
        log("أستمع الآن. قل: جوني ثم الأمر.")
        paintBubble(false)
        main.removeCallbacks(alertsTask)
        main.postDelayed(alertsTask, 20_000)
        listenOnce()
    }

    fun stopListening(byUser: Boolean = true) {
        if (byUser) prefs.edit().putBoolean("auto_listen", false).apply()
        listening = false
        main.removeCallbacksAndMessages(null)
        whisper.cancel()
        try { recognizer?.cancel() } catch (_: Exception) {}
        updateForeground(false)
        paintBubble(false)
        log("توقف الاستماع.")
    }

    /** Keep the foreground notification with the right types: microphone while listening, camera while the head pointer runs. */
    private fun updateForeground(mic: Boolean = listening) {
        val cam = head?.running == true
        if (!mic && !cam) {
            try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            return
        }
        showNotification(mic, cam)
    }

    private fun showNotification(mic: Boolean = true, cam: Boolean = head?.running == true): Boolean {
        try {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Johnny", NotificationManager.IMPORTANCE_LOW))
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Johnny")
                .setContentText(when {
                    mic && cam -> "أستمع، والمؤشر يتبع رأسك"
                    cam -> "المؤشر يتبع رأسك"
                    else -> "أستمع... قل: جوني"
                })
                .setOngoing(true)
                .build()
            var type = 0
            if (Build.VERSION.SDK_INT >= 30) {
                if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (cam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            ServiceCompat.startForeground(this, 1, n, type)
            return true
        } catch (e: Exception) {
            log("تنبيه: تعذر إظهار إشعار الاستماع (${e.javaClass.simpleName}).")
            return false
        }
    }

    private fun recognizerIntent(lang: String? = null) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang ?: prefs.getString("lang", "ar-SA"))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineWanted())
    }

    /** Offline is used only after the Arabic pack was found on the phone; otherwise the normal recogniser, as before. */
    private fun offlineWanted() = prefs.getBoolean("offline", true) && prefs.getBoolean("offline_ready", false) && !forceNormal

    /** The phone's own offline recogniser when there is one (Android 13+), otherwise the normal one. */
    private fun ensureRecognizer() {
        if (recognizer != null || useWhisper()) return
        val wantOffline = offlineWanted()
        val r = if (Build.VERSION.SDK_INT >= 33 && wantOffline && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            onDevice = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            onDevice = false
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        r.setRecognitionListener(listener)
        recognizer = r
    }

    private fun rebuildRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    /** Checks every 15 s that listening is still alive, and brings it back by itself if Android stopped it. */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                ecoCheck()
                if (!listening && !ecoPaused && prefs.getBoolean("auto_listen", false) && !blocked && granted(Manifest.permission.RECORD_AUDIO)) {
                    log("أعدت تشغيل الاستماع.")
                    startListening(false)
                } else if (listening && lastActivity > 0 && now - lastActivity > 40_000 && tts?.isSpeaking != true) {
                    log("الاستماع تعطّل، أعيد تشغيله.")
                    rebuildRecognizer()
                    ensureRecognizer()
                    listenOnce()
                }
            } catch (e: Exception) {
                log("حارس الاستماع: ${e.message}")
            }
            dog.postDelayed(this, 15_000)
        }
    }

    private fun listenOnce() {
        if (!listening) return
        lastActivity = SystemClock.elapsedRealtime()
        if (tts?.isSpeaking == true) { resume(500); return }      // do not listen to my own voice
        if (useWhisper()) { whisperListen(); return }
        ensureRecognizer()
        val intent = recognizerIntent()
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            log("خطأ في الاستماع: ${e.message}")
            resume(1500)
        }
    }

    private val whisper by lazy { WhisperEngine(this) }
    private var whisperFailed = false

    /** The experimental offline Whisper engine is used only when chosen in settings and while it works. */
    private fun useWhisper() = prefs.getString("engine", "google") == "whisper" && !whisperFailed

    fun whisperStatus(): String {
        val absent = whisper.missing()
        return if (absent.isEmpty()) "ملفات Whisper كاملة. جاهز." else "ملفات Whisper ناقصة: ${absent.joinToString("، ")}"
    }

    fun whisperFolder(): String = whisper.dir().absolutePath

    /** Called after the person changes the engine or copies the model files: try Whisper again. */
    fun resetWhisper() {
        whisperFailed = false
        whisper.release()
        if (listening) { main.removeCallbacks(resumeTask); resume(300) }
    }

    private fun whisperListen() {
        if (whisper.running) { resume(300); return }              // the previous round is still finishing
        val lang = if (prefs.getString("lang", "ar-SA") == "ar-SA") "ar" else "en"
        whisper.listen(lang,
            onText = { text ->
                lastActivity = SystemClock.elapsedRealtime()
                errors = 0
                processResults(listOf(text))
            },
            onNothing = { lastActivity = SystemClock.elapsedRealtime(); resume(100) },
            onError = { message ->
                whisperFailed = true
                log("Whisper لا يعمل: $message. أعود إلى محرك Google.")
                resume(500)
            })
    }

    // a message that was just read aloud from a notification, so that "رد بكذا" can answer it without opening the app
    private var replyAction: Notification.Action? = null
    private var replyInput: RemoteInput? = null
    private var replyFrom = ""
    private var replyAt = 0L
    private val recentNotes = ArrayList<String>()

    fun noteReplyTarget(from: String, action: Notification.Action?, input: RemoteInput?) {
        replyAction = action; replyInput = input; replyFrom = from; replyAt = SystemClock.elapsedRealtime()
        main.post { lastContact = from }
    }

    fun noteNotification(line: String) {
        main.post {
            recentNotes.add(0, line)
            while (recentNotes.size > 6) recentNotes.removeAt(recentNotes.size - 1)
        }
    }

    /** Says something that happened by itself (a message arrived, the phone is ringing) and then listens for the answer. */
    fun announce(text: String, openWindow: Boolean, urgent: Boolean = false) {
        main.post {
            if (!listening && !urgent) return@post
            if (mode() == "night" && !urgent) return@post
            arabic = Commands.isArabic(text)
            whisper.cancel()
            try { recognizer?.cancel() } catch (_: Exception) {}
            if (openWindow && listening) {
                awakeUntil = SystemClock.elapsedRealtime() + 20_000
                paintBubble(false)
                main.postDelayed({ paintBubble(false) }, 20_200)
            }
            beep(1)
            respond(text)
        }
    }

    /** Short tones, for a person who may not be looking at the screen: 1 = I am listening, 2 = on it, 3 = did not understand. */
    private fun beep(kind: Int) {
        if (!prefs.getBoolean("tones", true) || mode() == "night") return
        try {
            val gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            gen.startTone(when (kind) {
                1 -> ToneGenerator.TONE_PROP_BEEP
                2 -> ToneGenerator.TONE_PROP_ACK
                else -> ToneGenerator.TONE_PROP_NACK
            }, 160)
            main.postDelayed({ try { gen.release() } catch (_: Exception) {} }, 700)
        } catch (_: Exception) {}
    }

    private fun rememberUnknown(text: String) {
        val clean = text.trim().take(120)
        if (clean.length < 3) return
        val list = prefs.getString("unknown", "").orEmpty().lines().filter { it.isNotBlank() }.toMutableList()
        list.removeAll { Commands.normalize(it) == Commands.normalize(clean) }
        list.add(0, clean)
        while (list.size > 15) list.removeAt(list.size - 1)
        prefs.edit().putString("unknown", list.joinToString("\n")).apply()
    }

    // ---- home scenes: "اسم المشهد=جهاز:on,جهاز:off"

    private fun scenes(): List<Pair<String, List<Pair<String, Boolean>>>> =
        prefs.getString("scenes", "").orEmpty().lines().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at < 1) return@mapNotNull null
            val name = Commands.normalize(line.substring(0, at)).trim()
            val steps = line.substring(at + 1).split(",", "،").mapNotNull { part ->
                val p = part.trim().split(":")
                if (p.size != 2) null else p[0].trim() to (p[1].trim().lowercase() in setOf("on", "1", "شغل", "تشغيل", "ولع", "افتح"))
            }
            if (name.isEmpty() || steps.isEmpty()) null else name to steps
        }

    private fun findScene(spoken: String, exact: Boolean = false): Pair<String, List<Pair<String, Boolean>>>? {
        val n = Commands.normalize(spoken).trim()
        return scenes().firstOrNull { it.first == n || (!exact && it.first.length >= 3 && " $n ".contains(" ${it.first} ")) }
    }

    private fun runScene(name: String): String {
        val scene = findScene(name) ?: return t("لا يوجد مشهد باسم \"$name\". أضفه من الإعدادات.", "There is no scene named \"$name\". Add it in settings.")
        Thread {
            val failed = ArrayList<String>()
            scene.second.forEach { (device, on) ->
                val problem = homeNow(device, on)
                if (problem != null) failed.add(problem)
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
            if (failed.isNotEmpty()) main.post {
                val text = failed.joinToString(" ")
                log("جوني: $text")
                say(text, add = true)
            }
        }.start()
        return t("حاضر، شغّلت مشهد ${scene.first}.", "OK, scene ${scene.first} is on.")
    }

    // ---- phone calls

    // deprecated since Android 10 in favour of InCallService, but still the only way for an assistant that is not the dialer
    @Suppress("MissingPermission", "DEPRECATION")
    private fun answerCall(): String {
        if (Build.VERSION.SDK_INT < 26) return t("هذا الهاتف لا يدعم الرد بالصوت.", "This phone cannot answer by voice.")
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return t("أحتاج إذن الرد على المكالمات. امنحه من التطبيق.", "I need the answer-calls permission.")
        (getSystemService(TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
        return t("أجبت.", "Answered.")
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun endCall(): String {
        if (Build.VERSION.SDK_INT < 28) return t("هذا الهاتف لا يدعم إنهاء المكالمة بالصوت.", "This phone cannot end calls by voice.")
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return t("أحتاج إذن إدارة المكالمات. امنحه من التطبيق.", "I need the call permission.")
        val ended = (getSystemService(TELECOM_SERVICE) as TelecomManager).endCall()
        return if (ended) t("أنهيت المكالمة.", "Call ended.") else t("لا توجد مكالمة الآن.", "There is no call.")
    }

    @Suppress("DEPRECATION")
    private fun speaker(on: Boolean): String {
        (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = on
        return if (on) t("شغّلت مكبر الصوت.", "Speaker on.") else t("أطفأت مكبر الصوت.", "Speaker off.")
    }

    private fun readNotifs(): String =
        if (recentNotes.isEmpty()) t("لا توجد إشعارات جديدة.", "No new notifications.") else startReading(recentNotes.take(5).toList())

    // ---- screen grid: say a square number, then another, and the phone taps there (for places the numbers cannot reach)

    private var gridView: View? = null
    private var gridRect = Rect()
    private var gridLevel = 0

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= 30) Rect((getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds)
        else resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private fun drawGrid() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        gridView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val view = GridView(this, gridRect)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        wm.addView(view, lp)
        gridView = view
    }

    private fun showGrid(): String {
        hideNumbers()
        return try {
            gridRect = screenBounds()
            gridLevel = 0
            drawGrid()
            t("قل رقم المربع من 1 إلى 12.", "Say a square number, 1 to 12.")
        } catch (e: Exception) {
            gridView = null
            t("لم أستطع إظهار الشبكة.", "I could not show the grid.")
        }
    }

    private fun hideGrid() {
        val view = gridView ?: return
        gridView = null
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) {}
    }

    private fun gridPick(n: Int): String {
        if (n !in 1..12) return t("اختر رقماً من 1 إلى 12.", "Pick a number from 1 to 12.")
        val cw = gridRect.width() / 3
        val ch = gridRect.height() / 4
        val col = (n - 1) % 3
        val row = (n - 1) / 3
        gridRect = Rect(gridRect.left + col * cw, gridRect.top + row * ch, gridRect.left + (col + 1) * cw, gridRect.top + (row + 1) * ch)
        gridLevel++
        if (gridLevel >= 2) return gridTap()
        return try { drawGrid(); t("قل رقماً آخر، أو قل: هنا.", "Say another number, or: here.") } catch (e: Exception) { hideGrid(); t("تعذّر رسم الشبكة.", "Could not draw the grid.") }
    }

    private fun gridTap(): String {
        val x = gridRect.exactCenterX()
        val y = gridRect.exactCenterY()
        hideGrid()
        main.postDelayed({
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
            gesture(path, 60)
        }, 250)
        return t("حاضر.", "Done.")
    }

    // ---- reading long text: a paragraph at a time, with a short pause to say stop / repeat / next

    private val readQueue = ArrayList<String>()
    private var readPos = 0
    private var reading = false
    private var readPausedAt = 0L
    private val readAdvance = Runnable { if (reading) readNext() }

    private fun chunksOf(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (line in lines) {
            if (cur.isNotEmpty() && cur.length + line.length > 260) { out.add(cur.toString()); cur.clear() }
            if (cur.isNotEmpty()) cur.append(". ")
            cur.append(line)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun startReading(chunks: List<String>): String {
        if (chunks.isEmpty()) return t("لا يوجد ما أقرأه.", "There is nothing to read.")
        readQueue.clear(); readQueue.addAll(chunks); readPos = 1
        reading = chunks.size > 1
        return chunks[0]
    }

    private fun scheduleReadAdvance() {
        awakeUntil = SystemClock.elapsedRealtime() + 3500      // "توقف" works without the wake word while reading
        main.removeCallbacks(readAdvance)
        main.postDelayed(readAdvance, 3000)
    }

    private fun readNext() {
        if (readPos >= readQueue.size) {
            reading = false
            say(t("انتهت القراءة.", "That is all."))
            return
        }
        whisper.cancel()
        try { recognizer?.cancel() } catch (_: Exception) {}
        val chunk = readQueue[readPos++]
        arabic = Commands.isArabic(chunk)
        say(chunk)
    }

    private fun readLong(): String {
        val lines = LinkedHashSet<String>()
        for (node in screenNodes(3000)) {
            if (!node.isVisibleToUser) continue
            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.length >= 2 && text.length <= 1500) lines.add(text)
            if (lines.size > 120) break
        }
        if (lines.isEmpty()) return t("لا يوجد نص في الشاشة.", "There is no text on screen.")
        return startReading(chunksOf(lines.toList()))
    }

    /** Controls while reading. Returns true when the sentence was one of them. */
    private fun readControl(text: String): Boolean {
        val c = Commands.normalize(text).trim()
        val stop = setOf("توقف", "وقف", "قف", "كفايه", "خلاص", "اسكت", "stop", "enough", "pause")
        val again = setOf("اعد", "كرر", "اعد الفقره", "اعد الجمله", "repeat", "again")
        val back = setOf("السابق", "السابقه", "ارجع", "previous", "back")
        val next = setOf("اكمل", "كمل", "تابع", "التالي", "التاليه", "next", "continue", "go on")
        val live = reading
        val paused = !reading && readQueue.isNotEmpty() && SystemClock.elapsedRealtime() - readPausedAt < 60_000
        if (!live && !paused) return false
        if (Commands.isEmergency(text)) { reading = false; return false }
        main.removeCallbacks(readAdvance)
        when {
            c in stop && live -> { reading = false; readPausedAt = SystemClock.elapsedRealtime(); respond(t("توقفت. قل: أكمل.", "Paused. Say: continue.")) }
            c in again -> { readPos = maxOf(0, readPos - 1); reading = true; readNext() }
            c in back -> { readPos = maxOf(0, readPos - 2); reading = true; readNext() }
            c in next -> { reading = true; readNext() }
            live -> { reading = false; readQueue.clear(); return false }
            else -> { readQueue.clear(); return false }
        }
        return true
    }

    // ---- reminders (medicine and so on)

    private fun remind(c: Cmd.Remind): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, c.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.text)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (c.daily) intent.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(
            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY))
        val at = String.format(Locale.US, "%d:%02d", c.hour, c.minute)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            return try {                                      // the clock app refused a silent set: open it filled in
                startActivity(intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false))
                t("فتحت الساعة على $at. اضغط حفظ.", "Opened the clock at $at. Tap save.")
            } catch (e2: Exception) {
                t("لم أستطع ضبط التذكير: ${e2.message}", "I could not set the reminder: ${e2.message}")
            }
        }
        return if (c.daily) t("سأذكّرك ${c.text} كل يوم الساعة $at.", "I will remind you every day at $at.")
        else t("سأذكّرك ${c.text} الساعة $at.", "I will remind you at $at.")
    }

    // ---- smart home: ask a device how it is, and hear alerts the ESP32 raises by itself

    private fun homeBase(): String? {
        var base = prefs.getString("home_base", "").orEmpty().trim().trimEnd('/')
        if (base.isEmpty()) return null
        if (!base.startsWith("http")) base = "http://$base"
        return base
    }

    private fun httpGet(url: String): String? = try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 4000
        con.readTimeout = 4000
        val body = if (con.responseCode in 200..299) con.inputStream.bufferedReader().use { it.readText() } else null
        con.disconnect()
        body
    } catch (e: Exception) {
        null
    }

    private fun keyParam() = prefs.getString("home_key", "").orEmpty().trim().let { if (it.isEmpty()) "" else "&key=${Uri.encode(it)}" }

    private fun homeStatus(name: String): String {
        val id = findDevice(name) ?: return t("الجهاز \"$name\" غير مضاف في الإعدادات.", "The device \"$name\" is not in settings.")
        val base = homeBase() ?: return t("لم يتم ضبط عنوان ESP32 في الإعدادات.", "The ESP32 address is not set.")
        Thread {
            val body = httpGet("$base/state?id=${Uri.encode(id)}${keyParam()}")?.trim()?.take(60)
            main.post {
                val answer = when {
                    body == null -> t("لم أستطع الوصول إلى $name.", "I could not reach $name.")
                    body.lowercase() in setOf("1", "on", "true", "high") -> t("$name شغّال.", "$name is on.")
                    body.lowercase() in setOf("0", "off", "false", "low") -> t("$name مطفي.", "$name is off.")
                    else -> "$name: $body"
                }
                respond(answer)
            }
        }.start()
        return t("لحظة.", "One moment.")
    }

    private val alertsTask = object : Runnable {
        override fun run() {
            val base = homeBase()
            if (listening && base != null && prefs.getBoolean("home_alerts", false)) Thread {
                val body = httpGet("$base/alerts?x=1${keyParam()}")?.trim().orEmpty()
                if (body.isNotEmpty() && body.length < 400) main.post { announce(body.lines().filter { it.isNotBlank() }.joinToString(". "), true, urgent = true) }
            }.start()
            main.postDelayed(this, 20_000)
        }
    }

    // ---- caregiver

    private fun lastLocationLink(): String? {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            val best = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            best?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" }
        } catch (e: Exception) {
            null
        }
    }

    private fun smsCaregiver(text: String): Boolean {
        val number = prefs.getString("emergency_number", "").orEmpty().trim()
        if (number.isEmpty() || !granted(Manifest.permission.SEND_SMS)) return false
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val place = lastLocationLink()?.let { " موقعي: $it" }.orEmpty()
            sms.sendMultipartTextMessage(number, null, sms.divideMessage("$text$place"), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun notifyCaregiver(): String =
        if (smsCaregiver("جوني: أحتاج إليك إن أمكن.")) t("أبلغت المرافق.", "Your caregiver has been told.")
        else t("لم أستطع إبلاغ المرافق. تأكد من رقمه وإذن الرسائل.", "I could not tell your caregiver. Check the number and SMS permission.")

    // ---- local language model (optional)

    private val localLlm by lazy { LocalLlm(this) }

    private fun localAiReady() = prefs.getBoolean("llm_on", false) && localLlm.available()

    private fun askLocal(text: String) {
        log("أفكر...")
        val system = aiPrompt()
        Thread {
            var command = ""
            var reply = ""
            try {
                val content = localLlm.ask(system, text)
                val answer = Agent.parseReply(content)
                command = answer.command
                reply = answer.reply
                if (command.isEmpty() && reply.isNotEmpty() && !content.contains('{')) { command = reply; reply = "" }
            } catch (e: Throwable) {
                log("تعذّر تشغيل النموذج المحلي: ${e.message}")
            }
            main.post {
                val commands = try { if (command.isNotBlank()) Commands.parseAll(command) else emptyList() } catch (e: Exception) { emptyList() }
                when {
                    commands.isNotEmpty() -> runAll(commands, 0)
                    reply.isNotBlank() -> respond(reply)
                    else -> { beep(3); respond(t("لم أفهم. قل: مساعدة.", "I did not understand. Say: help.")) }
                }
            }
        }.start()
    }

    // ---- battery saving: optionally stop listening on a low battery, or while the screen is off and unplugged

    private var ecoPaused = false

    private val ecoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            main.post {
                ecoCheck()
                if (intent?.action == Intent.ACTION_USER_PRESENT) morningBriefing()
            }
        }
    }

    private fun batteryState(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return (if (level >= 0) level * 100 / scale else 100) to plugged
    }

    private fun ecoCheck() {
        val (pct, plugged) = batteryState()
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val low = prefs.getBoolean("eco_low", false) && pct <= 15
        val dark = prefs.getBoolean("eco_screen", false) && !interactive
        val pause = !plugged && (low || dark)
        if (pause && listening && !ecoPaused) {
            ecoPaused = true
            stopListening(false)
            log(if (low) "أوقفت الاستماع لتوفير البطارية ($pct%)." else "أوقفت الاستماع لأن الشاشة مطفأة.")
        } else if (!pause && ecoPaused) {
            ecoPaused = false
            if (prefs.getBoolean("auto_listen", false)) startListening(false)
        }
    }

    // ---- media, camera, chats

    private fun media(kind: String): String {
        val code = when (kind) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return t("حاضر.", "Done.")
    }

    private fun takePhoto(seconds: Int): String {
        try {
            startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return t("لم أستطع فتح الكاميرا.", "I could not open the camera.")
        }
        val labels = listOf("shutter", "التقاط", "التقط", "capture", "take photo", "take picture", "الغالق", "زر الالتقاط")
        fun press(attemptsLeft: Int) {
            val node = labels.firstNotNullOfOrNull { findByLabel(it) }
            if (node != null) press(node)
            else if (attemptsLeft > 0) main.postDelayed({ press(attemptsLeft - 1) }, 1000)
            else { log("جوني: لم أجد زر الالتقاط. قل: الأرقام ثم رقم الزر."); say(t("لم أجد زر الالتقاط.", "I could not find the shutter button.")) }
        }
        main.postDelayed({ press(3) }, 2500L + seconds * 1000L)
        return if (seconds > 0) t("سألتقط الصورة بعد $seconds ثوانٍ.", "Photo in $seconds seconds.") else t("حاضر.", "Done.")
    }

    private fun openChat(name: String): String {
        if (!granted(Manifest.permission.READ_CONTACTS)) return t("لا يوجد إذن لجهات الاتصال.", "Contacts permission missing.")
        val pkg = whatsappPackage() ?: return t("واتساب غير مثبت.", "WhatsApp is not installed.")
        val contact = resolveContact(name) ?: return t("لم أجد \"$name\" في جهات الاتصال.", "I cannot find \"$name\" in contacts.")
        lastContact = contact.first
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${waNumber(contact.second)}")).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return t("فتحت محادثة ${contact.first}.", "Opened the chat with ${contact.first}.")
    }

    // ---- dictation: everything said is typed, with spoken punctuation, until "انتهى الإملاء"

    private var dictating = false
    private var dictateUntil = 0L

    private fun punctuate(text: String): String {
        val w = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = StringBuilder()
        var i = 0
        while (i < w.size) {
            val one = Commands.normalize(w[i])
            val two = if (i + 1 < w.size) one + " " + Commands.normalize(w[i + 1]) else ""
            val mark: String?
            var used = 1
            when {
                two in setOf("علامه استفهام", "question mark") -> { mark = "؟"; used = 2 }
                two in setOf("علامه تعجب", "exclamation mark") -> { mark = "!"; used = 2 }
                two in setOf("سطر جديد", "new line", "newline") -> { mark = "\n"; used = 2 }
                one in setOf("نقطه", "period") -> mark = "."
                one in setOf("فاصله", "comma") -> mark = "،"
                else -> mark = null
            }
            if (mark == null) {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append(' ')
                out.append(w[i])
            } else out.append(mark)
            i += used
        }
        return out.toString()
    }

    private fun startDictation(): String {
        dictating = true
        dictateUntil = SystemClock.elapsedRealtime() + 5 * 60_000
        return t("بدأ الإملاء. اضغط على خانة الكتابة وتكلم. قل: انتهى الإملاء، للخروج.", "Dictation on. Say: stop dictation, to leave.")
    }

    /** While dictating: returns true when the sentence was used (typed or an editing command). */
    private fun dictate(heard: String): Boolean {
        if (SystemClock.elapsedRealtime() > dictateUntil) { dictating = false; return false }
        dictateUntil = SystemClock.elapsedRealtime() + 5 * 60_000
        val cmd = Commands.parse(heard)
        when {
            cmd is Cmd.Dictation && !cmd.start -> { dictating = false; respond(t("انتهى الإملاء.", "Dictation finished.")) }
            cmd is Cmd.DeleteWord || cmd is Cmd.Replace || cmd is Cmd.Send || cmd is Cmd.ClearText -> { log("سمعت: $heard"); respond(execute(cmd)) }
            else -> { log("إملاء: $heard"); type(punctuate(heard)); resume(200) }
        }
        return true
    }

    private fun fieldText(): Pair<AccessibilityNodeInfo, String>? {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return field to realText(field)
    }

    /** What the person typed. Many apps (WhatsApp: "مراسلة") report their placeholder as the field's text, so it is dropped. */
    private fun realText(field: AccessibilityNodeInfo): String {
        if (field.isShowingHintText) return ""
        val text = field.text?.toString().orEmpty()
        if (text.isEmpty()) return ""
        val hint = if (Build.VERSION.SDK_INT >= 26) field.hintText?.toString().orEmpty() else ""
        val n = Commands.normalize(text).trim()
        if (n.isEmpty()) return text
        if (hint.isNotEmpty() && n == Commands.normalize(hint).trim()) return ""
        // a placeholder has no cursor inside it: the caret sits at the start (or there is none), never after the words
        val caretAtStart = field.textSelectionEnd <= 0
        if (caretAtStart && n == Commands.normalize(field.contentDescription?.toString().orEmpty()).trim()) return ""
        if (caretAtStart && n in PLACEHOLDERS) return ""
        return text
    }

    private fun setFieldText(field: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun deleteWord(): String {
        val (field, old) = fieldText() ?: return t("اضغط على خانة الكتابة أولاً.", "Select a text field first.")
        val trimmed = old.trimEnd()
        val cut = maxOf(trimmed.lastIndexOf(' '), trimmed.lastIndexOf('\n'))
        return if (setFieldText(field, if (cut < 0) "" else trimmed.substring(0, cut))) t("حاضر.", "Done.") else t("لم أستطع التعديل.", "I could not edit.")
    }

    private fun replaceInField(from: String, to: String): String {
        val (field, old) = fieldText() ?: return t("اضغط على خانة الكتابة أولاً.", "Select a text field first.")
        val at = old.indexOf(from, ignoreCase = true)
        if (at < 0) return t("لم أجد \"$from\" في النص.", "I cannot find \"$from\" in the text.")
        return if (setFieldText(field, old.replaceRange(at, at + from.length, to))) t("غيّرت.", "Changed.") else t("لم أستطع التعديل.", "I could not edit.")
    }

    // ---- modes and the spoken password

    private fun mode() = prefs.getString("mode", "normal").orEmpty()

    private fun setMode(name: String): String {
        prefs.edit().putString("mode", name).apply()
        return when (name) {
            "car" -> t("وضع السيارة: سأقرأ الرسائل وأنتظر ردك.", "Car mode: I will read messages and wait for your answer.")
            "night" -> t("وضع الليل: بلا نغمات ولا إعلانات، إلا التنبيهات المهمة.", "Night mode: no tones or announcements except important alerts.")
            else -> t("رجعت للوضع العادي.", "Back to normal mode.")
        }
    }

    private var secretPending: (() -> String)? = null
    private var secretPendingUntil = 0L
    private var secretOkUntil = 0L

    private fun needsSecret(c: Cmd): Boolean {
        if (!prefs.getBoolean("secret_on", false) || prefs.getString("secret", "").orEmpty().isBlank()) return false
        if (SystemClock.elapsedRealtime() < secretOkUntil) return false
        return when (c) {
            is Cmd.Call -> c.target != "emergency"
            is Cmd.Message, is Cmd.WhatsApp, is Cmd.Reply -> true
            is Cmd.Home -> listOf("باب", "قفل", "door", "lock").any { Commands.normalize(c.device).contains(it) }
            else -> false
        }
    }

    private fun checkSecret(text: String): Boolean {
        val action = secretPending ?: return false
        secretPending = null
        if (SystemClock.elapsedRealtime() > secretPendingUntil) return false
        val ok = Commands.normalize(text).trim() == Commands.normalize(prefs.getString("secret", "").orEmpty()).trim()
        if (ok) {
            secretOkUntil = SystemClock.elapsedRealtime() + 120_000
            respond(action())
        } else {
            beep(3)
            respond(t("كلمة السر غير صحيحة. ألغيت الأمر.", "Wrong password. Command cancelled."))
        }
        return true
    }

    // ---- what Johnny remembers: notes, and nicknames used when calling or messaging

    private fun memoryLines() = prefs.getString("memory", "").orEmpty().lines().filter { it.isNotBlank() }

    private fun aliasFor(spoken: String): String? {
        val n = Commands.normalize(spoken).trim()
        return prefs.getString("aliases", "").orEmpty().lines().firstNotNullOfOrNull { line ->
            val at = line.indexOf('=')
            if (at > 0 && Commands.normalize(line.substring(0, at)).trim() == n) line.substring(at + 1).trim() else null
        }
    }

    private fun remember(text: String): String {
        val alias = Agent.aliasOf(text)
        val edit = prefs.edit()
        if (alias != null) {
            val others = prefs.getString("aliases", "").orEmpty().lines().filter { it.isNotBlank() && !it.startsWith(alias.first + "=") }
            edit.putString("aliases", (others + "${alias.first}=${alias.second}").takeLast(40).joinToString("\n"))
        }
        val notes = (memoryLines().filter { it != text } + text).takeLast(60)
        edit.putString("memory", notes.joinToString("\n")).apply()
        return if (alias != null) t("حاضر. من الآن \"${alias.first}\" يعني ${alias.second}.", "Done. \"${alias.first}\" now means ${alias.second}.")
        else t("حاضر، سأتذكر.", "OK, I will remember.")
    }

    private fun recall(query: String): String {
        val found = memoryLines().filter { Agent.matches(it, query) }.takeLast(3)
        return if (found.isEmpty()) t("لا أتذكر شيئاً عن هذا.", "I do not remember anything about that.") else found.joinToString(". ")
    }

    private fun forget(query: String): String {
        val before = memoryLines()
        val keep = before.filterNot { Agent.matches(it, query) }
        val aliases = prefs.getString("aliases", "").orEmpty().lines().filter { it.isNotBlank() && !Agent.matches(it.substringBefore('='), query) }
        prefs.edit().putString("memory", keep.joinToString("\n")).putString("aliases", aliases.joinToString("\n")).apply()
        return if (keep.size < before.size) t("نسيت.", "Forgotten.") else t("لم أجد ما أنساه.", "I had nothing to forget.")
    }

    // ---- questions about the screen ("لخص الرسالة"): the screen text goes to the smart server only if the person allowed it

    private fun screenText(limit: Int = 3500): String {
        val out = StringBuilder()
        for (node in screenNodes(2500)) {
            if (!node.isVisibleToUser || node.isPassword) continue
            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.length < 2) continue
            if (out.length + text.length > limit) break
            out.append(text).append('\n')
        }
        return out.toString()
    }

    private fun screenConsent(): String? = when {
        !aiReady() -> t("هذه الميزة تحتاج الذكاء السحابي. أضف المفتاح من الإعدادات.", "This needs the smart server. Add the key in settings.")
        !prefs.getBoolean("ai_screen", false) -> t("لم تسمح بإرسال نص الشاشة للنموذج. فعّل ذلك من الإعدادات.", "You have not allowed sending screen text to the model. Turn it on in settings.")
        else -> null
    }

    private fun askScreen(question: String): String {
        screenConsent()?.let { return it }
        val screen = screenText()
        if (screen.isBlank()) return t("لا يوجد نص في الشاشة.", "There is no text on screen.")
        val system = "You answer questions about what is on an Android screen. Use only the screen text. Answer in at most two short sentences, " +
            "in the same language and dialect as the question. If the answer is not on the screen, say so."
        Thread {
            val answer = cloudChat(system, "SCREEN:\n$screen\n\nQUESTION: $question", 250)?.substringAfterLast("</think>")?.trim()
            main.post { respond(if (answer.isNullOrBlank()) t("لم أستطع الحصول على إجابة.", "I could not get an answer.") else answer) }
        }.start()
        return t("لحظة.", "One moment.")
    }

    // ---- agent mode: the smart server looks at the screen and takes the next step, again and again, until the goal is done

    private var agentOn = false
    private var agentGoal = ""
    private var agentStep = 0
    private val agentDone = ArrayList<String>()
    private var agentNodes: List<AccessibilityNodeInfo> = emptyList()
    private val agentRunnable = Runnable { agentTick() }

    private fun startAgent(goal: String): String {
        when {
            !aiReady() -> return t("وضع الوكيل يحتاج الذكاء السحابي. أضف المفتاح من الإعدادات.", "Agent mode needs the smart server. Add the key in settings.")
            !prefs.getBoolean("agent_on", false) -> return t("فعّل وضع الوكيل من الإعدادات أولاً.", "Turn agent mode on in settings first.")
            !prefs.getBoolean("ai_screen", false) -> return t("لم تسمح بإرسال نص الشاشة للنموذج. فعّل ذلك من الإعدادات.", "You have not allowed sending screen text to the model.")
        }
        agentOn = true
        agentGoal = goal
        agentStep = 0
        agentDone.clear()
        main.postDelayed(agentRunnable, 2500)
        return t("حاضر، سأنفذ المهمة خطوة خطوة. قل: جوني توقف، للإلغاء.", "On it, step by step. Say: Johnny stop, to cancel.")
    }

    private fun stopAgent(message: String) {
        agentOn = false
        main.removeCallbacks(agentRunnable)
        respond(message)
    }

    private fun agentObserve(): List<Agent.Item> {
        val items = ArrayList<Agent.Item>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        for (node in screenNodes(2500)) {
            if (!node.isVisibleToUser || node.isPassword) continue
            val label = (node.text ?: node.contentDescription ?: node.hintText)?.toString()?.trim().orEmpty()
            val kind = when { node.isEditable -> "edit"; node.isClickable -> "button"; else -> "text" }
            if (label.isEmpty() && kind != "edit") continue
            if (kind == "text" && label.length < 2) continue
            nodes.add(node)
            items.add(Agent.Item(nodes.size, kind, label.ifEmpty { "(empty field)" }))
            if (items.size >= 45) break
        }
        agentNodes = nodes
        return items
    }

    private fun agentTick() {
        if (!agentOn) return
        if (agentStep >= Agent.MAX_STEPS) { stopAgent(t("وصلت للحد الأقصى من الخطوات دون إكمال المهمة.", "I reached the step limit without finishing.")); return }
        agentStep++
        val items = agentObserve()
        val app = rootInActiveWindow?.packageName?.toString().orEmpty()
        val obs = Agent.observation(app, agentGoal, items, agentDone, agentStep)
        Thread {
            val raw = cloudChat(Agent.SYSTEM, obs, 200)
            main.post { agentAct(raw) }
        }.start()
    }

    private fun agentNext(delay: Long = 1700) { main.postDelayed(agentRunnable, delay) }

    private fun agentAct(raw: String?) {
        if (!agentOn) return
        val step = raw?.let { Agent.parseStep(it) }
        if (step == null) { stopAgent(t("لم أفهم رد النموذج، توقفت.", "I could not understand the model, so I stopped.")); return }
        log("وكيل $agentStep: ${step.action} ${step.n} ${step.text}".trim())
        when (step.action) {
            "tap" -> {
                val node = agentNodes.getOrNull(step.n - 1)
                if (node == null) { agentDone.add("tap ${step.n}: no such element"); agentNext(300); return }
                val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
                if (Agent.isRisky(label)) {
                    agentOn = false                       // wait for the answer; a "no" simply ends the task
                    respond(ask(t("أضغط على «$label»؟ نعم أو لا.", "Press \"$label\"? Yes or no.")) {
                        agentOn = true
                        press(node); agentDone.add("tapped $label"); agentNext()
                        t("حاضر.", "Done.")
                    })
                    return
                }
                press(node); agentDone.add("tapped $label"); agentNext()
            }
            "type" -> {
                val node = agentNodes.getOrNull(step.n - 1)
                if (node == null || !node.isEditable) { agentDone.add("type ${step.n}: not an edit field"); agentNext(300); return }
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                setFieldText(node, step.text)
                agentDone.add("typed text"); agentNext()
            }
            "scroll" -> { swipe(step.direction != "up"); agentDone.add("scrolled ${step.direction}"); agentNext() }
            "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); agentDone.add("back"); agentNext() }
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); agentDone.add("home"); agentNext() }
            "open" -> { val r = openApp(step.text); agentDone.add("opened ${step.text}: $r"); agentNext(2500) }
            "wait" -> { agentDone.add("waited"); agentNext(2500) }
            "say" -> stopAgent(step.text.ifBlank { t("لا أستطيع المتابعة.", "I cannot continue.") })
            else -> stopAgent(step.text.ifBlank { t("تمت المهمة.", "Done.") })
        }
    }

    private var lastErr = -1
    private var sameErr = 0
    private var silent = 0

    private fun errName(code: Int) = when (code) {
        1 -> "انتهت مهلة الشبكة"; 2 -> "خطأ في الشبكة"; 3 -> "خطأ في الصوت"; 4 -> "خطأ في الخادم"; 5 -> "خطأ في التطبيق"
        8 -> "المحرك مشغول"; 9 -> "لا إذن للميكروفون"; 10 -> "طلبات كثيرة جداً"; 11 -> "انقطع المحرك"
        12 -> "اللغة غير مدعومة"; 13 -> "حزمة اللغة غير منزّلة"; else -> "رمز $code"
    }

    /** Make every failure visible in the chat: nothing may fail silently when the person cannot touch the phone. */
    private fun noteError(code: Int) {
        if (code == 6 || code == 7) {                          // nothing was said: normal, unless it never ends
            silent++
            if (silent == 15 || silent % 80 == 0) log("أستمع لكني لا ألتقط أي صوت. تأكد أن الميكروفون غير مكتوم وأن تطبيقاً آخر لا يستخدمه.")
            return
        }
        silent = 0
        if (code != lastErr) {
            lastErr = code; sameErr = 1
            log("خطأ في الاستماع: ${errName(code)} ($code)." + if (code in listOf(1, 2, 4)) " لا يوجد إنترنت، والاستماع دون إنترنت يحتاج حزمة العربية." else "")
        } else {
            sameErr++
            if (sameErr == 3 && code == 10) log("أندرويد يرفض طلبات الاستماع. أعد تشغيل الهاتف، أو امسح ذاكرة تطبيق Google ثم أعد تشغيل خدمة جوني.")
            else if (sameErr == 6 || sameErr % 30 == 0) log("الخطأ ${errName(code)} يتكرر ($sameErr مرة).")
        }
    }

    // only ONE pending restart at a time: several overlapping ones made Android answer "too many requests" (error 10)
    private val resumeTask = Runnable { listenOnce() }

    private fun resume(delayMs: Long) {
        main.removeCallbacks(resumeTask)
        main.postDelayed(resumeTask, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { lastActivity = SystemClock.elapsedRealtime() }
        override fun onBeginningOfSpeech() { lastActivity = SystemClock.elapsedRealtime() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            noteError(error)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> resume(300)   // silence: normal
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    log("النظام رفض استخدام الميكروفون (خطأ 9). افتح التطبيق واضغط زر الميكروفون.")
                    blocked = true
                    stopListening(false)
                }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    rebuildRecognizer()                       // drop every old session, wait, start one clean one
                    resume(3_000)
                }
                12, 13 -> {                       // language not supported / not downloaded: go back to the normal recogniser
                    if (!forceNormal || onDevice) log("حزمة الصوت العربية غير موجودة على الهاتف، أستخدم الاستماع العادي (يحتاج إنترنت).")
                    forceNormal = true
                    prefs.edit().putBoolean("offline_ready", false).apply()
                    rebuildRecognizer()
                    resume(500)
                }
                else -> {
                    errors++
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    if (onDevice && errors >= 3) {                // the offline engine does not work here: use the normal one
                        forceNormal = true
                        log("المحرك دون إنترنت لا يعمل في هذا الهاتف، أستخدم الاستماع العادي.")
                        rebuildRecognizer()
                    } else if (errors > 8) rebuildRecognizer()    // start from scratch, but never give up
                    resume(if (errors > 8) minOf(30_000L, 2_000L * errors) else 1500)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            errors = 0; silent = 0; lastErr = -1
            lastActivity = SystemClock.elapsedRealtime()
            processResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf())
        }
    }

    /** What to do with what was heard (the recogniser's guesses, best first). Used by every listening engine. */
    private fun processResults(list: List<String>) {
        run {
            val heard = list.firstOrNull().orEmpty()
            if (trainQueue != null && heard.isNotBlank() && list.none { Commands.isEmergency(it) }) {
                log("سمعت: $heard")
                trainHeard(list.map { Commands.stripWake(it) ?: it }.filter { it.isNotBlank() }.ifEmpty { list })
                return
            }
            if (dictating && secretPending == null && heard.isNotBlank() && dictate(heard)) return
            val waiting = pending != null && SystemClock.elapsedRealtime() < pendingUntil
            val answer = if (waiting) list.firstNotNullOfOrNull { Commands.yesNo(it) } else null
            val number = if (overlay != null || gridView != null) list.firstNotNullOfOrNull { Commands.toNumber(it) } else null
            // the recogniser gives several guesses: prefer one that is a command we understand
            val commands = list.mapNotNull { Commands.stripWake(it) }
            val command = commands.firstOrNull { Commands.parse(it) != null } ?: commands.firstOrNull()
            val wasAwake = SystemClock.elapsedRealtime() < awakeUntil
            // woken by "جوني" a moment ago: take what was just said as the command itself
            val followUp = if (wasAwake && command == null)
                list.firstOrNull { it.isNotBlank() && Commands.parse(it) != null } ?: heard.takeIf { it.isNotBlank() }
            else null
            when {
                list.any { Commands.isEmergency(it) } -> { log("سمعت: ${if (secretPending != null) "••••" else heard}"); handleText(heard) }
                answer != null -> { log("سمعت: ${if (secretPending != null) "••••" else heard}"); handleText(if (answer) "نعم" else "لا") }
                command != null -> { awakeUntil = 0L; log("سمعت: ${if (secretPending != null) "••••" else heard}"); handleText(command) }
                followUp != null -> { awakeUntil = 0L; log("سمعت: ${if (secretPending != null) "••••" else heard}"); handleText(followUp) }
                number != null -> { log("سمعت: ${if (secretPending != null) "••••" else heard}"); handleText(number.toString()) }   // numbers are showing
                else -> {                                // speech without the wake word: ignore it
                    if (heard.isNotBlank()) log("(بدون كلمة جوني) سمعت: $heard")
                    resume(200)
                }
            }
        }
    }

    // ------------------------------------------------------------------ understanding and acting

    /** Run what was said (after the wake word) or typed in the app. */
    fun handleText(text: String) {
        Commands.userDict = Commands.parseDict(prefs.getString("dialect", "").orEmpty())
        Commands.userPhrases = Commands.parsePhrases(prefs.getString("phrases", "").orEmpty())
        Commands.homeDevices = devices().keys
        if (text.isNotBlank()) prefs.edit().putBoolean("tested", true).apply()
        if (trainQueue != null && text.isNotBlank()) {
            if (Commands.isEmergency(text)) trainQueue = null else { trainHeard(listOf(text)); return }
        }
        if (agentOn && text.isNotBlank()) {                       // the person spoke: they take over
            agentOn = false
            main.removeCallbacks(agentRunnable)
            if (Commands.normalize(text).trim() in setOf("توقف", "وقف", "كفايه", "خلاص", "الغ", "stop", "cancel")) { respond(t("توقفت.", "Stopped.")); return }
        }
        if (text.isNotBlank() && checkSecret(text)) return
        if (text.isNotBlank() && readControl(text)) return
        main.removeCallbacks(readAdvance)
        val question = pending?.takeIf { SystemClock.elapsedRealtime() < pendingUntil }
        val answer = if (question != null) Commands.yesNo(text) else null
        if (question == null || answer == null) arabic = text.isBlank() || Commands.isArabic(text)
        try {
            if (question != null && answer != null) {
                pending = null
                val no = pendingNo
                pendingNo = null
                respond(if (answer) question() else no?.invoke() ?: t("ألغيت.", "Cancelled."))
                return
            }
            pending = null
            pendingNo = null
            if (text.isBlank()) {
                awakeUntil = SystemClock.elapsedRealtime() + 12_000   // now listen for the command
                paintBubble(false)
                main.postDelayed({ paintBubble(false) }, 12_200)
                beep(1)
                respond(t("نعم، تفضل.", "Yes? I'm listening."))
                return
            }
            val commands = Commands.parseAll(text)
            when {
                findScene(text, exact = true) != null -> { beep(2); respond(runScene(text)) }
                commands.isNotEmpty() -> runAll(commands, 0)
                findScene(text) != null -> { beep(2); respond(runScene(text)) }
                aiReady() -> askAi(text)
                else -> fallback(text)
            }
        } catch (e: Exception) {
            respond(t("حدث خطأ: ${e.message}", "Error: ${e.message}"))
        }
    }

    private fun respond(reply: String) {
        if (reply.isNotBlank()) log("جوني: $reply")
        say(reply)
    }

    // ------------------------------------------------------------------ head pointer (front camera)

    private var head: HeadControl? = null

    private fun tapAt(x: Float, y: Float) {
        gesture(Path().apply { moveTo(x, y); lineTo(x + 1f, y) }, 60)
    }

    fun headRunning() = head?.running == true

    /** Start the head pointer. Works best when started from the app: Android lets the camera start only while the app is in use. */
    fun startHead(): String {
        if (!granted(Manifest.permission.CAMERA)) return t("أحتاج إذن الكاميرا. افتح تطبيق جوني ← الإعدادات ← التحكم بالرأس.", "I need the camera permission.")
        if (head?.running == true) return t("التحكم بالرأس يعمل.", "Head control is on.")
        val control = HeadControl(this, onTap = { x, y -> tapAt(x, y); beep(2) }, onStatus = { log("جوني: $it") })
        head = control
        control.start(
            speed = prefs.getFloat("head_speed", 1f),
            dwellMs = prefs.getLong("head_dwell", 1500L),
            dwellOn = prefs.getBoolean("head_dwell_on", true),
            blinkTap = prefs.getBoolean("head_blink", false),
            invertX = prefs.getBoolean("head_invert", false),
        )
        if (!showNotification(listening, true)) {
            control.stop(); head = null
            updateForeground()
            return t("لم يسمح أندرويد بتشغيل الكاميرا من الخلفية. افتح تطبيق جوني واضغط «ابدأ التحكم بالرأس».",
                "Android blocked the camera from the background. Open Johnny and start it there.")
        }
        hideNumbers(); hideGrid()
        return t("شغّلت التحكم بالرأس. انظر إلى الأمام لحظة، ثم حرّك رأسك. ثبّت الدائرة لتضغط، أو قل: اضغط هنا.",
            "Head control on. Look ahead for a moment, then move your head. Hold the ring still to tap.")
    }

    fun stopHead(): String {
        val was = head?.running == true
        head?.stop()
        head = null
        updateForeground()
        return if (was) t("أوقفت التحكم بالرأس.", "Head control off.") else t("التحكم بالرأس متوقف أصلاً.", "Head control is already off.")
    }

    // ------------------------------------------------------------------ proactive assistant

    private fun reminders() = ProactivePlan.parseReminders(prefs.getString("pro_reminders", "").orEmpty())

    private fun saveReminders(list: List<ProactivePlan.Reminder>) {
        prefs.edit().putString("pro_reminders", list.joinToString("\n") { ProactivePlan.format(it) }).apply()
        ProactiveReceiver.schedule(this)
    }

    private fun clock(h: Int, m: Int) = String.format(Locale.US, "%d:%02d", h, m)

    private fun addReminder(c: Cmd.Remind): String {
        val list = reminders().filterNot { it.hour == c.hour && it.minute == c.minute && Commands.normalize(it.text) == Commands.normalize(c.text) }
        saveReminders(list + ProactivePlan.Reminder(c.hour, c.minute, c.text))
        return t("حفظت تذكيراً يومياً: ${c.text} الساعة ${clock(c.hour, c.minute)}. سأسألك هل فعلتها.",
            "Daily reminder saved: ${c.text} at ${clock(c.hour, c.minute)}.")
    }

    private fun listReminders(): String {
        val list = reminders().sortedBy { it.hour * 60 + it.minute }
        if (list.isEmpty()) return t("لا توجد تذكيرات يومية. قل مثلاً: ذكرني بالدواء كل يوم الساعة 8.", "No daily reminders.")
        return t("عندك ${list.size}: ", "You have ${list.size}: ") + list.joinToString("، ") { "${it.text} ${clock(it.hour, it.minute)}" }
    }

    private fun deleteReminder(name: String): String {
        val n = Commands.normalize(name).removePrefix("ال").removePrefix("ب").removePrefix("ال")
        val list = reminders()
        val keep = list.filterNot { Commands.normalize(it.text).contains(n) }
        if (n.isEmpty() || keep.size == list.size) return t("لم أجد تذكيراً باسم «$name».", "No reminder called \"$name\".")
        saveReminders(keep)
        return t("حذفت تذكير «$name».", "Deleted the reminder \"$name\".")
    }

    /** Called by the alarm. kind: remind (first time), again (asking again), bedtime. */
    fun proactiveFire(kind: String, text: String, attempt: Int) {
        main.post {
            if (kind != "again") ProactiveReceiver.schedule(this)          // the plan moves on to the next one
            if (!prefs.getBoolean("pro_on", false)) return@post
            when (kind) {
                "bedtime" -> {
                    val (pct, plugged) = batteryState()
                    if (!plugged) announce(t("وقت النوم. الهاتف ليس على الشاحن، والبطارية $pct%. ضعه على الشاحن.",
                        "Bedtime. The phone is not charging ($pct%)."), false, urgent = true)
                }
                "remind", "again" -> askReminder(text, attempt)
            }
        }
    }

    /**
     * Ask "did you do it?". An alarm for the next try is set at once, so a reminder that gets no answer at all
     * (Johnny not listening, phone asleep) still comes back; "نعم" cancels it. After 3 tries the caregiver is told.
     */
    private fun askReminder(text: String, attempt: Int) {
        if (attempt > 3) {
            val told = prefs.getBoolean("pro_care", false) && smsCaregiver("تنبيه من جوني: لم يتم تأكيد «$text» بعد 3 تذكيرات.")
            if (told) announce(t("لم تؤكد «$text»، فأرسلت رسالة للمرافق.", "Not confirmed: I texted your caregiver."), false, urgent = true)
            return
        }
        ProactiveReceiver.again(this, text, attempt + 1, 12)
        val question = if (attempt == 1) t("تذكير: $text. هل فعلتها؟ قل نعم أو لا.", "Reminder: $text. Done? Say yes or no.")
            else t("أذكّرك مرة أخرى: $text. هل فعلتها؟", "Reminder again: $text. Done?")
        ask(question, seconds = 120, onNo = {
            ProactiveReceiver.again(this, text, attempt + 1, 10)
            if (attempt >= 3 && prefs.getBoolean("pro_care", false)) t("حسناً. إن لم تؤكد خلال 10 دقائق سأبلغ المرافق.", "OK.")
            else t("سأذكّرك بعد 10 دقائق.", "I'll remind you in 10 minutes.")
        }) {
            ProactiveReceiver.cancelAgain(this)
            val log = prefs.getString("pro_log", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(60) +
                "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date())} $text"
            prefs.edit().putString("pro_log", log.joinToString("\n")).apply()
            t("ممتاز، سجّلت ذلك.", "Great, noted.")
        }
        announce(question, true, urgent = true)
    }

    private var batteryWarned = 101
    private var fullSaid = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !prefs.getBoolean("pro_on", false) || !prefs.getBoolean("pro_battery", true)) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged) {
                batteryWarned = 101
                if (pct >= 100 && !fullSaid) { fullSaid = true; announce(t("اكتمل شحن الهاتف.", "The phone is fully charged."), false) }
                return
            }
            fullSaid = false
            val step = ProactivePlan.batteryWarning(pct, plugged, batteryWarned) ?: return
            batteryWarned = step
            announce(t("البطارية $pct%. ضع الهاتف على الشاحن.", "Battery at $pct%. Please charge the phone."), false, urgent = step <= 10)
        }
    }

    /** First unlock of the morning: a short greeting with the time, today's reminders and what came in. */
    private fun morningBriefing() {
        if (!prefs.getBoolean("pro_on", false) || !prefs.getBoolean("pro_morning", true)) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour !in 5..11) return
        val today = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
        if (prefs.getString("pro_morning_day", "") == today) return
        prefs.edit().putString("pro_morning_day", today).apply()
        val list = reminders().filter { it.hour * 60 + it.minute > hour * 60 }.sortedBy { it.hour * 60 + it.minute }
        val plan = if (list.isEmpty()) "" else " " + t("تذكيرات اليوم: ", "Today: ") + list.joinToString("، ") { "${it.text} ${clock(it.hour, it.minute)}" } + "."
        announce(t("صباح الخير!", "Good morning!") + plan, false)
        main.postDelayed({ if (listening) runAll(Commands.parseAll("صباح الخير"), 0) }, 4000)
    }

    // ------------------------------------------------------------------ dialect training ("دربني")

    /** Johnny names a command; the person says it their own way; what he does not understand yet is learned. */
    private val trainTargets = listOf(
        "افتح الواتساب", "اتصل بامي", "ولع النور", "طفي النور", "كم الساعه", "البطاريه", "انزل", "اطلع", "ارجع",
        "الشاشه الرئيسيه", "اقرا الشاشه", "اقرا الاشعارات", "اقرا اخر رساله", "علي الصوت", "وطي الصوت", "ولع الكشاف",
        "طفي الكشاف", "صحيني الساعه سبعه", "اضغط علي ارسال", "التاريخ", "افتح الكاميرا", "التقط صوره", "ارسلها",
        "امسح الكلمه الاخيره", "الارقام", "ايقاف مؤقت", "الاغنيه التاليه",
    )
    private var trainQueue: ArrayDeque<String>? = null
    private var trainCurrent = ""
    private var trainLearned = 0
    private var trainKnown = 0

    private fun startDialectTraining(): String {
        trainQueue = ArrayDeque(trainTargets)
        trainLearned = 0; trainKnown = 0
        trainCurrent = trainQueue!!.removeFirst()
        awakeUntil = SystemClock.elapsedRealtime() + 30_000
        return "تمام، نبدأ التدريب. أقول لك أمراً وتقوله أنت بطريقتك، بدون كلمة جوني. " +
            "قل «التالي» لتتخطى و«خلاص» لتنهي. ${trainTargets.size} أمراً. الأول: كيف تقول «$trainCurrent»؟"
    }

    private fun trainHeard(guesses: List<String>) {
        val first = guesses.first().trim()
        val n = Commands.normalize(first).trim()
        when {
            n in setOf("خلاص", "توقف", "وقف", "كفايه", "انهي", "انهاء", "انهي التدريب", "بس", "stop", "done") -> { finishTraining(); return }
            n in setOf("التالي", "تخطي", "تخط", "بعده", "اللي بعده", "ما بعرف", "ما عارف", "skip", "next") -> { nextTrain(""); return }
            n in setOf("كرر", "عيد", "تاني", "repeat") -> { nextTrain("قلت: «$trainCurrent».", advance = false); return }
        }
        val want = Commands.parseAll(trainCurrent)
        if (guesses.any { Commands.parseAll(it) == want }) {
            trainKnown++
            nextTrain("فهمتها.")
            return
        }
        if (!savePhrase(first, trainCurrent)) { nextTrain("هذه الجملة محفوظة لأمر آخر، تركتها."); return }
        trainLearned++
        nextTrain("حفظت: «$first» يعني «$trainCurrent».")
    }

    private fun nextTrain(before: String, advance: Boolean = true) {
        val queue = trainQueue ?: return
        if (advance) {
            if (queue.isEmpty()) { finishTraining(before); return }
            trainCurrent = queue.removeFirst()
        }
        awakeUntil = SystemClock.elapsedRealtime() + 30_000
        respond("$before كيف تقول «$trainCurrent»؟".trim())
    }

    private fun finishTraining(before: String = "") {
        trainQueue = null
        awakeUntil = 0L
        prefs.edit().putBoolean("dialect_trained", true).apply()
        respond("$before انتهى التدريب. فهمت $trainKnown كما قلتها، وتعلمت $trainLearned جملة جديدة. تجدها في الإعدادات ← اللهجة والتعلّم.".trim())
    }

    /** "لما اقول X يعني Y" said by voice. */
    private fun teach(phrase: String, meaning: String): String {
        if (Commands.parseAll(meaning).isEmpty())
            return "لم أفهم «$meaning» نفسه. قله بأمر أعرفه، مثل: لما أقول جيب الواتس يعني افتح الواتساب."
        return if (savePhrase(phrase, meaning)) "حفظت. لما تقول «$phrase» أفهم «$meaning»."
        else "«$phrase» محفوظة من قبل لأمر آخر. غيّرها من الإعدادات ← اللهجة والتعلّم."
    }

    /** Add "phrase=meaning" to the taught sentences. False when the phrase already means something else. */
    private fun savePhrase(phrase: String, meaning: String): Boolean {
        val key = Commands.normalize(phrase).trim()
        if (key.isEmpty()) return false
        val current = Commands.parsePhrases(prefs.getString("phrases", "").orEmpty())
        current[key]?.let { return Commands.normalize(it).trim() == Commands.normalize(meaning).trim() }
        val old = prefs.getString("phrases", "").orEmpty().trim()
        prefs.edit().putString("phrases", (if (old.isEmpty()) "" else "$old\n") + "$phrase=$meaning").apply()
        Commands.userPhrases = Commands.parsePhrases(prefs.getString("phrases", "").orEmpty())
        return true
    }

    /** Run several commands one after the other, leaving the screen time to change in between. */
    private fun runAll(commands: List<Cmd>, index: Int) {
        if (index == 0) beep(2)
        if (index == 0 && commands.none { it is Cmd.Repeat }) lastCmds = commands
        val reply = try {
            execute(commands[index])
        } catch (e: Exception) {
            t("حدث خطأ: ${e.message}", "Error: ${e.message}")
        }
        if (index + 1 < commands.size) {
            log("جوني: $reply")
            main.postDelayed({ runAll(commands, index + 1) }, 1600)
        } else {
            respond(reply)
        }
    }

    // ------------------------------------------------------------------ smart mode (optional language model)

    private fun aiReady() = prefs.getString("ai_url", "").orEmpty().isNotBlank() &&
        prefs.getString("ai_model", "").orEmpty().isNotBlank()

    /** The fixed instructions plus what is useful to know right now: facts the person told Johnny, devices, current app. */
    private fun aiPrompt(): String = buildString {
        append(AI_PROMPT)
        val facts = prefs.getString("memory", "").orEmpty().lines().filter { it.isNotBlank() }.take(15)
        if (facts.isNotEmpty()) append("\nThings the user told you to remember: ").append(facts.joinToString("; "))
        val names = devices().keys
        if (names.isNotEmpty()) append("\nHome devices the user can switch: ").append(names.joinToString(", "))
        rootInActiveWindow?.packageName?.let { append("\nForeground app: ").append(it) }
    }

    /** One blocking request to the OpenAI-compatible server in settings (Gemini, Groq, Ollama...). Call it off the main thread. */
    private fun cloudChat(system: String, user: String, maxTokens: Int = 300): String? {
        var base = prefs.getString("ai_url", "").orEmpty().trim().trimEnd('/')
        if (base.isEmpty()) return null
        if (!base.startsWith("http")) base = "http://$base"
        val key = prefs.getString("ai_key", "").orEmpty().trim()
        return try {
            val body = JSONObject()
                .put("model", prefs.getString("ai_model", "").orEmpty().trim())
                .put("temperature", 0.2)
                .put("max_tokens", maxTokens)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            if (base.contains("generativelanguage.googleapis.com")) body.put("reasoning_effort", "none")   // Gemini 2.5 would spend the tokens thinking
            val con = URL("$base/chat/completions").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 8000
            con.readTimeout = 45000
            con.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $key")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) throw Exception("HTTP ${con.responseCode}")
            val raw = con.inputStream.bufferedReader().use { it.readText() }
            con.disconnect()
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } catch (e: Exception) {
            log("تعذر الوصول إلى الوضع الذكي: ${e.message}")
            null
        }
    }

    /** Nothing understood the sentence: ask "تقصد؟", then the local model, then give up politely. */
    private fun fallback(text: String) {
        rememberUnknown(text)
        val guess = Commands.suggest(text)
        when {
            guess != null -> {
                beep(1)
                respond(ask(t("تقصد: ${guess.first}؟ نعم أو لا.", "Do you mean: ${guess.first}? Yes or no.")) { execute(guess.second) })
            }
            localAiReady() -> askLocal(text)
            else -> { beep(3); respond(t("لم أفهم. قل: مساعدة.", "I did not understand. Say: help.")) }
        }
    }


    /** Ask the smart server to interpret the sentence; if it cannot be reached, fall back to the offline understanding. */
    private fun askAi(text: String) {
        log("أفكر...")
        val system = aiPrompt()
        Thread {
            val raw = cloudChat(system, text)
            main.post {
                if (raw == null) { fallback(text); return@post }
                val answer = Agent.parseReply(raw)
                val commands = try {
                    if (answer.command.isNotBlank()) Commands.parseAll(answer.command) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                when {
                    commands.isNotEmpty() -> runAll(commands, 0)
                    answer.agent.isNotBlank() -> respond(startAgent(answer.agent))
                    answer.reply.isNotBlank() -> respond(answer.reply)
                    else -> fallback(text)
                }
            }
        }.start()
    }

    /** Start the speech engine. If it fails (often right after boot) try once more a few seconds later. */
    private fun initVoice() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                arabicVoice = null
                if (!ttsRetried) { ttsRetried = true; main.postDelayed({ initVoice() }, 4000) }
                else log("محرك النطق لا يعمل. افتح إعدادات الهاتف ← تحويل النص إلى كلام واختر محرك Google.")
                return@TextToSpeech
            }
            val ar = tts?.isLanguageAvailable(Locale.forLanguageTag("ar")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            arabicVoice = ar >= TextToSpeech.LANG_AVAILABLE
            if (arabicVoice == false)
                log("صوت العربية غير منزّل، لذلك تظهر الردود كتابة فقط. نزّله من الإعدادات ← حالة النظام ← صوت النطق.")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { lastActivity = SystemClock.elapsedRealtime() }
                override fun onDone(utteranceId: String?) { resume(300); if (reading) scheduleReadAdvance() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { resume(300) }
            })
        }
    }

    private fun say(text: String, add: Boolean = false) {
        val engine = tts
        if (text.isBlank()) { resume(300); return }
        if (ttsReady && engine != null) {
            val arabic = Commands.isArabic(text)
            if (arabic && arabicVoice == false && !warnedVoice) {
                warnedVoice = true
                log("لا أستطيع نطق العربية: صوتها غير منزّل في الهاتف.")
            }
            engine.language = if (arabic) Locale.forLanguageTag("ar") else Locale.US
            val mode = if (add) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            if (engine.speak(text, mode, null, "jarvis") == TextToSpeech.SUCCESS) return
            log("تعذّر النطق. تحقق من محرك الصوت في إعدادات الهاتف.")
        } else {
            log("محرك الصوت غير جاهز بعد.")
        }
        resume(300)                                       // no voice available: just keep listening
    }

    private fun ask(question: String, seconds: Int = 25, onNo: (() -> String)? = null, action: () -> String): String {
        pending = action
        pendingNo = onNo
        pendingUntil = SystemClock.elapsedRealtime() + seconds * 1000L
        return question
    }

    private fun execute(c: Cmd): String {
        val ok = t("حاضر.", "Done.")
        if (needsSecret(c)) {
            secretPending = { execute(c) }
            secretPendingUntil = SystemClock.elapsedRealtime() + 20_000
            awakeUntil = SystemClock.elapsedRealtime() + 20_000       // the password is said without the wake word
            return t("قل كلمة السر.", "Say your password.")
        }
        val number = (c as? Cmd.Tap)?.label?.toIntOrNull()
        if (c !is Cmd.Numbers && !(number != null && overlay != null)) hideNumbers()   // the screen is about to change
        if (c !is Cmd.Grid && c !is Cmd.GridHere && !(number != null && gridView != null)) hideGrid()
        return when (c) {
            is Cmd.Global -> {
                val action = when (c.what) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else -1
                    "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
                    else -> -1
                }
                if (action != -1 && performGlobalAction(action)) ok else t("لم أستطع.", "I could not do that.")
            }
            is Cmd.Scroll -> { swipe(c.down); ok }
            is Cmd.Numbers -> if (c.show) showNumbers() else { hideNumbers(); ok }
            is Cmd.Tap -> when {
                number != null && gridView != null -> gridPick(number)
                number != null && overlay != null -> tapNumber(number)
                else -> tap(c.label)
            }
            is Cmd.Grid -> if (c.show) showGrid() else { hideGrid(); ok }
            Cmd.GridHere -> when {
                gridView != null -> gridTap()
                head?.running == true -> head?.position()?.let { (x, y) -> tapAt(x, y); t("حاضر.", "Done.") } ?: ok
                else -> t("افتح الشبكة أولاً بقول: الشبكة.", "Open the grid first by saying: grid.")
            }
            is Cmd.Remind -> if (c.daily && prefs.getBoolean("pro_on", false)) addReminder(c) else remind(c)
            is Cmd.Agent -> startAgent(c.goal)
            is Cmd.AskScreen -> askScreen(c.question)
            is Cmd.Remember -> remember(c.text)
            is Cmd.Recall -> recall(c.query)
            is Cmd.Forget -> forget(c.query)
            is Cmd.Media -> media(c.kind)
            is Cmd.Photo -> takePhoto(c.seconds)
            is Cmd.OpenChat -> openChat(c.name)
            is Cmd.Dictation -> if (c.start) startDictation() else { dictating = false; t("انتهى الإملاء.", "Dictation finished.") }
            Cmd.DeleteWord -> deleteWord()
            is Cmd.Replace -> replaceInField(c.from, c.to)
            is Cmd.Mode -> setMode(c.name)
            Cmd.ReadLong -> readLong()
            is Cmd.HomeStatus -> homeStatus(c.device)
            Cmd.Caregiver -> notifyCaregiver()
            is Cmd.Type -> type(c.text)
            is Cmd.OpenApp -> openApp(c.name)
            is Cmd.Call -> call(c.target)
            is Cmd.Message -> message(c.name, c.body)
            is Cmd.Home -> home(c.device, c.on)
            is Cmd.Swipe -> { swipeSide(c.toRight); ok }
            is Cmd.Flashlight -> flashlight(c.on)
            is Cmd.Alarm -> {
                val at = String.format(Locale.US, "%d:%02d", c.hour, c.minute)
                fun alarm(skipUi: Boolean) = startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, c.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    alarm(true)
                    t("ضبطت المنبه على $at.", "Alarm set for $at.")
                } catch (e: Exception) {
                    try {                                     // the clock app refused a silent set: open it with the time filled in
                        alarm(false)
                        t("فتحت الساعة على $at. اضغط حفظ.", "Opened the clock at $at. Tap save.")
                    } catch (e2: Exception) {
                        t("لم أستطع ضبط المنبه: ${e2.message}", "I could not set the alarm: ${e2.message}")
                    }
                }
            }
            is Cmd.Timer -> try {
                startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, c.minutes * 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                t("ضبطت مؤقتاً لمدة ${c.minutes} دقيقة.", "Timer set for ${c.minutes} minutes.")
            } catch (e: Exception) {
                t("لم أستطع ضبط المؤقت: ${e.message}", "I could not set the timer: ${e.message}")
            }
            is Cmd.Bubble -> {
                prefs.edit().putBoolean("bubble", c.show).apply()
                if (c.show) showBubble() else hideBubble()
                ok
            }
            is Cmd.Chat -> when (c.kind) {
                "thanks" -> t("العفو.", "You are welcome.")
                "who" -> t("أنا جوني، مساعدك الصوتي. أتحكم في الهاتف بصوتك.", "I am جوني, your voice assistant.")
                "how" -> t("تمام الحمد لله. كيف أساعدك؟", "I am fine. How can I help?")
                else -> t("وعليكم السلام، أهلاً بك.", "Hello.")
            }
            Cmd.ReadScreen -> readScreen()
            Cmd.Battery -> {
                val level = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                t("البطارية $level في المية.", "Battery is at $level percent.")
            }
            Cmd.Today -> {
                val locale = if (arabic) Locale.forLanguageTag("ar") else Locale.US
                val day = SimpleDateFormat("EEEE d MMMM", locale).format(Date())
                t("اليوم $day.", "Today is $day.")
            }
            Cmd.Help -> t(
                "أستطيع فتح التطبيقات، الاتصال، إرسال الرسائل، الكتابة، الضغط على الأزرار، قراءة الشاشة، " +
                    "ضبط المنبه، وتشغيل أجهزة المنزل. مثلاً قل: جوني افتح الواتساب. أو قل: جوني الأرقام.",
                "I can open apps, call, send messages, type, press buttons, read the screen, set alarms and control " +
                    "home devices. For example say: Johnny open WhatsApp. Or say: Johnny numbers.")
            Cmd.ClearText -> clearText()
            Cmd.Send -> send()
            is Cmd.Search -> searchHere(c.query) ?: run {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(c.query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ok
            }
            is Cmd.SearchIn -> {
                val opened = openApp(c.app)
                main.postDelayed({
                    val r = searchHere(c.query)
                    if (r != null) respond(r) else respond(t("لم أجد خانة البحث. قل: الأرقام.", "I cannot find a search box. Say: numbers."))
                }, 2800)
                opened
            }
            Cmd.Answer -> answerCall()
            Cmd.HangUp -> endCall()
            is Cmd.Speaker -> speaker(c.on)
            Cmd.ReadNotifs -> readNotifs()
            is Cmd.Scene -> runScene(c.name)
            is Cmd.WhatsApp -> whatsapp(c.name, c.body)
            is Cmd.Reply -> reply(c.body)
            Cmd.ReadLast -> readLast()
            is Cmd.Describe -> describeScreen(c.whereOnly)
            Cmd.Repeat -> {
                val again = lastCmds
                if (again.isEmpty()) t("لا يوجد أمر سابق.", "There is nothing to repeat.")
                else { main.postDelayed({ runAll(again, 0) }, 300); t("أكرر.", "Repeating.") }
            }
            is Cmd.Volume -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                val dir = if (c.up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
                ok
            }
            Cmd.Time -> {
                val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                t("الساعة $now", "It is $now")
            }
            Cmd.Emergency -> emergency()
            Cmd.Train -> startDialectTraining()
            is Cmd.Head -> if (c.on) startHead() else stopHead()
            Cmd.HeadCenter -> head?.takeIf { it.running }?.let { it.recenter(); t("انظر إلى الأمام لحظة… رجعت المؤشر للوسط.", "Look straight ahead… pointer centred.") }
                ?: t("التحكم بالرأس متوقف. قل: شغّل التحكم بالرأس.", "Head control is off.")
            Cmd.ListReminders -> listReminders()
            is Cmd.DeleteReminder -> deleteReminder(c.name)
            is Cmd.Teach -> teach(c.phrase, c.meaning)
            Cmd.Hush -> {
                try { tts?.stop() } catch (_: Exception) {}
                reading = false; readQueue.clear(); main.removeCallbacks(readAdvance)
                ""
            }
            is Cmd.Mute -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (c.on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                t(if (c.on) "كتمت الصوت. قل «رجع الصوت» لإعادته." else "رجّعت الصوت.", if (c.on) "Muted." else "Sound is back.")
            }
            Cmd.StopListening -> { stopListening(); t("أوقفت الاستماع.", "Stopped listening.") }
        }
    }

    // ------------------------------------------------------------------ screen control

    private fun gesture(path: Path, durationMs: Long) {
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(), null, null)
    }

    private fun swipe(down: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val low = dm.heightPixels * 0.70f
        val high = dm.heightPixels * 0.30f
        gesture(Path().apply {
            if (down) { moveTo(x, low); lineTo(x, high) } else { moveTo(x, high); lineTo(x, low) }
        }, 300)
    }

    private fun press(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect().also { node.getBoundsInScreen(it) }         // fall back to a touch at its centre
        gesture(Path().apply {
            moveTo(r.exactCenterX(), r.exactCenterY())
            lineTo(r.exactCenterX(), r.exactCenterY())
        }, 60)
    }

    /** Every node of the app on screen (breadth first), at most [limit] of them. */
    private fun screenNodes(limit: Int = 5000): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val roots: List<AccessibilityNodeInfo> = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        } catch (_: Exception) { emptyList() }
        if (roots.isNotEmpty()) queue.addAll(roots) else rootInActiveWindow?.let { queue.add(it) }
        val out = ArrayList<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && out.size < limit) {
            val node = queue.removeFirst()
            out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun nameOf(node: AccessibilityNodeInfo) =
        Commands.normalize("${node.text ?: ""} ${node.contentDescription ?: ""}")

    /** Everything a person could call this node: its own text, hint, description and id, or the words of its children for a bare button. */
    private fun callNames(node: AccessibilityNodeInfo): String {
        val own = StringBuilder(nameOf(node))
        if (Build.VERSION.SDK_INT >= 26) node.hintText?.let { own.append(' ').append(Commands.normalize(it.toString())) }
        if (own.isBlank() && node.isClickable) {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(node to 0)
            var seen = 0
            while (queue.isNotEmpty() && seen < 20) {
                val (n, depth) = queue.removeFirst()
                seen++
                if (n !== node) own.append(' ').append(nameOf(n))
                if (depth < 3) for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        if (own.isBlank()) node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.let { own.append(Commands.normalize(it)) }
        return own.toString().trim()
    }

    private val labelNoise = setOf("زر", "خيار", "علامه", "تبويب", "ايقونه", "button", "the", "icon", "tab")

    private fun labelWords(s: String) = Commands.normalize(s).split(" ")
        .map { it.removePrefix("ال") }.filter { it.length > 1 && it !in labelNoise }

    /** The node a person means by [label]: whole phrase first, then all the words in any order, then most of them. */
    private fun findByLabel(label: String): AccessibilityNodeInfo? {
        val want = labelWords(label)
        if (want.isEmpty()) return null
        val phrase = want.joinToString(" ")
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.0
        var bestLen = Int.MAX_VALUE
        for (node in screenNodes()) {
            if (!node.isVisibleToUser) continue
            val name = callNames(node)
            if (name.isEmpty()) continue
            val nameWords = labelWords(name)
            val flat = nameWords.joinToString(" ")
            val hits = want.count { w -> nameWords.any { it == w || (w.length >= 3 && (it.contains(w) || (it.length >= 3 && w.contains(it)))) } }
            val score = when {
                flat == phrase -> 4.0
                flat.contains(phrase) -> 3.0 - minOf(nameWords.size, 20) * 0.01
                hits == want.size -> 2.0 - minOf(nameWords.size, 20) * 0.01
                want.size >= 2 && hits.toDouble() / want.size >= 0.67 -> 1.0 + hits.toDouble() / want.size * 0.5
                else -> 0.0
            }
            if (score <= 0.0) continue
            val tapScore = score + if (node.isClickable) 0.05 else 0.0
            if (tapScore > bestScore || (tapScore == bestScore && name.length < bestLen)) {
                best = node; bestScore = tapScore; bestLen = name.length
            }
        }
        return best
    }

    /** Find something on screen whose text matches the label and press it. */
    private fun tap(label: String): String {
        val target = findByLabel(label)
            ?: return t("لم أجد \"$label\". قل: الأرقام.", "I cannot find \"$label\". Say: numbers.")
        press(target)
        return t("حاضر.", "Done.")
    }

    /** Press the send button of the app on screen, whatever language it is in. */
    private fun send(): String {
        val target = findByLabel("ارسال") ?: findByLabel("send")
            ?: return t("لم أجد زر الإرسال. قل: الأرقام.", "I cannot find a send button. Say: numbers.")
        press(target)
        return t("أرسلت.", "Sent.")
    }

    private fun swipeSide(toRight: Boolean) {
        val dm = resources.displayMetrics
        val y = dm.heightPixels / 2f
        val a = dm.widthPixels * 0.20f
        val b = dm.widthPixels * 0.80f
        gesture(Path().apply {
            if (toRight) { moveTo(a, y); lineTo(b, y) } else { moveTo(b, y); lineTo(a, y) }
        }, 250)
    }

    private fun readScreen(): String {
        val lines = LinkedHashSet<String>()
        var total = 0
        for (node in screenNodes(1500)) {
            if (!node.isVisibleToUser) continue
            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.length in 2..200 && lines.add(text)) total += text.length
            if (total > 700) break
        }
        val buttons = LinkedHashSet<String>()
        for (node in screenNodes(1500)) {
            if (!node.isVisibleToUser || !node.isClickable || node.isPassword) continue
            val n = callNames(node)
            if (n.length in 2..30) buttons.add(n)
            if (buttons.size >= 8) break
        }
        val text = if (lines.isEmpty()) t("لا يوجد نص في الشاشة.", "There is no text on screen.") else lines.joinToString(". ")
        return if (buttons.isEmpty()) text
        else text + ". " + t("يمكنك قول: اضغط على ", "You can say: tap ") + buttons.joinToString("، ")
    }

    private fun clearText(): String {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return t("اضغط على خانة الكتابة أولاً.", "Select a text field first.")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) t("مسحت.", "Cleared.")
        else t("لم أستطع المسح هنا.", "I could not clear this.")
    }

    private fun flashlight(on: Boolean): String {
        val cameras = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return t("لا يوجد كشاف في هذا الهاتف.", "This phone has no flashlight.")
        cameras.setTorchMode(id, on)
        return if (on) t("شغّلت الكشاف.", "Flashlight on.") else t("طفّيت الكشاف.", "Flashlight off.")
    }

    // ------------------------------------------------------------------ floating button

    /** A small round button over every app. Tap: listen / stop. Hold: open Johnny. Drag to the bottom: remove. */
    fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val d = dm.density
        val size = (56 * d).toInt()
        val view = ImageView(this)
        view.setImageResource(R.drawable.ic_mic)
        val pad = (15 * d).toInt()
        view.setPadding(pad, pad, pad, pad)
        view.contentDescription = "Johnny"
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = prefs.getInt("bubble_x", dm.widthPixels - size - (12 * d).toInt())
        lp.y = prefs.getInt("bubble_y", dm.heightPixels / 3)

        val removeLine = dm.heightPixels - (size * 2.2f).toInt()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var held = false
        val hold = Runnable {
            if (!moved) {
                held = true
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    held = false
                    view.postDelayed(hold, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 10 * d || abs(dy) > 10 * d)) {
                        moved = true
                        view.removeCallbacks(hold)
                    }
                    if (moved && !held) {
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
                        paintBubble(lp.y > removeLine)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(hold)
                    when {
                        held -> {}
                        !moved -> if (e.actionMasked == MotionEvent.ACTION_UP) {
                            if (listening) stopListening() else startListening()
                        }
                        lp.y > removeLine -> {                       // dropped at the bottom: remove it
                            prefs.edit().putBoolean("bubble", false).apply()
                            hideBubble()
                            log("أخفيت الزر العائم. لإرجاعه قل: جوني أظهر الزر.")
                        }
                        else -> {
                            prefs.edit().putInt("bubble_x", lp.x).putInt("bubble_y", lp.y).apply()
                            paintBubble(false)
                        }
                    }
                }
            }
            true
        }
        try {
            wm.addView(view, lp)
            bubble = view
            paintBubble(false)
        } catch (e: Exception) {
            log("تعذر إظهار الزر العائم.")
        }
    }

    fun hideBubble() {
        val view = bubble ?: return
        bubble = null
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) {}
    }

    /** Blue while listening, grey when stopped, red while it is being dragged to the remove area. */
    private fun paintBubble(removing: Boolean) {
        val view = bubble ?: return
        val d = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when {
                removing -> 0xFFE53935.toInt()
                awake -> 0xFF2E7D32.toInt()
                listening -> 0xFF1E88E5.toInt()
                else -> 0xE6263349.toInt()
            })
            setStroke((1.5f * d).toInt(), 0xFF4FC3F7.toInt())
        }
        view.alpha = if (removing || listening) 1f else 0.85f
    }

    private fun showNumbers(): String {
        hideNumbers()
        val items = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (node in screenNodes()) {
            if (!node.isVisibleToUser || !node.isClickable) continue
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 8 && r.height() > 8 && items.none { it.second == r }) items.add(node to r)
            if (items.size >= 99) break
        }
        if (items.isEmpty()) return t("لا أرى أزراراً في هذه الشاشة.", "I see no buttons on this screen.")
        items.sortWith(compareBy<Pair<AccessibilityNodeInfo, Rect>>({ it.second.top / 48 }, { it.second.left }))
        return try {
            val view = NumbersView(this, items.map { it.second })
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, lp)
            overlay = view
            numbered = items.map { it.first }
            t("قل الرقم.", "Say the number.")
        } catch (e: Exception) {
            t("لم أستطع إظهار الأرقام.", "I could not show the numbers.")
        }
    }

    private fun hideNumbers() {
        val view = overlay ?: return
        overlay = null
        numbered = emptyList()
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) {}
    }

    private fun tapNumber(n: Int): String {
        val node = numbered.getOrNull(n - 1)
        hideNumbers()
        if (node == null) return t("لا يوجد رقم $n.", "There is no number $n.")
        press(node)
        return t("حاضر.", "Done.")
    }

    private fun type(text: String): String {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return t("اضغط على خانة الكتابة أولاً.", "Select a text field first.")
        val old = realText(field)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                if (old.isEmpty()) text else "$old $text")
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) t("حاضر.", "Done.")
        else t("لم أستطع الكتابة هنا.", "I could not type here.")
    }

    private fun openApp(name: String): String {
        if (Commands.isPronoun(name)) {                       // "افتحه": the app we opened a moment ago
            val last = lastApp
            if (last != null && SystemClock.elapsedRealtime() - lastAppAt < 600_000) {
                packageManager.getLaunchIntentForPackage(last)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return t("حاضر.", "Done.") }
            }
            return t("أي تطبيق؟ قل: افتح واسم التطبيق.", "Which app? Say: open and the app name.")
        }
        if (findDevice(name) != null) return home(name, true)      // "افتح الباب" / "شغل المروحة" mean the house
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
        val index = Commands.matchApp(name, apps.map { it.loadLabel(packageManager).toString() })
            ?: return t("لم أجد تطبيق \"$name\".", "I cannot find the app \"$name\".")
        val app = apps[index]
        val intent = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
            ?: return t("لم أستطع فتح التطبيق.", "I could not open the app.")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lastApp = app.activityInfo.packageName
        lastAppAt = SystemClock.elapsedRealtime()
        val label = app.loadLabel(packageManager)
        return t("أفتح $label.", "Opening $label.")
    }

    // ------------------------------------------------------------------ smart home (ESP32)

    /** Devices from settings, one per line as "name=relay number": name -> relay id. */
    private fun devices(): Map<String, String> =
        prefs.getString("home_devices", "").orEmpty().lines().mapNotNull { line ->
            val parts = line.split("=")
            val name = Commands.normalize(parts[0]).removePrefix("ال")
            if (parts.size == 2 && name.isNotEmpty() && parts[1].trim().isNotEmpty()) name to parts[1].trim() else null
        }.toMap()

    private fun findDevice(spoken: String): String? {
        val n = Commands.normalize(spoken).removePrefix("ال")
        if (n.isEmpty()) return null
        return devices().entries.firstOrNull { it.key == n || " $n ".contains(" ${it.key} ") || " $n ".contains(" ال${it.key} ") }?.value
    }

    /** Switch one device and wait for the answer. Returns what went wrong, or null when it worked. Call it off the main thread. */
    private fun homeNow(name: String, on: Boolean): String? {
        val id = findDevice(name) ?: return t("الجهاز \"$name\" غير مضاف في الإعدادات.", "The device \"$name\" is not in settings.")
        var base = prefs.getString("home_base", "").orEmpty().trim().trimEnd('/')
        if (base.isEmpty()) return t("لم يتم ضبط عنوان ESP32 في الإعدادات.", "The ESP32 address is not set.")
        if (!base.startsWith("http")) base = "http://$base"
        val key = prefs.getString("home_key", "").orEmpty().trim()
        val url = "$base/relay?id=${Uri.encode(id)}&state=${if (on) "on" else "off"}" +
            if (key.isNotEmpty()) "&key=${Uri.encode(key)}" else ""
        return try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 4000
            con.readTimeout = 4000
            val code = con.responseCode
            con.disconnect()
            if (code in 200..299) null else t("$name لم يستجب.", "$name did not answer.")
        } catch (e: Exception) {
            t("لم أستطع الوصول إلى $name.", "I could not reach $name.")
        }
    }

    private fun home(name: String, on: Boolean): String {
        val id = findDevice(name)
            ?: return t("الجهاز \"$name\" غير مضاف في الإعدادات.", "The device \"$name\" is not in settings.")
        var base = prefs.getString("home_base", "").orEmpty().trim().trimEnd('/')
        if (base.isEmpty()) return t("لم يتم ضبط عنوان ESP32 في الإعدادات.", "The ESP32 address is not set.")
        if (!base.startsWith("http")) base = "http://$base"
        val key = prefs.getString("home_key", "").orEmpty().trim()
        val url = "$base/relay?id=${Uri.encode(id)}&state=${if (on) "on" else "off"}" +
            if (key.isNotEmpty()) "&key=${Uri.encode(key)}" else ""
        Thread {
            val ok = try {
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 4000
                con.readTimeout = 4000
                val code = con.responseCode
                con.disconnect()
                code in 200..299
            } catch (e: Exception) {
                false
            }
            if (!ok) main.post {
                val problem = t("لم أستطع الوصول إلى $name.", "I could not reach $name.")
                log("جوني: $problem")
                say(problem)
            }
        }.start()
        return if (on) t("حاضر، شغّلت $name.", "OK, $name is on.") else t("حاضر، طفّيت $name.", "OK, $name is off.")
    }

    // ------------------------------------------------------------------ calls and messages

    /** Best matching contact for a spoken name: (display name, phone number). */
    private fun findContact(spoken: String): Pair<String, String>? {
        val wanted = Commands.nameCandidates(spoken)
        if (wanted.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                val n = Commands.normalize(name)
                val score = wanted.maxOf { w ->
                    when {
                        n == w -> 4
                        n.startsWith("$w ") -> 3
                        " $n ".contains(" $w ") -> 2
                        n.contains(w) -> 1
                        else -> 0
                    }
                }
                if (score > bestScore || (score == bestScore && score > 0 && name.length < (best?.first?.length ?: 0))) {
                    bestScore = score
                    best = name to number
                }
            }
        }
        return best
    }

    private fun dial(number: String): String {
        if (!granted(Manifest.permission.CALL_PHONE)) return t("لا يوجد إذن للاتصال.", "Phone permission missing.")
        startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return t("جاري الاتصال.", "Calling.")
    }

    private fun call(target: String): String {
        if (target == "emergency") {
            val number = prefs.getString("emergency_number", "").orEmpty().trim()
            return if (number.isEmpty()) t("لم يتم ضبط رقم المرافق.", "No caregiver number is set.") else dial(number)
        }
        if (target.isNotEmpty() && target.all { it.isDigit() || it == '+' }) return dial(target)
        if (!granted(Manifest.permission.READ_CONTACTS)) return t("لا يوجد إذن لجهات الاتصال.", "Contacts permission missing.")
        val contact = resolveContact(target) ?: return t("لم أجد \"$target\" في جهات الاتصال.", "I cannot find \"$target\" in contacts.")
        lastContact = contact.first
        val result = dial(contact.second)
        return if (result == t("جاري الاتصال.", "Calling.")) t("أتصل بـ ${contact.first}.", "Calling ${contact.first}.") else result
    }

    /** A contact by name, or the last person we talked about when the sentence said "him" / "her" / "ليه". */
    private fun resolveContact(spoken: String): Pair<String, String>? {
        val name = if (Commands.isPronoun(spoken)) lastContact ?: return null else (aliasFor(spoken) ?: spoken)
        return findContact(name)
    }

    /** Phone number written for wa.me: digits only, with the country code from settings when it starts with 0. */
    private fun waNumber(raw: String): String {
        val cc = prefs.getString("wa_cc", "249").orEmpty().filter { it.isDigit() }.ifEmpty { "249" }
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> cc + digits.drop(1)
            else -> digits
        }
    }

    private fun whatsappPackage(): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }

    private fun whatsapp(name: String, body: String): String {
        if (!granted(Manifest.permission.READ_CONTACTS)) return t("لا يوجد إذن لجهات الاتصال.", "Contacts permission missing.")
        val pkg = whatsappPackage() ?: return t("واتساب غير مثبت.", "WhatsApp is not installed.")
        val contact = resolveContact(name) ?: return t("لم أجد \"$name\" في جهات الاتصال.", "I cannot find \"$name\" in contacts.")
        lastContact = contact.first
        if (body.isBlank()) return t("قل الرسالة كاملة، مثلاً: ارسل لأحمد واتساب تقول أنا في الطريق.",
            "Say the whole message, for example: whatsapp Ali saying I am on my way.")
        return ask(t("أرسل واتساب إلى ${contact.first}: $body. نعم أو لا؟", "WhatsApp to ${contact.first}: $body. Yes or no?")) {
            val uri = Uri.parse("https://wa.me/${waNumber(contact.second)}?text=${Uri.encode(body)}")
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            pressSendSoon(3)
            t("أرسل الرسالة في واتساب.", "Sending in WhatsApp.")
        }
    }

    /** Press the send button once the chat has opened (tries a few times while the screen loads). */
    private fun pressSendSoon(attempts: Int, delayMs: Long = 3200) {
        main.postDelayed({
            val target = findByLabel("ارسال") ?: findByLabel("send")
            if (target != null) {
                press(target)
                respond(t("أرسلت.", "Sent."))
            } else if (attempts > 1) pressSendSoon(attempts - 1, 1800)
            else respond(t("فتحت المحادثة. قل: أرسل.", "Chat is open. Say: send."))
        }, delayMs)
    }

    /** Write a reply in the chat on screen, after a yes. */
    private fun reply(body: String): String {
        val action = replyAction
        val input = replyInput
        if (action != null && input != null && SystemClock.elapsedRealtime() - replyAt < 90_000) {
            val from = replyFrom
            return ask(t("أرد على $from بـ: $body. نعم أو لا؟", "Reply to $from: $body. Yes or no?")) {
                try {
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, body) })
                    action.actionIntent.send(this, 0, intent)
                    replyAction = null
                    t("أرسلت الرد.", "Reply sent.")
                } catch (e: Exception) {
                    t("تعذر إرسال الرد: ${e.message}", "Could not send the reply: ${e.message}")
                }
            }
        }
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: screenNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
            ?: return t("افتح المحادثة أولاً.", "Open the chat first.")
        return ask(t("أرد بـ: $body. نعم أو لا؟", "Reply: $body. Yes or no?")) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, body) }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            pressSendSoon(3, 900)
            t("أرد الآن.", "Replying.")
        }
    }

    /** Read the last message of the open WhatsApp chat. */
    private fun readLast(): String {
        val root = rootInActiveWindow
        val ids = listOf("com.whatsapp:id/message_text", "com.whatsapp.w4b:id/message_text")
        val texts = ids.flatMap { root?.findAccessibilityNodeInfosByViewId(it).orEmpty() }
            .filter { it.isVisibleToUser }
            .sortedBy { val r = Rect(); it.getBoundsInScreen(r); r.top }
            .mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() }
        if (texts.isEmpty()) return t("لم أجد رسائل. افتح المحادثة أولاً.", "I found no messages. Open the chat first.")
        return t("آخر رسالة: ${texts.last()}", "Last message: ${texts.last()}")
    }

    /** Where am I, and what can I press here. Everything comes from the screen itself, so it works offline. */
    private fun describeScreen(whereOnly: Boolean): String {
        val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        val head = if (app.isEmpty()) t("لا أستطيع رؤية الشاشة.", "I cannot see the screen.") else t("أنت في $app.", "You are in $app.")
        if (whereOnly || app.isEmpty()) return head
        val buttons = LinkedHashSet<String>()
        var unnamed = 0
        val texts = LinkedHashSet<String>()
        for (node in screenNodes(1500)) {
            if (!node.isVisibleToUser) continue
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (node.isClickable) {
                if (label.isEmpty()) unnamed++ else if (label.length <= 30) buttons.add(label)
            } else if (label.length in 4..120) texts.add(label)
        }
        val sb = StringBuilder(head)
        if (buttons.isNotEmpty()) sb.append(t(" الأزرار: ", " Buttons: ")).append(buttons.take(8).joinToString("، ")).append(".")
        if (unnamed > 0) sb.append(t(" وفيها $unnamed زر بلا اسم، قل: الأرقام لاستخدامها.", " $unnamed buttons have no name; say: numbers."))
        if (texts.isNotEmpty()) sb.append(t(" النص: ", " Text: ")).append(texts.take(3).joinToString(". "))
        return sb.toString()
    }

    /** Search inside the app that was just opened: tap its search box, type, and press enter. Null = no search box. */
    private fun searchHere(query: String): String? {
        val recent = SystemClock.elapsedRealtime() - lastAppAt < 120_000
        val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (!recent || pkg != lastApp) return null
        val nodes = screenNodes(2000).filter { it.isVisibleToUser }
        val box = nodes.firstOrNull { it.isEditable }
            ?: nodes.filter { val n = nameOf(it); n.contains("بحث") || n.contains("search") }.minByOrNull { nameOf(it).length }
            ?: return null
        press(box)
        main.postDelayed({
            val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: screenNodes(2000).firstOrNull { it.isEditable && it.isVisibleToUser }
            if (field != null) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                main.postDelayed({
                    if (Build.VERSION.SDK_INT >= 30) field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }, 500)
            }
        }, 700)
        return t("أبحث عن $query.", "Searching for $query.")
    }

    // ------------------------------------------------------------------ offline speech pack

    fun openVoiceSettings() {
        val intents = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (i in intents) try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
    }

    /** Check that the Arabic pack is on the phone and start downloading it if it is not (Android 13+). */
    fun downloadOfflinePack() {
        val lang = prefs.getString("lang", "ar-SA").orEmpty()
        val prefix = lang.substringBefore('-')
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            log("نزّل حزمة اللغة العربية: إعدادات Google ← الصوت ← التعرف على الكلام دون اتصال. فتحت لك الإعدادات.")
            openVoiceSettings()
            return
        }
        val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val intent = recognizerIntent(lang)
        checker.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installed = support.installedOnDeviceLanguages
                val download = support.supportedOnDeviceLanguages
                when {
                    installed.any { it.startsWith(prefix) } -> {
                        prefs.edit().putBoolean("offline_ready", true).apply()
                        forceNormal = false
                        log("حزمة الصوت جاهزة، الاستماع يعمل دون إنترنت.")
                        if (listening) { rebuildRecognizer(); ensureRecognizer() }
                    }
                    download.any { it.startsWith(prefix) } -> {
                        val tag = download.first { it.startsWith(prefix) }
                        checker.triggerModelDownload(recognizerIntent(tag))
                        log("بدأ تنزيل حزمة الصوت ($tag). يحتاج إنترنت مرة واحدة، ثم يعمل دون إنترنت.")
                    }
                    else -> {
                        log("هذا الهاتف لا يدعم الاستماع العربي دون إنترنت داخلياً. نزّل الحزمة من إعدادات الصوت.")
                        openVoiceSettings()
                    }
                }
                main.postDelayed({ try { checker.destroy() } catch (_: Exception) {} }, 3_000)
            }

            override fun onError(error: Int) {
                log("تعذر فحص حزمة الصوت (رمز $error).")
                try { checker.destroy() } catch (_: Exception) {}
            }
        })
    }

    private fun message(name: String, body: String): String {
        if (!granted(Manifest.permission.READ_CONTACTS)) return t("لا يوجد إذن لجهات الاتصال.", "Contacts permission missing.")
        if (!granted(Manifest.permission.SEND_SMS)) return t("لا يوجد إذن للرسائل.", "SMS permission missing.")
        val contact = resolveContact(name) ?: return t("لم أجد \"$name\" في جهات الاتصال.", "I cannot find \"$name\" in contacts.")
        lastContact = contact.first
        if (body.isBlank()) return t("قل الرسالة كاملة، مثلاً: أرسل رسالة لأحمد تقول أنا في الطريق.",
            "Say the whole message, for example: send a message to Ali saying I am on my way.")
        // never send without a clear yes: speech recognition can pick the wrong person
        return ask(t("أرسل إلى ${contact.first}: $body. نعم أو لا؟", "Send to ${contact.first}: $body. Yes or no?")) {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendMultipartTextMessage(contact.second, null, sms.divideMessage(body), null, null)
            t("أرسلت الرسالة.", "Message sent.")
        }
    }

    /** Alert the caregiver: push notification through ntfy, then a phone call if a number is saved. */
    private fun emergency(): String {
        val topic = prefs.getString("ntfy_topic", "").orEmpty().trim()
        val number = prefs.getString("emergency_number", "").orEmpty().trim()
        if (topic.isNotEmpty()) Thread {
            try {
                val con = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
                con.requestMethod = "POST"
                con.doOutput = true
                con.connectTimeout = 10000
                con.readTimeout = 10000
                con.setRequestProperty("Priority", "urgent")
                con.setRequestProperty("Tags", "rotating_light")
                con.outputStream.use { it.write("جوني: طلب مساعدة عاجل".toByteArray()) }
                log(if (con.responseCode in 200..299) "تم إرسال تنبيه الطوارئ." else "فشل إرسال التنبيه (${con.responseCode}).")
                con.disconnect()
            } catch (e: Exception) {
                log("فشل إرسال التنبيه: ${e.message}")
            }
        }.start()
        smsCaregiver("جوني: طلب مساعدة عاجل!")
        val called = number.isNotEmpty() && dial(number) == t("جاري الاتصال.", "Calling.")
        return when {
            called -> t("أتصل بالمرافق الآن.", "Calling your caregiver now.")
            topic.isNotEmpty() -> t("أرسلت طلب المساعدة.", "Help request sent.")
            else -> t("لم يتم ضبط رقم أو قناة للطوارئ.", "No emergency number or channel is set.")
        }
    }

    // ------------------------------------------------------------------ numbers overlay

    /** Draws a small numbered badge on every button so it can be pressed by saying its number. */
    private class NumbersView(context: Context, private val items: List<Rect>) : View(context) {
        private val density = resources.displayMetrics.density
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF20B3954.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4FC3F7.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 14f * density
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        private val origin = IntArray(2)
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(origin)
            val h = 22f * density
            for ((i, r) in items.withIndex()) {
                val text = (i + 1).toString()
                val w = maxOf(h, label.measureText(text) + 10f * density)
                val left = (r.left - origin[0]).toFloat().coerceIn(0f, maxOf(0f, width - w))
                val top = (r.top - origin[1]).toFloat().coerceIn(0f, maxOf(0f, height - h))
                box.set(left, top, left + w, top + h)
                canvas.drawRoundRect(box, h / 2, h / 2, badge)
                canvas.drawRoundRect(box, h / 2, h / 2, border)
                canvas.drawText(text, box.centerX(), box.centerY() - (label.descent() + label.ascent()) / 2, label)
            }
        }
    }

    /** A 3 x 4 grid over the chosen area, each square numbered 1 to 12. */
    private class GridView(context: Context, private val area: Rect) : View(context) {
        private val density = resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC4FC3F7.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        private val shade = Paint().apply { color = 0x330B3954 }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 26f * density; isFakeBoldText = true; textAlign = Paint.Align.CENTER
            setShadowLayer(4f * density, 0f, 0f, 0xFF000000.toInt())
        }
        private val origin = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(origin)
            val l = (area.left - origin[0]).toFloat()
            val t = (area.top - origin[1]).toFloat()
            val w = area.width() / 3f
            val h = area.height() / 4f
            canvas.drawRect(l, t, l + area.width(), t + area.height(), shade)
            for (i in 0..3) canvas.drawLine(l, t + i * h, l + area.width(), t + i * h, line)
            for (i in 0..3) canvas.drawLine(l + i * w, t, l + i * w, t + area.height(), line)
            for (n in 1..12) {
                val col = (n - 1) % 3
                val row = (n - 1) / 3
                canvas.drawText(n.toString(), l + col * w + w / 2, t + row * h + h / 2 - (label.descent() + label.ascent()) / 2, label)
            }
        }
    }
}
