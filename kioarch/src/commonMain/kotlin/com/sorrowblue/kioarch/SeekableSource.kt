package com.sorrowblue.kioarch

interface SeekableSource : AutoCloseable {
    /**
     * Reads bytes into the specified buffer.
     * Returns the number of bytes read, or -1 if EOF is reached.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Seeks to the specified absolute byte position in the stream.
     */
    fun seek(position: Long)

    /**
     * Returns the current absolute read position (in bytes) in the stream.
     */
    fun position(): Long

    /**
     * Returns the total length of the stream in bytes.
     */
    fun length(): Long
}
