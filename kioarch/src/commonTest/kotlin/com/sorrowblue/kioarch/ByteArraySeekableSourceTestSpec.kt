package com.sorrowblue.kioarch

import kotlin.test.assertEquals

/**
 * Common test specification for [ByteArraySeekableSource].
 */
object ByteArraySeekableSourceTestSpec {

    /**
     * Verifies that the seek and read operations of [ByteArraySeekableSource] work correctly.
     */
    fun verifyByteArraySeekableSource() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val source = ByteArraySeekableSource(data)

        assertEquals(10L, source.length())
        assertEquals(0L, source.position())

        val buffer = ByteArray(4)
        val read1 = source.read(buffer, 0, 4)
        assertEquals(4, read1)
        assertEquals(0.toByte(), buffer[0])
        assertEquals(1.toByte(), buffer[1])
        assertEquals(2.toByte(), buffer[2])
        assertEquals(3.toByte(), buffer[3])
        assertEquals(4L, source.position())

        source.seek(2)
        assertEquals(2L, source.position())

        val read2 = source.read(buffer, 0, 4)
        assertEquals(4, read2)
        assertEquals(2.toByte(), buffer[0])
        assertEquals(3.toByte(), buffer[1])
        assertEquals(4.toByte(), buffer[2])
        assertEquals(5.toByte(), buffer[3])

        source.seek(8)
        val read3 = source.read(buffer, 0, 4)
        assertEquals(2, read3)
        assertEquals(8.toByte(), buffer[0])
        assertEquals(9.toByte(), buffer[1])

        source.close()
    }
}
