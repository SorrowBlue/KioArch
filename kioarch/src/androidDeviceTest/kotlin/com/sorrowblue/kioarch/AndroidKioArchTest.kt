package com.sorrowblue.kioarch

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Instrumented device tests for [KioArch] Android target.
 * These tests package the compiled native library (libkioarch.so) and run on a connected
 * Android emulator or device to verify native JNI functions and operations.
 */
@Suppress("MagicNumber")
class AndroidKioArchTest {

    /**
     * Verifies that the [ByteArraySeekableSource] behaves correctly.
     */
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

    /**
     * Verifies that passing an invalid/garbage byte array to [KioArch.createReader]
     * correctly fails and throws an [ArchiveInvalidException] via JNI error check.
     */
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
     * Helper function to dynamically generate a temporary ZIP file inside Android cache/temp dir.
     */
    private fun createTempZipFile(): File {
        val tempFile = File.createTempFile("kioarch_android_test", ".zip")
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                // First entry: A text file with dummy content
                val content1 = "This is a dummy text file inside Android zip test.".toByteArray()
                zos.putNextEntry(ZipEntry("dummy1.txt"))
                zos.write(content1)
                zos.closeEntry()

                // Second entry: A slightly larger file
                val content2 = ByteArray(1200) { 'k'.code.toByte() }
                zos.putNextEntry(ZipEntry("dummy2.txt"))
                zos.write(content2)
                zos.closeEntry()
            }
        }
        return tempFile
    }

    /**
     * Verifies that [KioArch] successfully reads and extracts files from a ZIP archive on Android.
     */
    @Test
    fun testRealZipExtraction() {
        val file = createTempZipFile()
        KioArch.createReader(file.readBytes()).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            // Find the first non-directory entry to test extraction
            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)

                // Assert that the extracted size matches the catalog entry size
                assertEquals(fileEntry.size, buffer.size)

                // Read out bytes and calculate CRC32 to verify integrity
                val extractedBytes = buffer.readByteArray()
                val crc = java.util.zip.CRC32()
                crc.update(extractedBytes)

                if (fileEntry.crc != 0L) {
                    assertEquals(fileEntry.crc, crc.value)
                }
            } else {
                assertTrue(false, "No non-empty file entries found in test.zip to test extraction.")
            }
        }
    }

    /**
     * Verifies thread-safety of [KioArch] operations and JNI path normalization on Android.
     */
    @Test
    fun testThreadSafetyAndPathNormalization() {
        val tempFile = File.createTempFile("kioarch_android_thread_test", ".zip")
        tempFile.deleteOnExit()

        val windowsPath = "directory\\subdir\\file.txt"
        val normalizedPath = "directory/subdir/file.txt"

        // Write a zip file containing a Windows-style path with backslashes
        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry(windowsPath))
            zos.write("hello_thread_android".toByteArray())
            zos.closeEntry()
        }

        KioArch.createReader(tempFile.readBytes()).use { reader ->
            // 1. Verify Path Normalization
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            // Should be converted to standard forward slashes
            assertEquals(normalizedPath, entries[0].name)

            // 2. Verify Thread Safety
            val numThreads = 5
            val threads = List(numThreads) {
                kotlin.concurrent.thread(start = false) {
                    for (i in 0 until 20) {
                        val list = reader.getEntries()
                        assertEquals(1, list.size)

                        val buffer = Buffer()
                        reader.extractEntry(list[0], buffer)
                        assertEquals(
                            "hello_thread_android",
                            buffer.readByteArray().decodeToString()
                        )
                    }
                }
            }

            // Start all threads
            threads.forEach { it.start() }
            // Wait for all threads to complete
            threads.forEach { it.join() }
        }
    }

    /**
     * Verifies that the [ArchiveEntry.extract] extension function works properly on Android.
     */
    @Test
    fun testArchiveEntryExtractExtension() {
        val tempFile = File.createTempFile("kioarch_android_ext_test", ".zip")
        tempFile.deleteOnExit()

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry("test.txt"))
            zos.write("hello_extension_android".toByteArray())
            zos.closeEntry()
        }

        KioArch.createReader(tempFile.readBytes()).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            val entry = entries[0]
            val buffer = Buffer()
            entry.extract(reader, buffer)
            assertEquals("hello_extension_android", buffer.readByteArray().decodeToString())
        }
    }

    /**
     * Verifies that [KioArch] successfully reads and extracts files from a ZIP archive
     * using a Kotlin Multiplatform [kotlinx.io.files.Path] on Android.
     */
    @Test
    fun testRealZipExtractionWithPath() {
        val file = createTempZipFile()
        val path = kotlinx.io.files.Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)
                assertEquals(fileEntry.size, buffer.size)
            } else {
                assertTrue(false, "No non-empty file entries found in test.zip")
            }
        }
    }

    /**
     * Verifies that [KioArch] successfully reads and extracts files from a ZIP archive
     * wrapping a [ParcelFileDescriptor] on Android.
     */
    @Test
    fun testParcelFileDescriptorExtraction() {
        val file = createTempZipFile()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        KioArch.createReader(pfd).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)
                assertEquals(fileEntry.size, buffer.size)
            } else {
                assertTrue(false, "No non-empty file entries found in test.zip")
            }
        }
    }

    /**
     * Verifies that [KioArch] successfully reads and extracts files from a ZIP archive
     * resolving an Android [Uri] on Android.
     */
    @Test
    fun testUriExtraction() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = createTempZipFile()
        val uri = Uri.fromFile(file)
        KioArch.createReader(context, uri).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)
                assertEquals(fileEntry.size, buffer.size)
            } else {
                assertTrue(false, "No non-empty file entries found in test.zip")
            }
        }
    }
}
