package com.sorrowblue.kioarch

import kotlinx.io.Sink

internal class SeekableArchiveReader(source: SeekableSource) : ArchiveReader {

    private val handle = KioArchJni.openArchive(source)

    private val lock = Any()

    init {
        require(handle != 0L) {
            "Failed to open archive"
        }
    }

    override fun getEntries(): List<ArchiveEntry> = synchronized(lock) {
        val jniEntries = KioArchJni.getEntries(handle)
        val count = jniEntries.index.size
        val list = ArrayList<ArchiveEntry>(count)
        for (i in 0 until count) {
            list.add(
                ArchiveEntry(
                    index = jniEntries.index[i],
                    name = jniEntries.name[i].replace('\\', '/'),
                    size = jniEntries.size[i],
                    compressedSize = jniEntries.size[i],
                    isDirectory = jniEntries.isDir[i],
                    crc = jniEntries.crc[i]
                )
            )
        }
        list
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) {
        synchronized(lock) {
            check(KioArchJni.extractEntry(handle, entry.index, sink)) {
                "Failed to extract entry: ${entry.name}"
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            KioArchJni.closeArchive(handle)
        }
    }
}
