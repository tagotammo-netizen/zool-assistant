package org.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.RemoteInput
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.MediaPlayer
import android.net.ConnectivityManager
import java.io.File
import android.net.NetworkCapabilities
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
import android.speech.RecognitionService
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
import android.widget.TextView
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
 * The heart of Zol on the phone. As an accessibility service it keeps running over every app,
 * listens for "زول ..." (or the chosen wake word) and controls the screen without touch.
 */
class JarvisService : AccessibilityService() {

    companion object {
        var instance: JarvisService? = null
        var onLog: ((String) -> Unit)? = null
        val history = ArrayList<String>()
        private const val CHANNEL = "jarvis_listening"
        /** Zol's own screens (captions…) close when "stop everything" is pressed. */
        const val ACTION_CLOSE_SCREENS = "org.jarvis.assistant.CLOSE_SCREENS"

        /** Placeholders that chat apps report as if they were typed text (already normalised). */
        private val PLACEHOLDERS = setOf("مراسله", "رساله", "اكتب رساله", "اكتب رسالتك", "الرساله", "رساله نصيه", "بحث", "ابحث",
            "message", "type a message", "text message", "search", "write a message")

        /** Instructions for the optional language model: turn messy speech into one of our commands. */
        private val AI_PROMPT = """
            You are Zol (زول), a voice assistant on an Android phone for a person who cannot use their hands.
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

    // after the wake word alone ("زول") the next sentence is the command, no wake word needed
    private var awakeUntil = 0L
    val awake get() = listening && SystemClock.elapsedRealtime() < awakeUntil

    /** For the system check in the app: can Zol speak? */
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

    /** Reply in the person's language; Arabic replies in Sudanese unless they chose standard Arabic. */
    private fun t(ar: String, en: String) = if (!arabic) en else if (sudani()) Sudani.convert(ar) else ar

    private fun sudani() = prefs.getString("reply_style", "sudani") != "fusha"

    /** "سبعة وربع الصباح" in Sudanese style, "7:15" otherwise. */
    private fun clockText(h: Int, m: Int) =
        if (arabic && sudani()) SpokenTime.say(h, m) else String.format(Locale.US, "%d:%02d", h, m)

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
        Commands.setWake(prefs.getString("wake_word", "زول"))
        usePrayerPlace()
        initVoice()
        Thread { OfflinePack.copyDetector(this, whisper.dir()) }.start()
        main.postDelayed(habitTick, 60_000)
        main.postDelayed(stopButtonTick, 3_000)
        main.postDelayed(planTick, 60_000)
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
        noteStart()
        markActive(force = true)
        applyCareSettings()
        // work in the background: start listening by itself, without opening the app
        if (prefs.getBoolean("auto_listen", false)) main.postDelayed({ if (!listening) startListening(false) }, 1500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (learning != null) watch(event)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && switches?.running == true)
            main.postDelayed({ switches?.refresh() }, 500)
        if (head?.running != true) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source ?: return
                if (!source.isEditable) return
                if (kbWanted || prefs.getBoolean("head_kb_auto", true)) main.post { showHeadKeyboard(source) }
                else if (head?.keyboardShown == true) main.post { refreshHeadKeyboard() }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (head?.keyboardShown == true)
                main.postDelayed({ if (head?.keyboardShown == true && fieldText() == null) hideHeadKeyboard() }, 600)
        }
    }

    override fun onInterrupt() {}

    /** A Bluetooth switch, a keyboard, or the volume keys when they are switches. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // the headset's button: talk to Zol without the wake word (play/pause still works while music plays)
        if (prefs.getBoolean("headset_ptt", true) && (event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !othersPlaying() && quran?.playing != true))) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) main.post { pushToTalk() }
            return true
        }
        val control = switches ?: return false
        if (!control.running) return false
        val key = SwitchScan.keyOf(event.keyCode, prefs.getString("switch_mode", "off") == "one", prefs.getBoolean("switch_volume", false))
        if (key == SwitchScan.Key.NONE) return false
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) main.post {
            markActive()
            if (key == SwitchScan.Key.NEXT) control.step() else control.select()
        }
        return true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        noteStop()
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        noteStop()
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
        hideHeadKeyboard()
        head?.stop()
        head = null
        quran?.stop()
        clipPlayer?.let { try { it.release() } catch (_: Exception) {} }
        clipPlayer = null
        stopQibla()
        stopFall()
        switches?.stop()
        switches = null
        board?.hide()
        board = null
        hideBubble()
        cancelRecognition()
        ear?.stop()
        ear = null
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
        log("أستمع الآن (المحرك: ${if (cloudStt() && cloudSttReady()) "Groq Whisper" else engineLabel()}). قل: ${Commands.wakeWord} ثم الأمر.")
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
        cancelRecognition()
        ear?.stop()
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
                .createNotificationChannel(NotificationChannel(CHANNEL, Brand.NAME, NotificationManager.IMPORTANCE_LOW))
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(Brand.NAME)
                .setContentText(when {
                    mic && cam -> "أستمع، والمؤشر يتبع رأسك"
                    cam -> "المؤشر يتبع رأسك"
                    else -> "أستمع... قل: ${Commands.wakeWord}"
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
    private var enginesLogged = false

    private fun ensureRecognizer() {
        if (recognizer != null || useWhisper()) return
        val wantOffline = offlineWanted()
        val r = if (Build.VERSION.SDK_INT >= 33 && wantOffline && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            onDevice = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            onDevice = false
            // nothing chosen: the Google app's engine when the phone has it. The phone's default is often
            // "Speech Recognition & Synthesis", which answers "too many requests" (error 10) on some phones.
            val chosen = prefs.getString("stt_service", null)?.let { ComponentName.unflattenFromString(it) }
                ?.takeIf { it in recognitionServices() }
                ?: recognitionServices().firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            if (!enginesLogged) {
                enginesLogged = true
                val names = recognitionServices().map { c ->
                    try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Exception) { c.packageName }
                }
                log("محركات الاستماع في التلفون: " + names.ifEmpty { listOf("ما في") }.joinToString("، "))
            }
            if (chosen != null) SpeechRecognizer.createSpeechRecognizer(this, chosen) else SpeechRecognizer.createSpeechRecognizer(this)
        }
        r.setRecognitionListener(listener)
        recognizer = r
    }

    /** Every speech engine on the phone (Google, the maker's own...). */
    private fun recognitionServices(): List<ComponentName> = try {
        packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    } catch (_: Exception) { emptyList() }

    private var busyErrors = 0          // "too many requests" / "busy" in a row
    private var busyWhisperUntil = 0L   // listening offline for a while because every engine refused

    /**
     * The engine keeps refusing ("too many requests"): try the phone's offline engine, then another speech engine,
     * then the offline Whisper engine for a quarter of an hour.
     */
    private fun switchRecognizer() {
        val services = recognitionServices()
        val current = (prefs.getString("stt_service", null)
            ?: android.provider.Settings.Secure.getString(contentResolver, "voice_recognition_service"))
            ?.let { ComponentName.unflattenFromString(it) }
        val at = services.indexOf(current)
        val others = if (services.size < 2) emptyList() else List(services.size - 1) { services[(at + 1 + it).mod(services.size)] }
            .filter { it != current }
        when {
            Build.VERSION.SDK_INT >= 33 && !onDevice && !prefs.getBoolean("offline_ready", false) &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this) && !triedOnDevice -> {
                triedOnDevice = true
                log("محرك الاستماع رافض الطلبات. بجرب محرك التلفون الداخلي.")
                downloadOfflinePack()
            }
            others.any { arabicEngine(it) } -> {
                val next = others.first { arabicEngine(it) }
                prefs.edit().putString("stt_service", next.flattenToString()).apply()
                val name = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(next.packageName, 0)) } catch (_: Exception) { next.packageName }
                log("محرك الاستماع رافض الطلبات. غيّرتو لمحرك تاني: $name.")
            }
            whisper.missing().isEmpty() && !whisperFailed -> {
                busyWhisperUntil = SystemClock.elapsedRealtime() + 15 * 60_000L
                log("كل محركات الاستماع رافضة. بسمع بدون نت (Whisper) لمدة ربع ساعة.")
            }
            else -> log("محرك الاستماع رافض الطلبات. حأستنى شوية وأجرب تاني. لو استمر: أعد تشغيل التلفون.")
        }
    }

    private var triedOnDevice = false

    /** Google's speech engines, except Android System Intelligence (on-device only, no Arabic here). */
    private fun arabicEngine(c: ComponentName) = c.packageName.startsWith("com.google.") && c.packageName != "com.google.android.as"
    private var langErrors = 0

    /** The engine in use, by name ("Google", "Honor"...), for the log and the report. */
    private fun engineLabel(): String {
        val c = prefs.getString("stt_service", null)?.let { ComponentName.unflattenFromString(it) }
            ?: recognitionServices().firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: android.provider.Settings.Secure.getString(contentResolver, "voice_recognition_service")
                ?.let { ComponentName.unflattenFromString(it) } ?: return "الأساسي"
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() }
        catch (_: Exception) { c.packageName }
    }

    /** Google's speech engine, when it is on the phone: it knows Arabic. */
    private fun googleEngine(): ComponentName? = recognitionServices().firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
        ?: recognitionServices().firstOrNull { it.packageName.startsWith("com.google.") && it.packageName != "com.google.android.as" }

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
                if (cloudSttUntil != 0L && now > cloudSttUntil) {
                    cloudSttUntil = 0L
                    busyErrors = 0
                    log("برجع أجرب استماع Google.")
                    if (listening) listenOnce()
                }
                if (busyWhisperUntil != 0L && now > busyWhisperUntil) {
                    busyWhisperUntil = 0L
                    whisper.cancel()
                    log("رجعت أجرب الاستماع العادي.")
                    if (listening) resume(500)
                }
                if (netWhisper && online()) {
                    netWhisper = false
                    warnedNoNet = false
                    whisper.cancel()
                    log("رجع النت، رجعت للاستماع العادي.")
                    if (listening) resume(500)
                } else if (!netWhisper && warnedNoNet && online()) warnedNoNet = false
                if (!listening && !ecoPaused && prefs.getBoolean("auto_listen", false) && !blocked && granted(Manifest.permission.RECORD_AUDIO)) {
                    log("أعدت تشغيل الاستماع.")
                    startListening(false)
                } else if (listening && ear?.active == true && now - ear!!.lastFrame > 10_000) {
                    log("المايك وقف، بشغّلو تاني.")         // the phone took the microphone away (a call, another app)
                    ear?.stop()
                    listenOnce()
                } else if (listening && ear?.active != true && now >= noStartBefore && lastActivity > 0 &&
                    now - lastActivity > 40_000 && tts?.isSpeaking != true) {
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
        if (tts?.isSpeaking == true || clipPlayer != null) { ear?.muted = true; resume(500); return }   // not my own voice
        if (useWhisper()) { ear?.stop(); whisperListen(); return }
        if (cloudStt()) { cloudListen(); return }
        if (useGate()) { gateListen(); return }
        val wait = noStartBefore - SystemClock.elapsedRealtime()
        if (wait > 0) { resume(wait); return }
        ensureRecognizer()
        val intent = recognizerIntent()
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            log("خطأ في الاستماع: ${e.message}")
            resume(1500)
        }
    }

    // ------------------------------------------------------------------ Zol's own ear (Android 13+)

    private var ear: MicGate? = null
    private var gateActive = false              // a sentence is going to the recogniser through the pipe
    private var gatePipe: android.os.ParcelFileDescriptor? = null
    private var gateFailed = false              // this recogniser does not take sound from a pipe
    private var gateWorked = false
    private var gateErrors = 0
    private var noStartBefore = 0L              // waiting after "too many requests"

    private fun useGate() = Build.VERSION.SDK_INT >= 33 && !gateFailed && prefs.getBoolean("gate_on", true)

    /** Listen with Zol's own ear; the recogniser is asked only when someone talks. */
    private fun gateListen() {
        val e = ear ?: newEar().also { ear = it }
        e.captureMode = false
        if (!e.active && !e.start()) {
            gateFailed = true
            log("ما قدرت أفتح المايك بطريقتي، رجعت للطريقة العادية.")
            ear = null
            listenOnce()
            return
        }
        e.muted = false
        lastActivity = SystemClock.elapsedRealtime()
    }

    /** Someone started talking: one request, fed with the sound from the ear (and the moment before). */
    private fun gateSession() {
        val e = ear ?: return
        if (!listening || gateActive || tts?.isSpeaking == true || clipPlayer != null) return
        if (SystemClock.elapsedRealtime() < noStartBefore) return
        ensureRecognizer()
        val pipe = e.openPipe() ?: return
        val intent = recognizerIntent().apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicGate.RATE)
        }
        gateActive = true
        gatePipe = pipe
        lastActivity = SystemClock.elapsedRealtime()
        try {
            recognizer?.startListening(intent)
        } catch (ex: Exception) {
            endGateSession()
            if (gateTrouble()) return
        }
        main.removeCallbacks(gateTimeout)
        main.postDelayed(gateTimeout, 15_000)
    }

    private val gateTimeout = Runnable { if (gateActive) { cancelRecognition(); resume(200) } }

    private fun endGateSession() {
        if (!gateActive) return
        gateActive = false
        main.removeCallbacks(gateTimeout)
        ear?.detach()
        gatePipe?.let { try { it.close() } catch (_: Exception) {} }
        gatePipe = null
    }

    /** True when the ear was given up for this phone (the recogniser refuses sound from a pipe). */
    private fun gateTrouble(): Boolean {
        if (gateWorked) return false
        gateErrors++
        if (gateErrors < 3) return false
        gateFailed = true
        ear?.stop()
        ear = null
        log("محرك الاستماع في التلفون ده ما بيقبل الصوت من زول مباشرة، رجعت للطريقة العادية (مع انتظار أطول في السكوت).")
        rebuildRecognizer()
        resume(1500)
        return true
    }

    private var silencedTries = 0

    private fun newEar() = MicGate({ main.post { gateSession() } }, { pcm -> cloudHeard(pcm) }, { main.post { earSilenced() } },
        { session -> androidSilences(session) }, voiceDetector())
        .also { it.source = MicSources.order(prefs.getInt("ear_source", MicSources.CALL), silencedTries) }

    private var detector: VoiceDetector? = null
    private var detectorTried = false

    /** The voice-or-noise model, loaded once (null on a phone where it cannot run: loudness decides then). */
    private fun voiceDetector(): VoiceDetector? {
        if (!detectorTried) {
            detectorTried = true
            detector = VoiceDetector.create(assets)
            if (detector == null) log("ما قدرت أشغّل مميّز الكلام من الضجة، بستعمل علو الصوت بس.")
        }
        return detector
    }

    /** What Zol said lately, so hearing it back from the speaker is not taken as a command. */
    private val ownVoice = OwnVoice()

    /** Another app is playing sound (music, a video): then "زول" must open the sentence, not just appear in it. */
    private fun othersPlaying(): Boolean {
        if (quran?.playing == true || clipPlayer != null || findPlayer != null) return false
        return try { (getSystemService(AUDIO_SERVICE) as AudioManager).isMusicActive } catch (_: Exception) { false }
    }

    /** Android's own answer: is it giving this recording silence? (Zeros alone can be a quiet room on some phones.) */
    private fun androidSilences(session: Int): Boolean = if (Build.VERSION.SDK_INT < 29) true else try {
        getSystemService(AudioManager::class.java).activeRecordingConfigurations
            .any { it.clientAudioSessionId == session && it.isClientSilenced }
    } catch (_: Exception) { false }

    /**
     * Android gives Zol's microphone only silence. Usually another app holds it (a voice assistant such as YOYO or
     * "Hey Google" listening), or the phone stopped Zol hearing in the background. Try the other microphone input once,
     * then say plainly what to check.
     */
    private fun earSilenced() {
        silencedTries++
        ear?.stop()
        ear = null
        if (silencedTries < MicSources.ALL.size) {
            log("المايك جايب سكوت تام. بجرب مدخل مايك تاني.")
            if (listening) listenOnce()
            return
        }
        log("أندرويد ما مدّي زول صوت من المايك (سكوت تام). الأسباب الغالبة: مساعد صوتي تاني ماسك المايك " +
            "(YOYO أو «Hey Google»: اقفل الاستماع بتاعو)، أو زول ما مسموح ليهو يسمع في الخلفية " +
            "(اقفل خدمة زول وافتحها تاني، واتأكد إنو إشعار «أستمع» ظاهر).")
        silencedTries = 0
        main.postDelayed({ if (listening) listenOnce() }, 60_000)
    }

    /**
     * The microphone test: records 3 seconds from each microphone input while the person speaks, measures the room
     * and the voice, keeps the clearer input, and plays back what Zol heard (made louder, as it is sent).
     * Everything measured goes in the chat, so a report shows it.
     */
    fun micTest() {
        main.post {
            if (!granted(Manifest.permission.RECORD_AUDIO)) { log("لا يوجد إذن للميكروفون."); return@post }
            val wasListening = listening
            if (listening) stopListening(false)
            say(t("اختبار المايك. بعد كل صفارة قول: زول كم الساعة. حتسمع تلاتة صفارات.",
                "Microphone test. After each of the three beeps say: Zol, what time is it."))
            Thread {
                SystemClock.sleep(6_000)
                val results = ArrayList<Triple<Int, Pair<Double, Double>, ByteArray>>()
                for (source in MicSources.ALL) {
                    main.post { beep(1) }
                    SystemClock.sleep(400)
                    val (levels, pcm) = recordFor(source, 3_000)
                    results.add(Triple(source, SpeechGate.stats(levels), pcm))
                }
                main.post {
                    beep(2)
                    val name = { s: Int -> MicSources.name(s) }
                    val lines = results.map { (src, st, pcm) ->
                        if (pcm.isEmpty()) "${name(src)}: ما اشتغل"
                        else "${name(src)}: سكوت ${st.first.toInt()}، كلام ${st.second.toInt()} (×${"%.1f".format(st.second / maxOf(st.first, 1.0))})"
                    }
                    val pick = MicSources.choose(results.filter { it.third.isNotEmpty() }
                        .associate { it.first to (it.second.second to it.second.second / maxOf(it.second.first, 1.0)) })
                    val best = results.firstOrNull { it.first == pick }
                    log("اختبار المايك: " + lines.joinToString(" • "))
                    if (best == null) {
                        log("ولا مدخل مايك اشتغل. في تطبيق تاني ماسك المايك، أو أندرويد مانع زول.")
                    } else {
                        prefs.edit().putInt("ear_source", best.first).apply()
                        val clear = best.second.second / maxOf(best.second.first, 1.0)
                        log("اخترت ${name(best.first)}." + when {
                            best.second.second < 30 -> " بس الصوت الواصل ضعيف شديد: قرّب التلفون أو اتأكد إنو المايك ما مغطّي."
                            clear < 2.0 -> " الكلام ما واضح من صوت الغرفة: جرّب في مكان أهدأ أو قرّب التلفون."
                            else -> " الصوت واضح."
                        })
                        playBack(best.third, SpeechGate.gainFor(best.second.second))
                    }
                    ear?.stop(); ear = null; silencedTries = 0
                    if (wasListening) main.postDelayed({ startListening(false) }, 4_000)
                }
            }.start()
        }
    }

    /** Loudness of every 20 ms, and the sound itself. Empty when the input does not work. */
    @SuppressLint("MissingPermission")
    private fun recordFor(source: Int, ms: Long): Pair<List<Double>, ByteArray> {
        val rate = MicGate.RATE
        val frame = rate / 50 * 2
        val r = try {
            AudioRecord(MicSources.android(source),
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 10))
        } catch (_: Exception) { return emptyList<Double>() to ByteArray(0) }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return emptyList<Double>() to ByteArray(0) }
        val levels = ArrayList<Double>()
        val out = java.io.ByteArrayOutputStream()
        try {
            r.startRecording()
            val buf = ByteArray(frame)
            val end = SystemClock.elapsedRealtime() + ms
            while (SystemClock.elapsedRealtime() < end) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                levels.add(SpeechGate.level(buf, n))
                out.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        return levels to out.toByteArray()
    }

    /** Play a recording back, made louder the way it is sent to the recogniser. */
    private fun playBack(pcm: ByteArray, gain: Double) {
        val loud = pcm.copyOf()
        SpeechGate.amplify(loud, loud.size, gain)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(MicGate.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(loud.size)
                .build()
            track.write(loud, 0, loud.size)
            track.play()
            main.postDelayed({ try { track.release() } catch (_: Exception) {} }, loud.size / (MicGate.RATE * 2 / 1000L) + 500)
            log("دا الصوت الزول سمعو (بعد التكبير).")
        } catch (e: Exception) {
            log("ما قدرت أسمّعك التسجيل: ${e.message}")
        }
    }

    /** For the status screen: is sound reaching Zol's own ear? */
    fun micState(): String = when {
        !listening -> "الاستماع مقفول"
        ear == null || ear?.active != true -> "بيستعمل محرك الاستماع مباشرة"
        ear?.silenced == true -> "سكوت تام: أندرويد ما مدّي زول صوت"
        else -> "بيسمع (أعلى صوت ${ear?.speechPeak?.toInt() ?: 0}، تكبير ×${"%.1f".format(ear?.gain ?: 1.0)}" +
            (if (ear?.echoCancel == true) "، ما بيسمع صوت التلفون" else "") +
            (if (detector != null) "، بيميّز الكلام من الضجة" else "") + ")"
    }

    // ------------------------------------------------------------------ listening through Gemini

    private var cloudSttUntil = 0L               // Google refused: Gemini writes down what is said, for a while
    @Volatile private var cloudBusy = false
    private var cloudLastAt = 0L

    /** Gemini can hear: the smart server in settings is Gemini, with a key. */
    private fun cloudSttReady() = groqKey().isNotBlank()

    /** The Groq key for listening: its own field, or the smart-mode key when the smart server is Groq. */
    private fun groqKey(): String = prefs.getString("stt_key", "").orEmpty().trim().ifEmpty {
        if (prefs.getString("ai_url", "").orEmpty().contains("groq.com")) prefs.getString("ai_key", "").orEmpty().trim() else ""
    }

    private fun cloudStt() = cloudSttReady() && (prefs.getBoolean("stt_cloud", false) || SystemClock.elapsedRealtime() < cloudSttUntil)

    /** The ear records each sentence on the phone; only the sentence (not the silence) goes to Gemini. */
    private fun cloudListen() {
        cancelRecognition()
        val e = ear ?: newEar().also { ear = it }
        e.captureMode = true
        if (!e.active && !e.start()) {
            cloudSttUntil = 0L
            log("ما قدرت أفتح المايك للاستماع عن طريق Groq.")
            ear = null
            return
        }
        e.muted = false
        lastActivity = SystemClock.elapsedRealtime()
    }

    /** A recorded sentence (from the ear's thread). One request at a time, at most one every 1.5 s. */
    private fun cloudHeard(pcm: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (cloudBusy || now - cloudLastAt < 1_500 || !listening) return
        if (!online()) {                          // no internet: the offline engine, when its files are on the phone
            main.post { if (!netTrouble()) cloudLog("النت قاطع. نزّل «الاستماع بدون نت» عشان زول يسمعك من غير نت.") }
            return
        }
        cloudBusy = true
        cloudLastAt = now
        Thread {
            // whose voice: when the person taught Zol their voice, someone else's sentence is not a command
            val other = voiceprint.on() && voiceprint.embed(pcm)?.let { v ->
                voiceprint.enrolled()?.let { Speaker.cosine(v, it) < Speaker.SAME } } == true
            val text = try { groqTranscribe(pcm) } finally { cloudBusy = false }
            main.post {
                lastActivity = SystemClock.elapsedRealtime()
                if (text.isNullOrBlank() || !listening) return@post
                if (other && !Commands.isEmergency(text)) { otherVoice(text); resume(200); return@post }
                processResults(listOf(text.trim()))
            }
        }.start()
    }

    // ------------------------------------------------------------------ the life log, plans from messages, the secretary

    private val lifeFile by lazy { java.io.File(filesDir, "lifelog.txt") }
    private var lifeCache: MutableList<LifeLog.Entry>? = null

    private fun life(): MutableList<LifeLog.Entry> = lifeCache ?: (try { LifeLog.decode(lifeFile.readText()).toMutableList() }
        catch (_: Exception) { ArrayList() }).also { lifeCache = it }

    /** A message came: kept, checked for a plan, and maybe answered (the secretary). */
    fun noteLife(app: String, who: String, text: String, at: Long) {
        main.post {
            val list = life()
            val e = LifeLog.Entry(at, app, who, text)
            if (list.any { it.who == who && it.text == text && Math.abs(it.time - at) < 120_000 }) return@post
            list.add(e)
            val kept = LifeLog.trim(list, System.currentTimeMillis())
            lifeCache = kept.toMutableList()
            Thread { try { lifeFile.writeText(kept.joinToString("\n") { LifeLog.encode(it) }) } catch (_: Exception) {} }.start()
            if (prefs.getBoolean("plans_on", true) && aiReady() && LifeLog.looksLikePlan(text)) findPlan(e)
            if (prefs.getBoolean("secretary_on", false) && aiReady()) secretary(e)
        }
    }

    private fun askMemory(question: String): String {
        val now = System.currentTimeMillis()
        val found = LifeLog.search(life(), question, now)
        if (found.isEmpty()) return t("ما لقيت رسايل بالشكل ده في آخر شهر.", "I found no such messages in the last month.")
        if (!aiReady()) return found.take(3).joinToString(". ") { LifeLog.format(it, now) }
        val lines = found.joinToString("\n") { LifeLog.format(it, now) }
        Thread {
            val answer = cloudChat("You answer the user's question only from these messages they received (newest first). " +
                "Answer in one or two short sentences in Sudanese Arabic; say who said it and when. If the messages do not answer it, say so.\n\n$lines",
                question, 250)?.substringAfterLast("</think>")?.trim()
            main.post { respond(answer?.takeIf { it.isNotBlank() } ?: found.take(3).joinToString(". ") { LifeLog.format(it, now) }) }
        }.start()
        return t("لحظة، بفتش في الرسايل.", "Looking through your messages.")
    }

    /** A message with a time in it: when it is a plan, offer a reminder an hour before. */
    private fun findPlan(e: LifeLog.Entry) {
        Thread {
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.US).format(java.util.Date())
            val raw = cloudChat("Now it is $now. If this message sets a plan for the reader with a day and time (a visit, an appointment, " +
                "an invitation), answer only JSON {\"plan\":true,\"when\":\"yyyy-MM-dd HH:mm\",\"what\":\"a few Arabic words\"}; " +
                "otherwise only {\"plan\":false}.", "${e.who}: ${e.text}", 120) ?: return@Thread
            val json = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch (_: Exception) { return@Thread }
            if (!json.optBoolean("plan")) return@Thread
            val at = try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(json.optString("when"))?.time } catch (_: Exception) { null }
                ?: return@Thread
            if (at < System.currentTimeMillis() + 30 * 60_000L) return@Thread
            val what = json.optString("what").ifBlank { "موعد" }
            val remindAt = maxOf(at - 60 * 60_000L, System.currentTimeMillis() + 10 * 60_000L)
            val day = android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS)
            val clock = java.text.SimpleDateFormat("h:mm", java.util.Locale.US).format(java.util.Date(at))
            main.post {
                respond(ask(t("${e.who} قال: «${e.text.take(80)}». أذكّرك بـ«$what» قبل ساعة ($day الساعة $clock)؟ نعم أو لا.",
                    "Remind you of $what an hour before?")) {
                    addPlan(remindAt, "$what مع ${e.who} الساعة $clock")
                    t("حاضر، بذكّرك.", "I will remind you.")
                })
            }
        }.start()
    }

    private fun addPlan(at: Long, text: String) {
        val list = prefs.getString("plans", "").orEmpty().lines().filter { it.isNotBlank() } + "$at|$text"
        prefs.edit().putString("plans", list.joinToString("\n")).apply()
    }

    /** Every minute: a plan whose time came is said. */
    private val planTick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 60_000)
            val now = System.currentTimeMillis()
            val lines = prefs.getString("plans", "").orEmpty().lines().filter { it.isNotBlank() }
            val (due, later) = lines.partition { (it.substringBefore('|').toLongOrNull() ?: 0L) <= now }
            if (due.isEmpty()) return
            prefs.edit().putString("plans", later.joinToString("\n")).apply()
            due.forEach { announce(t("تذكير: ", "Reminder: ") + it.substringAfter('|'), false, urgent = true) }
        }
    }

    private val secretaryLog = ArrayList<String>()
    private val secretaryLast = HashMap<String, Long>()

    /** While the person rests: a short polite answer that they will reply later; nothing promised, nothing told. */
    private fun secretary(e: LifeLog.Entry) {
        val action = replyAction ?: return
        val input = replyInput ?: return
        if (replyFrom != e.who || SystemClock.elapsedRealtime() - replyAt > 30_000) return
        val last = secretaryLast[e.who] ?: 0L
        if (System.currentTimeMillis() - last < 30 * 60_000L) { secretaryLog.add("${e.who}: ${e.text.take(80)}"); return }
        secretaryLast[e.who] = System.currentTimeMillis()
        val persona = Personas.prompt(prefs.getString("persona", "friend").orEmpty())
        Thread {
            val reply = cloudChat("You answer a message for the phone's owner, who is resting. $persona Write one short, warm reply in " +
                "Sudanese Arabic saying they will answer soon. Never promise anything, never agree to plans, never give any information " +
                "(where they are, health, money, numbers). Output only the reply.", "${e.who}: ${e.text}", 80)
                ?.substringAfterLast("</think>")?.trim()?.trim('"') ?: return@Thread
            val body = "$reply\n— رد تلقائي من زول"
            main.post {
                try {
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, body) })
                    action.actionIntent.send(this, 0, intent)
                    secretaryLog.add("${e.who}: ${e.text.take(80)} ← رديت: $reply")
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun secretaryReport(): String {
        if (secretaryLog.isEmpty()) return t("ما في رسايل جات وإنت مرتاح.", "No messages came while you rested.")
        val s = t("وإنت مرتاح: ", "While you rested: ") + secretaryLog.takeLast(8).joinToString(". ")
        secretaryLog.clear()
        return s
    }

    // ------------------------------------------------------------------ photos

    fun photosAllowed() = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** The newest photos on the phone: (id, content address), newest first. */
    private fun recentPhotos(max: Int): List<Pair<Long, Uri>> {
        val out = ArrayList<Pair<Long, Uri>>()
        val base = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        try {
            contentResolver.query(base, arrayOf(android.provider.MediaStore.Images.Media._ID), null, null,
                "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < max) { val id = c.getLong(0); out.add(id to android.content.ContentUris.withAppendedId(base, id)) }
            }
        } catch (_: Exception) {}
        return out
    }

    /** A small picture of a photo, as JPEG base64, for the model that can see. */
    private fun thumb(uri: Uri): String? = try {
        val bmp = if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, android.util.Size(512, 512), null)
            else contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }
        bmp?.let { jpeg(it) }
    } catch (_: Exception) { null }

    private fun sendLastPhoto(name: String): String {
        if (!photosAllowed()) return t("محتاج إذن الصور: افتح ✨ الميزات ← صورك.", "Photo permission needed: Features → photos.")
        val pkg = whatsappPackage() ?: return t("واتساب غير مثبت.", "WhatsApp is not installed.")
        val contact = resolveContact(name) ?: return t("لم أجد \"$name\" في جهات الاتصال.", "I cannot find \"$name\".")
        val (_, uri) = recentPhotos(1).firstOrNull() ?: return t("ما لقيت صور في التلفون.", "No photos on the phone.")
        Thread {
            val about = if (aiReady()) thumb(uri)?.let { visionChat("Describe this photo in one short Sudanese Arabic sentence.", "وصف الصورة", it, 80) }
                ?.substringAfterLast("</think>")?.trim() else null
            main.post {
                respond(ask(t((about?.let { "آخر صورة: $it. " } ?: "") + "أرسلها لـ${contact.first}؟ نعم أو لا.", "Send the last photo to ${contact.first}?")) {
                    try {
                        startActivity(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra("jid", waNumber(contact.second) + "@s.whatsapp.net").setPackage(pkg)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        pressSendSoon(4)
                        t("برسلها في واتساب.", "Sending.")
                    } catch (e: Exception) { t("ما قدرت: ${e.message}", "Could not send it.") }
                })
            }
        }.start()
        return t("لحظة، بشوف آخر صورة.", "Looking at the last photo.")
    }

    private val captionFile by lazy { java.io.File(filesDir, "photo_captions.txt") }

    /** Photos found by what is in them; the newest 200 are looked at, each described once and remembered. */
    private fun findPhotos(query: String): String {
        if (!photosAllowed()) return t("محتاج إذن الصور: افتح ✨ الميزات ← صورك.", "Photo permission needed: Features → photos.")
        if (!aiReady()) return t("البحث في الصور محتاج الذكاء السحابي.", "Photo search needs the smart server.")
        val terms = Photos.terms(query)
        if (terms.isEmpty()) return t("قول وريني صور شنو؟", "Photos of what?")
        Thread {
            val captions = try { Photos.decode(captionFile.readText()) } catch (_: Exception) { LinkedHashMap() }
            val photos = recentPhotos(200)
            val todo = photos.filter { it.first !in captions }.take(40)        // at most 40 new ones each time (the rest next time)
            if (todo.isNotEmpty()) main.post { log("بشوف ${todo.size} صورة جديدة أول مرة، دي بتاخد شوية…") }
            for (batch in todo.chunked(4)) {
                val pics = batch.mapNotNull { p -> thumb(p.second)?.let { p.first to it } }
                if (pics.isEmpty()) continue
                val raw = visionChat(Photos.PROMPT, "Describe these ${pics.size} photos.", pics.map { it.second }, 400) ?: continue
                Photos.parseCaptions(raw, pics.size).forEachIndexed { i, c -> if (c != null) captions[pics[i].first] = c }
            }
            try { captionFile.writeText(Photos.encode(captions)) } catch (_: Exception) {}
            val found = photos.map { it to Photos.score(captions[it.first].orEmpty(), terms) }.filter { it.second > 0 }
                .sortedByDescending { it.second }.map { it.first.second }.take(30)
            main.post {
                if (found.isEmpty()) respond(t("ما لقيت صور فيها «$query»" + (if (photos.size > captions.size) ". لسه في صور ما شفتها، قولها تاني." else "."),
                    "No photos of $query."))
                else {
                    startActivity(Intent(this, PhotosActivity::class.java).putExtra("uris", ArrayList(found.map { it.toString() }))
                        .putExtra("title", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    respond(t("لقيت ${found.size} صورة.", "Found ${found.size} photos."))
                }
            }
        }.start()
        return t("بفتش في صورك…", "Searching your photos…")
    }

    // ------------------------------------------------------------------ the TV on the Wi-Fi

    private fun savedTv(): TvRemote.Tv? = prefs.getString("tv", null)?.split('|')?.takeIf { it.size >= 2 }?.let { TvRemote.Tv(it[0], it[1]) }

    private fun findTvs(): List<TvRemote.Tv> {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        val lock = wifi.createMulticastLock("zol-tv").apply { setReferenceCounted(false) }
        return try { lock.acquire(); TvRemote.discover() } catch (_: Exception) { emptyList() } finally { try { lock.release() } catch (_: Exception) {} }
    }

    private fun tvCommand(action: String): String {
        Thread {
            var tv = savedTv()
            if (action == "find" || tv == null) {
                val tvs = findTvs()
                tv = tvs.firstOrNull()
                if (tv == null) {
                    main.post { respond(t("ما لقيت تلفزيون في الواي فاي. اتأكد إنو التلفزيون شغّال وفي نفس الشبكة. " +
                        "(زول بيعرف Roku وSamsung وLG؛ تلفزيونات أندرويد استعمل ليها «زول وصّل التلفزيون» بالبلوتوث.)", "No TV found on the Wi-Fi.")) }
                    return@Thread
                }
                prefs.edit().putString("tv", "${tv.brand}|${tv.ip}").apply()
                if (action == "find") { main.post { respond(t("لقيت تلفزيون ${tv.name}. قول مثلاً: زول علّي التلفزيون.", "Found a ${tv.name} TV.")) }; return@Thread }
            }
            val ok = try { tvSend(tv, action) } catch (_: Exception) { false }
            main.post {
                if (!ok) {
                    prefs.edit().remove("tv").apply()                // maybe its address changed: look again next time
                    respond(t("التلفزيون ما رد. لو أول مرة، اقبل طلب «زول» في شاشة التلفزيون وقول الأمر تاني.", "The TV did not answer; allow Zol on the TV and try again."))
                } else beep(2)
            }
        }.start()
        return ""
    }

    private fun tvSend(tv: TvRemote.Tv, action: String): Boolean = when (tv.brand) {
        "roku" -> TvRemote.roku(tv.ip, action)
        "samsung" -> {
            val app = mapOf("youtube" to "111299001912", "netflix" to "11101200001")[action]
            if (app != null) {
                val con = URL("http://${tv.ip}:8001/api/v2/applications/$app").openConnection() as HttpURLConnection
                con.requestMethod = "POST"; con.connectTimeout = 3000; con.readTimeout = 3000
                (con.responseCode in 200..299).also { con.disconnect() }
            } else {
                val key = TvRemote.samsungKey(action) ?: return false
                val name = android.util.Base64.encodeToString("زول".toByteArray(), android.util.Base64.NO_WRAP)
                TvRemote.ws(tv.ip, 8001, "/api/v2/channels/samsung.remote.control?name=$name", listOf(TvRemote.samsungMessage(key)), readMs = 1500)
            }
        }
        "lg" -> {
            val (uri, payload) = TvRemote.lgUri(action) ?: return false
            val saved = prefs.getString("tv_lg_key", null)
            TvRemote.ws(tv.ip, 3000, "/", listOf(TvRemote.lgRegister(saved), TvRemote.lgRequest(uri, payload)), readMs = if (saved == null) 30_000 else 2_000) { msg ->
                TvRemote.lgClientKey(msg)?.let { prefs.edit().putString("tv_lg_key", it).apply() }
            }
        }
        else -> false
    }

    // ------------------------------------------------------------------ learning from a correction

    private var lastSaid: Pair<String, Long>? = null
    private var correctFrom: Pair<String, Long>? = null

    /** "لا قصدي افتح الواتساب": the sentence before means this from now on; and it is done now. */
    private fun correctLast(meaning: String): String {
        val prev = correctFrom?.takeIf { SystemClock.elapsedRealtime() - it.second < 90_000 }?.first
        correctFrom = null
        if (Commands.parse(meaning) == null) return t("ما فهمت «$meaning» برضو. قولها بطريقة تانية.", "I don't understand that either.")
        if (prev != null && Commands.normalize(prev).trim() != Commands.normalize(meaning).trim()) {
            val (phrases, n) = Dialects.merge(prefs.getString("phrases", "").orEmpty(), mapOf(prev to meaning)) { Commands.normalize(it).trim() }
            val saved = if (n == 0) {                         // the sentence was taught before: the new meaning replaces it
                prefs.getString("phrases", "").orEmpty().lines().filter { it.isNotBlank() &&
                    Commands.normalize(it.substringBefore('=')).trim() != Commands.normalize(prev).trim() }.plus("$prev=$meaning").joinToString("\n")
            } else phrases
            prefs.edit().putString("phrases", saved).apply()
            Commands.userPhrases = Commands.parsePhrases(saved)
            log("اتعلمت: «$prev» يعني «$meaning».")
        }
        main.post { handleText(meaning) }
        return ""
    }

    // ------------------------------------------------------------------ the headset button

    private val clearHeadsetMic = Runnable {
        if (Build.VERSION.SDK_INT >= 31) try { (getSystemService(AUDIO_SERVICE) as AudioManager).clearCommunicationDevice() } catch (_: Exception) {}
    }

    /** A press on the headset: Zol listens for ten seconds without the wake word, through the headset's microphone. */
    private fun pushToTalk() {
        markActive()
        if (!granted(Manifest.permission.RECORD_AUDIO)) return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 31) try {
            audio.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            }?.let { audio.setCommunicationDevice(it) }
        } catch (_: Exception) {}
        main.removeCallbacks(clearHeadsetMic)
        main.postDelayed(clearHeadsetMic, 15_000)
        awakeUntil = SystemClock.elapsedRealtime() + 10_000
        beep(1)
        if (!listening) startListening(false) else { ear?.stop(); ear = null; resume(150) }   // the new microphone
    }

    // ------------------------------------------------------------------ the person's voice only

    val voiceprint by lazy { Voiceprint(this) }
    private var otherVoiceLogAt = 0L

    private fun otherVoice(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (Commands.stripWake(text) == null || now - otherVoiceLogAt < 60_000) return
        otherVoiceLogAt = now
        log("سمعت «${text.take(40)}» بصوت ما صوتك، فما نفذتو. (قول «زول اسمع كل الناس» لو عايز أسمع أي زول)")
    }

    private fun voiceprintCommand(what: String): String = when (what) {
        "off" -> { prefs.edit().putBoolean("voiceprint_on", false).apply(); t("بسمع أي زول هسع.", "Listening to anyone now.") }
        "on" -> if (voiceprint.enrolled() == null) learnVoice() else {
            prefs.edit().putBoolean("voiceprint_on", true).apply(); t("بسمع صوتك إنت بس.", "Only your voice now.") }
        else -> learnVoice()
    }

    /** Three sentences, after a beep each; their voice is kept as the person's. */
    private fun learnVoice(): String {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return t("لا يوجد إذن للميكروفون.", "Microphone permission missing.")
        if (!voiceprint.ready()) {
            voiceprint.download { error ->
                main.post { if (error == null) respond(learnVoice()) else log("ما كمل تنزيل بصمة الصوت: $error") }
            }
            return t("بنزّل بصمة الصوت (28 ميقا) أول مرة. التقدم ظاهر فوق.", "Downloading the voice model first.")
        }
        val wasListening = listening
        if (listening) stopListening(false)
        say(t("عشان أعرف صوتك: بعد كل صفارة قول جملة عادية، زي: زول كم الساعة. حتسمع تلاتة صفارات.",
            "To learn your voice: after each of three beeps, say a sentence."))
        Thread {
            SystemClock.sleep(7_000)
            val voices = ArrayList<FloatArray>()
            repeat(3) {
                main.post { beep(1) }
                SystemClock.sleep(400)
                val (_, pcm) = recordFor(prefs.getInt("ear_source", MicSources.CALL), 3_500)
                voiceprint.embed(pcm)?.let { voices.add(it) }
                SystemClock.sleep(600)
            }
            main.post {
                beep(2)
                val avg = Speaker.average(voices)
                when {
                    voices.size < 3 || avg == null -> respond(t("ما قدرت أسمع صوتك كويس. جرّب تاني في مكان هادي.", "Could not hear you well; try again."))
                    !Speaker.consistent(voices) -> respond(t("الجمل التلاتة ما طلعت بنفس الصوت (يمكن في زول اتكلم معاك). جرّب تاني براك.",
                        "The three did not sound like one voice; try again alone."))
                    else -> { voiceprint.save(avg); respond(t("عرفت صوتك. هسع بنفذ أوامرك إنت بس، إلا الطوارئ من أي زول.",
                        "I know your voice now; only you can give commands (anyone for emergencies).")) }
                }
                if (wasListening) main.postDelayed({ startListening(false) }, 3_000)
            }
        }.start()
        return ""
    }

    private var cloudFailures = 0

    /**
     * Google retires Gemini models and says which one to use instead ("... use models/gemini-3.8-flash ...").
     * Take that model for every request (listening and the smart mode). True when the model was changed.
     */
    private fun followModelHint(error: String): Boolean {
        val next = Regex("use models/([A-Za-z0-9.\\-]+)").find(error)?.groupValues?.get(1)?.trimEnd('.') ?: return false
        val old = prefs.getString("ai_model", "").orEmpty()
        if (next == old) return false
        prefs.edit().putString("ai_model", next).apply()
        main.post { log("موديل Gemini «$old» ما بقى متاح، غيّرتو لـ «$next» زي ما Google قال.") }
        return true
    }

    private var lastCloudLog = 0L
    /** The full Whisper: slower than turbo, but clearly better with Arabic dialects. */
    @Volatile private var sttWhisperModel = "whisper-large-v3"

    /** At most one line every 30 s about the cloud being busy, so the chat is not flooded. */
    private fun cloudLog(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCloudLog < 30_000) return
        lastCloudLog = now
        main.post { log(text) }
    }

    /**
     * Groq's Whisper (large-v3-turbo) writes down the sentence: free, fast, and good at Arabic. The sound goes as a
     * WAV file (OpenAI-style /audio/transcriptions). Pieces Whisper itself marks as "probably no speech" and the
     * sentences Whisper is known to invent from noise are dropped. Off the main thread.
     */
    private fun groqTranscribe(pcm: ByteArray): String? =
        groqFile(MicGate.wav(pcm), "s.wav", "audio/wav", if (translating) null else if (lesson != null) "en" else listenLang(), forListening = true)

    private fun listenLang() = if (prefs.getString("lang", "ar-SA").orEmpty().startsWith("en")) "en" else "ar"

    /**
     * Any sound file to Groq's Whisper. [lang] null: Whisper says which language it heard; anything but Arabic or
     * English is taken as Sudanese heard wrong and asked again in Arabic. [listening]: errors count against the
     * listening engine (three in a row turn Groq listening off).
     */
    private fun groqFile(audio: ByteArray, fileName: String, mime: String, lang: String?, forListening: Boolean = false): String? {
        val key = groqKey()
        if (key.isEmpty()) return null
        val boundary = "zol" + System.nanoTime()
        return try {
            val con = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 8000
            con.readTimeout = 15000 + audio.size / 2000            // a long voice message takes longer
            con.setRequestProperty("Authorization", "Bearer $key")
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            con.outputStream.use { out ->
                fun field(name: String, value: String) =
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                field("model", sttWhisperModel)
                field("response_format", "verbose_json")
                field("temperature", "0")
                if (lang != null) field("language", lang)            // guessing, Whisper took Sudanese for Icelandic
                // Sudanese words Zol hears every day: Whisper writes what it expects, so tell it what to expect
                field("prompt", "${Commands.wakeWord}، كم الساعة؟ ${Commands.wakeWord} اتصل بأمي. ${Commands.wakeWord} افتح واتساب. " +
                    "${Commands.wakeWord} رد عليهو قول ليهو جاي. ${Commands.wakeWord} شغل القرآن. داير، عايز، هسع، شنو، وين، كدا، ياخ.")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
                out.write(audio)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = con.responseCode
            if (code !in 200..299) {
                val why = try { con.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() } catch (_: Exception) { "" }
                val msg = Regex("\"message\"\\s*:\\s*\"([^\"]{0,160})").find(why)?.groupValues?.get(1) ?: why.take(160)
                con.disconnect()
                if ((code == 404 || code == 400) && sttWhisperModel != "whisper-large-v3-turbo") {
                    sttWhisperModel = "whisper-large-v3-turbo"        // the full model is not offered any more
                    return groqFile(audio, fileName, mime, lang, forListening)
                }
                if (code == 429 || code >= 500) { cloudLog("Groq مزحوم (${if (code == 429) "وصل حدّو" else "$code"}). بحاول مع الجملة الجاية."); return null }
                if (!forListening) { main.post { log("Groq رد بـ $code: $msg") }; return null }
                main.post {
                    cloudFailures++
                    log("Groq رد بـ $code: $msg" + if (code == 401) " (المفتاح غلط)" else "")
                    if (cloudFailures >= 3) {
                        log("وقّفت الاستماع عن طريق Groq بعد 3 أخطاء، برجع لـ Google.")
                        cloudSttUntil = 0L
                        prefs.edit().putBoolean("stt_cloud", false).apply()
                        cloudFailures = 0
                        if (listening) { ear?.captureMode = false; resume(2_000) }
                    }
                }
                return null
            }
            val raw = con.inputStream.bufferedReader().use { it.readText() }
            con.disconnect()
            if (forListening) main.post { cloudFailures = 0 }
            val json = JSONObject(raw)
            if (lang == null && json.optString("language").lowercase() !in setOf("arabic", "english", "ar", "en"))
                return groqFile(audio, fileName, mime, "ar", forListening)
            val segs = json.optJSONArray("segments")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("text") to o.optDouble("no_speech_prob", 0.0) } }
            }
            whisperText(json.optString("text"), segs)
        } catch (e: java.net.SocketTimeoutException) {
            cloudLog("Groq ما رد في الوقت. بحاول مع الجملة الجاية.")
            null
        } catch (e: Exception) {
            cloudLog("ما قدرت أرسل الصوت لـ Groq: ${e.message}")
            null
        }
    }

    /** The listening engine was changed in settings: start again with it. */
    fun listeningChanged() { main.post { if (listening) { cancelRecognition(); ear?.captureMode = false; listenOnce() } } }

    private fun cancelRecognition() {
        endGateSession()
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    private val whisper by lazy { WhisperEngine(this) }
    private var whisperFailed = false

    /** The experimental offline Whisper engine is used only when chosen in settings and while it works. */
    private fun useWhisper() = (prefs.getString("engine", "google") == "whisper" || netWhisper ||
        SystemClock.elapsedRealtime() < busyWhisperUntil) && !whisperFailed

    /** The internet dropped: listen with the offline engine (when its files are on the phone) until it comes back. */
    private var netWhisper = false
    private var warnedNoNet = false

    private fun online(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** True when the trouble was handled (switched to offline listening). */
    private fun netTrouble(): Boolean {
        if (online() || offlineWanted()) return false
        if (!netWhisper && prefs.getString("engine", "google") != "whisper" && whisper.missing().isEmpty() && !whisperFailed) {
            netWhisper = true
            log("النت قاطع، بقيت أسمع بدون نت.")
            rebuildRecognizer()
            resume(500)
            return true
        }
        if (!warnedNoNet) {
            warnedNoNet = true
            log("النت قاطع والاستماع محتاج نت. نزّل حزمة العربي بدون نت من الإعدادات ← الاستماع.")
        }
        return false
    }

    /** Download the offline listening files with a progress bar; when done, listening without internet is ready. */
    fun downloadWhisper(model: String) {
        val m = OfflinePack.MODELS.firstOrNull { it.id == model } ?: return
        if (Downloads.get("offline-$model") != null) { log("التنزيل شغّال."); return }
        log("بنزّل ${m.title}. التقدم ظاهر فوق. لو النت قطع، قول نفس الأمر تاني وبكمّل من وين وقف.")
        OfflinePack.download(this, whisper.dir(), model) { error ->
            main.post {
                if (error == null) {
                    resetWhisper()
                    log("الاستماع بدون نت جاهز. لو النت قطع، زول بيسمعك براهو من غير نت.")
                    say(t("الاستماع بدون نت جاهز.", "Offline listening is ready."))
                } else log("ما كمل تنزيل الحزمة: $error")
            }
        }
    }

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
            cancelRecognition()
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
                log("${Brand.NAME}: $text")
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
        cancelRecognition()
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
        val at = clockText(c.hour, c.minute)
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
        if (smsCaregiver("زول: أحتاج إليك إن أمكن.")) t("أبلغت المرافق.", "Your caregiver has been told.")
        else t("لم أستطع إبلاغ المرافق. تأكد من رقمه وإذن الرسائل.", "I could not tell your caregiver. Check the number and SMS permission.")

    // ---- local language model (optional)

    private val localLlm by lazy { LocalLlm(this) }

    private fun localAiReady() = prefs.getBoolean("llm_on", false) && localLlm.available()

    /** Download the thinking model (about 1.5 GB) so Zol understands harder sentences without internet. */
    private fun downloadBrain(): String {
        if (localLlm.available()) { prefs.edit().putBoolean("llm_on", true).apply(); return t("عقل زول بدون نت موجود وشغّال.", "The offline brain is ready.") }
        if (Downloads.get("brain") != null) return t("التنزيل شغّال.", "Downloading.")
        OfflinePack.downloadBrain(localLlm.file()) { error ->
            main.post {
                if (error == null) {
                    prefs.edit().putBoolean("llm_on", true).putString("llm_name", "qwen").apply()
                    respond(t("عقل زول بدون نت جاهز. لو النت قطع، بفهم الأوامر الصعبة براي.", "The offline brain is ready."))
                } else log("ما كمل تنزيل العقل: $error")
            }
        }
        return t("بنزّل عقل زول بدون نت (حوالي 1.5 قيقا، أحسن بالواي فاي). التقدم ظاهر فوق.", "Downloading the offline brain (about 1.5 GB).")
    }

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
                if (intent?.action == Intent.ACTION_USER_PRESENT || intent?.action == Intent.ACTION_SCREEN_ON) {
                    if (intent.action == Intent.ACTION_USER_PRESENT && wellbeing != null) wellbeingAnswered(true)
                    markActive()
                }
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
        quran?.takeIf { it.active }?.let { q ->
            when (kind) {
                "next" -> q.next()
                "prev" -> q.prev()
                else -> if (q.playing) q.pause() else q.resume()
            }
            return t("حاضر.", "Done.")
        }
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

    // ------------------------------------------------------------------ "انت وين؟": find the phone

    private var findPlayer: MediaPlayer? = null
    private var findVolume = -1
    private var torchOn = false
    /** Ring two and a half seconds, then two seconds of quiet, so "لقيتك" can be heard between rings. */
    private val findPulse = object : Runnable {
        override fun run() {
            val p = findPlayer ?: return
            try { if (p.isPlaying) { p.pause(); ear?.muted = false } else { ear?.muted = true; p.start() } } catch (_: Exception) {}
            main.postDelayed(this, if (p.isPlaying) 2_500L else 2_000L)
        }
    }
    private val findBlink = object : Runnable {
        override fun run() {
            torchOn = !torchOn
            torch(torchOn)
            main.postDelayed(this, 350)
        }
    }
    private val findTimeout = Runnable { stopFindPhone() }

    /** Ring as loud as the phone can, with the flashlight blinking, for half a minute or until found. */
    private fun findPhone(): String {
        stopFindPhone()
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            findVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { findVolume = -1 }
        val tone = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
        try {
            findPlayer = MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@JarvisService, tone)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            findPlayer = null
            beep(3)
        }
        ear?.muted = true
        main.post(findBlink)
        main.postDelayed(findPulse, 2_500)
        main.postDelayed(findTimeout, 30_000)
        log("بصفّر عشان تلقاني. قول «لقيتك» أو أي كلام عشان أسكت.")
        return ""
    }

    private fun stopFindPhone() {
        main.removeCallbacks(findPulse)
        if (findPlayer != null) ear?.muted = false
        main.removeCallbacks(findBlink)
        main.removeCallbacks(findTimeout)
        if (torchOn) { torchOn = false; torch(false) }
        val p = findPlayer ?: return
        findPlayer = null
        try { p.stop(); p.release() } catch (_: Exception) {}
        if (findVolume >= 0) try {
            (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, findVolume, 0)
        } catch (_: Exception) {}
        findVolume = -1
    }

    private fun torch(on: Boolean) {
        try {
            val cameras = getSystemService(CAMERA_SERVICE) as CameraManager
            cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?.let { cameras.setTorchMode(it, on) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ riddles and the Quran quiz

    private class GameState(val kind: String) {
        var question: Fun.Question? = null
        var tries = 0
        var right = 0
        var asked = 0
        val used = HashSet<String>()
    }

    private var game: GameState? = null
    private val random = java.util.Random()
    private val gameTimeout = Runnable { game?.let { endGame(t("وقفت اللعب عشان ما رديت.", "Game over, no answer.")) } }

    private fun startGame(kind: String): String {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return t("لا يوجد إذن للميكروفون.", "Microphone permission missing.")
        game = GameState(kind)
        if (!listening) startListening(false)
        val intro = if (kind == "quran") t("يلا مسابقة القرآن. جاوب من غير «زول»، وقول «ما عارف» عشان أوريك، و«خلاص» عشان نقيف.",
            "Quran quiz. Answer without the wake word; say: I don't know, or: stop.")
        else t("حجيتك ما بجيتك! جاوب من غير «زول»، وقول «ما عارف» عشان أوريك، و«خلاص» عشان نقيف.",
            "Riddle time. Answer without the wake word; say: I don't know, or: stop.")
        return intro + " " + nextQuestion()
    }

    /** The next question (not one already asked in this game). */
    private fun nextQuestion(): String {
        val g = game ?: return ""
        var q: Fun.Question
        var guard = 0
        do {
            q = if (g.kind == "quran") Fun.quranQuestion(random) else Fun.RIDDLES[random.nextInt(Fun.RIDDLES.size)]
            guard++
        } while (q.ask in g.used && guard < 40)
        if (g.kind != "quran" && g.used.size >= Fun.RIDDLES.size) g.used.clear()
        g.used.add(q.ask)
        g.question = q
        g.tries = 0
        g.asked++
        main.removeCallbacks(gameTimeout)
        main.postDelayed(gameTimeout, 180_000)
        return q.ask
    }

    private fun gameHeard(heard: String) {
        val g = game ?: return
        val q = g.question ?: return
        main.removeCallbacks(gameTimeout)
        main.postDelayed(gameTimeout, 180_000)
        when {
            Fun.stops(heard) -> endGame(null)
            Fun.isRight(q, heard) -> { g.right++; respond(t("صاح! ${q.reveal}. ", "Right! ") + nextQuestion()) }
            Fun.givesUp(heard) || Fun.skips(heard) -> respond(t("الإجابة: ${q.reveal}. ", "The answer: ${q.reveal}. ") + nextQuestion())
            g.tries == 0 -> { g.tries++; respond(t("لا، جرّب تاني.", "No, try again.")) }
            else -> respond(t("لا. الإجابة: ${q.reveal}. ", "No. The answer: ${q.reveal}. ") + nextQuestion())
        }
    }

    private fun endGame(why: String?) {
        val g = game ?: return
        game = null
        main.removeCallbacks(gameTimeout)
        val answered = maxOf(g.asked - 1, g.right)                 // the question asked last was not answered
        respond((why?.let { "$it " } ?: "") + Fun.score(g.right, answered))
    }

    // ------------------------------------------------------------------ translation

    private var translating = false

    private fun translateMode(on: Boolean): String {
        if (on && !aiReady()) return t("الترجمة محتاجة الذكاء السحابي. أضف المفتاح من الإعدادات.", "Translation needs the smart server.")
        translating = on
        if (!on) return t("وقفت الترجمة.", "Translation off.")
        if (!listening) startListening(false)
        return t("وضع الترجمة: أي كلام بالعربي بقولو بالإنجليزي، وأي كلام بالإنجليزي بقولو بالعربي. قول «وقف الترجمة» عشان نقيف.",
            "Translation mode: Arabic is said in English and English in Arabic. Say: stop translating.")
    }

    private fun translateHeard(heard: String) {
        val n = Commands.normalize(heard).trim()
        if (n in setOf("وقف الترجمه", "خلاص الترجمه", "اقفل الترجمه", "خلاص", "stop translating", "stop")) { respond(translateMode(false)); return }
        log("سمعت: $heard")
        translateOnce(heard).takeIf { it.isNotBlank() }?.let { respond(it) }
    }

    /** Arabic into English, anything else into Sudanese Arabic; said aloud in that language. */
    private fun translateOnce(text: String): String {
        if (!aiReady()) return t("الترجمة محتاجة الذكاء السحابي. أضف المفتاح من الإعدادات.", "Translation needs the smart server.")
        val to = Fun.translateTo(text)
        Thread {
            val out = cloudChat("You are a translator. Translate the user's words into $to. Keep the meaning and the tone; " +
                "for Sudanese Arabic use everyday Sudanese words. Output only the translation, nothing else.", text, 300)
                ?.substringAfterLast("</think>")?.trim()?.trim('"', '«', '»')
            main.post {
                if (out.isNullOrBlank()) respond(t("ما قدرت أترجم.", "I could not translate."))
                else { log("${Brand.NAME} ($to): $out"); say(out) }
            }
        }.start()
        return ""
    }

    // ------------------------------------------------------------------ WhatsApp voice messages, written out

    private var lastVoiceNote: String? = null

    /** Where WhatsApp keeps voice messages (each week in its own folder). */
    private fun voiceNoteDirs(): List<java.io.File> {
        val root = android.os.Environment.getExternalStorageDirectory()
        return listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes",
            "WhatsApp/Media/WhatsApp Voice Notes",
        ).map { java.io.File(root, it) }.filter { it.isDirectory }
    }

    fun filesAllowed(): Boolean = if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** The newest voice message, at most [since] old (0: any). */
    private fun newestVoiceNote(since: Long): java.io.File? = voiceNoteDirs()
        .flatMap { d -> d.walkTopDown().maxDepth(2).filter { it.isFile && it.length() > 0 && (it.name.endsWith(".opus") || it.name.endsWith(".ogg")) }.toList() }
        .filter { since == 0L || it.lastModified() >= since }
        .maxByOrNull { it.lastModified() }

    /**
     * A voice message came from [from] (or the person asked for the last one: [from] is null): find the file, which
     * WhatsApp may still be downloading, write it out with Whisper, and read it.
     */
    fun readVoiceNote(from: String?, postedAt: Long, tries: Int = 0): String {
        if (groqKey().isEmpty()) return t("عشان أقرا الرسايل الصوتية محتاج مفتاح Groq (الإعدادات ← الاستماع).", "Reading voice messages needs a Groq key.")
        if (!filesAllowed()) {
            if (from == null) openFilesAccess()
            return t("محتاج إذن الملفات عشان أقرا الرسايل الصوتية. فعّلو لزول وجرّب تاني.", "I need file access to read voice messages.")
        }
        val file = newestVoiceNote(if (postedAt > 0) postedAt - 90_000 else 0L)
            ?.takeIf { from == null || it.absolutePath != lastVoiceNote }      // not the one read a moment ago
        if (file == null) {
            if (from != null && tries < 4) {        // still downloading
                main.postDelayed({ readVoiceNote(from, postedAt, tries + 1).takeIf { it.isNotBlank() }?.let { announce(it, true) } }, 3_000)
                return ""
            }
            return if (from != null) t("جاتك رسالة صوتية من $from، بس ما نزلت في التلفون. افتح المحادثة عشان تنزل.",
                "A voice message from $from has not downloaded yet.")
            else t("ما لقيت رسالة صوتية في التلفون.", "I found no voice message.")
        }
        lastVoiceNote = file.absolutePath
        Thread {
            val text = try {
                groqFile(file.readBytes(), "voice.ogg", "audio/ogg", listenLang())
            } catch (e: Exception) { null }
            main.post {
                val who = from?.let { t("رسالة صوتية من $it", "Voice message from $it") } ?: t("الرسالة الصوتية", "The voice message")
                if (text.isNullOrBlank()) respond(t("$who: ما قدرت أفهم الكلام فيها.", "$who: I could not make out the words."))
                else { announce("$who: «$text»", true); noteLife("com.whatsapp", from ?: "رسالة صوتية", "🎤 $text", System.currentTimeMillis()) }
            }
        }.start()
        return if (from == null) t("لحظة، بقرا الرسالة الصوتية.", "One moment.") else ""
    }

    /** From the notification reader. */
    fun voiceNoteFrom(who: String, postedAt: Long) {
        main.post { readVoiceNote(who, postedAt).takeIf { it.isNotBlank() }?.let { announce(it, true) } }
    }

    fun openFilesAccess() {
        try {
            val i = if (Build.VERSION.SDK_INT >= 30)
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ recording a voice message by voice

    private var recorder: MediaRecorder? = null
    private var recordFile: java.io.File? = null

    /**
     * "سجل لأمي": Zol records after a beep until two seconds of quiet (a minute at most), asks, and sends the
     * recording to that person on WhatsApp.
     */
    private fun recordVoiceMessage(name: String): String {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return t("لا يوجد إذن للميكروفون.", "Microphone permission missing.")
        if (!granted(Manifest.permission.READ_CONTACTS)) return t("لا يوجد إذن لجهات الاتصال.", "Contacts permission missing.")
        val pkg = whatsappPackage() ?: return t("واتساب غير مثبت.", "WhatsApp is not installed.")
        val contact = resolveContact(name) ?: return t("لم أجد \"$name\" في جهات الاتصال.", "I cannot find \"$name\" in contacts.")
        lastContact = contact.first
        if (recorder != null) return t("في تسجيل شغّال هسع.", "Already recording.")
        val wasListening = listening
        if (listening) stopListening(false)
        say(t("اتكلم بعد الصفارة. لمن تسكت شوية برسلها لـ${contact.first}.", "Speak after the beep. When you stop, I send it to ${contact.first}."))
        main.postDelayed({ startRecording(contact, pkg, wasListening) }, 4_500)
        return ""
    }

    @Suppress("DEPRECATION")
    private fun startRecording(contact: Pair<String, String>, pkg: String, wasListening: Boolean) {
        val dir = java.io.File(cacheDir, "voice").apply { mkdirs() }         // shared through the app's FileProvider
        val ogg = Build.VERSION.SDK_INT >= 29
        val file = java.io.File(dir, if (ogg) "zol-voice.ogg" else "zol-voice.m4a").apply { delete() }
        val r = try {
            (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (ogg) { setOutputFormat(MediaRecorder.OutputFormat.OGG); setAudioEncoder(MediaRecorder.AudioEncoder.OPUS) }
                else { setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC) }
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(24000)
                setOutputFile(file.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            respond(t("ما قدرت أسجّل: ${e.message}", "I could not record: ${e.message}"))
            if (wasListening) startListening(false)
            return
        }
        beep(1)
        try { r.start() } catch (e: Exception) {
            r.release(); respond(t("ما قدرت أسجّل: ${e.message}", "I could not record: ${e.message}"))
            if (wasListening) startListening(false); return
        }
        recorder = r
        recordFile = file
        val started = SystemClock.elapsedRealtime()
        var spoke = false
        var quietSince = -1L
        var floor = Int.MAX_VALUE
        val poll = object : Runnable {
            override fun run() {
                val rec = recorder ?: return
                val now = SystemClock.elapsedRealtime()
                val amp = try { rec.maxAmplitude } catch (_: Exception) { 0 }
                if (now - started < 600) floor = minOf(floor, amp)           // the room, before speaking
                val loud = amp > maxOf(1_200, (if (floor == Int.MAX_VALUE) 0 else floor) * 3)
                if (loud) { spoke = true; quietSince = -1L } else if (quietSince < 0) quietSince = now
                val done = when {
                    !spoke && now - started > 8_000 -> "none"
                    spoke && quietSince > 0 && now - quietSince > 2_000 -> "ok"
                    now - started > 60_000 -> "ok"
                    else -> null
                }
                if (done == null) { main.postDelayed(this, 100); return }
                finishRecording(done == "ok", now - started, contact, pkg, wasListening)
            }
        }
        main.postDelayed(poll, 100)
    }

    private fun finishRecording(ok: Boolean, ms: Long, contact: Pair<String, String>, pkg: String, wasListening: Boolean) {
        val r = recorder
        recorder = null
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
        val file = recordFile
        beep(2)
        startListening(false)                                    // to hear the yes
        if (!ok || file == null || file.length() < 500) {
            file?.delete()
            respond(t("ما سمعت حاجة، ما سجّلت.", "I heard nothing; nothing recorded."))
            if (!wasListening) main.postDelayed({ stopListening(false) }, 3_000)
            return
        }
        val secs = (ms / 1000).coerceAtLeast(1)
        respond(ask(t("سجّلت $secs ثانية. أرسلها لـ${contact.first}؟ نعم أو لا.", "Recorded $secs seconds. Send to ${contact.first}? Yes or no."),
            onNo = { file.delete(); t("ما أرسلتها.", "Not sent.") }) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                startActivity(Intent(Intent.ACTION_SEND)
                    .setType(if (file.name.endsWith(".ogg")) "audio/ogg" else "audio/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra("jid", waNumber(contact.second) + "@s.whatsapp.net")      // straight into that chat
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                pressSendSoon(4)
                t("برسلها في واتساب.", "Sending on WhatsApp.")
            } catch (e: Exception) {
                t("ما قدرت أرسلها: ${e.message}", "Could not send it: ${e.message}")
            }
        })
    }

    // ------------------------------------------------------------------ habits

    private var fromHabit = false
    private fun localMinute() = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 60_000

    private fun noteHabit(text: String) {
        val list = Habits.add(Habits.decode(prefs.getString("habits", "").orEmpty()), Habits.Entry(text.trim(), localMinute()))
        prefs.edit().putString("habits", Habits.encode(list)).apply()
    }

    private fun counts(key: String): MutableMap<String, Long> = prefs.getString(key, "").orEmpty().lines()
        .mapNotNull { l -> l.substringAfterLast('|').toLongOrNull()?.let { l.substringBeforeLast('|') to it } }.toMap().toMutableMap()

    private fun saveCounts(key: String, m: Map<String, Long>) =
        prefs.edit().putString(key, m.entries.joinToString("\n") { "${it.key}|${it.value}" }).apply()

    /** Every five minutes: is it the time of something the person does every day? Offer it once. */
    private val habitTick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 5 * 60_000L)
            if (!prefs.getBoolean("pro_on", true) || !prefs.getBoolean("habits_on", true)) return
            if (tts?.isSpeaking == true || game != null || beads != null || translating || learning != null || sleepEnd != 0L) return
            if (pending != null && SystemClock.elapsedRealtime() < pendingUntil) return
            val now = localMinute()
            val offered = counts("habits_offered")
            val refused = counts("habits_refused").mapValues { it.value.toInt() }
            val h = Habits.due(Habits.decode(prefs.getString("habits", "").orEmpty()), now, offered, refused) ?: return
            val key = Commands.normalize(h.text).trim()
            offered[key] = now / (24 * 60)
            saveCounts("habits_offered", offered)
            respond(ask(t("عادةً في الوقت ده بتقول «${h.text}». أعملها؟ نعم أو لا.", "Around now you usually say \"${h.text}\". Do it?"),
                onNo = {
                    val r = counts("habits_refused"); r[key] = (r[key] ?: 0) + 1; saveCounts("habits_refused", r)
                    t("تمام.", "OK.")
                }) {
                fromHabit = true
                try { handleText(h.text) } finally { fromHabit = false }
                ""
            })
        }
    }

    // ------------------------------------------------------------------ teaching Zol by showing it

    private var learning: MutableList<Macros.Step>? = null
    private var learningName = ""
    private var lastClickAt = 0L
    private val learnTimeout = Runnable { if (learning != null) respond(finishLearning("")) }

    fun macros(): LinkedHashMap<String, List<Macros.Step>> = Macros.decode(prefs.getString("macros", "").orEmpty())

    private fun saveMacros(m: Map<String, List<Macros.Step>>) {
        prefs.edit().putString("macros", Macros.encode(m)).apply()
        Commands.macroNames = m.keys
    }

    private fun startLearning(name: String): String {
        learning = ArrayList()
        learningName = name
        // already inside an app: the routine starts by opening it
        rootInActiveWindow?.packageName?.toString()?.let { pkg ->
            if (pkg != packageName && pkg != homePackage() && packageManager.getLaunchIntentForPackage(pkg) != null)
                learning?.add(Macros.Step("open", pkg))
        }
        main.removeCallbacks(learnTimeout)
        main.postDelayed(learnTimeout, 10 * 60_000L)
        return t("عاين ليك. اعمل الحاجة قدامي: اضغط وأكتب، أو قول الأوامر. ولمن تخلص قول: زول سمّيها" +
            (if (name.isEmpty()) " وبعدها الاسم." else "، أو زول خلصت."),
            "Watching. Do it once (tap, type, or say commands); then say: name it, and the name.")
    }

    private fun finishLearning(name: String): String {
        val steps = learning ?: return t("ما كنت بتعلم حاجة. قول «زول اتعلم» عشان أبدأ.", "I was not learning. Say: learn this.")
        main.removeCallbacks(learnTimeout)
        val n = name.ifBlank { learningName }.trim()
        if (n.isEmpty()) {                               // no name yet: the next sentence is the name
            awakeUntil = SystemClock.elapsedRealtime() + 15_000
            return ask(t("سمّيها شنو؟ قول: زول سمّيها، وبعدها الاسم.", "What should I call it?"), onNo = { learning = null; t("ألغيت.", "Cancelled.") }) {
                t("قول: زول سمّيها، وبعدها الاسم.", "Say: name it, and the name.")
            }
        }
        learning = null
        if (steps.isEmpty()) return t("ما شفتك عملت حاجة، فما حفظت.", "I saw nothing, so nothing was kept.")
        val all = macros()
        all[n] = steps
        saveMacros(all)
        return t("اتعلمت «$n»: ", "Learned \"$n\": ") + Macros.describe(steps) { appName(it) } +
            t(". قول «زول $n» عشان أعملها.", ". Say it to run it.")
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** While learning: what the person taps, types, and which app comes up. */
    private fun watch(event: AccessibilityEvent) {
        val steps = learning ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui") return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                val r = Rect().also { node.getBoundsInScreen(it) }
                var text = node.text?.toString().orEmpty()
                if (text.isEmpty()) for (i in 0 until node.childCount) {
                    val c = node.getChild(i)?.text?.toString().orEmpty()
                    if (c.isNotEmpty()) { text = c; break }
                }
                Macros.add(steps, Macros.Step("click", pkg, text.take(80), node.contentDescription?.toString().orEmpty().take(80),
                    node.viewIdResourceName.orEmpty(), r.centerX(), r.centerY()))
                lastClickAt = SystemClock.elapsedRealtime()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = event.source
                if (node?.isPassword == true) return                 // passwords are never kept
                val text = event.text?.joinToString(" ")?.trim().orEmpty()
                if (text.isNotEmpty()) Macros.add(steps, Macros.Step("type", pkg, text.take(500), id = node?.viewIdResourceName.orEmpty()))
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // an app that came up from the home screen (not because of a tap inside the last app)
                val launcher = homePackage()
                if (pkg == launcher) return
                val last = steps.lastOrNull()
                if (last?.kind == "click" && last.pkg != launcher && SystemClock.elapsedRealtime() - lastClickAt < 2_000) return
                if (packageManager.getLaunchIntentForPackage(pkg) == null) return
                if (last?.kind == "click" && last.pkg == launcher) steps.removeAt(steps.size - 1)   // the icon tap is the opening
                Macros.add(steps, Macros.Step("open", pkg))
            }
        }
    }

    private fun homePackage(): String? = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName

    /** Do a taught routine again, step by step, waiting for each screen. */
    private fun runMacro(name: String): String {
        val steps = macros()[name] ?: return t("ما لقيت «$name».", "No routine called $name.")
        if (steps.isEmpty()) return t("«$name» فاضية.", "Empty.")
        runStep(name, steps, 0, 0)
        return t("بعمل «$name».", "Doing $name.")
    }

    private fun runStep(name: String, steps: List<Macros.Step>, i: Int, tries: Int) {
        if (i >= steps.size) { respond(t("خلصت «$name».", "Done.")); return }
        val s = steps[i]
        val next = { main.postDelayed({ runStep(name, steps, i + 1, 0) }, if (s.kind == "open") 2_500L else 1_200L) }
        when (s.kind) {
            "open" -> {
                val launch = packageManager.getLaunchIntentForPackage(s.pkg)
                if (launch == null) { respond(t("التطبيق ${s.pkg} ما موجود.", "App not installed.")); return }
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                next()
            }
            "say" -> { handleText(s.text); main.postDelayed({ runStep(name, steps, i + 1, 0) }, 2_500) }
            "type" -> {
                val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: screenNodes().firstOrNull { it.isEditable && it.isVisibleToUser && (s.id.isEmpty() || it.viewIdResourceName == s.id) }
                    ?: screenNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
                if (field == null) {
                    if (tries < 5) { main.postDelayed({ runStep(name, steps, i, tries + 1) }, 700); return }
                    respond(t("وقفت: ما لقيت مكان الكتابة.", "Stopped: no text field.")); return
                }
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s.text) }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                next()
            }
            else -> {
                val best = screenNodes().filter { it.isVisibleToUser }
                    .map { it to Macros.score(s, it.viewIdResourceName, it.text?.toString(), it.contentDescription?.toString()) }
                    .maxByOrNull { it.second }
                if (best != null && best.second >= Macros.ENOUGH) { press(best.first); next(); return }
                if (tries < 6) { main.postDelayed({ runStep(name, steps, i, tries + 1) }, 700); return }
                if (s.x >= 0 && s.text.isEmpty() && s.desc.isEmpty()) {     // a button with no words: where it was
                    gesture(Path().apply { moveTo(s.x.toFloat(), s.y.toFloat()); lineTo(s.x.toFloat(), s.y.toFloat()) }, 60)
                    next(); return
                }
                respond(t("وقفت في الخطوة ${i + 1}: ما لقيت «${s.text.ifEmpty { s.desc }}».", "Stopped at step ${i + 1}."))
            }
        }
    }

    // ------------------------------------------------------------------ the phone as the computer's mouse

    private var pc: PcLink? = null

    private fun pcCommand(c: Cmd.Pc): String {
        val link = pc
        return when (c.what) {
            "on" -> startPc()
            "off" -> { head?.remote = null; link?.stop(); pc = null; t("فصلت الكمبيوتر.", "Disconnected.") }
            else -> {
                if (link?.connected != true) return t("الكمبيوتر ما متوصل. قول «زول وصّل الكمبيوتر».", "No computer connected.")
                when (c.what) {
                    "type" -> link.type(c.text)
                    "click" -> link.click(1)
                    "right" -> link.click(2)
                    "enter" -> link.key(0, Hid.ENTER)
                    "back" -> link.key(0, Hid.BACKSPACE)
                    "esc" -> link.key(0, Hid.ESCAPE)
                }
                t("حاضر.", "Done.")
            }
        }
    }

    fun startPc(): String {
        if (Build.VERSION.SDK_INT < 28) return t("الميزة دي محتاجة أندرويد 9 أو أحدث.", "Needs Android 9 or newer.")
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
            return t("محتاج إذن البلوتوث: افتح ✨ الميزات ← الكمبيوتر بالراس.", "Bluetooth permission needed: Features → computer.")
        val link = pc ?: PcLink(this) { st -> main.post { pcStatus(st) } }.also { pc = it }
        if (!link.start()) { pc = null; return t("تلفونك ما بيقدر يبقى ماوس بلوتوث.", "This phone cannot be a Bluetooth mouse.") }
        return t("بجهّز الماوس والكيبورد…", "Getting the mouse ready…")
    }

    private fun pcStatus(st: String) {
        when {
            st == "ready" -> if (pc?.connected != true) {
                try {
                    startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
                respond(t("من الكمبيوتر: الإعدادات ← Bluetooth ← أضف جهاز، واختار تلفونك. المرة الجاية بيتوصل براهو.",
                    "On the computer: Settings → Bluetooth → Add device, and choose this phone."))
            }
            st.startsWith("connected:") -> {
                if (head?.running != true) startHead()
                attachPcHead()
                respond(t("اتوصلت بـ${st.substringAfter(':')}. لف راسك عشان الماوس يمشي، وارجع راسك للنص عشان يقيف، " +
                    "واسكت عليهو شوية عشان يضغط. وقول «زول اكتب في الكمبيوتر …» عشان تكتب.",
                    "Connected. Turn your head to move the mouse; hold still to click."))
            }
            st == "disconnected" -> { head?.remote = null; log("الكمبيوتر اتفصل.") }
            st == "unsupported" -> { pc?.stop(); pc = null; respond(t("الشركة المصنعة قافلة الميزة دي في تلفونك (ماوس البلوتوث).",
                "The maker turned this off on this phone.")) }
            st == "bluetooth_off" -> {
                try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                pc = null
                respond(t("شغّل البلوتوث وقول تاني «زول وصّل الكمبيوتر».", "Turn Bluetooth on, then try again."))
            }
        }
    }

    /** The head moves the computer's mouse like a joystick, and holding still clicks. */
    private fun attachPcHead() {
        val dm = resources.displayMetrics
        head?.remote = remote@{ f ->
            val link = pc
            if (link?.connected != true) return@remote false
            if (!f.face) return@remote true
            val vx = (f.x - dm.widthPixels / 2f) / (dm.widthPixels / 2f)
            val vy = (f.y - dm.heightPixels / 2f) / (dm.heightPixels / 2f)
            link.move(Hid.speed(vx), Hid.speed(vy))
            if (f.tap) { link.click(1); beep(2) }
            true
        }
    }

    // ------------------------------------------------------------------ stop everything

    /** What is running now that the person might want to stop (for the stop button and its label). */
    fun running(): List<String> = buildList {
        if (game != null) add("اللعب")
        if (translating) add("الترجمة")
        if (beads != null) add("المسبحة")
        if (lesson != null) add("درس الإنجليزي")
        if (learning != null) add("التعليم")
        if (findPlayer != null) add("الصفارة")
        if (sleepEnd != 0L) add("مؤقت النوم")
        if (dimView != null) add("التغميق")
        if (awakeView != null) add("الشاشة شغالة")
        if (eyeGestures != null) add("التحكم بالعيون")
        if (pc != null) add("الكمبيوتر")
        if (head?.running == true && eyeGestures == null) add("التحكم بالرأس")
        if (switches?.running == true && !eyesStartedSwitches) add("التنقل بالمفاتيح")
        if (quran?.playing == true) add("القرآن")
        if (board?.shown == true) add("لوحة الكلام")
        if (recorder != null) add("التسجيل")
        if (captionListeners.isNotEmpty()) add("الكلام مكتوب")
        if (agentGoal.isNotEmpty()) add("المساعد")
    }

    /** Every feature stops and every screen of Zol's over the phone goes away; listening stays as it was. */
    fun stopEverything(): String {
        val was = running()
        game = null; main.removeCallbacks(gameTimeout)
        translating = false
        if (beads != null) { beads = null; main.removeCallbacks(beadsTimeout); hideBeads() }
        lesson = null; main.removeCallbacks(lessonTimeout)
        learning = null; main.removeCallbacks(learnTimeout)
        stopFindPhone()
        cancelSleep(false)
        dimScreen(false)
        keepScreenOn(false)
        if (eyeGestures != null) stopEyes()
        if (pc != null) { head?.remote = null; pc?.stop(); pc = null }
        if (head?.running == true) stopHead()
        if (switches?.running == true) { switches?.stop(); switches = null; prefs.edit().putString("switch_mode", "off").apply() }
        quran?.stop()
        clipPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        clipPlayer = null
        if (board?.shown == true) hideBoard()
        recorder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        recorder = null; recordFile?.delete()
        if (agentGoal.isNotEmpty()) stopAgent(t("وقفت.", "Stopped."))
        pending = null
        tts?.stop()
        sendBroadcast(Intent(ACTION_CLOSE_SCREENS).setPackage(packageName))     // the captions screen and the like
        refreshStopButton()
        return if (was.isEmpty()) t("ما في حاجة شغالة.", "Nothing was running.")
        else t("وقفت كل حاجة: ", "Stopped: ") + was.joinToString("، ") + "."
    }

    private var stopButton: TextView? = null
    private val stopButtonTick = object : Runnable {
        override fun run() { refreshStopButton(); main.postDelayed(this, 1_500) }
    }

    /**
     * A red button over the screen while any feature runs (a game, the beads, head or eye control, the timer…):
     * a tap stops them all, so nothing stays stuck on the screen. It goes away by itself when nothing runs.
     */
    fun refreshStopButton() {
        val names = running()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (names.isEmpty() || !prefs.getBoolean("stop_button", true)) {
            stopButton?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            stopButton = null
            return
        }
        val label = "⏹ وقّف " + (if (names.size == 1) names[0] else "الكل (${names.size})")
        stopButton?.let { it.text = label; return }
        val d = resources.displayMetrics.density
        val v = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(Color.argb(235, 190, 30, 30)) }
            contentDescription = "وقّف كل الميزات الشغالة"
            setOnClickListener { respond(stopEverything()) }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = (12 * d).toInt()
        lp.y = (90 * d).toInt()
        try { wm.addView(v, lp); stopButton = v } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ control with the eyes

    private var eyeGestures: EyeGestures? = null
    private var eyesStartedSwitches = false

    /**
     * For someone who can move neither hands nor head: the switch scanning goes over the screen, and the eyes
     * are the two switches: a wink of the right eye moves on, a wink of the left eye chooses, closing both eyes
     * for a moment goes back.
     */
    private fun startEyes(): String {
        if (!granted(Manifest.permission.CAMERA)) return t("محتاج إذن الكاميرا. افتح زول ← الإعدادات ← التحكم بالرأس.", "Camera permission needed.")
        if (switches?.running != true) {
            val control = switches ?: SwitchControl(this, { switchItems() }, { switchStrip(it) }).also { switches = it }
            control.start(0L)
            eyesStartedSwitches = true
        }
        if (head?.running != true) startHead()
        val g = EyeGestures().also { eyeGestures = it }
        head?.hideCursor = true
        head?.remote = { true }                          // the head does not move a pointer here
        head?.eyes = { l, r ->
            when (g.feed(l, r, SystemClock.elapsedRealtime())) {
                EyeGestures.Event.WINK_RIGHT -> switches?.step()
                EyeGestures.Event.WINK_LEFT -> { switches?.select(); beep(2) }
                EyeGestures.Event.LONG_CLOSE -> { performGlobalAction(GLOBAL_ACTION_BACK); beep(1) }
                null -> {}
            }
        }
        return t("التحكم بالعيون شغّال. غمّز بعينك اليمين عشان تمشي للجاي، وبعينك الشمال عشان تختار، وغمّض عيونك التنين شوية عشان ترجع. " +
            "لو الاتجاهات طلعت بالعكس، بس اتعوّد عليها كدا.",
            "Eye control on: right wink moves on, left wink chooses, both eyes closed goes back.")
    }

    private fun stopEyes(): String {
        eyeGestures = null
        head?.eyes = null
        head?.remote = null
        head?.hideCursor = false
        if (eyesStartedSwitches) { eyesStartedSwitches = false; if (prefs.getString("switch_mode", "off") == "off") { switches?.stop(); switches = null } }
        stopHead()
        return t("وقفت التحكم بالعيون.", "Eye control off.")
    }

    // ------------------------------------------------------------------ the English teacher

    private class LessonState(var lesson: Int, var phrase: Int) { var tries = 0; var good = 0 }
    private var lesson: LessonState? = null
    private val lessonTimeout = Runnable { if (lesson != null) { lesson = null; respond(t("وقفت الدرس عشان ما رديت. نكمّل بعدين.", "Lesson paused.")) } }

    private fun startEnglish(): String {
        if (groqKey().isEmpty() || !cloudStt()) return t("الدرس محتاج زول يسمع إنجليزي عن طريق Groq: شغّل «اسمع عن طريق Groq Whisper» من الإعدادات.",
            "The lesson needs Groq listening.")
        val l = prefs.getInt("eng_lesson", 0).coerceIn(0, Teacher.LESSONS.size - 1)
        lesson = LessonState(l, prefs.getInt("eng_phrase", 0).coerceIn(0, Teacher.LESSONS[l].phrases.size - 1))
        if (!listening) startListening(false)
        say(t("يلا نتعلم إنجليزي. درس ${Teacher.LESSONS[l].title}. بقول ليك الجملة، وإنت قولها بعدي. قول stop عشان نقيف، و next عشان الجاية.",
            "English lesson. Repeat after me; say stop or next."))
        main.postDelayed({ sayPhrase() }, 7_000)
        return ""
    }

    private fun sayPhrase() {
        val s = lesson ?: return
        val p = Teacher.LESSONS[s.lesson].phrases[s.phrase]
        main.removeCallbacks(lessonTimeout)
        main.postDelayed(lessonTimeout, 120_000)
        say(p.arabic)
        say(p.english, add = true)
    }

    private fun englishHeard(heard: String) {
        val s = lesson ?: return
        val p = Teacher.LESSONS[s.lesson].phrases[s.phrase]
        log("سمعت: $heard")
        if (Fun.stops(heard) || Teacher.control(heard) == "stop") {
            lesson = null
            main.removeCallbacks(lessonTimeout)
            respond(t("كويس، وقفنا. نكمّل من نفس المكان المرة الجاية.", "Stopped; we carry on next time."))
            return
        }
        when (Teacher.control(heard)) {
            "again" -> { sayPhrase(); return }
            "next" -> { nextPhrase(false); return }
        }
        val g = Teacher.grade(p.english, heard)
        when {
            g.score >= 0.85 -> { s.good++; respond(t("ممتاز! ", "Excellent! ")); main.postDelayed({ nextPhrase(true) }, 1_500) }
            g.score >= 0.5 && s.tries == 0 -> {
                s.tries++
                say(t("قريبة! الكلمة الناقصة: ", "Close! Missing: "))
                say(g.missing.joinToString(" "), add = true)
                say(t("قولها تاني:", "Again:"), add = true)
                say(p.english, add = true)
            }
            s.tries == 0 -> { s.tries++; say(t("جرّب تاني، اسمعها:", "Try again, listen:")); say(p.english, add = true) }
            else -> { respond(t("ما مشكلة، نمشي للجاية.", "No problem, the next one.")); main.postDelayed({ nextPhrase(false) }, 1_500) }
        }
    }

    private fun nextPhrase(wasGood: Boolean) {
        val s = lesson ?: return
        s.tries = 0
        s.phrase++
        if (s.phrase >= Teacher.LESSONS[s.lesson].phrases.size) {
            val done = Teacher.LESSONS[s.lesson].title
            val good = s.good
            s.lesson = (s.lesson + 1) % Teacher.LESSONS.size
            s.phrase = 0
            s.good = 0
            prefs.edit().putInt("eng_lesson", s.lesson).putInt("eng_phrase", 0).apply()
            lesson = null
            respond(t("خلصنا درس $done! قلت $good جملة صاح. الدرس الجاي: ${Teacher.LESSONS[s.lesson].title}. قول «زول علمني انجليزي» لمن تكون جاهز.",
                "Lesson done! Next: ${Teacher.LESSONS[s.lesson].title}."))
            return
        }
        prefs.edit().putInt("eng_lesson", s.lesson).putInt("eng_phrase", s.phrase).apply()
        sayPhrase()
    }

    // ------------------------------------------------------------------ dialect packs

    private fun myPack(): String? {
        val words = Commands.parseDict(prefs.getString("dialect", "").orEmpty())
        val phrases = Commands.parsePhrases(prefs.getString("phrases", "").orEmpty())
        if (words.isEmpty() && phrases.isEmpty()) return null
        return Dialects.format(prefs.getString("dialect_name", null) ?: "لهجتي", words, phrases)
    }

    /** What the person taught Zol, as text to send: to someone (WhatsApp...), or to the community list. */
    private fun shareDialect(community: Boolean): String {
        val pack = myPack() ?: return t("لسه ما درّبتني على كلامك. قول «زول دربني» أو «زول علمني».", "Nothing taught yet.")
        return try {
            if (community) {
                val body = "دي حزمة لهجة من زول. ممكن تضيفوها للهجات المجتمع؟\n\n```\n${pack.take(6000)}\n```"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Dialects.CONTRIBUTE + "?title=" + Uri.encode("لهجة جديدة") +
                    "&body=" + Uri.encode(body))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                t("فتحت صفحة المجتمع في GitHub والحزمة مكتوبة فيها. اضغط Submit عشان ترسلها.", "Opened GitHub with your pack; press Submit.")
            } else {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pack),
                    "شارك لهجتك").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                t("اختار وين ترسلها. البيستلمها يشاركها مع زول بتاعو عشان تتضاف.", "Choose where to send it.")
            }
        } catch (e: Exception) { t("ما قدرت: ${e.message}", "Could not share.") }
    }

    /** A pack someone sent (shared to Zol), or a community one: added to what Zol knows, the person's own words kept. */
    fun importPack(text: String): String {
        val pack = Dialects.parse(text) ?: return t("دي ما حزمة لهجة من زول.", "That is not a Zol dialect pack.")
        val (words, nw) = Dialects.merge(prefs.getString("dialect", "").orEmpty(), pack.words) { Commands.normalize(it).replace(" ", "") }
        val (phrases, np) = Dialects.merge(prefs.getString("phrases", "").orEmpty(), pack.phrases) { Commands.normalize(it).trim() }
        prefs.edit().putString("dialect", words).putString("phrases", phrases).apply()
        Commands.userDict = Commands.parseDict(words)
        Commands.userPhrases = Commands.parsePhrases(phrases)
        return if (nw + np == 0) t("«${pack.name}»: كل الفيها عارفو من قبل.", "Nothing new in ${pack.name}.")
        else t("ضفت «${pack.name}»: $nw كلمة و$np جملة.", "Added ${pack.name}: $nw words, $np sentences.")
    }

    /** The community list: read out ([want] null), or the pack [want] downloaded and added. */
    fun communityDialects(want: String?, done: ((List<Dialects.Entry>) -> Unit)? = null) {
        Thread {
            val list = try { Dialects.parseIndex(fetchText(Dialects.BASE + "index.txt")) } catch (_: Exception) { null }
            main.post {
                if (list == null) { respond(t("ما قدرت أوصل للهجات المجتمع. اتأكد من النت.", "Could not reach the community list.")); return@post }
                if (done != null) { done(list); return@post }
                if (want == null) {
                    respond(if (list.isEmpty()) t("لسه ما في لهجات. كن أول زول: قول «زول رسّل لهجتي للمجتمع».", "None yet.")
                        else t("لهجات المجتمع: ", "Community dialects: ") + list.joinToString("، ") { it.name } +
                            t(". قول «زول نزّل لهجة» والاسم.", ". Say: install dialect, and the name."))
                    return@post
                }
                val e = Dialects.find(list, want) ?: run { respond(t("ما لقيت لهجة «$want».", "No dialect called $want.")); return@post }
                installCommunity(e)
            }
        }.start()
    }

    fun installCommunity(e: Dialects.Entry) {
        Thread {
            val text = try { fetchText(Dialects.BASE + e.id + ".txt") } catch (_: Exception) { null }
            main.post { respond(if (text == null) t("ما قدرت أنزّل «${e.name}».", "Could not download ${e.name}.") else importPack(text)) }
        }.start()
    }

    private fun fetchText(address: String): String {
        val con = URL(address).openConnection() as HttpURLConnection
        con.connectTimeout = 10_000
        con.readTimeout = 15_000
        if (con.responseCode !in 200..299) { con.disconnect(); throw java.io.IOException("${con.responseCode}") }
        return con.inputStream.bufferedReader().use { it.readText() }.also { con.disconnect() }
    }

    // ------------------------------------------------------------------ live captions

    /** The sentences written so far (the screen shows them again when it is opened). */
    val captions = ArrayList<String>()
    private val captionListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    private var captionsStartedListening = false

    /** The captions screen is open: every sentence heard goes there, and nothing is done with it. */
    fun startCaptions(l: (String) -> Unit) {
        captionListeners.add(l)
        if (!listening && granted(Manifest.permission.RECORD_AUDIO)) { captionsStartedListening = true; startListening(false) }
    }

    fun stopCaptions(l: (String) -> Unit) {
        captionListeners.remove(l)
        if (captionListeners.isEmpty() && captionsStartedListening) { captionsStartedListening = false; stopListening(false) }
    }

    private fun caption(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        captions.add(line)
        while (captions.size > 100) captions.removeAt(0)
        captionListeners.forEach { it(line) }
    }

    // ------------------------------------------------------------------ the prayer beads

    private class Beads { var total = 0; val each = LinkedHashMap<String, Int>() }
    private var beads: Beads? = null
    private var beadsView: TextView? = null
    private val beadsTimeout = Runnable { if (beads != null) respond(endTasbeeh()) }

    /** Count every "سبحان الله", "الحمد لله"… heard; a big counter on screen (a tap adds one). */
    private fun startTasbeeh(): String {
        if (beads != null) return t("المسبحة شغّالة.", "Already counting.")
        beads = Beads()
        if (!listening && granted(Manifest.permission.RECORD_AUDIO)) startListening(false)
        showBeads()
        main.postDelayed(beadsTimeout, 30 * 60_000L)
        return t("المسبحة جاهزة. سبّح من غير «زول» وأنا بعد. قول «خلاص» عشان أقول ليك العدد.",
            "Counting. Say the words without the wake word; say: stop, for the total.")
    }

    private fun tasbeehHeard(heard: String) {
        val b = beads ?: return
        main.removeCallbacks(beadsTimeout)
        main.postDelayed(beadsTimeout, 30 * 60_000L)
        val n = Commands.normalize(heard).trim()
        if (n in setOf("خلاص", "خلاص التسبيح", "وقف التسبيح", "كفايه", "انتهيت", "stop")) { respond(endTasbeeh()); return }
        if (n in setOf("صفر", "صفر العداد", "من جديد", "ابدا من جديد", "reset")) { b.total = 0; b.each.clear(); paintBeads(); beep(1); return }
        val counted = Fun.countDhikr(heard)
        if (counted.isEmpty()) { resume(200); return }
        val before = b.total
        for ((k, v) in counted) { b.each[k] = (b.each[k] ?: 0) + v; b.total += v }
        paintBeads()
        when {
            b.total / 100 > before / 100 -> respond(t("${b.total}. ما شاء الله.", "${b.total}."))
            b.total / 33 > before / 33 -> { beep(2); resume(200) }
            else -> resume(200)
        }
    }

    private fun addBead() {
        val b = beads ?: return
        val before = b.total
        b.total++
        paintBeads()
        if (b.total / 33 > before / 33) beep(2)
    }

    private fun endTasbeeh(): String {
        val b = beads ?: return t("المسبحة ما شغّالة.", "Not counting.")
        beads = null
        main.removeCallbacks(beadsTimeout)
        hideBeads()
        if (b.total == 0) return t("قفلت المسبحة.", "Stopped.")
        val parts = b.each.entries.joinToString("، ") { "${it.key} ${it.value}" }
        return t("سبّحت ${b.total} مرة" + (if (parts.isNotEmpty()) ": $parts." else ".") + " تقبّل الله.", "Total: ${b.total}.")
    }

    private fun showBeads() {
        hideBeads()
        val d = resources.displayMetrics.density
        val v = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * d; setColor(Color.argb(215, 20, 70, 50)) }
            contentDescription = "عداد التسبيح: اضغط عشان تزيد واحد، واضغط طويل عشان تقفل"
            setOnClickListener { addBead() }
            setOnLongClickListener { respond(endTasbeeh()); true }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = (70 * d).toInt()
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp); beadsView = v; paintBeads() } catch (_: Exception) {}
    }

    private fun paintBeads() {
        val b = beads ?: return
        beadsView?.text = "📿 ${b.total}"
    }

    private fun hideBeads() {
        beadsView?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        beadsView = null
    }

    // ------------------------------------------------------------------ "زول نوّمني"

    private var sleepVolume = -1
    private var sleepEnd = 0L
    private val sleepTick = object : Runnable {
        override fun run() {
            val left = sleepEnd - SystemClock.elapsedRealtime()
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            if (left <= 0) { cancelSleep(false); return }
            // the last three minutes: one step quieter every so often, down to the lowest
            if (left < 180_000 && sleepVolume > 0) {
                val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val target = (sleepVolume * left / 180_000.0).toInt().coerceAtLeast(1)
                if (now > target) try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) } catch (_: Exception) {}
            }
            main.postDelayed(this, 10_000)
        }
    }

    /** Surat al-Mulk and on, quietly; the sound fades over the last minutes and stops after [minutes]. */
    private fun sleepWithQuran(minutes: Int): String {
        cancelSleep(false)
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        sleepVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val calm = (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.35).toInt().coerceAtLeast(1)
        if (sleepVolume > calm) try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, calm, 0) } catch (_: Exception) {}
        val restoreTo = sleepVolume
        sleepVolume = minOf(sleepVolume, calm)
        sleepRestore = restoreTo
        sleepEnd = SystemClock.elapsedRealtime() + minutes * 60_000L
        main.postDelayed(sleepTick, 10_000)
        playQuran(listOf(67), null)
        if (listening) stopListening(false)                       // the night is quiet; the mic rests too
        val span = spanText(minutes)
        return t("تصبح على خير. شغّلت سورة الملك بصوت هادي، وبقيف بعد $span.", "Good night. Quran for $minutes minutes.")
    }

    private var sleepRestore = -1

    private fun cancelSleep(byVoice: Boolean): String {
        main.removeCallbacks(sleepTick)
        val was = sleepEnd != 0L
        sleepEnd = 0L
        if (was) {
            quran?.stop()
            if (sleepRestore >= 0) main.postDelayed({
                try { (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, sleepRestore, 0) } catch (_: Exception) {}
                sleepRestore = -1
            }, 1_500)
        }
        sleepVolume = -1
        return if (byVoice) (if (was) t("صباح الخير. وقّفت القرآن.", "Stopped.") else t("ما في مؤقت نوم.", "No sleep timer.")) else ""
    }

    // ------------------------------------------------------------------ the screen by voice

    /** Brightness, rotation: Android lets an app change them only after the person allows it once. */
    private fun canWriteSettings(): Boolean {
        if (android.provider.Settings.System.canWrite(this)) return true
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        return false
    }

    private val BRIGHT_STEPS = intArrayOf(3, 15, 35, 65, 105, 155, 205, 255)

    private fun brightness(how: String): String {
        if (!canWriteSettings()) return t("اسمح لزول يغيّر إعدادات النظام (فتحتها ليك)، وبعدها قول تاني.", "Allow Zol to change system settings, then say it again.")
        val cr = contentResolver
        try {
            if (how == "auto") {
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return t("الإضاءة بقت تلقائية.", "Brightness is automatic.")
            }
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val now = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
            val at = BRIGHT_STEPS.indices.minByOrNull { Math.abs(BRIGHT_STEPS[it] - now) } ?: 3
            val next = when (how) {
                "max" -> BRIGHT_STEPS.last()
                "min" -> BRIGHT_STEPS.first()
                "up" -> BRIGHT_STEPS[minOf(at + 1, BRIGHT_STEPS.size - 1)]
                else -> BRIGHT_STEPS[maxOf(at - 1, 0)]
            }
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, next)
            if (next == now && how in setOf("up", "max")) return t("الإضاءة على الآخر.", "Brightness is at the top.")
            if (next == now && how in setOf("down", "min")) return t("الإضاءة في الأقل. قول: غمّق الشاشة، لو عايزها أضلم.", "Lowest already. Say: dim the screen.")
            return t("حاضر.", "Done.")
        } catch (e: Exception) {
            return t("ما قدرت أغيّر الإضاءة: ${e.message}", "Could not change the brightness.")
        }
    }

    private var dimView: View? = null

    /** A dark see-through layer over everything, darker than the lowest brightness; touches pass through. */
    private fun dimScreen(on: Boolean): String {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dimView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dimView = null
        if (!on) return t("رجّعت الشاشة عادي.", "Filter off.")
        val v = View(this).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        return try { wm.addView(v, lp); dimView = v; t("غمّقت الشاشة. قول «رجّع الشاشة عادي» عشان أشيلو.", "Screen dimmed.") }
        catch (e: Exception) { t("ما قدرت: ${e.message}", "Could not dim the screen.") }
    }

    private fun autoRotate(on: Boolean): String {
        if (!canWriteSettings()) return t("اسمح لزول يغيّر إعدادات النظام (فتحتها ليك)، وبعدها قول تاني.", "Allow Zol to change system settings, then say it again.")
        return try {
            android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            if (on) t("الشاشة بتلف مع التلفون.", "Auto-rotate on.") else t("ثبّت الشاشة.", "Rotation locked.")
        } catch (e: Exception) { t("ما قدرت: ${e.message}", "Could not change rotation.") }
    }

    private var awakeView: View? = null
    private val awakeTimeout = Runnable { keepScreenOn(false) }

    /** A tiny invisible window that asks Android to keep the screen on (three hours at most). */
    private fun keepScreenOn(on: Boolean): String {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        awakeView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        awakeView = null
        main.removeCallbacks(awakeTimeout)
        if (!on) return t("الشاشة بتقفل عادي.", "The screen will sleep as usual.")
        val v = View(this)
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT)
        return try {
            wm.addView(v, lp); awakeView = v
            main.postDelayed(awakeTimeout, 3 * 60 * 60_000L)
            t("الشاشة ما بتقفل لحدي ما تقول «خلي الشاشة تقفل» (أو بعد 3 ساعات).", "The screen stays on (3 hours at most).")
        } catch (e: Exception) { t("ما قدرت: ${e.message}", "Could not keep the screen on.") }
    }

    // ------------------------------------------------------------------ YouTube

    private val youtube = "com.google.android.youtube"

    /** Look for it on YouTube and play the first video (not an advert) in the results. */
    private fun playVideo(query: String): String {
        val app = Intent(Intent.ACTION_SEARCH).setPackage(youtube)
            .putExtra(android.app.SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(app)
        } catch (_: Exception) {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                t("تطبيق يوتيوب ما موجود، فتحت البحث في المتصفح.", "YouTube is not installed; I opened the search in the browser.")
            } catch (_: Exception) { t("ما قدرت أفتح يوتيوب.", "I could not open YouTube.") }
        }
        playFirstVideo(0)
        return t("بفتش في يوتيوب عن $query.", "Looking on YouTube for $query.")
    }

    private fun playFirstVideo(tries: Int) {
        main.postDelayed({
            val nodes = screenNodes(3000).filter { it.packageName?.toString() == youtube && it.isVisibleToUser }
            val item = nodes.firstOrNull { MediaHelp.isVideo("${it.contentDescription ?: ""} ${it.text ?: ""}") }
            when {
                item != null -> {
                    press(item)
                    respond(t("شغّلتو. قول: وقّف، أو قدّم شوية، أو رجّع شوية، أو الفيديو الجاي.",
                        "Playing. Say: pause, skip forward, rewind, or next video."))
                }
                tries < 3 -> playFirstVideo(tries + 1)
                else -> respond(t("فتحت نتايج البحث بس ما لقيت فيديو أضغطو. قول: الأرقام، واختار رقم الفيديو.",
                    "The results are open but I found no video to tap. Say: numbers, then pick one."))
            }
        }, if (tries == 0) 3500L else 1500L)
    }

    /**
     * Ten seconds on or back. In YouTube: a double tap on the right (or left) of the player, like a finger would;
     * elsewhere the media keys most players understand.
     */
    private fun seek(forward: Boolean): String {
        if (rootInActiveWindow?.packageName?.toString() == youtube) {
            val dm = resources.displayMetrics
            val player = screenNodes(2500)
                .filter { it.viewIdResourceName?.contains("player") == true }
                .map { n -> Rect().also { n.getBoundsInScreen(it) } }
                .filter { it.width() > dm.widthPixels / 2 && it.height() > 100 }
                .maxByOrNull { it.width() * it.height() }
                ?: Rect(0, dm.heightPixels / 12, dm.widthPixels, dm.heightPixels / 12 + dm.widthPixels * 9 / 16)
            val x = player.left + player.width() * (if (forward) 0.82f else 0.18f)
            val y = player.exactCenterY()
            val tap = { Path().apply { moveTo(x, y); lineTo(x + 1f, y) } }
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tap(), 0, 50))
                .addStroke(GestureDescription.StrokeDescription(tap(), 160, 50)).build(), null, null)
            return t(if (forward) "قدّمت عشرة ثواني." else "رجّعت عشرة ثواني.", if (forward) "Ten seconds on." else "Ten seconds back.")
        }
        val code = if (forward) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return t("حاضر.", "Done.")
    }

    // ------------------------------------------------------------------ "describe the picture"

    /**
     * A picture of the screen goes to a model that can see (Llama 4 on Groq, or Gemini), which says in a few words
     * what is in it: a photo in a chat, a video, a page. Needs the same permission as sending the screen text.
     */
    private fun seeScreen(question: String): String {
        screenConsent()?.let { return it }
        if (Build.VERSION.SDK_INT < 30) return askScreen(question.ifBlank { "وصف ما على الشاشة" })
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    val image = try {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                        val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        soft
                    } catch (_: Exception) { null } finally { try { shot.hardwareBuffer.close() } catch (_: Exception) {} }
                    if (image == null) { respond(t("ما قدرت أقرا صورة الشاشة.", "I could not read the screenshot.")); return }
                    Thread {
                        val answer = vision(jpeg(image), question)
                        main.post {
                            respond(if (answer.isNullOrBlank()) t("ما قدرت أعرف شنو في الشاشة.", "I could not tell what is on the screen.") else answer)
                        }
                    }.start()
                }

                override fun onFailure(code: Int) {
                    respond(t("ما قدرت أصوّر الشاشة (رمز $code). لو أول مرة: اقفل خدمة زول في إمكانية الوصول وافتحها تاني.",
                        "I could not take a screenshot (code $code). If this is the first time: turn Zol off and on in Accessibility."))
                }
            })
        } catch (e: Exception) {
            return t("ما قدرت أصوّر الشاشة: ${e.message}", "I could not take a screenshot: ${e.message}")
        }
        return t("لحظة، بشوف الشاشة.", "One moment, looking at the screen.")
    }

    /** A picture of the screen as JPEG base64 (null when Android refuses), for the agent. */
    private fun screenPicture(done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null); return }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    val bmp = try {
                        android.graphics.Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                            ?.let { hw -> hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                    } catch (_: Exception) { null } finally { try { shot.hardwareBuffer.close() } catch (_: Exception) {} }
                    done(bmp?.let { jpeg(it) })
                }
                override fun onFailure(code: Int) = done(null)
            })
        } catch (_: Exception) { done(null) }
    }

    /** A chat message with a picture, to the model that can see. Off the main thread. */
    private fun visionChat(system: String, user: String, image: String, maxTokens: Int): String? = visionChat(system, user, listOf(image), maxTokens)

    private fun visionChat(system: String, user: String, images: List<String>, maxTokens: Int): String? {
        var base = prefs.getString("ai_url", "").orEmpty().trim().trimEnd('/')
        if (!base.startsWith("http")) base = "http://$base"
        val key = prefs.getString("ai_key", "").orEmpty().trim()
        val model = visionModel(base, key) ?: return null
        return try {
            val body = JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", maxTokens)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", user))
                        images.forEach { put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it"))) }
                    })))
            if (base.contains("generativelanguage.googleapis.com")) body.put("reasoning_effort", "low")
            val con = URL("$base/chat/completions").openConnection() as HttpURLConnection
            con.requestMethod = "POST"; con.doOutput = true
            con.connectTimeout = 8000; con.readTimeout = 45000
            con.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $key")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) { con.disconnect(); return null }
            val raw = con.inputStream.bufferedReader().use { it.readText() }
            con.disconnect()
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } catch (_: Exception) { null }
    }

    /** At most 1280 pixels on the long side, as JPEG in base64: small enough to send quickly. */
    private fun jpeg(bitmap: android.graphics.Bitmap): String {
        val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        if (small !== bitmap) small.recycle()
        bitmap.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** The model that looks at pictures: on Groq a vision model from its list (kept), elsewhere the chosen model. */
    private fun visionModel(base: String, key: String): String? {
        if (!base.contains("groq.com")) return prefs.getString("ai_model", "").orEmpty().trim().ifEmpty { null }
        prefs.getString("vision_model", null)?.let { return it }
        val ids = try {
            val con = URL("$base/models").openConnection() as HttpURLConnection
            con.connectTimeout = 8000
            con.readTimeout = 10000
            con.setRequestProperty("Authorization", "Bearer $key")
            val raw = if (con.responseCode in 200..299) con.inputStream.bufferedReader().use { it.readText() } else ""
            con.disconnect()
            val arr = if (raw.isEmpty()) JSONArray() else JSONObject(raw).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (_: Exception) { emptyList() }
        val m = MediaHelp.bestVisionModel(ids) ?: return null
        prefs.edit().putString("vision_model", m).apply()
        return m
    }

    /** Ask the model what is in the picture. Off the main thread. */
    private fun vision(image: String, question: String, retried: Boolean = false): String? {
        var base = prefs.getString("ai_url", "").orEmpty().trim().trimEnd('/')
        if (!base.startsWith("http")) base = "http://$base"
        val key = prefs.getString("ai_key", "").orEmpty().trim()
        val model = visionModel(base, key) ?: run {
            main.post { log("السيرفر الذكي ما عندو موديل بيشوف الصور.") }
            return null
        }
        return try {
            val ask = question.ifBlank { t("وصف لي الشاشة دي، وخصوصاً الصورة أو الفيديو لو في.", "Describe this screen, especially any picture or video.") }
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_tokens", 300)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content",
                        "You look at a phone screenshot for a person who cannot use their hands well, and say what is in it. " +
                        "Describe the picture or video first (people, place, things, any writing in it), then anything important on the screen. " +
                        "Ignore the status bar and any floating button. At most three short sentences, " +
                        "in the language and dialect of the question (Sudanese Arabic when the question is Arabic)."))
                    .put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", ask))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image"))))))
            if (base.contains("generativelanguage.googleapis.com")) body.put("reasoning_effort", if (model.startsWith("gemini-2.5")) "none" else "low")
            val con = URL("$base/chat/completions").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 8000
            con.readTimeout = 45000
            con.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $key")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = con.responseCode
            if (code !in 200..299) {
                val why = try { con.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() } catch (_: Exception) { "" }
                con.disconnect()
                if ((code == 404 || code == 400) && !retried && base.contains("groq.com") && why.contains("model", true)) {
                    prefs.edit().remove("vision_model").apply()          // retired: take another from the list
                    return vision(image, question, true)
                }
                val msg = Regex("\"message\"\\s*:\\s*\"([^\"]{0,140})").find(why)?.groupValues?.get(1) ?: why.take(140)
                main.post { log("الموديل «$model» ما قدر يشوف الصورة ($code): $msg") }
                return null
            }
            val raw = con.inputStream.bufferedReader().use { it.readText() }
            con.disconnect()
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                .substringAfterLast("</think>").trim()
        } catch (e: Exception) {
            main.post { log("ما قدرت أرسل صورة الشاشة: ${e.message}") }
            null
        }
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
            else { log("${Brand.NAME}: لم أجد زر الالتقاط. قل: الأرقام ثم رقم الزر."); say(t("لم أجد زر الالتقاط.", "I could not find the shutter button.")) }
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

    // ---- what Zol remembers: notes, and nicknames used when calling or messaging

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
        return t("حاضر، سأنفذ المهمة خطوة خطوة. قل: ${Commands.wakeWord} توقف، للإلغاء.", "On it, step by step. Say: ${Commands.wakeWord} stop, to cancel.")
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
        // few readable elements (a picture, a game, a web page): the agent looks at the screen itself
        if (items.size < 6 && Build.VERSION.SDK_INT >= 30 && prefs.getBoolean("agent_vision", true)) {
            screenPicture { image ->
                Thread {
                    val raw = if (image != null) visionChat(Agent.SYSTEM + "\n" + Agent.PICTURE, obs, image, 200) ?: cloudChat(Agent.SYSTEM, obs, 200)
                        else cloudChat(Agent.SYSTEM, obs, 200)
                    main.post { agentAct(raw) }
                }.start()
            }
            return
        }
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
            "tap_xy" -> {
                if (Agent.isRisky(step.text) || step.text.isBlank()) {      // sending, paying, deleting: only after a yes
                    agentOn = false
                    respond(ask(t("الخطوة الجاية: أضغط «${step.text.ifBlank { "نقطة في الشاشة" }}». أواصل؟ نعم أو لا.", "Next: press ${step.text}. Go on?"),
                        onNo = { stopAgent(t("وقفت.", "Stopped.")); "" }) {
                        val dm = resources.displayMetrics
                        tapAt(step.x * dm.widthPixels / 1000f, step.y * dm.heightPixels / 1000f)
                        agentDone.add("tapped ${step.text}"); agentOn = true; agentNext(); ""
                    })
                    return
                }
                val dm = resources.displayMetrics
                tapAt(step.x * dm.widthPixels / 1000f, step.y * dm.heightPixels / 1000f)
                agentDone.add("tapped the picture at ${step.x},${step.y}")
                agentNext()
            }
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
            if (sameErr == 3 && code == 10) log("أندرويد يرفض طلبات الاستماع. أعد تشغيل الهاتف، أو امسح ذاكرة تطبيق Google ثم أعد تشغيل خدمة زول.")
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
        override fun onReadyForSpeech(params: Bundle?) { lastActivity = SystemClock.elapsedRealtime(); busyErrors = 0 }
        override fun onBeginningOfSpeech() { lastActivity = SystemClock.elapsedRealtime() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (gateActive) ear?.detach() }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            val wasGate = gateActive
            endGateSession()
            noteError(error)
            // "language not supported" from an engine picked earlier is that engine's fault, not the ear's
            val engineFault = error in listOf(12, 13) && prefs.getString("stt_service", null) != null
            if (wasGate && error !in listOf(6, 7, 8, 10) && !engineFault && gateTrouble()) return
            if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER) { if (netTrouble()) return }
            when (error) {
                // silence: normal. Without the ear, ask less often the longer it stays quiet
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    resume(if (gateActive || ear?.active == true) 200 else minOf(4_000L, 300L shl minOf(silent / 4, 4)))
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    log("النظام رفض استخدام الميكروفون (خطأ 9). افتح التطبيق واضغط زر الميكروفون.")
                    blocked = true
                    stopListening(false)
                }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // drop every old session and wait longer each time (3 s, 6 s, 12 s ... a minute): retrying fast
                    // is what keeps the engine refusing
                    busyErrors++
                    rebuildRecognizer()
                    if (busyErrors == 2 && cloudSttReady() && !cloudStt()) {
                        cloudSttUntil = SystemClock.elapsedRealtime() + 30 * 60_000L
                        log("Google رافض الطلبات، بقيت أسمع عن طريق Groq Whisper لمدة نص ساعة.")
                        resume(300)
                        return
                    }
                    if (busyErrors == 4 || busyErrors == 10 || busyErrors == 18) switchRecognizer()
                    val wait = minOf(60_000L, 3_000L shl minOf(busyErrors - 1, 5))
                    noStartBefore = SystemClock.elapsedRealtime() + wait
                    ear?.quietUntil = noStartBefore
                    resume(wait)
                }
                12, 13 -> {                       // language not supported / not downloaded
                    langErrors++
                    if (onDevice) {
                        log("حزمة الصوت العربية غير موجودة على الهاتف، أستخدم الاستماع العادي (يحتاج إنترنت).")
                        forceNormal = true
                        prefs.edit().putBoolean("offline_ready", false).apply()
                    } else if (prefs.getString("stt_service", null) != null) {
                        // an engine chosen earlier (after "too many requests") does not know Arabic: back to the phone's own
                        log("المحرك ${engineLabel()} ما بيفهم العربي، رجعت للمحرك الأساسي.")
                        prefs.edit().remove("stt_service").apply()
                        gateFailed = false; gateErrors = 0; gateWorked = false   // the ear may work with the right engine
                    } else if (langErrors == 3) {
                        googleEngine()?.let {
                            prefs.edit().putString("stt_service", it.flattenToString()).apply()
                            log("المحرك الأساسي ما بيفهم العربي، بقيت أستعمل محرك Google.")
                        }
                    }
                    rebuildRecognizer()
                    resume(minOf(30_000L, 1_000L shl minOf(langErrors, 5)))   // never a tight loop
                }
                else -> {
                    errors++
                    cancelRecognition()
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
            langErrors = 0
            if (gateActive && !results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).isNullOrEmpty()) gateWorked = true
            endGateSession()
            errors = 0; silent = 0; lastErr = -1
            lastActivity = SystemClock.elapsedRealtime()
            processResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf())
        }
    }

    /** What to do with what was heard (the recogniser's guesses, best first). Used by every listening engine. */
    private fun processResults(list: List<String>) {
        run {
            val heard = list.firstOrNull().orEmpty()
            val now = SystemClock.elapsedRealtime()
            // Zol's own voice coming back from the speaker is not a command
            // (in an English lesson the person repeats Zol's sentence on purpose)
            if (heard.isNotBlank() && lesson == null && ownVoice.isEcho(heard, now) && list.none { Commands.isEmergency(it) }) { resume(200); return }
            // a song or video playing on the phone says "زول" too: then the sentence must start with it
            val inMode = wellbeing != null || findPlayer != null || game != null || translating || beads != null ||
                trainQueue != null || dictating || (pending != null && now < pendingUntil) || now < awakeUntil || captionListeners.isNotEmpty() ||
                lesson != null
            if (!inMode && heard.isNotBlank() && othersPlaying() && list.none { Commands.isEmergency(it) } &&
                list.none { Commands.wakeAtStart(it) }) { resume(200); return }
            if (wellbeing != null && heard.isNotBlank()) {               // the answer to "are you alright?"
                log("سمعت: $heard")
                markActive()
                val bad = list.any { Commands.isEmergency(it) } || list.any { Commands.yesNo(it) == false } ||
                    list.any { Commands.normalize(it).let { n -> "ساعد" in n || "الحق" in n || "وجع" in n || "تعبان" in n } }
                wellbeingAnswered(!bad)
                return
            }
            if (heard.isNotBlank() && list.none { Commands.isEmergency(it) }) {
                if (findPlayer != null) {                // the alarm itself is heard too: only words that mean "found"
                    if (Fun.foundPhone(heard)) { log("سمعت: $heard"); stopFindPhone(); respond(t("الحمد لله.", "Good.")) }
                    return
                }
                if (game != null) { log("سمعت: $heard"); gameHeard(Commands.stripWake(heard) ?: heard); return }
                if (translating) { translateHeard(Commands.stripWake(heard) ?: heard); return }
                if (beads != null) { tasbeehHeard(Commands.stripWake(heard) ?: heard); return }
                if (captionListeners.isNotEmpty()) { caption(heard); resume(200); return }
                if (lesson != null) { englishHeard(heard); return }
            }
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
            // woken by the wake word a moment ago: take what was just said as the command itself
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
                    if (heard.isNotBlank()) log("(بدون كلمة ${Commands.wakeWord}) سمعت: $heard")
                    resume(200)
                }
            }
        }
    }

    // ------------------------------------------------------------------ understanding and acting

    /** Run what was said (after the wake word) or typed in the app. */
    fun handleText(text: String) {
        if (text.isNotBlank()) markActive()
        Commands.userDict = Commands.parseDict(prefs.getString("dialect", "").orEmpty())
        Commands.userPhrases = Commands.parsePhrases(prefs.getString("phrases", "").orEmpty())
        Commands.homeDevices = devices().keys
        Commands.macroNames = macros().keys
        val prevSaid = lastSaid
        if (Commands.parse(text) !is Cmd.Correct) lastSaid = text.trim() to SystemClock.elapsedRealtime()
        else correctFrom = prevSaid
        Commands.setWake(prefs.getString("wake_word", "زول"))
        usePrayerPlace()
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
            if (commands.size == 1 && Habits.worth(commands[0]) && !fromHabit) noteHabit(text)
            // while learning, spoken commands are steps too
            if (learning != null && commands.isNotEmpty() && commands.none { it is Cmd.Learn || it is Cmd.LearnDone || it is Cmd.ListMacros })
                Macros.add(learning!!, Macros.Step("say", text = text))
            when {
                findScene(text, exact = true) != null -> { beep(2); respond(runScene(text)) }
                commands.isNotEmpty() -> runAll(commands, 0)
                findScene(text) != null -> { beep(2); respond(runScene(text)) }
                aiReady() && !online() && localAiReady() -> askLocal(text)      // no internet: the brain on the phone
                aiReady() -> askAi(text)
                else -> fallback(text)
            }
        } catch (e: Exception) {
            respond(t("حدث خطأ: ${e.message}", "Error: ${e.message}"))
        }
    }

    private fun respond(reply: String) {
        if (reply.isNotBlank()) log("${Brand.NAME}: $reply")
        say(reply)
    }

    // ------------------------------------------------------------------ head pointer (front camera)

    private var head: HeadControl? = null

    private fun tapAt(x: Float, y: Float) {
        gesture(Path().apply { moveTo(x, y); lineTo(x + 1f, y) }, 60)
    }

    fun headRunning() = head?.running == true

    private fun longPressAt(x: Float, y: Float) {
        gesture(Path().apply { moveTo(x, y); lineTo(x + 1f, y) }, 800)
    }

    /** A button of the head dock. [x], [y]: the last place the person aimed at, so scrolling moves that list. */
    private fun headAction(action: HeadControl.Action, x: Float, y: Float) {
        when (action) {
            HeadControl.Action.SCROLL_UP -> swipeAt(x, y, false)
            HeadControl.Action.SCROLL_DOWN -> swipeAt(x, y, true)
            HeadControl.Action.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            HeadControl.Action.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            HeadControl.Action.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> return
        }
        beep(2)
    }

    /** Scroll the content under (x, y) by about a third of the screen. */
    private fun swipeAt(x: Float, y: Float, down: Boolean) {
        val dm = resources.displayMetrics
        val h = dm.heightPixels.toFloat()
        val half = h * 0.17f
        val cy = y.coerceIn(h * 0.25f, h * 0.75f)
        val cx = x.coerceIn(dm.widthPixels * 0.15f, dm.widthPixels * 0.85f)
        gesture(Path().apply {
            if (down) { moveTo(cx, cy + half); lineTo(cx, cy - half) } else { moveTo(cx, cy - half); lineTo(cx, cy + half) }
        }, 280)
    }

    /** Start the head pointer. Works best when started from the app: Android lets the camera start only while the app is in use. */
    fun startHead(): String {
        if (!granted(Manifest.permission.CAMERA)) return t("أحتاج إذن الكاميرا. افتح تطبيق زول ← الإعدادات ← التحكم بالرأس.", "I need the camera permission.")
        if (head?.running == true) return t("التحكم بالرأس يعمل.", "Head control is on.")
        val control = HeadControl(this,
            onTap = { x, y -> tapAt(x, y); beep(2) },
            onLongPress = { x, y -> longPressAt(x, y); beep(2) },
            onAction = { action, x, y -> headAction(action, x, y) },
            onStatus = { log("${Brand.NAME}: $it") },
            onKey = { headKey(it) })
        head = control
        control.start(
            speed = prefs.getFloat("head_speed", 1f),
            dwellMs = prefs.getLong("head_dwell", 1500L),
            dwellOn = prefs.getBoolean("head_dwell_on", true),
            blinkTap = prefs.getBoolean("head_blink", false),
            smileTap = prefs.getBoolean("head_smile", false),
            invertX = prefs.getBoolean("head_invert", false),
            dock = prefs.getString("head_dock", "left") ?: "left",
        )
        if (!showNotification(listening, true)) {
            control.stop(); head = null
            updateForeground()
            return t("لم يسمح أندرويد بتشغيل الكاميرا من الخلفية. افتح تطبيق زول واضغط «ابدأ التحكم بالرأس».",
                "Android blocked the camera from the background. Open Zol and start it there.")
        }
        hideNumbers(); hideGrid()
        return t("شغّلت التحكم بالرأس. انظر إلى الأمام لحظة، ثم حرّك رأسك. ثبّت الدائرة لتضغط، أو قل: اضغط هنا.",
            "Head control on. Look ahead for a moment, then move your head. Hold the ring still to tap.")
    }

    fun stopHead(): String {
        val was = head?.running == true
        hideHeadKeyboard()
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

    private fun clock(h: Int, m: Int) = clockText(h, m)

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
            if (kind == "prayer") { prayerAlert(text, attempt); return@post }
            if (kind == "azkar") { azkarAlert(text); return@post }
            if (kind == "inactive") { inactiveFire(); return@post }
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
     * (Zol not listening, phone asleep) still comes back; "نعم" cancels it. After 3 tries the caregiver is told.
     */
    private fun askReminder(text: String, attempt: Int) {
        if (attempt > 3) {
            val told = prefs.getBoolean("pro_care", false) && smsCaregiver("تنبيه من زول: لم يتم تأكيد «$text» بعد 3 تذكيرات.")
            if (told) announce(t("لم تؤكد «$text»، فأرسلت رسالة للمرافق.", "Not confirmed: I texted your caregiver."), false, urgent = true)
            return
        }
        ProactiveReceiver.again(this, text, attempt + 1, 12)
        val question = if (attempt == 1) t("تذكير: «$text». هل فعلتها؟ قل نعم أو لا.", "Reminder: $text. Done? Say yes or no.")
            else t("أذكّرك مرة أخرى: «$text». هل فعلتها؟", "Reminder again: $text. Done?")
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
        val dua = if (arabic) " " + Fun.dailyText("dua", Fun.dailyPick("dua", java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR))) else ""
        announce(t("صباح الخير!", "Good morning!") + plan + dua, false)
        main.postDelayed({ if (listening) runAll(Commands.parseAll("صباح الخير"), 0) }, 4000)
    }

    // ------------------------------------------------------------------ prayer times

    /** Let the parser turn "بعد المغرب" into a clock time at the person's place. */
    private fun usePrayerPlace() {
        val place = ProactiveReceiver.prayerPlace(this)
        Commands.prayerMinutes = { p -> PrayerTimes.forDay(java.util.Calendar.getInstance(), place.lat, place.lon)[p] }
    }

    private fun prayerName(p: PrayerTimes.Prayer) = t(p.ar, p.en)

    private fun clockOfDay(minutes: Int) = clockText(minutes / 60, minutes % 60)

    /** "ساعة و20 دقيقة", "40 دقيقة", "ساعتين" */
    private fun spanText(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        if (!arabic) return listOfNotNull(if (h > 0) "$h h" else null, if (m > 0) "$m min" else null).joinToString(" ").ifEmpty { "now" }
        val hours = when (h) { 0 -> ""; 1 -> "ساعة"; 2 -> "ساعتين"; else -> "$h ساعات" }
        val mins = when (m) { 0 -> ""; 1 -> "دقيقة"; 2 -> "دقيقتين"; in 3..10 -> "$m دقايق"; else -> "$m دقيقة" }
        return listOf(hours, mins).filter { it.isNotEmpty() }.joinToString(" و").ifEmpty { "دقيقة" }
    }

    /** prayer: null = the next one, "all" = today's list, or a [PrayerTimes.Prayer] name. */
    private fun prayerAnswer(prayer: String?): String {
        val place = ProactiveReceiver.prayerPlace(this)
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val today = PrayerTimes.forDay(now, place.lat, place.lon)
        if (prayer == "all") {
            val list = PrayerTimes.FIVE.joinToString("، ") { "${prayerName(it)} ${clockOfDay(today.getValue(it))}" }
            return t("مواقيت الصلاة الليلة في ${place.name}: $list.", "Prayer times today in ${place.name}: $list.")
        }
        val p = prayer?.let { runCatching { PrayerTimes.Prayer.valueOf(it) }.getOrNull() }
        if (p == null) {
            val (next, at, tomorrow) = PrayerTimes.next(now, place.lat, place.lon)
            val left = (if (tomorrow) at + 24 * 60 else at) - nowMin
            return t("الصلاة الجاية ${prayerName(next)} الساعة ${clockOfDay(at)}، فاضل ليها ${spanText(left)}.",
                "Next is ${prayerName(next)} at ${clockOfDay(at)}, in ${spanText(left)}.")
        }
        val at = today.getValue(p)
        return if (at > nowMin) t("${prayerName(p)} الساعة ${clockOfDay(at)}، فاضل ${spanText(at - nowMin)}.",
            "${prayerName(p)} is at ${clockOfDay(at)}, in ${spanText(at - nowMin)}.")
        else t("${prayerName(p)} أذّن الليلة الساعة ${clockOfDay(at)}، قبل ${spanText(nowMin - at)}.",
            "${prayerName(p)} was at ${clockOfDay(at)} today, ${spanText(nowMin - at)} ago.")
    }

    /** The adhan alarm fired: [name] is the prayer, [before] how many minutes early it was set. */
    private fun prayerAlert(name: String, before: Int) {
        val p = runCatching { PrayerTimes.Prayer.valueOf(name) }.getOrNull() ?: return
        val text = if (before > 0) t("فاضل ${spanText(before)} على أذان ${prayerName(p)}.", "${prayerName(p)} in ${spanText(before)}.")
            else t("حان وقت صلاة ${prayerName(p)}.", "It is time for ${prayerName(p)}.")
        beep(2)
        announce(text, false, urgent = true)
    }

    // ------------------------------------------------------------------ keeping alive

    private fun bootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun noteStart() {
        val installed = try { packageManager.getPackageInfo(packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val s = ServiceHealth.started(ServiceHealth.decode(prefs.getString("svc_health", null)), System.currentTimeMillis(), bootTime(), installed)
        prefs.edit().putString("svc_health", ServiceHealth.encode(s)).commit()
        val today = ServiceHealth.stopsToday(s, System.currentTimeMillis())
        if (today > 0 && s.stops.lastOrNull()?.let { System.currentTimeMillis() - it < 60_000 } == true)
            log("التلفون قفل زول ($today مرة اليوم). افتح الإعدادات ← المساعدة ← «خلّي زول ما يقفل».")
    }

    private fun noteStop() {
        val s = ServiceHealth.stopped(ServiceHealth.decode(prefs.getString("svc_health", null)))
        prefs.edit().putString("svc_health", ServiceHealth.encode(s)).commit()
    }

    fun stopsToday() = ServiceHealth.stopsToday(ServiceHealth.decode(prefs.getString("svc_health", null)), System.currentTimeMillis())

    fun recentEvents(): List<String> = history.toList()

    /** Settings for safety, the caregiver and switches changed (or Zol just started). */
    fun applyCareSettings() {
        if (prefs.getBoolean("fall_on", false)) startFall() else stopFall()
        scheduleInactive()
        main.removeCallbacks(remoteTask)
        if (prefs.getBoolean("remote_on", false) && remoteTopic().isNotEmpty()) main.postDelayed(remoteTask, 5_000)
        val mode = prefs.getString("switch_mode", "off")
        if (mode == "off") { switches?.stop(); switches = null } else {
            val control = switches ?: SwitchControl(this, { switchItems() }, { switchStrip(it) }).also { switches = it }
            control.start(if (mode == "one") prefs.getLong("switch_scan", 1500L) else 0L)
        }
    }

    // ------------------------------------------------------------------ talking board

    private var board: AacBoard? = null

    fun showBoard(): String {
        if (board?.shown == true) return t("لوحة الكلام فاتحة.", "The board is open.")
        val b = AacBoard(this, Aac.pages(prefs.getString("aac_custom", "").orEmpty()), { card, send -> boardCard(card, send) }, { hideBoard() })
        board = b
        b.show()
        head?.raise()
        if (switches?.running == true) main.postDelayed({ switches?.refresh() }, 300)
        return t("دي لوحة الكلام. اختار الكلام الداير تقولو.", "Here is the talking board.")
    }

    fun hideBoard() {
        board?.hide()
        board = null
        if (switches?.running == true) main.postDelayed({ switches?.refresh() }, 300)
    }

    /** Said aloud as written (the person's own words), or sent to the caregiver. */
    private fun boardCard(card: Aac.Card, send: Boolean) {
        markActive()
        if (send) {
            val sent = caregiverMessage(Aac.forCaregiver(card), card.urgent)
            say(if (sent) t("رسلتها للمرافق.", "Sent to your caregiver.") else t("ما في رقم أو قناة للمرافق في الإعدادات.", "No caregiver is set."))
            return
        }
        if (card.urgent) beep(3)
        try { tts?.stop() } catch (_: Exception) {}
        say(card.text)
        if (card.urgent) say(card.text, add = true)
    }

    /** A message to the caregiver: a notification through ntfy and an SMS when a number is saved. */
    private fun caregiverMessage(text: String, urgent: Boolean): Boolean {
        val topic = prefs.getString("ntfy_topic", "").orEmpty().trim()
        if (topic.isNotEmpty()) ntfyPost(topic, text, if (urgent) "urgent" else "default")
        val sms = smsCaregiver(text)
        return topic.isNotEmpty() || sms
    }

    private fun ntfyPost(topic: String, text: String, priority: String = "default") = Thread {
        try {
            val con = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 10000
            con.readTimeout = 10000
            con.setRequestProperty("Priority", priority)
            con.outputStream.use { it.write(text.toByteArray()) }
            con.responseCode
            con.disconnect()
        } catch (e: Exception) {
            log("ما قدرت أرسل للمرافق: ${e.message}")
        }
    }.start()

    // ------------------------------------------------------------------ safety: falls and long quiet

    private var fallListener: SensorEventListener? = null
    private val fallDetector = FallDetector()
    private var wellbeing: String? = null              // "fall" or "inactive" while waiting for an answer
    private var wellbeingTries = 0
    private var lastActiveSaved = 0L

    private fun startFall() {
        if (fallListener != null) return
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (fallDetector.update(e.values[0], e.values[1], e.values[2], SystemClock.elapsedRealtime()))
                    main.post { if (wellbeing == null) askWellbeing("fall") }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        fallListener = listener
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopFall() {
        fallListener?.let { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(it) }
        fallListener = null
        main.removeCallbacks(wellbeingTimeout)
    }

    /** The person did something: the quiet-time check moves on. Saved at most every ten minutes. */
    private fun markActive(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastActiveSaved < 10 * 60_000L) return
        lastActiveSaved = now
        prefs.edit().putLong("last_active", now).apply()
        scheduleInactive()
    }

    private fun scheduleInactive() {
        val hours = prefs.getInt("inactive_hours", 0)
        ProactiveReceiver.scheduleInactive(this,
            Inactivity.nextCheck(prefs.getLong("last_active", System.currentTimeMillis()), hours, System.currentTimeMillis()))
    }

    private fun inactiveFire() {
        val hours = prefs.getInt("inactive_hours", 0)
        if (!Inactivity.idle(prefs.getLong("last_active", 0L), hours, System.currentTimeMillis())) { scheduleInactive(); return }
        if (wellbeing == null) askWellbeing("inactive")
    }

    private val wellbeingTimeout = Runnable {
        val kind = wellbeing ?: return@Runnable
        if (wellbeingTries < 2) { askWellbeing(kind); return@Runnable }
        wellbeing = null
        wellbeingTries = 0
        if (kind == "fall") respond(emergency("زول: يمكن يكون وقع، وما رد علي"))
        else {
            val hours = prefs.getInt("inactive_hours", 0)
            caregiverMessage("زول: ما في أي حركة في التلفون من $hours ساعات، وما رد علي. اتطمّن عليهو.", true)
            log("أرسلت للمرافق يتطمّن عليك.")
            markActive(force = true)
        }
    }

    private fun askWellbeing(kind: String) {
        wellbeing = kind
        wellbeingTries++
        beep(3)
        val question = if (kind == "fall") t("في حاجة وقعت؟ إنت كويس؟ قول أنا كويس، أو قول ساعدني.", "Did you fall? Are you alright?")
        else t("ما سمعت منك من زمن. إنت كويس؟ قول أنا كويس.", "I have not heard from you for a while. Are you alright?")
        announce(question, true, urgent = true)
        if (!listening && granted(Manifest.permission.RECORD_AUDIO)) startListening(false)
        awakeUntil = SystemClock.elapsedRealtime() + 90_000            // the answer is said without the wake word
        main.removeCallbacks(wellbeingTimeout)
        main.postDelayed(wellbeingTimeout, if (kind == "fall") 45_000L else 90_000L)
    }

    private fun wellbeingAnswered(fine: Boolean) {
        val kind = wellbeing ?: return
        wellbeing = null
        wellbeingTries = 0
        main.removeCallbacks(wellbeingTimeout)
        awakeUntil = 0L
        if (fine) respond(t("الحمد لله على السلامة.", "Glad you are fine."))
        else respond(emergency(if (kind == "fall") "زول: وقع ومحتاج مساعدة" else "زول: محتاج مساعدة"))
        markActive(force = true)
    }

    // ------------------------------------------------------------------ the caregiver, from afar

    private fun remoteTopic() = prefs.getString("remote_topic", "").orEmpty().trim()

    private val remoteTask = object : Runnable {
        override fun run() {
            val topic = remoteTopic()
            if (!prefs.getBoolean("remote_on", false) || topic.isEmpty()) return
            if (online()) Thread { pollRemote(topic) }.start()
            main.postDelayed(this, 60_000)
        }
    }

    private fun pollRemote(topic: String) {
        val last = prefs.getString("remote_last", "").orEmpty()
        val since = if (last.isNotEmpty()) last else prefs.getLong("remote_since", System.currentTimeMillis() / 1000).toString()
        val body = httpGet("https://ntfy.sh/$topic/json?poll=1&since=${Uri.encode(since)}") ?: return
        val messages = body.lines().mapNotNull { Remote.ntfyMessage(it) }
        if (messages.isEmpty()) return
        prefs.edit().putString("remote_last", messages.last().first).apply()
        val pin = prefs.getString("remote_pin", "").orEmpty()
        main.post { for ((_, text) in messages) Remote.parse(text, pin)?.let { remoteDo(it) } }
    }

    private fun remoteReply(text: String) {
        val out = prefs.getString("ntfy_topic", "").orEmpty().trim().ifEmpty { remoteTopic() + "-reply" }
        ntfyPost(out, text)
    }

    private fun remoteDo(action: Remote.Action) {
        when (action) {
            is Remote.Action.Say -> { announce(t("رسالة من المرافق: ", "From your caregiver: ") + action.text, true); remoteReply("✓ وصلت، وقلتها.") }
            is Remote.Action.Run -> {
                val result = execute(action.cmd)
                announce(t("المرافق ضبط ليك: ", "Your caregiver set: ") + result, true)
                remoteReply("✓ $result")
            }
            Remote.Action.Battery -> remoteReply(batteryState().let { (pct, plugged) -> "البطارية $pct%" + if (plugged) " وهو في الشاحن" else "" })
            Remote.Action.Where -> remoteReply(lastLocationLink()?.let { "آخر موقع: $it" } ?: "الموقع ما متاح (إذن الموقع مقفول أو ما في موقع حديث).")
            Remote.Action.Status -> {
                val mins = (System.currentTimeMillis() - prefs.getLong("last_active", 0L)) / 60_000
                val (pct, _) = batteryState()
                remoteReply("زول شغّال • الاستماع ${if (listening) "شغّال" else "مقفول"} • البطارية $pct% • آخر استعمال قبل $mins دقيقة")
            }
            Remote.Action.Help -> remoteReply(Remote.HELP)
            is Remote.Action.Refused -> remoteReply("✗ ${action.why}")
        }
    }

    // ------------------------------------------------------------------ switches

    private var switches: SwitchControl? = null

    /** What the switch frame visits: the board when it is open, otherwise everything that can be pressed on screen. */
    private fun switchItems(): List<Pair<SwitchScan.Item, () -> Unit>> {
        board?.takeIf { it.shown }?.let { b ->
            return b.targets().map { (r, v) ->
                SwitchScan.Item(r.left, r.top, r.right, r.bottom, v.contentDescription?.toString().orEmpty()) to { v.performClick(); Unit }
            }
        }
        val nodes = ArrayList<Pair<SwitchScan.Item, AccessibilityNodeInfo>>()
        val root = rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 800 && nodes.size < 80) {
            val n = queue.removeFirst()
            seen++
            if ((n.isClickable || n.isEditable || n.isLongClickable) && n.isVisibleToUser && n.isEnabled) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty) nodes.add(SwitchScan.Item(r.left, r.top, r.right, r.bottom, (n.contentDescription ?: n.text ?: "").toString()) to n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        val byItem = nodes.toMap()
        val d = resources.displayMetrics.density
        return SwitchScan.order(nodes.map { it.first }, (24 * d).toInt(), (16 * d).toInt()).map { item ->
            item to {
                val node = byItem[item]
                if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapAt(item.cx.toFloat(), item.cy.toFloat())
            }
        }
    }

    private fun switchStrip(s: SwitchScan.Strip) {
        markActive()
        when (s) {
            SwitchScan.Strip.BACK -> if (board?.shown == true) hideBoard() else performGlobalAction(GLOBAL_ACTION_BACK)
            SwitchScan.Strip.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            SwitchScan.Strip.DOWN -> swipe(true)
            SwitchScan.Strip.UP -> swipe(false)
            SwitchScan.Strip.NOTIFS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            SwitchScan.Strip.LISTEN -> { awakeUntil = SystemClock.elapsedRealtime() + 15_000; beep(1); if (!listening) startListening(false) else resume(100) }
            SwitchScan.Strip.BOARD -> if (board?.shown == true) hideBoard() else respond(showBoard())
        }
    }

    // ------------------------------------------------------------------ Quran downloads

    private fun downloadQuran(surahs: List<Int>, reciterId: String?): String {
        if (!online()) return t("النت قاطع هسي. جرّب لما يرجع.", "No internet right now.")
        val reciter = Quran.reciter(reciterId ?: prefs.getString("quran_reciter", Quran.DEFAULT_RECITER))
        QuranPlayer.download(this, surahs, reciter.id) { saved, failed ->
            announce(if (failed == 0) t("نزّلت ${if (surahs.size == 1) Quran.title(surahs[0]) else "$saved سورة"}، بتشتغل من غير نت.", "Downloaded.")
            else t("نزّلت $saved، وما قدرت أنزّل $failed. جرّب تاني بعدين.", "Some downloads failed."), false)
        }
        return t("بنزّل ${if (surahs.size == 1) Quran.title(surahs[0]) else "${surahs.size} سورة"} بصوت الشيخ ${reciter.ar}. بقول ليك لما أخلص.",
            "Downloading.")
    }

    fun downloadEssentialQuran() { main.post { respond(downloadQuran(Quran.ESSENTIAL, null)) } }

    fun quranDownloaded() = QuranPlayer.downloaded(this, prefs.getString("quran_reciter", Quran.DEFAULT_RECITER) ?: Quran.DEFAULT_RECITER)

    // ------------------------------------------------------------------ Quran

    private var quran: QuranPlayer? = null

    private fun quranPlayer(): QuranPlayer = quran ?: QuranPlayer(this) { event ->
        when (event) {
            is QuranPlayer.Event.Started -> {
                prefs.edit().putInt("quran_last", event.surah).apply()
                if (event.fellBack) announce(t("الشيخ ما عندو تسجيل لـ${Quran.title(event.surah)}، فشغّلتها بصوت الشيخ مشاري العفاسي.",
                    "That reciter has no recording of this surah, so it is Mishary Alafasy."), false)
            }
            is QuranPlayer.Event.Failed -> announce(if (!online())
                t("النت قاطع، و${Quran.title(event.surah)} ما منزّلة في التلفون. لما النت يرجع قول: نزّل ${Quran.title(event.surah)}.",
                    "No internet, and this surah is not downloaded.")
            else t("ما قدرت أشغّل ${Quran.title(event.surah)}. اتأكد إنو الإنترنت شغّال.",
                "I could not play the surah. Check the internet."), false)
            QuranPlayer.Event.Finished -> {}
        }
    }.also { quran = it }

    /** [surahs] empty: carry on from the last surah played (or start with Al-Fatiha). */
    private fun playQuran(surahs: List<Int>, reciterId: String?): String {
        val list = surahs.ifEmpty { listOf(prefs.getInt("quran_last", 1).coerceIn(1, 114)) }
        val reciter = Quran.reciter(reciterId ?: prefs.getString("quran_reciter", Quran.DEFAULT_RECITER))
        val player = quranPlayer()
        player.continuous = list.size == 1 && prefs.getBoolean("quran_continue", true)
        player.play(list, reciter.id)
        val what = if (list == Quran.MUAWWIDHAT) "المعوذات" else Quran.title(list.first())
        return t("حاضر، $what بصوت الشيخ ${reciter.ar}.", "Playing surah ${list.first()} by ${reciter.en}.")
    }

    private fun quranControl(what: String): String {
        val q = quran
        return when (what) {
            "stop" -> if (q?.active == true) { q.stop(); t("وقّفت القرآن.", "Stopped.") } else t("القرآن ما شغّال.", "Nothing is playing.")
            "resume" -> if (q?.resume() == true) t("حاضر.", "Done.") else playQuran(emptyList(), null)
            "next" -> if (q?.active == true) { q.next(); t("${Quran.title(q.current)}.", "Next surah.") }
                else playQuran(listOf(Quran.next(prefs.getInt("quran_last", 0).coerceIn(0, 114))), null)
            "prev" -> if (q?.active == true) { q.prev(); t("${Quran.title(q.current)}.", "Previous surah.") }
                else playQuran(listOf(Quran.prev(prefs.getInt("quran_last", 2).coerceIn(1, 114))), null)
            else -> t("حاضر.", "Done.")
        }
    }

    // ------------------------------------------------------------------ adhkar

    /** [kind] null: morning adhkar until Asr, evening after it. */
    private fun readAzkar(kind: String?): String {
        val place = ProactiveReceiver.prayerPlace(this)
        val now = java.util.Calendar.getInstance()
        val times = PrayerTimes.forDay(now, place.lat, place.lon)
        val minutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val k = kind ?: Azkar.kindAt(minutes, times.getValue(PrayerTimes.Prayer.FAJR), times.getValue(PrayerTimes.Prayer.ASR))
        quran?.takeIf { it.playing }?.pause()
        return startReading(Azkar.script(k))
    }

    /** From the settings screen. */
    fun readAzkarNow(kind: String) { main.post { respond(readAzkar(kind)) } }

    /** The adhkar alarm: ask before reading, the person may be busy. */
    private fun azkarAlert(kind: String) {
        val question = t("وقت ${Azkar.title(kind)}. أقراها ليك؟ قول نعم أو لا.", "Time for the adhkar. Shall I read them?")
        ask(question, seconds = 120) { readAzkar(kind) }
        announce(question, true)
    }

    // ------------------------------------------------------------------ qibla

    private var qiblaListener: SensorEventListener? = null
    private var qiblaStep: Qibla.Step? = null
    private var qiblaSaidAt = 0L
    private var qiblaAheadSince = 0L
    private val qiblaTimeout = Runnable { if (qiblaListener != null) { stopQibla(); say(t("وقّفت البوصلة.", "Compass off.")) } }

    private fun stopQibla() {
        qiblaListener?.let { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(it) }
        qiblaListener = null
        main.removeCallbacks(qiblaTimeout)
    }

    /** Say where the qibla is, then guide the person round with the compass for half a minute. */
    private fun qibla(): String {
        stopQibla()
        val place = ProactiveReceiver.prayerPlace(this)
        val bearing = Qibla.bearing(place.lat, place.lon)
        val where = t("القبلة من ${place.name} ناحية ${Qibla.compassWord(bearing)}، ${Qibla.degrees(bearing)} درجة من الشمال.",
            "From ${place.name} the qibla is ${Qibla.degrees(bearing)} degrees from north.")
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return where
        val declination = GeomagneticField(place.lat.toFloat(), place.lon.toFloat(), 0f, System.currentTimeMillis()).declination
        val matrix = FloatArray(9)
        val angles = FloatArray(3)
        qiblaStep = null
        qiblaAheadSince = 0L
        qiblaSaidAt = SystemClock.elapsedRealtime() + 6000          // let the first sentence finish
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, angles)
                val heading = (Math.toDegrees(angles[0].toDouble()) + declination + 360.0) % 360.0
                val step = Qibla.step(Qibla.turn(heading, bearing))
                val now = SystemClock.elapsedRealtime()
                if (step == Qibla.Step.AHEAD) {
                    if (qiblaAheadSince == 0L) qiblaAheadSince = now
                    if (now - qiblaAheadSince > 2500 && now > qiblaSaidAt) {
                        stopQibla(); beep(2)
                        say(t("تمام، إنت هسي مواجه القبلة.", "You are facing the qibla."))
                        return
                    }
                } else qiblaAheadSince = 0L
                if (step != qiblaStep && now > qiblaSaidAt) {
                    qiblaStep = step
                    qiblaSaidAt = now + 1800
                    say(Qibla.say(step, !arabic))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        qiblaListener = listener
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        main.postDelayed(qiblaTimeout, 40_000)
        return where + " " + t("خلّي التلفون مسطّح قدامك، ولف بشويش وأنا بوجهك.", "Hold the phone flat and turn slowly; I will guide you.")
    }

    // ------------------------------------------------------------------ head keyboard

    private var kbWanted = false                   // asked for the keyboard before choosing a text field
    private var kbWords: MutableMap<String, Int>? = null

    private fun learnedWords(): MutableMap<String, Int> =
        kbWords ?: WordPredict.decode(prefs.getString("kb_words", "").orEmpty()).also { kbWords = it }

    private fun openHeadKeyboard(): String {
        if (head?.running != true) {
            val started = startHead()
            if (head?.running != true) return started
        }
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field == null) {
            kbWanted = true
            return t("وجّه الدائرة على خانة الكتابة وثبّتها، وأنا بفتح ليك الكيبورد.", "Aim at a text field and hold; the keyboard will open.")
        }
        showHeadKeyboard(field)
        return t("الكيبورد جاهز. ثبّت الدائرة على الحرف لتكتبه.", "Keyboard ready. Hold the ring on a letter to type it.")
    }

    /** Over the half of the screen away from [field], so the person sees what they type. */
    private fun showHeadKeyboard(field: AccessibilityNodeInfo) {
        val control = head ?: return
        kbWanted = false
        val r = Rect()
        field.getBoundsInScreen(r)
        val atTop = r.centerY() > resources.displayMetrics.heightPixels / 2
        try { softKeyboardController.showMode = SHOW_MODE_HIDDEN } catch (_: Exception) {}   // the phone's own keyboard would cover ours
        if (!control.keyboardShown) control.showKeyboard(atTop, prefs.getLong("head_kb_dwell", 900L))
        refreshHeadKeyboard()
    }

    private fun refreshHeadKeyboard() {
        val text = fieldText()?.second.orEmpty()
        head?.keyboardText(text, WordPredict.suggest(text, learnedWords()))
    }

    private fun hideHeadKeyboard() {
        kbWanted = false
        if (head?.keyboardShown != true) return
        head?.hideKeyboard()
        try { softKeyboardController.showMode = SHOW_MODE_AUTO } catch (_: Exception) {}
    }

    private fun headKey(key: HeadKeys.Key) {
        when (key.kind) {
            HeadKeys.Kind.CLOSE -> { hideHeadKeyboard(); return }
            HeadKeys.Kind.MODE -> { head?.keyboardNumbers(); refreshHeadKeyboard(); return }
            HeadKeys.Kind.ENTER -> { sendFromHeadKeyboard(); return }
            else -> {}
        }
        val (field, old) = fieldText() ?: run { say(t("ما في خانة كتابة مختارة.", "No text field is selected.")); return }
        val new = HeadKeys.apply(old, key)
        if (new != old && !setFieldText(field, new)) { beep(3); return }
        if (key.kind == HeadKeys.Kind.SPACE || key.kind == HeadKeys.Kind.SUGGEST) learnWords(new, lastOnly = true)
        refreshHeadKeyboard()
    }

    /** Count what the person types, so the suggestions become theirs. */
    private fun learnWords(text: String, lastOnly: Boolean) {
        val words = learnedWords()
        if (lastOnly) WordPredict.learnLast(text, words) else WordPredict.learn(text, words)
        prefs.edit().putString("kb_words", WordPredict.encode(words)).apply()
    }

    /** The keyboard's send key: the field's own "enter" action, else a button called send, else a new line. */
    private fun sendFromHeadKeyboard() {
        val (field, text) = fieldText() ?: return
        var done = false
        if (Build.VERSION.SDK_INT >= 30)
            done = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (!done) {
            val send = findNode(rootInActiveWindow) { n ->
                val label = Commands.normalize((n.contentDescription ?: n.text ?: "").toString())
                n.isClickable && label in setOf("ارسال", "send", "ارسل", "إرسال")
            }
            done = send?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        if (!done) setFieldText(field, text + "\n")
        beep(2)
        main.postDelayed({ refreshHeadKeyboard() }, 400)
    }

    private fun findNode(root: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 600) {
            val n = queue.removeFirst()
            seen++
            if (test(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    // ------------------------------------------------------------------ dialect training ("دربني")

    /** Zol names a command; the person says it their own way; what he does not understand yet is learned. */
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
        return "تمام، نبدأ التدريب. أقول لك أمراً وتقوله أنت بطريقتك، بدون كلمة ${Commands.wakeWord}. " +
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
            log("${Brand.NAME}: $reply")
            main.postDelayed({ runAll(commands, index + 1) }, 1600)
        } else {
            respond(reply)
        }
    }

    // ------------------------------------------------------------------ smart mode (optional language model)

    private fun aiReady() = prefs.getString("ai_url", "").orEmpty().isNotBlank() &&
        prefs.getString("ai_model", "").orEmpty().isNotBlank()

    /** The fixed instructions plus what is useful to know right now: facts the person told Zol, devices, current app. */
    /** The phone's Arabic voices, one after the other (each phone has its own). */
    private fun nextVoice(): String {
        val engine = tts ?: return t("صوت النطق ما جاهز.", "Speech is not ready.")
        val voices = try { engine.voices.orEmpty().filter { it.locale.language == "ar" && !it.isNetworkConnectionRequired }.sortedBy { it.name } }
            catch (_: Exception) { emptyList() }
        if (voices.size < 2) return t("تلفونك فيهو صوت عربي واحد بس. تقدر تنزّل أصوات تانية من إعدادات تحويل النص لكلام.",
            "This phone has one Arabic voice only.")
        val now = prefs.getString("tts_voice", null)
        val next = voices[(voices.indexOfFirst { it.name == now } + 1) % voices.size]
        prefs.edit().putString("tts_voice", next.name).apply()
        try { engine.voice = next } catch (_: Exception) {}
        return t("دا صوت ${voices.indexOf(next) + 1} من ${voices.size}. قول «غيّر الصوت» تاني للجاي.", "Voice ${voices.indexOf(next) + 1} of ${voices.size}.")
    }

    private fun aiPrompt(): String = buildString {
        append(AI_PROMPT)
        append("\nPersonality: ").append(Personas.prompt(prefs.getString("persona", "friend").orEmpty()))
        val facts = prefs.getString("memory", "").orEmpty().lines().filter { it.isNotBlank() }.take(15)
        if (facts.isNotEmpty()) append("\nThings the user told you to remember: ").append(facts.joinToString("; "))
        val names = devices().keys
        if (names.isNotEmpty()) append("\nHome devices the user can switch: ").append(names.joinToString(", "))
        rootInActiveWindow?.packageName?.let { append("\nForeground app: ").append(it) }
    }

    /** One blocking request to the OpenAI-compatible server in settings (Gemini, Groq, Ollama...). Call it off the main thread. */
    /**
     * Models that think before answering (gpt-oss, qwen3 on Groq) take seconds to do it: a command needs no long
     * thought, so they are asked to think little and not send their thinking.
     */
    private fun thinkLess(body: JSONObject) {
        val m = body.optString("model").lowercase()
        when {
            m.contains("gpt-oss") -> { body.put("reasoning_effort", "low"); body.put("include_reasoning", false) }
            m.contains("qwen3") -> { body.put("reasoning_effort", "none"); body.put("include_reasoning", false) }
        }
    }

    private fun cloudChat(system: String, user: String, maxTokens: Int = 300, retried: Boolean = false): String? {
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
            // Gemini would spend the tokens thinking: none for 2.5 (allowed there), low for newer models
            if (base.contains("generativelanguage.googleapis.com"))
                body.put("reasoning_effort", if (body.optString("model").startsWith("gemini-2.5")) "none" else "low")
            thinkLess(body)
            val con = URL("$base/chat/completions").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 8000
            con.readTimeout = 45000
            con.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $key")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            if (con.responseCode !in 200..299) {
                val why = try { con.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() } catch (_: Exception) { "" }
                val code = con.responseCode
                con.disconnect()
                if (code == 404 && !retried && (followModelHint(why) || replaceChatModel(base, key)))
                    return cloudChat(system, user, maxTokens, true)
                throw Exception("HTTP $code " + (Regex("\"message\"\\s*:\\s*\"([^\"]{0,120})").find(why)?.groupValues?.get(1) ?: ""))
            }
            val raw = con.inputStream.bufferedReader().use { it.readText() }
            con.disconnect()
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } catch (e: Exception) {
            log("تعذر الوصول إلى الوضع الذكي: ${e.message}")
            null
        }
    }

    /**
     * The smart server says the model does not exist (Groq retires models): ask it which models it has, take the best
     * one for chat, and keep it. True when the model was changed.
     */
    private fun replaceChatModel(base: String, key: String): Boolean {
        val old = prefs.getString("ai_model", "").orEmpty().trim()
        val ids = try {
            val con = URL("$base/models").openConnection() as HttpURLConnection
            con.connectTimeout = 8000
            con.readTimeout = 10000
            if (key.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $key")
            if (base.contains("generativelanguage.googleapis.com")) con.setRequestProperty("x-goog-api-key", key)
            val raw = if (con.responseCode in 200..299) con.inputStream.bufferedReader().use { it.readText() } else ""
            con.disconnect()
            val arr = if (raw.isEmpty()) JSONArray() else JSONObject(raw).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        } catch (_: Exception) { emptyList() }
        val next = bestChatModel(ids) ?: return false
        if (next == old) return false
        prefs.edit().putString("ai_model", next).apply()
        main.post { log("موديل «$old» ما بقى موجود في السيرفر، غيّرتو لـ «$next».") }
        return true
    }

    /** Nothing understood the sentence: ask "تقصد؟", then the local model, then give up politely. */
    private fun fallback(text: String) {
        rememberUnknown(text)
        val guess = Commands.suggest(text)
        when {
            guess != null -> {
                beep(1)
                respond(ask(t("تقصد: «${guess.first}»؟ نعم أو لا.", "Do you mean: ${guess.first}? Yes or no.")) { execute(guess.second) })
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
                if (raw == null && !online()) { respond(t("النت قاطع هسي، والسؤال ده محتاج نت.", "There is no internet right now.")); return@post }
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
                override fun onStart(utteranceId: String?) { lastActivity = SystemClock.elapsedRealtime(); ear?.muted = true }
                override fun onDone(utteranceId: String?) {
                    resume(300); if (reading) scheduleReadAdvance()
                    main.post { if (tts?.isSpeaking != true) quran?.duck(false) }
                }
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
            // a reply recorded in a Sudanese voice is played instead of the engine
            ownVoice.said(text, SystemClock.elapsedRealtime())
            val clip = if (arabic && prefs.getBoolean("voice_clips", true)) voiceFile(text).takeIf { it.length() > 0 } else null
            if (clip != null && (!add || (engine.isSpeaking != true && clipPlayer == null)) && playClip(clip)) return
            engine.language = if (arabic) Locale.forLanguageTag("ar") else Locale.US
            if (arabic) prefs.getString("tts_voice", null)?.let { name ->
                try { engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it } } catch (_: Exception) {}
            }
            engine.setSpeechRate(prefs.getFloat("tts_rate", 1f))
            engine.setPitch(prefs.getFloat("tts_pitch", 1f))
            val spoken = if (arabic && prefs.getBoolean("tts_pronounce", true)) VoicePack.pronounce(text, pronounceFixes()) else text
            val mode = if (add) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            if (quran?.playing == true) quran?.duck(true)
            if (engine.speak(spoken, mode, null, "jarvis") == TextToSpeech.SUCCESS) return
            log("تعذّر النطق. تحقق من محرك الصوت في إعدادات الهاتف.")
        } else {
            log("محرك الصوت غير جاهز بعد.")
        }
        resume(300)                                       // no voice available: just keep listening
    }

    // ------------------------------------------------------------------ Zol's own (Sudanese) voice

    private var clipPlayer: MediaPlayer? = null

    private fun pronounceFixes(): Map<String, String> =
        VoicePack.PRONOUNCE + VoicePack.parseFixes(prefs.getString("tts_fix", "").orEmpty())

    fun voiceFile(text: String): File = File(File(filesDir, "voice").apply { mkdirs() }, VoicePack.fileName(text))

    /** False when the recording cannot be played (then the engine says it). */
    private fun playClip(file: File): Boolean {
        try { tts?.stop() } catch (_: Exception) {}
        clipPlayer?.let { try { it.release() } catch (_: Exception) {} }
        clipPlayer = null
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.path)
            mp.setOnCompletionListener {
                it.release()
                if (clipPlayer === it) clipPlayer = null
                quran?.duck(false)
                resume(300)
                if (reading) scheduleReadAdvance()
            }
            mp.prepare()
            if (quran?.playing == true) quran?.duck(true)
            lastActivity = SystemClock.elapsedRealtime()
            ear?.muted = true
            mp.start()
            clipPlayer = mp
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The replies the person can record, written the way Zol says them now (Sudanese or standard). */
    fun voicePhrases(): List<String> = VoicePack.DEFAULTS.map { if (sudani()) Sudani.convert(it) else it }

    /** Arabic voices of the phone's engine: name and a short description. */
    fun arabicVoices(): List<Pair<String, String>> = try {
        tts?.voices.orEmpty().filter { it.locale.language == "ar" }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }))
            .map { v ->
                val where = v.locale.displayCountry.ifBlank { v.locale.country }
                v.name to (where.ifBlank { "عربي" } + (if (v.isNetworkConnectionRequired) " • محتاج نت" else "") + " • " + v.name.takeLast(14))
            }
    } catch (_: Exception) { emptyList() }

    fun sayNow(text: String) { main.post { say(text) } }

    /** Recording in the app: the microphone must be free. */
    private var micWasOn = false
    fun freeMic() { micWasOn = listening; if (listening) stopListening(false) }
    fun giveBackMic() { if (micWasOn) startListening(false); micWasOn = false }

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
            is Cmd.Scroll -> { if (pc?.connected == true) pc?.scroll(if (c.down) -4 else 4) else swipe(c.down); ok }
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
            is Cmd.PlayVideo -> playVideo(c.query)
            is Cmd.Seek -> seek(c.forward)
            is Cmd.SeeScreen -> seeScreen(c.question)
            is Cmd.FindPhone -> if (c.on) findPhone() else { stopFindPhone(); t("الحمد لله.", "Good.") }
            is Cmd.Game -> startGame(c.kind)
            is Cmd.Translate -> translateOnce(c.text)
            is Cmd.TranslateMode -> translateMode(c.on)
            Cmd.VoiceNote -> readVoiceNote(null, 0L)
            is Cmd.VoiceMessage -> recordVoiceMessage(c.name)
            is Cmd.Tasbeeh -> if (c.on) startTasbeeh() else endTasbeeh()
            is Cmd.Sleep -> if (c.minutes > 0) sleepWithQuran(c.minutes) else cancelSleep(true)
            is Cmd.Daily -> Fun.dailyText(c.kind, Fun.dailyPick(c.kind, java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)))
            is Cmd.Brightness -> brightness(c.how)
            is Cmd.Dim -> dimScreen(c.on)
            is Cmd.Rotate -> autoRotate(c.on)
            is Cmd.KeepScreen -> keepScreenOn(c.on)
            is Cmd.OfflinePack -> { downloadWhisper(c.model); "" }
            is Cmd.Learn -> startLearning(c.name)
            is Cmd.LearnDone -> finishLearning(c.name)
            is Cmd.RunMacro -> runMacro(c.name)
            Cmd.ListMacros -> macros().let { m ->
                if (m.isEmpty()) t("لسه ما علمتني حاجة. قول «زول اتعلم» واعملها قدامي مرة.", "Nothing taught yet. Say: learn this.")
                else t("الحاجات الاتعلمتها: ", "Routines: ") + m.keys.joinToString("، ") + "."
            }
            is Cmd.ForgetMacro -> { val m = macros(); m.remove(c.name); saveMacros(m); t("نسيت «${c.name}».", "Forgot ${c.name}.") }
            is Cmd.ShareDialect -> shareDialect(c.community)
            Cmd.CommunityDialects -> { communityDialects(null); t("بشوف لهجات المجتمع…", "Checking…") }
            is Cmd.InstallDialect -> { communityDialects(c.name); "" }
            Cmd.English -> startEnglish()
            is Cmd.Pc -> pcCommand(c)
            is Cmd.Eyes -> if (c.on) startEyes() else stopEyes()
            Cmd.StopAll -> stopEverything()
            is Cmd.VoicePrint -> voiceprintCommand(c.what)
            is Cmd.Correct -> correctLast(c.text)
            Cmd.OfflineBrain -> downloadBrain()
            is Cmd.AskMemory -> askMemory(c.question)
            is Cmd.Secretary -> { prefs.edit().putBoolean("secretary_on", c.on).apply()
                if (c.on) t("حاضر، برد على الرسايل بدلك بكلام قصير ومهذب، وبقول ليهم إنك حترد بعدين. قول «زول شنو الحصل» عشان أحكي ليك.",
                    "I will answer messages for you.") else t("وقفت الرد بدلك.", "Stopped answering for you.") }
            Cmd.SecretaryReport -> secretaryReport()
            is Cmd.Persona -> if (c.id.isEmpty()) t("أقدر أتكلم: ", "I can talk as: ") + Personas.ALL.joinToString("، ") { it.name } +
                t(". قول مثلاً: زول اتكلم زي الصاحب.", ". Say: talk like a friend.") else {
                val p = Personas.get(c.id)
                prefs.edit().putString("persona", p.id).putFloat("tts_rate", p.rate).putFloat("tts_pitch", p.pitch).apply()
                tts?.setSpeechRate(p.rate); tts?.setPitch(p.pitch)
                t("حاضر، بقيت ${p.name}.", "OK: ${p.name}.")
            }
            Cmd.NextVoice -> nextVoice()
            is Cmd.SendPhoto -> sendLastPhoto(c.name)
            is Cmd.FindPhotos -> findPhotos(c.query)
            is Cmd.Tv -> tvCommand(c.action)
            Cmd.VoiceBank -> {
                startActivity(Intent(this, VoiceBankActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                t("سجّل الجمل البتقولها كتير بصوتك.", "Record your own sentences.")
            }
            Cmd.Captions -> {
                startActivity(Intent(this, CaptionsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                t("بكتب ليك أي كلام بسمعو.", "Writing down everything I hear.")
            }
            is Cmd.Calc -> Fun.calc(c.text)?.let { t("بيساوي ${Fun.number(it)}.", "That is ${Fun.number(it)}.") }
                ?: t("ما فهمت الحساب.", "I did not get the sum.")
            is Cmd.Photo -> takePhoto(c.seconds)
            is Cmd.OpenChat -> openChat(c.name)
            is Cmd.Dictation -> if (c.start) startDictation() else { dictating = false; t("انتهى الإملاء.", "Dictation finished.") }
            Cmd.DeleteWord -> deleteWord()
            is Cmd.Replace -> replaceInField(c.from, c.to)
            is Cmd.Mode -> setMode(c.name)
            Cmd.ReadLong -> readLong()
            is Cmd.HomeStatus -> homeStatus(c.device)
            Cmd.Caregiver -> notifyCaregiver()
            is Cmd.Type -> if (pc?.connected == true) { pc?.type(c.text); t("كتبتها في الكمبيوتر.", "Typed on the computer.") } else type(c.text)
            is Cmd.OpenApp -> openApp(c.name)
            is Cmd.Call -> call(c.target)
            is Cmd.Message -> message(c.name, c.body)
            is Cmd.Home -> home(c.device, c.on)
            is Cmd.Swipe -> { swipeSide(c.toRight); ok }
            is Cmd.Flashlight -> flashlight(c.on)
            is Cmd.Alarm -> {
                val at = clockText(c.hour, c.minute)
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
                "who" -> t("أنا زول، مساعدك الصوتي. أتحكم في الهاتف بصوتك.", "I am Zol, your voice assistant.")
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
                    "ضبط المنبه، وتشغيل أجهزة المنزل. مثلاً قل: ${Commands.wakeWord} افتح الواتساب. أو قل: ${Commands.wakeWord} الأرقام.",
                "I can open apps, call, send messages, type, press buttons, read the screen, set alarms and control " +
                    "home devices. For example say: Zol open WhatsApp. Or say: Zol numbers.")
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
                val cal = java.util.Calendar.getInstance()
                val now = clockText(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                t("الساعة $now", "It is $now")
            }
            is Cmd.PrayerTime -> prayerAnswer(c.prayer)
            is Cmd.Quran -> playQuran(c.surahs, c.reciter)
            is Cmd.QuranCtl -> quranControl(c.what)
            is Cmd.Azkar -> readAzkar(c.kind)
            Cmd.Qibla -> qibla()
            is Cmd.QuranDownload -> downloadQuran(c.surahs, c.reciter)
            Cmd.CheckUpdate -> {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "update")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                t("بشوف لو في تحديث.", "Checking for an update.")
            }
            is Cmd.Board -> if (c.show) showBoard() else { hideBoard(); ok }
            is Cmd.HeadKeyboard -> if (c.show) openHeadKeyboard() else { hideHeadKeyboard(); t("قفلت الكيبورد.", "Keyboard closed.") }
            Cmd.Emergency -> emergency()
            Cmd.Train -> startDialectTraining()
            is Cmd.Head -> if (c.on) startHead() else stopHead()
            Cmd.HeadCenter -> head?.takeIf { it.running }?.let { it.recenter(); t("انظر إلى الأمام لحظة… رجعت المؤشر للوسط.", "Look straight ahead… pointer centred.") }
                ?: t("التحكم بالرأس متوقف. قل: شغّل التحكم بالرأس.", "Head control is off.")
            Cmd.ListReminders -> listReminders()
            Cmd.LongHere -> head?.takeIf { it.running }?.position()?.let { (x, y) -> longPressAt(x, y); t("حاضر.", "Done.") }
                ?: t("شغّل التحكم بالرأس أولاً، ثم وجّه الدائرة وقل: اضغط مطوّل هنا.", "Start head control first.")
            is Cmd.HeadPause -> head?.takeIf { it.running }?.let { it.setPaused(c.on); if (c.on) t("أوقفت الضغط بالرأس. المؤشر يتحرك لكنه لا يضغط.", "Head clicking paused.") else t("رجع الضغط بالرأس.", "Head clicking resumed.") }
                ?: t("التحكم بالرأس متوقف.", "Head control is off.")
            is Cmd.DeleteReminder -> deleteReminder(c.name)
            is Cmd.Teach -> teach(c.phrase, c.meaning)
            Cmd.Hush -> {
                stopFindPhone()
                clipPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
                clipPlayer = null
                val talking = tts?.isSpeaking == true || reading
                if (!talking) quran?.pause()
                stopQibla()
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

    /** A small round button over every app. Tap: listen / stop. Hold: open Zol. Drag to the bottom: remove. */
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
        view.contentDescription = "Zol"
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
                            log("أخفيت الزر العائم. لإرجاعه قل: ${Commands.wakeWord} أظهر الزر.")
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
                log("${Brand.NAME}: $problem")
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
        if (!online() && granted(Manifest.permission.SEND_SMS))
            return ask(t("النت قاطع. أرسل لـ${contact.first} رسالة عادية (SMS): «$body»؟ نعم أو لا.",
                "No internet. Send it to ${contact.first} as a text message instead?")) {
                @Suppress("DEPRECATION")
                val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sms.sendMultipartTextMessage(contact.second, null, sms.divideMessage(body), null, null)
                t("أرسلتها رسالة عادية.", "Sent as a text message.")
            }
        return ask(t("أرسل واتساب إلى ${contact.first}: «$body». نعم أو لا؟", "WhatsApp to ${contact.first}: $body. Yes or no?")) {
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
            return ask(t("أرد على $from بـ: «$body». نعم أو لا؟", "Reply to $from: $body. Yes or no?")) {
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
            ?: return t("ما في رسالة جات قريب أرد عليها. قول: زول رسّل لأحمد قول ليهو جاي، أو افتح المحادثة وقول: رد.",
                "No recent message to reply to. Say: message Ahmed saying I'm coming, or open the chat and say: reply.")
        return ask(t("أرد بـ: «$body». نعم أو لا؟", "Reply: $body. Yes or no?")) {
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
        return t("آخر رسالة: «${texts.last()}»", "Last message: ${texts.last()}")
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
        return t("أبحث عن «$query».", "Searching for $query.")
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
        return ask(t("أرسل إلى ${contact.first}: «$body». نعم أو لا؟", "Send to ${contact.first}: $body. Yes or no?")) {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendMultipartTextMessage(contact.second, null, sms.divideMessage(body), null, null)
            t("أرسلت الرسالة.", "Message sent.")
        }
    }

    /** Alert the caregiver: push notification through ntfy, then a phone call if a number is saved. */
    private fun emergency(message: String = "زول: طلب مساعدة عاجل"): String {
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
                con.outputStream.use { it.write(message.toByteArray()) }
                log(if (con.responseCode in 200..299) "تم إرسال تنبيه الطوارئ." else "فشل إرسال التنبيه (${con.responseCode}).")
                con.disconnect()
            } catch (e: Exception) {
                log("فشل إرسال التنبيه: ${e.message}")
            }
        }.start()
        smsCaregiver("$message!")
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
