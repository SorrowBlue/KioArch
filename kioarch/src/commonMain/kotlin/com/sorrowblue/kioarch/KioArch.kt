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

import kotlinx.io.files.Path

/**
 * Entry point for creating [ArchiveReader] instances across all supported Kotlin Multiplatform
 * targets.
 *
 * `KioArch` inspects the leading magic bytes of the input stream to determine the archive format
 * automatically, rather than relying on file extensions. Supported formats are defined in
 * [ArchiveFormat].
 *
 * ### Usage Example
 *
 * ```kotlin
 * import com.sorrowblue.kioarch.KioArch
 * import com.sorrowblue.kioarch.extractToByteArray
 * import kotlinx.io.files.Path
 *
 * KioArch.createReader(Path("example.7z")).use { reader ->
 *     for (entry in reader.getEntries()) {
 *         if (!entry.isDirectory) {
 *             val content = entry.extractToByteArray(reader)
 *             println("Extracted ${entry.name} (${content.size} bytes)")
 *         }
 *     }
 * }
 * ```
 *
 * ### Platform Initialization Notes
 * - **JVM / iOS**: Native decompression libraries are linked or extracted automatically on first
 *   use.
 * - **Android**: Preloaded automatically at startup via `KioArchInitializer`, or loaded on demand
 *   if App Startup initialization is disabled.
 * - **JS / WasmJs**: Call `KioArch.initialize(module)` with a resolved Emscripten module instance
 *   (obtained via `loadKioArchModule()`) before invoking any `createReader` overload.
 */
public expect object KioArch {
    /**
     * Creates an [ArchiveReader] reading from the given random-access [source].
     *
     * The returned [ArchiveReader] takes ownership of [source] and closes it when
     * [ArchiveReader.close] is called. If opening the archive fails, the caller remains
     * responsible for closing a custom [source] unless using one of the file/path convenience
     * overloads.
     *
     * @param source the random-access data source containing the archive bytes.
     * @return a new [ArchiveReader] positioned to inspect and extract entries from [source].
     * @throws ArchiveException if the archive header is corrupted, the format is unsupported, or an
     *   I/O error occurs while reading the archive metadata.
     */
    public fun createReader(source: SeekableSource): ArchiveReader

    /**
     * Creates an [ArchiveReader] reading from an in-memory [byteArray].
     *
     * @param byteArray the byte array containing the complete archive data.
     * @return a new [ArchiveReader] backed by a [ByteArraySeekableSource].
     * @throws ArchiveException if the archive data is corrupted or its format is unsupported.
     */
    public fun createReader(byteArray: ByteArray): ArchiveReader

    /**
     * Creates an [ArchiveReader] reading from the file at the specified [path].
     *
     * Note that on browser JS and WasmJs runtimes, direct filesystem access via [Path] is not
     * available and throws [UnsupportedOperationException]; use [createReader] with a [ByteArray]
     * or a custom [SeekableSource] instead. Node.js runtimes support [Path] via synchronous `fs`
     * descriptors.
     *
     * @param path the filesystem path to the archive file.
     * @return a new [ArchiveReader] for reading the archive at [path].
     * @throws ArchiveException if the file cannot be opened or is not a valid supported archive.
     * @throws UnsupportedOperationException if called in a browser JS or WasmJs environment.
     */
    public fun createReader(path: Path): ArchiveReader
}

/**
 * Returns the canonical lowercase file extensions for all archive formats supported by [KioArch].
 *
 * @return a list of supported file extensions (such as `"7z"`, `"zip"`, `"tar.gz"`, `"tgz"`,
 *   `"bz2"`, `"tar.bz2"`, `"tbz2"`, and `"tbz"`).
 * @see ArchiveFormat
 */
public fun KioArch.getSupportedExtensions(): List<String> =
    ArchiveFormat.entries.map(ArchiveFormat::extension)
