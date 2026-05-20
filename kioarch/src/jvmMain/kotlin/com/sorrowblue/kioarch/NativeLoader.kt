package com.sorrowblue.kioarch

import java.io.File
import java.io.FileOutputStream

internal object NativeLoader {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        try {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()

            // Resolve the resource subdirectory based on OS and architecture
            val (dir, ext) = when {
                os.contains("win") -> "windows/amd64" to "dll"
                os.contains("linux") -> "linux/amd64" to "so"
                else -> throw UnsupportedOperationException("Unsupported OS: $os")
            }

            val resourcePath = "/natives/$dir/kioarch.$ext"
            val inputStream = NativeLoader::class.java.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("Native library not found in classpath resources: $resourcePath")

            // Copy resource to a temporary file
            val tempFile = File.createTempFile("libkioarch", ".$ext")
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            // Load the dynamic native library
            System.load(tempFile.absolutePath)
            loaded = true
        } catch (e: Exception) {
            throw UnsatisfiedLinkError("Failed to load native KioArch library: ${e.message}").apply {
                initCause(e)
            }
        }
    }
}
