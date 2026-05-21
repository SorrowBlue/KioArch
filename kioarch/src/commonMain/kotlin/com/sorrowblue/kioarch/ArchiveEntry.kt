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

import kotlinx.io.Sink

public data class ArchiveEntry(
    val index: Int,
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val crc: Long
)

/**
 * Extracts the content of this entry using the specified [reader] and writes it into the [sink].
 *
 * @param reader the [ArchiveReader] that contains this entry
 * @param sink the [Sink] to write the extracted content to
 */
public fun ArchiveEntry.extract(reader: ArchiveReader, sink: Sink) {
    reader.extractEntry(this, sink)
}
