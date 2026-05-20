package com.antigravity.sevenzip

/**
 * An in-memory implementation of [SeekableSource] wrapping a [ByteArray].
 */
class ByteArraySeekableSource(private val bytes: ByteArray) : SeekableSource {
    private var pos = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= bytes.size) return -1
        val available = bytes.size - pos
        val toRead = if (length > available) available.toInt() else length
        for (i in 0 until toRead) {
            buffer[offset + i] = bytes[(pos + i).toInt()]
        }
        pos += toRead
        return toRead
    }

    override fun seek(position: Long) {
        pos = if (position < 0) 0L else if (position > bytes.size) bytes.size.toLong() else position
    }

    override fun position(): Long = pos

    override fun length(): Long = bytes.size.toLong()

    override fun close() {
        // No resources to release
    }
}

expect object SevenZipMultiplatform {
    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource].
     */
    fun createReader(source: SeekableSource): ArchiveReader

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray].
     */
    fun createReader(byteArray: ByteArray): ArchiveReader
}
