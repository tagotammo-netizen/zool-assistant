package org.jarvis.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Optional small language model that runs on the phone (MediaPipe LLM Inference), with no internet.
 * The person copies a ".task" model file from the settings screen; nothing is downloaded by the app.
 */
class LocalLlm(private val context: Context) {
    private var engine: LlmInference? = null
    private var loadedFrom = 0L

    fun dir(): File = File(context.getExternalFilesDir(null), "llm").also { it.mkdirs() }
    fun file(): File = File(dir(), "model.task")
    fun available() = file().let { it.exists() && it.length() > 1_000_000 }

    /** The chat format depends on the model family: Gemma and Qwen use different markers. */
    private fun wrap(system: String, user: String): String {
        val name = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("llm_name", "").orEmpty().lowercase()
        return if (name.contains("gemma"))
            "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"
        else
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    @Synchronized
    fun ask(system: String, user: String): String {
        val f = file()
        if (engine == null || loadedFrom != f.lastModified()) {
            close()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(f.absolutePath)
                .setMaxTokens(512)
                .build()
            engine = LlmInference.createFromOptions(context, options)
            loadedFrom = f.lastModified()
        }
        return engine!!.generateResponse(wrap(system, user))
    }

    @Synchronized
    fun close() {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
    }
}
