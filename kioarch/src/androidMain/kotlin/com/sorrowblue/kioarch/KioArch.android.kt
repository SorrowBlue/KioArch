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

import android.util.Log
import kotlinx.io.Sink

internal class AndroidArchiveReader(private val source: SeekableSource) : ArchiveReader {
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

public actual object KioArch {
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        return AndroidArchiveReader(source)
    }

    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return AndroidArchiveReader(ByteArraySeekableSource(byteArray))
    }

    private var isLoaded = false

    @Synchronized
    public fun loadLibrary() {
        if (!isLoaded) {
            System.loadLibrary("kioarch")
            Log.d("KioArch", "KioArch loaded.")
            isLoaded = true
        }
    }

    @Synchronized
    internal fun loadLibraryLazily() {
        if (!isLoaded) {
            Log.w(
                "KioArch",
                "KioArch JNI library is being loaded lazily on-demand. " +
                "To avoid potential runtime stutter or performance issues during archive operations, " +
                "ensure it is loaded early at application startup either via Jetpack App Startup " +
                "or by calling KioArch.loadLibrary() manually."
            )
            loadLibrary()
        }
    }
}
