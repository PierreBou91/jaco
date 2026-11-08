package dev.pbou.stt.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.Closeable
import java.io.File

/** Audio everywhere in the engine is 16 kHz mono float in [-1, 1]. */
const val SAMPLE_RATE = 16_000

/** Settings that change how a model is loaded; a different value means a reload. */
data class EngineOptions(
    /** ISO language code for models that need or accept one, null to let the model detect it. */
    val language: String?,
    val threads: Int,
)

/** A loaded speech-to-text model. Not thread-safe: [EngineHost] runs every call on one thread. */
interface SpeechEngine : Closeable {
    val spec: ModelSpec
    val options: EngineOptions
    fun transcribe(samples: FloatArray): String
}

/** Any sherpa-onnx offline model, configured from its [EngineKind]. */
internal class SherpaEngine(
    override val spec: ModelSpec,
    dir: File,
    override val options: EngineOptions,
) : SpeechEngine {

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = spec.featureDim),
            modelConfig = modelConfig(spec, dir, options),
        ),
    )

    override fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    private companion object {
        fun modelConfig(spec: ModelSpec, dir: File, o: EngineOptions): OfflineModelConfig {
            fun f(path: String): String {
                val file = File(dir, path)
                check(file.exists()) { "missing model file ${file.absolutePath}" }
                return file.absolutePath
            }
            fun first(vararg suffixes: String): String = spec.files.map { it.path }
                .firstNotNullOfOrNull { p -> suffixes.firstOrNull { p.endsWith(it) }?.let { p } }
                ?.let(::f) ?: error("${spec.id}: no file ending with ${suffixes.joinToString()}")
            val threads = o.threads
            return when (spec.kind) {
                EngineKind.NEMO_TRANSDUCER -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(first("encoder.int8.onnx"), first("decoder.int8.onnx"), first("joiner.int8.onnx")),
                    tokens = first("tokens.txt"), modelType = "nemo_transducer", numThreads = threads,
                )
                EngineKind.CANARY -> {
                    val lang = o.language ?: "en"
                    OfflineModelConfig(
                        canary = OfflineCanaryModelConfig(
                            encoder = first("encoder.int8.onnx"), decoder = first("decoder.int8.onnx"),
                            srcLang = lang, tgtLang = lang, usePnc = true,
                        ),
                        tokens = first("tokens.txt"), numThreads = threads,
                    )
                }
                EngineKind.WHISPER -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = first("encoder.int8.onnx"), decoder = first("decoder.int8.onnx"),
                        language = o.language ?: "", task = "transcribe",
                    ),
                    tokens = first("tokens.txt"), numThreads = threads,
                )
                EngineKind.QWEN3_ASR -> OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = f("conv_frontend.onnx"), encoder = f("encoder.int8.onnx"),
                        decoder = f("decoder.int8.onnx"), tokenizer = File(dir, "tokenizer").absolutePath,
                    ),
                    numThreads = threads,
                )
                EngineKind.OMNILINGUAL_CTC -> OfflineModelConfig(
                    omnilingual = OfflineOmnilingualAsrCtcModelConfig(f("model.int8.onnx")),
                    tokens = f("tokens.txt"), numThreads = threads,
                )
                EngineKind.COHERE_TRANSCRIBE -> OfflineModelConfig(
                    cohereTranscribe = OfflineCohereTranscribeModelConfig(
                        encoder = f("encoder.int8.onnx"), decoder = f("decoder.int8.onnx"),
                        language = o.language ?: "en", usePunct = true, useItn = true,
                    ),
                    tokens = f("tokens.txt"), numThreads = threads,
                )
            }
        }
    }
}
