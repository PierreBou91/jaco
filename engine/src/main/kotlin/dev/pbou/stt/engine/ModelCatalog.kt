package dev.pbou.stt.engine

/**
 * Every speech-to-text model the app knows how to download and run.
 *
 * Adding a model of an existing [EngineKind] only needs a new entry here (plus its UI copy in the app).
 * A new kind of model also needs a branch in [SherpaEngine]'s config builder.
 *
 * File sizes and SHA-256 are those of the files benchmarked in the hub-spikes STT spike (2026-10-30),
 * so a model installed here is byte-for-byte the one that was measured.
 */
object ModelCatalog {

    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /**
     * Hugging Face files pinned to a commit: unlike GitHub release assets, which upstream re-uploads under
     * the same URL (Parakeet's was replaced on 2026-11-07), a pinned URL always serves the same bytes.
     */
    private fun hf(repo: String, commit: String) = ModelSource.Files("https://huggingface.co/$repo/resolve/$commit")

    val PARAKEET = ModelSpec(
        id = "parakeet-tdt-0.6b-v3",
        name = "Parakeet TDT 0.6B v3",
        maker = "NVIDIA",
        license = License.CC_BY_4,
        kind = EngineKind.NEMO_TRANSDUCER,
        // The 2025-08 export, the one benchmarked. Upstream published a new export on 2026-11-07 (not
        // benchmarked yet); this commit keeps the measured one.
        source = hf("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "2bda32ec70b097a55adaa07d9a7173915b43cc78"),
        files = listOf(
            ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        ),
        languages = Languages.Auto(
            count = 25,
            codes = setOf(
                "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
                "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
            ),
        ),
        punctuation = true,
        bench = Bench(werPercent = 7.0, phoneRtf = 0.09, loadSeconds = 5.3),
        recommended = true,
    )

    val CANARY = ModelSpec(
        id = "canary-180m-flash",
        name = "Canary 180M Flash",
        maker = "NVIDIA",
        license = License.CC_BY_4,
        kind = EngineKind.CANARY,
        source = hf("csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8", "9077164e0d3dd1d5353743e89ceaa1d3a770838c"),
        files = listOf(
            ModelFile("encoder.int8.onnx", 132_678_643, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
            ModelFile("decoder.int8.onnx", 74_437_848, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
            ModelFile("tokens.txt", 53_555, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
        ),
        languages = Languages.Choose(setOf("en", "es", "de", "fr")),
        punctuation = true,
        featureDim = 128,
        bench = Bench(werPercent = 10.9, phoneRtf = 0.19, loadSeconds = 2.5),
    )

    val WHISPER_TURBO = ModelSpec(
        id = "whisper-large-v3-turbo",
        name = "Whisper large-v3 turbo",
        maker = "OpenAI",
        license = License.MIT,
        kind = EngineKind.WHISPER,
        source = hf("csukuangfj/sherpa-onnx-whisper-turbo", "2ca6ff69fc878651b770880507669577ac41c2ff"),
        files = listOf(
            ModelFile("turbo-encoder.int8.onnx", 674_716_297, "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b"),
            ModelFile("turbo-decoder.int8.onnx", 361_080_764, "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547"),
            ModelFile("turbo-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
        ),
        languages = Languages.ChooseOrAuto(count = 99),
        punctuation = true,
        bench = Bench(werPercent = 6.3, phoneRtf = 1.2, loadSeconds = 5.0),
    )

    val WHISPER_SMALL = ModelSpec(
        id = "whisper-small",
        name = "Whisper small",
        maker = "OpenAI",
        license = License.MIT,
        kind = EngineKind.WHISPER,
        source = hf("csukuangfj/sherpa-onnx-whisper-small", "8f3c18b358db4d1f2fc1eae49d75cd20989e4309"),
        files = listOf(
            ModelFile("small-encoder.int8.onnx", 112_442_483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"),
            ModelFile("small-decoder.int8.onnx", 262_226_114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"),
            ModelFile("small-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
        ),
        languages = Languages.ChooseOrAuto(count = 99),
        punctuation = true,
        bench = Bench(werPercent = 11.4, phoneRtf = 0.90, loadSeconds = 3.9),
    )

    val QWEN3 = ModelSpec(
        id = "qwen3-asr-0.6b",
        name = "Qwen3-ASR 0.6B",
        maker = "Alibaba Qwen",
        license = License.APACHE_2,
        kind = EngineKind.QWEN3_ASR,
        source = hf("csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25", "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"),
        files = listOf(
            ModelFile("conv_frontend.onnx", 44_148_281, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
            ModelFile("encoder.int8.onnx", 182_491_662, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
            ModelFile("decoder.int8.onnx", 755_914_231, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            ModelFile("tokenizer/merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            ModelFile("tokenizer/tokenizer_config.json", 12_487, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
            ModelFile("tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        ),
        languages = Languages.Auto(count = 30),
        punctuation = true,
        featureDim = 128,
        bench = Bench(werPercent = 12.2, phoneRtf = 0.74, loadSeconds = 8.5),
    )

    val OMNILINGUAL = ModelSpec(
        id = "omnilingual-asr-300m",
        name = "Omnilingual ASR 300M",
        maker = "Meta",
        license = License.APACHE_2,
        kind = EngineKind.OMNILINGUAL_CTC,
        // Only published as a GitHub archive (its Hugging Face repo is empty): checked by hash on unpacking.
        source = ModelSource.Archive(
            url = "$GH/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2",
            size = 292_313_120,
            rootDir = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05",
        ),
        files = listOf(
            ModelFile("model.int8.onnx", 365_841_453, "e3042b2f3b3ef0af2211bf99d2b4bf94a21f5ac0e9898827e7dd6d003a860e91"),
            ModelFile("tokens.txt", 90_630, "7d99997ef207ff14c2cfe825f2aa037528ea250113cc3c6392bfe49326884ba6"),
        ),
        languages = Languages.Auto(count = 1600),
        punctuation = false,
        bench = Bench(werPercent = 22.1, phoneRtf = 0.20, loadSeconds = 2.4),
    )

    val COHERE = ModelSpec(
        id = "cohere-transcribe-2b",
        name = "Cohere Transcribe 2B",
        maker = "Cohere",
        license = License.APACHE_2,
        kind = EngineKind.COHERE_TRANSCRIBE,
        source = hf("csukuangfj2/sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01", "156a470cf08eefe706a0004f3c52d9ee567ca7a0"),
        files = listOf(
            ModelFile("encoder.int8.onnx", 3_090_822, "cf704f8cfa90e3f0a76f9ffc05998bdf00ba9ae983192c14a85a3a5eb008b367"),
            ModelFile("encoder.int8.onnx.data", 2_731_503_072, "bcf1b7148c8518ae52df1ad2d2fc2b4e89261ea23e6c874eef1d9f55bcbaa4a3"),
            ModelFile("decoder.int8.onnx", 153_250_705, "8372ca6c8ff4db8b916ca3592f5c757a715e691b9edec751ba19b29fc854baf9"),
            ModelFile("tokens.txt", 207_437, "013ede043ae2480e3a9205cc34550d9686100cc682bacc90f702facdfbb93035"),
        ),
        languages = Languages.Choose(
            setOf("en", "fr", "de", "it", "es", "pt", "el", "nl", "pl", "zh", "ja", "ko", "vi", "ar"),
        ),
        punctuation = true,
        featureDim = 128,
        bench = Bench(werPercent = 5.2, phoneRtf = 0.53, loadSeconds = 13.7),
    )

    /** In display order: recommended first, then roughly by how usable they are for live dictation. */
    val all: List<ModelSpec> = listOf(PARAKEET, CANARY, COHERE, OMNILINGUAL, WHISPER_SMALL, QWEN3, WHISPER_TURBO)

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }
}

/** The families of model sherpa-onnx runs, each needing its own config. */
enum class EngineKind { NEMO_TRANSDUCER, CANARY, WHISPER, QWEN3_ASR, OMNILINGUAL_CTC, COHERE_TRANSCRIBE }

enum class License(val label: String, val url: String) {
    CC_BY_4("CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/"),
    MIT("MIT", "https://opensource.org/license/mit"),
    APACHE_2("Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
}

/**
 * One file of an installed model, relative to the model's folder. [url] overrides where it is fetched
 * from (a pack whose files live in different repositories); null: the source's base URL plus [path].
 */
data class ModelFile(val path: String, val size: Long, val sha256: String, val url: String? = null)

/** Anything [ModelInstaller] can download and check: a speech model, or the speaker models. */
interface Installable {
    val id: String
    val name: String
    val source: ModelSource
    val files: List<ModelFile>

    /** Space taken once installed. */
    val installBytes: Long get() = files.sumOf { it.size }

    /** Bytes to fetch over the network. */
    val downloadBytes: Long
        get() = when (val s = source) {
            is ModelSource.Files -> installBytes
            is ModelSource.Archive -> s.size
        }
}

sealed interface ModelSource {
    /** Each file is fetched from `baseUrl/path`, resumable file by file. */
    data class Files(val baseUrl: String) : ModelSource

    /** One `.tar.bz2` whose entries live under `rootDir/`; only the listed files are kept. */
    data class Archive(val url: String, val size: Long, val rootDir: String) : ModelSource
}

/** How a model deals with the spoken language. */
sealed interface Languages {
    val count: Int

    /** Detects the language by itself and takes no language setting. */
    data class Auto(override val count: Int, val codes: Set<String>? = null) : Languages

    /** Must be told the language, among [codes]. */
    data class Choose(val codes: Set<String>) : Languages {
        override val count get() = codes.size
    }

    /** Can be told the language, or detect it. */
    data class ChooseOrAuto(override val count: Int) : Languages
}

/** Measured in the spike: WER on 30 FLEURS French clips (desktop), real-time factor and load time on a Honor 90. */
data class Bench(val werPercent: Double, val phoneRtf: Double, val loadSeconds: Double)

data class ModelSpec(
    override val id: String,
    override val name: String,
    val maker: String,
    val license: License,
    val kind: EngineKind,
    override val source: ModelSource,
    override val files: List<ModelFile>,
    val languages: Languages,
    val punctuation: Boolean,
    val featureDim: Int = 80,
    val bench: Bench? = null,
    val recommended: Boolean = false,
) : Installable {

    /**
     * The language code to pass to the model, or null to let it detect the language.
     * [wanted] is the user's choice ("auto" or an ISO code), [fallbacks] the device's languages in order.
     */
    fun effectiveLanguage(wanted: String, fallbacks: List<String>): String? = when (val l = languages) {
        is Languages.Auto -> null
        is Languages.ChooseOrAuto -> wanted.takeIf { it != AUTO }
        is Languages.Choose -> wanted.takeIf { it in l.codes }
            ?: fallbacks.firstOrNull { it in l.codes }
            ?: if ("en" in l.codes) "en" else l.codes.first()
    }

    companion object {
        const val AUTO = "auto"
    }
}
