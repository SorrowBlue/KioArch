package com.sorrowblue.kioarch

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KioArchTest {

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
        assertFailsWith<IllegalArgumentException> {
            KioArch.createReader(invalidData).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testRealExtraction() {
        var file = java.io.File("src/jvmTest/resources/test.7z")
        if (!file.exists()) {
            file = java.io.File("kioarch/src/jvmTest/resources/test.7z")
        }
        assertTrue(file.exists(), "test.7z does not exist (tried: src/jvmTest/resources/test.7z and kioarch/src/jvmTest/resources/test.7z)")


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
        var file = java.io.File("src/jvmTest/resources/test.zip")
        if (!file.exists()) {
            file = java.io.File("kioarch/src/jvmTest/resources/test.zip")
        }
        assertTrue(file.exists(), "test.zip does not exist (tried: src/jvmTest/resources/test.zip and kioarch/src/jvmTest/resources/test.zip)")

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
}

