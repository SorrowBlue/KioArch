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

/**
 * An [ArchiveReader] decorator returned when opening Bzip2-compressed archives (`.bz2`,
 * `.tar.bz2`, `.tbz2`, and `.tbz`).
 *
 * All [ArchiveReader] operations are delegated transparently to the underlying platform
 * [delegate].
 *
 * @param delegate the underlying native [ArchiveReader] handling Bzip2 stream decompression.
 */
public class Bzip2ArchiveReader(private val delegate: ArchiveReader) : ArchiveReader by delegate

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

@Suppress("TooGenericExceptionCaught")
internal fun wrapWithBzip2IfNeeded(source: SeekableSource, reader: ArchiveReader): ArchiveReader =
    try {
        if (isBzip2(source)) Bzip2ArchiveReader(reader) else reader
    } catch (t: Throwable) {
        reader.close()
        throw t
    }
