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

package com.sorrowblue.kioarch

import java.nio.ByteBuffer

/**
 * A JVM and Android extension of [SeekableSource] that transfers bytes directly into a NIO
 * [ByteBuffer], bypassing intermediate JVM heap `ByteArray` copies across the JNI boundary.
 *
 * When a `DirectSeekableSource` is passed to [KioArch.createReader], the native JNI layer
 * allocates a direct [ByteBuffer] via `NewDirectByteBuffer` and invokes [read] directly into
 * native memory.
 *
 * @see FileSeekableSource
 * @see PathSeekableSource
 */
public interface DirectSeekableSource : SeekableSource {
    /**
     * Reads a sequence of bytes from this source at the current position into [byteBuffer].
     *
     * Up to `byteBuffer.remaining()` bytes are transferred, and both this source's position and
     * `byteBuffer.position()` are advanced by the number of bytes read.
     *
     * @param byteBuffer the destination buffer into which bytes are transferred.
     * @return the number of bytes read, or `-1` if the source has reached end-of-stream.
     */
    public fun read(byteBuffer: ByteBuffer): Int
}
