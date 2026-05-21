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
    external fun closeArchive(handle: Long): Unit
    external fun getEntryCount(handle: Long): Int
    external fun getEntryInfo(handle: Long, index: Int): JniEntryInfo?
    external fun extractEntry(handle: Long, index: Int, sink: Sink): Boolean
}
