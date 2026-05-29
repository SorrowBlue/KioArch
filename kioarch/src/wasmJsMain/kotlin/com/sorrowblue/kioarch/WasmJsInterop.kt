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

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("TooManyFunctions", "LongParameterList")

package com.sorrowblue.kioarch

// --- JS Interop Helper Functions using @JsFun ---

@JsFun("() => { if (!globalThis.kioarchSources) { globalThis.kioarchSources = new Map(); } }")
private external fun initOpaqueMapInternal()

internal fun initOpaqueMap(): Unit = initOpaqueMapInternal()

@JsFun(
    """(id, readFn, seekFn, posFn, lenFn) => {
        globalThis.kioarchSources.set(id, {
            read: readFn,
            seek: seekFn,
            position: posFn,
            length: lenFn
        });
    }"""
)
private external fun putSourceJsInternal(
    id: Int,
    readFn: (Int, Int) -> Int,
    seekFn: (Double) -> Unit,
    posFn: () -> Double,
    lenFn: () -> Double
)

internal fun putSourceJs(
    id: Int,
    readFn: (Int, Int) -> Int,
    seekFn: (Double) -> Unit,
    posFn: () -> Double,
    lenFn: () -> Double
): Unit = putSourceJsInternal(id, readFn, seekFn, posFn, lenFn)

@JsFun("(id) => { globalThis.kioarchSources.delete(id); }")
private external fun removeSourceJsInternal(id: Int)

internal fun removeSourceJs(id: Int): Unit = removeSourceJsInternal(id)

@JsFun("() => { if (!globalThis.kioarchSinks) { globalThis.kioarchSinks = new Map(); } }")
private external fun initSinkMapInternal()

internal fun initSinkMap(): Unit = initSinkMapInternal()

@JsFun("(id, writeFn) => { globalThis.kioarchSinks.set(id, { write: writeFn }); }")
private external fun putSinkJsInternal(id: Int, writeFn: (Int, Int) -> Unit)

internal fun putSinkJs(id: Int, writeFn: (Int, Int) -> Unit): Unit =
    putSinkJsInternal(id, writeFn)

@JsFun("(id) => { globalThis.kioarchSinks.delete(id); }")
private external fun removeSinkJsInternal(id: Int)

internal fun removeSinkJs(id: Int): Unit = removeSinkJsInternal(id)

// --- Emscripten dynamic callbacks function pointer registration ---

@JsFun(
    """(module) => module.addFunction(function(opaqueId, bufPtr, len) {
        var source = globalThis.kioarchSources.get(opaqueId);
        if (!source) return -1;
        return source.read(bufPtr, len);
    }, 'iiii')"""
)
private external fun registerReadCallbackInternal(module: JsAny): Int

internal fun registerReadCallback(module: JsAny): Int =
    registerReadCallbackInternal(module)

@JsFun(
    """(module) => module.addFunction(function(opaqueId, pos) {
        var source = globalThis.kioarchSources.get(opaqueId);
        if (source) {
            source.seek(Number(pos));
        }
    }, 'vij')"""
)
private external fun registerSeekCallbackInternal(module: JsAny): Int

internal fun registerSeekCallback(module: JsAny): Int =
    registerSeekCallbackInternal(module)

@JsFun(
    """(module) => module.addFunction(function(opaqueId) {
        var source = globalThis.kioarchSources.get(opaqueId);
        return source ? BigInt(source.position()) : 0n;
    }, 'ji')"""
)
private external fun registerPosCallbackInternal(module: JsAny): Int

internal fun registerPosCallback(module: JsAny): Int =
    registerPosCallbackInternal(module)

@JsFun(
    """(module) => module.addFunction(function(opaqueId) {
        var source = globalThis.kioarchSources.get(opaqueId);
        return source ? BigInt(source.length()) : 0n;
    }, 'ji')"""
)
private external fun registerLenCallbackInternal(module: JsAny): Int

internal fun registerLenCallback(module: JsAny): Int =
    registerLenCallbackInternal(module)

@JsFun(
    """(module) => module.addFunction(function(opaqueId, bufPtr, len) {
        var sink = globalThis.kioarchSinks.get(opaqueId);
        if (sink) {
            sink.write(bufPtr, len);
        }
    }, 'viii')"""
)
private external fun registerWriteCallbackInternal(module: JsAny): Int

internal fun registerWriteCallback(module: JsAny): Int =
    registerWriteCallbackInternal(module)

// --- Emscripten directly bound C functions ---

@JsFun("(module, size) => module._malloc(size)")
private external fun wasmMallocInternal(module: JsAny, size: Int): Int

internal fun wasmMalloc(module: JsAny, size: Int): Int =
    wasmMallocInternal(module, size)

@JsFun("(module, ptr) => module._free(ptr)")
private external fun wasmFreeInternal(module: JsAny, ptr: Int)

internal fun wasmFree(module: JsAny, ptr: Int): Unit =
    wasmFreeInternal(module, ptr)

@JsFun("(module, ptr, offset, value) => { module.HEAP32[(ptr / 4) + offset] = value; }")
private external fun writeHeap32Internal(module: JsAny, ptr: Int, offset: Int, value: Int)

internal fun writeHeap32(module: JsAny, ptr: Int, offset: Int, value: Int): Unit =
    writeHeap32Internal(module, ptr, offset, value)

@JsFun("(module, ptr, offset) => module.HEAP32[(ptr / 4) + offset]")
private external fun readHeap32Internal(module: JsAny, ptr: Int, offset: Int): Int

internal fun readHeap32(module: JsAny, ptr: Int, offset: Int): Int =
    readHeap32Internal(module, ptr, offset)

@JsFun("(module, ptr, offset) => Number(module.HEAP64[((ptr + (offset * 8)) / 8)])")
private external fun readHeap64DoubleInternal(module: JsAny, ptr: Int, offset: Int): Double

internal fun readHeap64Double(module: JsAny, ptr: Int, offset: Int): Double =
    readHeap64DoubleInternal(module, ptr, offset)

@JsFun(
    """(module, namePtr) => {
        var len = 0;
        while (module.HEAPU8[namePtr + len] !== 0) {
            len++;
        }
        if (len === 0) return "";
        var bytes = new Uint8Array(module.HEAPU8.buffer, namePtr, len);
        try {
            var utf8Decoder = new TextDecoder('utf-8', { fatal: true });
            return utf8Decoder.decode(bytes);
        } catch (e) {
            try {
                var sjisDecoder = new TextDecoder('shift-jis');
                return sjisDecoder.decode(bytes);
            } catch (err) {
                return module.UTF8ToString(namePtr);
            }
        }
    }"""
)
private external fun wasmUtf8ToStringInternal(module: JsAny, namePtr: Int): String

internal fun wasmUtf8ToString(module: JsAny, namePtr: Int): String =
    wasmUtf8ToStringInternal(module, namePtr)

@JsFun(
    "(module, sourcePtr, errMsgPtr, errMsgLen) => module._kio_open_archive_wasm(sourcePtr, errMsgPtr, errMsgLen)"
)
private external fun wasmOpenArchiveInternal(
    module: JsAny,
    sourcePtr: Int,
    errMsgPtr: Int,
    errMsgLen: Int
): JsAny

internal fun wasmOpenArchive(
    module: JsAny,
    sourcePtr: Int,
    errMsgPtr: Int,
    errMsgLen: Int
): JsAny = wasmOpenArchiveInternal(module, sourcePtr, errMsgPtr, errMsgLen)

@JsFun("(module, handle) => module._kio_get_entry_count(handle)")
private external fun wasmGetEntryCountInternal(module: JsAny, handle: JsAny): Int

internal fun wasmGetEntryCount(module: JsAny, handle: JsAny): Int =
    wasmGetEntryCountInternal(module, handle)

@JsFun("(module, handle, index, entryPtr) => module._kio_get_entry(handle, index, entryPtr)")
private external fun wasmGetEntryInternal(module: JsAny, handle: JsAny, index: Int, entryPtr: Int): Int

internal fun wasmGetEntry(module: JsAny, handle: JsAny, index: Int, entryPtr: Int): Int =
    wasmGetEntryInternal(module, handle, index, entryPtr)

@JsFun(
    "(module, handle, index, sinkPtr, errMsgPtr, errMsgLen) => module._kio_extract_entry_wasm(handle, index, sinkPtr, errMsgPtr, errMsgLen)"
)
private external fun wasmExtractEntryInternal(
    module: JsAny,
    handle: JsAny,
    index: Int,
    sinkPtr: Int,
    errMsgPtr: Int,
    errMsgLen: Int
): Int

internal fun wasmExtractEntry(
    module: JsAny,
    handle: JsAny,
    index: Int,
    sinkPtr: Int,
    errMsgPtr: Int,
    errMsgLen: Int
): Int = wasmExtractEntryInternal(module, handle, index, sinkPtr, errMsgPtr, errMsgLen)

@JsFun("(module, handle) => module._kio_close_archive(handle)")
private external fun wasmCloseArchiveInternal(module: JsAny, handle: JsAny)

internal fun wasmCloseArchive(module: JsAny, handle: JsAny): Unit =
    wasmCloseArchiveInternal(module, handle)

@JsFun("(module, ptr) => module.removeFunction(ptr)")
private external fun wasmRemoveFunctionInternal(module: JsAny, ptr: Int)

internal fun wasmRemoveFunction(module: JsAny, ptr: Int): Unit =
    wasmRemoveFunctionInternal(module, ptr)

// --- Dynamic Memory Copy Helpers ---

@JsFun(
    """(module, bufPtr, getByteFn, len) => {
        var heap = new Uint8Array(module.HEAPU8.buffer, bufPtr, len);
        for (var i = 0; i < len; i++) {
            heap[i] = getByteFn(i);
        }
    }"""
)
private external fun copyToHeapJsInternal(
    module: JsAny,
    bufPtr: Int,
    getByteFn: (Int) -> Byte,
    len: Int
)

internal fun copyToHeapJs(
    module: JsAny,
    bufPtr: Int,
    getByteFn: (Int) -> Byte,
    len: Int
): Unit = copyToHeapJsInternal(module, bufPtr, getByteFn, len)

@JsFun(
    """(module, bufPtr, setByteFn, len) => {
        var heap = new Uint8Array(module.HEAPU8.buffer, bufPtr, len);
        for (var i = 0; i < len; i++) {
            setByteFn(i, heap[i]);
        }
    }"""
)
private external fun copyFromHeapJsInternal(
    module: JsAny,
    bufPtr: Int,
    setByteFn: (Int, Byte) -> Unit,
    len: Int
)

internal fun copyFromHeapJs(
    module: JsAny,
    bufPtr: Int,
    setByteFn: (Int, Byte) -> Unit,
    len: Int
): Unit = copyFromHeapJsInternal(module, bufPtr, setByteFn, len)

@JsFun("(rawHandle) => BigInt(rawHandle)")
private external fun toBigIntInternal(rawHandle: JsAny): JsAny

internal fun toBigInt(rawHandle: JsAny): JsAny = toBigIntInternal(rawHandle)

@JsFun("(bigIntVal) => bigIntVal === 0n")
private external fun isZeroBigIntInternal(bigIntVal: JsAny): Boolean

internal fun isZeroBigInt(bigIntVal: JsAny): Boolean =
    isZeroBigIntInternal(bigIntVal)

@JsFun(
    "() => typeof process !== 'undefined' && process.versions != null && process.versions.node != null"
)
private external fun isNodeJsWasmInternal(): Boolean

internal fun isNodeJsWasm(): Boolean = isNodeJsWasmInternal()

@JsFun(
    "() => { if (!globalThis.kioarchFs) { globalThis.kioarchFs = typeof require !== 'undefined' ? eval('require')('fs') : null; } }"
)
private external fun initNodeFsInternal()

internal fun initNodeFs(): Unit = initNodeFsInternal()

@JsFun(
    "(pathStr) => { if (!globalThis.kioarchFs) return -1; return globalThis.kioarchFs.openSync(pathStr, 'r'); }"
)
private external fun nodeOpenSyncInternal(pathStr: String): Int

internal fun nodeOpenSync(pathStr: String): Int = nodeOpenSyncInternal(pathStr)

@JsFun(
    "(fd) => { if (!globalThis.kioarchFs) return 0.0; return Number(globalThis.kioarchFs.fstatSync(fd).size); }"
)
private external fun nodeSizeSyncInternal(fd: Int): Double

internal fun nodeSizeSync(fd: Int): Double = nodeSizeSyncInternal(fd)

@JsFun(
    """(fd, bufPtr, bytesToRead, pos, module, copyFn) => {
        if (!globalThis.kioarchFs) return -1;
        var jsBuffer = typeof Buffer !== 'undefined' && typeof Buffer.alloc === 'function' ? Buffer.alloc(bytesToRead) : new Uint8Array(bytesToRead);
        var readBytes = globalThis.kioarchFs.readSync(fd, jsBuffer, 0, bytesToRead, pos);
        if (readBytes <= 0) return readBytes;
        for (var i = 0; i < readBytes; i++) {
            copyFn(i, jsBuffer[i]);
        }
        return readBytes;
    }"""
)
private external fun nodeReadSyncInternal(
    fd: Int,
    bufPtr: Int,
    bytesToRead: Int,
    pos: Double,
    module: JsAny,
    copyFn: (Int, Byte) -> Unit
): Int

internal fun nodeReadSync(
    fd: Int,
    bufPtr: Int,
    bytesToRead: Int,
    pos: Double,
    module: JsAny,
    copyFn: (Int, Byte) -> Unit
): Int = nodeReadSyncInternal(fd, bufPtr, bytesToRead, pos, module, copyFn)

@JsFun("(fd) => { if (globalThis.kioarchFs) globalThis.kioarchFs.closeSync(fd); }")
private external fun nodeCloseSyncInternal(fd: Int)

internal fun nodeCloseSync(fd: Int): Unit = nodeCloseSyncInternal(fd)
