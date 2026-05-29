package com.sorrowblue.kioarch

import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/**
 * Test class for [ArchiveReader] on JVM.
 */
class ArchiveReaderTest {

    init {
        HostTestNativeLoader.loadIfNeeded()
    }

    @Test
    fun testSupportedExtensions() {
        ArchiveReaderTestSpec.verifySupportedExtensions(KioArch.getSupportedExtensions())
    }

    @Test
    fun testInvalidArchiveThrowsException() {
        val invalidData = byteArrayOf(1, 2, 3, 4, 5)
        assertFailsWith<ArchiveInvalidException> {
            KioArch.createReader(invalidData).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testCorruptedArchiveThrowsException() {
        val file = File(System.getProperty("TEST_ZIP_PATH"))
        val bytes = file.readBytes()
        if (bytes.size > 100) {
            for (i in 30 until (bytes.size - 22)) {
                bytes[i] = 0.toByte()
            }
        }

        assertFailsWith<ArchiveCorruptedException> {
            KioArch.createReader(bytes).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testRealExtraction() {
        val file = File(System.getProperty("TEST_7Z_PATH"))
        KioArch.createReader(file).use { reader ->
            ArchiveReaderTestSpec.verify7zExtraction(reader)
        }
    }

    @Test
    fun testRealZipExtraction() {
        val file = File(System.getProperty("TEST_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            ArchiveReaderTestSpec.verifyZipExtraction(reader)
        }
    }

    @Test
    fun testZipShiftJisFilename() {
        val file = File(System.getProperty("TEST_SJIS_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            ArchiveReaderTestSpec.verifyZipShiftJisFilename(reader)
        }
    }

    @Test
    fun testThreadSafetyAndPathNormalization() {
        val file = File(System.getProperty("TEST_PATH_NORMAL_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            ArchiveReaderTestSpec.verifyPathNormalization(reader)

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
    fun testArchiveEntryExtractExtension() {
        val file = File(System.getProperty("TEST_EXT_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            ArchiveReaderTestSpec.verifyArchiveEntryExtractExtension(reader, "hello_extension1")
        }
    }

    @Test
    fun testRealZipExtractionWithPath() {
        val file = File(System.getProperty("TEST_ZIP_PATH"))
        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            ArchiveReaderTestSpec.verifyZipExtraction(reader)
        }
    }

    @Test
    fun testLargeZipExtraction() {
        val file = File(System.getProperty("LARGE_ZIP_PATH"))
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles
        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            ArchiveReaderTestSpec.verifyLargeExtraction(reader, numFiles, sizePerFile)
        }
    }

    @Test
    fun testRealTarGzExtraction() {
        val file = File(System.getProperty("TEST_TARGZ_PATH"))
        KioArch.createReader(file).use { reader ->
            ArchiveReaderTestSpec.verifyTarGzExtraction(reader)
        }
    }

    @Test
    fun testLarge7zExtraction() {
        val file = File(System.getProperty("LARGE_7Z_PATH"))
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles
        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            ArchiveReaderTestSpec.verifyLargeExtraction(reader, numFiles, sizePerFile)
        }
    }

    @Test
    fun testLargeTarGzExtraction() {
        val file = File(System.getProperty("LARGE_TARGZ_PATH"))
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles
        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            ArchiveReaderTestSpec.verifyLargeExtraction(reader, numFiles, sizePerFile)
        }
    }

    @Test
    fun testRealBzip2Extraction() {
        val file = File(System.getProperty("TEST_BZ2_PATH"))
        KioArch.createReader(file).use { reader ->
            assertTrue(reader is Bzip2ArchiveReader)
            ArchiveReaderTestSpec.verifyBzip2Extraction(reader)
        }
    }

    @Test
    fun testRealTarBz2Extraction() {
        val file = File(System.getProperty("TEST_TARBZ2_PATH"))
        KioArch.createReader(file).use { reader ->
            assertTrue(reader is Bzip2ArchiveReader)
            ArchiveReaderTestSpec.verifyTarBz2Extraction(reader)
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
}
