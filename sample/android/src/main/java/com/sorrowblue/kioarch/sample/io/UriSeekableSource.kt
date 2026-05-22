package com.sorrowblue.kioarch.sample.io

import android.content.Context
import android.net.Uri
import com.sorrowblue.kioarch.SeekableSource
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A [SeekableSource] implementation that reads data from an Android content [Uri].
 * This source relies on Android's [android.content.ContentResolver] to open a file descriptor
 * and supports seek operations using [java.nio.channels.FileChannel].
 *
 * @property context The Android context used to resolve the URI.
 * @property uri The content URI pointing to the target archive file.
 */
class UriSeekableSource(context: Context, uri: Uri) : SeekableSource {

    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Failed to open file descriptor for URI: $uri")

    private val fis = FileInputStream(pfd.fileDescriptor)
    private val channel = fis.channel

    /**
     * Reads a sequence of bytes from this source into the given buffer.
     *
     * @param buffer The byte array into which data is read.
     * @param offset The start offset in [buffer] at which the data is written.
     * @param length The maximum number of bytes to read.
     * @return The number of bytes read, or -1 if the end of the stream has been reached.
     * @throws IOException If an I/O error occurs.
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        channel.read(byteBuffer)
    } catch (e: IOException) {
        throw IOException("Error reading from UriSource", e)
    }

    /**
     * Sets the source position for the next read operation.
     *
     * @param position The absolute position from the start of the source.
     * @throws IOException If an I/O error occurs.
     */
    override fun seek(position: Long) {
        try {
            channel.position(position)
        } catch (e: IOException) {
            throw IOException("Error seeking to position $position", e)
        }
    }

    /**
     * Returns the current source position.
     *
     * @return The current byte offset from the beginning of the source.
     */
    override fun position(): Long = channel.position()

    /**
     * Returns the total length of the source in bytes.
     *
     * @return The size of the file represented by the URI.
     */
    override fun length(): Long = pfd.statSize

    /**
     * Closes this source and releases any system resources associated with it.
     */
    @Suppress("SwallowedException")
    override fun close() {
        try {
            channel.close()
            fis.close()
            pfd.close()
        } catch (e: IOException) {
            // Suppress close exceptions or log them
        }
    }
}
