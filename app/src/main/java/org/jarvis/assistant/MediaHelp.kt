package org.jarvis.assistant

/** Small rules for the YouTube and "describe the picture" features, kept apart so they can be tested. */
object MediaHelp {

    private val VIDEO_SIGNS = listOf("views", "view", "مشاهده", "مشاهدة", "مشاهدات", "play video", "تشغيل الفيديو")
    private val LENGTH_SIGNS = listOf("minute", "second", "hour", "دقيقه", "دقيقة", "دقائق", "ثانيه", "ثانية", "ثواني", "ساعه", "ساعة")
    private val AD_SIGNS = listOf("sponsored", "إعلان", "اعلان", "ad ·", "· ad", "إعلان ممول", "promoted")

    /**
     * A result in YouTube's list is a video when its spoken description has its views and length
     * ("… - 4 minutes, 12 seconds - … - 1.2M views"); adverts are skipped.
     */
    fun isVideo(description: String): Boolean {
        val d = description.lowercase()
        if (d.length < 12 || AD_SIGNS.any { d.contains(it) }) return false
        return VIDEO_SIGNS.any { d.contains(it) } && LENGTH_SIGNS.any { d.contains(it) }
    }

    /** A model that can look at pictures, from a server's list (Groq: Llama 4). */
    fun bestVisionModel(ids: List<String>): String? {
        val order = listOf("llama-4-scout", "llama-4-maverick", "vision", "llama-4", "gemma-3", "qwen-vl", "vl")
        for (o in order) ids.firstOrNull { it.contains(o, ignoreCase = true) && !it.contains("guard", ignoreCase = true) }?.let { return it }
        return null
    }
}
