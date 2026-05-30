package com.sorrowblue.kioarch

import kotlin.test.Test

/**
 * Test class for [ByteArraySeekableSource] on JVM.
 */
class ByteArraySeekableSourceTest {

    @Test
    fun testByteArraySeekableSource() {
        ByteArraySeekableSourceTestSpec.verifyByteArraySeekableSource()
    }
}
