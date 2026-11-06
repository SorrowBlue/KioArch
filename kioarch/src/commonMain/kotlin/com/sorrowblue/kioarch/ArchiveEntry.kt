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

@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package com.sorrowblue.kioarch

import kotlin.native.ObjCName
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray

/**
 * Metadata describing a single file or directory entry within an archive.
 *
 * Instances are retrieved via [ArchiveReader.getEntries] and passed to
 * [ArchiveReader.extractEntry] (or the [extract] / [extractToByteArray] extensions) to decompress
 * the entry's payload.
 */
public data class ArchiveEntry(
    /**
     * Zero-based positional index of this entry within the archive's entry table.
     */
    val index: Int,
    /**
     * Relative path of the entry inside the archive, normalized to use forward slashes (`/`).
     */
    val name: String,
    /**
     * Uncompressed size of the entry in bytes, or `0` for directories.
     */
    val size: Long,
    /**
     * Compressed size of the entry in bytes as reported by the archive backend.
     */
    val compressedSize: Long,
    /**
     * `true` if this entry represents a directory; `false` if it represents a regular file.
     */
    val isDirectory: Boolean,
    /**
     * Unsigned 32-bit CRC-32 checksum of the uncompressed entry data stored in the lower 32 bits,
     * or `0` for formats that do not record per-entry CRC values (such as `.tar.gz` or `.bz2`).
     */
    val crc: Long
)

/**
 * Extracts the uncompressed content of this entry from [reader] and writes it into [sink].
 *
 * This is a convenience extension equivalent to calling `reader.extractEntry(this, sink)`.
 *
 * @param reader the open [ArchiveReader] from which this entry originated.
 * @param sink the destination [Sink] that receives the decompressed bytes.
 * @throws ArchiveException if decompression fails or an I/O error occurs.
 * @throws IllegalStateException if [reader] has already been closed.
 */
@ObjCName(name = "extract", swiftName = "extract")
public fun ArchiveEntry.extract(reader: ArchiveReader, sink: Sink) {
    reader.extractEntry(this, sink)
}

/**
 * Extracts the uncompressed content of this entry from [reader] into a new in-memory [ByteArray].
 *
 * Because the entire entry payload is buffered in memory, prefer streaming via [extract] with a
 * file or network [Sink] when extracting large entries.
 *
 * ```kotlin
 * KioArch.createReader(archivePath).use { reader ->
 *     val entry = reader.getEntries().first { !it.isDirectory }
 *     val bytes = entry.extractToByteArray(reader)
 * }
 * ```
 *
 * @param reader the open [ArchiveReader] from which this entry originated.
 * @return a [ByteArray] containing the decompressed bytes of this entry.
 * @throws ArchiveException if decompression fails or an I/O error occurs.
 * @throws IllegalStateException if [reader] has already been closed.
 */
@ObjCName(name = "extractToByteArray", swiftName = "extractToByteArray")
public fun ArchiveEntry.extractToByteArray(reader: ArchiveReader): ByteArray {
    val buffer = Buffer()
    reader.extractEntry(this, buffer)
    return buffer.readByteArray()
}
