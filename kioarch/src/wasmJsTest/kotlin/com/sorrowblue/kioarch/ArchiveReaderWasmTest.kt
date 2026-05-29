package com.sorrowblue.kioarch

import kotlinx.io.files.Path
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Test class for [ArchiveReader] on WasmJS.
 */
class ArchiveReaderWasmTest {

    private var wasmModule: JsAny? = null

    private fun loadModuleIfNeeded(): Promise<JsAny> {
        val module = wasmModule
        if (module != null) {
            return Promise.resolve(module)
        }
        return loadKioArchModuleWasm().then { loaded ->
            KioArch.initialize(loaded)
            wasmModule = loaded
            loaded
        }
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
            readTestFile("TEST_ZIP_PATH").then { uint8Array ->
                if (uint8Array != null) {
                    val bytes = toByteArray(uint8Array)
                    KioArch.createReader(bytes).use { reader ->
                        ArchiveReaderTestSpec.verifyZipExtraction(reader, 2)
                    }
                }
                null
            }
        }
    }

    @Test
    fun testReal7zExtraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_7Z_PATH").then { uint8Array ->
                if (uint8Array != null) {
                    val bytes = toByteArray(uint8Array)
                    KioArch.createReader(bytes).use { reader ->
                        ArchiveReaderTestSpec.verify7zExtraction(reader, 2)
                    }
                }
                null
            }
        }
    }

    @Test
    fun testZipShiftJisFilename(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_SJIS_ZIP_PATH").then { uint8Array ->
                if (uint8Array != null) {
                    val bytes = toByteArray(uint8Array)
                    KioArch.createReader(bytes).use { reader ->
                        ArchiveReaderTestSpec.verifyZipShiftJisFilename(reader)
                    }
                }
                null
            }
        }
    }

    @Test
    fun testPathNormalization(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_PATH_NORMAL_ZIP_PATH").then { uint8Array ->
                if (uint8Array != null) {
                    val bytes = toByteArray(uint8Array)
                    KioArch.createReader(bytes).use { reader ->
                        ArchiveReaderTestSpec.verifyPathNormalization(reader)
                    }
                }
                null
            }
        }
    }

    @Test
    fun testLarge7zExtraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("LARGE_7Z_PATH").then { uint8Array ->
                if (uint8Array != null) {
                    val bytes = toByteArray(uint8Array)
                    KioArch.createReader(bytes).use { reader ->
                        val numFiles = 100
                        val sizePerFile = (10L * 1024 * 1024) / numFiles
                        ArchiveReaderTestSpec.verifyLargeExtraction(reader, numFiles, sizePerFile, verifyContent = false)
                    }
                }
                null
            }
        }
    }

    @Test
    fun testNodeJsPathReader(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            val isNode = isNodeJsWasm()
            if (isNode) {
                val envPath = getTestFilePath("TEST_ZIP_PATH")
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
            readTestFile("TEST_BZ2_PATH").then { uint8Array ->
                if (uint8Array != null) {
                    val bytes = toByteArray(uint8Array)
                    KioArch.createReader(bytes).use { reader ->
                        assertTrue(reader is Bzip2ArchiveReader)
                        ArchiveReaderTestSpec.verifyBzip2Extraction(reader)
                    }
                }
                null
            }
        }
    }

    @Test
    fun testRealTarBz2Extraction(): Promise<JsAny?> {
        return loadModuleIfNeeded().then {
            readTestFile("TEST_TARBZ2_PATH").then { uint8Array ->
                if (uint8Array != null) {
                    val bytes = toByteArray(uint8Array)
                    KioArch.createReader(bytes).use { reader ->
                        assertTrue(reader is Bzip2ArchiveReader)
                        ArchiveReaderTestSpec.verifyTarBz2Extraction(reader)
                    }
                }
                null
            }
        }
    }
}

@JsFun(
    """() => {
        var locateFile = function(path) {
            if (typeof require !== 'undefined') {
                return './kotlin/natives/' + path;
            } else {
                return '/base/kotlin/natives/' + path;
            }
        };
        var config = { locateFile: locateFile };
        if (typeof require !== 'undefined') {
            var createKioArchModule = eval('require')('./natives/kioarch.js');
            return createKioArchModule(config);
        } else {
            if (typeof globalThis.createKioArchModule !== 'function') {
                return Promise.reject("globalThis.createKioArchModule is not defined");
            }
            return globalThis.createKioArchModule(config);
        }
    }"""
)
private external fun loadKioArchModuleWasm(): Promise<JsAny>
