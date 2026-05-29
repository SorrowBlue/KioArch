package com.sorrowblue.kioarch

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Common test specification for [ArchiveReader].
 */
object ArchiveReaderTestSpec {

    private val sjisEdgeCaseNames = listOf(
        "テスト_日本語ファイル名_Shift_JIS.txt",
        "dame_moji_ソ表能予.txt",
        "half_width_ｶﾀｶﾅﾃｽﾄ.txt",
        "cp932_extensions_①Ⅳ髙﨑.txt"
    )

    private fun calculateCrc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            val temp = (crc xor b.toInt()) and 0xFF
            var r = temp
            repeat(8) {
                r = if ((r and 1) != 0) (r ushr 1) xor 0xEDB88320.toInt() else r ushr 1
            }
            crc = (crc ushr 8) xor r
        }
        return (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
    }

    fun verifySupportedExtensions(extensions: List<String>) {
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

    fun verifyZipExtraction(reader: ArchiveReader, expectedEntriesSize: Int? = null) {
        val entries = reader.getEntries()
        assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")
        if (expectedEntriesSize != null) {
            assertEquals(expectedEntriesSize, entries.size)
        }

        val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
        if (fileEntry != null) {
            val buffer = Buffer()
            reader.extractEntry(fileEntry, buffer)
            assertEquals(fileEntry.size, buffer.size)

            val extractedBytes = buffer.readByteArray()
            if (fileEntry.crc != 0L) {
                assertEquals(fileEntry.crc, calculateCrc32(extractedBytes))
            }
        } else {
            assertTrue(false, "No non-empty file entries found to test extraction.")
        }
    }

    fun verify7zExtraction(reader: ArchiveReader, expectedEntriesSize: Int? = null) {
        val entries = reader.getEntries()
        assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")
        if (expectedEntriesSize != null) {
            assertEquals(expectedEntriesSize, entries.size)
        }

        val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
        if (fileEntry != null) {
            val buffer = Buffer()
            reader.extractEntry(fileEntry, buffer)
            assertEquals(fileEntry.size, buffer.size)

            val extractedBytes = buffer.readByteArray()
            if (fileEntry.crc != 0L) {
                assertEquals(fileEntry.crc, calculateCrc32(extractedBytes))
            }
        } else {
            assertTrue(false, "No non-empty file entries found to test extraction.")
        }
    }

    fun verifyTarGzExtraction(reader: ArchiveReader, expectedEntriesSize: Int? = null) {
        val entries = reader.getEntries()
        assertTrue(entries.isNotEmpty(), "Archive should have at least one entry")
        if (expectedEntriesSize != null) {
            assertEquals(expectedEntriesSize, entries.size)
        }

        // Validate structure details if specific test.tar.gz is loaded
        if (entries.size == 3) {
            assertEquals("dummy1.txt", entries[0].name)
            assertEquals("dummy2.txt", entries[1].name)
            assertEquals("nested/windows/path.txt", entries[2].name)

            val buffer1 = Buffer()
            reader.extractEntry(entries[0], buffer1)
            assertEquals(
                "This is a dummy text " +
                    "file inside tar.gz.",
                buffer1.readByteArray().decodeToString()
            )

            val buffer2 = Buffer()
            reader.extractEntry(entries[1], buffer2)
            assertEquals(
                "Some more dummy content " +
                    "in the second tar.gz file.",
                buffer2.readByteArray().decodeToString()
            )

            val buffer3 = Buffer()
            reader.extractEntry(entries[2], buffer3)
            assertEquals("Windows path data", buffer3.readByteArray().decodeToString())
        } else {
            val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
            if (fileEntry != null) {
                val buffer = Buffer()
                reader.extractEntry(fileEntry, buffer)
                assertEquals(fileEntry.size, buffer.size)
            }
        }
    }

    fun verifyBzip2Extraction(reader: ArchiveReader) {
        val entries = reader.getEntries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("extracted_data", entry.name)
        assertEquals(-1L, entry.size)
        assertEquals(false, entry.isDirectory)

        val buffer = Buffer()
        reader.extractEntry(entry, buffer)
        val decompressed = buffer.readByteArray().decodeToString()
        assertEquals("This is a dummy text file compressed using bzip2.", decompressed)
    }

    fun verifyTarBz2Extraction(reader: ArchiveReader) {
        val entries = reader.getEntries()
        assertEquals(2, entries.size)
        assertEquals("dummy1.txt", entries[0].name)
        assertEquals("dummy2.txt", entries[1].name)

        val buffer1 = Buffer()
        reader.extractEntry(entries[0], buffer1)
        assertEquals(
            "This is a dummy text " +
                "file inside tar.bz2.",
            buffer1.readByteArray().decodeToString()
        )

        val buffer2 = Buffer()
        reader.extractEntry(entries[1], buffer2)
        assertEquals(
            "Some more dummy content " +
                "in the second tar.bz2 file.",
            buffer2.readByteArray().decodeToString()
        )
    }

    fun verifyZipShiftJisFilename(reader: ArchiveReader) {
        val entries = reader.getEntries()
        assertEquals(sjisEdgeCaseNames.size, entries.size)

        for (i in sjisEdgeCaseNames.indices) {
            assertEquals(sjisEdgeCaseNames[i], entries[i].name)
            val buffer = Buffer()
            reader.extractEntry(entries[i], buffer)
            val extractedBytes = buffer.readByteArray()
            assertEquals("hello", extractedBytes.decodeToString())
        }
    }

    fun verifyPathNormalization(reader: ArchiveReader) {
        val entries = reader.getEntries()
        assertEquals(2, entries.size)
        assertEquals("directory/subdir/file1.txt", entries[0].name)
        assertEquals("directory/subdir/file2.txt", entries[1].name)
    }

    fun verifyLargeExtraction(
        reader: ArchiveReader,
        expectedNumFiles: Int,
        sizePerFile: Long,
        filenamePrefix: String = "large_dummy_",
        verifyContent: Boolean = true
    ) {
        val entries = reader.getEntries()
        assertEquals(expectedNumFiles, entries.size)

        for (i in 0 until expectedNumFiles) {
            val entry = entries[i]
            assertEquals("$filenamePrefix$i.bin", entry.name)
            assertEquals(sizePerFile, entry.size)
            val buffer = Buffer()
            reader.extractEntry(entry, buffer)
            if (verifyContent) {
                verifyLargeBuffer(buffer, sizePerFile, i * 10)
            } else {
                assertEquals(sizePerFile, buffer.size)
            }
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

    fun verifyArchiveEntryExtractExtension(reader: ArchiveReader, expectedContent: String) {
        val entries = reader.getEntries()
        assertTrue(entries.isNotEmpty())
        val entry = entries[0]
        val buffer = Buffer()
        entry.extract(reader, buffer)
        assertEquals(expectedContent, buffer.readByteArray().decodeToString())
    }
}
