package com.antigravity.sevenzip

fun interface DataSink {
    /**
     * Writes decompressed data from the buffer to the destination.
     */
    fun write(buffer: ByteArray, offset: Int, length: Int)
}
