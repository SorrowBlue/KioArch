# 📦 KioArch (Kotlin I/O Archive)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Maven Central](https://img.shields.io/badge/Maven_Central-Publishing-orange.svg?style=flat)](https://search.maven.org)
[![Platform](https://img.shields.io/badge/Platform-JVM_%7C_Android-blue.svg?style=flat)](#)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg?style=flat)](#)

**KioArch** is a premium, high-performance Kotlin Multiplatform library for **transparent, filesystem-free archive extraction** (supporting both `.7z` and `.zip` archives). 

Powered by embedded, highly-optimized, pure C native engines (**7-Zip Core** and **miniz**), KioArch offers an elegant KMP API wrapping standard Kotlin I/O (`kotlinx.io.Source` / `kotlinx.io.Sink`) with **zero temporary files** and **$O(1)$ constant memory overhead**.

---

## ✨ Key Features

*   **🛡️ Multi-Format Autoprobe**: Automatically inspects magic bytes (`7z` or `PK`) under the hood to invoke the correct C engine. You call the same unified API regardless of the archive format.
*   **🚀 Filesystem-Free Extraction**: Streams data directly to and from memory-based buffers or custom inputs (e.g. Android Content Provider `Uri`s) without writing any temporary files to disk.
*   **💧 Constant Memory Footprint**: Decompresses files block-by-block and pipes them directly into `kotlinx.io.Sink`, keeping memory usage constant ($O(1)$) even for multi-gigabyte archives.
*   **🛠️ Zero-Config Native Bindings**: Automatically builds, packages, and loads native shared libraries (`.dll`, `.so`, `.dylib`) out of the box without requiring manual library installs.
*   **🔌 100% Isolated Submodules**: Tracks native `7zip` and `miniz` codebases as untouched Git submodules, ensuring safe, continuous updates from upstream.

---

## 💻 Supported Specifications

KioArch provides high-performance, cross-platform archive processing across various platforms, architectures, and formats.

### 1. Supported Platforms

| Platform | Targets / Architectures | Note |
| :--- | :--- | :--- |
| **JVM (Desktop / Server)** | Windows (`x64`), macOS (`Universal`), Linux (`x64`) | Automatically extracts and loads embedded native libraries. |
| **Android** | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` | Fully compatible with 16 KB page size on Android 15+. Integrates with Jetpack App Startup. |
| **iOS** | iOS Device (`arm64`), iOS Simulator (`x64`, `arm64`) | Distributed as an XCFramework with static C++ bindings. |
| **JS (Node.js / Web)** | Kotlin/JS (Node.js / Browser) | Highly optimized Emscripten Wasm execution. |
| **WasmJs (Node.js / Web)**| Kotlin/WasmJs (Node.js / Browser) | Highly optimized Emscripten Wasm execution. |

### 2. Supported Archive Formats (Autoprobe)

KioArch inspects the **magic bytes** at the beginning of the file under the hood to automatically detect the format and select the correct extraction engine. 

Because it relies purely on binary magic bytes rather than file extensions, it seamlessly supports all of the following:

- **`.7z`**: 7-Zip Archive format
- **`.zip`**: ZIP Archive format
  - **ZIP-Compatible Formats**: Also seamlessly extracts any ZIP-based file formats like **`.apk`**, **`.jar`**, **`.aar`**, **`.epub`**, etc.
- **`.tar.gz` / `.tgz`**: Gzip Compressed Tar Archive format

### 3. Decode & Encode Capabilities

- **Read-Only / Decompress Only**:
  - The current version of KioArch is **Read-Only**. It is optimized strictly for fast and filesystem-free **decompression (decoding)**. Creating or writing archives (encoding) is not supported.
- **Supported Compression Algorithms (Decoders)**:
  - **ZIP (via miniz)**:
    - `Deflate`
    - `Store` (No compression)
  - **7z (via 7-Zip Core)**:
    - `LZMA`
    - `LZMA2`
    - `PPMd`
    - CPU filters including `BCJ`, `BCJ2`, `ARM`, `ARM64`, `IA64`, and `Delta`
  - **Tar/Gzip (via Custom Streams)**:
    - `Gzip (Deflate)` (Automatically decompresses block-by-block and streams standard `ustar` Tar entries on the fly)

### 4. Smart Encoding (Shift_JIS / UTF-8)

When processing ZIP files created on Windows, Japanese file names are often encoded in **Shift_JIS (CP932)**. KioArch automatically detects the encoding and falls back to Shift_JIS when UTF-8 decoding fails. This avoids garbled file name issues (fully supported across JVM, Android, iOS, JS, and Wasm targets).

---

## 🚀 Quick Start

### 1. JVM (Java/Desktop) Usage

Extract an archive from a standard JVM `File` directly into a `kotlinx.io` `Sink` or standard file output:

```kotlin
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.createReader
import kotlinx.io.asSink
import java.io.File
import java.io.FileOutputStream

val archiveFile = File("path/to/archive.7z") // or archive.zip

KioArch.createReader(archiveFile).use { reader ->
    // Query catalog entries
    val entries = reader.getEntries()
    println("Total entries: ${entries.size}")

    // Extract a specific entry
    val targetEntry = entries.first { !it.isDirectory && it.name == "notes.txt" }
    
    FileOutputStream(File("extracted_notes.txt")).use { outputStream ->
        val sink = outputStream.asSink()
        reader.extractEntry(targetEntry, sink)
        sink.flush()
    }
}
```

### 2. Android Usage

Perform dynamic archive extraction from an Android content `Uri` (e.g., chosen via the System Picker) without temp files:

```kotlin
import android.content.Context
import android.net.Uri
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.SeekableSource
import kotlinx.io.Buffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

// Define a custom SeekableSource wrapping an Android Uri
class UriSeekableSource(context: Context, uri: Uri) : SeekableSource {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
    private val fis = FileInputStream(pfd.fileDescriptor)
    private val channel = fis.channel

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        return channel.read(byteBuffer)
    }

    override fun seek(position: Long) {
        channel.position(position)
    }

    override fun position(): Long = channel.position()
    override fun length(): Long = pfd.statSize

    override fun close() {
        channel.close()
        fis.close()
        pfd.close()
    }
}

// Extraction
fun extractFromUri(context: Context, archiveUri: Uri) {
    val source = UriSeekableSource(context, archiveUri)
    
    KioArch.createReader(source).use { reader ->
        val fileEntry = reader.getEntries().first { !it.isDirectory }
        val buffer = Buffer()
        
        // Decompresses directly to in-memory buffer
        reader.extractEntry(fileEntry, buffer)
        println("Extracted ${fileEntry.name} data size: ${buffer.size} bytes")
    }
}
```

#### ⚙️ Native Library Loading Timing & Policy (Android)

By default, `KioArch` utilizes **Jetpack App Startup** to automatically and safely preload the C++ native library (`libkioarch.so`) during application startup. This avoids runtime stutter (jank) when you perform archive operations for the first time.

##### 1. Automatic Early Loading (Default)
You do not need to perform any configuration. The library automatically registers a `KioArchInitializer` which loads the JNI library early on startup.

##### 2. Disabling Automatic Loading
If you wish to control the loading timing manually (e.g., to reduce application startup time or perform custom initialization), you can disable the automatic initializer by adding the following snippet to your app's `AndroidManifest.xml`:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge"
    xmlns:tools="http://schemas.android.com/tools">
    <meta-data
        android:name="com.sorrowblue.kioarch.KioArchInitializer"
        tools:node="remove" />
</provider>
```

##### 3. Manual Loading
Once disabled, you can manually trigger the native library load at any time (e.g., in a background thread or a custom lazy loading routine) by calling:

```kotlin
import com.sorrowblue.kioarch.KioArch

// Load the native library manually
KioArch.loadLibrary()
```

> [!WARNING]
> If you disable automatic loading and do not call `KioArch.loadLibrary()` manually before performing any archive operations, the library will fall back to **lazy load-on-demand**.
> This will print a warning log to logcat (`KioArch: KioArch JNI library is being loaded lazily on-demand.`) as it may cause UI thread blocking and frame drops on older devices due to blocking disk I/O and linking overhead.

### 3. JS / Wasm (Web/Node.js) Usage

For JS and Wasm (WebAssembly) targets, since WebAssembly loading is inherently asynchronous in web/Node.js environments, you must initialize `KioArch` with the modularized Emscripten module instance before calling any archive operations.

#### Step 1: Preload and Initialize the Module

```kotlin
import com.sorrowblue.kioarch.KioArch

// 1. Load the Wasm module asynchronously using your environment's loader or dynamic import
// For example, in JS/WasmJs with Emscripten's modularized loader:
val module = createKioArchModule() // Generated by Emscripten (e.g. from kioarch.js)

// 2. Initialize KioArch with the loaded module
KioArch.initialize(module)
```

#### Step 2: Transparent In-Memory Extraction

After initialization, you can use KioArch normally. Because JS/Wasm targets are filesystem-free, you read files directly from byte arrays or memory streams:

```kotlin
import com.sorrowblue.kioarch.KioArch
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

val archiveBytes: ByteArray = getArchiveData() // Fetch from network or read from memory

// Create reader from in-memory ByteArray
KioArch.createReader(archiveBytes).use { reader ->
    val entries = reader.getEntries()
    println("Found ${entries.size} entries")

    // Find first non-directory file entry
    val targetEntry = entries.first { !it.isDirectory }

    val buffer = Buffer()
    reader.extractEntry(targetEntry, buffer)
    
    val extractedBytes = buffer.readByteArray()
    println("Extracted: ${extractedBytes.decodeToString()}")
}
```

---

## 🛠️ Build & Development

KioArch features fully automated native compilation during Gradle builds. When you run tests or bundle artifacts, CMake runs automatically to build and package native shared libraries directly into your classpath resources.

### Prerequisites

*   **JDK 21**
*   **CMake (4.1.2)**
*   **Android NDK** (for Android cross-compilation)
*   **Compiler Toolchain**: MSVC (Windows), GCC/Clang (Linux), Xcode/Clang (macOS)

### Building and Testing

Run the full clean and JVM test suite:
```powershell
# Set JAVA_HOME path and run
$env:JAVA_HOME="C:\Program Files\Java\jdk-21" 
.\gradlew.bat :kioarch:clean :kioarch:jvmTest
```

Cross-compile all Android native ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`):
```powershell
.\gradlew.bat :kioarch:assembleDebug
```

---

## 📄 License

```text
Copyright 2026 SorrowBlue

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
