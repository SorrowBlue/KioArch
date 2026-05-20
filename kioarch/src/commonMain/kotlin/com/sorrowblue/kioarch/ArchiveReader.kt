package com.sorrowblue.kioarch

import kotlinx.io.Sink

interface ArchiveReader : AutoCloseable {
    /**
     * Returns a list of all entries inside the archive.
     */
    fun getEntries(): List<ArchiveEntry>

    /**
     * Extracts the content of the specified entry and writes it into the kotlinx.io.Sink.
     */
    fun extractEntry(entry: ArchiveEntry, sink: Sink)
}
