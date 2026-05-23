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

import java.io.File
import java.io.FileOutputStream

public actual object KioArch {
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        loadLibrary()
        return SeekableArchiveReader(source)
    }

    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        loadLibrary()
        return SeekableArchiveReader(ByteArraySeekableSource(byteArray))
    }

    public actual fun createReader(path: kotlinx.io.files.Path): ArchiveReader {
        loadLibrary()
        return SeekableArchiveReader(PathSeekableSource(java.nio.file.Paths.get(path.toString())))
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
