#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "7z.h"
#include "7zAlloc.h"
#include "7zBuf.h"
#include "7zCrc.h"
#include "7zFile.h"
#include "7zTypes.h"
#include "miniz.h"

#define kInputBufSize ((size_t)1 << 18) // 256KB input buffer for LookToRead2

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
} JniInStream;

typedef enum {
    ARCHIVE_TYPE_7Z,
    ARCHIVE_TYPE_ZIP
} ArchiveType;

// Unified ArchiveHandle structure keeping track of open archive session (7z or ZIP)
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

// JNI function to open a 7z or ZIP archive from SeekableSource with auto format detection
JNIEXPORT jlong JNICALL Java_com_antigravity_sevenzip_SevenZipJni_openArchive(
    JNIEnv *env, jobject obj, jobject kotlinSource
) {
    ArchiveHandle *archive;
    jclass sourceClass;
    jbyteArray initialBuffer;
    jint bytesRead;
    jbyte magic[8] = {0};
    int isZip = 0;
    int is7z = 0;

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
            (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
            free(archive);
            return 0;
        }
        archive->isZipInit = 1;
    } else {
        // Unsupported format
        (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
        free(archive);
        return 0;
    }

    return (jlong)archive;
}

// JNI function to close the open archive
JNIEXPORT void JNICALL Java_com_antigravity_sevenzip_SevenZipJni_closeArchive(
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
    }

    if (archive->inStream.kotlinSource != NULL) {
        (*env)->DeleteGlobalRef(env, archive->inStream.kotlinSource);
    }

    if (archive->inStream.tempBuffer != NULL) {
        (*env)->DeleteGlobalRef(env, archive->inStream.tempBuffer);
    }

    free(archive);
}

// JNI function to get total entries
JNIEXPORT jint JNICALL Java_com_antigravity_sevenzip_SevenZipJni_getEntryCount(
    JNIEnv *env, jobject obj, jlong handle
) {
    ArchiveHandle *archive;
    if (handle == 0) return 0;
    archive = (ArchiveHandle *)handle;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        return (jint)archive->db.NumFiles;
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        return (jint)mz_zip_reader_get_num_files(&archive->zipArchive);
    }

    return 0;
}

// JNI function to retrieve entry metadata
JNIEXPORT jobject JNICALL Java_com_antigravity_sevenzip_SevenZipJni_getEntryInfo(
    JNIEnv *env, jobject obj, jlong handle, jint index
) {
    ArchiveHandle *archive;
    jstring jname = NULL;
    jlong entrySize = 0;
    jboolean jisDir = JNI_FALSE;
    jlong jcrc = 0;
    jclass infoClass;
    jmethodID ctor;

    if (handle == 0) return NULL;
    archive = (ArchiveHandle *)handle;

    if (archive->type == ARCHIVE_TYPE_7Z) {
        size_t nameLen;
        if (index < 0 || index >= (jint)archive->db.NumFiles) return NULL;

        nameLen = SzArEx_GetFileNameUtf16(&archive->db, (size_t)index, NULL);
        if (nameLen > 0) {
            UInt16 *nameBuf = (UInt16 *)malloc(nameLen * sizeof(UInt16));
            if (nameBuf != NULL) {
                SzArEx_GetFileNameUtf16(&archive->db, (size_t)index, nameBuf);
                jname = (*env)->NewString(env, (const jchar *)nameBuf, (jsize)(nameLen - 1));
                free(nameBuf);
            }
        }
        entrySize = (jlong)SzArEx_GetFileSize(&archive->db, (size_t)index);
        jisDir = (jboolean)SzArEx_IsDir(&archive->db, (size_t)index);
        jcrc = (jlong)(SzBitWithVals_Check(&archive->db.CRCs, (size_t)index) ? archive->db.CRCs.Vals[index] : 0);
    } else if (archive->type == ARCHIVE_TYPE_ZIP) {
        mz_zip_archive_file_stat stat;
        if (index < 0 || index >= (jint)mz_zip_reader_get_num_files(&archive->zipArchive)) return NULL;

        archive->inStream.env = env; // refresh environment
        if (!mz_zip_reader_file_stat(&archive->zipArchive, (mz_uint)index, &stat)) {
            return NULL;
        }

        // miniz stores names in UTF-8
        jname = (*env)->NewStringUTF(env, stat.m_filename);
        entrySize = (jlong)stat.m_uncomp_size;
        jisDir = (jboolean)stat.m_is_directory;
        jcrc = (jlong)stat.m_crc32;
    }

    if (jname == NULL) {
        jname = (*env)->NewStringUTF(env, "");
    }

    // Resolve Kotlin JniEntryInfo constructor: JniEntryInfo(index, name, size, isDir, crc)
    infoClass = (*env)->FindClass(env, "com/antigravity/sevenzip/JniEntryInfo");
    if (infoClass == NULL) return NULL;

    ctor = (*env)->GetMethodID(env, infoClass, "<init>", "(ILjava/lang/String;JZJ)V");
    if (ctor == NULL) return NULL;

    return (*env)->NewObject(env, infoClass, ctor, index, jname, entrySize, jisDir, jcrc);
}

// JNI function to extract an entry and stream it to kotlinx.io.Sink
JNIEXPORT jboolean JNICALL Java_com_antigravity_sevenzip_SevenZipJni_extractEntry(
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
            return JNI_FALSE;
        }
    }

    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}
