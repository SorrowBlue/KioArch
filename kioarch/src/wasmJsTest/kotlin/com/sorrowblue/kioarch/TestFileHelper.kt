package com.sorrowblue.kioarch

@JsFun("() => typeof process !== 'undefined' ? process.env : {}")
private external fun getProcessEnvJs(): JsAny?

@JsFun(
    """(env, name) => {
        var path = env[name];
        if (!path) return 0;
        if (typeof require === 'undefined') return 0;
        var fs = require('node:fs');
        try {
            var buffer = fs.readFileSync(path);
            return buffer.length;
        } catch (e) {
            return 0;
        }
    }"""
)
private external fun getFileSizeJs(env: JsAny, name: String): Int

@JsFun(
    """(env, name, setByteFn) => {
        var path = env[name];
        if (!path) return;
        if (typeof require === 'undefined') return;
        var fs = require('node:fs');
        try {
            var buffer = fs.readFileSync(path);
            var len = buffer.length;
            for (var i = 0; i < len; i++) {
                setByteFn(i, buffer[i]);
            }
        } catch (e) {
            // Silence exceptions
        }
    }"""
)
private external fun readFileSyncJs(env: JsAny, name: String, setByteFn: (Int, Byte) -> Unit)

/**
 * Reads a test archive file specified by the given environment variable [envVarName]
 * using Node.js native 'node:fs' synchronous API in Kotlin/WasmJS environment.
 *
 * @param envVarName The name of the environment variable containing the file path.
 * @return The binary content of the test file as a [ByteArray].
 */
public fun readTestFile(envVarName: String): ByteArray {
    val env = getProcessEnvJs() ?: throw IllegalStateException("Node.js process environment is unavailable")
    val size = getFileSizeJs(env, envVarName)
    if (size <= 0) {
        throw IllegalStateException("Test file specified by env var '$envVarName' is not found or empty")
    }
    val byteArray = ByteArray(size)
    readFileSyncJs(env, envVarName) { index, byte ->
        byteArray[index] = byte
    }
    return byteArray
}

@JsFun(
    """(env, name) => {
        return env[name] || "";
    }"""
)
private external fun getEnvValueJs(env: JsAny, name: String): String

public fun getTestFilePath(envVarName: String): String {
    val env = getProcessEnvJs() ?: throw IllegalStateException("Node.js process environment is unavailable")
    val path = getEnvValueJs(env, envVarName)
    if (path.isEmpty()) {
        throw IllegalStateException("Environment variable $envVarName not set in Node.js process")
    }
    return path
}
