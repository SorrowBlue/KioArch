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

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/**
 * An Android-specific [DirectSeekableSource] wrapping a seekable [ParcelFileDescriptor] for
 * Storage Access Framework (`content://`) or ContentProvider random access without copying files
 * into cache storage.
 *
 * Closing this source closes the underlying [ParcelFileDescriptor].
 *
 * @param pfd a seekable [ParcelFileDescriptor] opened in read mode.
 */
public class ParcelFileDescriptorSeekableSource(private val pfd: ParcelFileDescriptor) :
    DirectSeekableSource {
    private val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val channel = fis.channel

    public override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        val readBytes = channel.read(byteBuffer)
        return if (readBytes == 0 && channel.position() >= channel.size()) -1 else readBytes
    }

    public override fun read(byteBuffer: ByteBuffer): Int {
        val readBytes = channel.read(byteBuffer)
        return if (readBytes == 0 && channel.position() >= channel.size()) -1 else readBytes
    }

    public override fun seek(position: Long) {
        channel.position(position)
    }

    public override fun position(): Long = channel.position()

    public override fun length(): Long = channel.size()

    public override fun close() {
        fis.close()
    }
}

/**
 * Creates an [ArchiveReader] directly from an Android [ParcelFileDescriptor].
 *
 * The returned [ArchiveReader] takes ownership of [pfd] and closes it when the reader is closed,
 * or immediately if opening the archive fails.
 *
 * @param pfd a seekable [ParcelFileDescriptor] pointing to the archive file.
 * @return an [ArchiveReader] to inspect and extract entries from [pfd].
 * @throws ArchiveException if the archive is corrupted, unsupported, or an I/O error occurs.
 */
@Suppress("TooGenericExceptionCaught")
public fun KioArch.createReader(pfd: ParcelFileDescriptor): ArchiveReader {
    val source = ParcelFileDescriptorSeekableSource(pfd)
    return try {
        createReader(source)
    } catch (t: Throwable) {
        source.close()
        throw t
    }
}

/**
 * Creates an [ArchiveReader] directly from an Android content [Uri] resolved via
 * `context.contentResolver`.
 *
 * The caller is responsible for ensuring that the application holds read permissions for [uri].
 *
 * ```kotlin
 * KioArch.createReader(context, documentUri).use { reader ->
 *     for (entry in reader.getEntries()) {
 *         println("${entry.name}: ${entry.size} bytes")
 *     }
 * }
 * ```
 *
 * @param context the Android [Context] used to access the `ContentResolver`.
 * @param uri the `content://` or `file://` [Uri] of the archive file.
 * @return an [ArchiveReader] to inspect and extract entries from [uri].
 * @throws FileNotFoundException if [uri] cannot be resolved or opened.
 * @throws ArchiveException if the archive is corrupted, unsupported, or an I/O error occurs.
 */
public fun KioArch.createReader(context: Context, uri: Uri): ArchiveReader {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw FileNotFoundException("Failed to open Uri: $uri")
    return createReader(pfd)
}
