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
 * A JVM/Android-specific [SeekableSource] implementation wrapping a [RandomAccessFile] for disk-based random access.
 */
public class FileSeekableSource(file: File) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")

    public override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        raf.read(buffer, offset, length)

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
 * Extension helper to create an [ArchiveReader] directly from a [File].
 */
public fun KioArch.createReader(file: File): ArchiveReader = createReader(FileSeekableSource(file))
