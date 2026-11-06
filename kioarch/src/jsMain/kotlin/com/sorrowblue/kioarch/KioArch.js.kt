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
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

private const val SourceStructSize = 20
private const val EntryStructSize = 24
private const val SinkStructSize = 8
private const val ErrMsgBufferSize = 512
private const val EntrySizeOffset = 8
private const val InitialReadBufferSize = 256 * 1024
private const val WriteChunkSize = 64 * 1024

// Global map to track active Kotlin objects passed to C callbacks
private val opaqueMap = mutableMapOf<Int, Any>()
private val readBufferMap = mutableMapOf<Int, ByteArray>()
private var nextOpaqueId = 1

@JsName("registerOpaque")
internal fun registerOpaque(obj: Any): Int {
    val id = nextOpaqueId++
    opaqueMap[id] = obj
    return id
}

@JsName("getOpaque")
internal fun getOpaque(id: Int): Any? = opaqueMap[id]

@JsName("releaseOpaque")
internal fun releaseOpaque(id: Int) {
    opaqueMap.remove(id)
    readBufferMap.remove(id)
}

internal fun getJsActiveNativeAllocations(): Long {
    val wasm: dynamic = wasmModule ?: return 0L
    val fn = wasm._kio_get_active_allocations_wasm
    return if (fn != undefined) (wasm._kio_get_active_allocations_wasm() as Int).toLong() else 0L
}

internal fun getJsActiveOpaqueReferences(): Long = opaqueMap.size.toLong()

@Suppress("LongMethod")
private fun wasmUtf8ToString(wasm: dynamic, namePtr: Int): String {
    val decoder = js(
        """
        function(wasm, namePtr) {
            var len = 0;
            while (wasm.HEAPU8[namePtr + len] !== 0) {
                len++;
            }
            if (len === 0) return "";
            var bytes = new Uint8Array(wasm.HEAPU8.buffer, namePtr, len);
            
            var isUtf8 = true;
            var i = 0;
            while (i < bytes.length) {
                var b1 = bytes[i];
                if (b1 <= 0x7F) {
                    i++;
                } else if (b1 >= 0xC2 && b1 <= 0xDF) {
                    if (i + 1 >= bytes.length || bytes[i+1] < 0x80 || bytes[i+1] > 0xBF) { isUtf8 = false; break; }
                    i += 2;
                } else if (b1 >= 0xE0 && b1 <= 0xEF) {
                    if (
                        i + 2 >= bytes.length ||
                        bytes[i+1] < 0x80 || bytes[i+1] > 0xBF ||
                        bytes[i+2] < 0x80 || bytes[i+2] > 0xBF
                    ) { isUtf8 = false; break; }
                    if (b1 === 0xE0 && bytes[i+1] < 0xA0) { isUtf8 = false; break; }
                    if (b1 === 0xED && bytes[i+1] > 0x9F) { isUtf8 = false; break; }
                    i += 3;
                } else if (b1 >= 0xF0 && b1 <= 0xF4) {
                    if (
                        i + 3 >= bytes.length ||
                        bytes[i+1] < 0x80 || bytes[i+1] > 0xBF ||
                        bytes[i+2] < 0x80 || bytes[i+2] > 0xBF ||
                        bytes[i+3] < 0x80 || bytes[i+3] > 0xBF
                    ) { isUtf8 = false; break; }
                    if (b1 === 0xF0 && bytes[i+1] < 0x90) { isUtf8 = false; break; }
                    if (b1 === 0xF4 && bytes[i+1] > 0x8F) { isUtf8 = false; break; }
                    i += 4;
                } else {
                    isUtf8 = false;
                    break;
                }
            }
            
            if (isUtf8) {
                try {
                    var utf8Decoder = new TextDecoder('utf-8', { fatal: true });
                    return utf8Decoder.decode(bytes);
                } catch (e) {}
            }
            
            var sjisLabels = ['shift_jis', 'shift-jis', 'windows-31j', 'x-sjis'];
            for (var j = 0; j < sjisLabels.length; j++) {
                try {
                    var sjisDecoder = new TextDecoder(sjisLabels[j]);
                    return sjisDecoder.decode(bytes);
                } catch (err) {}
            }
            
            return wasm.UTF8ToString(namePtr);
        }
    """
    ) as (dynamic, Int) -> String
    return decoder(wasm, namePtr)
}

private class JsSourceContext(val source: SeekableSource, var pendingException: Throwable? = null)

private class JsSinkContext(
    val sink: Sink,
    val writeBuffer: ByteArray,
    val writeJsArray: Int8Array,
    var pendingException: Throwable? = null
)

@Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount")
private fun bridgeRead(wasm: dynamic, opaqueId: Int, bufPtr: Int, len: Int): Int {
    val sourceCtx = getOpaque(opaqueId) as? JsSourceContext ?: return -1
    if (sourceCtx.pendingException != null) return -1
    val innerSource = sourceCtx.source
    return try {
        if (innerSource is ByteArraySeekableSource) {
            val srcBytes = innerSource.bytes
            val currentPos = innerSource.position().toInt()
            if (currentPos >= srcBytes.size) return -1
            val toRead = minOf(len, srcBytes.size - currentPos)
            if (toRead > 0) {
                val jsArray = srcBytes.asDynamic() as Int8Array
                val view = Uint8Array(
                    jsArray.buffer,
                    jsArray.byteOffset + currentPos,
                    toRead
                )
                wasm.HEAPU8.set(view, bufPtr)
                innerSource.seek((currentPos + toRead).toLong())
            }
            return toRead
        }

        var readBuffer = readBufferMap[opaqueId]
        if (readBuffer == null || readBuffer.size < len) {
            readBuffer = ByteArray(maxOf(len, InitialReadBufferSize))
            readBufferMap[opaqueId] = readBuffer
        }
        val bytesRead = innerSource.read(readBuffer, 0, len)
        if (bytesRead > 0) {
            val jsArray = readBuffer.asDynamic() as Int8Array
            val view = Uint8Array(
                jsArray.buffer,
                jsArray.byteOffset,
                bytesRead
            )
            wasm.HEAPU8.set(view, bufPtr)
        }
        bytesRead
    } catch (e: Throwable) {
        if (sourceCtx.pendingException == null) {
            sourceCtx.pendingException = e
        }
        -1
    }
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgeSeek(sourceObj: Any, pos: Double) {
    val sourceCtx = sourceObj as? JsSourceContext ?: return
    if (sourceCtx.pendingException != null) return
    try {
        sourceCtx.source.seek(pos.toLong())
    } catch (e: Throwable) {
        if (sourceCtx.pendingException == null) {
            sourceCtx.pendingException = e
        }
    }
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgePosition(sourceObj: Any): Double {
    val sourceCtx = sourceObj as? JsSourceContext
    return if (sourceCtx == null || sourceCtx.pendingException != null) {
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
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgeLength(sourceObj: Any): Double {
    val sourceCtx = sourceObj as? JsSourceContext
    return if (sourceCtx == null || sourceCtx.pendingException != null) {
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

@Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount")
private fun bridgeWrite(wasm: dynamic, sinkContextObj: Any, bufPtr: Int, len: Int): Int {
    val sinkCtx = sinkContextObj as? JsSinkContext ?: return 0
    if (sinkCtx.pendingException != null) return 0
    return try {
        val heapBuffer = wasm.HEAPU8.buffer as ArrayBuffer
        var offset = 0
        while (offset < len) {
            val chunk = minOf(WriteChunkSize, len - offset)
            val view = Int8Array(
                heapBuffer,
                bufPtr + offset,
                chunk
            )
            sinkCtx.writeJsArray.set(view)
            sinkCtx.sink.write(sinkCtx.writeBuffer, 0, chunk)
            sinkCtx.sink.emit()
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

/**
 * JS implementation of [KioArch] utilizing WebAssembly compiled from pure C archive engines.
 */
public actual object KioArch {

    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource] for JS.
     *
     * @param source the seekable source containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("MagicNumber", "TooGenericExceptionCaught", "LongMethod")
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        val wasm: dynamic = wasmModule ?: throwNotInitialized()

        val sourceCtx = JsSourceContext(source)
        val sourceOpaqueId = registerOpaque(sourceCtx)
        var sourcePtr = 0
        var ptrs: SourceCallbackPtrs? = null
        var handle: Long = 0L

        val reader = try {
            // Register callbacks to Emscripten function table
            ptrs = registerJsSourceCallbacks(wasm)

            // Allocate and populate kio_source_t structure (20 bytes)
            sourcePtr = wasm._malloc(SourceStructSize) as Int
            val heap32 = wasm.HEAP32
            val sourceWordOffset = sourcePtr / 4
            heap32[sourceWordOffset + 0] = ptrs.readPtr
            heap32[sourceWordOffset + 1] = ptrs.seekPtr
            heap32[sourceWordOffset + 2] = ptrs.posPtr
            heap32[sourceWordOffset + 3] = ptrs.lenPtr
            heap32[sourceWordOffset + 4] = sourceOpaqueId

            // Allocate error message buffer (512 bytes)
            val errMsgPtr = wasm._malloc(ErrMsgBufferSize) as Int
            try {
                val rawHandle = wasm._kio_open_archive(sourcePtr, errMsgPtr, ErrMsgBufferSize)

                // Emscripten handles 64-bit unsigned int returns as BigInt or low/high parts.
                // In modern Emscripten with BigInt support, it is returned as a BigInt.
                val handleVal = (
                    js(
                        "function(raw) { return globalThis['BigInt'](raw); }"
                    ) as (dynamic) -> dynamic
                    )(rawHandle)
                val isZero = (
                    js(
                        "function(raw) { " +
                            "return globalThis['BigInt'](raw) === globalThis['BigInt'](0); " +
                            "}"
                    ) as (dynamic) -> Boolean
                    )(rawHandle)
                if (!isZero) {
                    handle = handleVal.toString().toLong()
                }
                sourceCtx.pendingException?.let {
                    sourceCtx.pendingException = null
                    throw it
                }
                if (isZero) {
                    // Extract error message
                    val errMsg = wasmUtf8ToString(wasm, errMsgPtr)
                    throw ArchiveIOException("Failed to open archive. Native error: $errMsg")
                }
            } finally {
                wasm._free(errMsgPtr)
            }

            JsArchiveReader(
                wasm,
                sourceOpaqueId,
                sourceCtx,
                sourcePtr,
                ptrs.readPtr,
                ptrs.seekPtr,
                ptrs.posPtr,
                ptrs.lenPtr,
                handle
            )
        } catch (t: Throwable) {
            if (handle != 0L) {
                val rawH = (
                    js(
                        "function(h) { return globalThis['BigInt'](h.toString()); }"
                    ) as (dynamic) -> dynamic
                    )(handle)
                wasm._kio_close_archive(rawH)
            }
            cleanupSourcePtrs(wasm, sourcePtr, ptrs, sourceOpaqueId)
            throw t
        }
        return wrapWithBzip2IfNeeded(source, reader)
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray] for JS.
     *
     * @param byteArray the byte array containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader =
        createReader(ByteArraySeekableSource(byteArray))

    /**
     * Creates an [ArchiveReader] from a Kotlin Multiplatform [Path] for JS.
     *
     * @param path the path to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("TooGenericExceptionCaught")
    public actual fun createReader(path: Path): ArchiveReader {
        if (isNodeJs()) {
            val source = NodeFileSeekableSource(path.toString())
            return try {
                createReader(source)
            } catch (t: Throwable) {
                source.close()
                throw t
            }
        }
        throw UnsupportedOperationException(
            "File system access using Path is not supported in browser JS environment. " +
                "Use ByteArray or custom SeekableSource."
        )
    }
}

private class SourceCallbackPtrs(
    val readPtr: Int,
    val seekPtr: Int,
    val posPtr: Int,
    val lenPtr: Int
)

private fun throwNotInitialized(): Nothing = throw IllegalStateException(
    "KioArch has not been initialized. Call KioArch.initialize(module) first."
)

private fun registerJsSourceCallbacks(wasm: dynamic): SourceCallbackPtrs {
    val readFuncCreator = js(
        """
        function(wasm, bridgeRead) {
            return wasm.addFunction(function(opaqueId, bufPtr, len) {
                return bridgeRead(wasm, opaqueId, bufPtr, len);
            }, 'iiii');
        }
    """
    ) as (dynamic, (dynamic, Int, Int, Int) -> Int) -> Int
    val readPtr = readFuncCreator(wasm, ::bridgeRead)

    val seekFuncCreator = js(
        """
        function(wasm, getOpaque, bridgeSeek) {
            return wasm.addFunction(function(opaqueId, pos) {
                var innerSource = getOpaque(opaqueId);
                if (innerSource) {
                    bridgeSeek(innerSource, Number(pos));
                }
            }, 'vij');
        }
    """
    ) as (dynamic, (Int) -> Any?, (Any, Double) -> Unit) -> Int
    val seekPtr = seekFuncCreator(wasm, ::getOpaque, ::bridgeSeek)

    val posFuncCreator = js(
        """
        function(wasm, getOpaque, bridgePosition) {
            return wasm.addFunction(function(opaqueId) {
                var innerSource = getOpaque(opaqueId);
                var bigInt = globalThis["BigInt"];
                return innerSource ? bigInt(bridgePosition(innerSource)) : bigInt(0);
            }, 'ji');
        }
    """
    ) as (dynamic, (Int) -> Any?, (Any) -> Double) -> Int
    val posPtr = posFuncCreator(wasm, ::getOpaque, ::bridgePosition)

    val lenFuncCreator = js(
        """
        function(wasm, getOpaque, bridgeLength) {
            return wasm.addFunction(function(opaqueId) {
                var innerSource = getOpaque(opaqueId);
                var bigInt = globalThis["BigInt"];
                return innerSource ? bigInt(bridgeLength(innerSource)) : bigInt(0);
            }, 'ji');
        }
    """
    ) as (dynamic, (Int) -> Any?, (Any) -> Double) -> Int
    val lenPtr = lenFuncCreator(wasm, ::getOpaque, ::bridgeLength)

    return SourceCallbackPtrs(readPtr, seekPtr, posPtr, lenPtr)
}

private fun cleanupSourcePtrs(
    wasm: dynamic,
    sourcePtr: Int,
    ptrs: SourceCallbackPtrs?,
    opaqueId: Int
) {
    if (sourcePtr != 0) wasm._free(sourcePtr)
    if (ptrs != null) {
        if (ptrs.readPtr != 0) wasm.removeFunction(ptrs.readPtr)
        if (ptrs.seekPtr != 0) wasm.removeFunction(ptrs.seekPtr)
        if (ptrs.posPtr != 0) wasm.removeFunction(ptrs.posPtr)
        if (ptrs.lenPtr != 0) wasm.removeFunction(ptrs.lenPtr)
    }
    releaseOpaque(opaqueId)
}

private fun isNodeJs(): Boolean = js(
    "typeof process !== 'undefined' && process.versions != null && process.versions.node != null"
) as Boolean

public class NodeFileSeekableSource(private val pathStr: String) : SeekableSource {
    private val fs: dynamic = js("eval('require')('fs')")
    private val fd: Int
    private var pos: Long = 0L
    private val totalLength: Long
    private var isClosed = false

    init {
        fd = fs.openSync(pathStr, "r") as Int
        val stats = fs.fstatSync(fd)
        totalLength = (stats.size as Double).toLong()
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
                val isBufferAllocSupported = js(
                    "typeof Buffer.alloc === 'function'"
                ) as Boolean
                val jsBuffer = if (isBufferAllocSupported) {
                    js("Buffer.alloc(bytesToRead)")
                } else {
                    js("new Buffer(bytesToRead)")
                }
                val readBytes = fs.readSync(
                    fd,
                    jsBuffer,
                    0,
                    bytesToRead,
                    pos.toDouble()
                ) as Int

                if (readBytes <= 0) {
                    result = -1
                } else {
                    val kotlinArray = buffer.asDynamic() as Int8Array
                    val view = Int8Array(
                        jsBuffer.buffer as ArrayBuffer,
                        jsBuffer.byteOffset as Int,
                        readBytes
                    )
                    kotlinArray.set(view, offset)
                    pos += readBytes
                    result = readBytes
                }
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
            fs.closeSync(fd)
        }
    }
}

@Suppress("LongParameterList")
private class JsArchiveReader(
    private val wasm: dynamic,
    private val sourceOpaqueId: Int,
    private val sourceCtx: JsSourceContext,
    private val sourcePtr: Int,
    private val readFuncPtr: Int,
    private val seekFuncPtr: Int,
    private val posFuncPtr: Int,
    private val lenFuncPtr: Int,
    private val handle: Long
) : ArchiveReader {

    private var isClosed = false
    private var isOperating = false
    private val rawHandle: dynamic = (
        js(
            "function(h) { return globalThis['BigInt'](h.toString()); }"
        ) as (dynamic) -> dynamic
        )(handle)
    private var cachedWriteFuncPtr: Int = 0
    private var cachedSinkPtr: Int = 0
    private var cachedErrMsgPtr: Int = 0
    private val writeBuffer = ByteArray(WriteChunkSize)
    private val writeJsArray = writeBuffer.asDynamic() as Int8Array

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
        val count = wasm._kio_get_entry_count(rawHandle) as Int
        sourceCtx.pendingException?.let {
            sourceCtx.pendingException = null
            throw it
        }
        if (count < 0) return emptyList()

        val list = ArrayList<ArchiveEntry>(count)
        // Allocate kio_entry_t (24 bytes)
        val entryPtr = wasm._malloc(EntryStructSize) as Int
        try {
            for (i in 0 until count) {
                if (wasm._kio_get_entry(rawHandle, i, entryPtr) as Int != 0) {
                    val heap32 = wasm.HEAP32
                    val wordOffset = entryPtr / 4
                    val index = heap32[wordOffset + 0] as Int
                    val namePtr = heap32[wordOffset + 1] as Int

                    // Read string from memory using Emscripten helper and free native C buffer
                    val nameStr = if (namePtr != 0) {
                        try {
                            wasmUtf8ToString(wasm, namePtr)
                        } finally {
                            wasm._free(namePtr)
                        }
                    } else {
                        ""
                    }
                    val normalizedName = nameStr.replace('\\', '/')

                    // Size is int64, read from HEAP64/HEAPU32
                    // Emscripten handles 64-bit alignment, so offset of size is 8 bytes (word offset 2 and 3)
                    val sizeVal = (
                        js(
                            "function(heap, idx) { return Number(heap[idx]); }"
                        ) as (dynamic, Int) -> Double
                        )(wasm.HEAP64, (entryPtr + EntrySizeOffset) / EntrySizeOffset)
                    val size = sizeVal.toLong()

                    val isDir = heap32[wordOffset + 4] as Int != 0
                    val crc = heap32[wordOffset + 5] as Int

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
            wasm._free(entryPtr)
        }
        sourceCtx.pendingException?.let {
            sourceCtx.pendingException = null
            throw it
        }
        list
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) = withExclusiveAccess {
        sourceCtx.pendingException = null
        val sinkCtx = JsSinkContext(sink, writeBuffer, writeJsArray)
        val sinkOpaqueId = registerOpaque(sinkCtx)
        try {
            if (cachedWriteFuncPtr == 0) {
                val writeFuncCreator = js(
                    """
                    function(wasm, getOpaque, bridgeWrite) {
                        return wasm.addFunction(function(opaqueId, bufPtr, len) {
                            var innerSink = getOpaque(opaqueId);
                            if (innerSink) {
                                return bridgeWrite(wasm, innerSink, bufPtr, len);
                            }
                            return 0;
                        }, 'iiii');
                    }
                """
                ) as (dynamic, (Int) -> Any?, (dynamic, Any, Int, Int) -> Int) -> Int
                cachedWriteFuncPtr = writeFuncCreator(wasm, ::getOpaque, ::bridgeWrite)
            }
            if (cachedSinkPtr == 0) {
                cachedSinkPtr = wasm._malloc(SinkStructSize) as Int
            }
            if (cachedErrMsgPtr == 0) {
                cachedErrMsgPtr = wasm._malloc(ErrMsgBufferSize) as Int
            }

            val heap32 = wasm.HEAP32
            val wordOffset = cachedSinkPtr / 4
            heap32[wordOffset + 0] = cachedWriteFuncPtr
            heap32[wordOffset + 1] = sinkOpaqueId

            val success = wasm._kio_extract_entry(
                rawHandle,
                entry.index,
                cachedSinkPtr,
                cachedErrMsgPtr,
                ErrMsgBufferSize
            ) as Int

            val sourceEx = sourceCtx.pendingException
            sourceCtx.pendingException = null
            val sinkEx = sinkCtx.pendingException
            sinkCtx.pendingException = null
            val callbackEx = sinkEx ?: sourceEx
            if (callbackEx != null) {
                throw callbackEx
            }

            if (success == 0) {
                val errMsg = wasmUtf8ToString(wasm, cachedErrMsgPtr)
                throw ArchiveIOException(
                    "Failed to extract entry: ${entry.name}. Native error: $errMsg"
                )
            }
        } finally {
            releaseOpaque(sinkOpaqueId)
        }
    }

    override fun close() {
        check(!isOperating) { "Cannot close ArchiveReader from within a callback" }
        if (!isClosed) {
            isClosed = true
            if (cachedSinkPtr != 0) {
                wasm._free(cachedSinkPtr)
                cachedSinkPtr = 0
            }
            if (cachedErrMsgPtr != 0) {
                wasm._free(cachedErrMsgPtr)
                cachedErrMsgPtr = 0
            }
            if (cachedWriteFuncPtr != 0) {
                wasm.removeFunction(cachedWriteFuncPtr)
                cachedWriteFuncPtr = 0
            }
            wasm._kio_close_archive(rawHandle)
            wasm._free(sourcePtr)
            wasm.removeFunction(readFuncPtr)
            wasm.removeFunction(seekFuncPtr)
            wasm.removeFunction(posFuncPtr)
            wasm.removeFunction(lenFuncPtr)
            try {
                sourceCtx.source.close()
            } finally {
                releaseOpaque(sourceOpaqueId)
            }
        }
    }
}
