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
 * An in-memory implementation of [SeekableSource] wrapping a [ByteArray].
 */
public class ByteArraySeekableSource(private val bytes: ByteArray) : SeekableSource {
    private var pos = 0L

    public override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= bytes.size) return -1
        val available = bytes.size - pos
        val toRead = if (length > available) available.toInt() else length
        for (i in 0 until toRead) {
            buffer[offset + i] = bytes[(pos + i).toInt()]
        }
        pos += toRead
        return toRead
    }

    public override fun seek(position: Long) {
        pos = if (position < 0) {
            0L
        } else if (position > bytes.size) {
            bytes.size.toLong()
        } else {
            position
        }
    }

    public override fun position(): Long = pos

    public override fun length(): Long = bytes.size.toLong()

    public override fun close() {
        // No resources to release
    }
}
