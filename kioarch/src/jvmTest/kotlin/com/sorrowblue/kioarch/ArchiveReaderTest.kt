package com.sorrowblue.kioarch

import com.sun.management.ThreadMXBean
import java.io.File
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * JVM-specific test class for [ArchiveReader].
 */
class ArchiveReaderTest {

    init {
        HostTestNativeLoader.loadIfNeeded()
    }

    @Test
    fun testRealZipExtractionWithFile() {
        val file = File(System.getProperty("TEST_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            reader.verifyZipExtraction(2)
        }
    }

    @Test
    fun testThreadSafety() {
        val file = File(System.getProperty("TEST_PATH_NORMAL_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            reader.verifyPathNormalization()

            val numThreads = 10
            val threads = List(numThreads) {
                thread(start = false) {
                    repeat(50) {
                        val list = reader.getEntries()
                        assertEquals(2, list.size)

                        val buffer1 = Buffer()
                        reader.extractEntry(list[0], buffer1)
                        assertEquals("hello_thread1", buffer1.readByteArray().decodeToString())

                        val buffer2 = Buffer()
                        reader.extractEntry(list[1], buffer2)
                        assertEquals("hello_thread2", buffer2.readByteArray().decodeToString())
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
    }

    @Test
    fun testBulkMetadata() {
        val file = File(System.getProperty("TEST_BULK_ZIP_PATH"))
        val numEntries = 100
        val fileNames = List(numEntries) { i -> "folder/subfolder/file_$i.txt" }

        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertEquals(numEntries, entries.size)

            val source = FileSeekableSource(file)
            val handle = KioArchJni.openArchive(source)
            try {
                assertTrue(handle != 0L)
                val bulkEntries = KioArchJni.getEntries(handle)
                assertEquals(numEntries, bulkEntries.index.size)
                assertEquals(numEntries, bulkEntries.name.size)
                assertEquals(numEntries, bulkEntries.size.size)
                assertEquals(numEntries, bulkEntries.isDir.size)
                assertEquals(numEntries, bulkEntries.crc.size)

                for (i in 0 until numEntries) {
                    assertEquals(i, bulkEntries.index[i])
                    assertEquals(fileNames[i], bulkEntries.name[i])
                    assertEquals(4L, bulkEntries.size[i])
                    assertEquals(false, bulkEntries.isDir[i])
                    assertEquals(fileNames[i], entries[i].name)
                    assertEquals(i, entries[i].index)
                }
            } finally {
                KioArchJni.closeArchive(handle)
                source.close()
            }
        }
    }

    @Test
    fun testDirectSeekableSourceAndZeroCopyExtraction() {
        val zipFile = File(System.getProperty("TEST_ZIP_PATH"))
        val directSource = FileSeekableSource(zipFile)
        try {
            @Suppress("USELESS_IS_CHECK")
            assertTrue(
                directSource is DirectSeekableSource,
                "FileSeekableSource should implement DirectSeekableSource"
            )

            KioArch.createReader(directSource).use { reader ->
                val entries = reader.getEntries()
                assertTrue(entries.isNotEmpty())

                val fileEntry = entries.first { !it.isDirectory && it.size > 0 }
                val extractedFile = File.createTempFile("kioarch_direct_out", ".bin")
                extractedFile.deleteOnExit()

                FileOutputStream(extractedFile).use { fos ->
                    val sink = fos.asSink().buffered()
                    reader.extractEntry(fileEntry, sink)
                    sink.flush()
                }

                assertEquals(
                    fileEntry.size,
                    extractedFile.length(),
                    "Extracted file size should match entry size"
                )
            }
        } finally {
            directSource.close()
        }

        val sevenzFile = File(System.getProperty("TEST_7Z_PATH"))
        val source = FileSeekableSource(sevenzFile)
        try {
            KioArch.createReader(source).use { reader ->
                val entries = reader.getEntries()
                val fileEntry = entries.first { !it.isDirectory && it.size > 0 }
                val extractedFile = File.createTempFile("kioarch_direct_out_7z", ".bin")
                extractedFile.deleteOnExit()
                FileOutputStream(extractedFile).use { fos ->
                    val sink = fos.asSink().buffered()
                    reader.extractEntry(fileEntry, sink)
                    sink.flush()
                }
                assertEquals(fileEntry.size, extractedFile.length())
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun testPathSeekableSourceDirectExtraction() {
        val zipFile = File(System.getProperty("TEST_ZIP_PATH"))
        val pathSource = PathSeekableSource(zipFile.toPath())
        try {
            @Suppress("USELESS_IS_CHECK")
            assertTrue(
                pathSource is DirectSeekableSource,
                "PathSeekableSource should implement DirectSeekableSource"
            )
            KioArch.createReader(pathSource).use { reader ->
                val entries = reader.getEntries()
                val fileEntry = entries.first { !it.isDirectory && it.size > 0 }
                val directBuffer = Buffer()
                reader.extractEntry(fileEntry, directBuffer)
                val directBytes = directBuffer.readByteArray()

                SeekableArchiveReader(
                    FileSeekableSource(zipFile),
                    useDirectOutput = false
                ).use { stdReader ->
                    val stdBuffer = Buffer()
                    stdReader.extractEntry(fileEntry, stdBuffer)
                    assertContentEquals(stdBuffer.readByteArray(), directBytes)
                }
            }
        } finally {
            pathSource.close()
        }
    }

    @Test
    fun testZeroCopyReducesHeapAllocations() {
        val threadBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!threadBean.isThreadAllocatedMemorySupported) return
        threadBean.isThreadAllocatedMemoryEnabled = true

        val tempZip = File.createTempFile("kioarch_alloc_test", ".zip")
        tempZip.deleteOnExit()
        ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
            zos.putNextEntry(ZipEntry("payload.bin"))
            zos.write(Random(42).nextBytes(2 * 1024 * 1024))
            zos.closeEntry()
        }

        val discardingSink = object : RawSink {
            override fun write(source: Buffer, byteCount: Long) = source.skip(byteCount)
            override fun flush() = Unit
            override fun close() = Unit
        }

        fun measureAllocatedBytes(useDirect: Boolean): Long {
            val source = if (useDirect) {
                FileSeekableSource(tempZip)
            } else {
                object : SeekableSource by FileSeekableSource(tempZip) {}
            }
            return SeekableArchiveReader(source, useDirectOutput = useDirect).use { reader ->
                val entry = reader.getEntries().first()
                val sink = discardingSink.buffered()
                repeat(3) {
                    reader.extractEntry(entry, sink)
                    sink.flush()
                }
                val before = threadBean.currentThreadAllocatedBytes
                repeat(10) {
                    reader.extractEntry(entry, sink)
                    sink.flush()
                }
                threadBean.currentThreadAllocatedBytes - before
            }
        }

        try {
            val directAllocated = measureAllocatedBytes(useDirect = true)
            val stdAllocated = measureAllocatedBytes(useDirect = false)
            assertTrue(
                directAllocated < stdAllocated,
                "Expected Direct ByteBuffer ($directAllocated B) < ByteArray ($stdAllocated B)"
            )
        } finally {
            tempZip.delete()
        }
    }

    @Test
    fun testDoubleCloseAndClosedAccessWithFile() {
        val zipFile = File(System.getProperty("TEST_ZIP_PATH"))
        KioArch.createReader(zipFile).use { reader ->
            reader.verifyDoubleCloseAndClosedAccess()
        }

        val bz2File = File(System.getProperty("TEST_BZ2_PATH"))
        KioArch.createReader(bz2File).use { reader ->
            reader.verifyDoubleCloseAndClosedAccess()
        }
    }

    @Test
    fun testHighContentionMultiThreadedStressAllFormats() {
        val baseAlloc = KioArchJni.getActiveNativeAllocations()
        val baseRefs = KioArchJni.getActiveJniGlobalRefs()

        val formatProps = listOf(
            "LARGE_ZIP_PATH",
            "LARGE_7Z_PATH",
            "LARGE_TARGZ_PATH",
            "TEST_TARBZ2_PATH",
            "TEST_BZ2_PATH"
        )

        for (prop in formatProps) {
            val file = File(System.getProperty(prop))
            KioArch.createReader(file).use { sharedReader ->
                val entries = sharedReader.getEntries().filter { !it.isDirectory }.take(12)
                assertTrue(entries.isNotEmpty(), "Expected entries for $prop")

                val numThreads = 8
                val barrier = CyclicBarrier(numThreads)
                val errors = ConcurrentLinkedQueue<Throwable>()

                val threads = List(numThreads) { threadIdx ->
                    thread(start = false) {
                        try {
                            barrier.await()
                            // 1. Contend on the shared reader
                            for (i in entries.indices) {
                                val entry = entries[(i + threadIdx) % entries.size]
                                val buf = Buffer()
                                sharedReader.extractEntry(entry, buf)
                                if (entry.size >= 0) {
                                    assertEquals(entry.size, buf.size)
                                } else {
                                    assertTrue(buf.size > 0)
                                }
                            }
                            // 2. Simultaneously open and extract from an independent File reader
                            KioArch.createReader(file).use { ownReader ->
                                val ownEntry = ownReader.getEntries().first { !it.isDirectory }
                                val ownBuf = Buffer()
                                ownReader.extractEntry(ownEntry, ownBuf)
                                if (ownEntry.size >= 0) {
                                    assertEquals(ownEntry.size, ownBuf.size)
                                } else {
                                    assertTrue(ownBuf.size > 0)
                                }
                            }
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                }

                threads.forEach { it.start() }
                threads.forEach { it.join() }

                if (errors.isNotEmpty()) {
                    throw AssertionError("Thread failed in $prop", errors.first())
                }
            }
            assertEquals(baseAlloc, KioArchJni.getActiveNativeAllocations())
            assertEquals(baseRefs, KioArchJni.getActiveJniGlobalRefs())
        }
    }

    @Test
    fun testConcurrentCloseAndExtractRaceSafety() {
        val baseAlloc = KioArchJni.getActiveNativeAllocations()
        val baseRefs = KioArchJni.getActiveJniGlobalRefs()
        val bulkZipFile = File(System.getProperty("TEST_BULK_ZIP_PATH"))

        repeat(20) {
            val reader = KioArch.createReader(bulkZipFile)
            val entries = reader.getEntries().filter { entry -> !entry.isDirectory }
            val numExtractors = 6
            val numClosers = 2
            val barrier = CyclicBarrier(numExtractors + numClosers)
            val unexpectedErrors = ConcurrentLinkedQueue<Throwable>()

            val extractors = List(numExtractors) { idx ->
                thread(start = false) {
                    try {
                        barrier.await()
                        for (entry in entries) {
                            val out = Buffer()
                            reader.extractEntry(entry, out)
                            assertEquals(entry.size, out.size)
                            if (idx == 0) {
                                reader.getEntries()
                            }
                        }
                    } catch (e: IllegalStateException) {
                        assertTrue(
                            e.message?.contains("closed", ignoreCase = true) == true,
                            "Unexpected IllegalStateException: ${e.message}"
                        )
                    } catch (t: Throwable) {
                        unexpectedErrors.add(t)
                    }
                }
            }

            val closers = List(numClosers) {
                thread(start = false) {
                    try {
                        barrier.await()
                        reader.close()
                    } catch (t: Throwable) {
                        unexpectedErrors.add(t)
                    }
                }
            }

            (extractors + closers).forEach { worker -> worker.start() }
            (extractors + closers).forEach { worker -> worker.join() }
            reader.close()

            if (unexpectedErrors.isNotEmpty()) {
                throw AssertionError(
                    "Unexpected error during concurrent close race",
                    unexpectedErrors.first()
                )
            }
            assertEquals(baseAlloc, KioArchJni.getActiveNativeAllocations())
            assertEquals(baseRefs, KioArchJni.getActiveJniGlobalRefs())
        }
    }
}
