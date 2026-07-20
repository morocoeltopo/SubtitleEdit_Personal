cmake_minimum_required(VERSION 3.22.1)

project(subtitleedit_model_archive LANGUAGES C CXX)

set(BZIP2_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/bzip2")

add_library(
    subtitleedit_bzip2
    STATIC
    "${BZIP2_DIR}/blocksort.c"
    "${BZIP2_DIR}/huffman.c"
    "${BZIP2_DIR}/crctable.c"
    "${BZIP2_DIR}/randtable.c"
    "${BZIP2_DIR}/compress.c"
    "${BZIP2_DIR}/decompress.c"
    "${BZIP2_DIR}/bzlib.c"
)

target_include_directories(subtitleedit_bzip2 PUBLIC "${BZIP2_DIR}")
target_compile_definitions(
    subtitleedit_bzip2
    PRIVATE
    BZ_NO_STDIO
    _FILE_OFFSET_BITS=64
)
target_compile_options(subtitleedit_bzip2 PRIVATE -O3 -fvisibility=hidden)
set_target_properties(
    subtitleedit_bzip2
    PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_VISIBILITY_PRESET hidden
)

add_library(
    model_archive_jni
    SHARED
    native_tar_bz2.cpp
)

target_compile_features(model_archive_jni PRIVATE cxx_std_17)
target_compile_definitions(
    model_archive_jni
    PRIVATE
    BZ_NO_STDIO
    _FILE_OFFSET_BITS=64
)
target_compile_options(
    model_archive_jni
    PRIVATE
    -O2
    -Wall
    -Wextra
    -Wpedantic
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
target_link_options(model_archive_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(model_archive_jni PRIVATE subtitleedit_bzip2 log)

