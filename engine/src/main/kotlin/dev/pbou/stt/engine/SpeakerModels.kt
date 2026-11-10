package dev.pbou.stt.engine

/**
 * The two models that find who spoke when ([Diarizer]), installed together like a speech model:
 * - pyannote segmentation 3.0 (MIT, CNRS), which finds where each voice speaks in 10 s windows;
 * - a voice embedding model, which tells voices apart (and recognises saved ones).
 *
 * Chosen on AMI meetings (far-field microphone, 4 speakers, 4 x 15 min, collar 0.25 s), see
 * docs/speakers.md for the numbers. Files pinned to Hugging Face commits, by size and SHA-256.
 */
object SpeakerModels : Installable {
    override val id = "speakers-v1"
    override val name = "Speaker models"
    override val source: ModelSource = ModelSource.Files(baseUrl = "")

    const val SEGMENTATION = "segmentation.int8.onnx"
    const val EMBEDDING = "voice.onnx"

    override val files = listOf(
        ModelFile(
            SEGMENTATION, 1_540_506, "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
        ),
        ModelFile(
            EMBEDDING, 40_257_283, "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e",
            url = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf/nemo_en_titanet_small.onnx",
        ),
    )

    /** NVIDIA TitaNet small, CC-BY-4.0. */
    val embeddingLicense = License.CC_BY_4

    /** Clustering threshold inside a chunk (sherpa-onnx's own clustering, its scale). */
    const val CHUNK_THRESHOLD = 0.7f

    /** Cosine distance under which two chunks' voices are the same person. */
    const val LINK_DISTANCE = 0.5f

    /** Cosine similarity from which a person is recognised as a saved voice (stricter: a wrong name is worse than none). */
    const val MATCH_SIMILARITY = 0.65f
}
