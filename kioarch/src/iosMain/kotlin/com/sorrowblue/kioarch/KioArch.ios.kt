/*
 * Copyright 2026 SorrowBlue
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sorrowblue.kioarch

import com.sorrowblue.kioarch.internal.cinterop.kio_close_archive
import com.sorrowblue.kioarch.internal.cinterop.kio_entry_t
import com.sorrowblue.kioarch.internal.cinterop.kio_extract_entry
import com.sorrowblue.kioarch.internal.cinterop.kio_get_entry
import com.sorrowblue.kioarch.internal.cinterop.kio_get_entry_count
import com.sorrowblue.kioarch.internal.cinterop.kio_open_archive
import com.sorrowblue.kioarch.internal.cinterop.kio_sink_t
import com.sorrowblue.kioarch.internal.cinterop.kio_source_t
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.Sink
import kotlinx.io.files.Path
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSLock
import platform.Foundation.NSShiftJISStringEncoding
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.offsetInFile
import platform.Foundation.seekToEndOfFile
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.feof
import platform.posix.fopen
import platform.posix.fread
import platform.posix.free
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.memcpy

private const val NsShiftJisWindows31JEncoding = 0x80000A01uL
private const val ErrMsgBufferSize = 512

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun decodeCName(cPointer: CPointer<ByteVar>?): String {
    if (cPointer == null) return ""

    // Calculate length of C string safely without POSIX strlen type issues
    var len = 0uL
    while (cPointer[len.toInt()] != 0.toByte()) {
        len++
    }
    return if (len == 0uL) {
        ""
    } else {
        (
            NSString.create(bytes = cPointer, length = len, encoding = NSUTF8StringEncoding)
                ?: NSString.create(
                    bytes = cPointer,
                    length = len,
                    encoding = NsShiftJisWindows31JEncoding
                )
                ?: NSString.create(
                    bytes = cPointer,
                    length = len,
                    encoding = NSShiftJISStringEncoding
                )
            ) as? String ?: cPointer.toKString()
    }
}

private inline fun <T> NSLock.withLock(action: () -> T): T {
    this.lock()
    try {
        return action()
    } finally {
        this.unlock()
    }
}

private class IosPathSeekableSource(pathStr: String) : SeekableSource {
    private val file = fopen(
        pathStr,
        "rb"
    ) ?: throw ArchiveIOException("Failed to open file: $pathStr")
    private val lock = NSLock()
    private var isClosed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (isClosed) return -1
        buffer.usePinned { pinned ->
            val bytesRead = fread(pinned.addressOf(offset), 1uL, length.toULong(), file)
            if (bytesRead == 0uL) {
                if (feof(file) != 0) {
                    return -1
                }
                throw ArchiveIOException("Error reading from file")
            }
            bytesRead.toInt()
        }
    }

    override fun seek(position: Long) {
        lock.withLock {
            if (isClosed) return
            fseek(file, position, SEEK_SET)
        }
    }

    override fun position(): Long = lock.withLock {
        if (isClosed) 0L else ftell(file)
    }

    override fun length(): Long = lock.withLock {
        if (isClosed) {
            0L
        } else {
            val current = ftell(file)
            fseek(file, 0L, SEEK_END)
            val len = ftell(file)
            fseek(file, current, SEEK_SET)
            len
        }
    }

    override fun close() {
        lock.withLock {
            if (!isClosed) {
                fclose(file)
                isClosed = true
            }
        }
    }
}

private class IosNSDataSeekableSource(private val nsData: NSData) : SeekableSource {
    private val lock = NSLock()
    private var pos = 0L
    private var isClosed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (isClosed) return -1
        val totalLength = nsData.length.toLong()
        if (pos >= totalLength) return -1
        val available = totalLength - pos
        val toRead = if (length > available) available.toInt() else length
        if (toRead <= 0) return -1

        buffer.usePinned { pinned ->
            val rawPtr = nsData.bytes?.reinterpret<ByteVar>() ?: return@withLock -1
            val src = rawPtr + pos
            memcpy(pinned.addressOf(offset), src, toRead.toULong())
        }
        pos += toRead
        toRead
    }

    override fun seek(position: Long) {
        lock.withLock {
            if (isClosed) return
            val totalLength = nsData.length.toLong()
            pos = if (position < 0) {
                0L
            } else if (position > totalLength) {
                totalLength
            } else {
                position
            }
        }
    }

    override fun position(): Long = lock.withLock {
        if (isClosed) 0L else pos
    }

    override fun length(): Long = lock.withLock {
        if (isClosed) 0L else nsData.length.toLong()
    }

    override fun close() {
        lock.withLock {
            isClosed = true
        }
    }
}

private class IosNSURLSeekableSource(private val nsUrl: NSURL) : SeekableSource {
    private val delegate: IosPathSeekableSource

    init {
        val path = nsUrl.path ?: throw ArchiveIOException("URL path is null: $nsUrl")
        delegate = IosPathSeekableSource(path)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(
        buffer,
        offset,
        length
    )
    override fun seek(position: Long) = delegate.seek(position)
    override fun position(): Long = delegate.position()
    override fun length(): Long = delegate.length()
    override fun close() = delegate.close()
}

private class IosNSFileHandleSeekableSource(private val fileHandle: NSFileHandle) : SeekableSource {
    private val lock = NSLock()
    private var isClosed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (isClosed) return -1
        val data = fileHandle.readDataUpToLength(length.toULong(), null) ?: return -1
        if (data.length == 0uL) return -1

        data.bytes?.let { bytesPtr ->
            buffer.usePinned { pinned ->
                memcpy(pinned.addressOf(offset), bytesPtr, data.length)
            }
        }
        data.length.toInt()
    }

    override fun seek(position: Long) {
        lock.withLock {
            if (isClosed) return
            fileHandle.seekToOffset(position.toULong(), null)
        }
    }

    override fun position(): Long = lock.withLock {
        if (isClosed) 0L else fileHandle.offsetInFile.toLong()
    }

    override fun length(): Long = lock.withLock {
        if (isClosed) {
            0L
        } else {
            val current = fileHandle.offsetInFile
            fileHandle.seekToEndOfFile()
            val len = fileHandle.offsetInFile.toLong()
            fileHandle.seekToOffset(current, null)
            len
        }
    }

    override fun close() {
        lock.withLock {
            if (!isClosed) {
                fileHandle.closeFile()
                isClosed = true
            }
        }
    }
}

internal val activeIosStableRefs = AtomicLong(0L)

private class IosSourceContext(val source: SeekableSource, var pendingException: Throwable? = null)

private class IosSinkContext(val sink: Sink, var pendingException: Throwable? = null)

private class IosArchiveReader(
    private val sourceStableRef: StableRef<IosSourceContext>,
    private val handle: Long
) : ArchiveReader {

    private val lock = NSLock()
    private val activeThread = AtomicReference<NSThread?>(null)
    private var isClosed = false

    private inline fun <T> withExclusiveAccess(action: () -> T): T {
        val current = NSThread.currentThread
        check(activeThread.value != current) {
            "Re-entrant ArchiveReader operation from callback is not allowed"
        }
        return lock.withLock {
            check(!isClosed) { "Reader is closed" }
            activeThread.value = current
            try {
                action()
            } finally {
                activeThread.value = null
            }
        }
    }

    override fun getEntries(): List<ArchiveEntry> = withExclusiveAccess {
        val sourceCtx = sourceStableRef.get()
        sourceCtx.pendingException = null
        val count = kio_get_entry_count(handle.toULong())
        sourceCtx.pendingException?.let {
            sourceCtx.pendingException = null
            throw it
        }
        if (count < 0) return emptyList()

        val list = ArrayList<ArchiveEntry>(count)
        memScoped {
            val entry = alloc<kio_entry_t>()
            for (i in 0 until count) {
                if (kio_get_entry(handle.toULong(), i, entry.ptr) != 0) {
                    val cName = entry.name
                    val nameStr = try {
                        decodeCName(cName)
                    } finally {
                        if (cName != null) {
                            free(cName)
                        }
                    }
                    val normalizedName = nameStr.replace('\\', '/')
                    list.add(
                        ArchiveEntry(
                            index = entry.index,
                            name = normalizedName,
                            size = entry.size,
                            compressedSize = entry.size,
                            isDirectory = entry.is_dir != 0,
                            crc = entry.crc.toLong()
                        )
                    )
                }
            }
        }
        sourceCtx.pendingException?.let {
            sourceCtx.pendingException = null
            throw it
        }
        list
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    override fun extractEntry(entry: ArchiveEntry, sink: Sink) = withExclusiveAccess {
        val sourceCtx = sourceStableRef.get()
        sourceCtx.pendingException = null
        val sinkCtx = IosSinkContext(sink)
        val sinkStableRef = StableRef.create(sinkCtx)
        activeIosStableRefs.incrementAndGet()
        try {
            memScoped {
                val errMsgBytes = allocArray<ByteVar>(ErrMsgBufferSize)

                val kioSink = alloc<kio_sink_t>().apply {
                    write =
                        staticCFunction {
                                opaque: COpaquePointer?,
                                buf: CPointer<UByteVar>?,
                                len: Int
                            ->
                            if (opaque == null || buf == null) return@staticCFunction 0
                            val ctx = opaque.asStableRef<IosSinkContext>().get()
                            if (ctx.pendingException != null) return@staticCFunction 0
                            try {
                                val byteArray = ByteArray(len)
                                if (len > 0) {
                                    byteArray.usePinned { pinned ->
                                        memcpy(pinned.addressOf(0), buf, len.toULong())
                                    }
                                }
                                ctx.sink.write(byteArray, 0, len)
                                ctx.sink.emit()
                                1
                            } catch (t: Throwable) {
                                ctx.pendingException = t
                                0
                            }
                        }
                    opaque = sinkStableRef.asCPointer()
                }

                val success = kio_extract_entry(
                    handle.toULong(),
                    entry.index,
                    kioSink.readValue(),
                    errMsgBytes,
                    ErrMsgBufferSize
                )

                val sourceEx = sourceCtx.pendingException
                sourceCtx.pendingException = null
                val sinkEx = sinkCtx.pendingException
                sinkCtx.pendingException = null
                val callbackEx = sinkEx ?: sourceEx
                if (callbackEx != null) {
                    throw callbackEx
                }

                if (success == 0) {
                    val errMsg = errMsgBytes.toKString()
                    throw ArchiveIOException(
                        "Failed to extract entry: ${entry.name}. Native error: $errMsg"
                    )
                }
            }
        } finally {
            sinkStableRef.dispose()
            activeIosStableRefs.decrementAndGet()
        }
    }

    override fun close() {
        val current = NSThread.currentThread
        check(activeThread.value != current) {
            "Cannot close ArchiveReader from within a callback"
        }
        lock.withLock {
            if (!isClosed) {
                isClosed = true
                try {
                    kio_close_archive(handle.toULong())
                } finally {
                    try {
                        sourceStableRef.get().source.close()
                    } finally {
                        sourceStableRef.dispose()
                        activeIosStableRefs.decrementAndGet()
                    }
                }
            }
        }
    }
}

/**
 * iOS implementation of [KioArch] leveraging pure C archive decompression engine.
 */
public actual object KioArch {

    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource] for iOS.
     *
     * @param source the seekable source containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        val sourceCtx = IosSourceContext(source)
        val sourceStableRef = StableRef.create(sourceCtx)
        activeIosStableRefs.incrementAndGet()
        var handle: ULong = 0uL
        try {
            memScoped {
                val errMsgBytes = allocArray<ByteVar>(ErrMsgBufferSize)

                val kioSource = alloc<kio_source_t>().apply {
                    read =
                        staticCFunction {
                                opaque: COpaquePointer?,
                                buf: CPointer<UByteVar>?,
                                len: Int
                            ->
                            if (opaque == null || buf == null) return@staticCFunction -1
                            val ctx = opaque.asStableRef<IosSourceContext>().get()
                            if (ctx.pendingException != null) return@staticCFunction -1
                            try {
                                val byteArray = ByteArray(len)
                                val readBytes = ctx.source.read(byteArray, 0, len)
                                if (readBytes > 0) {
                                    byteArray.usePinned { pinned ->
                                        memcpy(buf, pinned.addressOf(0), readBytes.toULong())
                                    }
                                }
                                readBytes
                            } catch (t: Throwable) {
                                ctx.pendingException = t
                                -1
                            }
                        }
                    seek = staticCFunction { opaque: COpaquePointer?, pos: Long ->
                        if (opaque == null) return@staticCFunction
                        val ctx = opaque.asStableRef<IosSourceContext>().get()
                        if (ctx.pendingException != null) return@staticCFunction
                        try {
                            ctx.source.seek(pos)
                        } catch (t: Throwable) {
                            ctx.pendingException = t
                        }
                    }
                    position = staticCFunction { opaque: COpaquePointer? ->
                        if (opaque == null) return@staticCFunction 0L
                        val ctx = opaque.asStableRef<IosSourceContext>().get()
                        if (ctx.pendingException != null) return@staticCFunction 0L
                        try {
                            ctx.source.position()
                        } catch (t: Throwable) {
                            ctx.pendingException = t
                            0L
                        }
                    }
                    length = staticCFunction { opaque: COpaquePointer? ->
                        if (opaque == null) return@staticCFunction 0L
                        val ctx = opaque.asStableRef<IosSourceContext>().get()
                        if (ctx.pendingException != null) return@staticCFunction 0L
                        try {
                            ctx.source.length()
                        } catch (t: Throwable) {
                            ctx.pendingException = t
                            0L
                        }
                    }
                    opaque = sourceStableRef.asCPointer()
                }

                handle = kio_open_archive(kioSource.readValue(), errMsgBytes, ErrMsgBufferSize)
                sourceCtx.pendingException?.let {
                    sourceCtx.pendingException = null
                    throw it
                }
                if (handle == 0uL) {
                    val errMsg = errMsgBytes.toKString()
                    throw ArchiveIOException("Failed to open archive. Native error: $errMsg")
                }
            }
        } catch (t: Throwable) {
            if (handle != 0uL) {
                kio_close_archive(handle)
            }
            sourceStableRef.dispose()
            activeIosStableRefs.decrementAndGet()
            throw t
        }
        val reader = IosArchiveReader(sourceStableRef, handle.toLong())
        return wrapWithBzip2IfNeeded(source, reader)
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray] for iOS.
     *
     * @param byteArray the byte array containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader =
        createReader(ByteArraySeekableSource(byteArray))

    /**
     * Creates an [ArchiveReader] from a Kotlin Multiplatform [Path] for iOS.
     *
     * @param path the path to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("TooGenericExceptionCaught")
    public actual fun createReader(path: Path): ArchiveReader {
        val source = IosPathSeekableSource(path.toString())
        return try {
            createReader(source)
        } catch (t: Throwable) {
            source.close()
            throw t
        }
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [NSData] for iOS.
     *
     * @param nsData the [NSData] containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public fun createReader(nsData: NSData): ArchiveReader =
        createReader(IosNSDataSeekableSource(nsData))

    /**
     * Creates an [ArchiveReader] from an [NSURL] for iOS.
     *
     * @param nsUrl the [NSURL] pointing to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("TooGenericExceptionCaught")
    public fun createReader(nsUrl: NSURL): ArchiveReader {
        val source = IosNSURLSeekableSource(nsUrl)
        return try {
            createReader(source)
        } catch (t: Throwable) {
            source.close()
            throw t
        }
    }

    /**
     * Creates an [ArchiveReader] from an [NSFileHandle] for iOS.
     *
     * @param fileHandle the [NSFileHandle] to read the archive from
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("TooGenericExceptionCaught")
    public fun createReader(fileHandle: NSFileHandle): ArchiveReader {
        val source = IosNSFileHandleSeekableSource(fileHandle)
        return try {
            createReader(source)
        } catch (t: Throwable) {
            source.close()
            throw t
        }
    }
}
