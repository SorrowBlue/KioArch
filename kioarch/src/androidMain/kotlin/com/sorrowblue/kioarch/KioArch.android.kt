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

public actual object KioArch {
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        loadLibraryLazily()
        return SeekableArchiveReader(source)
    }

    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        loadLibraryLazily()
        return SeekableArchiveReader(ByteArraySeekableSource(byteArray))
    }

    public actual fun createReader(path: kotlinx.io.files.Path): ArchiveReader {
        loadLibraryLazily()
        return SeekableArchiveReader(PathSeekableSource(java.nio.file.Paths.get(path.toString())))
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
