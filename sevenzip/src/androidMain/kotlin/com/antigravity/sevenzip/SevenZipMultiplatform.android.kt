package com.antigravity.sevenzip

import kotlinx.io.Sink

class AndroidArchiveReader(private val source: SeekableSource) : ArchiveReader {
    private val handle: Long

    init {
        handle = SevenZipJni.openArchive(source)
        if (handle == 0L) {
            throw IllegalArgumentException("Failed to open archive")
        }
    }

    override fun getEntries(): List<ArchiveEntry> {
        val count = SevenZipJni.getEntryCount(handle)
        val list = ArrayList<ArchiveEntry>()
        for (i in 0 until count) {
            val jniInfo = SevenZipJni.getEntryInfo(handle, i)
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
        val success = SevenZipJni.extractEntry(handle, entry.index, sink)
        if (!success) {
            throw IllegalStateException("Failed to extract entry: ${entry.name}")
        }
    }

    override fun close() {
        SevenZipJni.closeArchive(handle)
    }
}

actual object SevenZipMultiplatform {
    actual fun createReader(source: SeekableSource): ArchiveReader {
        return AndroidArchiveReader(source)
    }

    actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return AndroidArchiveReader(ByteArraySeekableSource(byteArray))
    }
}
