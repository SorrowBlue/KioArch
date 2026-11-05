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

package com.sorrowblue.kioarch.benchmark

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal expect fun initBenchmarkPlatform()

@Suppress("MagicNumber")
internal object BenchmarkArchiveGenerator {
    private val crcTable = IntArray(256).apply {
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            }
            this[n] = c
        }
    }

    private const val DEFAULT_ENTRY_COUNT = 20
    private const val SMALL_ENTRY_SIZE = 4 * 1024
    private const val MEDIUM_ENTRY_SIZE = 128 * 1024
    private const val LARGE_ENTRY_SIZE = 1024 * 1024

    fun generate(format: String, profile: String = "medium"): ByteArray {
        val entrySize = when (profile) {
            "small" -> SMALL_ENTRY_SIZE
            "medium" -> MEDIUM_ENTRY_SIZE
            "large" -> LARGE_ENTRY_SIZE
            else -> error("Unsupported benchmark profile: $profile")
        }
        return generate(format = format, entryCount = DEFAULT_ENTRY_COUNT, entrySize = entrySize)
    }

    fun generate(
        format: String,
        entryCount: Int = DEFAULT_ENTRY_COUNT,
        entrySize: Int = MEDIUM_ENTRY_SIZE
    ): ByteArray = when (format) {
        "zip" -> generateZip(entryCount, entrySize)
        "7z" -> generate7z(entryCount, entrySize)
        "targz" -> generateTarGz(entryCount, entrySize)
        else -> error("Unsupported benchmark archive format: $format")
    }

    private fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var c = -1
        for (i in offset until offset + length) {
            c = crcTable[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c xor -1
    }

    private fun createPayload(seed: Int, size: Int): ByteArray =
        ByteArray(size) { i -> ((i + seed * 17) and 0xFF).toByte() }

    private fun generateZip(entryCount: Int, entrySize: Int): ByteArray {
        val out = Buffer()
        val centralDir = Buffer()
        var localOffset = 0

        for (index in 0 until entryCount) {
            val nameBytes = "entry_$index.bin".encodeToByteArray()
            val payload = createPayload(index, entrySize)
            val crc = crc32(payload)

            // Local file header (Store method = 0)
            out.writeLe32(0x04034B50)
            out.writeLe16(20)
            out.writeLe16(0)
            out.writeLe16(0)
            out.writeLe16(0)
            out.writeLe16(0)
            out.writeLe32(crc)
            out.writeLe32(payload.size)
            out.writeLe32(payload.size)
            out.writeLe16(nameBytes.size)
            out.writeLe16(0)
            out.write(nameBytes)
            out.write(payload)

            // Central directory header
            centralDir.writeLe32(0x02014B50)
            centralDir.writeLe16(20)
            centralDir.writeLe16(20)
            centralDir.writeLe16(0)
            centralDir.writeLe16(0)
            centralDir.writeLe16(0)
            centralDir.writeLe16(0)
            centralDir.writeLe32(crc)
            centralDir.writeLe32(payload.size)
            centralDir.writeLe32(payload.size)
            centralDir.writeLe16(nameBytes.size)
            centralDir.writeLe16(0)
            centralDir.writeLe16(0)
            centralDir.writeLe16(0)
            centralDir.writeLe16(0)
            centralDir.writeLe32(0)
            centralDir.writeLe32(localOffset)
            centralDir.write(nameBytes)

            localOffset += 30 + nameBytes.size + payload.size
        }

        val centralDirBytes = centralDir.readByteArray()
        out.write(centralDirBytes)

        // End of central directory record
        out.writeLe32(0x06054B50)
        out.writeLe16(0)
        out.writeLe16(0)
        out.writeLe16(entryCount)
        out.writeLe16(entryCount)
        out.writeLe32(centralDirBytes.size)
        out.writeLe32(localOffset)
        out.writeLe16(0)

        return out.readByteArray()
    }

    private fun generateTarGz(entryCount: Int, entrySize: Int): ByteArray {
        val tarBuffer = Buffer()
        for (index in 0 until entryCount) {
            val name = "entry_$index.bin"
            val payload = createPayload(index, entrySize)
            val header = ByteArray(512)
            val nameBytes = name.encodeToByteArray()
            nameBytes.copyInto(header, 0, 0, nameBytes.size)
            writeOctal(header, 100, 8, 0b110100100L) // mode 0644
            writeOctal(header, 108, 8, 0L)
            writeOctal(header, 116, 8, 0L)
            writeOctal(header, 124, 12, payload.size.toLong())
            writeOctal(header, 136, 12, 0L)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            "ustar\u0000".encodeToByteArray().copyInto(header, 257)
            "00".encodeToByteArray().copyInto(header, 263)
            var sum = 0L
            for (b in header) sum += (b.toInt() and 0xFF)
            writeOctal(header, 148, 7, sum)
            header[155] = ' '.code.toByte()

            tarBuffer.write(header)
            tarBuffer.write(payload)
            val padding = (512 - (payload.size % 512)) % 512
            if (padding > 0) {
                tarBuffer.write(ByteArray(padding))
            }
        }
        // Two 512-byte zero blocks at EOF
        tarBuffer.write(ByteArray(1024))
        val tarBytes = tarBuffer.readByteArray()

        // Wrap in RFC 1952 GZIP with RFC 1951 stored deflate blocks
        val gz = Buffer()
        gz.writeByte(0x1F.toByte())
        gz.writeByte(0x8B.toByte())
        gz.writeByte(8) // deflate
        gz.writeByte(0) // flags
        gz.writeLe32(0) // mtime
        gz.writeByte(0) // xfl
        gz.writeByte(0xFF.toByte()) // os

        var offset = 0
        val maxBlock = 65535
        while (offset < tarBytes.size) {
            val chunk = minOf(maxBlock, tarBytes.size - offset)
            val isFinal = (offset + chunk) >= tarBytes.size
            gz.writeByte(if (isFinal) 1 else 0)
            gz.writeLe16(chunk)
            gz.writeLe16(chunk.inv() and 0xFFFF)
            gz.write(tarBytes, offset, offset + chunk)
            offset += chunk
        }

        gz.writeLe32(crc32(tarBytes))
        gz.writeLe32(tarBytes.size)
        return gz.readByteArray()
    }

    private fun writeOctal(dest: ByteArray, offset: Int, length: Int, value: Long) {
        val octal = value.toString(8).padStart(length - 1, '0')
        val bytes = octal.encodeToByteArray()
        bytes.copyInto(dest, offset, 0, minOf(bytes.size, length - 1))
        dest[offset + length - 1] = 0
    }

    private fun Buffer.write7zNumber(value: Long) {
        var mask = 0x80
        var firstByte = 0
        var count = 0
        while (count < 8) {
            if (value < (1L shl (7 * (count + 1)))) {
                firstByte = firstByte or ((value ushr (8 * count)).toInt() and (mask - 1))
                break
            }
            firstByte = firstByte or mask
            mask = mask ushr 1
            count++
        }
        writeByte(firstByte.toByte())
        for (i in 0 until count) {
            writeByte(((value ushr (8 * i)) and 0xFF).toByte())
        }
    }

    private fun generate7z(entryCount: Int, entrySize: Int): ByteArray {
        val rawData = Buffer()
        val entryCrcs = IntArray(entryCount)
        for (index in 0 until entryCount) {
            val payload = createPayload(index, entrySize)
            entryCrcs[index] = crc32(payload)
            rawData.write(payload)
        }
        val unpackBytes = rawData.readByteArray()

        // Encode as LZMA2 uncompressed chunks so 7z LZMA2 decoder is exercised
        val packBuffer = Buffer()
        var pos = 0
        var firstChunk = true
        while (pos < unpackBytes.size) {
            val chunk = minOf(65536, unpackBytes.size - pos)
            packBuffer.writeByte(if (firstChunk) 0x01 else 0x02)
            firstChunk = false
            val lenMinus1 = chunk - 1
            packBuffer.writeByte(((lenMinus1 ushr 8) and 0xFF).toByte())
            packBuffer.writeByte((lenMinus1 and 0xFF).toByte())
            packBuffer.write(unpackBytes, pos, pos + chunk)
            pos += chunk
        }
        packBuffer.writeByte(0x00) // LZMA2 end marker
        val packBytes = packBuffer.readByteArray()

        val nextHeader = build7zNextHeader(
            entryCount,
            entrySize,
            packBytes.size,
            unpackBytes.size,
            entryCrcs
        )
        val nextHeaderCrc = crc32(nextHeader)

        val startHeader = Buffer()
        startHeader.writeLe64(packBytes.size.toLong())
        startHeader.writeLe64(nextHeader.size.toLong())
        startHeader.writeLe32(nextHeaderCrc)
        val startHeaderBytes = startHeader.readByteArray()
        val startHeaderCrc = crc32(startHeaderBytes)

        val out = Buffer()
        out.write(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04))
        out.writeLe32(startHeaderCrc)
        out.write(startHeaderBytes)
        out.write(packBytes)
        out.write(nextHeader)
        return out.readByteArray()
    }

    private fun build7zNextHeader(
        entryCount: Int,
        entrySize: Int,
        packSize: Int,
        unpackSize: Int,
        entryCrcs: IntArray
    ): ByteArray {
        val header = Buffer()
        header.writeByte(0x01) // kHeader
        header.writeByte(0x04) // kMainStreamsInfo

        // kPackInfo
        header.writeByte(0x06)
        header.write7zNumber(0L) // PackPos
        header.write7zNumber(1L) // NumPackStreams
        header.writeByte(0x09) // kSize
        header.write7zNumber(packSize.toLong())
        header.writeByte(0x00) // kEnd

        // kUnPackInfo (1 Folder with LZMA2 coder 0x21 and dict prop 0x18)
        header.writeByte(0x07)
        header.writeByte(0x0B) // kFolder
        header.write7zNumber(1L) // NumFolders
        header.writeByte(0x00) // External = 0
        header.write7zNumber(1L) // NumCoders = 1
        header.writeByte(0x21) // Coder flags: 1-byte id + has attributes (0x20 | 0x01)
        header.writeByte(0x21) // k_LZMA2
        header.write7zNumber(1L) // Properties size = 1
        header.writeByte(0x18) // 4 MB dictionary
        header.writeByte(0x0C) // kCodersUnPackSize
        header.write7zNumber(unpackSize.toLong())
        header.writeByte(0x00) // kEnd

        // kSubStreamsInfo
        header.writeByte(0x08)
        header.writeByte(0x0D) // kNumUnPackStream
        header.write7zNumber(entryCount.toLong())
        if (entryCount > 1) {
            header.writeByte(0x09) // kSize
            repeat(entryCount - 1) {
                header.write7zNumber(entrySize.toLong())
            }
        }
        header.writeByte(0x0A) // kCRC
        header.writeByte(0x01) // AllAreDefined = 1
        for (crc in entryCrcs) {
            header.writeLe32(crc)
        }
        header.writeByte(0x00) // kEnd of SubStreamsInfo
        header.writeByte(0x00) // kEnd of MainStreamsInfo

        // kFilesInfo
        header.writeByte(0x05)
        header.write7zNumber(entryCount.toLong())

        val namesPayload = Buffer()
        namesPayload.writeByte(0x00) // External = 0
        for (index in 0 until entryCount) {
            val name = "entry_$index.bin"
            for (ch in name) {
                namesPayload.writeLe16(ch.code)
            }
            namesPayload.writeLe16(0)
        }
        val namesBytes = namesPayload.readByteArray()
        header.writeByte(0x11) // kName
        header.write7zNumber(namesBytes.size.toLong())
        header.write(namesBytes)
        header.writeByte(0x00) // kEnd of FilesInfo
        header.writeByte(0x00) // kEnd of Header
        return header.readByteArray()
    }
}

@Suppress("MagicNumber")
private fun Buffer.writeLe16(value: Int) {
    writeByte((value and 0xFF).toByte())
    writeByte(((value ushr 8) and 0xFF).toByte())
}

@Suppress("MagicNumber")
private fun Buffer.writeLe32(value: Int) {
    writeByte((value and 0xFF).toByte())
    writeByte(((value ushr 8) and 0xFF).toByte())
    writeByte(((value ushr 16) and 0xFF).toByte())
    writeByte(((value ushr 24) and 0xFF).toByte())
}

@Suppress("MagicNumber")
private fun Buffer.writeLe64(value: Long) {
    writeLe32(value.toInt())
    writeLe32((value ushr 32).toInt())
}
