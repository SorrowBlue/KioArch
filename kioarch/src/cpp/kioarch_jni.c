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

#include <jni.h>
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
#include "bzlib.h"

#define kInputBufSize ((size_t)1 << 18) // 256KB input buffer for LookToRead2

static void throw_archive_exception(JNIEnv *env, const char *class_path, const char *format, ...) {
    char message[512];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);

    jclass exClass = (*env)->FindClass(env, class_path);
    if (exClass != NULL) {
        (*env)->ThrowNew(env, exClass, message);
    }
}

static void throw_7z_error(JNIEnv *env, SRes res, const char *action) {
    const char *ex_class = "com/sorrowblue/kioarch/ArchiveException";
    const char *err_str = "Unknown error";

    switch (res) {
        case SZ_ERROR_DATA:
            ex_class = "com/sorrowblue/kioarch/ArchiveCorruptedException";
            err_str = "Data error (corruption)";
            break;
        case SZ_ERROR_MEM:
            ex_class = "com/sorrowblue/kioarch/ArchiveOutOfMemoryException";
            err_str = "Memory allocation failed";
            break;
        case SZ_ERROR_CRC:
            ex_class = "com/sorrowblue/kioarch/ArchiveCorruptedException";
            err_str = "CRC check failed";
            break;
        case SZ_ERROR_UNSUPPORTED:
            ex_class = "com/sorrowblue/kioarch/ArchiveUnsupportedException";
            err_str = "Unsupported archive or compression method";
            break;
        case SZ_ERROR_PARAM:
            ex_class = "com/sorrowblue/kioarch/ArchiveInvalidException";
            err_str = "Invalid parameter";
            break;
        case SZ_ERROR_INPUT_EOF:
        case SZ_ERROR_OUTPUT_EOF:
        case SZ_ERROR_READ:
        case SZ_ERROR_WRITE:
            ex_class = "com/sorrowblue/kioarch/ArchiveIOException";
            err_str = "I/O error";
            break;
        case SZ_ERROR_ARCHIVE:
            ex_class = "com/sorrowblue/kioarch/ArchiveCorruptedException";
            err_str = "Archive structure is corrupted";
            break;
        case SZ_ERROR_NO_ARCHIVE:
            ex_class = "com/sorrowblue/kioarch/ArchiveInvalidException";
            err_str = "Not a valid 7z archive";
            break;
        default:
            break;
    }
    throw_archive_exception(env, ex_class, "Failed to %s: %s (code: %d)", action, err_str, res);
}

static void throw_miniz_error(JNIEnv *env, mz_zip_error err, const char *action) {
    const char *ex_class = "com/sorrowblue/kioarch/ArchiveException";
    const char *err_str = mz_zip_get_error_string(err);

    switch (err) {
        case MZ_ZIP_INVALID_HEADER_OR_CORRUPTED:
        case MZ_ZIP_CRC_CHECK_FAILED:
        case MZ_ZIP_UNEXPECTED_DECOMPRESSED_SIZE:
        case MZ_ZIP_VALIDATION_FAILED:
            ex_class = "com/sorrowblue/kioarch/ArchiveCorruptedException";
            break;
        case MZ_ZIP_UNSUPPORTED_METHOD:
        case MZ_ZIP_UNSUPPORTED_ENCRYPTION:
        case MZ_ZIP_UNSUPPORTED_FEATURE:
        case MZ_ZIP_UNSUPPORTED_MULTIDISK:
        case MZ_ZIP_UNSUPPORTED_CDIR_SIZE:
            ex_class = "com/sorrowblue/kioarch/ArchiveUnsupportedException";
            break;
        case MZ_ZIP_ALLOC_FAILED:
            ex_class = "com/sorrowblue/kioarch/ArchiveOutOfMemoryException";
            break;
        case MZ_ZIP_NOT_AN_ARCHIVE:
        case MZ_ZIP_INVALID_PARAMETER:
        case MZ_ZIP_INVALID_FILENAME:
            ex_class = "com/sorrowblue/kioarch/ArchiveInvalidException";
            break;
        case MZ_ZIP_FILE_READ_FAILED:
        case MZ_ZIP_FILE_WRITE_FAILED:
        case MZ_ZIP_FILE_SEEK_FAILED:
        case MZ_ZIP_WRITE_CALLBACK_FAILED:
            ex_class = "com/sorrowblue/kioarch/ArchiveIOException";
            break;
        default:
            break;
    }
    throw_archive_exception(env, ex_class, "Failed to %s: %s (code: %d)", action, err_str, err);
}

// JNI input stream structure mapping SeekableSource to ISeekInStream
typedef struct {
    ISeekInStream vt;
    JNIEnv *env;
    jobject kotlinSource;
    jmethodID readMethod;
    jmethodID seekMethod;
    jmethodID positionMethod;
    jmethodID lengthMethod;
    jbyteArray tempBuffer;
    jint tempBufferSize;
    jmethodID readDirectMethod;
} JniInStream;

typedef enum {
    ARCHIVE_TYPE_7Z,
    ARCHIVE_TYPE_ZIP,
    ARCHIVE_TYPE_TARGZ,
    ARCHIVE_TYPE_BZIP2,
    ARCHIVE_TYPE_TARBZ2
} ArchiveType;

typedef struct {
    bz_stream stream;
    int is_init;
    jlong current_source_offset;
    jlong total_decompressed;
    unsigned char in_buf[16384];
    int is_stream_end;
} Bzip2Decoder;

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
    jlong size;
    jboolean is_dir;
    jlong data_offset;
    jlong header_offset;
} TarGzEntry;

typedef struct {
    mz_stream stream;
    int is_init;
    jlong current_source_offset;
    jlong total_decompressed;
    unsigned char in_buf[16384];
} GzipDecoder;

// Unified ArchiveHandle structure keeping track of open archive session (7z, ZIP, or tar.gz)
typedef struct {
    ArchiveType type;
    JniInStream inStream;

    // 7-Zip Context
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
} ArchiveHandle;

// Context structure passed to miniz extraction callback
typedef struct {
    JNIEnv *env;
    jobject kotlinSink;
    jmethodID writeMethod;
} ZipExtractContext;

// ISeekInStream.Read callback implementation calling SeekableSource.read()
static SRes JniInStream_Read(ISeekInStreamPtr p, void *buf, size_t *size) {
    JniInStream *stream = (JniInStream *)p;
    JNIEnv *env = stream->env;
    size_t originalSize = *size;
    jint readBytes;
    jbyteArray localArray;

    if (originalSize == 0) return SZ_OK;

    // Direct ByteBuffer Path
    if (stream->readDirectMethod != NULL) {
        jobject directBuffer = (*env)->NewDirectByteBuffer(env, buf, originalSize);
        if (directBuffer != NULL) {
            readBytes = (*env)->CallIntMethod(env, stream->kotlinSource, stream->readDirectMethod, directBuffer);
            (*env)->DeleteLocalRef(env, directBuffer);
            if ((*env)->ExceptionCheck(env)) {
                return SZ_ERROR_FAIL;
            }
            if (readBytes < 0) {
                *size = 0;
            } else {
                *size = (size_t)readBytes;
            }
            return SZ_OK;
        }
    }

    // Lazily allocate/resize JNI temporary byte array to reuse across reads
    if (stream->tempBuffer == NULL || stream->tempBufferSize < (jint)originalSize) {
        if (stream->tempBuffer != NULL) {
            (*env)->DeleteGlobalRef(env, stream->tempBuffer);
        }
        localArray = (*env)->NewByteArray(env, (jsize)originalSize);
        stream->tempBuffer = (*env)->NewGlobalRef(env, localArray);
        (*env)->DeleteLocalRef(env, localArray);
        stream->tempBufferSize = (jint)originalSize;
    }

    // Call SeekableSource.read(buffer, offset, length)
    readBytes = (*env)->CallIntMethod(env, stream->kotlinSource, stream->readMethod,
                                           stream->tempBuffer, 0, (jint)originalSize);
    if ((*env)->ExceptionCheck(env)) {
        return SZ_ERROR_FAIL;
    }

    if (readBytes < 0) {
        *size = 0;
        return SZ_OK;
    }

    // Copy read bytes from JVM byte array to native C buffer
    (*env)->GetByteArrayRegion(env, stream->tempBuffer, 0, readBytes, (jbyte *)buf);
    *size = (size_t)readBytes;
    return SZ_OK;
}

// ISeekInStream.Seek callback implementation calling SeekableSource.seek()
static SRes JniInStream_Seek(ISeekInStreamPtr p, Int64 *pos, ESzSeek origin) {
    JniInStream *stream = (JniInStream *)p;
    JNIEnv *env = stream->env;
    Int64 newPos = *pos;

    if (origin == SZ_SEEK_CUR) {
        jlong cur = (*env)->CallLongMethod(env, stream->kotlinSource, stream->positionMethod);
        if ((*env)->ExceptionCheck(env)) return SZ_ERROR_FAIL;
        newPos += cur;
    } else if (origin == SZ_SEEK_END) {
        jlong len = (*env)->CallLongMethod(env, stream->kotlinSource, stream->lengthMethod);
        if ((*env)->ExceptionCheck(env)) return SZ_ERROR_FAIL;
        newPos += len;
    }

    // Call SeekableSource.seek(position)
    (*env)->CallVoidMethod(env, stream->kotlinSource, stream->seekMethod, (jlong)newPos);
    if ((*env)->ExceptionCheck(env)) {
        return SZ_ERROR_FAIL;
    }

    *pos = newPos;
    return SZ_OK;
}

// Custom read callback for miniz wrapping SeekableSource
static size_t Miniz_Read_Callback(void *pOpaque, mz_uint64 file_ofs, void *pBuf, size_t n) {
    ArchiveHandle *archive = (ArchiveHandle *)pOpaque;
    JniInStream *stream = &archive->inStream;
    JNIEnv *env = stream->env;

    if (n == 0) return 0;

    // Refresh thread-bound JNIEnv in stream struct
    stream->env = env;

    // Call SeekableSource.seek(file_ofs)
    (*env)->CallVoidMethod(env, stream->kotlinSource, stream->seekMethod, (jlong)file_ofs);
    if ((*env)->ExceptionCheck(env)) {
        return 0;
    }

    // Direct ByteBuffer Path
    if (stream->readDirectMethod != NULL) {
        jobject directBuffer = (*env)->NewDirectByteBuffer(env, pBuf, n);
        if (directBuffer != NULL) {
            jint readBytes = (*env)->CallIntMethod(env, stream->kotlinSource, stream->readDirectMethod, directBuffer);
            (*env)->DeleteLocalRef(env, directBuffer);
            if ((*env)->ExceptionCheck(env)) {
                return 0;
            }
            return (readBytes <= 0) ? 0 : (size_t)readBytes;
        }
    }

    // Lazily allocate/resize JNI temporary byte array to reuse across reads
    if (stream->tempBuffer == NULL || stream->tempBufferSize < (jint)n) {
        if (stream->tempBuffer != NULL) {
            (*env)->DeleteGlobalRef(env, stream->tempBuffer);
        }
        jbyteArray localArray = (*env)->NewByteArray(env, (jsize)n);
        if (localArray == NULL) {
            return 0;
        }
        stream->tempBuffer = (*env)->NewGlobalRef(env, localArray);
        (*env)->DeleteLocalRef(env, localArray);
        stream->tempBufferSize = (jint)n;
    }

    // Call SeekableSource.read(buffer, offset, length)
    jint readBytes = (*env)->CallIntMethod(env, stream->kotlinSource, stream->readMethod,
                                           stream->tempBuffer, 0, (jint)n);
    if ((*env)->ExceptionCheck(env)) {
        return 0;
    }

    if (readBytes <= 0) {
        return 0;
    }

    // Copy read bytes from JVM byte array to native C buffer
    (*env)->GetByteArrayRegion(env, stream->tempBuffer, 0, readBytes, (jbyte *)pBuf);
    return (size_t)readBytes;
}

// Custom write callback for miniz streaming decompressed chunk into kotlinx.io.Sink
static size_t Miniz_Write_Callback(void *pOpaque, mz_uint64 file_ofs, const void *pBuf, size_t n) {
    ZipExtractContext *ctx = (ZipExtractContext *)pOpaque;
    JNIEnv *env = ctx->env;

    if (n == 0) return 0;

    jbyteArray chunkArray = (*env)->NewByteArray(env, (jsize)n);
    if (chunkArray == NULL) return 0;

    // Copy data chunk to JVM byte array
    (*env)->SetByteArrayRegion(env, chunkArray, 0, (jsize)n, (const jbyte *)pBuf);

    // Call kotlinx.io.Sink.write(ByteArray, offset, byteCount)
    (*env)->CallVoidMethod(env, ctx->kotlinSink, ctx->writeMethod, chunkArray, 0, (jint)n);

    (*env)->DeleteLocalRef(env, chunkArray);

    if ((*env)->ExceptionCheck(env)) {
        return 0; // abort extraction on exception
    }

    return n;
}

static SRes init_gzip_decoder(JNIEnv *env, ArchiveHandle *archive, GzipDecoder *decoder) {
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

static int read_from_source(JNIEnv *env, ArchiveHandle *archive, jlong offset, void *buf, size_t size) {
    (*env)->CallVoidMethod(env, archive->inStream.kotlinSource, archive->inStream.seekMethod, (jlong)offset);
    if ((*env)->ExceptionCheck(env)) return -1;

    // Direct ByteBuffer Path
    if (archive->inStream.readDirectMethod != NULL) {
        jobject directBuffer = (*env)->NewDirectByteBuffer(env, buf, size);
        if (directBuffer != NULL) {
            jint readBytes = (*env)->CallIntMethod(env, archive->inStream.kotlinSource, archive->inStream.readDirectMethod, directBuffer);
            (*env)->DeleteLocalRef(env, directBuffer);
            if ((*env)->ExceptionCheck(env)) {
                return -1;
            }
            return readBytes;
        }
    }

    if (archive->inStream.tempBuffer == NULL || archive->inStream.tempBufferSize < (jint)size) {
        if (archive->inStream.tempBuffer != NULL) {
            (*env)->DeleteGlobalRef(env, archive->inStream.tempBuffer);
        }
        jbyteArray localArray = (*env)->NewByteArray(env, (jsize)size);
        if (localArray == NULL) return -1;
        archive->inStream.tempBuffer = (*env)->NewGlobalRef(env, localArray);
        (*env)->DeleteLocalRef(env, localArray);
        archive->inStream.tempBufferSize = (jint)size;
    }
    jint readBytes = (*env)->CallIntMethod(env, archive->inStream.kotlinSource, archive->inStream.readMethod,
                                           archive->inStream.tempBuffer, 0, (jint)size);
    if ((*env)->ExceptionCheck(env)) return -1;
    if (readBytes <= 0) return readBytes;
    (*env)->GetByteArrayRegion(env, archive->inStream.tempBuffer, 0, readBytes, (jbyte *)buf);
    return readBytes;
}

static jlong skip_gzip_header(JNIEnv *env, ArchiveHandle *archive, jlong start_offset) {
    unsigned char header[10];
    int readBytes = read_from_source(env, archive, start_offset, header, 10);
    if (readBytes < 10) return -1;
    if (header[0] != 0x1F || header[1] != 0x8B || header[2] != 0x08) {
        return -1;
    }
    unsigned char flags = header[3];
    jlong offset = start_offset + 10;
    if (flags & 4) {
        unsigned char xlen_buf[2];
        readBytes = read_from_source(env, archive, offset, xlen_buf, 2);
        if (readBytes < 2) return -1;
        int xlen = xlen_buf[0] | (xlen_buf[1] << 8);
        offset += 2 + xlen;
    }
    if (flags & 8) {
        unsigned char c;
        do {
            readBytes = read_from_source(env, archive, offset, &c, 1);
            if (readBytes < 1) return -1;
            offset++;
        } while (c != 0);
    }
    if (flags & 16) {
        unsigned char c;
        do {
            readBytes = read_from_source(env, archive, offset, &c, 1);
            if (readBytes < 1) return -1;
            offset++;
        } while (c != 0);
    }
    if (flags & 2) {
        offset += 2;
    }
    return offset;
}

static SRes read_decompressed_bytes(JNIEnv *env, ArchiveHandle *archive, GzipDecoder *decoder, void *out_buf, size_t out_len, size_t *processed, jlong raw_deflate_start) {
    size_t written = 0;
    *processed = 0;
    while (written < out_len) {
        if (decoder->stream.avail_in == 0) {
            jlong src_offset = raw_deflate_start + decoder->current_source_offset;
            int r = read_from_source(env, archive, src_offset, decoder->in_buf, sizeof(decoder->in_buf));
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

static SRes skip_decompressed_bytes(JNIEnv *env, ArchiveHandle *archive, GzipDecoder *decoder, size_t skip_len, jlong raw_deflate_start) {
    unsigned char dummy[4096];
    size_t remaining = skip_len;
    while (remaining > 0) {
        size_t to_read = (remaining < sizeof(dummy)) ? remaining : sizeof(dummy);
        size_t processed = 0;
        SRes res = read_decompressed_bytes(env, archive, decoder, dummy, to_read, &processed, raw_deflate_start);
        if (res != SZ_OK) return res;
        if (processed == 0) return SZ_ERROR_DATA;
        remaining -= processed;
    }
    return SZ_OK;
}

static SRes scan_tar_gz_entries(JNIEnv *env, ArchiveHandle *archive, jlong raw_deflate_start) {
    GzipDecoder decoder;
    SRes res = init_gzip_decoder(env, archive, &decoder);
    if (res != SZ_OK) return res;
    int capacity = 16;
    archive->tarGzEntries = (TarGzEntry *)malloc(capacity * sizeof(TarGzEntry));
    archive->tarGzCount = 0;
    TarHeader header;
    int consecutive_zero_blocks = 0;
    while (1) {
        size_t processed = 0;
        jlong header_offset = decoder.total_decompressed;
        res = read_decompressed_bytes(env, archive, &decoder, &header, 512, &processed, raw_deflate_start);
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
        jlong file_size = (jlong)strtol(size_str, NULL, 8);
        jboolean is_dir = (header.typeflag == '5') ? JNI_TRUE : JNI_FALSE;
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
        jlong skip_size = (file_size + 511) & ~511;
        res = skip_decompressed_bytes(env, archive, &decoder, (size_t)skip_size, raw_deflate_start);
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

static int validate_tar_header_checksum(const TarHeader *header) {
    const unsigned char *bytes = (const unsigned char *)header;
    unsigned int sum = 0;
    for (int i = 0; i < 512; i++) {
        if (i >= 148 && i < 156) {
            sum += ' ';
        } else {
            sum += bytes[i];
        }
    }
    char chksum_str[9] = {0};
    memcpy(chksum_str, header->chksum, 8);
    unsigned int expected_sum = (unsigned int)strtol(chksum_str, NULL, 8);
    if (expected_sum == 0) return 0;
    return (sum == expected_sum);
}

static SRes init_bzip2_decoder(JNIEnv *env, ArchiveHandle *archive, Bzip2Decoder *decoder) {
    memset(decoder, 0, sizeof(Bzip2Decoder));
    decoder->stream.bzalloc = NULL;
    decoder->stream.bzfree = NULL;
    decoder->stream.opaque = NULL;
    int status = BZ2_bzDecompressInit(&decoder->stream, 0, 0);
    if (status != BZ_OK) {
        return SZ_ERROR_MEM;
    }
    decoder->is_init = 1;
    decoder->current_source_offset = 0;
    decoder->total_decompressed = 0;
    return SZ_OK;
}

static void free_bzip2_decoder(Bzip2Decoder *decoder) {
    if (decoder->is_init) {
        BZ2_bzDecompressEnd(&decoder->stream);
        decoder->is_init = 0;
    }
}

static SRes read_bzip2_decompressed_bytes(JNIEnv *env, ArchiveHandle *archive, Bzip2Decoder *decoder, void *out_buf, size_t out_len, size_t *processed, jlong raw_bzip2_start) {
    size_t written = 0;
    *processed = 0;
    if (decoder->is_stream_end) {
        return SZ_OK;
    }
    while (written < out_len) {
        if (decoder->stream.avail_in == 0) {
            jlong src_offset = raw_bzip2_start + decoder->current_source_offset;
            int r = read_from_source(env, archive, src_offset, decoder->in_buf, sizeof(decoder->in_buf));
            if (r < 0) return SZ_ERROR_READ;
            if (r == 0) {
                break;
            }
            decoder->stream.next_in = (char *)decoder->in_buf;
            decoder->stream.avail_in = (unsigned int)r;
            decoder->current_source_offset += r;
        }
        decoder->stream.next_out = (char *)out_buf + written;
        decoder->stream.avail_out = (unsigned int)(out_len - written);
        int status = BZ2_bzDecompress(&decoder->stream);
        size_t decompressed_this_time = (out_len - written) - decoder->stream.avail_out;
        written += decompressed_this_time;
        decoder->total_decompressed += decompressed_this_time;
        if (status == BZ_STREAM_END) {
            decoder->is_stream_end = 1;
            break;
        }
        if (status != BZ_OK) {
            return SZ_ERROR_DATA;
        }
    }
    *processed = written;
    return SZ_OK;
}

static SRes skip_bzip2_decompressed_bytes(JNIEnv *env, ArchiveHandle *archive, Bzip2Decoder *decoder, size_t skip_len, jlong raw_bzip2_start) {
    unsigned char dummy[4096];
    size_t remaining = skip_len;
    while (remaining > 0) {
        size_t to_read = (remaining < sizeof(dummy)) ? remaining : sizeof(dummy);
        size_t processed = 0;
        SRes res = read_bzip2_decompressed_bytes(env, archive, decoder, dummy, to_read, &processed, raw_bzip2_start);
        if (res != SZ_OK) return res;
        if (processed == 0) return SZ_ERROR_DATA;
        remaining -= processed;
    }
    return SZ_OK;
}

static SRes scan_tar_bz2_entries(JNIEnv *env, ArchiveHandle *archive, jlong raw_bzip2_start) {
    Bzip2Decoder decoder;
    SRes res = init_bzip2_decoder(env, archive, &decoder);
    if (res != SZ_OK) return res;
    int capacity = 16;
    archive->tarGzEntries = (TarGzEntry *)malloc(capacity * sizeof(TarGzEntry));
    archive->tarGzCount = 0;
    TarHeader header;
    int consecutive_zero_blocks = 0;
    while (1) {
        size_t processed = 0;
        jlong header_offset = decoder.total_decompressed;
        res = read_bzip2_decompressed_bytes(env, archive, &decoder, &header, 512, &processed, raw_bzip2_start);
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
        jlong file_size = (jlong)strtol(size_str, NULL, 8);
        jboolean is_dir = (header.typeflag == '5') ? JNI_TRUE : JNI_FALSE;
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
        jlong skip_size = (file_size + 511) & ~511;
        res = skip_bzip2_decompressed_bytes(env, archive, &decoder, (size_t)skip_size, raw_bzip2_start);
        if (res != SZ_OK) goto error;
    }
    free_bzip2_decoder(&decoder);
    return SZ_OK;
error:
    free_bzip2_decoder(&decoder);
    for (int i = 0; i < archive->tarGzCount; i++) {
        free(archive->tarGzEntries[i].name);
    }
    free(archive->tarGzEntries);
    archive->tarGzEntries = NULL;
    archive->tarGzCount = 0;
    return res;
}

// JNI function to open a 7z or ZIP archive from SeekableSource with auto format detection
JNIEXPORT jlong JNICALL Java_com_sorrowblue_kioarch_KioArchJni_openArchive(
    JNIEnv *env, jobject obj, jobject kotlinSource
) {
    ArchiveHandle *archive;
    jclass sourceClass;
    jbyteArray initialBuffer;
    jint bytesRead;
    jbyte magic[8] = {0};
    int isZip = 0;
    int is7z = 0;
    int isTarGz = 0;
    int isBzip2 = 0;

    CrcGenerateTable();

    archive = (ArchiveHandle *)malloc(sizeof(ArchiveHandle));
    if (archive == NULL) return 0;

    memset(archive, 0, sizeof(ArchiveHandle));

    // Retrieve and cache method IDs of SeekableSource
    sourceClass = (*env)->GetObjectClass(env, kotlinSource);
    archive->inStream.readMethod = (*env)->GetMethodID(env, sourceClass, "read", "([BII)I");
    archive->inStream.seekMethod = (*env)->GetMethodID(env, sourceClass, "seek", "(J)V");
    archive->inStream.positionMethod = (*env)->GetMethodID(env, sourceClass, "position", "()J");
    archive->inStream.lengthMethod = (*env)->GetMethodID(env, sourceClass, "length", "()J");

    if (archive->inStream.readMethod == NULL || archive->inStream.seekMethod == NULL ||
        archive->inStream.positionMethod == NULL || archive->inStream.lengthMethod == NULL) {
        free(archive);
        return 0;
    }

    // Check if kotlinSource implements DirectSeekableSource
    jclass directSourceClass = (*env)->FindClass(env, "com/sorrowblue/kioarch/DirectSeekableSource");
    if (directSourceClass != NULL && (*env)->IsInstanceOf(env, kotlinSource, directSourceClass)) {
        archive->inStream.readDirectMethod = (*env)->GetMethodID(env, directSourceClass, "read", "(Ljava/nio/ByteBuffer;)I");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            archive->inStream.readDirectMethod = NULL;
        }
    } else {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        archive->inStream.readDirectMethod = NULL;
    }

    // Capture global reference for SeekableSource to prevent GC while archive is open
    archive->inStream.kotlinSource = (*env)->NewGlobalRef(env, kotlinSource);
    archive->inStream.env = env;

    // Detect format using magic bytes
    initialBuffer = (*env)->NewByteArray(env, 8);
    (*env)->CallVoidMethod(env, kotlinSource, archive->inStream.seekMethod, (jlong)0);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
        (*env)->DeleteLocalRef(env, initialBuffer);
        free(archive);
        return 0;
    }

    bytesRead = (*env)->CallIntMethod(env, kotlinSource, archive->inStream.readMethod, initialBuffer, 0, 8);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
        (*env)->DeleteLocalRef(env, initialBuffer);
        free(archive);
        return 0;
    }

    if (bytesRead >= 4) {
        (*env)->GetByteArrayRegion(env, initialBuffer, 0, bytesRead, magic);
        // Check 7z Magic: 37 7A BC AF 27 1C
        if (bytesRead >= 6 &&
            magic[0] == 0x37 && magic[1] == 0x7A && magic[2] == (jbyte)0xBC &&
            magic[3] == (jbyte)0xAF && magic[4] == 0x27 && magic[5] == 0x1C) {
            is7z = 1;
        }
        // Check Zip Magic: PK (50 4B)
        else if (magic[0] == 0x50 && magic[1] == 0x4B) {
            isZip = 1;
        }
        // Check Gzip Magic: 1F 8B
        else if (magic[0] == 0x1F && magic[1] == (jbyte)0x8B) {
            isTarGz = 1;
        }
        // Check Bzip2 Magic: BZh (42 5A 68)
        else if (bytesRead >= 3 && magic[0] == 0x42 && magic[1] == 0x5A && magic[2] == 0x68) {
            isBzip2 = 1;
        }
    }
    (*env)->DeleteLocalRef(env, initialBuffer);

    // Reset stream position to 0 after magic detection
    (*env)->CallVoidMethod(env, kotlinSource, archive->inStream.seekMethod, (jlong)0);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
        free(archive);
        return 0;
    }

    if (is7z) {
        SRes res;
        archive->type = ARCHIVE_TYPE_7Z;
        archive->alloc.Alloc = SzAlloc;
        archive->alloc.Free = SzFree;
        archive->allocTemp.Alloc = SzAllocTemp;
        archive->allocTemp.Free = SzFreeTemp;

        archive->inStream.vt.Read = JniInStream_Read;
        archive->inStream.vt.Seek = JniInStream_Seek;

        // Allocate input buffer for LookToRead2 stream
        archive->lookStream.buf = (Byte *)archive->alloc.Alloc(&archive->alloc, kInputBufSize);
        if (archive->lookStream.buf == NULL) {
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }
        archive->lookStream.bufSize = kInputBufSize;
        archive->lookStream.realStream = &archive->inStream.vt;
        LookToRead2_CreateVTable(&archive->lookStream, False);
        LookToRead2_INIT(&archive->lookStream);

        // Initialize 7z database
        SzArEx_Init(&archive->db);
        archive->isDbExInit = 1;

        res = SzArEx_Open(&archive->db, &archive->lookStream.vt, &archive->alloc, &archive->allocTemp);
        if (res != SZ_OK) {
            throw_7z_error(env, res, "open archive");
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            archive->alloc.Free(&archive->alloc, archive->lookStream.buf);
            SzArEx_Free(&archive->db, &archive->alloc);
            free(archive);
            return 0;
        }
    } else if (isZip) {
        jlong totalLen;
        mz_bool zipRes;

        archive->type = ARCHIVE_TYPE_ZIP;
        totalLen = (*env)->CallLongMethod(env, kotlinSource, archive->inStream.lengthMethod);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }

        memset(&archive->zipArchive, 0, sizeof(mz_zip_archive));
        archive->zipArchive.m_pRead = Miniz_Read_Callback;
        archive->zipArchive.m_pIO_opaque = archive;

        zipRes = mz_zip_reader_init(&archive->zipArchive, (mz_uint64)totalLen, 0);
        if (!zipRes) {
            throw_miniz_error(env, mz_zip_get_last_error(&archive->zipArchive), "open archive");
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }
        archive->isZipInit = 1;
    } else if (isTarGz) {
        archive->type = ARCHIVE_TYPE_TARGZ;
        jlong raw_deflate_start = skip_gzip_header(env, archive, 0);
        if (raw_deflate_start < 0) {
            throw_archive_exception(env, "com/sorrowblue/kioarch/ArchiveCorruptedException", "Invalid Gzip header");
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }
        SRes res = scan_tar_gz_entries(env, archive, raw_deflate_start);
        if (res != SZ_OK) {
            throw_7z_error(env, res, "scan tar.gz entries");
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }
    } else if (isBzip2) {
        archive->type = ARCHIVE_TYPE_BZIP2;
        jlong resetPos = 0;
        (*env)->CallVoidMethod(env, kotlinSource, archive->inStream.seekMethod, resetPos);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }

        Bzip2Decoder decoder;
        SRes initRes = init_bzip2_decoder(env, archive, &decoder);
        if (initRes == SZ_OK) {
            TarHeader header;
            size_t processed = 0;
            SRes readRes = read_bzip2_decompressed_bytes(env, archive, &decoder, &header, 512, &processed, 0);
            free_bzip2_decoder(&decoder);

            // Reset stream again before scanning or returning
            (*env)->CallVoidMethod(env, kotlinSource, archive->inStream.seekMethod, resetPos);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
                free(archive);
                return 0;
            }

            if (readRes == SZ_OK && processed >= 512 && validate_tar_header_checksum(&header)) {
                archive->type = ARCHIVE_TYPE_TARBZ2;
                SRes scanRes = scan_tar_bz2_entries(env, archive, 0);
                if (scanRes != SZ_OK) {
                    throw_7z_error(env, scanRes, "scan tar.bz2 entries");
                    (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
                    free(archive);
                    return 0;
                }
            } else {
                archive->type = ARCHIVE_TYPE_BZIP2;
                archive->tarGzCount = 1;
                archive->tarGzEntries = (TarGzEntry *)malloc(sizeof(TarGzEntry));
                if (archive->tarGzEntries != NULL) {
                    archive->tarGzEntries[0].name = strdup("extracted_data");
                    archive->tarGzEntries[0].size = -1;
                    archive->tarGzEntries[0].is_dir = JNI_FALSE;
                    archive->tarGzEntries[0].header_offset = 0;
                    archive->tarGzEntries[0].data_offset = 0;
                } else {
                    (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
                    free(archive);
                    return 0;
                }
            }
        } else {
            throw_7z_error(env, initRes, "init bzip2 decoder");
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }
    } else {
        // Unsupported format
        throw_archive_exception(env, "com/sorrowblue/kioarch/ArchiveInvalidException", "Unsupported archive format (only 7z, ZIP, tar.gz, bz2 and tar.bz2 are supported)");
        (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
        free(archive);
        return 0;
    }

    return (jlong)archive;
}

// JNI function to close the open archive
JNIEXPORT void JNICALL Java_com_sorrowblue_kioarch_KioArchJni_closeArchive(
    JNIEnv *env, jobject obj, jlong handle
) {
    ArchiveHandle *archive;
    if (handle == 0) return;
    archive = (ArchiveHandle *)handle;

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
    } else if (archive->type == ARCHIVE_TYPE_TARGZ || archive->type == ARCHIVE_TYPE_TARBZ2 || archive->type == ARCHIVE_TYPE_BZIP2) {
        if (archive->tarGzEntries != NULL) {
            for (int i = 0; i < archive->tarGzCount; i++) {
                free(archive->tarGzEntries[i].name);
            }
            free(archive->tarGzEntries);
        }
    }

    if (archive->inStream.kotlinSource != NULL) {
        (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
    }

    if (archive->inStream.tempBuffer != NULL) {
        (*env)->DeleteGlobalRef(env, archive->inStream.tempBuffer);
    }

    free(archive);
}

// Helper function to check if a null-terminated string is valid UTF-8
static int is_valid_utf8(const char *str) {
    if (!str) return 0;
    const unsigned char *bytes = (const unsigned char *)str;
    while (*bytes) {
        if (bytes[0] <= 0x7F) {
            bytes += 1;
        } else if ((bytes[0] & 0xE0) == 0xC0) {
            if ((bytes[1] & 0xC0) != 0x80) return 0;
            bytes += 2;
        } else if ((bytes[0] & 0xF0) == 0xE0) {
            if ((bytes[1] & 0xC0) != 0x80 || (bytes[2] & 0xC0) != 0x80) return 0;
            bytes += 3;
        } else if ((bytes[0] & 0xF8) == 0xF0) {
            if ((bytes[1] & 0xC0) != 0x80 || (bytes[2] & 0xC0) != 0x80 || (bytes[3] & 0xC0) != 0x80) return 0;
            bytes += 4;
        } else {
            return 0;
        }
    }
    return 1;
}

// Safely creates a jstring using java.lang.String(byte[], String) constructor
static jstring create_jstring_from_bytes(JNIEnv *env, const char *src, int is_utf8) {
    if (src == NULL) return NULL;
    size_t len = strlen(src);
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)len);
    if (bytes == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)len, (const jbyte *)src);

    jstring charsetName = (*env)->NewStringUTF(env, is_utf8 ? "UTF-8" : "MS932");
    if (charsetName == NULL) {
        (*env)->DeleteLocalRef(env, bytes);
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        (*env)->DeleteLocalRef(env, bytes);
        (*env)->DeleteLocalRef(env, charsetName);
        return NULL;
    }

    jmethodID stringCtor = (*env)->GetMethodID(env, stringClass, "<init>", "([BLjava/lang/String;)V");
    if (stringCtor == NULL) {
        (*env)->DeleteLocalRef(env, bytes);
        (*env)->DeleteLocalRef(env, charsetName);
        return NULL;
    }

    jstring jstr = (*env)->NewObject(env, stringClass, stringCtor, bytes, charsetName);
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, charsetName);
    return jstr;
}

// JNI function to retrieve all entry metadata at once (Bulk Metadata Array)
JNIEXPORT jobject JNICALL Java_com_sorrowblue_kioarch_KioArchJni_getEntries(
    JNIEnv *env, jobject obj, jlong handle
) {
    ArchiveHandle *archive;
    jint count = 0;
    jint *indices = NULL;
    jlong *sizes = NULL;
    jboolean *isDirs = NULL;
    jlong *crcs = NULL;
    jclass stringClass;
    jobjectArray jnames = NULL;
    jintArray jindices = NULL;
    jlongArray jsizes = NULL;
    jbooleanArray jisDirs = NULL;
    jlongArray jcrcs = NULL;
    jclass entriesClass;
    jmethodID ctor;

    if (handle == 0) return NULL;
    archive = (ArchiveHandle *)handle;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        count = (jint)archive->db.NumFiles;
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        count = (jint)mz_zip_reader_get_num_files(&archive->zipArchive);
    } else if (archive->type == ARCHIVE_TYPE_TARGZ || archive->type == ARCHIVE_TYPE_TARBZ2 || archive->type == ARCHIVE_TYPE_BZIP2) {
        count = (jint)archive->tarGzCount;
    }

    if (count < 0) count = 0;
// (中略 - プレースホルダーのままでなく元の処理と新規処理の展開)
    indices = (jint *)malloc(count * sizeof(jint));
    sizes = (jlong *)malloc(count * sizeof(jlong));
    isDirs = (jboolean *)malloc(count * sizeof(jboolean));
    crcs = (jlong *)malloc(count * sizeof(jlong));

    if (count > 0 && (indices == NULL || sizes == NULL || isDirs == NULL || crcs == NULL)) {
        free(indices); free(sizes); free(isDirs); free(crcs);
        jclass exClass = (*env)->FindClass(env, "com/sorrowblue/kioarch/ArchiveOutOfMemoryException");
        if (exClass != NULL) {
            (*env)->ThrowNew(env, exClass, "Out of memory allocating metadata arrays");
        }
        return NULL;
    }

    stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        free(indices); free(sizes); free(isDirs); free(crcs);
        return NULL;
    }

    jnames = (*env)->NewObjectArray(env, count, stringClass, NULL);
    if (jnames == NULL) {
        free(indices); free(sizes); free(isDirs); free(crcs);
        return NULL;
    }

    for (jint i = 0; i < count; i++) {
        indices[i] = i;
        jstring jname = NULL;

        if (archive->type == ARCHIVE_TYPE_7Z) {
            size_t nameLen = SzArEx_GetFileNameUtf16(&archive->db, (size_t)i, NULL);
            if (nameLen > 0) {
                UInt16 *nameBuf = (UInt16 *)malloc(nameLen * sizeof(UInt16));
                if (nameBuf != NULL) {
                    SzArEx_GetFileNameUtf16(&archive->db, (size_t)i, nameBuf);
                    jname = (*env)->NewString(env, (const jchar *)nameBuf, (jsize)(nameLen - 1));
                    free(nameBuf);
                }
            }
            sizes[i] = (jlong)SzArEx_GetFileSize(&archive->db, (size_t)i);
            isDirs[i] = (jboolean)SzArEx_IsDir(&archive->db, (size_t)i);
            crcs[i] = (jlong)(SzBitWithVals_Check(&archive->db.CRCs, (size_t)i) ? archive->db.CRCs.Vals[i] : 0);
        } else if (archive->type == ARCHIVE_TYPE_ZIP) {
            mz_zip_archive_file_stat stat;
            archive->inStream.env = env; // refresh environment
            if (mz_zip_reader_file_stat(&archive->zipArchive, (mz_uint)i, &stat)) {
                int is_utf8 = ((stat.m_bit_flag & 0x0800) != 0) || is_valid_utf8(stat.m_filename);
                jname = create_jstring_from_bytes(env, stat.m_filename, is_utf8);
                sizes[i] = (jlong)stat.m_uncomp_size;
                isDirs[i] = (jboolean)stat.m_is_directory;
                crcs[i] = (jlong)stat.m_crc32;
            } else {
                jname = NULL;
                sizes[i] = 0;
                isDirs[i] = JNI_FALSE;
                crcs[i] = 0;
            }
        } else if (archive->type == ARCHIVE_TYPE_TARGZ || archive->type == ARCHIVE_TYPE_TARBZ2 || archive->type == ARCHIVE_TYPE_BZIP2) {
            TarGzEntry *entry = &archive->tarGzEntries[i];
            jname = create_jstring_from_bytes(env, entry->name, 1);
            sizes[i] = entry->size;
            isDirs[i] = entry->is_dir;
            crcs[i] = 0;
        }

        if (jname == NULL) {
            jname = (*env)->NewStringUTF(env, "");
        }

        (*env)->SetObjectArrayElement(env, jnames, i, jname);
        (*env)->DeleteLocalRef(env, jname);
    }

    jindices = (*env)->NewIntArray(env, count);
    jsizes = (*env)->NewLongArray(env, count);
    jisDirs = (*env)->NewBooleanArray(env, count);
    jcrcs = (*env)->NewLongArray(env, count);

    if (jindices != NULL && jsizes != NULL && jisDirs != NULL && jcrcs != NULL) {
        (*env)->SetIntArrayRegion(env, jindices, 0, count, indices);
        (*env)->SetLongArrayRegion(env, jsizes, 0, count, sizes);
        (*env)->SetBooleanArrayRegion(env, jisDirs, 0, count, (const jboolean *)isDirs);
        (*env)->SetLongArrayRegion(env, jcrcs, 0, count, crcs);
    }

    free(indices);
    free(sizes);
    free(isDirs);
    free(crcs);

    if (jindices == NULL || jsizes == NULL || jisDirs == NULL || jcrcs == NULL) {
        return NULL;
    }

    entriesClass = (*env)->FindClass(env, "com/sorrowblue/kioarch/JniEntries");
    if (entriesClass == NULL) return NULL;

    ctor = (*env)->GetMethodID(env, entriesClass, "<init>", "([I[Ljava/lang/String;[J[Z[J)V");
    if (ctor == NULL) return NULL;

    return (*env)->NewObject(env, entriesClass, ctor, jindices, jnames, jsizes, jisDirs, jcrcs);
}

// JNI function to extract an entry and stream it to kotlinx.io.Sink
JNIEXPORT jboolean JNICALL Java_com_sorrowblue_kioarch_KioArchJni_extractEntry(
    JNIEnv *env, jobject obj, jlong handle, jint index, jobject kotlinSink
) {
    ArchiveHandle *archive;
    if (handle == 0) return JNI_FALSE;
    archive = (ArchiveHandle *)handle;

    // Refresh thread-bound JNIEnv pointer before executing nested JNI calls
    archive->inStream.env = env;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        UInt32 blockIndex = 0xFFFFFFFF;
        Byte *outBuffer = NULL;
        size_t outBufferSize = 0;
        size_t offset = 0;
        size_t outSizeProcessed = 0;
        SRes res;

        if (index < 0 || index >= (jint)archive->db.NumFiles) return JNI_FALSE;

        res = SzArEx_Extract(
            &archive->db, &archive->lookStream.vt, (UInt32)index,
            &blockIndex, &outBuffer, &outBufferSize,
            &offset, &outSizeProcessed,
            &archive->alloc, &archive->allocTemp
        );

        if (res != SZ_OK) {
            throw_7z_error(env, res, "extract entry");
            if (outBuffer != NULL) {
                archive->alloc.Free(&archive->alloc, outBuffer);
            }
            return JNI_FALSE;
        }

        if (outSizeProcessed > 0 && outBuffer != NULL) {
            jclass sinkClass;
            jmethodID writeMethod;

            sinkClass = (*env)->GetObjectClass(env, kotlinSink);
            writeMethod = (*env)->GetMethodID(env, sinkClass, "write", "([BII)V");
            if (writeMethod == NULL) {
                archive->alloc.Free(&archive->alloc, outBuffer);
                return JNI_FALSE;
            }

            {
                size_t bytesWritten = 0;
                size_t chunkSize = 65536;
                jbyteArray chunkArray = (*env)->NewByteArray(env, (jsize)chunkSize);

                while (bytesWritten < outSizeProcessed) {
                    size_t remaining = outSizeProcessed - bytesWritten;
                    size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;

                    (*env)->SetByteArrayRegion(env, chunkArray, 0, (jsize)currentChunk, (jbyte *)(outBuffer + offset + bytesWritten));
                    (*env)->CallVoidMethod(env, kotlinSink, writeMethod, chunkArray, 0, (jint)currentChunk);

                    if ((*env)->ExceptionCheck(env)) {
                        break;
                    }

                    bytesWritten += currentChunk;
                }

                (*env)->DeleteLocalRef(env, chunkArray);
            }
        }

        if (outBuffer != NULL) {
            archive->alloc.Free(&archive->alloc, outBuffer);
        }
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        ZipExtractContext ctx;
        jclass sinkClass;
        mz_bool success;

        if (index < 0 || index >= (jint)mz_zip_reader_get_num_files(&archive->zipArchive)) return JNI_FALSE;

        ctx.env = env;
        ctx.kotlinSink = kotlinSink;

        sinkClass = (*env)->GetObjectClass(env, kotlinSink);
        ctx.writeMethod = (*env)->GetMethodID(env, sinkClass, "write", "([BII)V");
        if (ctx.writeMethod == NULL) return JNI_FALSE;

        // Extract using miniz's callback-based decompresor
        success = mz_zip_reader_extract_to_callback(&archive->zipArchive, (mz_uint)index, Miniz_Write_Callback, &ctx, 0);
        if (!success) {
            throw_miniz_error(env, mz_zip_get_last_error(&archive->zipArchive), "extract entry");
            return JNI_FALSE;
        }
    } else if (archive->type == ARCHIVE_TYPE_TARGZ) {
        if (index < 0 || index >= archive->tarGzCount) return JNI_FALSE;
        TarGzEntry *entry = &archive->tarGzEntries[index];
        jlong raw_deflate_start = skip_gzip_header(env, archive, 0);
        if (raw_deflate_start < 0) return JNI_FALSE;

        GzipDecoder decoder;
        SRes res = init_gzip_decoder(env, archive, &decoder);
        if (res != SZ_OK) return JNI_FALSE;

        res = skip_decompressed_bytes(env, archive, &decoder, (size_t)entry->data_offset, raw_deflate_start);
        if (res != SZ_OK) {
            free_gzip_decoder(&decoder);
            return JNI_FALSE;
        }

        jclass sinkClass = (*env)->GetObjectClass(env, kotlinSink);
        jmethodID writeMethod = (*env)->GetMethodID(env, sinkClass, "write", "([BII)V");
        if (writeMethod == NULL) {
            free_gzip_decoder(&decoder);
            return JNI_FALSE;
        }

        size_t bytesWritten = 0;
        size_t chunkSize = 65536;
        jbyteArray chunkArray = (*env)->NewByteArray(env, (jsize)chunkSize);
        if (chunkArray == NULL) {
            free_gzip_decoder(&decoder);
            return JNI_FALSE;
        }

        unsigned char *outBuffer = (unsigned char *)malloc(chunkSize);
        if (outBuffer == NULL) {
            (*env)->DeleteLocalRef(env, chunkArray);
            free_gzip_decoder(&decoder);
            return JNI_FALSE;
        }

        while (bytesWritten < (size_t)entry->size) {
            size_t remaining = (size_t)entry->size - bytesWritten;
            size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;
            size_t processed = 0;

            res = read_decompressed_bytes(env, archive, &decoder, outBuffer, currentChunk, &processed, raw_deflate_start);
            if (res != SZ_OK || processed == 0) {
                break;
            }

            (*env)->SetByteArrayRegion(env, chunkArray, 0, (jsize)processed, (jbyte *)outBuffer);
            (*env)->CallVoidMethod(env, kotlinSink, writeMethod, chunkArray, 0, (jint)processed);

            if ((*env)->ExceptionCheck(env)) {
                break;
            }

            bytesWritten += processed;
        }

        free(outBuffer);
        (*env)->DeleteLocalRef(env, chunkArray);
        free_gzip_decoder(&decoder);

        if (res != SZ_OK || bytesWritten < (size_t)entry->size) {
            return JNI_FALSE;
        }
    } else if (archive->type == ARCHIVE_TYPE_TARBZ2 || archive->type == ARCHIVE_TYPE_BZIP2) {
        if (index < 0 || index >= archive->tarGzCount) return JNI_FALSE;
        TarGzEntry *entry = &archive->tarGzEntries[index];

        Bzip2Decoder decoder;
        SRes res = init_bzip2_decoder(env, archive, &decoder);
        if (res != SZ_OK) return JNI_FALSE;

        if (archive->type == ARCHIVE_TYPE_TARBZ2) {
            res = skip_bzip2_decompressed_bytes(env, archive, &decoder, (size_t)entry->data_offset, 0);
            if (res != SZ_OK) {
                free_bzip2_decoder(&decoder);
                return JNI_FALSE;
            }
        }

        jclass sinkClass = (*env)->GetObjectClass(env, kotlinSink);
        jmethodID writeMethod = (*env)->GetMethodID(env, sinkClass, "write", "([BII)V");
        if (writeMethod == NULL) {
            free_bzip2_decoder(&decoder);
            return JNI_FALSE;
        }

        size_t bytesWritten = 0;
        size_t chunkSize = 65536;
        jbyteArray chunkArray = (*env)->NewByteArray(env, (jsize)chunkSize);
        if (chunkArray == NULL) {
            free_bzip2_decoder(&decoder);
            return JNI_FALSE;
        }

        unsigned char *outBuffer = (unsigned char *)malloc(chunkSize);
        if (outBuffer == NULL) {
            (*env)->DeleteLocalRef(env, chunkArray);
            free_bzip2_decoder(&decoder);
            return JNI_FALSE;
        }

        jlong targetSize = entry->size;
        int isInfinite = (targetSize < 0);

        while (isInfinite || (bytesWritten < (size_t)targetSize)) {
            size_t remaining = isInfinite ? chunkSize : ((size_t)targetSize - bytesWritten);
            size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;
            size_t processed = 0;

            res = read_bzip2_decompressed_bytes(env, archive, &decoder, outBuffer, currentChunk, &processed, 0);
            if (res != SZ_OK || processed == 0) {
                break;
            }

            (*env)->SetByteArrayRegion(env, chunkArray, 0, (jsize)processed, (jbyte *)outBuffer);
            (*env)->CallVoidMethod(env, kotlinSink, writeMethod, chunkArray, 0, (jint)processed);

            if ((*env)->ExceptionCheck(env)) {
                break;
            }

            bytesWritten += processed;
        }

        free(outBuffer);
        (*env)->DeleteLocalRef(env, chunkArray);
        free_bzip2_decoder(&decoder);

        if (res != SZ_OK || (!isInfinite && bytesWritten < (size_t)targetSize)) {
            return JNI_FALSE;
        }
    }

    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

// Context structure passed to miniz extraction callback for Direct ByteBuffer
typedef struct {
    JNIEnv *env;
    jobject callback;
    jmethodID onDataMethod;
} ZipExtractContextDirect;

// Custom write callback for miniz streaming decompressed chunk into DirectExtractCallback
static size_t Miniz_Write_Callback_Direct(void *pOpaque, mz_uint64 file_ofs, const void *pBuf, size_t n) {
    ZipExtractContextDirect *ctx = (ZipExtractContextDirect *)pOpaque;
    JNIEnv *env = ctx->env;

    if (n == 0) return 0;

    jobject directBuffer = (*env)->NewDirectByteBuffer(env, (void *)pBuf, (jlong)n);
    if (directBuffer == NULL) return 0;

    (*env)->CallVoidMethod(env, ctx->callback, ctx->onDataMethod, directBuffer);
    (*env)->DeleteLocalRef(env, directBuffer);

    if ((*env)->ExceptionCheck(env)) {
        return 0; // abort extraction on exception
    }

    return n;
}

// JNI function to extract an entry using zero-copy Direct ByteBuffer
JNIEXPORT jboolean JNICALL Java_com_sorrowblue_kioarch_KioArchJni_extractEntryDirect(
    JNIEnv *env, jobject obj, jlong handle, jint index, jobject callback
) {
    ArchiveHandle *archive;
    if (handle == 0) return JNI_FALSE;
    archive = (ArchiveHandle *)handle;

    // Refresh thread-bound JNIEnv pointer before executing nested JNI calls
    archive->inStream.env = env;

    jclass callbackClass = (*env)->GetObjectClass(env, callback);
    jmethodID onDataMethod = (*env)->GetMethodID(env, callbackClass, "onData", "(Ljava/nio/ByteBuffer;)V");
    if (onDataMethod == NULL) return JNI_FALSE;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        UInt32 blockIndex = 0xFFFFFFFF;
        Byte *outBuffer = NULL;
        size_t outBufferSize = 0;
        size_t offset = 0;
        size_t outSizeProcessed = 0;
        SRes res;

        if (index < 0 || index >= (jint)archive->db.NumFiles) return JNI_FALSE;

        res = SzArEx_Extract(
            &archive->db, &archive->lookStream.vt, (UInt32)index,
            &blockIndex, &outBuffer, &outBufferSize,
            &offset, &outSizeProcessed,
            &archive->alloc, &archive->allocTemp
        );

        if (res != SZ_OK) {
            throw_7z_error(env, res, "extract entry");
            if (outBuffer != NULL) {
                archive->alloc.Free(&archive->alloc, outBuffer);
            }
            return JNI_FALSE;
        }

        if (outSizeProcessed > 0 && outBuffer != NULL) {
            jobject directBuffer = (*env)->NewDirectByteBuffer(env, (void *)(outBuffer + offset), (jlong)outSizeProcessed);
            if (directBuffer != NULL) {
                (*env)->CallVoidMethod(env, callback, onDataMethod, directBuffer);
                (*env)->DeleteLocalRef(env, directBuffer);
            }
        }

        if (outBuffer != NULL) {
            archive->alloc.Free(&archive->alloc, outBuffer);
        }
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        ZipExtractContextDirect ctx;
        mz_bool success;

        if (index < 0 || index >= (jint)mz_zip_reader_get_num_files(&archive->zipArchive)) return JNI_FALSE;

        ctx.env = env;
        ctx.callback = callback;
        ctx.onDataMethod = onDataMethod;

        // Extract using miniz's callback-based decompresor with Direct Write Callback
        success = mz_zip_reader_extract_to_callback(&archive->zipArchive, (mz_uint)index, Miniz_Write_Callback_Direct, &ctx, 0);
        if (!success) {
            throw_miniz_error(env, mz_zip_get_last_error(&archive->zipArchive), "extract entry");
            return JNI_FALSE;
        }
    } else if (archive->type == ARCHIVE_TYPE_TARGZ) {
        if (index < 0 || index >= archive->tarGzCount) return JNI_FALSE;
        TarGzEntry *entry = &archive->tarGzEntries[index];
        jlong raw_deflate_start = skip_gzip_header(env, archive, 0);
        if (raw_deflate_start < 0) return JNI_FALSE;

        GzipDecoder decoder;
        SRes res = init_gzip_decoder(env, archive, &decoder);
        if (res != SZ_OK) return JNI_FALSE;

        res = skip_decompressed_bytes(env, archive, &decoder, (size_t)entry->data_offset, raw_deflate_start);
        if (res != SZ_OK) {
            free_gzip_decoder(&decoder);
            return JNI_FALSE;
        }

        size_t bytesWritten = 0;
        size_t chunkSize = 65536;

        unsigned char *outBuffer = (unsigned char *)malloc(chunkSize);
        if (outBuffer == NULL) {
            free_gzip_decoder(&decoder);
            return JNI_FALSE;
        }

        while (bytesWritten < (size_t)entry->size) {
            size_t remaining = (size_t)entry->size - bytesWritten;
            size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;
            size_t processed = 0;

            res = read_decompressed_bytes(env, archive, &decoder, outBuffer, currentChunk, &processed, raw_deflate_start);
            if (res != SZ_OK || processed == 0) {
                break;
            }

            jobject directBuffer = (*env)->NewDirectByteBuffer(env, outBuffer, (jlong)processed);
            if (directBuffer != NULL) {
                (*env)->CallVoidMethod(env, callback, onDataMethod, directBuffer);
                (*env)->DeleteLocalRef(env, directBuffer);
            }

            if ((*env)->ExceptionCheck(env)) {
                break;
            }

            bytesWritten += processed;
        }

        free(outBuffer);
        free_gzip_decoder(&decoder);

        if (res != SZ_OK || bytesWritten < (size_t)entry->size) {
            return JNI_FALSE;
        }
    } else if (archive->type == ARCHIVE_TYPE_TARBZ2 || archive->type == ARCHIVE_TYPE_BZIP2) {
        if (index < 0 || index >= archive->tarGzCount) return JNI_FALSE;
        TarGzEntry *entry = &archive->tarGzEntries[index];

        Bzip2Decoder decoder;
        SRes res = init_bzip2_decoder(env, archive, &decoder);
        if (res != SZ_OK) return JNI_FALSE;

        if (archive->type == ARCHIVE_TYPE_TARBZ2) {
            res = skip_bzip2_decompressed_bytes(env, archive, &decoder, (size_t)entry->data_offset, 0);
            if (res != SZ_OK) {
                free_bzip2_decoder(&decoder);
                return JNI_FALSE;
            }
        }

        size_t bytesWritten = 0;
        size_t chunkSize = 65536;

        unsigned char *outBuffer = (unsigned char *)malloc(chunkSize);
        if (outBuffer == NULL) {
            free_bzip2_decoder(&decoder);
            return JNI_FALSE;
        }

        jlong targetSize = entry->size;
        int isInfinite = (targetSize < 0);

        while (isInfinite || (bytesWritten < (size_t)targetSize)) {
            size_t remaining = isInfinite ? chunkSize : ((size_t)targetSize - bytesWritten);
            size_t currentChunk = (remaining < chunkSize) ? remaining : chunkSize;
            size_t processed = 0;

            res = read_bzip2_decompressed_bytes(env, archive, &decoder, outBuffer, currentChunk, &processed, 0);
            if (res != SZ_OK || processed == 0) {
                break;
            }

            jobject directBuffer = (*env)->NewDirectByteBuffer(env, outBuffer, (jlong)processed);
            if (directBuffer != NULL) {
                (*env)->CallVoidMethod(env, callback, onDataMethod, directBuffer);
                (*env)->DeleteLocalRef(env, directBuffer);
            }

            if ((*env)->ExceptionCheck(env)) {
                break;
            }

            bytesWritten += processed;
        }

        free(outBuffer);
        free_bzip2_decoder(&decoder);

        if (res != SZ_OK || (!isInfinite && bytesWritten < (size_t)targetSize)) {
            return JNI_FALSE;
        }
    }

    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}
