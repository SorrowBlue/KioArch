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
        loadLibraryLazily()
        return wrapIfNeeded(source, SeekableArchiveReader(source))
    }

    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        loadLibraryLazily()
        val source = ByteArraySeekableSource(byteArray)
        return wrapIfNeeded(source, SeekableArchiveReader(source))
    }

    public actual fun createReader(path: Path): ArchiveReader {
        loadLibraryLazily()
        val source = PathSeekableSource(Paths.get(path.toString()))
        return wrapIfNeeded(source, SeekableArchiveReader(source))
    }

    private var isLoaded = false

    @Synchronized
    public fun loadLibrary() {
        if (!isLoaded) {
            System.loadLibrary("kioarch")
            isLoaded = true
        }
    }

    @Synchronized
    internal fun loadLibraryLazily() {
        if (!isLoaded) {
            loadLibrary()
        }
    }
}
