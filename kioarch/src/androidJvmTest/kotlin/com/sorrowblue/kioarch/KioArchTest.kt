package com.sorrowblue.kioarch

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KioArchTest {

    init {
        HostTestNativeLoader.loadIfNeeded()
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

    /**
     * Generates a temporary ZIP file containing dummy entries for testing.
     * The archive is made large enough (over 1000 bytes) to support corruption tests.
     *
     * @return A [File] pointing to the generated ZIP archive.
     */
    private fun createTempZipFile(): File {
        val tempFile = File.createTempFile("kioarch_test_dynamic", ".zip")
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                // First entry: A text file with some dummy text content
                val content1 = "This is a dummy text file inside zip.".toByteArray()
                zos.putNextEntry(ZipEntry("dummy1.txt"))
                zos.write(content1)
                zos.closeEntry()

                // Second entry: A larger file to ensure the ZIP size exceeds 1000 bytes for the corruption test
                val content2 = ByteArray(1200) { 'a'.code.toByte() }
                zos.putNextEntry(ZipEntry("dummy2.txt"))
                zos.write(content2)
                zos.closeEntry()
            }
        }
        return tempFile
    }

    /**
     * Generates a temporary 7z file containing dummy entries for testing using Commons Compress.
     *
     * @return A [File] pointing to the generated 7z archive.
     */
    private fun createTemp7zFile(): File {
        val tempFile = File.createTempFile("kioarch_test_dynamic", ".7z")
        tempFile.deleteOnExit()
        SevenZOutputFile(tempFile).use { sevenZFile ->
            // First entry
            val content1 = "This is a dummy text file inside 7z.".toByteArray()
            val entry1 = sevenZFile.createArchiveEntry(tempFile, "dummy1.txt")
            entry1.size = content1.size.toLong()
            sevenZFile.putArchiveEntry(entry1)
            sevenZFile.write(content1)
            sevenZFile.closeArchiveEntry()

            // Second entry
            val content2 = "Some more dummy content in the second 7z file.".toByteArray()
            val entry2 = sevenZFile.createArchiveEntry(tempFile, "dummy2.txt")
            entry2.size = content2.size.toLong()
            sevenZFile.putArchiveEntry(entry2)
            sevenZFile.write(content2)
            sevenZFile.closeArchiveEntry()
        }
        return tempFile
    }

    @Test
    fun testCorruptedArchiveThrowsException() {
        val file = createTempZipFile()
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
        val file = createTemp7zFile()
        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            println("Found ${entries.size} entries in test.7z")
            for (entry in entries.take(5)) {
                println("Entry: name=${entry.name}, size=${entry.size}, isDir=${entry.isDirectory}, crc=${entry.crc}")
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
                val crc = java.util.zip.CRC32()
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
        val file = createTempZipFile()
        KioArch.createReader(file).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            println("Found ${entries.size} entries in test.zip")
            for (entry in entries.take(5)) {
                println("Entry: name=${entry.name}, size=${entry.size}, isDir=${entry.isDirectory}, crc=${entry.crc}")
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
                val crc = java.util.zip.CRC32()
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
        val tempFile = File.createTempFile("kioarch_sjis_test", ".zip")
        tempFile.deleteOnExit()

        val edgeCaseNames = listOf(
            "テスト_日本語ファイル名_Shift_JIS.txt",
            "dame_moji_ソ表能予.txt",       // Second byte of these characters is 0x5C (backslash '\')
            "half_width_ｶﾀｶﾅﾃｽﾄ.txt",      // Half-width Katakana
            "cp932_extensions_①Ⅳ髙﨑.txt"  // Circled numbers, Roman numerals, NEC/IBM characters
        )

        // Write a zip file with MS932 (Windows-31J) encoding for maximum compatibility with all edge cases
        ZipOutputStream(
            FileOutputStream(tempFile),
            java.nio.charset.Charset.forName("MS932")
        ).use { zos ->
            for (name in edgeCaseNames) {
                zos.putNextEntry(ZipEntry(name))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }
        }

        // Read using KioArch and verify all names are decoded correctly
        KioArch.createReader(tempFile).use { reader ->
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
        val tempFile = File.createTempFile("kioarch_thread_test", ".zip")
        tempFile.deleteOnExit()

        val windowsPath = "directory\\subdir\\file.txt"
        val normalizedPath = "directory/subdir/file.txt"

        // Write a zip file containing a Windows-style path
        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry(windowsPath))
            zos.write("hello_thread".toByteArray())
            zos.closeEntry()
        }

        KioArch.createReader(tempFile).use { reader ->
            // 1. Verify Path Normalization
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            assertEquals(normalizedPath, entries[0].name) // Should be converted to forward slashes

            // 2. Verify Thread Safety (Multiple threads concurrently calling reader operations)
            val numThreads = 10
            val threads = List(numThreads) {
                kotlin.concurrent.thread(start = false) {
                    for (i in 0 until 50) {
                        val list = reader.getEntries()
                        assertEquals(1, list.size)

                        val buffer = Buffer()
                        reader.extractEntry(list[0], buffer)
                        assertEquals("hello_thread", buffer.readByteArray().decodeToString())
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
        val tempFile = File.createTempFile("kioarch_ext_test", ".zip")
        tempFile.deleteOnExit()

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry("test.txt"))
            zos.write("hello_extension".toByteArray())
            zos.closeEntry()
        }

        KioArch.createReader(tempFile).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            val entry = entries[0]
            val buffer = Buffer()
            // Test our new ArchiveEntry.extract extension function
            entry.extract(reader, buffer)
            assertEquals("hello_extension", buffer.readByteArray().decodeToString())
        }
    }

    @Test
    fun testBulkMetadata() {
        val tempFile = File.createTempFile("kioarch_bulk_test", ".zip")
        tempFile.deleteOnExit()

        val numEntries = 100
        val fileNames = List(numEntries) { i -> "folder/subfolder/file_$i.txt" }

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            for (name in fileNames) {
                zos.putNextEntry(ZipEntry(name))
                zos.write("data".toByteArray())
                zos.closeEntry()
            }
        }

        // Create reader and verify bulk retrieval yields correct results
        KioArch.createReader(tempFile).use { reader ->
            val entries = reader.getEntries()
            assertEquals(numEntries, entries.size)

            val source = FileSeekableSource(tempFile)
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
}
