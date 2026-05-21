package com.sorrowblue.kioarch

import kotlinx.io.Sink

data class ArchiveEntry(
    val index: Int,
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val crc: Long
)

/**
 * Extracts the content of this entry using the specified [reader] and writes it into the [sink].
 *
 * @param reader the [ArchiveReader] that contains this entry
 * @param sink the [Sink] to write the extracted content to
 */
fun ArchiveEntry.extract(reader: ArchiveReader, sink: Sink) {
    reader.extractEntry(this, sink)
}
