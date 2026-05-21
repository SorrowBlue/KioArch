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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Host-side unit tests for [KioArch] Android target running on the JVM.
 * These tests verify platform-independent components that do not require loading
 * the native JNI library (.so) on the host machine.
 */
@Suppress("MagicNumber")
class AndroidHostKioArchTest {

    /**
     * Verifies that the [ByteArraySeekableSource] correctly reads bytes
     * and tracks position and length as expected.
     */
    @Test
    fun testByteArraySeekableSource() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val source = ByteArraySeekableSource(data)

        assertEquals(5L, source.length())
        assertEquals(0L, source.position())

        val buffer = ByteArray(3)
        val readCount = source.read(buffer, 0, 3)
        assertEquals(3, readCount)
        assertEquals(10.toByte(), buffer[0])
        assertEquals(20.toByte(), buffer[1])
        assertEquals(30.toByte(), buffer[2])
        assertEquals(3L, source.position())

        source.seek(1)
        assertEquals(1L, source.position())

        val nextRead = source.read(buffer, 0, 2)
        assertEquals(2, nextRead)
        assertEquals(20.toByte(), buffer[0])
        assertEquals(30.toByte(), buffer[1])

        source.close()
    }

    /**
     * Verifies that custom exception classes are defined correctly and
     * can be instantiated with appropriate messages.
     */
    @Test
    fun testExceptions() {
        val invalidException = ArchiveInvalidException("Invalid archive file")
        assertEquals("Invalid archive file", invalidException.message)

        val corruptedException = ArchiveCorruptedException("Corrupted header found")
        assertEquals("Corrupted header found", corruptedException.message)

        assertNotNull(invalidException)
        assertNotNull(corruptedException)
    }
}
