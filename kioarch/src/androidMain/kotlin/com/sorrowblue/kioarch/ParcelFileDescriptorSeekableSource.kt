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
 * An Android-specific [SeekableSource] implementation wrapping a [ParcelFileDescriptor]
 * for storage-provider-based random access.
 *
 * @param pfd the parcel file descriptor to wrap
 */
public class ParcelFileDescriptorSeekableSource(
    private val pfd: ParcelFileDescriptor
) : SeekableSource {
    private val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val channel = fis.channel

    public override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
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
 * Extension helper to create an [ArchiveReader] directly from a [ParcelFileDescriptor].
 *
 * @param pfd the parcel file descriptor of the archive file
 * @return an [ArchiveReader] to read the archive
 */
public fun KioArch.createReader(pfd: ParcelFileDescriptor): ArchiveReader =
    createReader(ParcelFileDescriptorSeekableSource(pfd))

/**
 * Extension helper to create an [ArchiveReader] directly from an Android [Uri].
 * Note that the caller is responsible for ensuring the Uri is accessible (e.g. holding permissions).
 *
 * @param context the context to resolve the content resolver
 * @param uri the content Uri of the archive file
 * @return an [ArchiveReader] to read the archive
 * @throws FileNotFoundException if the Uri could not be resolved or opened
 */
public fun KioArch.createReader(context: Context, uri: Uri): ArchiveReader {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw FileNotFoundException("Failed to open Uri: $uri")
    return createReader(pfd)
}
