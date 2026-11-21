package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

/** Field names are read by the native library: keep them as they are. */
data class SpeakerEmbeddingExtractorConfig(
    val model: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

/** Turns a voice into a list of numbers that is about the same for the same person (sherpa-onnx's Kotlin API). */
class SpeakerEmbeddingExtractor(assetManager: AssetManager? = null, config: SpeakerEmbeddingExtractorConfig) {
    private var ptr: Long = if (assetManager != null) newFromAsset(assetManager, config) else newFromFile(config)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(): OnlineStream = OnlineStream(createStream(ptr))

    fun isReady(stream: OnlineStream) = isReady(ptr, stream.ptr)

    fun compute(stream: OnlineStream): FloatArray = compute(ptr, stream.ptr)

    fun dim() = dim(ptr)

    private external fun newFromAsset(assetManager: AssetManager, config: SpeakerEmbeddingExtractorConfig): Long
    private external fun newFromFile(config: SpeakerEmbeddingExtractorConfig): Long
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun compute(ptr: Long, streamPtr: Long): FloatArray
    private external fun dim(ptr: Long): Int

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
