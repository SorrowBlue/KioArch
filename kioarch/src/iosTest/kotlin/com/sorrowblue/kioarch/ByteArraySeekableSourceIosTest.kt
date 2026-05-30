package com.sorrowblue.kioarch

import kotlin.test.Test

/**
 * Test class for [ByteArraySeekableSource] on iOS.
 */
class ByteArraySeekableSourceIosTest {

    @Test
    fun testByteArraySeekableSource() {
        ByteArraySeekableSourceTestSpec.verifyByteArraySeekableSource()
    }
}
