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

/**
 * Base exception hierarchy representing errors thrown during archive opening, inspection, or
 * extraction in [KioArch].
 *
 * @param message a detail message describing the failure.
 * @param cause the underlying cause of this exception, or `null` if none.
 */
public sealed class ArchiveException(message: String, cause: Throwable? = null) :
    Exception(
        message,
        cause
    ) {
    /**
     * Creates an [ArchiveException] with the specified detail [message] and no cause.
     *
     * @param message a detail message describing the failure.
     */
    protected constructor(message: String) : this(message, null)
}

/**
 * Thrown when an archive's headers, compressed streams, or CRC-32 checksums are corrupted.
 *
 * @param message a detail message describing the corruption or validation failure.
 */
public class ArchiveCorruptedException(message: String) : ArchiveException(message)

/**
 * Thrown when an archive uses an unsupported container format, compression codec, filter, or
 * encryption scheme.
 *
 * @param message a detail message describing the unsupported feature.
 */
public class ArchiveUnsupportedException(message: String) : ArchiveException(message)

/**
 * Thrown when an I/O failure occurs while reading from a [SeekableSource], seeking within the
 * archive, or writing decompressed bytes to a `kotlinx.io.Sink`.
 *
 * @param message a detail message describing the I/O failure.
 * @param cause the underlying I/O cause, or `null` if reported directly by the native engine.
 */
public class ArchiveIOException(message: String, cause: Throwable? = null) :
    ArchiveException(
        message,
        cause
    ) {
    /**
     * Creates an [ArchiveIOException] with the specified detail [message] and no cause.
     *
     * @param message a detail message describing the I/O failure.
     */
    public constructor(message: String) : this(message, null)
}

/**
 * Thrown when the native decompression engine fails to allocate required heap or dictionary
 * memory.
 *
 * @param message a detail message describing the allocation failure.
 */
public class ArchiveOutOfMemoryException(message: String) : ArchiveException(message)

/**
 * Thrown when the input stream is not a recognized archive or invalid arguments are supplied to an
 * archive operation.
 *
 * @param message a detail message describing why the input or operation is invalid.
 */
public class ArchiveInvalidException(message: String) : ArchiveException(message)
