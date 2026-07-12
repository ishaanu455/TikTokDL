package com.example.tiktokdl.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A snapshot of an in-flight download's progress, for the in-app UI to show live. */
data class DownloadProgressUpdate(
    val label: String,
    val percent: Int,
    val indeterminate: Boolean
)

/**
 * Tiny in-process signal the DownloadService uses to tell the UI "history changed —
 * reload it", plus a live progress stream for the current download. No IPC, no
 * broadcasts, no permissions needed: Service and Activity live in the same process.
 */
object DownloadEvents {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    private val _progress = MutableStateFlow<DownloadProgressUpdate?>(null)
    val progress: StateFlow<DownloadProgressUpdate?> = _progress

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    fun updateProgress(update: DownloadProgressUpdate?) {
        _progress.value = update
    }
}
