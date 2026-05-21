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

internal class JniEntryInfo(
    val index: Int,
    val name: String,
    val size: Long,
    val isDir: Boolean,
    val crc: Long
)

internal object KioArchJni {
    init {
        KioArch.loadLibraryLazily()
    }

    external fun openArchive(source: SeekableSource): Long
    external fun closeArchive(handle: Long)
    external fun getEntryCount(handle: Long): Int
    external fun getEntryInfo(handle: Long, index: Int): JniEntryInfo?
    external fun extractEntry(handle: Long, index: Int, sink: Sink): Boolean
}
