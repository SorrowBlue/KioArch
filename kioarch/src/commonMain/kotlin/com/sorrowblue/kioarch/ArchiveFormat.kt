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
 * Archive and compression formats supported by [KioArch].
 *
 * Format detection at runtime is performed by inspecting the stream's leading magic bytes rather
 * than matching filenames against [extension].
 *
 * @param extension the canonical lowercase file extension (without a leading dot) associated with
 *   this format.
 */
public enum class ArchiveFormat(
    /**
     * The canonical lowercase file extension (without a leading dot) associated with this format.
     */
    public val extension: String
) {
    /** 7-Zip archive format (`.7z`), decoded via the 7-Zip Core C engine (`CSzArEx`). */
    SEVEN_ZIP("7z"),

    /** ZIP archive format (`.zip`), decoded via the `miniz` engine. */
    ZIP("zip"),

    /** Gzip-compressed POSIX `ustar` Tar archive format (`.tar.gz`). */
    TAR_GZ("tar.gz"),

    /** Gzip-compressed POSIX `ustar` Tar archive format using the `.tgz` extension alias. */
    TGZ("tgz"),

    /** Single-stream Bzip2 compressed format (`.bz2`), decoded via `libbzip2`. */
    BZIP2("bz2"),

    /** Bzip2-compressed POSIX `ustar` Tar archive format (`.tar.bz2`). */
    TAR_BZ2("tar.bz2"),

    /** Bzip2-compressed POSIX `ustar` Tar archive format using the `.tbz2` extension alias. */
    TBZ2("tbz2"),

    /** Bzip2-compressed POSIX `ustar` Tar archive format using the `.tbz` extension alias. */
    TBZ("tbz")
}
