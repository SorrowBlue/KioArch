# Getting Started

This guide covers adding KioArch to a Kotlin Multiplatform or single-platform Gradle build and performing common archive operations.

---

## Gradle Setup

KioArch is published to Maven Central under the group ID `com.sorrowblue.kioarch`.

=== "Kotlin Multiplatform (`build.gradle.kts`)"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("com.sorrowblue.kioarch:kioarch:0.3.3")
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
            }
        }
    }
    ```

=== "Version Catalog (`gradle/libs.versions.toml`)"

    ```toml
    [versions]
    kioarch = "0.3.3"
    kotlinx-io = "0.9.0"

    [libraries]
    kioarch = { module = "com.sorrowblue.kioarch:kioarch", version.ref = "kioarch" }
    kotlinx-io-core = { module = "org.jetbrains.kotlinx:kotlinx-io-core", version.ref = "kotlinx-io" }
    ```

=== "JVM / Android Only (`build.gradle.kts`)"

    ```kotlin
    dependencies {
        implementation("com.sorrowblue.kioarch:kioarch:0.3.3")
        implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
    }
    ```

---

## Basic Usage

### 1. Opening an Archive and Listing Entries

[`KioArch.createReader`](api/-kio-arch/com.sorrowblue.kioarch/-kio-arch/create-reader.html) inspects the leading bytes of the input source, initializes the matching native decoder, and returns an [`ArchiveReader`](api/-kio-arch/com.sorrowblue.kioarch/-archive-reader/index.html). Always wrap the reader in a `.use { ... }` block so native handles and the underlying [`SeekableSource`](api/-kio-arch/com.sorrowblue.kioarch/-seekable-source/index.html) are closed deterministically.

```kotlin
import com.sorrowblue.kioarch.KioArch
import kotlinx.io.files.Path

val archivePath = Path("path/to/archive.7z")

KioArch.createReader(archivePath).use { reader ->
    for (entry in reader.getEntries()) {
        val type = if (entry.isDirectory) "DIR " else "FILE"
        println("[$type] ${entry.name} (${entry.compressedSize} -> ${entry.size} bytes)")
    }
}
```

!!! note "Path Normalization"
    Archive entry names ([`ArchiveEntry.name`](api/-kio-arch/com.sorrowblue.kioarch/-archive-entry/name.html)) are automatically normalized to use forward slashes (`/`) regardless of whether the archive was created on Windows or Unix.

---

### 2. Streaming an Entry into a `Sink`

To extract large files without allocating the entire uncompressed payload in heap memory, stream the entry into any [`kotlinx.io.Sink`](https://kotlinlang.org/api/kotlinx-io/kotlinx-io-core/kotlinx.io/-sink/index.html) using [`ArchiveReader.extractEntry`](api/-kio-arch/com.sorrowblue.kioarch/-archive-reader/extract-entry.html) or the [`ArchiveEntry.extract`](api/-kio-arch/com.sorrowblue.kioarch/extract.html) extension:

```kotlin
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.extract
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

KioArch.createReader(Path("archive.zip")).use { reader ->
    val target = reader.getEntries().first { !it.isDirectory && it.name == "docs/manual.pdf" }

    SystemFileSystem.sink(Path("output/manual.pdf")).buffered().use { sink ->
        with(reader) {
            target.extract(sink)
        }
        sink.flush()
    }
}
```

---

### 3. Extracting Small Entries to a `ByteArray`

For small configuration files, manifests, or thumbnails that fit comfortably in memory, use [`extractToByteArray`](api/-kio-arch/com.sorrowblue.kioarch/extract-to-byte-array.html):

```kotlin
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.extractToByteArray
import kotlinx.io.files.Path

val jsonText: String = KioArch.createReader(Path("bundle.tar.gz")).use { reader ->
    val entry = reader.getEntries().first { it.name == "manifest.json" }
    with(reader) {
        entry.extractToByteArray().decodeToString()
    }
}
```

---

## Error Handling

All native and I/O failures during header parsing or extraction are translated into subclasses of the sealed [`ArchiveException`](api/-kio-arch/com.sorrowblue.kioarch/-archive-exception/index.html) hierarchy:

| Exception Class | Cause |
| :--- | :--- |
| [`ArchiveUnsupportedException`](api/-kio-arch/com.sorrowblue.kioarch/-archive-unsupported-exception/index.html) | Unrecognized magic signature or unsupported compression method (such as encrypted headers). |
| [`ArchiveCorruptedException`](api/-kio-arch/com.sorrowblue.kioarch/-archive-corrupted-exception/index.html) | Truncated archive, invalid header structure, or CRC32 / bitstream verification failure. |
| [`ArchiveIOException`](api/-kio-arch/com.sorrowblue.kioarch/-archive-i-o-exception/index.html) | Underlying [`SeekableSource`](api/-kio-arch/com.sorrowblue.kioarch/-seekable-source/index.html) read/seek failure or [`Sink`](https://kotlinlang.org/api/kotlinx-io/kotlinx-io-core/kotlinx.io/-sink/index.html) write error. |
| [`ArchiveInvalidException`](api/-kio-arch/com.sorrowblue.kioarch/-archive-invalid-exception/index.html) | Invalid entry index or illegal operation on a closed [`ArchiveReader`](api/-kio-arch/com.sorrowblue.kioarch/-archive-reader/index.html). |
| [`ArchiveOutOfMemoryException`](api/-kio-arch/com.sorrowblue.kioarch/-archive-out-of-memory-exception/index.html) | Native decoder dictionary or heap allocation failure. |

```kotlin
import com.sorrowblue.kioarch.ArchiveCorruptedException
import com.sorrowblue.kioarch.ArchiveException
import com.sorrowblue.kioarch.ArchiveUnsupportedException
import com.sorrowblue.kioarch.KioArch

try {
    KioArch.createReader(rawBytes).use { reader ->
        // Inspect or extract entries
    }
} catch (e: ArchiveUnsupportedException) {
    println("Unsupported archive format: ${e.message}")
} catch (e: ArchiveCorruptedException) {
    println("Archive is damaged or incomplete: ${e.message}")
} catch (e: ArchiveException) {
    println("Extraction failed: ${e.message}")
}
```
