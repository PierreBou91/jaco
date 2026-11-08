package dev.pbou.stt.ui.file

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.data.FileState
import dev.pbou.stt.engine.ModelCatalog
import dev.pbou.stt.engine.ModelSpec
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class FileUi(val file: FileState? = null, val installed: List<ModelSpec> = emptyList())

class FileViewModel(private val graph: AppGraph) : ViewModel() {

    val ui: StateFlow<FileUi> = combine(graph.files.state, graph.store.installed) { file, installed ->
        FileUi(file, ModelCatalog.all.filter { it.id in installed })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileUi(graph.files.state.value))

    fun open(uri: Uri) = graph.files.open(uri)

    fun rerun(model: ModelSpec, language: String? = null) = graph.files.rerun(model, language)

    fun stop() = graph.files.stop()
}
