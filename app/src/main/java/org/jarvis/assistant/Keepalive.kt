package org.jarvis.assistant

/**
 * Keeping Zol alive. Many phones sold in Sudan (Tecno, Infinix, itel, Xiaomi, Oppo, Samsung...) close apps that run in
 * the background, so Zol stops listening. [Oem] says what to switch on for each make, and [ServiceHealth] notices
 * when Zol was stopped without being turned off.
 */
object Oem {

    data class Guide(val brand: String, val steps: List<String>, val screens: List<Pair<String, String>>)

    private val TRANSSION = Guide(
        "Tecno / Infinix / itel",
        listOf(
            "افتح «Phone Master» أو «مدير الهاتف».",
            "ادخل «التشغيل التلقائي» (Auto-start) وفعّل زول.",
            "في الإعدادات ← البطارية ← زول: اختار «بدون قيود».",
            "افتح التطبيقات الأخيرة، اسحب زول لتحت واقفله (القفل 🔒) عشان ما يتقفل.",
        ),
        listOf(
            "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
            "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity",
        ),
    )

    private val GUIDES = mapOf(
        "xiaomi" to Guide(
            "Xiaomi / Redmi / POCO",
            listOf(
                "الإعدادات ← التطبيقات ← إدارة التطبيقات ← زول.",
                "فعّل «التشغيل التلقائي» (Autostart).",
                "«توفير البطارية» ← اختار «بلا قيود».",
                "في التطبيقات الأخيرة اضغط مطوّل على زول واختار القفل 🔒.",
            ),
            listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
        ),
        "tecno" to TRANSSION, "infinix" to TRANSSION, "itel" to TRANSSION,
        "oppo" to Guide(
            "Oppo / Realme",
            listOf(
                "الإعدادات ← البطارية ← زول: فعّل «السماح بالنشاط في الخلفية».",
                "الإعدادات ← التطبيقات ← التشغيل التلقائي: فعّل زول.",
                "في التطبيقات الأخيرة اقفل زول 🔒.",
            ),
            listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        ),
        "vivo" to Guide(
            "Vivo",
            listOf(
                "الإعدادات ← البطارية ← استهلاك الطاقة في الخلفية: اسمح لزول.",
                "i Manager ← إدارة التطبيقات ← التشغيل التلقائي: فعّل زول.",
            ),
            listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        ),
        "huawei" to Guide(
            "Huawei / Honor",
            listOf(
                "الإعدادات ← البطارية ← تشغيل التطبيقات ← زول.",
                "اقفل «إدارة تلقائية» وفعّل التشغيل التلقائي والتشغيل في الخلفية.",
            ),
            listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        ),
        "honor" to Guide(
            "Honor",
            listOf(
                "الإعدادات ← البطارية ← تشغيل التطبيقات (App launch) ← زول.",
                "اقفل «إدارة تلقائية»، وفعّل التلاتة: التشغيل التلقائي، التشغيل الثانوي، والتشغيل في الخلفية.",
                "الإعدادات ← البطارية ← إعدادات إضافية: فعّل «البقاء متصلاً لما الشاشة تقفل».",
                "في التطبيقات الأخيرة اسحب زول لتحت عشان يتقفل 🔒.",
            ),
            listOf(
                "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.hihonor.systemmanager" to "com.hihonor.systemmanager.optimize.process.ProtectActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),
        "samsung" to Guide(
            "Samsung",
            listOf(
                "الإعدادات ← البطارية ← حدود الاستخدام في الخلفية.",
                "شيل زول من «التطبيقات الخاملة» وضيفو في «التطبيقات الما بتنام أبداً».",
            ),
            listOf("com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ),
    )

    private val GENERIC = Guide(
        "تلفونك",
        listOf(
            "الإعدادات ← التطبيقات ← زول ← البطارية: اختار «بدون قيود».",
            "لو في «تشغيل تلقائي» فعّلو لزول.",
            "في التطبيقات الأخيرة اقفل زول 🔒 لو التلفون بيسمح.",
        ),
        emptyList(),
    )

    fun guide(manufacturer: String, brand: String = ""): Guide {
        val m = (manufacturer + " " + brand).lowercase()
        if ("realme" in m || "oneplus" in m) return GUIDES.getValue("oppo")
        if ("redmi" in m || "poco" in m) return GUIDES.getValue("xiaomi")
        return GUIDES.entries.firstOrNull { it.key in m }?.value ?: GENERIC
    }

    /** True for makes known to close background apps hard. */
    fun aggressive(manufacturer: String, brand: String = "") = guide(manufacturer, brand) !== GENERIC
}

/**
 * Remembers when Zol's service started and stopped. A start with no stop since the last one, on the same boot,
 * means Android closed Zol without asking. Times are milliseconds since 1970; "boot" is when the phone started.
 */
object ServiceHealth {

    data class State(val running: Boolean, val boot: Long, val stops: List<Long>, val installed: Long = 0)

    /** Boot times within a minute are the same boot (they are worked out from two clocks). */
    private fun sameBoot(a: Long, b: Long) = kotlin.math.abs(a - b) < 60_000

    /** [installed]: when the app was last installed or updated; an update stops the service and is not a kill. */
    fun started(s: State, now: Long, boot: Long, installed: Long = 0): State {
        val updated = installed != 0L && s.installed != 0L && installed != s.installed
        val killed = s.running && sameBoot(s.boot, boot) && !updated
        val stops = (if (killed) s.stops + now else s.stops).filter { now - it < 7 * DAY }.takeLast(30)
        return State(true, boot, stops, installed)
    }

    fun stopped(s: State) = s.copy(running = false)

    fun stopsToday(s: State, now: Long) = s.stops.count { now - it < DAY }

    fun encode(s: State) = "${s.running}|${s.boot}|${s.stops.joinToString(",")}|${s.installed}"

    fun decode(text: String?): State {
        val p = text.orEmpty().split("|")
        if (p.size < 3) return State(false, 0, emptyList())
        return State(p[0] == "true", p[1].toLongOrNull() ?: 0, p[2].split(",").mapNotNull { it.toLongOrNull() },
            p.getOrNull(3)?.toLongOrNull() ?: 0)
    }

    const val DAY = 24 * 60 * 60 * 1000L
}
