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
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A JVM/Android-specific [SeekableSource] implementation wrapping a [FileChannel] for NIO-based random access.
 *
 * @param path the Java NIO path to wrap
 */
public class PathSeekableSource(path: Path) : SeekableSource {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)

    public override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        val readBytes = channel.read(byteBuffer)
        return if (readBytes == 0 && channel.position() >= channel.size()) -1 else readBytes
    }

    public override fun seek(position: Long) {
        channel.position(position)
    }

    public override fun position(): Long = channel.position()

    public override fun length(): Long = channel.size()

    public override fun close() {
        channel.close()
    }
}
