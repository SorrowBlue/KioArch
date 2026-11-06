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

/**
 * Android implementation of [KioArch] backed by JNI bindings to `libkioarch.so`.
 *
 * By default, [KioArchInitializer] preloads `libkioarch.so` via Jetpack App Startup when the
 * application process starts. If automatic initialization is disabled in `AndroidManifest.xml`,
 * [loadLibrary] can be called explicitly during background initialization, or [createReader] will
 * load the native library lazily on first use.
 *
 * @see KioArchInitializer
 * @see ParcelFileDescriptorSeekableSource
 */
public actual object KioArch {
    /**
     * Creates an [ArchiveReader] reading from the given [source] on Android.
     *
     * @param source the random-access data source containing the archive bytes.
     * @return a new [ArchiveReader] for reading the archive.
     * @throws ArchiveException if the archive cannot be opened or its format is unsupported.
     */
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        loadLibraryLazily()
        return wrapWithBzip2IfNeeded(source, SeekableArchiveReader(source))
    }

    /**
     * Creates an [ArchiveReader] reading from an in-memory [byteArray] on Android.
     *
     * @param byteArray the byte array containing the archive data.
     * @return a new [ArchiveReader] for reading the archive.
     * @throws ArchiveException if the archive cannot be opened or its format is unsupported.
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        loadLibraryLazily()
        val source = ByteArraySeekableSource(byteArray)
        return wrapWithBzip2IfNeeded(source, SeekableArchiveReader(source))
    }

    /**
     * Creates an [ArchiveReader] reading from a Kotlin Multiplatform [path] on Android via
     * [PathSeekableSource].
     *
     * @param path the path to the archive file.
     * @return a new [ArchiveReader] for reading the archive.
     * @throws ArchiveException if the archive cannot be opened or its format is unsupported.
     */
    @Suppress("TooGenericExceptionCaught")
    public actual fun createReader(path: Path): ArchiveReader {
        loadLibraryLazily()
        val source = PathSeekableSource(Paths.get(path.toString()))
        return try {
            wrapWithBzip2IfNeeded(source, SeekableArchiveReader(source))
        } catch (t: Throwable) {
            source.close()
            throw t
        }
    }

    private var isLoaded = false

    /**
     * Explicitly loads the native `libkioarch.so` shared library if it has not been loaded yet.
     *
     * This method is thread-safe and idempotent. It is invoked automatically by
     * [KioArchInitializer] at application startup, or lazily when [createReader] is called.
     *
     * @throws UnsatisfiedLinkError if `libkioarch.so` cannot be found or linked.
     */
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
