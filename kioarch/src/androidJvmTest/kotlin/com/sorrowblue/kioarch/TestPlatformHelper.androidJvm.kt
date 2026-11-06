package com.sorrowblue.kioarch

import java.io.File
import kotlinx.io.files.Path

internal actual suspend fun initTestPlatform() {
    HostTestNativeLoader.loadIfNeeded()
}

internal actual suspend fun readTestFileBytes(envVarName: String): ByteArray {
    val path = System.getProperty(envVarName)
        ?: error("System property $envVarName not set")
    return File(path).readBytes()
}

internal actual suspend fun getTestFilePath(envVarName: String): Path? {
    val path = System.getProperty(envVarName) ?: return null
    return Path(File(path).absolutePath)
}

internal actual val isLargeContentVerificationEnabled: Boolean = true

internal actual val isCallbackExceptionSupported: Boolean = true

internal actual fun getActiveNativeAllocations(): Long {
    HostTestNativeLoader.loadIfNeeded()
    return KioArchJni.getActiveNativeAllocations()
}

internal actual fun getActivePlatformReferences(): Long {
    HostTestNativeLoader.loadIfNeeded()
    return KioArchJni.getActiveJniGlobalRefs()
}
