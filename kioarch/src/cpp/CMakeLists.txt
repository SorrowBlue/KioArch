cmake_minimum_required(VERSION 3.10.2)
project(kioarch C)

# Find JNI (Only for JVM/Desktop build, Android NDK provides JNI headers automatically)
if(NOT ANDROID)
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

if(NOT ANDROID)
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

# Define the shared library target
add_library(kioarch SHARED
    kioarch_jni.c
    "${MINIZ_DIR}/miniz.c"
    "${MINIZ_DIR}/miniz_tdef.c"
    "${MINIZ_DIR}/miniz_tinfl.c"
    "${MINIZ_DIR}/miniz_zip.c"
    ${7ZIP_SOURCES}
)

# Link libraries
if(NOT ANDROID)
    target_link_libraries(kioarch ${JNI_LIBRARIES})
endif()

