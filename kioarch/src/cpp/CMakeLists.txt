cmake_minimum_required(VERSION 3.22)
project(kioarch C)

# Find JNI (Only for JVM/Desktop build, Android NDK provides JNI headers automatically)
if(NOT ANDROID AND NOT IOS AND NOT EMSCRIPTEN)
    find_package(JNI REQUIRED)
endif()

set(7ZIP_C_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../7zip/C")
set(MINIZ_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../miniz")

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${7ZIP_C_DIR}
    ${MINIZ_DIR}
)

if(NOT ANDROID AND NOT IOS AND NOT EMSCRIPTEN)
    include_directories(${JNI_INCLUDE_DIRS})
endif()


# Core 7-Zip C SDK files
set(7ZIP_SOURCES
    "${7ZIP_C_DIR}/7zAlloc.c"
    "${7ZIP_C_DIR}/7zArcIn.c"
    "${7ZIP_C_DIR}/7zBuf.c"
    "${7ZIP_C_DIR}/7zBuf2.c"
    "${7ZIP_C_DIR}/7zCrc.c"
    "${7ZIP_C_DIR}/7zCrcOpt.c"
    "${7ZIP_C_DIR}/7zDec.c"
    "${7ZIP_C_DIR}/7zStream.c"
    "${7ZIP_C_DIR}/LzmaDec.c"
    "${7ZIP_C_DIR}/Lzma2Dec.c"
    "${7ZIP_C_DIR}/Bra.c"
    "${7ZIP_C_DIR}/Bra86.c"
    "${7ZIP_C_DIR}/BraIA64.c"
    "${7ZIP_C_DIR}/Bcj2.c"
    "${7ZIP_C_DIR}/Ppmd7.c"
    "${7ZIP_C_DIR}/Ppmd7Dec.c"
    "${7ZIP_C_DIR}/CpuArch.c"
    "${7ZIP_C_DIR}/Delta.c"
    "${7ZIP_C_DIR}/Aes.c"
    "${7ZIP_C_DIR}/AesOpt.c"
    "${7ZIP_C_DIR}/Sha256.c"
    "${7ZIP_C_DIR}/Sha256Opt.c"
)

# Set preprocessor flags
# Z7_ST: Single-threaded build (highly portable, avoids multi-threading overhead)
# _GNU_SOURCE: For Linux builds
add_definitions(-DZ7_ST)

if(UNIX)
    add_definitions(-D_GNU_SOURCE)
    # Enable position-independent code for shared library
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC")
endif()

# Define the target library
if(EMSCRIPTEN)
    add_executable(kioarch
        kioarch_ios.c
        "${MINIZ_DIR}/miniz.c"
        "${MINIZ_DIR}/miniz_tdef.c"
        "${MINIZ_DIR}/miniz_tinfl.c"
        "${MINIZ_DIR}/miniz_zip.c"
        ${7ZIP_SOURCES}
    )
    target_link_options(kioarch PRIVATE
        "-sEXPORTED_FUNCTIONS=['_kio_open_archive','_kio_open_archive_wasm','_kio_close_archive','_kio_get_entry_count','_kio_get_entry','_kio_extract_entry','_kio_extract_entry_wasm','_malloc','_free']"
        "-sEXPORTED_RUNTIME_METHODS=['cwrap','ccall','addFunction','removeFunction','UTF8ToString','HEAP8','HEAPU8','HEAP16','HEAPU16','HEAP32','HEAPU32','HEAPF32','HEAPF64','HEAP64','HEAPU64']"
        "-sALLOW_TABLE_GROWTH=1"
        "-sALLOW_MEMORY_GROWTH=1"
        "-sMODULARIZE=1"
        "-sEXPORT_NAME='createKioArchModule'"
        "-sSINGLE_FILE=0"
    )
elseif(IOS)
    # Disable code signing for iOS builds on CI/Simulator
    set(CMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED "NO")
    set(CMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY "")
    set(CMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED "NO")

    # Fix ARM64 CRC32 and SHA256 compiler errors by enabling crc and crypto extension features
    # Use -Xarch_arm64 to only apply this flag for ARM64 target and avoid conflicts on x86_64 simulator
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Xarch_arm64 -march=armv8-a+crc+crypto")

    add_library(kioarch STATIC
        kioarch_ios.c
        "${MINIZ_DIR}/miniz.c"
        "${MINIZ_DIR}/miniz_tdef.c"
        "${MINIZ_DIR}/miniz_tinfl.c"
        "${MINIZ_DIR}/miniz_zip.c"
        ${7ZIP_SOURCES}
    )
else()
    add_library(kioarch SHARED
        kioarch_jni.c
        "${MINIZ_DIR}/miniz.c"
        "${MINIZ_DIR}/miniz_tdef.c"
        "${MINIZ_DIR}/miniz_tinfl.c"
        "${MINIZ_DIR}/miniz_zip.c"
        ${7ZIP_SOURCES}
    )
endif()

# Link libraries
if(NOT ANDROID AND NOT IOS AND NOT EMSCRIPTEN)
    target_link_libraries(kioarch ${JNI_LIBRARIES})
endif()

if(ANDROID)
    # Support 16 KB page size devices on Android 15+
    target_link_options(kioarch PRIVATE "-Wl,-z,max-page-size=16384")
endif()

