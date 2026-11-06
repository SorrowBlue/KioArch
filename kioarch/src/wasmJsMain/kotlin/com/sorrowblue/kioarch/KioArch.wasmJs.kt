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

@file:Suppress("TooManyFunctions")

package com.sorrowblue.kioarch

import kotlinx.io.Sink
import kotlinx.io.files.Path

private const val SourceStructSize = 20
private const val EntryStructSize = 24
private const val SinkStructSize = 8
private const val ErrMsgBufferSize = 512
private const val EntrySizeOffset = 1
private const val InitialReadBufferSize = 256 * 1024
private const val WriteChunkSize = 64 * 1024

@Suppress("MagicNumber")
private fun readIntLe(byteArray: ByteArray, offset: Int): Int =
    (byteArray[offset].toInt() and 0xFF) or
        ((byteArray[offset + 1].toInt() and 0xFF) shl 8) or
        ((byteArray[offset + 2].toInt() and 0xFF) shl 16) or
        ((byteArray[offset + 3].toInt() and 0xFF) shl 24)

@Suppress("MagicNumber")
private fun writeIntLe(byteArray: ByteArray, offset: Int, value: Int) {
    byteArray[offset] = value.toByte()
    byteArray[offset + 1] = (value ushr 8).toByte()
    byteArray[offset + 2] = (value ushr 16).toByte()
    byteArray[offset + 3] = (value ushr 24).toByte()
}

private class WasmSourceCallbackPtrs(
    val readPtr: Int,
    val seekPtr: Int,
    val posPtr: Int,
    val lenPtr: Int
)

private fun throwNotInitializedWasm(): Nothing = throw IllegalStateException(
    "KioArch has not been initialized. Call KioArch.initialize(module) first."
)

private fun registerWasmSourceCallbacks(module: JsAny): WasmSourceCallbackPtrs {
    val readPtr = registerReadCallback(module)
    val seekPtr = registerSeekCallback(module)
    val posPtr = registerPosCallback(module)
    val lenPtr = registerLenCallback(module)
    return WasmSourceCallbackPtrs(readPtr, seekPtr, posPtr, lenPtr)
}

private fun cleanupWasmSourcePtrs(
    module: JsAny,
    sourcePtr: Int,
    ptrs: WasmSourceCallbackPtrs?,
    opaqueId: Int
) {
    if (sourcePtr != 0) wasmFree(module, sourcePtr)
    if (ptrs != null) {
        if (ptrs.readPtr != 0) wasmRemoveFunction(module, ptrs.readPtr)
        if (ptrs.seekPtr != 0) wasmRemoveFunction(module, ptrs.seekPtr)
        if (ptrs.posPtr != 0) wasmRemoveFunction(module, ptrs.posPtr)
        if (ptrs.lenPtr != 0) wasmRemoveFunction(module, ptrs.lenPtr)
    }
    removeSourceJs(opaqueId)
    releaseOpaque(opaqueId)
}

@Suppress("MagicNumber")
private fun copyToHeap(module: JsAny, bufPtr: Int, byteArray: ByteArray, offset: Int, len: Int) {
    var i = 0
    if ((bufPtr and 3) == 0) {
        val limit32 = len - 31
        val baseIdx = bufPtr ushr 2
        while (i < limit32) {
            val srcOff = offset + i
            writeHeap32x8(
                module,
                baseIdx + (i ushr 2),
                readIntLe(byteArray, srcOff),
                readIntLe(byteArray, srcOff + 4),
                readIntLe(byteArray, srcOff + 8),
                readIntLe(byteArray, srcOff + 12),
                readIntLe(byteArray, srcOff + 16),
                readIntLe(byteArray, srcOff + 20),
                readIntLe(byteArray, srcOff + 24),
                readIntLe(byteArray, srcOff + 28)
            )
            i += 32
        }
        val limit4 = len - 3
        while (i < limit4) {
            writeHeap32(module, bufPtr, i ushr 2, readIntLe(byteArray, offset + i))
            i += 4
        }
    }
    while (i < len) {
        writeHeapU8(module, bufPtr + i, byteArray[offset + i].toInt() and 0xFF)
        i++
    }
}

@Suppress("MagicNumber", "LongParameterList")
private fun copyFromHeap(module: JsAny, bufPtr: Int, byteArray: ByteArray, len: Int) {
    copyFromHeapJs(
        module,
        bufPtr,
        set32Fn = { i, w0, w1, w2, w3, w4, w5, w6, w7 ->
            writeIntLe(byteArray, i, w0)
            writeIntLe(byteArray, i + 4, w1)
            writeIntLe(byteArray, i + 8, w2)
            writeIntLe(byteArray, i + 12, w3)
            writeIntLe(byteArray, i + 16, w4)
            writeIntLe(byteArray, i + 20, w5)
            writeIntLe(byteArray, i + 24, w6)
            writeIntLe(byteArray, i + 28, w7)
        },
        setByteFn = { i, value -> byteArray[i] = value },
        len = len
    )
}

// Global map to track active Kotlin objects passed to C callbacks for Wasm
private val opaqueMap = mutableMapOf<Int, Any>()
private var nextOpaqueId = 1

private class WasmSourceContext(val source: SeekableSource, var pendingException: Throwable? = null)

private class WasmSinkContext(val sink: Sink, var pendingException: Throwable? = null)

private fun registerOpaque(obj: Any): Int {
    val id = nextOpaqueId++
    opaqueMap[id] = obj
    return id
}

private fun getOpaque(id: Int): Any? = opaqueMap[id]

private fun releaseOpaque(id: Int) {
    opaqueMap.remove(id)
}

internal fun getWasmActiveNativeAllocations(): Long =
    wasmModule?.let { wasmGetActiveAllocations(it).toLong() } ?: 0L

internal fun getWasmActiveOpaqueReferences(): Long = opaqueMap.size.toLong()

/**
 * WasmJs implementation of [KioArch] utilizing WebAssembly compiled from pure C archive engines.
 */
public actual object KioArch {

    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource] for WasmJs.
     *
     * @param source the seekable source containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("MagicNumber", "TooGenericExceptionCaught")
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        val module = wasmModule ?: throwNotInitializedWasm()

        val sourceCtx = WasmSourceContext(source)
        val sourceOpaqueId = registerOpaque(sourceCtx)
        var sourcePtr = 0
        var ptrs: WasmSourceCallbackPtrs? = null
        var handle: JsAny? = null

        val reader = try {
            // Register Kotlin SeekableSource callbacks into JS global map
            putWasmSourceCallbacks(module, sourceOpaqueId, sourceCtx)

            // Register C-compatible callback function pointers in Emscripten
            ptrs = registerWasmSourceCallbacks(module)

            // Allocate and populate kio_source_t structure (20 bytes)
            sourcePtr = wasmMalloc(module, SourceStructSize)
            writeHeap32(module, sourcePtr, 0, ptrs.readPtr)
            writeHeap32(module, sourcePtr, 1, ptrs.seekPtr)
            writeHeap32(module, sourcePtr, 2, ptrs.posPtr)
            writeHeap32(module, sourcePtr, 3, ptrs.lenPtr)
            writeHeap32(module, sourcePtr, 4, sourceOpaqueId)

            // Allocate error message buffer (512 bytes)
            val errMsgPtr = wasmMalloc(module, ErrMsgBufferSize)
            try {
                val rawHandle = wasmOpenArchive(module, sourcePtr, errMsgPtr, ErrMsgBufferSize)
                val handleVal = toBigInt(rawHandle)

                if (!isZeroBigInt(handleVal)) {
                    handle = handleVal
                }
                sourceCtx.pendingException?.let {
                    sourceCtx.pendingException = null
                    throw it
                }
                if (isZeroBigInt(handleVal)) {
                    val errMsg = wasmUtf8ToString(module, errMsgPtr)
                    throw ArchiveIOException("Failed to open archive. Native error: $errMsg")
                }
            } finally {
                wasmFree(module, errMsgPtr)
            }

            WasmArchiveReader(
                module,
                sourceOpaqueId,
                sourceCtx,
                sourcePtr,
                ptrs.readPtr,
                ptrs.seekPtr,
                ptrs.posPtr,
                ptrs.lenPtr,
                handle!!
            )
        } catch (t: Throwable) {
            if (handle != null) {
                wasmCloseArchive(module, handle)
            }
            cleanupWasmSourcePtrs(module, sourcePtr, ptrs, sourceOpaqueId)
            throw t
        }
        return wrapWithBzip2IfNeeded(source, reader)
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray] for WasmJs.
     *
     * @param byteArray the byte array containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader =
        createReader(ByteArraySeekableSource(byteArray))

    /**
     * Creates an [ArchiveReader] from a Kotlin Multiplatform [Path] for WasmJs.
     *
     * @param path the path to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("TooGenericExceptionCaught")
    public actual fun createReader(path: Path): ArchiveReader {
        if (isNodeJsWasm()) {
            val module = wasmModule ?: throw IllegalStateException(
                "KioArch has not been initialized. Call KioArch.initialize(module) first."
            )
            val source = NodeFileSeekableSource(path.toString(), module)
            return try {
                createReader(source)
            } catch (t: Throwable) {
                source.close()
                throw t
            }
        }
        throw UnsupportedOperationException(
            "File system access using Path is not supported in browser WasmJs environment. " +
                "Use ByteArray or custom SeekableSource."
        )
    }
}

@Suppress(
    "SwallowedException",
    "TooGenericExceptionCaught",
    "LongMethod",
    "CyclomaticComplexMethod"
)
private fun putWasmSourceCallbacks(
    module: JsAny,
    sourceOpaqueId: Int,
    sourceCtx: WasmSourceContext
) {
    var readBuffer = ByteArray(InitialReadBufferSize)
    putSourceJs(
        sourceOpaqueId,
        readFn = { bufPtr, len ->
            if (sourceCtx.pendingException != null) {
                -1
            } else {
                val innerSource = sourceCtx.source
                try {
                    if (innerSource is ByteArraySeekableSource) {
                        val srcBytes = innerSource.bytes
                        val currentPos = innerSource.position().toInt()
                        if (currentPos >= srcBytes.size) {
                            -1
                        } else {
                            val toRead = minOf(len, srcBytes.size - currentPos)
                            if (toRead > 0) {
                                copyToHeap(module, bufPtr, srcBytes, currentPos, toRead)
                                innerSource.seek((currentPos + toRead).toLong())
                            }
                            toRead
                        }
                    } else {
                        if (readBuffer.size < len) {
                            readBuffer = ByteArray(len)
                        }
                        val readBytes = innerSource.read(readBuffer, 0, len)
                        if (readBytes > 0) {
                            copyToHeap(module, bufPtr, readBuffer, 0, readBytes)
                        }
                        readBytes
                    }
                } catch (e: Throwable) {
                    if (sourceCtx.pendingException == null) {
                        sourceCtx.pendingException = e
                    }
                    -1
                }
            }
        },
        seekFn = { pos ->
            if (sourceCtx.pendingException == null) {
                try {
                    sourceCtx.source.seek(pos.toLong())
                } catch (e: Throwable) {
                    if (sourceCtx.pendingException == null) {
                        sourceCtx.pendingException = e
                    }
                }
            }
        },
        posFn = {
            if (sourceCtx.pendingException != null) {
                0.0
            } else {
                try {
                    sourceCtx.source.position().toDouble()
                } catch (e: Throwable) {
                    if (sourceCtx.pendingException == null) {
                        sourceCtx.pendingException = e
                    }
                    0.0
                }
            }
        },
        lenFn = {
            if (sourceCtx.pendingException != null) {
                0.0
            } else {
                try {
                    sourceCtx.source.length().toDouble()
                } catch (e: Throwable) {
                    if (sourceCtx.pendingException == null) {
                        sourceCtx.pendingException = e
                    }
                    0.0
                }
            }
        }
    )
}

/**
 * A Node.js-specific [SeekableSource] implementation for Kotlin/WasmJs that performs synchronous
 * random access via the Node.js `node:fs` module (`openSync`, `readSync`, `fstatSync`,
 * `closeSync`).
 *
 * This class is intended for Node.js environments (CLI, server, or tests) and is not supported in
 * browser runtimes.
 *
 * @param pathStr the filesystem path to the archive file to open in read-only mode.
 * @param module the initialized Emscripten WebAssembly module instance ([wasmModule]).
 * @throws ArchiveIOException if the file at [pathStr] cannot be opened.
 */
public class NodeFileSeekableSource(private val pathStr: String, private val module: JsAny) :
    SeekableSource {
    private val fd: Int
    private var pos: Long = 0L
    private val totalLength: Long
    private var isClosed = false

    init {
        initNodeFs()
        fd = nodeOpenSync(pathStr)
        if (fd < 0) throw ArchiveIOException("Failed to open file: $pathStr")
        totalLength = nodeSizeSync(fd).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isClosed) return -1
        val result: Int
        if (pos >= totalLength) {
            result = -1
        } else {
            val bytesToRead = minOf(length.toLong(), totalLength - pos).toInt()
            if (bytesToRead <= 0) {
                result = 0
            } else {
                val readBytes = nodeReadSync(
                    fd,
                    0,
                    bytesToRead,
                    pos.toDouble(),
                    module,
                    copyFn = { i, value ->
                        buffer[offset + i] = value
                    }
                )
                if (readBytes > 0) {
                    pos += readBytes
                }
                result = readBytes
            }
        }
        return result
    }

    override fun seek(position: Long) {
        if (isClosed) return
        pos = maxOf(0L, minOf(position, totalLength))
    }

    override fun position(): Long = if (isClosed) 0L else pos

    override fun length(): Long = if (isClosed) 0L else totalLength

    override fun close() {
        if (!isClosed) {
            isClosed = true
            nodeCloseSync(fd)
        }
    }
}

@Suppress("LongParameterList")
private class WasmArchiveReader(
    private val module: JsAny,
    private val sourceOpaqueId: Int,
    private val sourceCtx: WasmSourceContext,
    private val sourcePtr: Int,
    private val readFuncPtr: Int,
    private val seekFuncPtr: Int,
    private val posFuncPtr: Int,
    private val lenFuncPtr: Int,
    private val handle: JsAny
) : ArchiveReader {

    private var isClosed = false
    private var isOperating = false
    private var cachedWriteFuncPtr: Int = 0
    private var cachedSinkPtr: Int = 0
    private var cachedErrMsgPtr: Int = 0
    private val writeBuffer = ByteArray(WriteChunkSize)

    private inline fun <T> withExclusiveAccess(block: () -> T): T {
        check(!isOperating) { "Re-entrant ArchiveReader operation from callback is not allowed" }
        check(!isClosed) { "Reader is closed" }
        isOperating = true
        try {
            return block()
        } finally {
            isOperating = false
        }
    }

    @Suppress("NestedBlockDepth", "MagicNumber")
    override fun getEntries(): List<ArchiveEntry> = withExclusiveAccess {
        sourceCtx.pendingException = null
        val count = wasmGetEntryCount(module, handle)
        sourceCtx.pendingException?.let {
            sourceCtx.pendingException = null
            throw it
        }
        if (count < 0) return emptyList()

        val list = ArrayList<ArchiveEntry>(count)
        // Allocate kio_entry_t (24 bytes)
        val entryPtr = wasmMalloc(module, EntryStructSize)
        try {
            for (i in 0 until count) {
                if (wasmGetEntry(module, handle, i, entryPtr) != 0) {
                    val index = readHeap32(module, entryPtr, 0)
                    val namePtr = readHeap32(module, entryPtr, 1)

                    val nameStr = if (namePtr != 0) {
                        try {
                            wasmUtf8ToString(module, namePtr)
                        } finally {
                            wasmFree(module, namePtr)
                        }
                    } else {
                        ""
                    }
                    val normalizedName = nameStr.replace('\\', '/')

                    // Size is int64, read using double/HEAP64 helper (offset of size is 8 bytes)
                    val size = readHeap64Double(module, entryPtr, EntrySizeOffset).toLong()

                    val isDir = readHeap32(module, entryPtr, 4) != 0
                    val crc = readHeap32(module, entryPtr, 5)

                    list.add(
                        ArchiveEntry(
                            index = index,
                            name = normalizedName,
                            size = size,
                            compressedSize = size,
                            isDirectory = isDir,
                            crc = crc.toLong() and 0xFFFFFFFFL
                        )
                    )
                }
            }
        } finally {
            wasmFree(module, entryPtr)
        }
        sourceCtx.pendingException?.let {
            sourceCtx.pendingException = null
            throw it
        }
        list
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) = withExclusiveAccess {
        sourceCtx.pendingException = null
        val sinkCtx = WasmSinkContext(sink)
        val sinkOpaqueId = registerOpaque(sinkCtx)

        try {
            // Register Kotlin Sink callback into JS global map
            putWasmSinkCallbacks(module, sinkOpaqueId, sinkCtx, writeBuffer)

            if (cachedWriteFuncPtr == 0) {
                cachedWriteFuncPtr = registerWriteCallback(module)
            }
            if (cachedSinkPtr == 0) {
                cachedSinkPtr = wasmMalloc(module, SinkStructSize)
            }
            if (cachedErrMsgPtr == 0) {
                cachedErrMsgPtr = wasmMalloc(module, ErrMsgBufferSize)
            }

            writeHeap32(module, cachedSinkPtr, 0, cachedWriteFuncPtr)
            writeHeap32(module, cachedSinkPtr, 1, sinkOpaqueId)

            val success = wasmExtractEntry(
                module,
                handle,
                entry.index,
                cachedSinkPtr,
                cachedErrMsgPtr,
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
                val errMsg = wasmUtf8ToString(module, cachedErrMsgPtr)
                throw ArchiveIOException(
                    "Failed to extract entry: ${entry.name}. Native error: $errMsg"
                )
            }
        } finally {
            removeSinkJs(sinkOpaqueId)
            releaseOpaque(sinkOpaqueId)
        }
    }

    override fun close() {
        check(!isOperating) { "Cannot close ArchiveReader from within a callback" }
        if (!isClosed) {
            isClosed = true
            if (cachedSinkPtr != 0) {
                wasmFree(module, cachedSinkPtr)
                cachedSinkPtr = 0
            }
            if (cachedErrMsgPtr != 0) {
                wasmFree(module, cachedErrMsgPtr)
                cachedErrMsgPtr = 0
            }
            if (cachedWriteFuncPtr != 0) {
                wasmRemoveFunction(module, cachedWriteFuncPtr)
                cachedWriteFuncPtr = 0
            }
            wasmCloseArchive(module, handle)
            wasmFree(module, sourcePtr)
            wasmRemoveFunction(module, readFuncPtr)
            wasmRemoveFunction(module, seekFuncPtr)
            wasmRemoveFunction(module, posFuncPtr)
            wasmRemoveFunction(module, lenFuncPtr)
            removeSourceJs(sourceOpaqueId)
            try {
                sourceCtx.source.close()
            } finally {
                releaseOpaque(sourceOpaqueId)
            }
        }
    }
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun putWasmSinkCallbacks(
    module: JsAny,
    sinkOpaqueId: Int,
    sinkCtx: WasmSinkContext,
    writeBuffer: ByteArray
) {
    putSinkJs(
        sinkOpaqueId,
        writeFn = { bufPtr, len ->
            if (sinkCtx.pendingException != null) {
                0
            } else {
                val innerSink = sinkCtx.sink
                try {
                    var offset = 0
                    while (offset < len) {
                        val chunk = minOf(WriteChunkSize, len - offset)
                        copyFromHeap(module, bufPtr + offset, writeBuffer, chunk)
                        innerSink.write(writeBuffer, 0, chunk)
                        innerSink.emit()
                        offset += chunk
                    }
                    1
                } catch (e: Throwable) {
                    if (sinkCtx.pendingException == null) {
                        sinkCtx.pendingException = e
                    }
                    0
                }
            }
        }
    )
}
