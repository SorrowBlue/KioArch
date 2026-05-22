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
        KioArch.loadLibraryLazily()
        handle = KioArchJni.openArchive(source)
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

public actual object KioArch {
    public actual fun createReader(source: SeekableSource): ArchiveReader = AndroidArchiveReader(
        source
    )

    public actual fun createReader(byteArray: ByteArray): ArchiveReader =
        AndroidArchiveReader(ByteArraySeekableSource(byteArray))

    public actual fun createReader(path: kotlinx.io.files.Path): ArchiveReader =
        AndroidArchiveReader(PathSeekableSource(java.nio.file.Paths.get(path.toString())))

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
                    "To avoid potential runtime stutter or performance issues during archive " +
                    "operations, ensure it is loaded early at application startup either via " +
                    "Jetpack App Startup or by calling KioArch.loadLibrary() manually."
            )
            loadLibrary()
        }
    }
}
