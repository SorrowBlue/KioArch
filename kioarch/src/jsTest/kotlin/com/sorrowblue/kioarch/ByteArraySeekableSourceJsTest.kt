package com.sorrowblue.kioarch

import kotlin.test.Test

/**
 * Test class for [ByteArraySeekableSource] on JS.
 */
class ByteArraySeekableSourceJsTest {

    @Test
    fun testByteArraySeekableSource() {
        ByteArraySeekableSourceTestSpec.verifyByteArraySeekableSource()
    }
}
