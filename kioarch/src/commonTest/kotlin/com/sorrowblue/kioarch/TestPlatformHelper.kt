package com.sorrowblue.kioarch

import kotlinx.io.files.Path

internal expect suspend fun initTestPlatform()

internal expect suspend fun readTestFileBytes(envVarName: String): ByteArray

internal expect suspend fun getTestFilePath(envVarName: String): Path?

internal expect val isLargeContentVerificationEnabled: Boolean

internal expect val isCallbackExceptionSupported: Boolean

internal expect fun getActiveNativeAllocations(): Long

internal expect fun getActivePlatformReferences(): Long

internal fun getFileNameFromEnvVar(envVarName: String): String = when (envVarName) {
    "TEST_ZIP_PATH" -> "test.zip"
    "TEST_7Z_PATH" -> "test.7z"
    "TEST_TARGZ_PATH" -> "test.tar.gz"
    "TEST_SJIS_ZIP_PATH" -> "test_sjis.zip"
    "TEST_PATH_NORMAL_ZIP_PATH" -> "test_path_normal.zip"
    "TEST_EXT_ZIP_PATH" -> "test_ext.zip"
    "TEST_BULK_ZIP_PATH" -> "test_bulk.zip"
    "LARGE_ZIP_PATH" -> "large.zip"
    "LARGE_7Z_PATH" -> "large.7z"
    "LARGE_TARGZ_PATH" -> "large.tar.gz"
    "TEST_BZ2_PATH" -> "test.bz2"
    "TEST_TARBZ2_PATH" -> "test.tar.bz2"
    else -> throw IllegalArgumentException("Unknown env var name: $envVarName")
}
