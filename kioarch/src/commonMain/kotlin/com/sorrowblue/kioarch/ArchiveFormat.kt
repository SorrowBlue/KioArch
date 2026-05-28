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
 * Supported archive formats in KioArch.
 *
 * @property extension The canonical lowercase file extension of the format.
 */
public enum class ArchiveFormat(public val extension: String) {
    /** 7-Zip Archive format. */
    SEVEN_ZIP("7z"),

    /** ZIP Archive format. */
    ZIP("zip"),

    /** Gzip Compressed Tar Archive format. */
    TAR_GZ("tar.gz"),

    /** Gzip Compressed Tar Archive format (.tgz). */
    TGZ("tgz"),

    /** Bzip2 Compressed format. */
    BZIP2("bz2"),

    /** Bzip2 Compressed Tar Archive format. */
    TAR_BZ2("tar.bz2"),

    /** Bzip2 Compressed Tar Archive format (.tbz2). */
    TBZ2("tbz2"),

    /** Bzip2 Compressed Tar Archive format (.tbz). */
    TBZ("tbz")
}
