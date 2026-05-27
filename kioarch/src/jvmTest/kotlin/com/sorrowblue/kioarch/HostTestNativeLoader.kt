package com.sorrowblue.kioarch

import java.io.File
import java.io.FileOutputStream

/**
 * Utility to automatically load the host-specific JNI library (.dll, .so, etc.)
 * when running Android host unit tests on the local JVM process.
 * This loader is silently bypassed on actual Android devices.
 */
internal object HostTestNativeLoader {
    private var loaded = false

    /**
     * Extracts and loads the native library from classpath resources if running
     * in a standard desktop JVM environment (host unit tests).
     */
    @Synchronized
    @Suppress("NestedBlockDepth")
    fun loadIfNeeded() {
        if (!loaded) {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val (dir, ext) = when {
                os.contains("win") -> "windows/amd64" to "dll"
                os.contains("linux") -> "linux/amd64" to "so"
                else -> "" to ""
            }

            if (dir.isNotEmpty()) {
                val prefix = if (os.contains("win")) "" else "lib"
                val resourcePath = "/natives/$dir/${prefix}kioarch.$ext"
                val inputStream = HostTestNativeLoader::class.java.getResourceAsStream(
                    resourcePath
                )

                if (inputStream != null) {
                    try {
                        val tempFile = File.createTempFile("libkioarch_hosttest", ".$ext")
                        tempFile.deleteOnExit()

                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }

                        System.load(tempFile.absolutePath)

                        bypassAndroidLoader()

                        loaded = true
                    } catch (e: Exception) {
                        System.err.println(
                            "HostTestNativeLoader failed to load host binary: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun bypassAndroidLoader() {
        try {
            val kioarchClass = Class.forName("com.sorrowblue.kioarch.KioArch")
            val instanceField = kioarchClass.getDeclaredField("INSTANCE")
            val instance = instanceField.get(null)

            // Set com.sorrowblue.kioarch.KioArch.isLoaded to true
            val isLoadedField = kioarchClass.getDeclaredField("isLoaded")
            isLoadedField.isAccessible = true
            isLoadedField.set(instance, true)
        } catch (e: Exception) {
            // Ignore if it's JVM target (does not have isLoaded field)
        }
    }
}
