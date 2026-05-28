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
 * A seekable byte channel source that supports direct read operations using [ByteBuffer]
 * to bypass JNI copy overhead on JVM and Android platforms.
 */
public interface DirectSeekableSource : SeekableSource {
    /**
     * Reads a sequence of bytes from this source into the given [byteBuffer].
     *
     * @param byteBuffer the buffer into which bytes are to be transferred.
     * @return The number of bytes read, possibly zero, or -1 if the channel has reached end-of-stream.
     */
    public fun read(byteBuffer: ByteBuffer): Int
}
