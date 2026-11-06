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

/**
 * JVM implementation of [KioArch] backed by JNI bindings to the native C decompression engine.
 *
 * On first invocation of [createReader], the embedded platform native library (`.dll` on Windows,
 * `.dylib` on macOS, or `.so` on Linux) is automatically extracted from classpath resources and
 * loaded into the JVM process if not already present on `java.library.path`.
 */
public actual object KioArch {
    /**
     * Creates an [ArchiveReader] reading from the given [source] on JVM.
     *
     * Passing a [DirectSeekableSource] (such as [FileSeekableSource] or [PathSeekableSource])
     * enables zero-copy reads across the JNI boundary via direct `ByteBuffer` memory.
     *
     * @param source the random-access data source containing the archive bytes.
     * @return a new [ArchiveReader] for reading the archive.
     * @throws ArchiveException if the archive cannot be opened or its format is unsupported.
     */
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        loadLibrary()
        return wrapWithBzip2IfNeeded(source, SeekableArchiveReader(source))
    }

    /**
     * Creates an [ArchiveReader] reading from an in-memory [byteArray] on JVM.
     *
     * @param byteArray the byte array containing the archive data.
     * @return a new [ArchiveReader] for reading the archive.
     * @throws ArchiveException if the archive cannot be opened or its format is unsupported.
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        loadLibrary()
        val source = ByteArraySeekableSource(byteArray)
        return wrapWithBzip2IfNeeded(source, SeekableArchiveReader(source))
    }

    /**
     * Creates an [ArchiveReader] reading from a Kotlin Multiplatform [path] on JVM via
     * [PathSeekableSource].
     *
     * @param path the path to the archive file.
     * @return a new [ArchiveReader] for reading the archive.
     * @throws ArchiveException if the archive cannot be opened or its format is unsupported.
     */
    @Suppress("TooGenericExceptionCaught")
    public actual fun createReader(path: Path): ArchiveReader {
        loadLibrary()
        val source = PathSeekableSource(Paths.get(path.toString()))
        return try {
            wrapWithBzip2IfNeeded(source, SeekableArchiveReader(source))
        } catch (t: Throwable) {
            source.close()
            throw t
        }
    }

    @Suppress("UnusedPrivateProperty", "VarCouldBeVal")
    private var loaded = false

    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    internal fun loadLibrary() {
        if (loaded) return
        try {
            System.loadLibrary("kioarch")
            loaded = true
            return
        } catch (_: UnsatisfiedLinkError) {
            // Do nothing
        }

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
