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

import kotlinx.io.Sink

class JvmArchiveReader(private val source: SeekableSource) : ArchiveReader {
    private val handle: Long
    private val lock = Any()

    init {
        handle = KioArchJni.openArchive(source)
        if (handle == 0L) {
            throw ArchiveInvalidException("Failed to open archive")
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
                throw ArchiveCorruptedException("Failed to extract entry: ${entry.name}")
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
        return JvmArchiveReader(source)
    }

    actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return JvmArchiveReader(ByteArraySeekableSource(byteArray))
    }
}
