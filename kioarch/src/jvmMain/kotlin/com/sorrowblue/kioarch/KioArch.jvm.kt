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
import java.nio.file.Paths
import kotlinx.io.files.Path

public actual object KioArch {
    @Suppress("MagicNumber")
    private fun isBzip2(source: SeekableSource): Boolean {
        val current = source.position()
        source.seek(0)
        val buf = ByteArray(3)
        val read = source.read(buf, 0, 3)
        source.seek(current)
        return read >= 3 &&
            buf[0] == 0x42.toByte() &&
            buf[1] == 0x5A.toByte() &&
            buf[2] == 0x68.toByte()
    }

    private fun wrapIfNeeded(source: SeekableSource, reader: ArchiveReader): ArchiveReader =
        if (isBzip2(source)) Bzip2ArchiveReader(reader) else reader

    public actual fun createReader(source: SeekableSource): ArchiveReader {
        loadLibrary()
        return wrapIfNeeded(source, SeekableArchiveReader(source))
    }

    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        loadLibrary()
        val source = ByteArraySeekableSource(byteArray)
        return wrapIfNeeded(source, SeekableArchiveReader(source))
    }

    public actual fun createReader(path: Path): ArchiveReader {
        loadLibrary()
        val source = PathSeekableSource(Paths.get(path.toString()))
        return wrapIfNeeded(source, SeekableArchiveReader(source))
    }

    @Suppress("UnusedPrivateProperty", "VarCouldBeVal")
    private var loaded = false

    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    internal fun loadLibrary() {
        if (loaded) return
        try {
            val os = System.getProperty("os.name").lowercase()
            System.getProperty("os.arch").lowercase()

            // Resolve the resource subdirectory based on OS and architecture
            val (dir, ext) = when {
                os.contains("win") -> "windows/amd64" to "dll"

                os.contains("mac") || os.contains("darwin") ->
                    "macos/universal" to "dylib"

                os.contains("linux") -> "linux/amd64" to "so"

                else -> throwUnsupportedOS(os)
            }

            val prefix = if (os.contains("win")) "" else "lib"
            val resourcePath = "/natives/$dir/${prefix}kioarch.$ext"
            val inputStream = KioArch::class.java.getResourceAsStream(resourcePath)
                ?: throwLibraryNotFound(resourcePath)

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
            throw UnsatisfiedLinkError(
                "Failed to load native KioArch library: ${e.message}"
            ).apply {
                initCause(e)
            }
        }
    }

    /**
     * Throws an [UnsupportedOperationException] when the current OS is not supported.
     *
     * @param os the name of the operating system.
     * @throws UnsupportedOperationException always.
     */
    private fun throwUnsupportedOS(os: String): Nothing =
        throw UnsupportedOperationException("Unsupported OS: $os")

    /**
     * Throws an [IllegalStateException] when the native library resource is not found.
     *
     * @param path the resource path that was searched.
     * @throws IllegalStateException always.
     */
    private fun throwLibraryNotFound(path: String): Nothing = error(
        "Native library not found in classpath resources: $path"
    )
}
