package com.yarosz.reader

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSaverTest {

    /** Records every save in order. [beforeTaking] and [afterTaking] each run once, around the next save's read of its data. */
    private class FakeStore {
        val saves = mutableListOf<ReadingData>()
        var failing = false
        var beforeTaking: (() -> Unit)? = null
        var afterTaking: (() -> Unit)? = null

        fun save(mine: () -> ReadingData) {
            beforeTaking?.let { beforeTaking = null; it() }
            if (failing) throw IOException("disk full")
            saves += mine()
            afterTaking?.let { afterTaking = null; it() }
        }
    }

    private val store = FakeStore()
    private val failures = mutableListOf<Throwable>()
    private val a = ReadingData().shelve("urn:a", "A", "a.epub")
    private val b = a.shelve("urn:b", "B", "b.epub")

    private fun TestScope.saver() = ReadingSaver(this, StandardTestDispatcher(testScheduler), 1_000, store::save, failures::add)

    @Test
    fun `changes are saved once they settle, the latest state only`() = runTest {
        val saver = saver()
        saver.change { a }
        advanceTimeBy(600)
        saver.change { b }
        advanceTimeBy(999)
        runCurrent()
        assertEquals(emptyList(), store.saves)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(b), store.saves)
        assertEquals(b, saver.data)
    }

    @Test
    fun `a flush saves a pending change at once, and a flush with nothing new is a no-op`() = runTest {
        val saver = saver()
        saver.flush()
        assertEquals(emptyList(), store.saves)
        saver.loaded(a)
        saver.flush()
        assertEquals(emptyList(), store.saves)
        saver.change { b }
        saver.flush()
        assertEquals(listOf(b), store.saves)
        advanceUntilIdle()
        saver.flush()
        assertEquals(listOf(b), store.saves)
    }

    @Test
    fun `a failed save leaves the data dirty, so a flush retries it`() = runTest {
        val saver = saver()
        store.failing = true
        saver.change { a }
        advanceUntilIdle()
        assertEquals(emptyList(), store.saves)
        assertEquals(1, failures.size)
        store.failing = false
        saver.flush()
        assertEquals(listOf(a), store.saves)
    }

    @Test
    fun `the save that runs last carries the newest data, even when it started before the change`() = runTest {
        val saver = saver()
        saver.change { a }
        store.beforeTaking = {
            saver.change { b }
            saver.flush()
        }
        advanceUntilIdle()
        assertEquals(b, store.saves.last())
        assertFalse(a in store.saves, "the older data was written: ${store.saves}")
    }

    @Test
    fun `a change made after a save read its data stays dirty, so the flush writes it`() = runTest {
        val saver = saver()
        saver.change { a }
        store.afterTaking = { saver.change { b } }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(a), store.saves)
        saver.flush()
        assertEquals(listOf(a, b), store.saves)
        advanceUntilIdle()
        assertEquals(listOf(a, b), store.saves)
    }

    @Test
    fun `the loaded data is the base, so an equal change saves nothing and a real one does`() = runTest {
        val saver = saver()
        saver.loaded(a)
        saver.change { it.copy() }
        advanceUntilIdle()
        assertEquals(emptyList(), store.saves)
        val shelved = a.shelve("id", "T", "t.epub")
        saver.change { it.shelve("id", "T", "t.epub") }
        advanceUntilIdle()
        assertEquals(listOf(shelved), store.saves)
        assertEquals(shelved, saver.data)
    }

    @Test
    fun `loading after a change fails loudly instead of dropping the change`() = runTest {
        val saver = saver()
        saver.change { b }
        assertFailsWith<IllegalStateException> { saver.loaded(a) }
        assertEquals(b, saver.data)
    }
}
