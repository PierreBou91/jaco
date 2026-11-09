package dev.pbou.stt.ui.library

import android.content.Context
import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pbou.stt.AppGraph
import dev.pbou.stt.R
import dev.pbou.stt.data.LibraryItem
import dev.pbou.stt.data.LibraryText
import dev.pbou.stt.engine.RecordingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class LibraryUi(
    val rows: List<Row> = emptyList(),
    val recording: Recording? = null,
    val hasModel: Boolean = true,
    /** Nothing in the library at all (not just no search match). */
    val empty: Boolean = false,
    /** Space taken by the kept audio. */
    val audioBytes: Long = 0,
) {
    data class Row(val hit: LibraryText.Hit, val improving: Float?)
    data class Recording(val itemId: String, val phase: RecordingSession.Phase, val recordedMs: Long)
}

class LibraryViewModel(private val graph: AppGraph, private val context: Context) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Searching is apart from the recording's clock, which ticks four times a second.
    private val rows = combine(graph.library.items, _query, graph.improver.progress) { items, q, progress ->
        LibraryText.search(items, q) { itemTitle(context, it) }.map { LibraryUi.Row(it, progress[it.item.id]) } to items.isEmpty()
    }

    private val audioBytes = graph.library.items.map { items ->
        withContext(Dispatchers.IO) { items.sumOf { graph.library.audioFile(it)?.length() ?: 0L } }
    }

    val ui: StateFlow<LibraryUi> = combine(
        rows, graph.recorder.itemId, graph.recorder.session.state, graph.activeModel, audioBytes,
    ) { (rows, empty), recId, rec, model, audio ->
        LibraryUi(
            rows = rows,
            recording = recId?.let { LibraryUi.Recording(it, rec.phase, rec.recordedMs) },
            hasModel = model != null,
            empty = empty && recId == null,
            audioBytes = audio,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    fun search(q: String) {
        _query.value = q
    }
}

/** The item's title; recordings that were not renamed are called after their date. */
fun itemTitle(context: Context, item: LibraryItem): String =
    item.title ?: context.getString(R.string.rec_default_title, itemDate(context, item))

/** "8 Nov, 23:10" (with the year if not this year). */
fun itemDate(context: Context, item: LibraryItem): String = DateUtils.formatDateTime(
    context, item.createdAt,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
)
