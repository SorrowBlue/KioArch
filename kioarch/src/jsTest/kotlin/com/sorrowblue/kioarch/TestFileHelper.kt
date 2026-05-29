package com.sorrowblue.kioarch

import kotlin.js.Promise

private external val process: dynamic

private fun isNodeJs(): Boolean = js(
    "typeof process !== 'undefined' && process.versions != null && process.versions.node != null"
) as Boolean

private fun getFileNameFromEnvVar(envVarName: String): String {
    return when (envVarName) {
        "TEST_ZIP_PATH" -> "test.zip"
        "TEST_7Z_PATH" -> "test.7z"
        "TEST_SJIS_ZIP_PATH" -> "test_sjis.zip"
        "TEST_PATH_NORMAL_ZIP_PATH" -> "test_path_normal.zip"
        "LARGE_ZIP_PATH" -> "large.zip"
        "LARGE_7Z_PATH" -> "large.7z"
        "LARGE_TARGZ_PATH" -> "large.tar.gz"
        "TEST_BZ2_PATH" -> "test.bz2"
        "TEST_TARBZ2_PATH" -> "test.tar.bz2"
        else -> throw IllegalArgumentException("Unknown env var name: $envVarName")
    }
}

/**
 * Reads a test archive file specified by the given environment variable [envVarName]
 * using Node.js native 'node:fs' synchronous API in Node.js,
 * or fetch API in browser.
 *
 * @param envVarName The name of the environment variable containing the file path.
 * @return The binary content of the test file as a [ByteArray] wrapped in a [Promise].
 */
public fun readTestFile(envVarName: String): Promise<ByteArray> {
    if (isNodeJs()) {
        val path = process.env[envVarName] as? String
            ?: return Promise.reject(IllegalStateException("Environment variable $envVarName not set in Node.js process"))
        val fs = js("eval('require')('node:fs')")
        val buffer = fs.readFileSync(path)
        val length = buffer.length as Int
        val byteArray = ByteArray(length)
        js("""
            var kotlinArray = byteArray;
            kotlinArray.set(new Int8Array(buffer.buffer, buffer.byteOffset, length));
        """)
        return Promise.resolve(byteArray)
    } else {
        val fileName = getFileNameFromEnvVar(envVarName)
        val url = "/base/kotlin/" + fileName
        return js("""
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
        """) as Promise<ByteArray>
    }
}
