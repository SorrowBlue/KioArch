@file:Suppress("InjectDispatcher")

package com.sorrowblue.kioarch

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray

private const val StressCoroutineCount = 8
private const val StressIterationsPerCoroutine = 6
private const val LeakCheckCycles = 25

class ArchiveStressAndLeakCommonTest {

    private class CustomMemorySeekableSource(private val data: ByteArray) : SeekableSource {
        private var pos = 0L
        var isClosed = false
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (isClosed || pos >= data.size) return -1
            val toRead = minOf(length.toLong(), data.size - pos).toInt()
            if (toRead > 0) {
                data.copyInto(buffer, offset, pos.toInt(), pos.toInt() + toRead)
                pos += toRead
            }
            return toRead.coerceAtLeast(0)
        }

        override fun seek(position: Long) {
            pos = position.coerceIn(0L, data.size.toLong())
        }

        override fun position(): Long = pos

        override fun length(): Long = data.size.toLong()

        override fun close() {
            isClosed = true
        }
    }

    private class FaultInjectingSeekableSource(private val data: ByteArray) : SeekableSource {
        private var pos = 0L
        var failReads: Boolean = false
        var failAfterReads: Int = Int.MAX_VALUE
        var readCount: Int = 0
        var onReadHook: (() -> Unit)? = null

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            onReadHook?.invoke()
            readCount++
            if (failReads || readCount >= failAfterReads) {
                throw ArchiveIOException("Simulated transient I/O read failure")
            }
            if (pos >= data.size) return -1
            val toRead = minOf(length.toLong(), data.size - pos).toInt()
            if (toRead > 0) {
                data.copyInto(buffer, offset, pos.toInt(), pos.toInt() + toRead)
                pos += toRead
            }
            return toRead.coerceAtLeast(0)
        }

        override fun seek(position: Long) {
            pos = position.coerceIn(0L, data.size.toLong())
        }

        override fun position(): Long = pos

        override fun length(): Long = data.size.toLong()

        override fun close() = Unit
    }

    @Test
    fun testSharedReaderConcurrentExtractionAllFormats() = runTest {
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val formatEnvVars = listOf(
            "TEST_ZIP_PATH",
            "TEST_7Z_PATH",
            "TEST_TARGZ_PATH",
            "TEST_TARBZ2_PATH",
            "TEST_BZ2_PATH",
            "TEST_BULK_ZIP_PATH"
        )

        for (envVar in formatEnvVars) {
            val archiveBytes = readTestFileBytes(envVar)
            KioArch.createReader(archiveBytes).use { reader ->
                val entries = reader.getEntries().filter { !it.isDirectory }
                assertTrue(entries.isNotEmpty(), "Expected file entries for $envVar")

                val expectedContents = entries.associate { entry ->
                    val buf = Buffer()
                    reader.extractEntry(entry, buf)
                    entry.index to buf.readByteArray()
                }

                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val deferreds = (0 until StressCoroutineCount).map { workerIdx ->
                            async {
                                val orderedEntries = when (workerIdx % 3) {
                                    0 -> entries
                                    1 -> entries.reversed()
                                    else -> entries.sortedBy { (it.index * 31 + workerIdx) % 17 }
                                }
                                repeat(StressIterationsPerCoroutine) {
                                    for (entry in orderedEntries) {
                                        val out = Buffer()
                                        reader.extractEntry(entry, out)
                                        assertContentEquals(
                                            expectedContents.getValue(entry.index),
                                            out.readByteArray(),
                                            "Content mismatch in $envVar for ${entry.name}"
                                        )
                                    }
                                }
                            }
                        }
                        deferreds.awaitAll()
                    }
                }
            }
            assertEquals(baseAlloc, getActiveNativeAllocations(), "Native leak after $envVar")
            assertEquals(baseRefs, getActivePlatformReferences(), "Reference leak after $envVar")
        }
    }

    @Test
    fun testParallelReadersConcurrentExtractionAllFormats() = runTest {
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val archives = listOf(
            "TEST_ZIP_PATH" to readTestFileBytes("TEST_ZIP_PATH"),
            "TEST_7Z_PATH" to readTestFileBytes("TEST_7Z_PATH"),
            "TEST_TARGZ_PATH" to readTestFileBytes("TEST_TARGZ_PATH"),
            "TEST_TARBZ2_PATH" to readTestFileBytes("TEST_TARBZ2_PATH"),
            "TEST_BZ2_PATH" to readTestFileBytes("TEST_BZ2_PATH")
        )

        withContext(Dispatchers.Default) {
            coroutineScope {
                val jobs = (0 until (StressCoroutineCount * 2)).map { idx ->
                    async {
                        val (name, bytes) = archives[idx % archives.size]
                        repeat(StressIterationsPerCoroutine) { iter ->
                            val source = if (iter % 2 == 0) {
                                ByteArraySeekableSource(bytes)
                            } else {
                                CustomMemorySeekableSource(bytes)
                            }
                            KioArch.createReader(source).use { reader ->
                                val entries = reader.getEntries().filter { !it.isDirectory }
                                assertTrue(entries.isNotEmpty(), "No entries in $name")
                                for (entry in entries) {
                                    val sink = Buffer()
                                    reader.extractEntry(entry, sink)
                                    if (entry.size >= 0) {
                                        assertEquals(entry.size, sink.size)
                                    } else {
                                        assertTrue(sink.size > 0)
                                    }
                                }
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        assertEquals(baseAlloc, getActiveNativeAllocations())
        assertEquals(baseRefs, getActivePlatformReferences())
    }

    @Test
    fun testDeterministicNativeMemoryAndReferenceLeakAcrossCycles() = runTest {
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val formatBytes = listOf(
            readTestFileBytes("TEST_ZIP_PATH"),
            readTestFileBytes("TEST_7Z_PATH"),
            readTestFileBytes("TEST_TARGZ_PATH"),
            readTestFileBytes("TEST_TARBZ2_PATH"),
            readTestFileBytes("TEST_BZ2_PATH")
        )

        repeat(LeakCheckCycles) {
            for (bytes in formatBytes) {
                val customSource = CustomMemorySeekableSource(bytes)
                KioArch.createReader(customSource).use { reader ->
                    val entries = reader.getEntries()
                    for (entry in entries) {
                        if (!entry.isDirectory) {
                            val sink = Buffer()
                            reader.extractEntry(entry, sink)
                        }
                    }
                }
                assertTrue(customSource.isClosed, "Underlying source must be closed with reader")
            }
            assertEquals(baseAlloc, getActiveNativeAllocations())
            assertEquals(baseRefs, getActivePlatformReferences())
        }
    }

    @Test
    @Suppress("MagicNumber")
    fun testCorruptedArchiveOpenFailureDoesNotLeakResources() = runTest {
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val corruptedPayloads = listOf(
            byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07),
            byteArrayOf(
                0x37,
                0x7A,
                0xBC.toByte(),
                0xAF.toByte(),
                0x27,
                0x1C,
                0x00,
                0x01,
                0xFF.toByte()
            ),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00),
            byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0x00, 0x00),
            byteArrayOf(
                0x42,
                0x5A,
                0x68,
                '9'.code.toByte(),
                0x17,
                0x72,
                0x45,
                0x38,
                0x50,
                0x90.toByte()
            )
        )

        repeat(10) {
            for (payload in corruptedPayloads) {
                assertFailsWith<ArchiveException> {
                    KioArch.createReader(payload)
                }
                val customSource = CustomMemorySeekableSource(payload)
                assertFailsWith<ArchiveException> {
                    KioArch.createReader(customSource)
                }
                assertEquals(baseAlloc, getActiveNativeAllocations())
                assertEquals(baseRefs, getActivePlatformReferences())
            }
        }
    }

    @Test
    fun testSourceReadExceptionPropagationAndRecovery() = runTest {
        if (!isCallbackExceptionSupported) return@runTest
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val formatEnvVars = listOf(
            "TEST_ZIP_PATH",
            "TEST_7Z_PATH",
            "TEST_TARGZ_PATH",
            "TEST_TARBZ2_PATH",
            "TEST_BZ2_PATH"
        )

        for (envVar in formatEnvVars) {
            val bytes = readTestFileBytes(envVar)

            // 1. Fail during openArchive
            val failingOpenSource = FaultInjectingSeekableSource(bytes).apply {
                failReads = true
            }
            assertFails {
                KioArch.createReader(failingOpenSource)
            }
            assertEquals(baseAlloc, getActiveNativeAllocations())
            assertEquals(baseRefs, getActivePlatformReferences())

            // 2. Open successfully, fail during extractEntry, then recover on retry
            val recoverableSource = FaultInjectingSeekableSource(bytes)
            KioArch.createReader(recoverableSource).use { reader ->
                val fileEntry = reader.getEntries().first { !it.isDirectory }

                recoverableSource.failReads = true
                assertFails {
                    reader.extractEntry(fileEntry, Buffer())
                }

                // Clear fault and verify recovery on the same reader
                recoverableSource.failReads = false
                val recoveredBuffer = Buffer()
                reader.extractEntry(fileEntry, recoveredBuffer)
                assertTrue(recoveredBuffer.size > 0, "Expected recovered data for $envVar")
            }

            assertEquals(baseAlloc, getActiveNativeAllocations())
            assertEquals(baseRefs, getActivePlatformReferences())
        }
    }

    @Test
    fun testSinkWriteExceptionAndCancellationMidExtraction() = runTest {
        if (!isCallbackExceptionSupported) return@runTest
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val formatEnvVars = listOf(
            "TEST_ZIP_PATH",
            "TEST_7Z_PATH",
            "TEST_TARGZ_PATH",
            "TEST_TARBZ2_PATH",
            "TEST_BZ2_PATH"
        )

        for (envVar in formatEnvVars) {
            val bytes = readTestFileBytes(envVar)
            for (useCustomSource in listOf(false, true)) {
                val source = if (useCustomSource) {
                    CustomMemorySeekableSource(bytes)
                } else {
                    ByteArraySeekableSource(bytes)
                }
                KioArch.createReader(source).use { reader ->
                    val entry = reader.getEntries().first { !it.isDirectory }

                    val failingSink = object : RawSink {
                        override fun write(source: Buffer, byteCount: Long): Unit =
                            error("Simulated disk full in Sink.write")

                        override fun flush() = Unit

                        override fun close() = Unit
                    }.buffered()

                    assertFails {
                        reader.extractEntry(entry, failingSink)
                    }

                    val cancellingSink = object : RawSink {
                        override fun write(source: Buffer, byteCount: Long): Unit =
                            throw CancellationException("Simulated coroutine cancellation in Sink")

                        override fun flush() = Unit

                        override fun close() = Unit
                    }.buffered()

                    assertFails {
                        reader.extractEntry(entry, cancellingSink)
                    }

                    // Verify reader can still extract cleanly after Sink failure
                    val okBuffer = Buffer()
                    reader.extractEntry(entry, okBuffer)
                    assertTrue(okBuffer.size > 0)
                }
                assertEquals(baseAlloc, getActiveNativeAllocations())
                assertEquals(baseRefs, getActivePlatformReferences())
            }
        }
    }

    @Test
    fun testDelayedIoWithConcurrentCoroutineCancellation() = runTest {
        if (!isCallbackExceptionSupported) return@runTest
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val bulkZipBytes = readTestFileBytes("TEST_BULK_ZIP_PATH")

        withContext(Dispatchers.Default) {
            coroutineScope {
                val cancellableJobs = (0 until 4).map {
                    launch {
                        var isCancelled = false
                        val slowSource = FaultInjectingSeekableSource(bulkZipBytes)
                        KioArch.createReader(slowSource).use { reader ->
                            val entries = reader.getEntries().filter { !it.isDirectory }
                            for (entry in entries) {
                                delay(5)
                                val sink = object : RawSink {
                                    override fun write(source: Buffer, byteCount: Long) {
                                        source.skip(byteCount)
                                        if (isCancelled) {
                                            throw CancellationException("Cancelled mid-stream")
                                        }
                                    }

                                    override fun flush() = Unit

                                    override fun close() = Unit
                                }.buffered()
                                reader.extractEntry(entry, sink)
                                isCancelled = true
                            }
                        }
                    }
                }

                val survivorJobs = (0 until 4).map {
                    async {
                        KioArch.createReader(bulkZipBytes).use { reader ->
                            val entries = reader.getEntries().filter { !it.isDirectory }
                            for (entry in entries) {
                                delay(2)
                                val out = Buffer()
                                reader.extractEntry(entry, out)
                                assertEquals(entry.size, out.size)
                            }
                        }
                    }
                }

                delay(15)
                for (job in cancellableJobs) {
                    job.cancelAndJoin()
                }
                survivorJobs.awaitAll()
            }
        }

        assertEquals(baseAlloc, getActiveNativeAllocations())
        assertEquals(baseRefs, getActivePlatformReferences())
    }

    @Test
    fun testReentrantCallbackOperationsAreRejectedSafely() = runTest {
        if (!isCallbackExceptionSupported) return@runTest
        initTestPlatform()
        val baseAlloc = getActiveNativeAllocations()
        val baseRefs = getActivePlatformReferences()

        val zipBytes = readTestFileBytes("TEST_ZIP_PATH")
        val source = FaultInjectingSeekableSource(zipBytes)
        val reader = KioArch.createReader(source)
        try {
            val entry = reader.getEntries().first { !it.isDirectory }

            // 1. Attempt re-entrant reader.close() from inside Sink.write callback
            val reentrantCloseSink = object : RawSink {
                override fun write(source: Buffer, byteCount: Long) {
                    reader.close()
                }

                override fun flush() = Unit

                override fun close() = Unit
            }.buffered()

            assertFailsWith<IllegalStateException> {
                reader.extractEntry(entry, reentrantCloseSink)
            }

            // 2. Attempt re-entrant reader.getEntries() from inside SeekableSource.read callback
            source.onReadHook = {
                reader.getEntries()
            }
            assertFails {
                reader.extractEntry(entry, Buffer())
            }
            source.onReadHook = null

            // Reader must still be usable and closeable after rejecting re-entry
            val validOut = Buffer()
            reader.extractEntry(entry, validOut)
            assertEquals(entry.size, validOut.size)
        } finally {
            reader.close()
        }

        assertEquals(baseAlloc, getActiveNativeAllocations())
        assertEquals(baseRefs, getActivePlatformReferences())
    }
}
