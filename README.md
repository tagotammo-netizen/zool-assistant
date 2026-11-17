# جوني — Johnny

**مساعد صوتي مفتوح المصدر يعمل بالعربية (بما فيها اللهجة السودانية) والإنجليزية، لمن لا يستطيع استخدام يديه.**
**An open-source voice assistant for Arabic (including Sudanese dialect) and English, built for people who cannot use their hands.**

الهاتف والكمبيوتر، بلا اشتراكات، وأغلب الميزات تعمل بلا إنترنت.
Phone and PC, no subscriptions, most features work offline.

> الحالة: مشروع تجريبي قيد التطوير. لم يُختبر على أجهزة كثيرة بعد. جرّبه مع مرافق قبل الاعتماد عليه، ولا تعتمد عليه وحده في حالات الطوارئ.
> Status: experimental. Not tested on many devices yet. Try it with a helper first and never rely on it alone in an emergency.

## ماذا يفعل | What it does

- يستمع دائماً ويستيقظ بكلمة **«جوني»** ثم ينفذ الأمر: فتح التطبيقات، الاتصال، الرسائل والواتساب، التمرير، الضغط على الأزرار بالرقم أو بالشبكة.
- قراءة الشاشة والمقالات والإشعارات بصوت عالٍ، والرد على الرسائل بالصوت.
- الرد على المكالمات وإنهاؤها، الإملاء مع علامات الترقيم، التذكير بالدواء.
- التحكم بالبيت الذكي عبر ESP32 (تشغيل، حالة الجهاز، تنبيهات صوتية).
- أوضاع (سيارة، ليل)، كلمة سر صوتية للأوامر الحساسة، إبلاغ المرافق مع الموقع.
- يتعلم من أخطائه: جمل غير مفهومة تُعلَّم مرة واحدة، وتدريب على صوتك.
- Hands-free control of any app, calling, messaging, reading the screen, dictation, reminders, smart-home control, a caregiver alert, and local learning of your own phrases.

## التثبيت | Install

1. افتح مجلد `jarvis-android` في Android Studio، اضغط **Sync** ثم **Run ▶** على هاتفك.
2. افتح التطبيق واتبع البطاقة الأولى: الأذونات، تفعيل خدمة **جوني** في إمكانية الوصول، استثناء البطارية.
3. قل: «جوني افتح الواتساب».

Open `jarvis-android` in Android Studio, Sync, Run. Follow the setup card, then say: "Johnny, open WhatsApp".

المتطلبات: أندرويد 8+ (المكالمات تحتاج 9+ لإنهائها).

## الاختياري | Optional

| الميزة | ما يلزم |
|---|---|
| التعرف دون إنترنت | حزمة صوت عربي من إعدادات Google (الخطوة 4 في البطاقة) |
| محرك Whisper | ملفات النموذج + مجلد من الهاتف (`setup_whisper.ps1`) |
| نموذج لغوي محلي | ملف `.task` (مثلاً Qwen2.5-0.5B من litert-community) يُختار من الإعدادات |
| البيت الذكي | `esp32/jarvis_home/jarvis_home.ino` على لوحة ESP32 |
| نسخة الكمبيوتر | `jarvis.py` (Python)، انظر مجلد `jarvis` |

## الاختبارات | Tests

```
./gradlew test                 # الهاتف
python test_shared_cases.py    # الكمبيوتر: يفحص الجدول المشترك commands_cases.tsv
```

## الخصوصية | Privacy

الصوت يُعالج على الهاتف إن فعّلت الحزمة المحلية أو Whisper، وإلا يستخدم خدمة التعرف في Google. لا يرسل المشروع شيئاً إلى خادم خاص به. الإنترنت يُستخدم فقط عند تفعيلك الوضع الذكي عبر خادم تحدده، أو إنذار الطوارئ عبر ntfy.
Audio is processed on the phone when you enable the offline pack or Whisper; otherwise Google's recogniser is used. The project has no server of its own.

## المساهمة | Contribute

نحتاج: مستخدمين يجرّبون، جمل بلهجات عربية مختلفة (زر «تصدير جملي» في الإعدادات)، ومراجعة الكود.
We need testers, dialect phrases (use "Export my phrases" in settings), and code review.

الرخصة المقترحة: GPL-3.0. Suggested licence: GPL-3.0.
