package com.sorrowblue.kioarch

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
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

@Suppress("LargeClass")
class KioArchTest {

    init {
        HostTestNativeLoader.loadIfNeeded()
    }

    @Test
    fun testSupportedExtensions() {
        val extensions = KioArch.getSupportedExtensions()
        val expected = listOf(
            "7z",
            "zip",
            "tar.gz",
            "tgz",
            "bz2",
            "tar.bz2",
            "tbz2",
            "tbz"
        )
        assertEquals(expected, extensions)
    }

    @Test
    fun testByteArraySeekableSource() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val source = ByteArraySeekableSource(data)

        assertEquals(10L, source.length())
        assertEquals(0L, source.position())

        val buffer = ByteArray(4)
        val read1 = source.read(buffer, 0, 4)
        assertEquals(4, read1)
        assertEquals(0.toByte(), buffer[0])
        assertEquals(1.toByte(), buffer[1])
        assertEquals(2.toByte(), buffer[2])
        assertEquals(3.toByte(), buffer[3])
        assertEquals(4L, source.position())

        source.seek(2)
        assertEquals(2L, source.position())

        val read2 = source.read(buffer, 0, 4)
        assertEquals(4, read2)
        assertEquals(2.toByte(), buffer[0])
        assertEquals(3.toByte(), buffer[1])
        assertEquals(4.toByte(), buffer[2])
        assertEquals(5.toByte(), buffer[3])

        source.seek(8)
        val read3 = source.read(buffer, 0, 4)
        assertEquals(2, read3)
        assertEquals(8.toByte(), buffer[0])
        assertEquals(9.toByte(), buffer[1])

        source.close()
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
        // Corrupt the archive by clearing bytes in the middle of central directory / local headers
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
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            println("Found ${entries.size} entries in test.7z")
            for (entry in entries.take(5)) {
                println(
                    "Entry: name=${entry.name}, size=${entry.size}, " +
                        "isDir=${entry.isDirectory}, crc=${entry.crc}"
                )
            }

            // Find the first non-directory entry to test extraction
            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                println("Extracting entry: ${fileEntry.name} (${fileEntry.size} bytes)...")
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)

                // Assert that the extracted size matches the catalog entry size
                assertEquals(fileEntry.size, buffer.size)

                // Read out bytes and calculate CRC32 to verify integrity
                val extractedBytes = buffer.readByteArray()
                val crc = CRC32()
                crc.update(extractedBytes)

                println("Calculated CRC32: ${crc.value}, Archive CRC: ${fileEntry.crc}")
                if (fileEntry.crc != 0L) {
                    assertEquals(fileEntry.crc, crc.value)
                }
            } else {
                println("No non-empty file entries found in test.7z to test extraction.")
            }
        }
    }

    @Test
    fun testRealZipExtraction() {
        val file = File(System.getProperty("TEST_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            println("Found ${entries.size} entries in test.zip")
            for (entry in entries.take(5)) {
                println(
                    "Entry: name=${entry.name}, size=${entry.size}, " +
                        "isDir=${entry.isDirectory}, crc=${entry.crc}"
                )
            }

            // Find the first non-directory entry to test extraction
            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                println("Extracting entry: ${fileEntry.name} (${fileEntry.size} bytes)...")
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)

                // Assert that the extracted size matches the catalog entry size
                assertEquals(fileEntry.size, buffer.size)

                // Read out bytes and calculate CRC32 to verify integrity
                val extractedBytes = buffer.readByteArray()
                val crc = CRC32()
                crc.update(extractedBytes)

                println("Calculated CRC32: ${crc.value}, Archive CRC: ${fileEntry.crc}")
                if (fileEntry.crc != 0L) {
                    // CRC32 returned by miniz/zip is unsigned 32-bit int, matches java.util.zip.CRC32.value
                    assertEquals(fileEntry.crc, crc.value)
                }
            } else {
                println("No non-empty file entries found in test.zip to test extraction.")
            }
        }
    }

    @Test
    fun testZipShiftJisFilename() {
        val file = File(System.getProperty("TEST_SJIS_ZIP_PATH"))
        val edgeCaseNames = listOf(
            "テスト_日本語ファイル名_Shift_JIS.txt",
            "dame_moji_ソ表能予.txt", // Second byte of these characters is 0x5C (backslash '\')
            "half_width_ｶﾀｶﾅﾃｽﾄ.txt", // Half-width Katakana
            "cp932_extensions_①Ⅳ髙﨑.txt" // Circled numbers, Roman numerals, NEC/IBM characters
        )

        // Read using KioArch and verify all names are decoded correctly
        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertEquals(edgeCaseNames.size, entries.size)

            for (i in edgeCaseNames.indices) {
                assertEquals(edgeCaseNames[i], entries[i].name)

                // Verify extraction works too
                val buffer = Buffer()
                reader.extractEntry(entries[i], buffer)
                val extractedBytes = buffer.readByteArray()
                assertEquals("hello", extractedBytes.decodeToString())
            }
        }
    }

    @Test
    fun testThreadSafetyAndPathNormalization() {
        val file = File(System.getProperty("TEST_PATH_NORMAL_ZIP_PATH"))
        val normalizedPath1 = "directory/subdir/file1.txt"
        val normalizedPath2 = "directory/subdir/file2.txt"

        KioArch.createReader(file).use { reader ->
            // 1. Verify Path Normalization
            val entries = reader.getEntries()
            assertEquals(2, entries.size)
            assertEquals(normalizedPath1, entries[0].name)
            assertEquals(normalizedPath2, entries[1].name)

            // 2. Verify Thread Safety (Multiple threads concurrently calling reader operations)
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

            // Start all threads
            threads.forEach { it.start() }
            // Wait for all threads to complete
            threads.forEach { it.join() }
        }
    }

    @Test
    fun testArchiveEntryExtractExtension() {
        val file = File(System.getProperty("TEST_EXT_ZIP_PATH"))
        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertEquals(2, entries.size)
            
            val buffer1 = Buffer()
            entries[0].extract(reader, buffer1)
            assertEquals("hello_extension1", buffer1.readByteArray().decodeToString())

            val buffer2 = Buffer()
            entries[1].extract(reader, buffer2)
            assertEquals("hello_extension2", buffer2.readByteArray().decodeToString())
        }
    }

    @Test
    fun testBulkMetadata() {
        val file = File(System.getProperty("TEST_BULK_ZIP_PATH"))
        val numEntries = 100
        val fileNames = List(numEntries) { i -> "folder/subfolder/file_$i.txt" }

        // Create reader and verify bulk retrieval yields correct results
        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertEquals(numEntries, entries.size)

            val source = FileSeekableSource(file)
            val handle = KioArchJni.openArchive(source)
            try {
                assertTrue(handle != 0L)

                // 1. Invoke getEntries (bulk JNI) and check dimensions
                val bulkEntries = KioArchJni.getEntries(handle)
                assertEquals(numEntries, bulkEntries.index.size)
                assertEquals(numEntries, bulkEntries.name.size)
                assertEquals(numEntries, bulkEntries.size.size)
                assertEquals(numEntries, bulkEntries.isDir.size)
                assertEquals(numEntries, bulkEntries.crc.size)

                // 2. Assert bulk data matches original file properties
                for (i in 0 until numEntries) {
                    assertEquals(i, bulkEntries.index[i])
                    assertEquals(fileNames[i], bulkEntries.name[i])
                    assertEquals(4L, bulkEntries.size[i]) // "data" is 4 bytes
                    assertEquals(false, bulkEntries.isDir[i])

                    // Also check entries returned by the high-level reader
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
    fun testRealZipExtractionWithPath() {
        val file = File(System.getProperty("TEST_ZIP_PATH"))
        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)
                assertEquals(fileEntry.size, buffer.size)

                val extractedBytes = buffer.readByteArray()
                val crc = CRC32()
                crc.update(extractedBytes)
                if (fileEntry.crc != 0L) {
                    assertEquals(fileEntry.crc, crc.value)
                }
            } else {
                assertTrue(false, "No non-empty file entries found in test.zip")
            }
        }
    }

    @Test
    fun testLargeZipExtraction() {
        val file = File(System.getProperty("LARGE_ZIP_PATH"))
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles

        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertEquals(numFiles, entries.size)

            for (i in 0 until numFiles) {
                val entry = entries[i]
                assertEquals("large_dummy_$i.bin", entry.name)
                assertEquals(sizePerFile, entry.size)
                val buffer = Buffer()
                reader.extractEntry(entry, buffer)
                verifyLargeBuffer(buffer, sizePerFile, i * 10)
            }
        }
    }

    @Test
    fun testRealTarGzExtraction() {
        val file = File(System.getProperty("TEST_TARGZ_PATH"))
        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            println("Found ${entries.size} entries in test.tar.gz")
            for (entry in entries) {
                println("Entry: name=${entry.name}, size=${entry.size}, isDir=${entry.isDirectory}")
            }

            assertEquals(3, entries.size)
            assertEquals("dummy1.txt", entries[0].name)
            assertEquals("dummy2.txt", entries[1].name)
            assertEquals("nested/windows/path.txt", entries[2].name) // Path normalization check

            // Test extraction of first entry
            val fileEntry1 = entries[0]
            val buffer1 = Buffer()
            reader.extractEntry(fileEntry1, buffer1)
            assertEquals(fileEntry1.size, buffer1.size)
            assertEquals(
                "This is a dummy text file inside tar.gz.",
                buffer1.readByteArray().decodeToString()
            )

            // Test extraction of second entry
            val fileEntry2 = entries[1]
            val buffer2 = Buffer()
            reader.extractEntry(fileEntry2, buffer2)
            assertEquals(fileEntry2.size, buffer2.size)
            assertEquals(
                "Some more dummy content in the second tar.gz file.",
                buffer2.readByteArray().decodeToString()
            )

            // Test extraction of third entry
            val fileEntry3 = entries[2]
            val buffer3 = Buffer()
            reader.extractEntry(fileEntry3, buffer3)
            assertEquals(fileEntry3.size, buffer3.size)
            assertEquals("Windows path data", buffer3.readByteArray().decodeToString())
        }
    }

    @Test
    fun testLarge7zExtraction() {
        val file = File(System.getProperty("LARGE_7Z_PATH"))
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles

        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertEquals(numFiles, entries.size)

            for (i in 0 until numFiles) {
                val entry = entries[i]
                assertEquals("large_dummy_$i.bin", entry.name)
                assertEquals(sizePerFile, entry.size)
                val buffer = Buffer()
                reader.extractEntry(entry, buffer)
                verifyLargeBuffer(buffer, sizePerFile, i * 10)
            }
        }
    }

    @Test
    fun testLargeTarGzExtraction() {
        val file = File(System.getProperty("LARGE_TARGZ_PATH"))
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles

        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertEquals(numFiles, entries.size)

            for (i in 0 until numFiles) {
                val entry = entries[i]
                assertEquals("large_dummy_$i.bin", entry.name)
                assertEquals(sizePerFile, entry.size)
                val buffer = Buffer()
                reader.extractEntry(entry, buffer)
                verifyLargeBuffer(buffer, sizePerFile, i * 10)
            }
        }
    }

    @Test
    fun testDirectSeekableSourceAndZeroCopyExtraction() {
        val zipFile = File(System.getProperty("TEST_ZIP_PATH"))
        val directSource = FileSeekableSource(zipFile)
        try {
            // Assert that FileSeekableSource implements DirectSeekableSource on JVM
            @Suppress("USELESS_IS_CHECK")
            assertTrue(
                directSource is DirectSeekableSource,
                "FileSeekableSource should implement DirectSeekableSource"
            )

            KioArch.createReader(directSource).use { reader ->
                val entries = reader.getEntries()
                assertTrue(entries.isNotEmpty())

                val fileEntry = entries.first { !it.isDirectory && it.size > 0 }

                // Extracting into a file-based channel/stream to test
                // the direct ByteBuffer writing path
                val extractedFile = File.createTempFile(
                    "kioarch_direct_out",
                    ".bin"
                )
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

        // Test with 7z too
        val sevenzFile = File(System.getProperty("TEST_7Z_PATH"))
        val source = FileSeekableSource(sevenzFile)
        try {
            KioArch.createReader(source).use { reader ->
                val entries = reader.getEntries()
                val fileEntry = entries.first { !it.isDirectory && it.size > 0 }
                val extractedFile = File.createTempFile(
                    "kioarch_direct_out_7z",
                    ".bin"
                )
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
    fun testRealBzip2Extraction() {
        val file = File(System.getProperty("TEST_BZ2_PATH"))
        KioArch.createReader(file).use { reader ->
            assertTrue(reader is Bzip2ArchiveReader)
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            val entry = entries[0]
            assertEquals("extracted_data", entry.name)
            assertEquals(-1L, entry.size)
            assertEquals(false, entry.isDirectory)

            val buffer = Buffer()
            reader.extractEntry(entry, buffer)
            val decompressed = buffer.readByteArray().decodeToString()
            assertEquals(
                "This is a dummy text file compressed using bzip2.",
                decompressed
            )
        }
    }

    @Test
    fun testRealTarBz2Extraction() {
        val file = File(System.getProperty("TEST_TARBZ2_PATH"))
        KioArch.createReader(file).use { reader ->
            assertTrue(reader is Bzip2ArchiveReader)
            val entries = reader.getEntries()
            assertEquals(2, entries.size)
            assertEquals("dummy1.txt", entries[0].name)
            assertEquals("dummy2.txt", entries[1].name)

            val buffer1 = Buffer()
            reader.extractEntry(entries[0], buffer1)
            assertEquals(
                "This is a dummy text file inside tar.bz2.",
                buffer1.readByteArray().decodeToString()
            )

            val buffer2 = Buffer()
            reader.extractEntry(entries[1], buffer2)
            assertEquals(
                "Some more dummy content in the second tar.bz2 file.",
                buffer2.readByteArray().decodeToString()
            )
        }
    }

    private fun verifyLargeBuffer(buffer: Buffer, expectedSize: Long, offset: Int) {
        assertEquals(expectedSize, buffer.size)
        var verifiedBytes = 0L
        val chunk = ByteArray(1024 * 1024)
        while (buffer.size > 0) {
            val toRead = if (buffer.size > chunk.size) chunk.size else buffer.size.toInt()
            val readBytes = buffer.readAtMostTo(chunk, 0, toRead)
            for (i in 0 until readBytes) {
                assertEquals(((verifiedBytes + i + offset) % 256).toByte(), chunk[i])
            }
            verifiedBytes += readBytes
        }
        assertEquals(expectedSize, verifiedBytes)
    }
}
