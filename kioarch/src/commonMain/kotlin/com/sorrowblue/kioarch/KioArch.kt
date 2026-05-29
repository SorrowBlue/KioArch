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

public expect object KioArch {
    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource].
     */
    public fun createReader(source: SeekableSource): ArchiveReader

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray].
     */
    public fun createReader(byteArray: ByteArray): ArchiveReader

    /**
     * Creates an [ArchiveReader] from a Kotlin Multiplatform [Path].
     *
     * @param path the path to the archive file
     * @return an [ArchiveReader] to read the archive
     */
    public fun createReader(path: Path): ArchiveReader
}

/**
 * Returns a list of file extensions supported by KioArch.
 *
 * @return a list of supported file extensions (e.g., "7z", "zip", "tar.gz", "tgz")
 */
public fun KioArch.getSupportedExtensions(): List<String> =
    ArchiveFormat.entries.map(ArchiveFormat::extension)
