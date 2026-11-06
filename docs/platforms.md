# Platform Guides

KioArch provides idiomatic factory overloads and [`SeekableSource`](api/-kio-arch/com.sorrowblue.kioarch/-seekable-source/index.html) implementations tailored to each target platform.

---

## JVM (Desktop & Server)

On JVM, native binaries for Windows (`x64`), macOS (`Universal`), and Linux (`x64`) are bundled inside the JAR and extracted/loaded automatically on first use. You can create a reader directly from a `java.io.File`, `java.nio.file.Path`, or `kotlinx.io.files.Path`:

```kotlin
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.createReader
import kotlinx.io.asSink
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths

// From java.io.File
KioArch.createReader(File("archive.7z")).use { reader ->
    val entry = reader.getEntries().first { !it.isDirectory }
    FileOutputStream(File("output.bin")).use { out ->
        val sink = out.asSink()
        reader.extractEntry(entry, sink)
        sink.flush()
    }
}

// From java.nio.file.Path
KioArch.createReader(Paths.get("archive.zip")).use { reader ->
    println("Found ${reader.getEntries().size} entries")
}
```

Both `File` and NIO `Path` overloads use [`FileSeekableSource`](api/-kio-arch/com.sorrowblue.kioarch/-file-seekable-source/index.html) and [`PathSeekableSource`](api/-kio-arch/com.sorrowblue.kioarch/-path-seekable-source/index.html), which implement [`DirectSeekableSource`](api/-kio-arch/com.sorrowblue.kioarch/-direct-seekable-source/index.html) for zero-copy JNI transfers via `FileChannel`.

---

## Android

### Automatic Initialization & 16 KB Page Size

KioArch ships prebuilt JNI libraries for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` aligned for Android 15+ 16 KB page sizes. By default, [`KioArchInitializer`](api/-kio-arch/com.sorrowblue.kioarch/-kio-arch-initializer/index.html) loads `libkioarch.so` automatically at app startup via Jetpack App Startup.

If you disable automatic initialization in `AndroidManifest.xml`, call [`KioArch.loadLibrary()`](api/-kio-arch/com.sorrowblue.kioarch/-kio-arch/load-library.html) once before opening an archive.

### Reading from Storage Access Framework (`content://` `Uri`)

When a user selects an archive via the Android document picker, pass the `ParcelFileDescriptor` obtained from `ContentResolver.openFileDescriptor` directly to [`KioArch.createReader(ParcelFileDescriptor)`](api/-kio-arch/com.sorrowblue.kioarch/-kio-arch/create-reader.html). No temporary cache copy is required:

```kotlin
import android.content.Context
import android.net.Uri
import com.sorrowblue.kioarch.KioArch
import kotlinx.io.Buffer

fun inspectContentUri(context: Context, uri: Uri) {
    val pfd = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
        "Unable to open file descriptor for $uri"
    }

    // Closing the reader automatically closes the ParcelFileDescriptor
    KioArch.createReader(pfd).use { reader ->
        for (entry in reader.getEntries()) {
            if (!entry.isDirectory) {
                val buffer = Buffer()
                reader.extractEntry(entry, buffer)
            }
        }
    }
}
```

### Custom Zero-Copy Source with `DirectSeekableSource`

When reading over custom channels (such as SMB network shares or encrypted virtual filesystems), implement [`DirectSeekableSource`](api/-kio-arch/com.sorrowblue.kioarch/-direct-seekable-source/index.html) so the JNI layer can populate a native-allocated direct `ByteBuffer` without intermediate heap copies:

```kotlin
import com.sorrowblue.kioarch.DirectSeekableSource
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

class ChannelSeekableSource(
    private val channel: SeekableByteChannel
) : DirectSeekableSource {
    override fun read(byteBuffer: ByteBuffer): Int = channel.read(byteBuffer)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length))

    override fun seek(position: Long) {
        channel.position(position)
    }

    override fun position(): Long = channel.position()
    override fun length(): Long = channel.size()
    override fun close() = channel.close()
}
```

---

## iOS (Kotlin/Native)

On iOS (`iosArm64`, `iosSimulatorArm64`, `iosX64`), the static `libkioarch.a` archive is embedded directly into the published `.klib` artifact, so downstream Xcode and Kotlin Multiplatform projects do not need manual linker flags.

[`KioArch.createReader`](api/-kio-arch/com.sorrowblue.kioarch/-kio-arch/create-reader.html) provides native overloads for `NSData`, `NSURL`, `NSFileHandle`, and `kotlinx.io.files.Path`:

```kotlin
import com.sorrowblue.kioarch.KioArch
import kotlinx.io.Buffer
import platform.Foundation.NSData
import platform.Foundation.NSURL

// From an in-memory NSData buffer
fun readFromData(data: NSData) {
    KioArch.createReader(data).use { reader ->
        reader.getEntries().forEach { println(it.name) }
    }
}

// From a local file NSURL (backed by NSFileHandle random access)
fun readFromFileUrl(fileUrl: NSURL) {
    KioArch.createReader(fileUrl).use { reader ->
        val firstFile = reader.getEntries().first { !it.isDirectory }
        val sink = Buffer()
        reader.extractEntry(firstFile, sink)
    }
}
```

---

## JS & WebAssembly (`wasmJs`)

### Asynchronous Module Initialization

On `js` and `wasmJs` targets, the Emscripten WebAssembly binary must be loaded asynchronously once before calling any synchronous `KioArch` APIs:

```kotlin
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.initialize
import com.sorrowblue.kioarch.loadKioArchModule

suspend fun initKioArchWeb() {
    val module = loadKioArchModule()
    KioArch.initialize(module)
}
```

### Browser (Web Worker + `FileReaderSync`) vs. Node.js

Because [`SeekableSource.read`](api/-kio-arch/com.sorrowblue.kioarch/-seekable-source/read.html) is synchronous, large archive files in the browser should be processed inside a **Web Worker** using the standard `FileReaderSync` API to read slices of a `File` or `Blob` on demand without loading the entire archive into memory:

=== "Browser (Web Worker)"

    ```kotlin
    import com.sorrowblue.kioarch.KioArch
    import com.sorrowblue.kioarch.SeekableSource
    import com.sorrowblue.kioarch.initialize
    import com.sorrowblue.kioarch.loadKioArchModule

    suspend fun processInWorker(archiveBytes: ByteArray) {
        KioArch.initialize(loadKioArchModule())
        KioArch.createReader(archiveBytes).use { reader ->
            reader.getEntries().forEach { entry ->
                println("${entry.name}: ${entry.size} bytes")
            }
        }
    }
    ```

=== "Node.js (`Path`)"

    ```kotlin
    import com.sorrowblue.kioarch.KioArch
    import com.sorrowblue.kioarch.initialize
    import com.sorrowblue.kioarch.loadKioArchModule
    import kotlinx.io.files.Path

    suspend fun processInNode() {
        KioArch.initialize(loadKioArchModule())
        // Uses Node's synchronous fs module (openSync / readSync / fstatSync)
        KioArch.createReader(Path("archive.tar.gz")).use { reader ->
            reader.getEntries().forEach { println(it.name) }
        }
    }
    ```
