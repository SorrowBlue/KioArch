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

#include "kioarch_ios.h"
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>

#include "7z.h"
#include "7zAlloc.h"
#include "7zBuf.h"
#include "7zCrc.h"
#include "7zFile.h"
#include "7zTypes.h"
#include "miniz.h"

#define kInputBufSize ((size_t)1 << 18) // 256KB input buffer for LookToRead2

static void set_error_msg(char *err_msg, int32_t err_msg_len, const char *format, ...) {
    if (err_msg == NULL || err_msg_len <= 0) return;
    va_list args;
    va_start(args, format);
    vsnprintf(err_msg, (size_t)err_msg_len, format, args);
    va_end(args);
}

static void get_7z_error_str(SRes res, char *out_err, int32_t max_len) {
    const char *err_str = "Unknown error";
    switch (res) {
        case SZ_ERROR_DATA:
            err_str = "Data error (corruption)";
            break;
        case SZ_ERROR_MEM:
            err_str = "Memory allocation failed";
            break;
        case SZ_ERROR_CRC:
            err_str = "CRC check failed";
            break;
        case SZ_ERROR_UNSUPPORTED:
            err_str = "Unsupported archive or compression method";
            break;
        case SZ_ERROR_PARAM:
            err_str = "Invalid parameter";
            break;
        case SZ_ERROR_INPUT_EOF:
        case SZ_ERROR_OUTPUT_EOF:
        case SZ_ERROR_READ:
        case SZ_ERROR_WRITE:
            err_str = "I/O error";
            break;
        case SZ_ERROR_ARCHIVE:
            err_str = "Archive structure is corrupted";
            break;
        case SZ_ERROR_NO_ARCHIVE:
            err_str = "Not a valid 7z archive";
            break;
        default:
            break;
    }
    snprintf(out_err, max_len, "%s (code: %d)", err_str, res);
}

typedef enum {
    ARCHIVE_TYPE_7Z,
    ARCHIVE_TYPE_ZIP,
    ARCHIVE_TYPE_TARGZ
} ArchiveType;

typedef struct {
    char name[100];
    char mode[8];
    char uid[8];
    char gid[8];
    char size[12];
    char mtime[12];
    char chksum[8];
    char typeflag;
    char linkname[100];
    char magic[6];
    char version[2];
    char uname[32];
    char gname[32];
    char devmajor[8];
    char devminor[8];
    char prefix[155];
    char padding[12];
} TarHeader;

typedef struct {
    char *name;
    int64_t size;
    int32_t is_dir;
    int64_t data_offset;
    int64_t header_offset;
} TarGzEntry;

typedef struct {
    mz_stream stream;
    int is_init;
    int64_t current_source_offset;
    int64_t total_decompressed;
    unsigned char in_buf[16384];
} GzipDecoder;

// Unified ArchiveHandle for iOS
typedef struct {
    ISeekInStream vt;
    kio_source_t *kioSource;
} Ios7zInStream;

typedef struct {
    ArchiveType type;
    kio_source_t source;

    // 7-Zip Context
    Ios7zInStream ios7zStream;
    CLookToRead2 lookStream;
    CSzArEx db;
    ISzAlloc alloc;
    ISzAlloc allocTemp;
    int isDbExInit;

    // miniz ZIP Context
    mz_zip_archive zipArchive;
    int isZipInit;

    // .tar.gz Context
    TarGzEntry *tarGzEntries;
    int tarGzCount;
} ArchiveHandleIos;

// 7-Zip ISeekInStream.Read callback implementation calling custom Kotlin callback
static SRes IosInStream_Read(ISeekInStreamPtr p, void *buf, size_t *size) {
    Ios7zInStream *stream = (Ios7zInStream *)p;
    kio_source_t *kio = stream->kioSource;
    size_t originalSize = *size;
    if (originalSize == 0) return SZ_OK;

    int32_t readBytes = kio->read(kio->opaque, (uint8_t *)buf, (int32_t)originalSize);
    if (readBytes < 0) {
        *size = 0;
        return SZ_ERROR_FAIL;
    }
    *size = (size_t)readBytes;
    return SZ_OK;
}

// 7-Zip ISeekInStream.Seek callback implementation calling custom Kotlin callback
static SRes IosInStream_Seek(ISeekInStreamPtr p, Int64 *pos, ESzSeek origin) {
    Ios7zInStream *stream = (Ios7zInStream *)p;
    kio_source_t *kio = stream->kioSource;
    Int64 newPos = *pos;

    if (origin == SZ_SEEK_CUR) {
        int64_t cur = kio->position(kio->opaque);
        newPos += cur;
    } else if (origin == SZ_SEEK_END) {
        int64_t len = kio->length(kio->opaque);
        newPos += len;
    }

    kio->seek(kio->opaque, newPos);
    *pos = newPos;
    return SZ_OK;
}

// Custom read callback for miniz wrapping custom source
static size_t Miniz_Read_Callback_Ios(void *pOpaque, mz_uint64 file_ofs, void *pBuf, size_t n) {
    kio_source_t *stream = (kio_source_t *)pOpaque;
    if (n == 0) return 0;

    stream->seek(stream->opaque, (int64_t)file_ofs);
    int32_t readBytes = stream->read(stream->opaque, (uint8_t *)pBuf, (int32_t)n);
    if (readBytes <= 0) return 0;
    return (size_t)readBytes;
}

// Custom write callback for miniz streaming decompressed chunk into custom sink
static size_t Miniz_Write_Callback_Ios(void *pOpaque, mz_uint64 file_ofs, const void *pBuf, size_t n) {
    kio_sink_t *ctx = (kio_sink_t *)pOpaque;
    if (n == 0) return 0;

    ctx->write(ctx->opaque, (const uint8_t *)pBuf, (int32_t)n);
    return n;
}

static SRes init_gzip_decoder(ArchiveHandleIos *archive, GzipDecoder *decoder) {
    memset(decoder, 0, sizeof(GzipDecoder));
    decoder->stream.zalloc = NULL;
    decoder->stream.zfree = NULL;
    decoder->stream.opaque = NULL;
    int status = mz_inflateInit2(&decoder->stream, -15);
    if (status != MZ_OK) {
        return SZ_ERROR_MEM;
    }
    decoder->is_init = 1;
    decoder->current_source_offset = 0;
    decoder->total_decompressed = 0;
    return SZ_OK;
}

static void free_gzip_decoder(GzipDecoder *decoder) {
    if (decoder->is_init) {
        mz_inflateEnd(&decoder->stream);
        decoder->is_init = 0;
    }
}

static int read_from_source(ArchiveHandleIos *archive, int64_t offset, void *buf, size_t size) {
    archive->source.seek(archive->source.opaque, offset);
    int32_t readBytes = archive->source.read(archive->source.opaque, (uint8_t *)buf, (int32_t)size);
    return readBytes;
}

static int64_t skip_gzip_header(ArchiveHandleIos *archive, int64_t start_offset) {
    unsigned char header[10];
    int readBytes = read_from_source(archive, start_offset, header, 10);
    if (readBytes < 10) return -1;
    if (header[0] != 0x1F || header[1] != 0x8B || header[2] != 0x08) {
        return -1;
    }
    unsigned char flags = header[3];
    int64_t offset = start_offset + 10;
    if (flags & 4) {
        unsigned char xlen_buf[2];
        readBytes = read_from_source(archive, offset, xlen_buf, 2);
        if (readBytes < 2) return -1;
        int xlen = xlen_buf[0] | (xlen_buf[1] << 8);
        offset += 2 + xlen;
    }
    if (flags & 8) {
        unsigned char c;
        do {
            readBytes = read_from_source(archive, offset, &c, 1);
            if (readBytes < 1) return -1;
            offset++;
        } while (c != 0);
    }
    if (flags & 16) {
        unsigned char c;
        do {
            readBytes = read_from_source(archive, offset, &c, 1);
            if (readBytes < 1) return -1;
            offset++;
        } while (c != 0);
    }
    if (flags & 2) {
        offset += 2;
    }
    return offset;
}

static SRes read_decompressed_bytes(ArchiveHandleIos *archive, GzipDecoder *decoder, void *out_buf, size_t out_len, size_t *processed, int64_t raw_deflate_start) {
    size_t written = 0;
    *processed = 0;
    while (written < out_len) {
        if (decoder->stream.avail_in == 0) {
            int64_t src_offset = raw_deflate_start + decoder->current_source_offset;
            int r = read_from_source(archive, src_offset, decoder->in_buf, sizeof(decoder->in_buf));
            if (r < 0) return SZ_ERROR_READ;
            if (r == 0) {
                break;
            }
            decoder->stream.next_in = decoder->in_buf;
            decoder->stream.avail_in = (unsigned int)r;
            decoder->current_source_offset += r;
        }
        decoder->stream.next_out = (unsigned char *)out_buf + written;
        decoder->stream.avail_out = (unsigned int)(out_len - written);
        int status = mz_inflate(&decoder->stream, Z_NO_FLUSH);
        size_t decompressed_this_time = (out_len - written) - decoder->stream.avail_out;
        written += decompressed_this_time;
        decoder->total_decompressed += decompressed_this_time;
        if (status == Z_STREAM_END) {
            break;
        }
        if (status != Z_OK && status != Z_BUF_ERROR) {
            return SZ_ERROR_DATA;
        }
    }
    *processed = written;
    return SZ_OK;
}

static SRes skip_decompressed_bytes(ArchiveHandleIos *archive, GzipDecoder *decoder, size_t skip_len, int64_t raw_deflate_start) {
    unsigned char dummy[4096];
    size_t remaining = skip_len;
    while (remaining > 0) {
        size_t to_read = (remaining < sizeof(dummy)) ? remaining : sizeof(dummy);
        size_t processed = 0;
        SRes res = read_decompressed_bytes(archive, decoder, dummy, to_read, &processed, raw_deflate_start);
        if (res != SZ_OK) return res;
        if (processed == 0) return SZ_ERROR_DATA;
        remaining -= processed;
    }
    return SZ_OK;
}

static SRes scan_tar_gz_entries(ArchiveHandleIos *archive, int64_t raw_deflate_start) {
    GzipDecoder decoder;
    SRes res = init_gzip_decoder(archive, &decoder);
    if (res != SZ_OK) return res;
    int capacity = 16;
    archive->tarGzEntries = (TarGzEntry *)malloc(capacity * sizeof(TarGzEntry));
    archive->tarGzCount = 0;
    TarHeader header;
    int consecutive_zero_blocks = 0;
    while (1) {
        size_t processed = 0;
        int64_t header_offset = decoder.total_decompressed;
        res = read_decompressed_bytes(archive, &decoder, &header, 512, &processed, raw_deflate_start);
        if (res != SZ_OK) goto error;
        if (processed < 512) {
            break;
        }
        int is_zero = 1;
        unsigned char *p = (unsigned char *)&header;
        for (int i = 0; i < 512; i++) {
            if (p[i] != 0) {
                is_zero = 0;
                break;
            }
        }
        if (is_zero) {
            consecutive_zero_blocks++;
            if (consecutive_zero_blocks >= 2) {
                break;
            }
            continue;
        }
        consecutive_zero_blocks = 0;
        char name[101] = {0};
        memcpy(name, header.name, 100);
        char full_name[257] = {0};
        if (strncmp(header.magic, "ustar", 5) == 0) {
            char prefix[156] = {0};
            memcpy(prefix, header.prefix, 155);
            if (strlen(prefix) > 0) {
                snprintf(full_name, sizeof(full_name), "%s/%s", prefix, name);
            } else {
                strncpy(full_name, name, sizeof(full_name) - 1);
            }
        } else {
            strncpy(full_name, name, sizeof(full_name) - 1);
        }
        char size_str[13] = {0};
        memcpy(size_str, header.size, 12);
        int64_t file_size = (int64_t)strtol(size_str, NULL, 8);
        int32_t is_dir = (header.typeflag == '5') ? 1 : 0;
        if (archive->tarGzCount >= capacity) {
            capacity *= 2;
            TarGzEntry *new_entries = (TarGzEntry *)realloc(archive->tarGzEntries, capacity * sizeof(TarGzEntry));
            if (new_entries == NULL) {
                res = SZ_ERROR_MEM;
                goto error;
            }
            archive->tarGzEntries = new_entries;
        }
        TarGzEntry *entry = &archive->tarGzEntries[archive->tarGzCount];
        entry->name = strdup(full_name);
        entry->size = file_size;
        entry->is_dir = is_dir;
        entry->header_offset = header_offset;
        entry->data_offset = decoder.total_decompressed;
        archive->tarGzCount++;
        int64_t skip_size = (file_size + 511) & ~511;
        res = skip_decompressed_bytes(archive, &decoder, (size_t)skip_size, raw_deflate_start);
        if (res != SZ_OK) goto error;
    }
    free_gzip_decoder(&decoder);
    return SZ_OK;
error:
    free_gzip_decoder(&decoder);
    for (int i = 0; i < archive->tarGzCount; i++) {
        free(archive->tarGzEntries[i].name);
    }
    free(archive->tarGzEntries);
    archive->tarGzEntries = NULL;
    archive->tarGzCount = 0;
    return res;
}

uint64_t kio_open_archive(kio_source_t source, char *err_msg, int32_t err_msg_len) {
    ArchiveHandleIos *archive = NULL;
    uint8_t magic[8] = {0};
    int isZip = 0;
    int is7z = 0;
    int isTarGz = 0;

    CrcGenerateTable();

    archive = (ArchiveHandleIos *)malloc(sizeof(ArchiveHandleIos));
    if (archive == NULL) {
        set_error_msg(err_msg, err_msg_len, "Failed to allocate memory for archive handle");
        return 0;
    }
    memset(archive, 0, sizeof(ArchiveHandleIos));
    archive->source = source;

    // Detect format using magic bytes
    source.seek(source.opaque, 0);
    int32_t bytesRead = source.read(source.opaque, magic, 8);
    if (bytesRead < 0) {
        set_error_msg(err_msg, err_msg_len, "Failed to read magic bytes from source");
        free(archive);
        return 0;
    }

    if (bytesRead >= 4) {
        // Check 7z Magic: 37 7A BC AF 27 1C
        if (bytesRead >= 6 &&
            magic[0] == 0x37 && magic[1] == 0x7A && magic[2] == 0xBC &&
            magic[3] == 0xAF && magic[4] == 0x27 && magic[5] == 0x1C) {
            is7z = 1;
        }
        // Check Zip Magic: PK (50 4B)
        else if (magic[0] == 0x50 && magic[1] == 0x4B) {
            isZip = 1;
        }
        // Check Gzip Magic: 1F 8B
        else if (magic[0] == 0x1F && magic[1] == 0x8B) {
            isTarGz = 1;
        }
    }

    // Reset stream position to 0 after magic detection
    source.seek(source.opaque, 0);

    if (is7z) {
        SRes res;
        archive->type = ARCHIVE_TYPE_7Z;
        archive->alloc.Alloc = SzAlloc;
        archive->alloc.Free = SzFree;
        archive->allocTemp.Alloc = SzAllocTemp;
        archive->allocTemp.Free = SzFreeTemp;

        archive->ios7zStream.vt.Read = IosInStream_Read;
        archive->ios7zStream.vt.Seek = IosInStream_Seek;
        archive->ios7zStream.kioSource = &archive->source;

        // Allocate input buffer for LookToRead2 stream
        archive->lookStream.buf = (Byte *)archive->alloc.Alloc(&archive->alloc, kInputBufSize);
        if (archive->lookStream.buf == NULL) {
            set_error_msg(err_msg, err_msg_len, "Failed to allocate 7z look stream buffer");
            free(archive);
            return 0;
        }
        archive->lookStream.bufSize = kInputBufSize;
        archive->lookStream.realStream = &archive->ios7zStream.vt;
        LookToRead2_CreateVTable(&archive->lookStream, False);
        LookToRead2_INIT(&archive->lookStream);

        // Initialize 7z database
        SzArEx_Init(&archive->db);
        archive->isDbExInit = 1;

        res = SzArEx_Open(&archive->db, &archive->lookStream.vt, &archive->alloc, &archive->allocTemp);
        if (res != SZ_OK) {
            char err_str[128];
            get_7z_error_str(res, err_str, sizeof(err_str));
            set_error_msg(err_msg, err_msg_len, "Failed to open 7z archive: %s", err_str);
            archive->alloc.Free(&archive->alloc, archive->lookStream.buf);
            SzArEx_Free(&archive->db, &archive->alloc);
            free(archive);
            return 0;
        }
    } else if (isZip) {
        archive->type = ARCHIVE_TYPE_ZIP;
        int64_t totalLen = source.length(source.opaque);

        memset(&archive->zipArchive, 0, sizeof(mz_zip_archive));
        archive->zipArchive.m_pRead = Miniz_Read_Callback_Ios;
        archive->zipArchive.m_pIO_opaque = &archive->source;

        mz_bool zipRes = mz_zip_reader_init(&archive->zipArchive, (mz_uint64)totalLen, 0);
        if (!zipRes) {
            mz_zip_error last_err = mz_zip_get_last_error(&archive->zipArchive);
            set_error_msg(err_msg, err_msg_len, "Failed to open zip archive: %s (code: %d)", mz_zip_get_error_string(last_err), last_err);
            free(archive);
            return 0;
        }
        archive->isZipInit = 1;
    } else if (isTarGz) {
        archive->type = ARCHIVE_TYPE_TARGZ;
        int64_t raw_deflate_start = skip_gzip_header(archive, 0);
        if (raw_deflate_start < 0) {
            set_error_msg(err_msg, err_msg_len, "Failed to parse Gzip header: Invalid header format");
            free(archive);
            return 0;
        }
        SRes res = scan_tar_gz_entries(archive, raw_deflate_start);
        if (res != SZ_OK) {
            char err_str[128];
            get_7z_error_str(res, err_str, sizeof(err_str));
            set_error_msg(err_msg, err_msg_len, "Failed to scan tar.gz entries: %s", err_str);
            free(archive);
            return 0;
        }
    } else {
        set_error_msg(err_msg, err_msg_len, "Unsupported archive format (only 7z, ZIP, and tar.gz are supported)");
        free(archive);
        return 0;
    }

    return (uint64_t)archive;
}

void kio_close_archive(uint64_t handle) {
    if (handle == 0) return;
    ArchiveHandleIos *archive = (ArchiveHandleIos *)handle;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        if (archive->isDbExInit) {
            SzArEx_Free(&archive->db, &archive->alloc);
        }
        if (archive->lookStream.buf != NULL) {
            archive->alloc.Free(&archive->alloc, archive->lookStream.buf);
        }
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        if (archive->isZipInit) {
            mz_zip_reader_end(&archive->zipArchive);
        }
    } else if (archive->type == ARCHIVE_TYPE_TARGZ) {
        if (archive->tarGzEntries != NULL) {
            for (int i = 0; i < archive->tarGzCount; i++) {
                free(archive->tarGzEntries[i].name);
            }
            free(archive->tarGzEntries);
        }
    }
    free(archive);
}

int32_t kio_get_entry_count(uint64_t handle) {
    if (handle == 0) return -1;
    ArchiveHandleIos *archive = (ArchiveHandleIos *)handle;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        return (int32_t)archive->db.NumFiles;
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        return (int32_t)mz_zip_reader_get_num_files(&archive->zipArchive);
    } else if (archive->type == ARCHIVE_TYPE_TARGZ) {
        return archive->tarGzCount;
    }
    return -1;
}

int32_t kio_get_entry(uint64_t handle, int32_t index, kio_entry_t *entry) {
    if (handle == 0 || entry == NULL) return 0;
    ArchiveHandleIos *archive = (ArchiveHandleIos *)handle;

    int32_t count = kio_get_entry_count(handle);
    if (index < 0 || index >= count) return 0;

    entry->index = index;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        size_t nameLen = SzArEx_GetFileNameUtf16(&archive->db, (size_t)index, NULL);
        if (nameLen > 0) {
            UInt16 *nameBuf = (UInt16 *)malloc(nameLen * sizeof(UInt16));
            if (nameBuf != NULL) {
                SzArEx_GetFileNameUtf16(&archive->db, (size_t)index, nameBuf);
                // In Kotlin/Native cinterop, we represent names as UTF-8. 
                // We will convert UTF-16 name to UTF-8 before returning.
                // For simplicity, we can do name conversion in Kotlin side or in C.
                // Converting UTF-16 to UTF-8 in C:
                size_t utf8_capacity = nameLen * 3 + 1;
                char *utf8_name = (char *)malloc(utf8_capacity);
                if (utf8_name != NULL) {
                    size_t utf8_len = 0;
                    for (size_t i = 0; i < nameLen - 1; i++) {
                        UInt16 c = nameBuf[i];
                        if (c < 0x80) {
                            utf8_name[utf8_len++] = (char)c;
                        } else if (c < 0x800) {
                            utf8_name[utf8_len++] = (char)(0xC0 | (c >> 6));
                            utf8_name[utf8_len++] = (char)(0x80 | (c & 0x3F));
                        } else {
                            utf8_name[utf8_len++] = (char)(0xE0 | (c >> 12));
                            utf8_name[utf8_len++] = (char)(0x80 | ((c >> 6) & 0x3F));
                            utf8_name[utf8_len++] = (char)(0x80 | (c & 0x3F));
                        }
                    }
                    utf8_name[utf8_len] = '\0';
                    entry->name = utf8_name; // allocated dynamically, Kotlin side must copy it.
                } else {
                    entry->name = "";
                }
                free(nameBuf);
            } else {
                entry->name = "";
            }
        } else {
            entry->name = "";
        }
        entry->size = (int64_t)SzArEx_GetFileSize(&archive->db, (size_t)index);
        entry->is_dir = SzArEx_IsDir(&archive->db, (size_t)index) ? 1 : 0;
        entry->crc = SzBitWithVals_Check(&archive->db.CRCs, (size_t)index) ? archive->db.CRCs.Vals[index] : 0;
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        mz_zip_archive_file_stat stat;
        if (mz_zip_reader_file_stat(&archive->zipArchive, (mz_uint)index, &stat)) {
            entry->name = strdup(stat.m_filename); // allocated dynamically
            entry->size = (int64_t)stat.m_uncomp_size;
            entry->is_dir = stat.m_is_directory ? 1 : 0;
            entry->crc = stat.m_crc32;
        } else {
            return 0;
        }
    } else if (archive->type == ARCHIVE_TYPE_TARGZ) {
        TarGzEntry *tar_entry = &archive->tarGzEntries[index];
        entry->name = strdup(tar_entry->name); // allocated dynamically
        entry->size = tar_entry->size;
        entry->is_dir = tar_entry->is_dir;
        entry->crc = 0;
    }

    return 1;
}

int32_t kio_extract_entry(uint64_t handle, int32_t index, kio_sink_t sink, char *err_msg, int32_t err_msg_len) {
    if (handle == 0) return 0;
    ArchiveHandleIos *archive = (ArchiveHandleIos *)handle;

    int32_t count = kio_get_entry_count(handle);
    if (index < 0 || index >= count) return 0;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        UInt32 blockIndex = 0xFFFFFFFF;
        Byte *outBuffer = NULL;
        size_t outBufferSize = 0;
        size_t offset = 0;
        size_t outSizeProcessed = 0;
        SRes res;

        res = SzArEx_Extract(
            &archive->db, &archive->lookStream.vt, (UInt32)index,
            &blockIndex, &outBuffer, &outBufferSize,
            &offset, &outSizeProcessed,
            &archive->alloc, &archive->allocTemp
        );

        if (res != SZ_OK) {
            char err_str[128];
            get_7z_error_str(res, err_str, sizeof(err_str));
            set_error_msg(err_msg, err_msg_len, "Failed to extract 7z entry: %s", err_str);
            if (outBuffer != NULL) {
                archive->alloc.Free(&archive->alloc, outBuffer);
            }
            return 0;
        }

        if (outSizeProcessed > 0 && outBuffer != NULL) {
            size_t bytesWritten = 0;
            size_t chunkSize = 65536;

            while (bytesWritten < outSizeProcessed) {
                size_t remaining = outSizeProcessed - bytesWritten;
                size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;

                sink.write(sink.opaque, outBuffer + offset + bytesWritten, (int32_t)currentChunk);
                bytesWritten += currentChunk;
            }
        }

        if (outBuffer != NULL) {
            archive->alloc.Free(&archive->alloc, outBuffer);
        }
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        mz_bool success = mz_zip_reader_extract_to_callback(&archive->zipArchive, (mz_uint)index, Miniz_Write_Callback_Ios, &sink, 0);
        if (!success) {
            mz_zip_error last_err = mz_zip_get_last_error(&archive->zipArchive);
            set_error_msg(err_msg, err_msg_len, "Failed to extract ZIP entry: %s (code: %d)", mz_zip_get_error_string(last_err), last_err);
            return 0;
        }
    } else if (archive->type == ARCHIVE_TYPE_TARGZ) {
        TarGzEntry *entry = &archive->tarGzEntries[index];
        int64_t raw_deflate_start = skip_gzip_header(archive, 0);
        if (raw_deflate_start < 0) return 0;

        GzipDecoder decoder;
        SRes res = init_gzip_decoder(archive, &decoder);
        if (res != SZ_OK) return 0;

        res = skip_decompressed_bytes(archive, &decoder, (size_t)entry->data_offset, raw_deflate_start);
        if (res != SZ_OK) {
            free_gzip_decoder(&decoder);
            return 0;
        }

        size_t bytesWritten = 0;
        size_t chunkSize = 65536;
        unsigned char *outBuffer = (unsigned char *)malloc(chunkSize);
        if (outBuffer == NULL) {
            free_gzip_decoder(&decoder);
            return 0;
        }

        while (bytesWritten < (size_t)entry->size) {
            size_t remaining = (size_t)entry->size - bytesWritten;
            size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;
            size_t processed = 0;

            res = read_decompressed_bytes(archive, &decoder, outBuffer, currentChunk, &processed, raw_deflate_start);
            if (res != SZ_OK || processed == 0) {
                break;
            }

            sink.write(sink.opaque, outBuffer, (int32_t)processed);
            bytesWritten += processed;
        }

        free(outBuffer);
        free_gzip_decoder(&decoder);

        if (res != SZ_OK || bytesWritten < (size_t)entry->size) {
            set_error_msg(err_msg, err_msg_len, "Failed to decompress Gzip stream during extraction");
            return 0;
        }
    }

    return 1;
}
