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

/**
 * A JVM-specific [SeekableSource] implementation wrapping a [RandomAccessFile] for disk-based random access.
 */
class FileSeekableSource(file: File) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return raf.read(buffer, offset, length)
    }

    override fun seek(position: Long) {
        raf.seek(position)
    }

    override fun position(): Long = raf.filePointer

    override fun length(): Long = raf.length()

    override fun close() {
        raf.close()
    }
}

/**
 * Extension helper to create an [ArchiveReader] directly from a JVM [File].
 */
fun KioArch.createReader(file: File): ArchiveReader {
    return createReader(FileSeekableSource(file))
}
