/*
 * Copyright 2026 SorrowBlue
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sorrowblue.kioarch

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.toKString
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.usleep
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Force rebuild
private inline fun <T> NSLock.withLock(action: () -> T): T {
    this.lock()
    try {
        return action()
    } finally {
        this.unlock()
    }
}

private fun String.hexToByteArray(): ByteArray {
    val result = ByteArray(length / 2)
    for (i in 0 until length step 2) {
        val firstDigit = this[i].digitToInt(16)
        val secondDigit = this[i + 1].digitToInt(16)
        result[i / 2] = ((firstDigit shl 4) + secondDigit).toByte()
    }
    return result
}

private fun calculateCRC32(data: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()
    for (b in data) {
        val temp = (crc xor b.toInt()) and 0xFF
        var r = temp
        for (i in 0 until 8) {
            r = if ((r and 1) != 0) (r ushr 1) xor 0xEDB88320.toInt() else r ushr 1
        }
        crc = (crc ushr 8) xor r
    }
    return crc xor 0xFFFFFFFF.toInt()
}

private fun createLargeStoreZipBytes(dataSize: Int): ByteArray {
    val name = "large.bin"
    val nameBytes = name.encodeToByteArray()
    
    val data = ByteArray(dataSize)
    for (i in 0 until dataSize) {
        data[i] = (i % 256).toByte()
    }
    val crc = calculateCRC32(data)

    val lfh = Buffer().apply {
        write(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // Signature
        write(byteArrayOf(0x0A, 0x00))             // Version
        write(byteArrayOf(0x00, 0x00))             // Flags
        write(byteArrayOf(0x00, 0x00))             // Method (Store)
        write(byteArrayOf(0xE9.toByte(), 0x8B.toByte())) // Time
        write(byteArrayOf(0x13, 0x59))             // Date
        writeIntLe(crc)                            // CRC-32
        writeIntLe(dataSize)                       // Compressed Size
        writeIntLe(dataSize)                       // Uncompressed Size
        writeShortLe(nameBytes.size.toShort())     // Name Length
        writeShortLe(0.toShort())                  // Extra Length
        write(nameBytes)                           // Name
    }.readByteArray()

    val cdh = Buffer().apply {
        write(byteArrayOf(0x50, 0x4B, 0x01, 0x02)) // Signature
        write(byteArrayOf(0x1E, 0x03))             // Made by
        write(byteArrayOf(0x0A, 0x00))             // Version
        write(byteArrayOf(0x00, 0x00))             // Flags
        write(byteArrayOf(0x00, 0x00))             // Method
        write(byteArrayOf(0xE9.toByte(), 0x8B.toByte())) // Time
        write(byteArrayOf(0x13, 0x59))             // Date
        writeIntLe(crc)                            // CRC-32
        writeIntLe(dataSize)                       // Compressed Size
        writeIntLe(dataSize)                       // Uncompressed Size
        writeShortLe(nameBytes.size.toShort())     // Name Length
        writeShortLe(0.toShort())                  // Extra Length
        writeShortLe(0.toShort())                  // Comment Length
        writeShortLe(0.toShort())                  // Disk Start
        writeShortLe(0.toShort())                  // Internal Attr
        writeIntLe(0)                              // External Attr
        writeIntLe(0)                              // Local Header Offset
        write(nameBytes)                           // Name
    }.readByteArray()

    val eocd = Buffer().apply {
        write(byteArrayOf(0x50, 0x4B, 0x05, 0x06)) // Signature
        writeShortLe(0.toShort())                  // Disk Number
        writeShortLe(0.toShort())                  // CD Disk
        writeShortLe(1.toShort())                  // CD Disk Records
        writeShortLe(1.toShort())                  // CD Records
        writeIntLe(cdh.size)                       // CD Size
        writeIntLe(lfh.size + dataSize)            // CD Offset
        writeShortLe(0.toShort())                  // Comment Length
    }.readByteArray()

    val zipBytes = ByteArray(lfh.size + dataSize + cdh.size + eocd.size)
    var offset = 0
    
    lfh.copyInto(zipBytes, offset)
    offset += lfh.size
    
    data.copyInto(zipBytes, offset)
    offset += data.size
    
    cdh.copyInto(zipBytes, offset)
    offset += cdh.size
    
    eocd.copyInto(zipBytes, offset)
    
    return zipBytes
}

@OptIn(ExperimentalForeignApi::class)
class KioArchIosTest {

    // A tiny ZIP file containing "test.txt" with content "hello" (119 bytes)
    // Correct CRC-32 for "hello" is 0x3610A686 (Little-endian: 86 A6 10 36)
    private val tinyZipBytes = byteArrayOf(
        0x50, 0x4B, 0x03, 0x04, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0xE9.toByte(), 0x8B.toByte(), 0x13, 0x59,
        0x86.toByte(), 0xA6.toByte(), 0x10, 0x36,
        0x05, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x74, 0x65,
        0x73, 0x74, 0x2E, 0x74, 0x78, 0x74, 0x68, 0x65, 0x6C, 0x6C, 0x6F, 0x50, 0x4B, 0x01, 0x02, 0x1E,
        0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0xE9.toByte(), 0x8B.toByte(), 0x13, 0x59,
        0x86.toByte(), 0xA6.toByte(), 0x10, 0x36,
        0x05, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x74, 0x65, 0x73, 0x74, 0x2E, 0x74,
        0x78, 0x74, 0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x36, 0x00,
        0x00, 0x00, 0x2B, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    private val tiny7zBytes = "377ABCAF271C0002DFD819BC5A00000000000000A100000000000000371D9BE30100235468697320697320612064756D6D7920746578742066696C6520696E7369646520377A2E0001002D536F6D65206D6F72652064756D6D7920636F6E74656E7420696E20746865207365636F6E6420377A2066696C652E0001040600020928320A017CC6B237AD08206500070B0200012121011601212101160C242E0A018B37435F431984E9000800000502112D00640075006D006D00790031002E007400780074000000640075006D006D00790032002E0074007800740000001212010000EAC425A7EBDC0100EAC425A7EBDC01131201003F38E725A7EBDC013F38E725A7EBDC01141201009B3BE725A7EBDC01F7F5E725A7EBDC010000".hexToByteArray()

    private val tinyTarGzBytes = "1F8B08000000000000FFED944D0AC23010467B94394199C4341E44C18D9B60461BB0A99829FE9CDE5605A12E5C685B9079040221904926EFF34D555D54CE67CE060315A23506F0417F462C1054A1D1D899D5DD3EA50A3BCB00872BE94593D81D33FCFAACFEE57E50DA182CCB90A01D0E7CF71380E9CCB00D7B821053F004EDEBE4BB6B3E759DC230DCBBAE27F6DFEA77FFE7E2FF182CEA8AA0AA8FF4F47F5347A6C8ADFDC02541A276C13F43E09E0B92047F45A4C4E4D7A7107D7D4AEB83E3F2E761F0D17FAD7AFE1B83E2FF28AC1E8D87AEF1E01DBBA90B1204411046E106D3F7DD9500100000".hexToByteArray()

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
        assertFailsWith<ArchiveIOException> {
            KioArch.createReader(invalidData).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testCorruptedArchiveThrowsException() {
        val bytes = tinyZipBytes.copyOf()
        // Corrupt central directory structure in the tiny ZIP
        for (i in 40 until 80) {
            bytes[i] = 0.toByte()
        }
        assertFailsWith<ArchiveIOException> {
            KioArch.createReader(bytes).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testTinyZipExtraction() {
        KioArch.createReader(tinyZipBytes).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            
            val entry = entries[0]
            assertEquals("test.txt", entry.name)
            assertEquals(5L, entry.size)
            assertEquals(false, entry.isDirectory)

            val buffer = Buffer()
            reader.extractEntry(entry, buffer)
            assertEquals(5L, buffer.size)

            val bytes = buffer.readByteArray()
            assertEquals("hello", bytes.decodeToString())
        }
    }

    @Test
    fun testReal7zExtraction() {
        KioArch.createReader(tiny7zBytes).use { reader ->
            val entries = reader.getEntries()
            assertEquals(2, entries.size)
            
            assertEquals("dummy1.txt", entries[0].name)
            assertEquals(36L, entries[0].size)
            
            assertEquals("dummy2.txt", entries[1].name)
            assertEquals(46L, entries[1].size)

            val buffer = Buffer()
            reader.extractEntry(entries[0], buffer)
            assertEquals(36L, buffer.size)
            assertEquals("This is a dummy text file inside 7z.", buffer.readByteArray().decodeToString())
        }
    }

    @Test
    fun testRealTarGzExtraction() {
        KioArch.createReader(tinyTarGzBytes).use { reader ->
            val entries = reader.getEntries()
            assertEquals(3, entries.size)
            
            assertEquals("dummy1.txt", entries[0].name)
            assertEquals(40L, entries[0].size)
            
            assertEquals("dummy2.txt", entries[1].name)
            assertEquals(50L, entries[1].size)

            assertEquals("nested/windows/path.txt", entries[2].name)
            assertEquals(17L, entries[2].size)

            val buffer = Buffer()
            reader.extractEntry(entries[0], buffer)
            assertEquals(40L, buffer.size)
            assertEquals("This is a dummy text file inside tar.gz.", buffer.readByteArray().decodeToString())
        }
    }

    private fun createTempZipFile(): String {
        val tempDir = NSTemporaryDirectory()
        val tempFilePath = "$tempDir/${NSUUID().UUIDString()}.zip"
        val file = fopen(tempFilePath, "wb") ?: throw IllegalStateException("Failed to create temp file: $tempFilePath")
        try {
            tinyZipBytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1uL, tinyZipBytes.size.toULong(), file)
            }
        } finally {
            fclose(file)
        }
        return tempFilePath
    }

    @Test
    fun testTinyZipExtractionWithPath() {
        val tempPathStr = createTempZipFile()
        try {
            val path = Path(tempPathStr)
            KioArch.createReader(path).use { reader ->
                val entries = reader.getEntries()
                assertEquals(1, entries.size)
                
                val entry = entries[0]
                assertEquals("test.txt", entry.name)
                assertEquals(5L, entry.size)

                val buffer = Buffer()
                reader.extractEntry(entry, buffer)
                assertEquals(5L, buffer.size)

                val bytes = buffer.readByteArray()
                assertEquals("hello", bytes.decodeToString())
            }
        } finally {
            remove(tempPathStr)
        }
    }

    @Test
    fun testThreadSafety() {
        KioArch.createReader(tinyZipBytes).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)

            val numThreads = 10
            val lock = NSLock()
            var finishedThreads = 0

            for (t in 0 until numThreads) {
                NSThread.detachNewThreadWithBlock {
                    try {
                        for (i in 0 until 50) {
                            val list = reader.getEntries()
                            assertEquals(1, list.size)

                            val buffer = Buffer()
                            reader.extractEntry(list[0], buffer)
                            assertEquals("hello", buffer.readByteArray().decodeToString())
                        }
                    } finally {
                        lock.withLock {
                            finishedThreads++
                        }
                    }
                }
            }

            // Wait for all threads to complete (max 5 seconds)
            var totalWaitMs = 0
            while (totalWaitMs < 5000) {
                val done = lock.withLock { finishedThreads == numThreads }
                if (done) break
                usleep(10000u) // wait 10ms
                totalWaitMs += 10
            }

            val done = lock.withLock { finishedThreads == numThreads }
            assertTrue(done, "Not all threads finished in time")
        }
    }

    @Test
    fun testLargeZipExtraction() {
        val dataSize = 10 * 1024 * 1024 // 10MB
        val zipBytes = createLargeStoreZipBytes(dataSize)

        KioArch.createReader(zipBytes).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            
            val entry = entries[0]
            assertEquals("large.bin", entry.name)
            assertEquals(dataSize.toLong(), entry.size)

            val buffer = Buffer()
            reader.extractEntry(entry, buffer)
            assertEquals(dataSize.toLong(), buffer.size)

            // Verify content integrity chunk by chunk
            var verifiedBytes = 0L
            val chunk = ByteArray(1024 * 1024)
            while (buffer.size > 0) {
                val toRead = if (buffer.size > chunk.size) chunk.size else buffer.size.toInt()
                val readBytes = buffer.readAtMostTo(chunk, 0, toRead)
                for (i in 0 until readBytes) {
                    assertEquals(((verifiedBytes + i) % 256).toByte(), chunk[i])
                }
                verifiedBytes += readBytes
            }
            assertEquals(dataSize.toLong(), verifiedBytes)
        }
    }

    @Test
    fun testLarge7zExtraction() {
        val pathStr = getenv("LARGE_7Z_PATH")?.toKString()
        if (pathStr == null) {
            println("Skipping testLarge7zExtraction because LARGE_7Z_PATH is not set")
            return
        }

        val path = Path(pathStr)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            val entry = entries[0]
            assertEquals("large_dummy.bin", entry.name)
            assertEquals(10 * 1024 * 1024L, entry.size)

            val buffer = Buffer()
            reader.extractEntry(entry, buffer)
            assertEquals(10 * 1024 * 1024L, buffer.size)

            var verifiedBytes = 0L
            val chunk = ByteArray(1024 * 1024)
            while (buffer.size > 0) {
                val toRead = if (buffer.size > chunk.size) chunk.size else buffer.size.toInt()
                val readBytes = buffer.readAtMostTo(chunk, 0, toRead)
                for (i in 0 until readBytes) {
                    assertEquals(((verifiedBytes + i) % 256).toByte(), chunk[i])
                }
                verifiedBytes += readBytes
            }
            assertEquals(10 * 1024 * 1024L, verifiedBytes)
        }
    }

    @Test
    fun testLargeTarGzExtraction() {
        val pathStr = getenv("LARGE_TARGZ_PATH")?.toKString()
        if (pathStr == null) {
            println("Skipping testLargeTarGzExtraction because LARGE_TARGZ_PATH is not set")
            return
        }

        val path = Path(pathStr)
        KioArch.createReader(path).use { reader ->
            val entries = reader.getEntries()
            assertEquals(1, entries.size)
            val entry = entries[0]
            assertEquals("large_dummy.bin", entry.name)
            assertEquals(10 * 1024 * 1024L, entry.size)

            val buffer = Buffer()
            reader.extractEntry(entry, buffer)
            assertEquals(10 * 1024 * 1024L, buffer.size)

            var verifiedBytes = 0L
            val chunk = ByteArray(1024 * 1024)
            while (buffer.size > 0) {
                val toRead = if (buffer.size > chunk.size) chunk.size else buffer.size.toInt()
                val readBytes = buffer.readAtMostTo(chunk, 0, toRead)
                for (i in 0 until readBytes) {
                    assertEquals(((verifiedBytes + i) % 256).toByte(), chunk[i])
                }
                verifiedBytes += readBytes
            }
            assertEquals(10 * 1024 * 1024L, verifiedBytes)
        }
    }
}
