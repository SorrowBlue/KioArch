package com.sorrowblue.kioarch

import java.io.File
import java.io.RandomAccessFile

/**
 * A JVM-specific [SeekableSource] implementation wrapping a [RandomAccessFile] for disk-based random access.
 */
class FileSeekableSource(file: File) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return raf.read(buffer, offset, length)
    }

    override fun seek(position: Long) {
        raf.seek(position)
    }

    override fun position(): Long = raf.filePointer

    override fun length(): Long = raf.length()

    override fun close() {
        raf.close()
    }
}

/**
 * Extension helper to create an [ArchiveReader] directly from a JVM [File].
 */
fun KioArch.createReader(file: File): ArchiveReader {
    return createReader(FileSeekableSource(file))
}
