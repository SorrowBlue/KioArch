@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sorrowblue.kioarch

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv

internal actual suspend fun initTestPlatform() = Unit

internal actual suspend fun readTestFileBytes(envVarName: String): ByteArray {
    val pathStr = getenv(envVarName)?.toKString()
        ?: error("Environment variable $envVarName not set")
    val file = fopen(pathStr, "rb") ?: error("Failed to open test file: $pathStr")
    try {
        fseek(file, 0L, SEEK_END)
        val size = ftell(file).toInt()
        fseek(file, 0L, SEEK_SET)
        val bytes = ByteArray(size)
        if (size > 0) {
            bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1uL, size.toULong(), file)
            }
        }
        return bytes
    } finally {
        fclose(file)
    }
}

internal actual suspend fun getTestFilePath(envVarName: String): Path? {
    val pathStr = getenv(envVarName)?.toKString() ?: return null
    return Path(pathStr)
}

internal actual val isLargeContentVerificationEnabled: Boolean = true

internal actual val isCallbackExceptionSupported: Boolean = false
