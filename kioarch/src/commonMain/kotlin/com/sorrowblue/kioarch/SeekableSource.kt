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
 * A random-access byte stream from which [KioArch] reads archive headers and compressed payloads.
 *
 * Implementations provide synchronous read and seek operations over an underlying storage medium
 * such as an in-memory [ByteArray] ([ByteArraySeekableSource]), a local file, an Android
 * `ParcelFileDescriptor`, or a remote network/SMB stream.
 *
 * When passed to [KioArch.createReader], the [SeekableSource] is closed automatically when the
 * resulting [ArchiveReader] is closed.
 *
 * ### Custom Implementation Example
 *
 * ```kotlin
 * class CustomSeekableSource(private val data: ByteArray) : SeekableSource {
 *     private var offset = 0L
 *
 *     override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
 *         if (this.offset >= data.size) return -1
 *         val count = minOf(length.toLong(), data.size - this.offset).toInt()
 *         data.copyInto(buffer, offset, this.offset.toInt(), this.offset.toInt() + count)
 *         this.offset += count
 *         return count
 *     }
 *
 *     override fun seek(position: Long) {
 *         this.offset = position.coerceIn(0L, data.size.toLong())
 *     }
 *
 *     override fun position(): Long = offset
 *     override fun length(): Long = data.size.toLong()
 *     override fun close() = Unit
 * }
 * ```
 */
public interface SeekableSource : AutoCloseable {
    /**
     * Reads up to [length] bytes from the current position into [buffer] starting at [offset].
     *
     * Advances the stream position by the number of bytes actually read.
     *
     * @param buffer the destination byte array into which data is read.
     * @param offset the start offset in [buffer] at which the data is written.
     * @param length the maximum number of bytes to read.
     * @return the number of bytes read into [buffer], or `-1` if the end of the stream has been
     *   reached.
     */
    public fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Sets the absolute byte offset from the beginning of the stream for the next [read]
     * operation.
     *
     * @param position the zero-based absolute byte offset to seek to.
     */
    public fun seek(position: Long)

    /**
     * Returns the current zero-based read offset in bytes from the beginning of the stream.
     *
     * @return the current byte position in the stream.
     */
    public fun position(): Long

    /**
     * Returns the total size of the underlying data source in bytes.
     *
     * @return the total length of the stream in bytes.
     */
    public fun length(): Long
}
