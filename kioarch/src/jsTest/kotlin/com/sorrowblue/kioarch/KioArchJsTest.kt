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

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KioArchJsTest {

    private var wasmModule: JsAny? = null

    private fun loadModuleIfNeeded(): Promise<JsAny> {
        val module = wasmModule
        if (module != null) {
            @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
            return Promise.resolve(module)
        }
        val isNode = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
        val promise = if (isNode) {
            val createKioArchModule = js("eval('require')('./natives/kioarch.js')")
            val config = js("({ locateFile: (path) => './kotlin/natives/' + path })")
            createKioArchModule(config) as Promise<JsAny>
        } else {
            val locateFile = js("function(path) { return '/base/kotlin/natives/' + path; }")
            val config = js("({ locateFile: locateFile })")
            val initFn = js("globalThis.createKioArchModule")
            if (initFn == null) {
                Promise.reject(IllegalStateException("globalThis.createKioArchModule is not defined"))
            } else {
                initFn(config) as Promise<JsAny>
            }
        }
        return promise.then { loaded ->
            KioArch.initialize(loaded)
            wasmModule = loaded
            loaded
        }
    }

    @Test
    fun testByteArraySeekableSource() {
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

    @Test
    fun testInvalidArchiveThrowsException() {
        assertFailsWith<IllegalStateException> {
            val invalidData = byteArrayOf(1, 2, 3, 4, 5)
            KioArch.createReader(invalidData).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testRealZipExtraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_ZIP_PATH").then { bytes ->
                KioArch.createReader(bytes).use { reader ->
                    val entries = reader.getEntries()
                    assertTrue(entries.isNotEmpty(), "ZIP should have entries")
                    assertEquals(2, entries.size, "ZIP should contain exactly 2 entries")

                    val entry1 = entries.first { it.name == "dummy1.txt" }
                    val buffer1 = Buffer()
                    reader.extractEntry(entry1, buffer1)
                    assertEquals("This is a dummy text file inside zip.", buffer1.readByteArray().decodeToString())

                    val entry2 = entries.first { it.name == "dummy2.txt" }
                    val buffer2 = Buffer()
                    reader.extractEntry(entry2, buffer2)
                    assertEquals(1200L, buffer2.size)
                }
                null
            }
        }
    }

    @Test
    fun testReal7zExtraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_7Z_PATH").then { bytes ->
                KioArch.createReader(bytes).use { reader ->
                    val entries = reader.getEntries()
                    assertTrue(entries.isNotEmpty(), "7z should have entries")
                    assertEquals(2, entries.size, "7z should contain exactly 2 entries")

                    val entry1 = entries.first { it.name == "dummy1.txt" }
                    val buffer1 = Buffer()
                    reader.extractEntry(entry1, buffer1)
                    assertEquals("This is a dummy text file inside 7z.", buffer1.readByteArray().decodeToString())
                }
                null
            }
        }
    }

    @Test
    fun testZipShiftJisFilename(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_SJIS_ZIP_PATH").then { bytes ->
                KioArch.createReader(bytes).use { reader ->
                    val entries = reader.getEntries()
                    val expectedNames = listOf(
                        "テスト_日本語ファイル名_Shift_JIS.txt",
                        "dame_moji_ソ表能予.txt",
                        "half_width_ｶﾀｶﾅﾃｽﾄ.txt",
                        "cp932_extensions_①Ⅳ髙﨑.txt"
                    )
                    assertEquals(expectedNames.size, entries.size, "ZIP should contain all CP932 edge case entries")
                    for (i in expectedNames.indices) {
                        assertEquals(expectedNames[i], entries[i].name, "Decoded name should match CP932 original")
                    }
                }
                null
            }
        }
    }

    @Test
    fun testPathNormalization(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_PATH_NORMAL_ZIP_PATH").then { bytes ->
                KioArch.createReader(bytes).use { reader ->
                    val entries = reader.getEntries()
                    assertEquals(2, entries.size)
                    assertEquals("directory/subdir/file1.txt", entries[0].name, "Backslashes should be normalized to forward slashes")
                    assertEquals("directory/subdir/file2.txt", entries[1].name, "Backslashes should be normalized to forward slashes")
                }
                null
            }
        }
    }

    @Test
    fun testLarge7zExtraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("LARGE_7Z_PATH").then { bytes ->
                KioArch.createReader(bytes).use { reader ->
                    val entries = reader.getEntries()
                    val numFiles = 100
                    assertEquals(numFiles, entries.size)
                    
                    val sizePerFile = (10L * 1024 * 1024) / numFiles
                    for (i in 0 until numFiles) {
                        val entry = entries[i]
                        assertEquals("large_dummy_$i.bin", entry.name)
                        assertEquals(sizePerFile, entry.size, "Uncompressed size should be 100KB")

                        val buffer = Buffer()
                        reader.extractEntry(entry, buffer)
                        assertEquals(sizePerFile, buffer.size)
                    }
                }
                null
            }
        }
    }

    @Test
    fun testNodeJsPathReader(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            val isNode = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
            if (isNode) {
                val envPath = js("process.env['TEST_ZIP_PATH']") as? String
                    ?: throw IllegalStateException("TEST_ZIP_PATH not set")
                KioArch.createReader(Path(envPath)).use { reader ->
                    val entries = reader.getEntries()
                    assertTrue(entries.isNotEmpty())
                    assertEquals(2, entries.size)
                }
            }
            null
        }
    }

    @Test
    fun testExceptionSafetyInCallbacks(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            val badSource = object : SeekableSource {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    throw RuntimeException("Simulated read exception")
                }
                override fun seek(position: Long) {}
                override fun position(): Long = 0L
                override fun length(): Long = 100L
                override fun close() {}
            }
            assertFailsWith<ArchiveIOException> {
                KioArch.createReader(badSource)
            }
            null
        }
    }

    @Test
    fun testRealBzip2Extraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_BZ2_PATH").then { bytes ->
                KioArch.createReader(bytes).use { reader ->
                    assertTrue(reader is Bzip2ArchiveReader)
                    val entries = reader.getEntries()
                    assertEquals(1, entries.size)
                    val entry = entries[0]
                    assertEquals("extracted_data", entry.name)
                    assertEquals(-1L, entry.size)

                    val buffer = Buffer()
                    reader.extractEntry(entry, buffer)
                    assertEquals("This is a dummy text file compressed using bzip2.", buffer.readByteArray().decodeToString())
                }
                null
            }
        }
    }

    @Test
    fun testRealTarBz2Extraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_TARBZ2_PATH").then { bytes ->
                KioArch.createReader(bytes).use { reader ->
                    assertTrue(reader is Bzip2ArchiveReader)
                    val entries = reader.getEntries()
                    assertEquals(2, entries.size)

                    val entry1 = entries.first { it.name == "dummy1.txt" }
                    val buffer1 = Buffer()
                    reader.extractEntry(entry1, buffer1)
                    assertEquals("This is a dummy text file inside tar.bz2.", buffer1.readByteArray().decodeToString())

                    val entry2 = entries.first { it.name == "dummy2.txt" }
                    val buffer2 = Buffer()
                    reader.extractEntry(entry2, buffer2)
                    assertEquals("Some more dummy content in the second tar.bz2 file.", buffer2.readByteArray().decodeToString())
                }
                null
            }
        }
    }
}
