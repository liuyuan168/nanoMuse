package io.github.nanomuse.wakeword

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * nanoMuse: thin lifecycle wrapper around sherpa-onnx's KeywordSpotter for the
 * packaged model under assets/nanomuse/kws/ (zipformer zh-en 3M, int8,
 * chunk-16). Owns one stream; the service feeds 16 kHz mono PCM16 chunks and
 * polls [detect] between reads.
 *
 * The keywords asset is pinyin tokens followed by the written phrase:
 * `x iǎo l iú t óng x ué @小刘同学` — every token must exist in tokens.txt
 * (KeyWordsFileTest guards that pair).
 */
class SherpaKeywordSpotter(assetManager: AssetManager) {

    private val spotter: KeywordSpotter
    private var stream: OnlineStream? = null

    init {
        val modelConfig = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "nanomuse/kws/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "nanomuse/kws/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                    joiner = "nanomuse/kws/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "nanomuse/kws/tokens.txt",
                // Two threads: one leaves the decode loop below realtime pace
                // when the CPU is throttled with the screen off.
                numThreads = 2,
            ),
            keywordsFile = "nanomuse/kws/keywords.txt",
            keywordsThreshold = 0.25f,
            keywordsScore = 1.0f,
            maxActivePaths = 4,
            numTrailingBlanks = 1,
        )
        spotter = KeywordSpotter(assetManager, modelConfig)
    }

    /** Start accepting audio on a fresh stream. */
    fun start() {
        stop()
        stream = spotter.createStream()
    }

    /**
     * Feed one chunk of 16 kHz mono PCM16 audio. When the wake word is heard
     * the stream is reset and the recognised phrase returned (empty otherwise).
     */
    fun accept(pcm: ShortArray, length: Int): String {
        val active = stream ?: return ""
        val floats = FloatArray(length)
        for (i in 0 until length) floats[i] = pcm[i] / 32768.0f
        // Second parameter is the SAMPLE RATE (the JNI consumes the whole
        // array); passing the chunk length here silently wrecked the
        // feature extraction — every chunk claimed to be 640 Hz audio.
        active.acceptWaveform(floats, SAMPLE_RATE)
        while (spotter.isReady(active)) spotter.decode(active)
        val hit = spotter.getResult(active).keyword
        return if (hit.isNotBlank()) {
            spotter.reset(active)
            hit
        } else {
            ""
        }
    }

    /** Drop buffered audio; used when detection resumes after a pause. */
    fun restart() = start()

    fun release() {
        stop()
        spotter.release()
    }

    private fun stop() {
        stream = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
