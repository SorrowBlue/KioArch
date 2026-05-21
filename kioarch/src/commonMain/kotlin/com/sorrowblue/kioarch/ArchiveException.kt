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
 * Base exception class representing errors during archive operations in KioArch.
 */
public sealed class ArchiveException(message: String, cause: Throwable? = null) :
    Exception(
        message,
        cause
    )

/**
 * Exception thrown when the archive is corrupted or its data structure is invalid.
 */
public class ArchiveCorruptedException(message: String) : ArchiveException(message)

/**
 * Exception thrown when an unsupported archive format, compression algorithm, or encryption method is used.
 */
public class ArchiveUnsupportedException(message: String) : ArchiveException(message)

/**
 * Exception thrown when an I/O error occurs during archive reading, writing, or seeking.
 */
public class ArchiveIOException(message: String, cause: Throwable? = null) :
    ArchiveException(
        message,
        cause
    )

/**
 * Exception thrown when native memory allocation fails.
 */
public class ArchiveOutOfMemoryException(message: String) : ArchiveException(message)

/**
 * Exception thrown when the specified file is not a valid archive or the input arguments are invalid.
 */
public class ArchiveInvalidException(message: String) : ArchiveException(message)
