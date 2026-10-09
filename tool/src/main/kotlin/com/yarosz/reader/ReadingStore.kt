package com.yarosz.reader

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Reader"

/** One lock per canonical directory, shared by every [ReadingStore] in the process. */
private val saveLocks = ConcurrentHashMap<String, Any>()

/** For tests, whose directories don't outlive them: forgets [dir]'s lock. */
internal fun forgetSaveLock(dir: File) {
    saveLocks.remove(dir.canonicalPath)
}

/**
 * The reading data file in [dir], saved so that a kill at any moment leaves a readable file. A save
 * writes the new text to a temp file, syncs it, and renames it over the main file in one step, so a
 * readable main file is never absent. Before that, the previous main file's text becomes the backup
 * the same way (temp, sync, rename), but only when it parsed: a main file that doesn't parse is set
 * aside as `.corrupt` (one generation) and never replaces a good backup. Every save merges with the
 * file on disk (see [merge]). Saves are exclusive per directory across every ReadingStore in the
 * process, because they share the temp files. Credentials never go in this file. [rename] and [sync]
 * exist so a test can make one fail.
 */
class ReadingStore(
    dir: File,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    private val main = File(dir, "reading-data.json")
    private val backup = File(dir, "reading-data.json.bak")
    private val corrupt = File(dir, "reading-data.json.corrupt")
    private val temp = File(dir, "reading-data.json.tmp")
    private val backupTemp = File(dir, "reading-data.json.bak.tmp")
    private val lock: Any = saveLocks.computeIfAbsent(dir.canonicalPath) { Any() }

    /** The main file, else the backup, else empty data. Never throws: an unreadable file counts as missing. */
    fun load(): ReadingData = loadSaved() ?: ReadingData()

    /** The main file, else the backup; null when neither exists and parses. Never throws. */
    fun loadSaved(): ReadingData? = read(main) ?: read(backup)

    /**
     * Merges [mine]'s data into the file on disk. [mine] runs under the directory's lock, right before
     * the write, so a save that started before a change still writes the changed data. Throws
     * IOException when a write, sync, or rename fails; a main file that parsed is then as it was.
     */
    fun save(mine: () -> ReadingData): Unit = synchronized(lock) {
        val text = readText(main)
        val current = text?.let { decode(main, it) }
        if (text != null) {
            if (current != null) replace(backupTemp, backup, text)
            else if (!rename(main, corrupt)) throw IOException("couldn't set ${main.name} aside as ${corrupt.name}")
        }
        val merged = merge(current ?: read(backup) ?: ReadingData(), mine())
        replace(temp, main, merged.encode())
    }

    /** Writes [text] to [via], syncs it, and renames it over [target]: the target changes in one step or not at all. */
    private fun replace(via: File, target: File, text: String) {
        FileOutputStream(via).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            sync(out)
        }
        if (!rename(via, target)) throw IOException("couldn't rename ${via.name} to ${target.name}")
    }

    private fun read(file: File): ReadingData? = readText(file)?.let { decode(file, it) }

    private fun readText(file: File): String? = runCatching { file.takeIf { it.exists() }?.readText(Charsets.UTF_8) }
        .onFailure { Log.w(TAG, "reading data: couldn't read ${file.name}", it) }
        .getOrNull()

    private fun decode(file: File, text: String): ReadingData? = decodeReadingData(text)
        .onFailure { Log.w(TAG, "reading data: ${file.name} doesn't parse", it) }
        .getOrNull()
}
