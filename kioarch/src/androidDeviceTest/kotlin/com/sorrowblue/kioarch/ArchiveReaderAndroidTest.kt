package com.sorrowblue.kioarch

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/**
 * Instrumented device tests for [ArchiveReader] Android target.
 */
class ArchiveReaderAndroidTest {

    @Test
    fun testInvalidArchiveThrowsException() {
        val invalidData = byteArrayOf(1, 2, 3, 4, 5)
        assertFailsWith<ArchiveInvalidException> {
            KioArch.createReader(invalidData).use { reader ->
                reader.getEntries()
            }
        }
    }

    private fun createTempZipFile(): File {
        val tempFile = File.createTempFile("kioarch_android_test", ".zip")
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                val content1 = (
                    "This is a dummy text file inside " +
                        "Android zip test."
                    ).toByteArray()
                zos.putNextEntry(ZipEntry("dummy1.txt"))
                zos.write(content1)
                zos.closeEntry()

                val content2 = ByteArray(1200) { 'k'.code.toByte() }
                zos.putNextEntry(ZipEntry("dummy2.txt"))
                zos.write(content2)
                zos.closeEntry()
            }
        }
        return tempFile
    }

    @Test
    fun testRealZipExtraction() {
        val file = createTempZipFile()
        KioArch.createReader(file.readBytes()).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")

            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)
                assertEquals(fileEntry.size, buffer.size)
            } else {
                assertTrue(false, "No non-empty file entries found to test extraction.")
            }
        }
    }

    @Test
    fun testThreadSafetyAndPathNormalization() {
        val tempFile = File.createTempFile("kioarch_android_thread_test", ".zip")
        tempFile.deleteOnExit()

        val windowsPath = "directory\\subdir\\file.txt"
        val normalizedPath = "directory/subdir/file.txt"

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            zos.putNextEntry(ZipEntry(windowsPath))
            zos.write("hello_thread_android".toByteArray())
            zos.closeEntry()
        }

        KioArch.createReader(tempFile.readBytes()).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            assertEquals(normalizedPath, entries[0].name)

            val numThreads = 5
            val threads = List(numThreads) {
                thread(start = false) {
                    repeat(20) {
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

            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
    }

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

    @Test
    fun testRealZipExtractionWithPath() {
        val file = createTempZipFile()
        val path = Path(file.absolutePath)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty())
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

    @Test
    fun testParcelFileDescriptorExtraction() {
        val file = createTempZipFile()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        KioArch.createReader(pfd).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty())

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

    @Test
    fun testUriExtraction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = createTempZipFile()
        val uri = Uri.fromFile(file)
        KioArch.createReader(context, uri).use { reader ->
            val entries = reader.getEntries()
            assertTrue(entries.isNotEmpty())

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
