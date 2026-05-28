package com.sorrowblue.kioarch

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import kotlinx.io.Sink

internal class SeekableArchiveReader(source: SeekableSource) : ArchiveReader {

    private val handle = KioArchJni.openArchive(source)

    private val lock = Any()

    init {
        require(handle != 0L) {
            "Failed to open archive"
        }
    }

    override fun getEntries(): List<ArchiveEntry> = synchronized(lock) {
        val jniEntries = KioArchJni.getEntries(handle)
        val count = jniEntries.index.size
        val list = ArrayList<ArchiveEntry>(count)
        for (i in 0 until count) {
            list.add(
                ArchiveEntry(
                    index = jniEntries.index[i],
                    name = jniEntries.name[i].replace('\\', '/'),
                    size = jniEntries.size[i],
                    compressedSize = jniEntries.size[i],
                    isDirectory = jniEntries.isDir[i],
                    crc = jniEntries.crc[i]
                )
            )
        }
        list
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun extractEntry(entry: ArchiveEntry, sink: Sink) {
        synchronized(lock) {
            var directSuccess = false
            try {
                val targetChannel = findWritableChannel(sink)
                if (targetChannel != null) {
                    directSuccess = KioArchJni.extractEntryDirect(
                        handle,
                        entry.index,
                        object : DirectExtractCallback {
                            override fun onData(buffer: ByteBuffer) {
                                while (buffer.hasRemaining()) {
                                    targetChannel.write(buffer)
                                }
                            }
                        }
                    )
                } else {
                    val targetStream = findOutputStream(sink)
                    if (targetStream != null) {
                        val channel = Channels.newChannel(targetStream)
                        directSuccess = KioArchJni.extractEntryDirect(
                            handle,
                            entry.index,
                            object : DirectExtractCallback {
                                override fun onData(buffer: ByteBuffer) {
                                    while (buffer.hasRemaining()) {
                                        channel.write(buffer)
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (e: Throwable) {
                directSuccess = false
            }

            if (!directSuccess) {
                check(KioArchJni.extractEntry(handle, entry.index, sink)) {
                    "Failed to extract entry: ${entry.name}"
                }
            }
        }
    }

    private val channelFields = setOf("sink", "delegate", "out", "channel")
    private val streamFields = setOf("sink", "delegate", "out", "outputStream")

    private fun findWritableChannel(sink: Any): WritableByteChannel? {
        var current: Any? = sink
        while (current != null) {
            if (current is WritableByteChannel) return current
            current = findNextField(current, WritableByteChannel::class.java, channelFields)
        }
        return null
    }

    private fun findOutputStream(sink: Any): OutputStream? {
        var current: Any? = sink
        while (current != null) {
            if (current is OutputStream) return current
            current = findNextField(current, OutputStream::class.java, streamFields)
        }
        return null
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun findNextField(obj: Any, targetType: Class<*>, candidateNames: Set<String>): Any? {
        for (f in obj.javaClass.declaredFields) {
            try {
                if (targetType.isAssignableFrom(f.type) || f.name in candidateNames) {
                    f.isAccessible = true
                    val value = f.get(obj)
                    if (value != null && value !== obj) {
                        return value
                    }
                }
            } catch (e: Throwable) {
                // Ignore Reflection errors safely
            }
        }
        return null
    }

    override fun close() {
        synchronized(lock) {
            KioArchJni.closeArchive(handle)
        }
    }
}
