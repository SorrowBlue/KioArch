# Module KioArch

KioArch is a Kotlin Multiplatform library for on-demand archive inspection and extraction without intermediate disk writes. Backed by native C decompression engines (**7-Zip Core**, **miniz**, and **libbzip2**), it streams decompressed chunks directly from a random-access [com.sorrowblue.kioarch.SeekableSource] into a `kotlinx.io.Sink` while keeping heap allocations constant regardless of archive size.

## Supported Archive Formats

KioArch identifies formats by inspecting magic bytes at the start of the stream rather than relying on file extensions:

- **7z** (`.7z`): LZMA, LZMA2, and PPMd with BCJ, BCJ2, ARM, ARM64, IA64, and Delta filters
- **ZIP** (`.zip`, `.apk`, `.jar`, `.aar`, `.epub`): Deflate and Store, with automatic UTF-8 and Shift_JIS (CP932) filename decoding
- **TAR + Gzip** (`.tar.gz`, `.tgz`): Streaming Gzip (Deflate) decompression over POSIX `ustar` Tar archives
- **Bzip2 / TAR + Bzip2** (`.bz2`, `.tar.bz2`, `.tbz2`, `.tbz`): Streaming Bzip2 decompression

# Package com.sorrowblue.kioarch

Core entry points, reader interfaces, seekable data sources, and exception hierarchy for reading archives across JVM, Android, iOS, JS, and WasmJs.

## Quick Start

Use [com.sorrowblue.kioarch.KioArch.createReader] to open an archive from a file path, in-memory `ByteArray`, platform-specific descriptor (`java.io.File`, Android `Uri` / `ParcelFileDescriptor`, Apple `NSData` / `NSURL` / `NSFileHandle`), or a custom [com.sorrowblue.kioarch.SeekableSource]:

```kotlin
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.extractToByteArray
import kotlinx.io.files.Path

KioArch.createReader(Path("archive.7z")).use { reader ->
    for (entry in reader.getEntries()) {
        if (!entry.isDirectory) {
            val bytes = entry.extractToByteArray(reader)
            println("${entry.name}: ${bytes.size} bytes")
        }
    }
}
```
