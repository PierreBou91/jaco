package dev.pbou.stt.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads, checks and installs a model into a [ModelStore], resuming whatever a previous attempt left
 * in the staging folder. Network failures throw [IOException] (worth retrying); a bad hash throws
 * [CorruptDownloadException] and a full disk [NotEnoughSpaceException] (not worth retrying as is).
 *
 * Plain JVM code, so it is unit-tested without a device.
 */
class ModelInstaller(private val http: HttpFetcher = HttpFetcher()) {

    enum class Phase { DOWNLOADING, VERIFYING, UNPACKING }

    fun interface Listener {
        fun onProgress(phase: Phase, done: Long, total: Long)
    }

    suspend fun install(spec: ModelSpec, store: ModelStore, listener: Listener) = withContext(Dispatchers.IO) {
        val staging = store.stagingDir(spec)
        // Leftovers from another version of this catalog entry (new URL or hashes) are never reused.
        val stamp = File(staging, ModelStore.STAMP)
        if (staging.exists() && stamp.takeIf { it.exists() }?.readText() != fingerprint(spec)) staging.deleteRecursively()
        staging.mkdirs()
        stamp.writeText(fingerprint(spec))
        val payload = File(staging, PAYLOAD_DIR)
        checkSpace(spec, store, staging)
        when (val source = spec.source) {
            is ModelSource.Files -> fetchFiles(spec, source, payload, listener)
            is ModelSource.Archive -> fetchArchive(spec, source, staging, payload, listener)
        }
        val target = store.dir(spec)
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!payload.renameTo(target)) throw IOException("could not move ${spec.id} into place")
        staging.deleteRecursively()
        store.refresh()
    }

    private suspend fun fetchFiles(spec: ModelSpec, source: ModelSource.Files, payload: File, listener: Listener) {
        val total = spec.installBytes
        var before = 0L
        for (f in spec.files) {
            val out = File(payload, f.path)
            if (out.length() != f.size) {
                out.parentFile?.mkdirs()
                val part = File(out.path + PART)
                http.download("${source.baseUrl}/${f.path}", part, f.size) { listener.onProgress(Phase.DOWNLOADING, before + it, total) }
                listener.onProgress(Phase.VERIFYING, before + f.size, total)
                if (sha256(part) != f.sha256) {
                    part.delete()
                    throw CorruptDownloadException("${f.path}: checksum mismatch")
                }
                if (!part.renameTo(out)) throw IOException("could not rename $part")
            }
            before += f.size
        }
    }

    private suspend fun fetchArchive(
        spec: ModelSpec, source: ModelSource.Archive, staging: File, payload: File, listener: Listener,
    ) {
        val archive = File(staging, ARCHIVE)
        if (archive.length() != source.size) {
            val part = File(archive.path + PART)
            http.download(source.url, part, source.size) { listener.onProgress(Phase.DOWNLOADING, it, source.size) }
            if (!part.renameTo(archive)) throw IOException("could not rename $part")
        }
        // Always unpack from scratch: an interrupted unpack leaves files of unknown state.
        payload.deleteRecursively()
        payload.mkdirs()
        val wanted = spec.files.associateBy { "${source.rootDir}/${it.path}" }
        val found = HashSet<String>()
        val counting = CountingInputStream(archive.inputStream())
        val ctx = currentCoroutineContext()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, BUFFER), true)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val f = wanted[entry.name.removePrefix("./")] ?: continue
                val out = File(payload, f.path).apply { parentFile?.mkdirs() }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        listener.onProgress(Phase.UNPACKING, counting.count, source.size)
                        ctx.ensureActive()
                    }
                }
                if (written != f.size || digest.digest().hex() != f.sha256) {
                    // Nothing from this archive can be trusted: drop it and what was unpacked from it.
                    archive.delete()
                    payload.deleteRecursively()
                    throw CorruptDownloadException("${f.path}: checksum mismatch in archive")
                }
                found += f.path
            }
        }
        val missing = spec.files.map { it.path }.filterNot { it in found }
        if (missing.isNotEmpty()) {
            archive.delete()
            payload.deleteRecursively()
            throw CorruptDownloadException("missing from archive: ${missing.joinToString()}")
        }
        archive.delete()
    }

    /** What is left to download plus room to unpack, against the free space, with a small margin. */
    private fun checkSpace(spec: ModelSpec, store: ModelStore, staging: File) {
        val staged = store.stagedBytes(spec)
        val needed = when (spec.source) {
            is ModelSource.Files -> spec.installBytes - staged
            // The archive and the unpacked files coexist until the archive is deleted.
            is ModelSource.Archive -> spec.downloadBytes + spec.installBytes - staged
        } + SPACE_MARGIN
        val free = staging.usableSpace
        if (needed > free) throw NotEnoughSpaceException(needed, free)
    }

    companion object {
        private const val PAYLOAD_DIR = "files"
        private const val ARCHIVE = "archive.tar.bz2"
        private const val PART = ".part"
        private const val BUFFER = 256 * 1024
        private const val SPACE_MARGIN = 64L * 1024 * 1024

        /** Identifies what a staging folder was downloaded for. */
        fun fingerprint(spec: ModelSpec): String =
            (listOf(spec.source.toString()) + spec.files.map { "${it.path} ${it.size} ${it.sha256}" }).joinToString("\n")

        fun sha256(f: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().hex()
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

class CorruptDownloadException(message: String) : Exception(message)

class NotEnoughSpaceException(val needed: Long, val free: Long) :
    Exception("not enough space: $needed bytes needed, $free free")

/** Resumable HTTP GET into a file, following redirects by hand so the Range header survives them. */
open class HttpFetcher(private val userAgent: String = "stt_app (Android)") {

    open suspend fun download(url: String, dest: File, expectedSize: Long, onBytes: (Long) -> Unit) {
        var offset = if (dest.exists()) dest.length() else 0L
        if (offset > expectedSize) {
            dest.delete(); offset = 0
        }
        if (offset == expectedSize) return
        val conn = open(url, offset)
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {}
                HttpURLConnection.HTTP_OK -> offset = 0 // the server ignored the range: start over
                416 -> { dest.delete(); throw IOException("HTTP 416 for $url") }
                else -> throw IOException("HTTP $code for $url")
            }
            val ctx = currentCoroutineContext()
            conn.inputStream.use { input ->
                FileOutputStream(dest, offset > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        offset += n
                        onBytes(offset)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (offset != expectedSize) throw IOException("incomplete download of $url: $offset of $expectedSize bytes")
    }

    private fun open(url: String, offset: Long): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = URI(current).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("redirect without Location from $current")
                conn.disconnect()
                current = URI(current).resolve(location).toString()
            } else {
                return conn
            }
        }
        throw IOException("too many redirects for $url")
    }

    private companion object {
        const val MAX_REDIRECTS = 8
    }
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
