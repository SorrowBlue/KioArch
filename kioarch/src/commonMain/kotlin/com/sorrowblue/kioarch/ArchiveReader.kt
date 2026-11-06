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
import kotlinx.io.Sink

/**
 * Reads metadata and extracts entries on demand from an open archive.
 *
 * An `ArchiveReader` holds native decoder state and an underlying [SeekableSource]. Always close
 * the reader when finished—typically via `.use { ... }`—to release native memory and close the
 * underlying [SeekableSource].
 *
 * ### Thread Safety and Re-entrancy
 * Implementations synchronize access to the underlying native handle so concurrent calls across
 * threads are serialized safely. However, invoking `ArchiveReader` methods re-entrantly from
 * within a [SeekableSource] or [Sink] callback during an active operation is prohibited and throws
 * [IllegalStateException].
 *
 * ### Usage Example
 *
 * ```kotlin
 * KioArch.createReader(archiveBytes).use { reader ->
 *     val target = reader.getEntries().first { !it.isDirectory && it.name.endsWith(".txt") }
 *     val buffer = kotlinx.io.Buffer()
 *     reader.extractEntry(target, buffer)
 *     println(buffer.readString())
 * }
 * ```
 *
 * @see KioArch.createReader
 * @see ArchiveEntry
 */
public interface ArchiveReader : AutoCloseable {
    /**
     * Returns all file and directory entries recorded in the archive.
     *
     * Entry path separators are normalized to Unix-style forward slashes (`/`) across all archive
     * formats and platforms.
     *
     * @return a list of [ArchiveEntry] descriptors in the order they appear in the archive.
     * @throws ArchiveException if reading or decoding the archive's entry table fails.
     * @throws IllegalStateException if this reader has already been closed or is called
     *   re-entrantly from an active I/O callback.
     */
    public fun getEntries(): List<ArchiveEntry>

    /**
     * Decompresses the specified [entry] and streams its raw content into [sink].
     *
     * Extraction is performed in fixed-size chunks without materializing the entire uncompressed
     * entry in heap memory.
     *
     * @param entry the [ArchiveEntry] obtained from [getEntries] to extract.
     * @param sink the destination [Sink] that receives decompressed bytes.
     * @throws ArchiveException if decompression fails, CRC verification fails, or an I/O error
     *   occurs in the underlying source or sink.
     * @throws IllegalStateException if this reader has already been closed or is called
     *   re-entrantly from an active I/O callback.
     */
    @ObjCName(name = "extractEntry", swiftName = "extract")
    public fun extractEntry(entry: ArchiveEntry, sink: Sink)
}
