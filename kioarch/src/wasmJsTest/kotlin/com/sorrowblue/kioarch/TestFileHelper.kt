package com.sorrowblue.kioarch

import kotlin.js.Promise

@JsFun("() => typeof process !== 'undefined' ? process.env : {}")
private external fun getProcessEnvJs(): JsAny?

@JsFun(
    """() => typeof process !== 'undefined' && process.versions != null && process.versions.node != null"""
)
internal external fun isNodeJsWasm(): Boolean

@JsFun(
    """(envVarName, env) => {
        var isNode = typeof process !== 'undefined' && process.versions != null && process.versions.node != null;
        if (isNode) {
            return new Promise(function(resolve, reject) {
                try {
                    var path = env[envVarName];
                    if (!path) {
                        reject(new Error("Environment variable " + envVarName + " not set"));
                        return;
                    }
                    var fs = eval('require')('node:fs');
                    var buffer = fs.readFileSync(path);
                    resolve(new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.length));
                } catch (e) {
                    reject(e);
                }
            });
        } else {
            var fileNames = {
                "TEST_ZIP_PATH": "test.zip",
                "TEST_7Z_PATH": "test.7z",
                "TEST_SJIS_ZIP_PATH": "test_sjis.zip",
                "TEST_PATH_NORMAL_ZIP_PATH": "test_path_normal.zip",
                "LARGE_ZIP_PATH": "large.zip",
                "LARGE_7Z_PATH": "large.7z",
                "LARGE_TARGZ_PATH": "large.tar.gz",
                "TEST_BZ2_PATH": "test.bz2",
                "TEST_TARBZ2_PATH": "test.tar.bz2"
            };
            var fileName = fileNames[envVarName];
            if (!fileName) {
                return Promise.reject(new Error("Unknown env var: " + envVarName));
            }
            var url = "/base/kotlin/" + fileName;
            return fetch(url).then(function(res) {
                if (!res.ok) throw new Error("Failed to fetch " + url + " (" + res.status + ")");
                return res.arrayBuffer();
            }).then(function(ab) {
                return new Uint8Array(ab);
            });
        }
    }"""
)
private external fun readTestFileJs(envVarName: String, env: JsAny): Promise<JsAny?>

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

/**
 * Reads a test archive file specified by the given environment variable [envVarName]
 * using Node.js native 'node:fs' synchronous API in Node.js,
 * or fetch API in browser.
 *
 * @param envVarName The name of the environment variable containing the file path.
 * @return The binary content of the test file as a [JsAny] (Uint8Array) wrapped in a [Promise].
 */
public fun readTestFile(envVarName: String): Promise<JsAny?> {
    val env = getProcessEnvJs() ?: throw IllegalStateException("Node.js process environment is unavailable")
    return readTestFileJs(envVarName, env)
}

public fun toByteArray(uint8Array: JsAny): ByteArray {
    val size = getUint8ArrayLength(uint8Array)
    val byteArray = ByteArray(size)
    copyUint8ArrayToKotlinJs(uint8Array) { index, byte ->
        byteArray[index] = byte
    }
    return byteArray
}

public fun getTestFilePath(envVarName: String): String {
    val env = getProcessEnvJs() ?: throw IllegalStateException("Node.js process environment is unavailable")
    val path = getEnvValueJs(env, envVarName)
    if (path.isEmpty()) {
        throw IllegalStateException("Environment variable $envVarName not set in Node.js process")
    }
    return path
}
