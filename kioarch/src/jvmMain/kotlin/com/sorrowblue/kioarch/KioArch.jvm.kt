package com.sorrowblue.kioarch

import kotlinx.io.Sink

class JvmArchiveReader(private val source: SeekableSource) : ArchiveReader {
    private val handle: Long

    init {
        handle = KioArchJni.openArchive(source)
        if (handle == 0L) {
            throw IllegalArgumentException("Failed to open archive")
        }
    }

    override fun getEntries(): List<ArchiveEntry> {
        val count = KioArchJni.getEntryCount(handle)
        val list = ArrayList<ArchiveEntry>()
        for (i in 0 until count) {
            val jniInfo = KioArchJni.getEntryInfo(handle, i)
            if (jniInfo != null) {
                list.add(ArchiveEntry(
                    index = jniInfo.index,
                    name = jniInfo.name,
                    size = jniInfo.size,
                    compressedSize = jniInfo.size,
                    isDirectory = jniInfo.isDir,
                    crc = jniInfo.crc
                ))
            }
        }
        return list
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) {
        val success = KioArchJni.extractEntry(handle, entry.index, sink)
        if (!success) {
            throw IllegalStateException("Failed to extract entry: ${entry.name}")
        }
    }

    override fun close() {
        KioArchJni.closeArchive(handle)
    }
}

actual object KioArch {
    actual fun createReader(source: SeekableSource): ArchiveReader {
        return JvmArchiveReader(source)
    }

    actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return JvmArchiveReader(ByteArraySeekableSource(byteArray))
    }
}
