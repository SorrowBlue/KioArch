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

internal class JniEntries(
    val index: IntArray,
    val name: Array<String>,
    val size: LongArray,
    val isDir: BooleanArray,
    val crc: LongArray
)

internal object KioArchJni {
    external fun openArchive(source: SeekableSource): Long
    external fun closeArchive(handle: Long): Unit
    external fun getEntries(handle: Long): JniEntries
    external fun extractEntry(handle: Long, index: Int, sink: Sink): Boolean
}
