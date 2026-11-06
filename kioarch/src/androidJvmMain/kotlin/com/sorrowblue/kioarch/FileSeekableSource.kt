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
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A JVM and Android [DirectSeekableSource] backed by a read-only [RandomAccessFile] and its
 * underlying `FileChannel`.
 *
 * @param file the local archive [File] to open in read-only mode.
 * @throws java.io.FileNotFoundException if [file] does not exist or cannot be opened for reading.
 */
public class FileSeekableSource(file: File) : DirectSeekableSource {
    private val raf = RandomAccessFile(file, "r")
    private val channel = raf.channel

    public override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        raf.read(buffer, offset, length)

    public override fun read(byteBuffer: ByteBuffer): Int = channel.read(byteBuffer)

    public override fun seek(position: Long) {
        raf.seek(position)
    }

    public override fun position(): Long = raf.filePointer

    public override fun length(): Long = raf.length()

    public override fun close() {
        raf.close()
    }
}

/**
 * Creates an [ArchiveReader] reading directly from a `java.io.File` via [FileSeekableSource].
 *
 * If opening the archive fails, the underlying file descriptor is closed automatically before the
 * exception is rethrown.
 *
 * ```kotlin
 * KioArch.createReader(File("archive.zip")).use { reader ->
 *     reader.getEntries().forEach { println(it.name) }
 * }
 * ```
 *
 * @param file the archive [File] to open.
 * @return a new [ArchiveReader] for inspecting and extracting entries from [file].
 * @throws java.io.FileNotFoundException if [file] does not exist or cannot be opened.
 * @throws ArchiveException if the file is not a valid supported archive or an I/O error occurs.
 */
@Suppress("TooGenericExceptionCaught")
public fun KioArch.createReader(file: File): ArchiveReader {
    val source = FileSeekableSource(file)
    return try {
        createReader(source)
    } catch (t: Throwable) {
        source.close()
        throw t
    }
}
