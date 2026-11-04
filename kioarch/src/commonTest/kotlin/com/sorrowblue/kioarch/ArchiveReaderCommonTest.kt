package com.sorrowblue.kioarch

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * Common tests for [ArchiveReader] executed across all platforms.
 *
 * By default, archive tests use [KioArch.createReader] with `Path` (on-demand reading without
 * loading the entire archive into memory). In-memory expansion via [readTestFileBytes] is
 * used only for tests that specifically verify [KioArch.createReader] with [ByteArray].
 */
class ArchiveReaderCommonTest {

    @Test
    fun testSupportedExtensions() {
        KioArch.verifySupportedExtensions()
    }

    @Test
    fun testInvalidArchiveThrowsException() = runTest {
        initTestPlatform()
        val invalidData = byteArrayOf(1, 2, 3, 4, 5)
        assertFailsWith<ArchiveException> {
            KioArch.createReader(invalidData).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testCorruptedArchiveThrowsException() = runTest {
        initTestPlatform()
        val bytes = readTestFileBytes("TEST_ZIP_PATH")
        if (bytes.size > 100) {
            for (i in 30 until (bytes.size - 22)) {
                bytes[i] = 0.toByte()
            }
        }
        assertFailsWith<ArchiveException> {
            KioArch.createReader(bytes).use { reader ->
                reader.getEntries()
            }
        }
    }

    @Test
    fun testRealZipExtractionWithByteArray() = runTest {
        initTestPlatform()
        val bytes = readTestFileBytes("TEST_ZIP_PATH")
        KioArch.createReader(bytes).use { reader ->
            reader.verifyZipExtraction(2)
        }
    }

    @Test
    fun testRealZipExtraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_ZIP_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verifyZipExtraction(2)
        }
    }

    @Test
    fun testReal7zExtraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_7Z_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verify7zExtraction(2)
        }
    }

    @Test
    fun testRealTarGzExtraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_TARGZ_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verifyTarGzExtraction(3)
        }
    }

    @Test
    fun testRealBzip2Extraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_BZ2_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verifyBzip2Extraction()
        }
    }

    @Test
    fun testRealTarBz2Extraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_TARBZ2_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verifyTarBz2Extraction()
        }
    }

    @Test
    fun testZipShiftJisFilename() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_SJIS_ZIP_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verifyZipShiftJisFilename()
        }
    }

    @Test
    fun testPathNormalization() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_PATH_NORMAL_ZIP_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verifyPathNormalization()
        }
    }

    @Test
    fun testArchiveEntryExtractExtension() = runTest {
        initTestPlatform()
        val path = getTestFilePath("TEST_EXT_ZIP_PATH") ?: return@runTest
        KioArch.createReader(path).use { reader ->
            reader.verifyArchiveEntryExtractExtension("hello_extension1")
        }
    }

    @Test
    fun testLargeZipExtraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("LARGE_ZIP_PATH") ?: return@runTest
        val numFiles = 100
        val sizePerFile = (10L * 1024 * 1024) / numFiles
        KioArch.createReader(path).use { reader ->
            reader.verifyLargeExtraction(
                numFiles,
                sizePerFile,
                verifyContent = isLargeContentVerificationEnabled
            )
        }
    }

    @Test
    fun testLarge7zExtraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("LARGE_7Z_PATH") ?: return@runTest
        val numFiles = 100
        val sizePerFile = (10L * 1024 * 1024) / numFiles
        KioArch.createReader(path).use { reader ->
            reader.verifyLargeExtraction(
                numFiles,
                sizePerFile,
                verifyContent = isLargeContentVerificationEnabled
            )
        }
    }

    @Test
    fun testLargeTarGzExtraction() = runTest {
        initTestPlatform()
        val path = getTestFilePath("LARGE_TARGZ_PATH") ?: return@runTest
        val numFiles = 100
        val sizePerFile = (10L * 1024 * 1024) / numFiles
        KioArch.createReader(path).use { reader ->
            reader.verifyLargeExtraction(
                numFiles,
                sizePerFile,
                verifyContent = isLargeContentVerificationEnabled
            )
        }
    }

    @Test
    fun testExceptionSafetyInCallbacks() = runTest {
        if (!isCallbackExceptionSupported) return@runTest
        initTestPlatform()
        val badSource = object : SeekableSource {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                error("Simulated read exception")

            override fun seek(position: Long) = Unit

            override fun position(): Long = 0L

            override fun length(): Long = 100L

            override fun close() = Unit
        }
        assertFails {
            KioArch.createReader(badSource)
        }
    }

    @Test
    fun testDoubleCloseAndClosedAccessWithByteArray() = runTest {
        initTestPlatform()
        val zipBytes = readTestFileBytes("TEST_ZIP_PATH")
        KioArch.createReader(zipBytes).use { reader ->
            reader.verifyDoubleCloseAndClosedAccess()
        }
    }

    @Test
    fun testDoubleCloseAndClosedAccess() = runTest {
        initTestPlatform()
        val zipPath = getTestFilePath("TEST_ZIP_PATH") ?: return@runTest
        KioArch.createReader(zipPath).use { reader ->
            reader.verifyDoubleCloseAndClosedAccess()
        }

        val sevenzPath = getTestFilePath("TEST_7Z_PATH") ?: return@runTest
        KioArch.createReader(sevenzPath).use { reader ->
            reader.verifyDoubleCloseAndClosedAccess()
        }

        val bz2Path = getTestFilePath("TEST_BZ2_PATH") ?: return@runTest
        KioArch.createReader(bz2Path).use { reader ->
            reader.verifyDoubleCloseAndClosedAccess()
        }
    }
}
