package org.jarvis.assistant

/**
 * "زول المعلّم": short spoken English lessons. Zol says a sentence and what it means, the person says it back,
 * and Whisper (listening for English) shows how close it was. Kept apart from the service so it can be tested.
 */
object Teacher {

    data class Phrase(val english: String, val arabic: String)
    data class Lesson(val title: String, val phrases: List<Phrase>)

    val LESSONS = listOf(
        Lesson("التحية", listOf(
            Phrase("Hello", "أهلاً"),
            Phrase("Good morning", "صباح الخير"),
            Phrase("How are you?", "كيفك؟"),
            Phrase("I am fine, thank you", "أنا كويس، شكراً"),
            Phrase("My name is Ahmed", "اسمي أحمد"),
            Phrase("Nice to meet you", "اتشرفنا"),
        )),
        Lesson("المساعدة", listOf(
            Phrase("Can you help me?", "ممكن تساعدني؟"),
            Phrase("I need help", "أنا محتاج مساعدة"),
            Phrase("Please call my family", "لو سمحت اتصل بأهلي"),
            Phrase("I cannot walk", "أنا ما بقدر أمشي"),
            Phrase("Please wait", "لو سمحت استنى"),
            Phrase("Thank you very much", "شكراً جزيلاً"),
        )),
        Lesson("عند الدكتور", listOf(
            Phrase("I have a headache", "عندي صداع"),
            Phrase("My back hurts", "ضهري بيوجعني"),
            Phrase("I take this medicine every day", "بشرب الدوا ده كل يوم"),
            Phrase("When is my next appointment?", "موعدي الجاي متين؟"),
            Phrase("I feel better today", "حاسي إني أحسن النهارده"),
            Phrase("Please speak slowly", "لو سمحت اتكلم بشويش"),
        )),
        Lesson("الأرقام والزمن", listOf(
            Phrase("One, two, three", "واحد، اتنين، تلاتة"),
            Phrase("What time is it?", "الساعة كم؟"),
            Phrase("It is ten o'clock", "الساعة عشرة"),
            Phrase("See you tomorrow", "نتلاقى بكرة"),
            Phrase("I will come at five", "حاجي الساعة خمسة"),
            Phrase("Today is Friday", "النهارده الجمعة"),
        )),
        Lesson("العيلة", listOf(
            Phrase("This is my mother", "دي أمي"),
            Phrase("My father is at home", "أبوي في البيت"),
            Phrase("I have two brothers", "عندي أخوين"),
            Phrase("My sister is a teacher", "أختي أستاذة"),
            Phrase("I love my family", "بحب عيلتي"),
            Phrase("We live in Khartoum", "بنسكن في الخرطوم"),
        )),
        Lesson("الأكل والسوق", listOf(
            Phrase("I am hungry", "أنا جعان"),
            Phrase("I would like some water", "عايز شوية موية"),
            Phrase("How much is this?", "ده بكم؟"),
            Phrase("That is too expensive", "ده غالي شديد"),
            Phrase("The food is delicious", "الأكل حلو شديد"),
            Phrase("Can I have the bill, please?", "ممكن الحساب لو سمحت؟"),
        )),
        Lesson("المشاعر", listOf(
            Phrase("I am happy", "أنا فرحان"),
            Phrase("I am tired", "أنا تعبان"),
            Phrase("I am a little worried", "أنا قلقان شوية"),
            Phrase("Don't worry", "ما تقلق"),
            Phrase("I miss you", "مشتاق ليك"),
            Phrase("Everything will be fine", "كل حاجة حتبقى كويسة"),
        )),
        Lesson("التلفون والشغل", listOf(
            Phrase("Can you call me later?", "ممكن تتصل بي بعدين؟"),
            Phrase("Send me a message", "رسّل لي رسالة"),
            Phrase("I work from home", "بشتغل من البيت"),
            Phrase("I am learning English", "أنا بتعلم إنجليزي"),
            Phrase("Please repeat that", "لو سمحت عيدها تاني"),
            Phrase("I don't understand", "ما فهمت"),
        )),
    )

    data class Grade(val score: Double, val missing: List<String>)

    private fun words(s: String) = s.lowercase().replace("'", "").replace(Regex("[^a-z0-9 ]"), " ")
        .split(" ").filter { it.isNotEmpty() }

    /** How much of the sentence was said, word by word (order matters little, missing words matter). */
    fun grade(target: String, heard: String): Grade {
        val want = words(target)
        val got = words(heard).toMutableList()
        val missing = ArrayList<String>()
        for (w in want) {
            val i = got.indexOfFirst { it == w || (w.length >= 5 && close(it, w)) }
            if (i >= 0) got.removeAt(i) else missing.add(w)
        }
        val score = if (want.isEmpty()) 0.0 else (want.size - missing.size).toDouble() / want.size
        return Grade(score, missing)
    }

    /** One letter apart: "thanks" for "thank", "tomorow" for "tomorrow". */
    private fun close(a: String, b: String): Boolean {
        if (Math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var diff = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            diff++
            if (diff > 1) return false
            when { a.length > b.length -> i++; b.length > a.length -> j++; else -> { i++; j++ } }
        }
        return diff + (a.length - i) + (b.length - j) <= 1
    }

    /** What the person says to steer the lesson, heard as English (Whisper writes "khalas" for خلاص). */
    fun control(heard: String): String? {
        val h = words(heard).joinToString(" ")
        return when {
            h in setOf("stop", "enough", "khalas", "khalaas", "finish", "end", "exit") || h.startsWith("stop ") -> "stop"
            h in setOf("next", "skip", "next one") -> "next"
            h in setOf("again", "repeat", "say it again", "one more time") -> "again"
            else -> null
        }
    }
}
