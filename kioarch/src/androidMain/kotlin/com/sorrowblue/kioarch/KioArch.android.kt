package com.sorrowblue.kioarch

import kotlinx.io.Sink

class AndroidArchiveReader(private val source: SeekableSource) : ArchiveReader {
    private val handle: Long
    private val lock = Any()

    init {
        handle = KioArchJni.openArchive(source)
        if (handle == 0L) {
            throw IllegalArgumentException("Failed to open archive")
        }
    }

    override fun getEntries(): List<ArchiveEntry> {
        return synchronized(lock) {
            val count = KioArchJni.getEntryCount(handle)
            val list = ArrayList<ArchiveEntry>()
            for (i in 0 until count) {
                val jniInfo = KioArchJni.getEntryInfo(handle, i)
                if (jniInfo != null) {
                    list.add(ArchiveEntry(
                        index = jniInfo.index,
                        name = jniInfo.name.replace('\\', '/'),
                        size = jniInfo.size,
                        compressedSize = jniInfo.size,
                        isDirectory = jniInfo.isDir,
                        crc = jniInfo.crc
                    ))
                }
            }
            list
        }
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) {
        synchronized(lock) {
            val success = KioArchJni.extractEntry(handle, entry.index, sink)
            if (!success) {
                throw IllegalStateException("Failed to extract entry: ${entry.name}")
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            KioArchJni.closeArchive(handle)
        }
    }
}

actual object KioArch {
    actual fun createReader(source: SeekableSource): ArchiveReader {
        return AndroidArchiveReader(source)
    }

    actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return AndroidArchiveReader(ByteArraySeekableSource(byteArray))
    }
}
