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

package com.sorrowblue.kioarch

import java.nio.ByteBuffer
import kotlinx.io.Sink
import kotlinx.io.write

internal class SeekableArchiveReader(
    private val source: SeekableSource,
    useDirectOutput: Boolean = true
) : ArchiveReader {

    private val handle = KioArchJni.openArchive(source)

    private val lock = Any()

    private var isClosed = false

    private var directBuffer: ByteBuffer? =
        if (useDirectOutput) ByteBuffer.allocateDirect(DIRECT_BUFFER_SIZE) else null

    init {
        require(handle != 0L) {
            "Failed to open archive"
        }
    }

    override fun getEntries(): List<ArchiveEntry> = synchronized(lock) {
        check(!isClosed) { "Reader is closed" }
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
            check(!isClosed) { "Reader is closed" }
            val workBuffer = directBuffer
            val success = if (workBuffer != null) {
                KioArchJni.extractEntryDirect(
                    handle,
                    entry.index,
                    workBuffer
                ) { buffer, length ->
                    var offset = 0
                    while (offset < length) {
                        val chunk = minOf(WRITE_CHUNK_SIZE, length - offset)
                        buffer.limit(offset + chunk)
                        buffer.position(offset)
                        while (buffer.hasRemaining()) {
                            sink.write(buffer)
                        }
                        offset += chunk
                    }
                }
            } else {
                KioArchJni.extractEntry(handle, entry.index, sink)
            }
            check(success) {
                "Failed to extract entry: ${entry.name}"
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                isClosed = true
                directBuffer = null
                try {
                    KioArchJni.closeArchive(handle)
                } finally {
                    source.close()
                }
            }
        }
    }

    private companion object {
        private const val DIRECT_BUFFER_SIZE = 64 * 1024
        private const val WRITE_CHUNK_SIZE = 32 * 1024
    }
}
