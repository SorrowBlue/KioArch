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
import com.sorrowblue.kioarch.internal.cinterop.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    val numFiles = 100
    val sizePerFile = dataSize / numFiles
    
    val nameBytesList = List(numFiles) { i -> "large_$i.bin".encodeToByteArray() }
    val dataList = List(numFiles) { i ->
        ByteArray(sizePerFile) { b -> ((b + i * 10) % 256).toByte() }
    }
    val crcList = dataList.map { calculateCRC32(it) }

    // Build local headers and calculate offsets
    val lfhList = mutableListOf<ByteArray>()
    val lfhOffsets = mutableListOf<Int>()
    var currentOffset = 0

    for (i in 0 until numFiles) {
        lfhOffsets.add(currentOffset)
        val lfh = Buffer().apply {
            write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            write(byteArrayOf(0x0A, 0x00))
            write(byteArrayOf(0x00, 0x00))
            write(byteArrayOf(0x00, 0x00))
            write(byteArrayOf(0xE9.toByte(), 0x8B.toByte()))
            write(byteArrayOf(0x13, 0x59))
            writeIntLe(crcList[i])
            writeIntLe(sizePerFile)
            writeIntLe(sizePerFile)
            writeShortLe(nameBytesList[i].size.toShort())
            writeShortLe(0.toShort())
            write(nameBytesList[i])
        }.readByteArray()
        lfhList.add(lfh)
        currentOffset += lfh.size + sizePerFile
    }

    val cdOffset = currentOffset

    // Build central directory headers
    val cdhList = mutableListOf<ByteArray>()
    var cdSize = 0
    for (i in 0 until numFiles) {
        val cdh = Buffer().apply {
            write(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
            write(byteArrayOf(0x1E, 0x03))
            write(byteArrayOf(0x0A, 0x00))
            write(byteArrayOf(0x00, 0x00))
            write(byteArrayOf(0x00, 0x00))
            write(byteArrayOf(0xE9.toByte(), 0x8B.toByte()))
            write(byteArrayOf(0x13, 0x59))
            writeIntLe(crcList[i])
            writeIntLe(sizePerFile)
            writeIntLe(sizePerFile)
            writeShortLe(nameBytesList[i].size.toShort())
            writeShortLe(0.toShort())
            writeShortLe(0.toShort())
            writeShortLe(0.toShort())
            writeShortLe(0.toShort())
            writeIntLe(0)
            writeIntLe(lfhOffsets[i])
            write(nameBytesList[i])
        }.readByteArray()
        cdhList.add(cdh)
        cdSize += cdh.size
    }

    val eocd = Buffer().apply {
        write(byteArrayOf(0x50, 0x4B, 0x05, 0x06))
        writeShortLe(0.toShort())
        writeShortLe(0.toShort())
        writeShortLe(numFiles.toShort())
        writeShortLe(numFiles.toShort())
        writeIntLe(cdSize)
        writeIntLe(cdOffset)
        writeShortLe(0.toShort())
    }.readByteArray()

    val zipBytes = ByteArray(cdOffset + cdSize + eocd.size)
    var offset = 0

    for (i in 0 until numFiles) {
        lfhList[i].copyInto(zipBytes, offset)
        offset += lfhList[i].size
        dataList[i].copyInto(zipBytes, offset)
        offset += dataList[i].size
    }

    for (i in 0 until numFiles) {
        cdhList[i].copyInto(zipBytes, offset)
        offset += cdhList[i].size
    }

    eocd.copyInto(zipBytes, offset)

    return zipBytes
}

@OptIn(ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
class ArchiveReaderIosTest {

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
            ArchiveReaderTestSpec.verifyZipExtraction(reader, 1)
        }
    }

    @Test
    fun testReal7zExtraction() {
        KioArch.createReader(tiny7zBytes).use { reader ->
            ArchiveReaderTestSpec.verify7zExtraction(reader, 2)
        }
    }

    @Test
    fun testRealTarGzExtraction() {
        KioArch.createReader(tinyTarGzBytes).use { reader ->
            ArchiveReaderTestSpec.verifyTarGzExtraction(reader, 3)
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
                ArchiveReaderTestSpec.verifyZipExtraction(reader, 1)
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

            var totalWaitMs = 0
            while (totalWaitMs < 5000) {
                val done = lock.withLock { finishedThreads == numThreads }
                if (done) break
                usleep(10000u)
                totalWaitMs += 10
            }

            val done = lock.withLock { finishedThreads == numThreads }
            assertTrue(done, "Not all threads finished in time")
        }
    }

    @Test
    fun testLargeZipExtraction() {
        val dataSize = 10 * 1024 * 1024
        val numFiles = 100
        val sizePerFile = dataSize / numFiles.toLong()
        val zipBytes = createLargeStoreZipBytes(dataSize)

        KioArch.createReader(zipBytes).use { reader ->
            ArchiveReaderTestSpec.verifyLargeExtraction(reader, numFiles, sizePerFile, "large_")
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
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles
        KioArch.createReader(path).use { reader ->
            ArchiveReaderTestSpec.verifyLargeExtraction(reader, numFiles, sizePerFile)
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
        val numFiles = 100
        val sizePerFile = (10 * 1024 * 1024L) / numFiles
        KioArch.createReader(path).use { reader ->
            ArchiveReaderTestSpec.verifyLargeExtraction(reader, numFiles, sizePerFile)
        }
    }

    @Test
    fun testLeakBrokenArchive() {
        val bytes = tinyZipBytes.copyOf()
        for (i in 40 until 80) {
            bytes[i] = 0.toByte()
        }

        val initialMem = kio_get_resident_memory().toLong()
        println("testLeakBrokenArchive - Initial Memory: ${initialMem / 1024 / 1024} MB")

        repeat(200) {
            assertFailsWith<ArchiveIOException> {
                KioArch.createReader(bytes).use { reader ->
                    reader.getEntries()
                }
            }
        }

        kotlin.native.runtime.GC.collect()

        val finalMem = kio_get_resident_memory().toLong()
        val growth = finalMem - initialMem
        println("testLeakBrokenArchive - Final Memory: ${finalMem / 1024 / 1024} MB (Growth: ${growth / 1024 / 1024} MB)")

        assertTrue(growth < 5 * 1024 * 1024, "Memory leak detected in error handling paths! Growth: ${growth / 1024 / 1024} MB")
    }

    @Test
    fun testLeakLargeArchive() {
        val pathStr = getenv("LARGE_7Z_PATH")?.toKString()
        if (pathStr == null) {
            println("Skipping testLeakLargeArchive because LARGE_7Z_PATH is not set")
            return
        }

        val path = Path(pathStr)
        val initialMem = kio_get_resident_memory().toLong()
        println("testLeakLargeArchive - Initial Memory: ${initialMem / 1024 / 1024} MB")

        repeat(20) { i ->
            KioArch.createReader(path).use { reader ->
                val entries = reader.getEntries()
                val entry = entries.firstOrNull { !it.isDirectory }
                if (entry != null) {
                    val buffer = Buffer()
                    reader.extractEntry(entry, buffer)
                }
            }
            kotlin.native.runtime.GC.collect()
            val currentMem = kio_get_resident_memory().toLong()
            if ((i + 1) % 5 == 0) {
                println("testLeakLargeArchive - Iteration ${i + 1} Memory: ${currentMem / 1024 / 1024} MB")
            }
        }

        kotlin.native.runtime.GC.collect()
        val finalMem = kio_get_resident_memory().toLong()
        val growth = finalMem - initialMem
        println("testLeakLargeArchive - Final Memory: ${finalMem / 1024 / 1024} MB (Growth: ${growth / 1024 / 1024} MB)")

        assertTrue(growth < 15 * 1024 * 1024, "Memory leak detected in large file extraction! Growth: ${growth / 1024 / 1024} MB")
    }
}
