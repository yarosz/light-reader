package com.yarosz.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This process's [ReadingData] and its way to the file. A change is saved once changes settle for
 * [debounceMs], on [io]; [flush] saves at once on the calling thread. One thread (the main thread in
 * the Tool) makes every change; [store] may run on any thread and reads [data] as it is when it runs,
 * never a snapshot, so the save that runs last carries the newest data. [saved] is what the file
 * holds: a failed save leaves the data dirty, so the next save or flush retries it, and a flush with
 * nothing new is a no-op.
 */
class ReadingSaver(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val debounceMs: Long,
    private val store: (mine: () -> ReadingData) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    @Volatile
    var data: ReadingData = ReadingData()
        private set

    /** Equal to [data] while nothing is unsaved; a flush before any change or load writes nothing. */
    @Volatile
    private var saved: ReadingData = data
    private var pending: Job? = null

    private var changed = false

    /**
     * The file's data as loaded: the base the next changes are measured against. Throws after a
     * [change], because the loaded data would silently replace it.
     */
    fun loaded(fromDisk: ReadingData) {
        check(!changed) { "reading data loaded after a change" }
        saved = fromDisk
        data = fromDisk
    }

    fun change(transform: (ReadingData) -> ReadingData) {
        changed = true
        data = transform(data)
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            withContext(io) { write() }
        }
    }

    /** Saves any unsaved change now, on this thread: the file is tiny and this may be the last chance before a kill. */
    fun flush() {
        pending?.cancel()
        write()
    }

    /** One save at a time, so [saved] follows the order of the writes. */
    @Synchronized
    private fun write() {
        val mine = data
        if (mine == saved) return
        var taken = mine
        runCatching { store { data.also { taken = it } } }
            .onSuccess { saved = taken }
            .onFailure(onFailure)
    }
}
