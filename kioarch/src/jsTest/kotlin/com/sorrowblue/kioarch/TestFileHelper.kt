package com.sorrowblue.kioarch

private external val process: dynamic

/**
 * Reads a test archive file specified by the given environment variable [envVarName]
 * using Node.js native 'node:fs' synchronous API in Kotlin/JS environment.
 *
 * @param envVarName The name of the environment variable containing the file path.
 * @return The binary content of the test file as a [ByteArray].
 */
public fun readTestFile(envVarName: String): ByteArray {
    val path = process.env[envVarName] as? String
        ?: throw IllegalStateException("Environment variable $envVarName not set in Node.js process")
    val fs = js("require('node:fs')")
    val buffer = fs.readFileSync(path)
    val length = buffer.length as Int
    val byteArray = ByteArray(length)
    js("""
        var kotlinArray = byteArray;
        kotlinArray.set(new Int8Array(buffer.buffer, buffer.byteOffset, length));
    """)
    return byteArray
}
