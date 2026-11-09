package dev.pbou.stt.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where models live on disk, and which ones are installed.
 *
 * Layout under [root]:
 * - `<model id>/...` an installed model, exactly the files of its [ModelSpec];
 * - `.staging/<model id>/...` a download in progress (kept when paused, so it can resume).
 *
 * A model counts as installed when every file has its expected size. Hashes are checked once, at install
 * time. This also means a model copied by hand (`adb push`, see tools/push_models.sh) is picked up as is.
 */
class ModelStore(val root: File, private val catalog: List<ModelSpec> = ModelCatalog.all) {

    private val _installed = MutableStateFlow(scan())

    /** Ids of the installed models. Call [refresh] after anything changes the disk. */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun dir(spec: Installable) = File(root, spec.id)
    fun stagingDir(spec: Installable) = File(root, ".staging/${spec.id}")

    fun isInstalled(spec: Installable) = spec.files.all { File(dir(spec), it.path).length() == it.size }

    fun refresh() {
        _installed.value = scan()
    }

    /** Bytes already downloaded for a paused or running install (0 if none). */
    fun stagedBytes(spec: Installable): Long =
        stagingDir(spec).walkTopDown().filter { it.isFile && it.name != STAMP }.sumOf { it.length() }

    /** Space used by installed models and leftover downloads. */
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(): Long = root.apply { mkdirs() }.usableSpace

    suspend fun delete(spec: Installable) = withContext(Dispatchers.IO) {
        dir(spec).deleteRecursively()
        stagingDir(spec).deleteRecursively()
        refresh()
    }

    suspend fun discardStaging(spec: Installable) = withContext(Dispatchers.IO) {
        stagingDir(spec).deleteRecursively()
    }

    private fun scan(): Set<String> = catalog.filter(::isInstalled).map { it.id }.toSet()

    companion object {
        /** File in a staging folder recording what it was downloaded for (see [ModelInstaller]). */
        const val STAMP = "source.txt"
    }
}
