cmake_minimum_required(VERSION 3.22.1)
project(videosubtitle_whisper LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# Compile only what we need for an Android CPU-only build of whisper.cpp v1.7.5.
# Backends (CUDA / Metal / SYCL / Vulkan etc.) are deliberately omitted; ggml-cpu
# is the single backend.
set(SOURCE_FILES
    ${CMAKE_SOURCE_DIR}/whisper_jni.c
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml.c
    ${WHISPER_LIB_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_LIB_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_LIB_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/unary-ops.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/binary-ops.cpp
    # Intel AMX backend — empty translation units on ARM (guarded by
    # __AMX_INT8__ / __AVX512VNNI__) but ggml-cpu.cpp #includes amx.h
    # unconditionally, so the headers must be present in the include path.
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/amx/amx.cpp
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/amx/mmq.cpp
)

find_library(LOG_LIB log)

add_library(whisper SHARED ${SOURCE_FILES})

target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="1.7.5")

target_include_directories(whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3)
    target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(whisper PRIVATE -Wl,--gc-sections)
    target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)
endif()

target_link_libraries(whisper ${LOG_LIB} android)
