package com.sorrowblue.kioarch

import com.sorrowblue.kioarch.internal.cinterop.kio_get_resident_memory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.posix.getenv
import platform.posix.usleep

private inline fun <T> NSLock.withLock(action: () -> T): T {
    this.lock()
    try {
        return action()
    } finally {
        this.unlock()
    }
}

@OptIn(ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
class ArchiveReaderIosTest {

    private val tinyZipBytes = byteArrayOf(
        0x50, 0x4B, 0x03, 0x04, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xE9.toByte(), 0x8B.toByte(), 0x13, 0x59, 0x86.toByte(), 0xA6.toByte(), 0x10, 0x36,
        0x05, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x74, 0x65,
        0x73, 0x74, 0x2E, 0x74, 0x78, 0x74, 0x68, 0x65, 0x6C, 0x6C, 0x6F, 0x50, 0x4B, 0x01,
        0x02, 0x1E, 0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0xE9.toByte(), 0x8B.toByte(),
        0x13, 0x59, 0x86.toByte(), 0xA6.toByte(), 0x10, 0x36,
        0x05, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x74, 0x65,
        0x73, 0x74, 0x2E, 0x74, 0x78, 0x74, 0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x36, 0x00, 0x00, 0x00, 0x2B, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    @Test
    fun testThreadSafety() {
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()
        KioArch.createReader(tinyZipBytes).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)

            val numThreads = 10
            val lock = NSLock()
            var finishedThreads = 0
            var threadError: Throwable? = null

            repeat(numThreads) {
                NSThread.detachNewThreadWithBlock {
                    try {
                        repeat(50) {
                            val list = reader.getEntries()
                            assertEquals(1, list.size)

                            val buffer = Buffer()
                            reader.extractEntry(list[0], buffer)
                            assertEquals("hello", buffer.readByteArray().decodeToString())
                        }
                    } catch (t: Throwable) {
                        lock.withLock {
                            if (threadError == null) threadError = t
                        }
                    } finally {
                        lock.withLock {
                            finishedThreads++
                        }
                    }
                }
            }

            var totalWaitMs = 0
            while (totalWaitMs < 5000) {
                val done = lock.withLock { finishedThreads == numThreads }
                if (done) break
                usleep(10000u)
                totalWaitMs += 10
            }

            val done = lock.withLock { finishedThreads == numThreads }
            assertTrue(done, "Not all threads finished in time")
            threadError?.let { throw it }
        }
        assertEquals(baseAlloc, getActiveNativeAllocations())
        assertEquals(baseRefs, getActivePlatformReferences())
    }

    @Test
    @Suppress("LongMethod")
    fun testConcurrentCloseAndExtractRaceSafetyIos() {
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        repeat(15) {
            val reader = KioArch.createReader(tinyZipBytes)
            val entry = reader.getEntries().first()
            val numExtractors = 4
            val numClosers = 2
            val totalThreads = numExtractors + numClosers
            val lock = NSLock()
            var finishedThreads = 0
            var unexpectedError: Throwable? = null

            repeat(numExtractors) {
                NSThread.detachNewThreadWithBlock {
                    try {
                        repeat(20) {
                            val buffer = Buffer()
                            reader.extractEntry(entry, buffer)
                            assertEquals("hello", buffer.readByteArray().decodeToString())
                        }
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("closed", ignoreCase = true) != true) {
                            lock.withLock {
                                if (unexpectedError == null) unexpectedError = e
                            }
                        }
                    } catch (t: Throwable) {
                        lock.withLock {
                            if (unexpectedError == null) unexpectedError = t
                        }
                    } finally {
                        lock.withLock { finishedThreads++ }
                    }
                }
            }

            repeat(numClosers) {
                NSThread.detachNewThreadWithBlock {
                    try {
                        usleep(500u)
                        reader.close()
                    } catch (t: Throwable) {
                        lock.withLock {
                            if (unexpectedError == null) unexpectedError = t
                        }
                    } finally {
                        lock.withLock { finishedThreads++ }
                    }
                }
            }

            var totalWaitMs = 0
            while (totalWaitMs < 5000) {
                val done = lock.withLock { finishedThreads == totalThreads }
                if (done) break
                usleep(5000u)
                totalWaitMs += 5
            }
            reader.close()
            assertTrue(lock.withLock { finishedThreads == totalThreads })
            unexpectedError?.let { throw it }
            assertEquals(baseAlloc, getActiveNativeAllocations())
            assertEquals(baseRefs, getActivePlatformReferences())
        }
    }

    @Test
    fun testLeakBrokenArchive() {
        val bytes = tinyZipBytes.copyOf()
        for (i in 40 until 80) {
            bytes[i] = 0.toByte()
        }

        val initialMem = kio_get_resident_memory().toLong()
        println("testLeakBrokenArchive - Initial Memory: ${initialMem / 1024 / 1024} MB")

        repeat(200) {
            assertFailsWith<ArchiveIOException> {
                KioArch.createReader(bytes).use { reader ->
                    reader.getEntries()
                }
            }
        }

        kotlin.native.runtime.GC.collect()

        val finalMem = kio_get_resident_memory().toLong()
        val growth = finalMem - initialMem
        println(
            "testLeakBrokenArchive - Final Memory: ${finalMem / 1024 / 1024} MB " +
                "(Growth: ${growth / 1024 / 1024} MB)"
        )

        assertTrue(
            growth < 5 * 1024 * 1024,
            "Memory leak detected in error handling paths! Growth: ${growth / 1024 / 1024} MB"
        )
    }

    @Test
    fun testLeakLargeArchive() {
        val pathStr = getenv("LARGE_7Z_PATH")?.toKString()
        if (pathStr == null) {
            println("Skipping testLeakLargeArchive because LARGE_7Z_PATH is not set")
            return
        }

        val path = Path(pathStr)
        val initialMem = kio_get_resident_memory().toLong()
        println("testLeakLargeArchive - Initial Memory: ${initialMem / 1024 / 1024} MB")

        repeat(20) { i ->
            KioArch.createReader(path).use { reader ->
                val entries = reader.getEntries()
                val entry = entries.firstOrNull { !it.isDirectory }
                if (entry != null) {
                    val buffer = Buffer()
                    reader.extractEntry(entry, buffer)
                }
            }
            kotlin.native.runtime.GC.collect()
            val currentMem = kio_get_resident_memory().toLong()
            if ((i + 1) % 5 == 0) {
                println(
                    "testLeakLargeArchive - Iteration ${i + 1} " +
                        "Memory: ${currentMem / 1024 / 1024} MB"
                )
            }
        }

        kotlin.native.runtime.GC.collect()
        val finalMem = kio_get_resident_memory().toLong()
        val growth = finalMem - initialMem
        println(
            "testLeakLargeArchive - Final Memory: ${finalMem / 1024 / 1024} MB " +
                "(Growth: ${growth / 1024 / 1024} MB)"
        )

        assertTrue(
            growth < 15 * 1024 * 1024,
            "Memory leak detected in large file extraction! Growth: ${growth / 1024 / 1024} MB"
        )
    }
}
