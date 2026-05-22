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
import java.io.File
import java.io.FileOutputStream

internal class JvmArchiveReader(private val source: SeekableSource) : ArchiveReader {
    private val handle: Long
    private val lock = Any()

    init {
        KioArch.loadLibrary()
        handle = KioArchJni.openArchive(source)
        require(handle != 0L) {
            "Failed to open archive"
        }
    }

    override fun getEntries(): List<ArchiveEntry> {
        return synchronized(lock) {
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
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        return JvmArchiveReader(source)
    }

    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return JvmArchiveReader(ByteArraySeekableSource(byteArray))
    }

    private var loaded = false

    @Synchronized
    internal fun loadLibrary() {
        if (loaded) return
        try {
            val os = System.getProperty("os.name").lowercase()
            System.getProperty("os.arch").lowercase()

            // Resolve the resource subdirectory based on OS and architecture
            val (dir, ext) = when {
                os.contains("win") -> "windows/amd64" to "dll"
                os.contains("linux") -> "linux/amd64" to "so"
                else -> throw UnsupportedOperationException("Unsupported OS: $os")
            }

            val prefix = if (os.contains("win")) "" else "lib"
            val resourcePath = "/natives/$dir/${prefix}kioarch.$ext"
            val inputStream = KioArch::class.java.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("Native library not found in classpath resources: $resourcePath")

            // Copy resource to a temporary file
            val tempFile = File.createTempFile("libkioarch", ".$ext")
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            // Load the dynamic native library
            System.load(tempFile.absolutePath)
            loaded = true
        } catch (e: Exception) {
            throw UnsatisfiedLinkError("Failed to load native KioArch library: ${e.message}").apply {
                initCause(e)
            }
        }
    }
}
