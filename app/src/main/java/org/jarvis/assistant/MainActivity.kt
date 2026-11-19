package org.jarvis.assistant

import android.Manifest
import android.app.AlarmManager
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import android.app.AlertDialog
import android.speech.RecognizerIntent
import org.json.JSONObject
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Home screen: the microphone, the conversation, and a settings drawer that slides in from the right. */
@SuppressLint("RtlHardcoded")
class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var chatList: LinearLayout
    private lateinit var statusChip: TextView
    private lateinit var statusDot: View
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var micButton: View
    private lateinit var micRing: View
    private lateinit var micIcon: ImageView
    private lateinit var cardSetup: View
    private var pulsing = false
    private var loading = false                 // settings are being put on screen: do not treat that as the person changing them
    private val prefs get() = getSharedPreferences("jarvis", MODE_PRIVATE)

    /** Text settings: the field that edits each one, and the key it is saved under. */
    private val textSettings = listOf(
        R.id.emergencyNumber to "emergency_number", R.id.ntfyTopic to "ntfy_topic", R.id.homeBase to "home_base",
        R.id.homeKey to "home_key", R.id.homeDevices to "home_devices", R.id.dialectDict to "dialect",
        R.id.scenesBox to "scenes", R.id.secretBox to "secret", R.id.phrasesBox to "phrases", R.id.waCc to "wa_cc",
        R.id.aiUrl to "ai_url", R.id.aiModel to "ai_model", R.id.aiKey to "ai_key",
        R.id.proReminders to "pro_reminders", R.id.proPeople to "pro_people", R.id.proBedtime to "pro_bedtime",
        R.id.prayerCity to "prayer_city",
    )

    /** On/off settings: the switch, its key, and the value when it was never touched. */
    private val switchSettings = listOf(
        Triple(R.id.switchOffline, "offline", true), Triple(R.id.switchTones, "tones", true),
        Triple(R.id.switchReadNotifs, "read_notifs", false), Triple(R.id.switchAiScreen, "ai_screen", false),
        Triple(R.id.switchAgent, "agent_on", false), Triple(R.id.switchEcoLow, "eco_low", false),
        Triple(R.id.switchEcoScreen, "eco_screen", false), Triple(R.id.switchSecret, "secret_on", false),
        Triple(R.id.switchHomeAlerts, "home_alerts", false), Triple(R.id.switchLlm, "llm_on", false),
        Triple(R.id.switchBubble, "bubble", true),
        Triple(R.id.switchPro, "pro_on", false), Triple(R.id.switchProCare, "pro_care", false),
        Triple(R.id.switchProMorning, "pro_morning", true), Triple(R.id.switchProBattery, "pro_battery", true),
        Triple(R.id.switchHeadDwell, "head_dwell_on", true), Triple(R.id.switchHeadBlink, "head_blink", false),
        Triple(R.id.switchHeadInvert, "head_invert", false), Triple(R.id.switchHeadSmile, "head_smile", false),
        Triple(R.id.switchPrayer, "prayer_alert", false), Triple(R.id.switchHeadKb, "head_kb_auto", true),
        Triple(R.id.switchQuranContinue, "quran_continue", true), Triple(R.id.switchAzkar, "azkar_alert", false),
    )

    private val befores = listOf(R.id.beforeNow to 0, R.id.before10 to 10, R.id.before15 to 15)
    private val docks = listOf(R.id.dockLeft to "left", R.id.dockRight to "right", R.id.dockOff to "off")

    private val headSpeeds = listOf(R.id.headSlow to 0.6f, R.id.headNormal to 1f, R.id.headFast to 1.7f)
    private val reciters = listOf(R.id.reciterAfs to "afs", R.id.reciterBasit to "basit", R.id.reciterNourin to "nourin")
    private val kbDwells = listOf(R.id.kbFast to 600L, R.id.kbMid to 900L, R.id.kbSlow to 1300L)
    private val headDwells = listOf(R.id.dwellShort to 1000L, R.id.dwellMid to 1500L, R.id.dwellLong to 2500L)

    private val closeDrawerOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { drawer.closeDrawer(Gravity.RIGHT) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.getString("last_crash", null)?.let { showCrash(it); return }
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawer)
        val home = findViewById<View>(R.id.main)
        val panel = findViewById<View>(R.id.settingsPanel)
        val pad = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            home.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            panel.setPadding(0, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        chatList = findViewById(R.id.chatList)
        statusChip = findViewById(R.id.statusChip)
        statusDot = findViewById(R.id.statusDot)
        micButton = findViewById(R.id.micButton)
        micRing = findViewById(R.id.micRing)
        micIcon = findViewById(R.id.micIcon)
        cardSetup = findViewById(R.id.cardSetup)
        val input = findViewById<EditText>(R.id.input)

        setupChips()
        setupGuide()
        setupDrawer(panel)
        setupSections()
        setupSettings()
        loadSettings()

        findViewById<View>(R.id.btnAccess).setOnClickListener { nextStep()?.action?.invoke() }
        findViewById<View>(R.id.btnClearChat).setOnClickListener { JarvisService.history.clear(); refresh() }
        micButton.setOnClickListener {
            val service = JarvisService.instance
            when {
                service == null -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                !granted(Manifest.permission.RECORD_AUDIO) -> askPermissions()
                service.listening -> service.stopListening()
                else -> service.startListening()
            }
            refresh()
        }
        fun send() {
            val text = input.text.toString().trim()
            val service = JarvisService.instance
            if (service == null) {
                toast("فعّل خدمة زول أولاً")
            } else if (text.isNotEmpty()) {
                JarvisService.history.add("أنت: $text")
                input.setText("")
                service.handleText(text)
                refresh()
            }
        }
        findViewById<View>(R.id.btnSend).setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        askPermissions()
    }

    /**
     * The app stopped last time: show what went wrong, built in code so it works even when the crash was in the
     * main layout. The person can copy it to send to the developer, then open the app normally.
     */
    private fun showCrash(text: String) {
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        box.addView(TextView(this).apply {
            this.text = "توقف ${Brand.NAME} في المرة السابقة بسبب خطأ"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            this.text = "انسخ الخطأ وأرسله للمطوّر حتى يصلحه، ثم اضغط «تشغيل التطبيق»."
            textSize = 14f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, dp(8), 0, dp(12))
        })
        fun button(label: String, action: () -> Unit) = android.widget.Button(this).apply {
            this.text = label
            setOnClickListener { action() }
        }
        box.addView(button("نسخ الخطأ") {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            toast("نُسخ الخطأ")
        })
        box.addView(button("تشغيل التطبيق") {
            prefs.edit().remove("last_crash").commit()
            recreate()
        })
        box.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFE6EDF7.toInt())
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0A1426.toInt()); addView(box) })
    }

    // ---- settings drawer

    private fun drawerOpen() = drawer.isDrawerOpen(Gravity.RIGHT)

    private fun setupDrawer(panel: View) {
        val panelWidth = minOf((resources.displayMetrics.widthPixels * 0.88f).toInt(), dp(420))
        val lp = panel.layoutParams
        lp.width = panelWidth
        panel.layoutParams = lp
        drawer.setScrimColor(ContextCompat.getColor(this, R.color.scrim))
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            private var fresh = false

            /** Zol may have learned sentences by voice since the drawer was last open: show the saved values, not old ones. */
            override fun onDrawerStateChanged(newState: Int) {
                if (newState != DrawerLayout.STATE_IDLE && !fresh && !drawerOpen()) { loadSettings(); fresh = true }
                if (newState == DrawerLayout.STATE_IDLE && !drawerOpen()) fresh = false
            }

            override fun onDrawerOpened(drawerView: View) {
                closeDrawerOnBack.isEnabled = true
                renderDiag()
                renderPrayer()
            }

            override fun onDrawerClosed(drawerView: View) {
                closeDrawerOnBack.isEnabled = false
                saveSettings()                       // nothing typed in the drawer is lost by closing it
                hideKeyboard()
                refresh()
            }
        })
        onBackPressedDispatcher.addCallback(this, closeDrawerOnBack)
        findViewById<View>(R.id.btnSettings).setOnClickListener { hideKeyboard(); drawer.openDrawer(Gravity.RIGHT) }
        findViewById<View>(R.id.btnCloseSettings).setOnClickListener { drawer.closeDrawer(Gravity.RIGHT) }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            saveSettings()
            toast("تم الحفظ")
            drawer.closeDrawer(Gravity.RIGHT)
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
        findViewById<TextView>(R.id.versionText).text = "${Brand.NAME} ${version.orEmpty()} • مفتوح المصدر".replace("  ", " ")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(drawer.windowToken, 0)
        currentFocus?.clearFocus()
    }

    /** Settings are grouped in sections; one is open at a time so the drawer stays short. */
    private fun sectionHeaders(): List<Pair<TextView, View>> {
        val list = findViewById<LinearLayout>(R.id.settingsList)
        val out = ArrayList<Pair<TextView, View>>()
        for (i in 0 until list.childCount - 1) {
            val header = list.getChildAt(i) as? TextView ?: continue
            if (header.tag == "section") out.add(header to list.getChildAt(i + 1))
        }
        return out
    }

    private fun setupSections() {
        for ((header, body) in sectionHeaders())
            header.setOnClickListener { openSection(header, body.visibility != View.VISIBLE) }
    }

    private fun openSection(target: TextView, open: Boolean) {
        for ((header, body) in sectionHeaders()) {
            val show = header === target && open
            body.visibility = if (show) View.VISIBLE else View.GONE
            chevron(header, show)
        }
        if (open) {
            val scroll = findViewById<ScrollView>(R.id.settingsScroll)
            scroll.post { scroll.smoothScrollTo(0, target.top) }
        }
    }

    private fun chevron(view: TextView, open: Boolean) {
        val start = view.compoundDrawablesRelative[0]
        val end = ContextCompat.getDrawable(this, if (open) R.drawable.ic_collapse else R.drawable.ic_expand)
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(start, null, end, null)
    }

    /** Buttons and switches of the drawer. Switches save at once; text fields are saved when the drawer closes. */
    private fun setupSettings() {
        for ((id, key, _) in switchSettings) {
            findViewById<MaterialSwitch>(id).setOnCheckedChangeListener { _, checked ->
                if (loading) return@setOnCheckedChangeListener
                prefs.edit().putBoolean(key, checked).apply()
                if (id == R.id.switchReadNotifs && checked && !notificationAccess()) openNotificationAccess()
                if (id == R.id.switchBubble) JarvisService.instance?.let { if (checked) it.showBubble() else it.hideBubble() }
                if (key.startsWith("prayer_")) { ProactiveReceiver.schedulePrayer(this); renderPrayer() }
                if (key == "azkar_alert") {
                    ProactiveReceiver.scheduleAzkar(this)
                    if (checked && !exactAlarms()) toast("اسمح بالتذكير الدقيق حتى يجي التذكير في وقته")
                }
                if (key.startsWith("pro_")) {
                    saveSettings()
                    if (id == R.id.switchPro && checked && !exactAlarms()) toast("اسمح بالتذكير الدقيق حتى لا تتأخر التذكيرات")
                }
                renderDiag()
            }
        }
        findViewById<MaterialSwitch>(R.id.switchWhisper).setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            prefs.edit().putString("engine", if (checked) "whisper" else "google").apply()
            JarvisService.instance?.resetWhisper()
        }
        findViewById<MaterialButtonToggleGroup>(R.id.langGroup).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !loading) prefs.edit().putString("lang", if (checkedId == R.id.btnAr) "ar-SA" else "en-US").apply()
        }
        val aiUrl = findViewById<EditText>(R.id.aiUrl)
        val aiModel = findViewById<EditText>(R.id.aiModel)
        val aiKey = findViewById<EditText>(R.id.aiKey)
        findViewById<View>(R.id.btnPresetGemini).setOnClickListener {
            aiUrl.setText("https://generativelanguage.googleapis.com/v1beta/openai"); aiModel.setText("gemini-2.5-flash")
            aiKey.requestFocus()
            toast("ضع مفتاح Gemini في خانة المفتاح ثم احفظ")
        }
        findViewById<View>(R.id.btnPresetGroq).setOnClickListener {
            aiUrl.setText("https://api.groq.com/openai/v1"); aiModel.setText("llama-3.3-70b-versatile")
            aiKey.requestFocus()
            toast("ضع مفتاح Groq في خانة المفتاح ثم احفظ")
        }
        findViewById<View>(R.id.btnPresetOllama).setOnClickListener {
            aiUrl.setText("http://192.168.1.10:11434/v1"); aiModel.setText("qwen3:1.7b"); aiKey.setText("")
            toast("عدّل عنوان الكمبيوتر ثم احفظ")
        }
        findViewById<View>(R.id.btnOffline).setOnClickListener {
            val service = JarvisService.instance
            if (service == null) toast("فعّل خدمة زول أولاً") else { service.downloadOfflinePack(); toast("يتم فحص حزمة الصوت، راقب المحادثة") }
        }
        findViewById<View>(R.id.btnNotifAccess).setOnClickListener { openNotificationAccess() }
        findViewById<View>(R.id.btnTrain).setOnClickListener { startTraining() }
        findViewById<View>(R.id.btnDialectTrain).setOnClickListener {
            val service = JarvisService.instance
            if (service == null) { toast("فعّل خدمة زول أولاً"); return@setOnClickListener }
            if (!service.listening) service.startListening()
            drawer.closeDrawer(Gravity.RIGHT)
            JarvisService.history.add("أنت: دربني")
            service.handleText("دربني")
            refresh()
        }
        findViewById<View>(R.id.btnSettingsExport).setOnClickListener { saveSettings(); exportSettingsFile.launch("zol-settings.json") }
        findViewById<View>(R.id.btnSettingsImport).setOnClickListener { importSettingsFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
        findViewById<View>(R.id.btnLlmPick).setOnClickListener { pickLlm.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btnExport).setOnClickListener { saveSettings(); exportLearning() }
        findViewById<View>(R.id.btnWhisperFolder).setOnClickListener { pickWhisperFolder.launch(null) }
        findViewById<View>(R.id.btnWhisperCheck).setOnClickListener {
            val service = JarvisService.instance
            if (service == null) toast("فعّل خدمة زول أولاً") else {
                val line = service.whisperStatus()
                JarvisService.history.add("${Brand.NAME}: $line")
                toast(line)
                refresh()
            }
        }
        findViewById<View>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<View>(R.id.btnExactAlarm).setOnClickListener { openExactAlarms() }
        findViewById<View>(R.id.btnHead).setOnClickListener { toggleHead() }
        findViewById<MaterialButtonToggleGroup>(R.id.headSpeedGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !loading) headSpeeds.firstOrNull { it.first == id }?.let { prefs.edit().putFloat("head_speed", it.second).apply() }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.styleGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !loading) prefs.edit().putString("reply_style", if (id == R.id.styleFusha) "fusha" else "sudani").apply()
        }
        findViewById<MaterialButtonToggleGroup>(R.id.prayerBeforeGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !loading) befores.firstOrNull { it.first == id }?.let {
                prefs.edit().putInt("prayer_before", it.second).apply()
                ProactiveReceiver.schedulePrayer(this)
            }
        }
        findViewById<View>(R.id.btnPrayerLocation).setOnClickListener { usePhoneLocation() }
        findViewById<MaterialButtonToggleGroup>(R.id.reciterGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !loading) reciters.firstOrNull { it.first == id }?.let { prefs.edit().putString("quran_reciter", it.second).apply() }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.kbDwellGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !loading) kbDwells.firstOrNull { it.first == id }?.let { prefs.edit().putLong("head_kb_dwell", it.second).apply() }
        }
        findViewById<View>(R.id.btnAzkarMorning).setOnClickListener { readAzkar("morning") }
        findViewById<View>(R.id.btnAzkarEvening).setOnClickListener { readAzkar("evening") }
        findViewById<MaterialButtonToggleGroup>(R.id.headDockGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !loading) docks.firstOrNull { it.first == id }?.let { prefs.edit().putString("head_dock", it.second).apply() }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.wakeGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked || loading) return@addOnButtonCheckedListener
            val custom = findViewById<View>(R.id.wakeCustomBox)
            custom.visibility = if (id == R.id.wakeCustom) View.VISIBLE else View.GONE
            when (id) {
                R.id.wakeZol -> setWake("زول")
                R.id.wakeJohnny -> setWake("جوني")
                else -> findViewById<EditText>(R.id.wakeCustomText).requestFocus()
            }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.headDwellGroup).addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked && !loading) headDwells.firstOrNull { it.first == id }?.let { prefs.edit().putLong("head_dwell", it.second).apply() }
        }
    }

    /** Put what is saved on screen. */
    private fun loadSettings() {
        loading = true
        for ((id, key) in textSettings)
            findViewById<EditText>(id).setText(prefs.getString(key, if (key == "wa_cc") "249" else ""))
        for ((id, key, default) in switchSettings)
            findViewById<MaterialSwitch>(id).isChecked = prefs.getBoolean(key, default)
        findViewById<MaterialSwitch>(R.id.switchWhisper).isChecked = prefs.getString("engine", "google") == "whisper"
        findViewById<MaterialButtonToggleGroup>(R.id.langGroup)
            .check(if (prefs.getString("lang", "ar-SA") == "ar-SA") R.id.btnAr else R.id.btnEn)
        findViewById<MaterialButtonToggleGroup>(R.id.styleGroup)
            .check(if (prefs.getString("reply_style", "sudani") == "fusha") R.id.styleFusha else R.id.styleSudani)
        findViewById<MaterialButtonToggleGroup>(R.id.prayerBeforeGroup)
            .check(befores.firstOrNull { it.second == prefs.getInt("prayer_before", 0) }?.first ?: R.id.beforeNow)
        findViewById<MaterialButtonToggleGroup>(R.id.reciterGroup)
            .check(reciters.firstOrNull { it.second == prefs.getString("quran_reciter", Quran.DEFAULT_RECITER) }?.first ?: R.id.reciterAfs)
        val kbDwell = prefs.getLong("head_kb_dwell", 900L)
        findViewById<MaterialButtonToggleGroup>(R.id.kbDwellGroup).check(kbDwells.minBy { kotlin.math.abs(it.second - kbDwell) }.first)
        findViewById<MaterialButtonToggleGroup>(R.id.headDockGroup)
            .check(docks.firstOrNull { it.second == prefs.getString("head_dock", "left") }?.first ?: R.id.dockLeft)
        val wake = prefs.getString("wake_word", "زول").orEmpty()
        Commands.setWake(wake)
        val wakeId = when (Commands.wakeWord) { "زول" -> R.id.wakeZol; "جوني" -> R.id.wakeJohnny; else -> R.id.wakeCustom }
        findViewById<MaterialButtonToggleGroup>(R.id.wakeGroup).check(wakeId)
        findViewById<View>(R.id.wakeCustomBox).visibility = if (wakeId == R.id.wakeCustom) View.VISIBLE else View.GONE
        findViewById<EditText>(R.id.wakeCustomText).setText(if (wakeId == R.id.wakeCustom) wake else "")
        findViewById<View>(R.id.wakeNote).visibility = if (Commands.wakeWord == "زول") View.VISIBLE else View.GONE
        val speed = prefs.getFloat("head_speed", 1f)
        findViewById<MaterialButtonToggleGroup>(R.id.headSpeedGroup).check(headSpeeds.minBy { kotlin.math.abs(it.second - speed) }.first)
        val dwell = prefs.getLong("head_dwell", 1500L)
        findViewById<MaterialButtonToggleGroup>(R.id.headDwellGroup).check(headDwells.minBy { kotlin.math.abs(it.second - dwell) }.first)
        loading = false
        renderHeadButton()
        renderPrayer()
    }

    private fun saveSettings() {
        val typedCity = findViewById<EditText>(R.id.prayerCity).text.toString().trim()
        if (typedCity != prefs.getString("prayer_city", "") && typedCity != "موقعك")
            prefs.edit().remove("prayer_lat").remove("prayer_lon").apply()
        val edit = prefs.edit()
        for ((id, key) in textSettings) edit.putString(key, findViewById<EditText>(id).text.toString().trim())
        edit.apply()
        if (findViewById<MaterialButtonToggleGroup>(R.id.wakeGroup).checkedButtonId == R.id.wakeCustom) {
            val word = findViewById<EditText>(R.id.wakeCustomText).text.toString().trim()
            if (word.isNotEmpty()) setWake(word)
        }
        ProactiveReceiver.schedule(this)
    }

    /** Save the wake word and use it at once, here and in the service (they share [Commands]). */
    private fun setWake(word: String) {
        val clean = word.trim().split(Regex("\\s+")).take(2).joinToString(" ")
        prefs.edit().putString("wake_word", clean).apply()
        Commands.setWake(clean)
        findViewById<TextView>(R.id.wakeNote).visibility = if (Commands.wakeWord == "زول") View.VISIBLE else View.GONE
        if (!loading) refresh()
    }

    // ---- prayer times

    /** Today's times for the chosen place, shown at the top of the prayer section. */
    private fun renderPrayer() {
        val place = ProactiveReceiver.prayerPlace(this)
        val typed = prefs.getString("prayer_city", "").orEmpty()
        val known = typed.isEmpty() || typed == "موقعك" || PrayerTimes.city(typed) != null || !prefs.getFloat("prayer_lat", Float.NaN).isNaN()
        val times = PrayerTimes.forDay(java.util.Calendar.getInstance(), place.lat, place.lon)
        val line = PrayerTimes.FIVE.joinToString("  •  ") { p ->
            val m = times.getValue(p); "${p.ar} " + String.format(java.util.Locale.US, "%d:%02d", m / 60, m % 60)
        }
        val qibla = Qibla.bearing(place.lat, place.lon)
        findViewById<TextView>(R.id.prayerToday).text =
            (if (known) "" else "ما لقيت «$typed»، فاستعملت الخرطوم. ") + "${place.name} الليلة:\n$line" +
                "\nالقبلة: ${Qibla.degrees(qibla)}° من الشمال (${Qibla.compassWord(qibla)})"
    }

    private fun readAzkar(kind: String) {
        val service = JarvisService.instance
        if (service == null) { toast("شغّل خدمة زول أولاً"); return }
        service.readAzkarNow(kind)
        drawer.closeDrawer(Gravity.RIGHT)
    }

    /** The phone's last known place, so prayer times fit where the person actually is. */
    @SuppressLint("MissingPermission")
    private fun usePhoneLocation() {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val loc = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null) { toast("ما لقيت موقعك. شغّل الموقع (GPS) لحظة وجرّب تاني، أو اكتب اسم المدينة."); return }
        prefs.edit().putFloat("prayer_lat", loc.latitude.toFloat()).putFloat("prayer_lon", loc.longitude.toFloat())
            .putString("prayer_city", "موقعك").apply()
        loading = true
        findViewById<EditText>(R.id.prayerCity).setText("موقعك")
        loading = false
        ProactiveReceiver.schedulePrayer(this)
        renderPrayer()
        toast("تمام، أوقات الصلاة حسب موقعك")
    }

    // ---- proactive assistant and head pointer

    private fun exactAlarms(): Boolean =
        Build.VERSION.SDK_INT < 31 || (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    private fun openExactAlarms() {
        if (exactAlarms()) { toast("مسموح بالفعل"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun renderHeadButton() {
        val on = JarvisService.instance?.headRunning() == true
        findViewById<TextView>(R.id.btnHead).text = if (on) "أوقف التحكم بالرأس" else "ابدأ التحكم بالرأس"
    }

    /** Started from here, while the app is on screen, so Android allows the camera. */
    private fun toggleHead() {
        val service = JarvisService.instance
        if (service == null) { toast("فعّل خدمة زول أولاً"); return }
        if (service.headRunning()) {
            note(service.stopHead())
        } else if (!granted(Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        } else {
            note(service.startHead())
            drawer.closeDrawer(Gravity.RIGHT)
        }
        renderHeadButton()
        refresh()
    }

    private fun note(line: String) {
        JarvisService.history.add("${Brand.NAME}: $line")
        toast(line)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) usePhoneLocation()
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleHead()
            else toast("بدون إذن الكاميرا لا يعمل التحكم بالرأس")
        }
    }

    // ---- system check, shown at the top of the drawer: green works, red needs a tap, grey is optional and off

    private class Check(val title: String, val ok: Boolean?, val detail: String, val fix: (() -> Unit)? = null)

    private fun checks(): List<Check> {
        val service = JarvisService.instance
        val readNotifs = prefs.getBoolean("read_notifs", false)
        val model = prefs.getString("ai_model", "").orEmpty().trim()
        val aiOn = model.isNotEmpty() && prefs.getString("ai_url", "").orEmpty().isNotBlank()
        val homeOn = prefs.getString("home_base", "").orEmpty().isNotBlank()
        return listOf(
            Check("خدمة زول", service != null, if (service != null) "تعمل" else "فعّلها") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Check("الميكروفون", granted(Manifest.permission.RECORD_AUDIO),
                if (granted(Manifest.permission.RECORD_AUDIO)) "مسموح" else "امنح الإذن") { askPermissions() },
            Check("الاتصال والرسائل", allPermissions(), if (allPermissions()) "مسموح" else "امنح الأذونات") { askPermissions() },
            Check("العمل في الخلفية", batteryOk(), if (batteryOk()) "مسموح" else "اسمح") { requestBatteryExemption() },
            voiceCheck(service),
            Check("قراءة الإشعارات", if (readNotifs) notificationAccess() else null,
                when { !readNotifs -> "متوقفة"; notificationAccess() -> "تعمل"; else -> "امنح الإذن" }) { openNotificationAccess() },
            Check("الذكاء السحابي", if (aiOn) true else null, if (aiOn) model else "غير مضبوط") {
                openSection(findViewById(R.id.secAi), true)
            },
            Check("المساعد الاستباقي", if (!prefs.getBoolean("pro_on", false)) null else exactAlarms(),
                when { !prefs.getBoolean("pro_on", false) -> "متوقف"; exactAlarms() -> "يعمل"; else -> "اسمح بالتذكير الدقيق" }) {
                if (prefs.getBoolean("pro_on", false)) openExactAlarms() else openSection(findViewById(R.id.secPro), true)
            },
            Check("التحكم بالرأس", if (service?.headRunning() == true) true else null,
                if (service?.headRunning() == true) "يعمل" else "متوقف") { openSection(findViewById(R.id.secHead), true) },
            Check("المنزل الذكي", if (homeOn) true else null, if (homeOn) "مضبوط" else "غير مضبوط") {
                openSection(findViewById(R.id.secHome), true)
            },
        )
    }

    /** Row 5: can Zol actually speak Arabic? An engine with no Arabic voice "speaks" silence. */
    private fun voiceCheck(service: JarvisService?): Check {
        val (ok, detail) = when {
            service == null -> null to "—"
            !service.voiceReady -> false to "المحرك لا يعمل"
            service.arabicVoice == false -> false to "نزّل صوت العربية"
            service.arabicVoice == null -> null to "يجري الفحص"
            else -> true to "جاهز"
        }
        return Check("صوت النطق", ok, detail) { openVoiceSettings(service?.voiceReady == true) }
    }

    private fun openVoiceSettings(engineWorks: Boolean) {
        val tries = listOfNotNull(
            if (engineWorks) Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA) else null,
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); toast("اختر العربية ونزّل صوتها"); return } catch (_: Exception) {}
        }
    }

    private fun renderDiag() {
        if (!drawerOpen()) return
        val box = findViewById<LinearLayout>(R.id.diagList)
        box.removeAllViews()
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        for (check in checks()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = dp(40)
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@MainActivity, when (check.ok) {
                    true -> R.color.success; false -> R.color.danger; else -> R.color.muted
                }))
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
            val title = TextView(this)
            title.text = check.title
            title.textSize = 14f
            title.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            val detail = TextView(this)
            detail.text = check.detail
            detail.textSize = 12f
            detail.maxLines = 1
            detail.maxWidth = dp(150)
            detail.ellipsize = android.text.TextUtils.TruncateAt.END
            val fix = check.fix
            val needsTap = fix != null && check.ok != true
            detail.setTextColor(ContextCompat.getColor(this, if (needsTap) R.color.primary else R.color.muted))
            row.addView(detail)
            if (needsTap) {
                row.isClickable = true
                row.isFocusable = true
                row.setBackgroundResource(ripple)
                row.contentDescription = "${check.title}: ${check.detail}"
                row.setOnClickListener { fix() }
            }
            box.addView(row)
        }
    }

    /** Android puts the switches back after a rotation; that is not the person flipping them. */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        loading = true
        super.onRestoreInstanceState(savedInstanceState)
        loading = false
    }

    override fun onResume() {
        super.onResume()
        JarvisService.onLog = { refresh() }
        loading = true                                   // a spoken "أخفِ الزر" may have changed it while we were away
        findViewById<MaterialSwitch>(R.id.switchBubble).isChecked = prefs.getBoolean("bubble", true)
        loading = false
        drawer.post { closeDrawerOnBack.isEnabled = drawerOpen() }
        renderHeadButton()
        refresh()
    }

    override fun onPause() {
        JarvisService.onLog = null
        pulsing = false
        micRing.animate().cancel()
        ui.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun refresh() {
        val service = JarvisService.instance
        val on = service?.listening == true
        renderSteps()
        val awake = service?.awake == true
        status.text = when {
            service == null -> "الخدمة غير مفعّلة"
            awake -> "نعم؟ تفضل بالأمر"
            on -> "أستمع إليك"
            else -> "الاستماع متوقف"
        }
        hint.text = when {
            service == null -> "فعّل زول من البطاقة بالأسفل"
            awake -> "قل الأمر الآن، لا تحتاج لقول ${Commands.wakeWord}"
            on -> "قل «${Commands.wakeWord}» وانتظر «نعم» ثم قل الأمر، أو قلهما معاً"
            else -> "اضغط الميكروفون للبدء"
        }
        statusChip.text = when {
            service == null -> "متوقف"
            awake -> "بانتظار الأمر"
            on -> "يستمع"
            else -> "جاهز"
        }
        val dotColor = ContextCompat.getColor(this, when {
            service == null -> R.color.danger
            awake -> R.color.success
            on -> R.color.primary
            else -> R.color.muted
        })
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
        ui.removeCallbacksAndMessages(null)
        if (awake) ui.postDelayed({ refresh() }, 12_300)
        micButton.setBackgroundResource(if (on) R.drawable.bg_mic_on else R.drawable.bg_mic_off)
        micIcon.setColorFilter(ContextCompat.getColor(this, if (on) R.color.on_primary else R.color.muted))
        renderChat(JarvisService.history.takeLast(12))
        renderLearn()
        renderDiag()
        if (on && !pulsing) { pulsing = true; pulse() } else if (!on) pulsing = false
    }

    // ---- commands guide (folded card on the home screen)

    private val guide = listOf(
        "التنقل في الهاتف" to listOf("افتح الواتساب", "ارجع • الرئيسية • انزل • اطلع", "اقرأ الشاشة ← ثم: اضغط على اسم الزر",
            "اضغط على إرسال", "الأرقام ← ثم قل رقم الزر", "الشبكة ← ثم قل رقم المربع مرتين"),
        "الاتصال والرسائل" to listOf("اتصل بأحمد", "أرسل رسالة لأحمد تقول أنا في الطريق", "ارسل لأحمد واتساب تقول وصلت",
            "افتح محادثة أحمد", "اقرأ آخر رسالة ← رد بشكراً", "أجب • ارفض • أنهِ المكالمة • شغّل المكبر", "اقرأ الإشعارات"),
        "الكتابة" to listOf("اكتب السلام عليكم", "ابدأ الإملاء ← تكلم ← انتهى الإملاء", "نقطة • فاصلة • سطر جديد  (أثناء الإملاء)",
            "امسح الكلمة الأخيرة", "غيّر سلام إلى مرحبا"),
        "المنزل" to listOf("ولّع النور • طفّي المروحة", "حالة المروحة • هل الباب مفتوح", "مشهد النوم"),
        "الوقت والتذكير" to listOf("صحّيني الساعة 7 ونص", "نبهني بعد 10 دقائق", "ذكرني بالدواء كل يوم الساعة 8 مساء",
            "كم الساعة • البطارية"),
        "الذكاء" to listOf("لخّص الرسالة • اسأل كم السعر", "نفّذ أرسل لأحمد أنني تأخرت عشر دقائق", "تذكّر أن أمي هي فاطمة ← اتصل بأمي"),
        "التحكم بالرأس" to listOf("شغّل التحكم بالرأس ← حرّك رأسك وثبّت الدائرة لتضغط",
            "ثبّت على ▲ ▼ في الشريط للتمرير، وعلى ↩ للرجوع", "اضغط هنا • اضغط مطوّل هنا • وسّط المؤشر",
            "وقّف الضغط • شغّل الضغط  (المؤشر يتحرك دون ضغط)", "وقّف التحكم بالرأس"),
        "الكتابة بالراس" to listOf("اختار خانة الكتابة بالدائرة ← يطلع كيبورد كبير", "ثبّت على الحرف لتكتبه، وفوق في اقتراحات كلمات",
            "اكتب بالراس • اقفل الكيبورد"),
        "القرآن والأذكار" to listOf("شغّل سورة الكهف • سورة يس بصوت نورين", "شغّل المعوذات • السورة الجاية • وقّف القرآن",
            "أذكار الصباح • أذكار المسا • أذكار النوم", "وين القبلة؟ ← خلّي التلفون مسطّح وهو بوجهك"),
        "الصلاة" to listOf("متين المغرب؟ • كم فاضل للعصر", "الصلاة الجاية • مواقيت الصلاة"),
        "الوقت بالسوداني" to listOf("صحيني سبعة إلا ربع • صحيني ستة وتلت الصباح", "ذكرني بالدوا كل يوم بعد العشا",
            "نبهني بعد تلت ساعة • بعد ساعة ونص"),
        "التذكير والمبادرة" to listOf("ذكرني بالدواء كل يوم الساعة 8 مساء ← يسألك: هل فعلتها؟", "شنو تذكيراتي • امسح تذكير الدواء"),
        "تعليم زول لهجتك" to listOf("دربني ← يقول لك أمراً فتقوله بطريقتك", "لما أقول جيب الواتس يعني افتح الواتساب",
            "اسكت • خلاص  (يوقف الكلام)", "سكّت الصوت • رجّع الصوت"),
        "أخرى" to listOf("ايقاف مؤقت • الأغنية التالية • التقط صورة", "شغّل الكشاف • علّي الصوت • وطّي الصوت",
            "وضع السيارة • وضع الليل", "افتح الواتساب ثم انزل  (أوامر متتالية)", "كرر • أبلغ المرافق • صباح الخير", "أخفِ الزر • أظهر الزر"),
        "الطوارئ" to listOf("ساعدوني ← دون كلمة التنبيه"),
    )

    private fun setupGuide() {
        val header = findViewById<TextView>(R.id.guideHeader)
        val body = findViewById<LinearLayout>(R.id.guideBody)
        body.addView(TextView(this).apply {
            text = "ابدأ كل أمر بكلمة «${Commands.wakeWord}»."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
        })
        for ((title, lines) in guide) {
            body.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                setPadding(0, dp(14), 0, dp(2))
            })
            for (line in lines) body.addView(TextView(this).apply {
                text = line
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
                setPadding(0, dp(5), 0, dp(5))
            })
        }
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron(header, open)
            if (open) {
                val scroll = findViewById<ScrollView>(R.id.homeScroll)
                scroll.post { scroll.smoothScrollTo(0, (header.parent.parent as View).top) }
            }
        }
    }

    /** The person picks the folder that holds the Whisper files (any folder on the phone); they are copied into the app. */
    private val pickWhisperFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            toast("أنسخ ملفات Whisper، قد يستغرق دقيقة...")
            Thread {
                val result = try { importWhisperFiles(tree) } catch (e: Exception) { "تعذر النسخ: ${e.message}" }
                runOnUiThread {
                    JarvisService.instance?.resetWhisper()
                    JarvisService.history.add("${Brand.NAME}: $result")
                    toast(result)
                    refresh()
                }
            }.start()
        }
    }

    /** The person picks the small language model file; it is copied into the app (no download happens here). */
    private val pickLlm = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast("أنسخ النموذج، قد يستغرق دقائق...")
            Thread {
                val result = try {
                    var name = "model"
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0)
                    }
                    val dir = File(getExternalFilesDir(null), "llm").apply { mkdirs() }
                    val target = File(dir, "model.task")
                    contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    prefs.edit().putString("llm_name", name).putBoolean("llm_on", true).apply()
                    if (target.length() < 1_000_000) "الملف صغير جداً، تأكد أنه ملف .task" else "نسخت النموذج ($name). فعّلته."
                } catch (e: Exception) {
                    "تعذر النسخ: ${e.message}"
                }
                runOnUiThread {
                    findViewById<MaterialSwitch>(R.id.switchLlm).isChecked = prefs.getBoolean("llm_on", false)
                    JarvisService.history.add("${Brand.NAME}: $result")
                    toast(result)
                    refresh()
                }
            }.start()
        }
    }

    /** Shares what the person taught Zol (sentences, dialect words, unknown sentences) so the dialect can be improved. */
    private fun exportLearning() {
        val text = buildString {
            appendLine("زول - بيانات تحسين اللهجة")
            appendLine("الهاتف: ${Build.MANUFACTURER} ${Build.MODEL} / أندرويد ${Build.VERSION.RELEASE}")
            appendLine()
            appendLine("## جمل علّمتها (جملة=أمر)")
            appendLine(prefs.getString("phrases", "").orEmpty())
            appendLine()
            appendLine("## قاموس اللهجة")
            appendLine(prefs.getString("dialect", "").orEmpty())
            appendLine()
            appendLine("## جمل لم يفهمها")
            appendLine(prefs.getString("unknown", "").orEmpty())
            appendLine()
            appendLine("## نتائج التدريب (المقصود|ما سمعه)")
            appendLine(prefs.getString("train_log", "").orEmpty())
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "بيانات تحسين زول").putExtra(Intent.EXTRA_TEXT, text), "شارك البيانات"))
    }

    // ---- training: the person reads sentences, Zol compares what the phone heard and learns the differences

    private val trainSentences = listOf(
        "افتح الواتساب", "ارجع", "انزل", "اتصل بأحمد", "شغل الكشاف", "اقرأ الشاشة", "الأرقام", "اطلع",
        "الرئيسية", "ولع الضو", "طفي المروحة", "كم الساعة", "علي الصوت", "اقرأ الإشعارات", "أجب",
    )
    private var trainIndex = 0
    private val trainLog = ArrayList<String>()

    private val trainLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            trainLog.add(trainSentences[trainIndex] + "|" + heard)
        }
        trainIndex++
        nextTrain()
    }

    private fun startTraining() {
        trainIndex = 0
        trainLog.clear()
        nextTrain()
    }

    private fun nextTrain() {
        if (trainIndex >= trainSentences.size) { finishTraining(); return }
        AlertDialog.Builder(this)
            .setTitle("جملة ${trainIndex + 1} من ${trainSentences.size}")
            .setMessage("اضغط «تكلم» ثم قل:\n\n«${trainSentences[trainIndex]}»")
            .setCancelable(false)
            .setPositiveButton("تكلم") { _, _ ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
                try { trainLauncher.launch(intent) } catch (e: Exception) { toast("التعرف على الكلام غير متوفر"); finishTraining() }
            }
            .setNegativeButton("تخطّ") { _, _ -> trainIndex++; nextTrain() }
            .setNeutralButton("إنهاء") { _, _ -> finishTraining() }
            .show()
    }

    private fun finishTraining() {
        var right = 0
        var learned = 0
        val phrases = StringBuilder(prefs.getString("phrases", "").orEmpty().trim())
        for (entry in trainLog) {
            val intended = entry.substringBefore("|")
            val heard = entry.substringAfter("|")
            if (Commands.normalize(heard).trim() == Commands.normalize(intended).trim()) { right++; continue }
            val want = Commands.parseAll(intended)
            if (heard.isNotBlank() && want.isNotEmpty() && Commands.parseAll(heard) != want && !phrases.contains("$heard=")) {
                if (phrases.isNotEmpty()) phrases.append('\n')
                phrases.append("$heard=$intended")
                learned++
            }
        }
        val old = prefs.getString("train_log", "").orEmpty().lines().filter { it.isNotBlank() }
        prefs.edit()
            .putString("phrases", phrases.toString())
            .putString("train_log", (trainLog + old).take(200).joinToString("\n"))
            .apply()
        findViewById<EditText>(R.id.phrasesBox).setText(phrases.toString())
        val total = trainLog.size
        AlertDialog.Builder(this)
            .setTitle("انتهى التدريب")
            .setMessage(if (total == 0) "لم أسجّل شيئاً." else "فهم الهاتف $right من $total جملة بدقة (${right * 100 / total}%).\nتعلّمتُ $learned صيغة جديدة من أخطائه.")
            .setPositiveButton("حسناً", null)
            .show()
    }

    // ---- settings file: save everything to one file, restore it on another phone

    private val settingKeys = listOf("dialect", "phrases", "scenes", "home_base", "home_key", "home_devices", "wa_cc",
        "emergency_number", "ntfy_topic", "tones", "read_notifs", "home_alerts", "eco_low", "eco_screen", "secret_on",
        "secret", "mode", "bubble", "ai_url", "ai_model", "ai_screen", "agent_on", "memory", "aliases",
        "pro_on", "pro_reminders", "pro_people", "pro_care", "pro_morning", "pro_battery", "pro_bedtime",
        "head_speed", "head_dwell", "head_dwell_on", "head_blink", "head_invert", "head_smile", "head_dock", "wake_word",
        "reply_style", "prayer_alert", "prayer_before", "prayer_city", "prayer_lat", "prayer_lon",
        "head_kb_auto", "head_kb_dwell", "quran_reciter", "quran_continue", "azkar_alert")

    private val exportSettingsFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val json = JSONObject()
                for (key in settingKeys) if (prefs.contains(key)) json.put(key, prefs.all[key])
                contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
                toast("حفظت الإعدادات. احتفظ بالملف في مكان آمن لأنه يحوي كلمات السر.")
            } catch (e: Exception) {
                toast("تعذر الحفظ: ${e.message}")
            }
        }
    }

    private val importSettingsFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val json = JSONObject(contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty())
                val edit = prefs.edit()
                var count = 0
                for (key in settingKeys) {
                    if (!json.has(key)) continue
                    when (val value = json.get(key)) {
                        is Boolean -> edit.putBoolean(key, value)
                        is String -> edit.putString(key, value)
                        is Number -> when (key) {
                            "head_speed", "prayer_lat", "prayer_lon" -> edit.putFloat(key, value.toFloat())
                            "prayer_before" -> edit.putInt(key, value.toInt())
                            "head_dwell", "head_kb_dwell" -> edit.putLong(key, value.toLong())
                            else -> continue
                        }
                        else -> continue
                    }
                    count++
                }
                edit.apply()
                loadSettings()
                JarvisService.instance?.let { if (prefs.getBoolean("bubble", true)) it.showBubble() else it.hideBubble() }
                ProactiveReceiver.schedule(this)
                toast("استعدت $count إعداداً.")
                refresh()
            } catch (e: Exception) {
                toast("الملف غير صالح: ${e.message}")
            }
        }
    }

    private fun importWhisperFiles(tree: Uri): String {
        val target = File(getExternalFilesDir(null), "whisper").apply { mkdirs() }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = ArrayList<Pair<String, String>>()
        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) files.add(c.getString(0) to c.getString(1))
        }
        val hasInt8 = files.any { it.second.endsWith(".int8.onnx") }
        var copied = 0
        for ((id, name) in files) {
            val wanted = name == "silero_vad.onnx" || name.endsWith("tokens.txt") ||
                (hasInt8 && name.endsWith(".int8.onnx")) || (!hasInt8 && name.endsWith(".onnx"))
            if (!wanted) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            contentResolver.openInputStream(uri)?.use { input ->
                File(target, name).outputStream().use { input.copyTo(it) }
            }
            copied++
        }
        return if (copied == 0) "لم أجد ملفات Whisper في هذا المجلد." else "نسخت $copied ملفات. الآن فعّل مفتاح Whisper."
    }

    private class Step(val title: String, val done: Boolean, val button: String, val action: () -> Unit)

    private fun batteryOk() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun allPermissions() = listOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS,
    ).all { granted(it) } && (Build.VERSION.SDK_INT < 26 || granted(Manifest.permission.ANSWER_PHONE_CALLS))

    /** The first-run checklist: each step ticks itself off when it is really done. */
    private fun steps(): List<Step> {
        val service = JarvisService.instance
        return listOf(
            Step("تفعيل خدمة زول في إمكانية الوصول", service != null, "فتح إعدادات إمكانية الوصول") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Step("أذونات الميكروفون والاتصال والرسائل", allPermissions(), "منح الأذونات") { askPermissions() },
            Step("البقاء يعمل في الخلفية (استثناء البطارية)", batteryOk(), "السماح بالعمل في الخلفية") { requestBatteryExemption() },
            Step("حزمة الصوت العربية للعمل دون إنترنت (اختياري)",
                prefs.getBoolean("offline_ready", false) || prefs.getBoolean("offline_checked", false), "فحص حزمة الصوت") {
                val svc = JarvisService.instance
                if (svc == null) toast("فعّل خدمة زول أولاً") else {
                    prefs.edit().putBoolean("offline_checked", true).apply()
                    svc.downloadOfflinePack()
                }
            },
            Step("جرّب: قل «${Commands.wakeWord}» ثم «افتح الإعدادات»", prefs.getBoolean("tested", false), "ابدأ الاستماع") {
                val svc = JarvisService.instance
                if (svc == null) toast("فعّل خدمة زول أولاً") else if (!svc.listening) svc.startListening()
            },
        )
    }

    private fun nextStep() = steps().firstOrNull { !it.done }

    private fun renderSteps() {
        val list = steps()
        val next = list.firstOrNull { !it.done }
        cardSetup.visibility = if (next == null) View.GONE else View.VISIBLE
        val box = findViewById<LinearLayout>(R.id.stepsList)
        box.removeAllViews()
        for ((i, step) in list.withIndex()) {
            val tv = TextView(this)
            tv.text = (if (step.done) "✓  " else "${i + 1}.  ") + step.title
            tv.textSize = 14f
            tv.alpha = if (step.done) 0.7f else 1f
            tv.setPadding(0, dp(4), 0, dp(4))
            tv.setTextColor(ContextCompat.getColor(this, if (step.done) R.color.muted else R.color.on_primary_container))
            box.addView(tv)
        }
        findViewById<TextView>(R.id.btnAccess).text = next?.button ?: ""
        val done = list.count { it.done }
        findViewById<TextView>(R.id.stepsCount).text = "$done من ${list.size}"
        findViewById<LinearProgressIndicator>(R.id.stepsProgress).setProgressCompat(done * 100 / list.size, true)
    }

    private fun notificationAccess() =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openNotificationAccess() {
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { toast("افتح إعدادات الإشعارات يدوياً") }
    }

    /** Sentences Zol did not understand: tap one and write the command it should have understood. */
    private fun renderLearn() {
        val card = findViewById<View>(R.id.cardLearn)
        val box = findViewById<LinearLayout>(R.id.learnList)
        val unknown = prefs.getString("unknown", "").orEmpty().lines().filter { it.isNotBlank() }.take(8)
        card.visibility = if (unknown.isEmpty()) View.GONE else View.VISIBLE
        box.removeAllViews()
        for (sentence in unknown) {
            val row = TextView(this)
            row.text = "«$sentence»"
            row.textSize = 14f
            row.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            row.setBackgroundResource(R.drawable.bg_chip)
            row.setPadding(dp(14), dp(10), dp(14), dp(10))
            row.setOnClickListener { teach(sentence) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            box.addView(row, lp)
        }
    }

    private fun teach(sentence: String) {
        val input = EditText(this)
        input.hint = "مثال: افتح الواتساب"
        AlertDialog.Builder(this)
            .setTitle("ماذا كنت تقصد بـ «$sentence»؟")
            .setMessage("اكتب الأمر القياسي الذي يفهمه زول.")
            .setView(input)
            .setPositiveButton("علّمه") { _, _ ->
                val command = input.text.toString().trim()
                if (command.isNotEmpty()) {
                    val phrases = prefs.getString("phrases", "").orEmpty().trim()
                    val rest = prefs.getString("unknown", "").orEmpty().lines().filter { it.isNotBlank() && it != sentence }
                    prefs.edit()
                        .putString("phrases", (if (phrases.isEmpty()) "" else "$phrases\n") + "$sentence=$command")
                        .putString("unknown", rest.joinToString("\n"))
                        .apply()
                    findViewById<EditText>(R.id.phrasesBox).setText(prefs.getString("phrases", ""))
                    toast("تعلّمتها. جرّبها الآن.")
                    refresh()
                }
            }
            .setNegativeButton("تجاهل") { _, _ ->
                val rest = prefs.getString("unknown", "").orEmpty().lines().filter { it.isNotBlank() && it != sentence }
                prefs.edit().putString("unknown", rest.joinToString("\n")).apply()
                refresh()
            }
            .show()
    }

    private fun requestBatteryExemption() {
        if (batteryOk()) { toast("مفعّل بالفعل"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Conversation as chat bubbles: you on one side, Zol on the other, system lines small and grey. */
    private fun renderChat(lines: List<String>) {
        chatList.removeAllViews()
        if (lines.isEmpty()) {
            chatList.addView(TextView(this).apply {
                text = "لا توجد أوامر بعد. اضغط الميكروفون وقل: ${Commands.wakeWord}"
                setPadding(0, dp(6), 0, dp(6))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                textSize = 14f
            })
            return
        }
        for (line in lines) {
            val mine = line.startsWith("أنت:") || line.startsWith("سمعت:")
            val jarvis = line.startsWith("${Brand.NAME}:")
            val body = line.substringAfter(":", line).trim()
            val tv = TextView(this)
            tv.text = if (mine || jarvis) body else line
            tv.textSize = if (mine || jarvis) 15f else 12f
            tv.setTextColor(ContextCompat.getColor(this, if (mine || jarvis) R.color.on_surface else R.color.muted))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            if (mine || jarvis) {
                tv.setBackgroundResource(if (mine) R.drawable.bg_bubble_user else R.drawable.bg_bubble_jarvis)
                tv.setPadding(dp(14), dp(9), dp(14), dp(9))
                lp.gravity = if (mine) Gravity.START else Gravity.END      // layout is right-to-left
                tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            }
            tv.setTextIsSelectable(true)
            chatList.addView(tv, lp)
        }
    }

    /** One-tap shortcuts for the most common commands. */
    private fun setupChips() {
        val row = findViewById<LinearLayout>(R.id.chips)
        val items = listOf(
            "الرئيسية" to "الرئيسية", "رجوع" to "ارجع", "الأرقام" to "الارقام", "انزل" to "انزل",
            "اطلع" to "اطلع", "الإشعارات" to "الاشعارات", "الكشاف" to "ولع الكشاف", "اقرأ الشاشة" to "اقرا الشاشة",
            "تذكيراتي" to "تذكيراتي", "الصلاة الجاية" to "الصلاة الجاية",
            "الأذكار" to "الاذكار", "القبلة" to "وين القبلة",
        )
        for ((label, command) in items) {
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 13f
            chip.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setPadding(dp(16), dp(10), dp(16), dp(10))
            chip.isFocusable = true
            chip.isClickable = true
            chip.setOnClickListener {
                val service = JarvisService.instance
                if (service == null) toast("فعّل خدمة زول أولاً") else service.handleText(command)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            row.addView(chip, lp)
        }
    }

    /** A soft ring that grows and fades around the microphone while Zol is listening. */
    private fun pulse() {
        if (!pulsing) {
            micRing.alpha = 0f
            return
        }
        micRing.scaleX = 0.9f
        micRing.scaleY = 0.9f
        micRing.alpha = 0.9f
        micRing.animate().scaleX(1.35f).scaleY(1.35f).alpha(0f).setDuration(1500).withEndAction { pulse() }.start()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS,
        )
        if (Build.VERSION.SDK_INT >= 26) wanted.add(Manifest.permission.ANSWER_PHONE_CALLS)
        wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { !granted(it) }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_CAMERA = 77
        const val REQ_LOCATION = 78
    }
}

