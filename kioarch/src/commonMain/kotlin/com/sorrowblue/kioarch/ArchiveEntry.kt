package com.sorrowblue.kioarch

data class ArchiveEntry(
    val index: Int,
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val crc: Long
)
