package com.sorrowblue.kioarch

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlinx.io.files.Path

private external val process: dynamic

private var cachedWasmModule: JsAny? = null

internal fun isNodeJs(): Boolean = js(
    "typeof process !== 'undefined' && process.versions != null && process.versions.node != null"
) as Boolean

private suspend fun <T> Promise<T>.awaitResult(): T = suspendCoroutine { cont ->
    then(
        onFulfilled = { cont.resume(it) },
        onRejected = { cont.resumeWithException(it) }
    )
}

internal actual suspend fun initTestPlatform() {
    if (cachedWasmModule != null) return
    val promise = if (isNodeJs()) {
        val createKioArchModule = js("eval('require')('./natives/kioarch.js')")
        val config = js("({ locateFile: (path) => './kotlin/natives/' + path })")
        createKioArchModule(config) as Promise<JsAny>
    } else {
        val locateFile = js("function(path) { return '/base/kotlin/natives/' + path; }")
        val config = js("({ locateFile: locateFile })")
        val initFn = js("globalThis.createKioArchModule")
        if (initFn == null) {
            Promise.reject(
                IllegalStateException("globalThis.createKioArchModule is not defined")
            )
        } else {
            initFn(config) as Promise<JsAny>
        }
    }
    val loaded = promise.awaitResult()
    KioArch.initialize(loaded)
    cachedWasmModule = loaded
}

internal actual suspend fun readTestFileBytes(envVarName: String): ByteArray =
    readTestFile(envVarName).awaitResult()

internal actual suspend fun getTestFilePath(envVarName: String): Path? {
    val path = if (isNodeJs()) process.env[envVarName] as? String else null
    return path?.let { Path(it) }
}

internal actual val isLargeContentVerificationEnabled: Boolean = false

internal actual val isCallbackExceptionSupported: Boolean = true

internal actual fun getActiveNativeAllocations(): Long = getJsActiveNativeAllocations()

internal actual fun getActivePlatformReferences(): Long = getJsActiveOpaqueReferences()

/**
 * Reads a test archive file specified by the given environment variable [envVarName]
 * using Node.js native 'node:fs' synchronous API in Node.js,
 * or fetch API in browser.
 *
 * @param envVarName The name of the environment variable containing the file path.
 * @return The binary content of the test file as a [ByteArray] wrapped in a [Promise].
 */
fun readTestFile(envVarName: String): Promise<ByteArray> = if (isNodeJs()) {
    readNodeTestFile(envVarName)
} else {
    fetchBrowserTestFile(envVarName)
}

private fun readNodeTestFile(envVarName: String): Promise<ByteArray> {
    val path = process.env[envVarName] as? String ?: return Promise.reject(
        IllegalStateException("Environment variable $envVarName not set in Node.js process")
    )
    val fs = js("eval('require')('node:fs')")
    val buffer = fs.readFileSync(path)
    val length = buffer.length as Int
    val byteArray = ByteArray(length)
    js(
        """
        var kotlinArray = byteArray;
        kotlinArray.set(new Int8Array(buffer.buffer, buffer.byteOffset, length));
        """
    )
    return Promise.resolve(byteArray)
}

private fun fetchBrowserTestFile(envVarName: String): Promise<ByteArray> {
    val fileName = getFileNameFromEnvVar(envVarName)
    val url = "/base/kotlin/$fileName"
    return js(
        """
        fetch(url)
            .then(function(res) {
                if (!res.ok) throw new Error("Failed to fetch: " + url + " (" + res.status + ")");
                return res.arrayBuffer();
            })
            .then(function(ab) {
                var view = new Int8Array(ab);
                var kotlinArray = new Int8Array(view.length);
                kotlinArray.set(view);
                return kotlinArray;
            })
        """
    ) as Promise<ByteArray>
}
