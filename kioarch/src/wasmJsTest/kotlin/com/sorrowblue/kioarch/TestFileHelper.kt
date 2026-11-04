package com.sorrowblue.kioarch

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlinx.io.files.Path

private var cachedWasmModule: JsAny? = null

@JsFun("() => typeof process !== 'undefined' ? process.env : {}")
private external fun getProcessEnvJs(): JsAny?

@JsFun(
    """() => typeof process !== 'undefined' &&
        process.versions != null && process.versions.node != null"""
)
private external fun isNodeEnvWasm(): Boolean

@JsFun(
    """() => {
        var isNode = typeof process !== 'undefined' &&
            process.versions != null && process.versions.node != null;
        var locateFile = function(path) {
            if (isNode) {
                return './kotlin/natives/' + path;
            } else {
                return '/base/kotlin/natives/' + path;
            }
        };
        var config = { locateFile: locateFile };
        if (isNode) {
            return import(/* webpackIgnore: true */ 'node:module').then(function(moduleMod) {
                var createRequire = moduleMod.createRequire ||
                    (moduleMod.default && moduleMod.default.createRequire);
                var customRequire = createRequire(import.meta.url);
                var createKioArchModule = customRequire('./natives/kioarch.js');
                return createKioArchModule(config);
            });
        } else {
            if (typeof globalThis.createKioArchModule !== 'function') {
                return Promise.reject("globalThis.createKioArchModule is not defined");
            }
            return globalThis.createKioArchModule(config);
        }
    }"""
)
private external fun loadKioArchModuleWasm(): Promise<JsAny?>

@JsFun(
    """(envVarName, fileName, env) => {
        var isNode = typeof process !== 'undefined' &&
            process.versions != null && process.versions.node != null;
        if (isNode) {
            var path = env[envVarName];
            if (!path) {
                return Promise.reject(
                    new Error("Environment variable " + envVarName + " not set")
                );
            }
            return import(/* webpackIgnore: true */ 'node:fs').then(function(fsModule) {
                var fs = fsModule.default || fsModule;
                var buffer = fs.readFileSync(path);
                return new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.length);
            });
        } else {
            var url = "/base/kotlin/" + fileName;
            return fetch(url).then(function(res) {
                if (!res.ok) {
                    throw new Error("Failed to fetch " + url + " (" + res.status + ")");
                }
                return res.arrayBuffer();
            }).then(function(ab) {
                return new Uint8Array(ab);
            });
        }
    }"""
)
private external fun readTestFileJs(
    envVarName: String,
    fileName: String,
    env: JsAny
): Promise<JsAny?>

@JsFun("(u8) => u8.length")
private external fun getUint8ArrayLength(u8: JsAny): Int

@JsFun(
    """(uint8Array, setByteFn) => {
        var len = uint8Array.length;
        for (var i = 0; i < len; i++) {
            setByteFn(i, uint8Array[i]);
        }
    }"""
)
private external fun copyUint8ArrayToKotlinJs(uint8Array: JsAny, setByteFn: (Int, Byte) -> Unit)

@JsFun(
    """(env, name) => {
        return env[name] || "";
    }"""
)
private external fun getEnvValueJs(env: JsAny, name: String): String

private suspend fun <T : JsAny?> Promise<T>.awaitResult(): T = suspendCoroutine { cont ->
    then(
        onFulfilled = {
            cont.resume(it)
            null
        },
        onRejected = {
            cont.resumeWithException(RuntimeException(it.toString()))
            null
        }
    )
}

internal actual suspend fun initTestPlatform() {
    if (cachedWasmModule != null) return
    val loaded = loadKioArchModuleWasm().awaitResult()
        ?: error("Failed to load Wasm module")
    KioArch.initialize(loaded)
    cachedWasmModule = loaded
}

internal actual suspend fun readTestFileBytes(envVarName: String): ByteArray {
    val env = getProcessEnvJs() ?: error("Node.js process environment is unavailable")
    val fileName = getFileNameFromEnvVar(envVarName)
    val jsArray = readTestFileJs(envVarName, fileName, env).awaitResult()
        ?: error("Failed to load test file for $envVarName")
    val size = getUint8ArrayLength(jsArray)
    val byteArray = ByteArray(size)
    copyUint8ArrayToKotlinJs(jsArray) { index, byte ->
        byteArray[index] = byte
    }
    return byteArray
}

internal actual suspend fun getTestFilePath(envVarName: String): Path? {
    val env = if (isNodeEnvWasm()) getProcessEnvJs() else null
    val path = env?.let { getEnvValueJs(it, envVarName) }.orEmpty()
    return if (path.isNotEmpty()) Path(path) else null
}

internal actual val isLargeContentVerificationEnabled: Boolean = false

internal actual val isCallbackExceptionSupported: Boolean = true
