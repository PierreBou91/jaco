package dev.pbou.stt.engine

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds who spoke when in an audio file (diarization), on the phone.
 *
 * sherpa-onnx's diarization (pyannote segmentation, then a voice embedding per piece, then clustering)
 * needs the whole audio in memory: an hour is 230 MB of samples, too much for an app. So the audio is
 * decoded and diarized in chunks of [CHUNK_S]; each chunk's speakers get one voice embedding (from up to
 * [VOICE_S] of their speech), and those are grouped across chunks by [VoiceMath.cluster]. Measured on AMI
 * meetings, this even does a little better than diarizing the whole file at once (docs/speakers.md).
 */
class Diarizer(private val store: ModelStore) {

    data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

    /** A speaker found in the audio: their voice, and how long they spoke. */
    class Speaker(val voice: FloatArray, val seconds: Float)

    data class Result(val turns: List<Turn>, val speakers: List<Speaker>)

    /**
     * Diarizes [file]. [count]: the number of speakers if known, null to find it. [onProgress] gets the
     * milliseconds of audio done. Throws [UnsupportedAudioException] if the audio cannot be decoded.
     */
    suspend fun run(file: File, count: Int?, threads: Int, onProgress: (Long) -> Unit): Result = withContext(Dispatchers.Default) {
        val dir = store.dir(SpeakerModels)
        val segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = File(dir, SpeakerModels.SEGMENTATION).path),
            numThreads = threads,
        )
        val embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, SpeakerModels.EMBEDDING).path, numThreads = threads)
        val sd = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = segmentation,
                embedding = embedding,
                clustering = FastClusteringConfig(numClusters = -1, threshold = SpeakerModels.CHUNK_THRESHOLD),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        )
        val extractor = SpeakerEmbeddingExtractor(config = embedding)
        try {
            val pieces = ArrayList<Piece>()
            val chunk = FloatArray(CHUNK_S * SAMPLE_RATE)
            var filled = 0
            var offset = 0L // samples before this chunk
            suspend fun flush() {
                if (filled >= MIN_CHUNK_S * SAMPLE_RATE) pieces += diarizeChunk(sd, extractor, chunk.copyOf(filled), offset)
                offset += filled
                filled = 0
                onProgress(offset * 1000 / SAMPLE_RATE)
                currentCoroutineContext().ensureActive()
            }
            AudioDecoder.decode(file) { samples ->
                var i = 0
                while (i < samples.size) {
                    val n = minOf(samples.size - i, chunk.size - filled)
                    samples.copyInto(chunk, filled, i, i + n)
                    filled += n
                    i += n
                    if (filled == chunk.size) flush()
                }
            }
            flush()
            group(pieces, count)
        } finally {
            extractor.release()
            sd.release()
        }
    }

    /** One speaker of one chunk: their turns (absolute times) and voice. */
    private class Piece(val turns: List<Pair<Long, Long>>, val voice: FloatArray, val seconds: Float)

    private fun diarizeChunk(sd: OfflineSpeakerDiarization, extractor: SpeakerEmbeddingExtractor, samples: FloatArray, offset: Long): List<Piece> {
        val found = sd.process(samples)
        val baseMs = offset * 1000 / SAMPLE_RATE
        return found.groupBy { it.speaker }.values.mapNotNull { segs ->
            val seconds = segs.sumOf { (it.end - it.start).toDouble() }.toFloat()
            // The longest stretches make the cleanest voice sample.
            val stream = extractor.createStream()
            try {
                var taken = 0f
                for (s in segs.sortedByDescending { it.end - it.start }) {
                    // Never more than VOICE_S in all: the voice models fail on minutes of audio at once.
                    val length = minOf(s.end - s.start, VOICE_S - taken)
                    val a = (s.start * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                    val b = ((s.start + length) * SAMPLE_RATE).toInt().coerceIn(a, samples.size)
                    if (b > a) stream.acceptWaveform(samples.copyOfRange(a, b), SAMPLE_RATE)
                    taken += length
                    if (taken >= VOICE_S) break
                }
                stream.inputFinished()
                if (taken < MIN_VOICE_S || !extractor.isReady(stream)) return@mapNotNull null
                Piece(
                    turns = segs.map { baseMs + (it.start * 1000).toLong() to baseMs + (it.end * 1000).toLong() },
                    voice = extractor.compute(stream),
                    seconds = seconds,
                )
            } finally {
                stream.release()
            }
        }
    }

    /**
     * Groups the chunks' speakers into people. Only those who speak at least [MIN_PIECE_S] in a chunk are
     * grouped (short bits of speech give unreliable voices, and with a known count they would take places
     * meant for real people); the short ones then join the closest person.
     */
    private fun group(pieces: List<Piece>, count: Int?): Result {
        if (pieces.isEmpty()) return Result(emptyList(), emptyList())
        val main = pieces.indices.filter { pieces[it].seconds >= MIN_PIECE_S }.ifEmpty { pieces.indices.toList() }
        val mainLabel = VoiceMath.cluster(main.map { pieces[it].voice }, SpeakerModels.LINK_DISTANCE, count)
        val groups = mainLabel.max() + 1
        val centroids = (0 until groups).map { g ->
            VoiceMath.mean(main.filterIndexed { k, _ -> mainLabel[k] == g }.map { pieces[it].voice })
        }
        val label = IntArray(pieces.size) { -1 }
        main.forEachIndexed { k, i -> label[i] = mainLabel[k] }
        pieces.indices.filter { label[it] < 0 }.forEach { i ->
            label[i] = centroids.indices.maxBy { VoiceMath.similarity(pieces[i].voice, centroids[it]) }
        }
        // Numbered in order of first words, so "Speaker 1" is the first to speak.
        val firstWords = (0 until groups).map { g -> pieces.indices.filter { label[it] == g }.minOf { i -> pieces[i].turns.minOf { it.first } } }
        val order = (0 until groups).sortedBy { firstWords[it] }
        val renumber = IntArray(groups).also { r -> order.forEachIndexed { new, old -> r[old] = new } }
        val speakers = order.map { g ->
            val members = pieces.indices.filter { label[it] == g }
            Speaker(
                voice = VoiceMath.mean(members.map { pieces[it].voice }, members.map { pieces[it].seconds }),
                seconds = members.sumOf { pieces[it].seconds.toDouble() }.toFloat(),
            )
        }
        val turns = pieces.indices.flatMap { i -> pieces[i].turns.map { (a, b) -> Turn(a, b, renumber[label[i]]) } }.sortedBy { it.startMs }
        return Result(turns, speakers)
    }

    private companion object {
        const val CHUNK_S = 300
        /** A shorter last chunk is still diarized; below this it is only silence or a word. */
        const val MIN_CHUNK_S = 2
        const val VOICE_S = 30f
        const val MIN_VOICE_S = 1f
        /** See [group]. */
        const val MIN_PIECE_S = 5f
    }
}
