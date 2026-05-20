#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "7z.h"
#include "7zAlloc.h"
#include "7zBuf.h"
#include "7zCrc.h"
#include "7zFile.h"
#include "7zTypes.h"

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

// SevenZipArchive structure keeping track of open archive session
typedef struct {
    JniInStream inStream;
    CLookToRead2 lookStream;
    CSzArEx db;
    ISzAlloc alloc;
    ISzAlloc allocTemp;
    int isDbExInit;
} SevenZipArchive;

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

// JNI function to open a 7z archive from SeekableSource
JNIEXPORT jlong JNICALL Java_com_antigravity_sevenzip_SevenZipJni_openArchive(
    JNIEnv *env, jobject obj, jobject kotlinSource
) {
    SevenZipArchive *archive;
    jclass sourceClass;
    SRes res;

    CrcGenerateTable();

    archive = (SevenZipArchive *)malloc(sizeof(SevenZipArchive));
    if (archive == NULL) return 0;

    memset(archive, 0, sizeof(SevenZipArchive));

    archive->alloc.Alloc = SzAlloc;
    archive->alloc.Free = SzFree;
    archive->allocTemp.Alloc = SzAllocTemp;
    archive->allocTemp.Free = SzFreeTemp;

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

    return (jlong)archive;
}

// JNI function to close the open archive
JNIEXPORT void JNICALL Java_com_antigravity_sevenzip_SevenZipJni_closeArchive(
    JNIEnv *env, jobject obj, jlong handle
) {
    SevenZipArchive *archive;
    if (handle == 0) return;
    archive = (SevenZipArchive *)handle;

    if (archive->isDbExInit) {
        SzArEx_Free(&archive->db, &archive->alloc);
    }

    if (archive->lookStream.buf != NULL) {
        archive->alloc.Free(&archive->alloc, archive->lookStream.buf);
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
    SevenZipArchive *archive;
    if (handle == 0) return 0;
    archive = (SevenZipArchive *)handle;
    return (jint)archive->db.NumFiles;
}

// JNI function to retrieve entry metadata
JNIEXPORT jobject JNICALL Java_com_antigravity_sevenzip_SevenZipJni_getEntryInfo(
    JNIEnv *env, jobject obj, jlong handle, jint index
) {
    SevenZipArchive *archive;
    size_t nameLen = 0;
    jstring jname = NULL;
    jlong entrySize;
    jboolean jisDir;
    jlong jcrc;
    jclass infoClass;
    jmethodID ctor;

    if (handle == 0) return NULL;
    archive = (SevenZipArchive *)handle;
    if (index < 0 || index >= (jint)archive->db.NumFiles) return NULL;

    nameLen = SzArEx_GetFileNameUtf16(&archive->db, (size_t)index, NULL);
    if (nameLen > 0) {
        UInt16 *nameBuf = (UInt16 *)malloc(nameLen * sizeof(UInt16));
        if (nameBuf != NULL) {
            SzArEx_GetFileNameUtf16(&archive->db, (size_t)index, nameBuf);
            // Direct UTF-16 conversion since 7z SDK stores names as UInt16 (same as Java char)
            jname = (*env)->NewString(env, (const jchar *)nameBuf, (jsize)(nameLen - 1));
            free(nameBuf);
        }
    }
    if (jname == NULL) {
        jname = (*env)->NewStringUTF(env, "");
    }

    entrySize = (jlong)SzArEx_GetFileSize(&archive->db, (size_t)index);
    jisDir = (jboolean)SzArEx_IsDir(&archive->db, (size_t)index);
    jcrc = (jlong)(SzBitWithVals_Check(&archive->db.CRCs, (size_t)index) ? archive->db.CRCs.Vals[index] : 0);

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
    SevenZipArchive *archive;
    UInt32 blockIndex = 0xFFFFFFFF;
    Byte *outBuffer = NULL;
    size_t outBufferSize = 0;
    size_t offset = 0;
    size_t outSizeProcessed = 0;
    SRes res;

    if (handle == 0) return JNI_FALSE;
    archive = (SevenZipArchive *)handle;
    if (index < 0 || index >= (jint)archive->db.NumFiles) return JNI_FALSE;

    // Refresh thread-bound JNIEnv pointer before executing nested stream reads
    archive->inStream.env = env;

    // Invoke 7z block extraction
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

        // Resolve kotlinx.io.Sink.write(ByteArray, offset, byteCount)
        sinkClass = (*env)->GetObjectClass(env, kotlinSink);
        writeMethod = (*env)->GetMethodID(env, sinkClass, "write", "([BII)V");
        if (writeMethod == NULL) {
            archive->alloc.Free(&archive->alloc, outBuffer);
            return JNI_FALSE;
        }

        {
            // Stream decompressed buffer to JVM in 64KB chunks to maintain fixed memory footprint
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

    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}
