package dev.pbou.stt.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.random.Random

class ModelInstallerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val content = HashMap<String, ByteArray>()
    /** Path -> number of bytes after which the next response is cut (once). */
    private val cutOnce = HashMap<String, Int>()
    private val rangeRequests = ArrayList<String>()
    private lateinit var base: String

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            if (path.startsWith("/redirect/")) {
                ex.responseHeaders.add("Location", path.removePrefix("/redirect"))
                ex.sendResponseHeaders(302, -1); ex.close(); return@createContext
            }
            val body = content[path]
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            val range = ex.requestHeaders.getFirst("Range")
            val start = range?.let { rangeRequests += "$path $it"; it.removePrefix("bytes=").removeSuffix("-").toInt() } ?: 0
            val slice = body.copyOfRange(start, body.size)
            if (range != null) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, slice.size.toLong())
            } else {
                ex.sendResponseHeaders(200, slice.size.toLong())
            }
            val cut = cutOnce.remove(path)
            try {
                if (cut != null) { ex.responseBody.write(slice, 0, cut); ex.responseBody.flush() } else ex.responseBody.write(slice)
            } catch (_: IOException) {
            }
            ex.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stopServer() = server.stop(0)

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun filesSpec(files: Map<String, ByteArray>, baseUrl: String = "$base/m") = ModelSpec(
        id = "test-model", name = "Test", maker = "Test", license = License.MIT, kind = EngineKind.NEMO_TRANSDUCER,
        source = ModelSource.Files(baseUrl),
        files = files.map { (p, b) -> ModelFile(p, b.size.toLong(), sha(b)) },
        languages = Languages.Auto(1), punctuation = true,
    )

    private fun store(spec: ModelSpec) = ModelStore(tmp.newFolder("models"), listOf(spec))

    @Test fun `downloads every file, verifies it and installs it`() = runBlocking {
        val files = mapOf("encoder.onnx" to bytes(300_000, 1), "tokenizer/vocab.json" to bytes(1_000, 2))
        files.forEach { (p, b) -> content["/m/$p"] = b }
        val spec = filesSpec(files)
        val store = store(spec)
        var last = 0L
        ModelInstaller().install(spec, store) { _, done, total -> last = done; assertEquals(spec.installBytes, total) }

        assertTrue(store.isInstalled(spec))
        assertEquals(setOf(spec.id), store.installed.value)
        files.forEach { (p, b) -> assertTrue(File(store.dir(spec), p).readBytes().contentEquals(b)) }
        assertFalse(store.stagingDir(spec).exists())
        assertEquals(spec.installBytes, last)
    }

    @Test fun `follows redirects`() = runBlocking {
        val b = bytes(10_000, 3)
        content["/m/a.bin"] = b
        val spec = filesSpec(mapOf("a.bin" to b), baseUrl = "$base/redirect/m")
        val store = store(spec)
        ModelInstaller().install(spec, store) { _, _, _ -> }
        assertTrue(store.isInstalled(spec))
    }

    @Test fun `resumes a cut download with a range request`() = runBlocking {
        val b = bytes(500_000, 4)
        content["/m/big.bin"] = b
        cutOnce["/m/big.bin"] = 200_000
        val spec = filesSpec(mapOf("big.bin" to b))
        val store = store(spec)
        val installer = ModelInstaller()
        try {
            installer.install(spec, store) { _, _, _ -> }
            fail("the first attempt should fail")
        } catch (_: IOException) {
        }
        assertFalse(store.isInstalled(spec))
        assertTrue(store.stagedBytes(spec) > 0)

        installer.install(spec, store) { _, _, _ -> }
        assertTrue(store.isInstalled(spec))
        assertTrue(File(store.dir(spec), "big.bin").readBytes().contentEquals(b))
        assertEquals(1, rangeRequests.size)
    }

    @Test fun `rejects a file whose checksum does not match`() = runBlocking {
        val expected = bytes(5_000, 5)
        content["/m/x.bin"] = bytes(5_000, 6) // same size, other bytes
        val spec = filesSpec(mapOf("x.bin" to expected))
        val store = store(spec)
        try {
            ModelInstaller().install(spec, store) { _, _, _ -> }
            fail("should throw")
        } catch (_: CorruptDownloadException) {
        }
        assertFalse(store.isInstalled(spec))
        assertEquals(0L, store.stagedBytes(spec)) // the bad file is not kept for a "resume"
    }

    @Test fun `unpacks only the listed files of an archive`() = runBlocking {
        val enc = bytes(200_000, 7)
        val tok = bytes(1_000, 8)
        val archive = tarBz2("pkg-root", mapOf("encoder.onnx" to enc, "tokens.txt" to tok, "test_wavs/0.wav" to bytes(9_000, 9)))
        content["/a.tar.bz2"] = archive
        val spec = ModelSpec(
            id = "arch", name = "A", maker = "T", license = License.MIT, kind = EngineKind.NEMO_TRANSDUCER,
            source = ModelSource.Archive("$base/a.tar.bz2", archive.size.toLong(), "pkg-root"),
            files = listOf(ModelFile("encoder.onnx", enc.size.toLong(), sha(enc)), ModelFile("tokens.txt", tok.size.toLong(), sha(tok))),
            languages = Languages.Auto(1), punctuation = true,
        )
        val store = store(spec)
        val phases = HashSet<ModelInstaller.Phase>()
        ModelInstaller().install(spec, store) { p, _, _ -> phases += p }

        assertTrue(store.isInstalled(spec))
        assertEquals(setOf("encoder.onnx", "tokens.txt"), store.dir(spec).list()!!.toSet())
        assertTrue(ModelInstaller.Phase.UNPACKING in phases)
        assertFalse(store.stagingDir(spec).exists())
    }

    @Test fun `reports a file missing from the archive`() = runBlocking {
        val enc = bytes(1_000, 10)
        val archive = tarBz2("root", mapOf("encoder.onnx" to enc))
        content["/b.tar.bz2"] = archive
        val spec = ModelSpec(
            id = "arch2", name = "A", maker = "T", license = License.MIT, kind = EngineKind.NEMO_TRANSDUCER,
            source = ModelSource.Archive("$base/b.tar.bz2", archive.size.toLong(), "root"),
            files = listOf(ModelFile("encoder.onnx", 1_000, sha(enc)), ModelFile("tokens.txt", 10, "0".repeat(64))),
            languages = Languages.Auto(1), punctuation = true,
        )
        try {
            ModelInstaller().install(spec, store(spec)) { _, _, _ -> }
            fail("should throw")
        } catch (e: CorruptDownloadException) {
            assertTrue(e.message!!.contains("tokens.txt"))
        }
    }

    @Test fun `refuses to start without enough free space`() = runBlocking {
        val spec = ModelSpec(
            id = "huge", name = "H", maker = "T", license = License.MIT, kind = EngineKind.NEMO_TRANSDUCER,
            source = ModelSource.Files("$base/m"),
            files = listOf(ModelFile("w.bin", Long.MAX_VALUE / 4, "0".repeat(64))),
            languages = Languages.Auto(1), punctuation = true,
        )
        try {
            ModelInstaller().install(spec, store(spec)) { _, _, _ -> }
            fail("should throw")
        } catch (_: NotEnoughSpaceException) {
        }
    }

    @Test fun `a bad file in an archive leaves nothing that a later attempt could trust`() = runBlocking {
        val tok = bytes(1_000, 11)
        val enc = bytes(2_000, 12)
        val archive = tarBz2("r", mapOf("tokens.txt" to tok, "encoder.onnx" to bytes(2_000, 13))) // right size, wrong bytes
        content["/c.tar.bz2"] = archive
        val spec = ModelSpec(
            id = "arch3", name = "A", maker = "T", license = License.MIT, kind = EngineKind.NEMO_TRANSDUCER,
            source = ModelSource.Archive("$base/c.tar.bz2", archive.size.toLong(), "r"),
            files = listOf(ModelFile("tokens.txt", 1_000, sha(tok)), ModelFile("encoder.onnx", 2_000, sha(enc))),
            languages = Languages.Auto(1), punctuation = true,
        )
        val store = store(spec)
        try {
            ModelInstaller().install(spec, store) { _, _, _ -> }
            fail("should throw")
        } catch (_: CorruptDownloadException) {
        }
        assertEquals(0L, store.stagedBytes(spec))
    }

    @Test fun `leftovers downloaded for another version of the entry are wiped, not reused`() = runBlocking {
        val good = bytes(3_000, 14)
        content["/m/w.bin"] = good
        val spec = filesSpec(mapOf("w.bin" to good))
        val store = store(spec)
        // A same-size file left by an older catalog entry (other hash), already in the payload folder.
        File(store.stagingDir(spec), "files").apply { mkdirs() }.resolve("w.bin").writeBytes(bytes(3_000, 15))
        File(store.stagingDir(spec), ModelStore.STAMP).writeText("an older entry")

        ModelInstaller().install(spec, store) { _, _, _ -> }
        assertTrue(File(store.dir(spec), "w.bin").readBytes().contentEquals(good))
    }

    private fun tarBz2(root: String, files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((p, b) in files) {
                val e = TarArchiveEntry("$root/$p").apply { size = b.size.toLong() }
                tar.putArchiveEntry(e)
                tar.write(b)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
}
