package com.sorrowblue.kioarch

import kotlin.test.Test

/**
 * Test class for [ByteArraySeekableSource] on WasmJS.
 */
class ByteArraySeekableSourceWasmTest {

    @Test
    fun testByteArraySeekableSource() {
        ByteArraySeekableSourceTestSpec.verifyByteArraySeekableSource()
    }
}
