cmake_minimum_required(VERSION 3.22.1)
project(videosubtitle_whisper LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# GPU acceleration knob — see docs/GPU_SUPPORT_PLAN.md (Phase A).
#
# Default ON: ggml-vulkan v1.7.5 sources are vendored under
# ggml/src/ggml-vulkan/. The build cross-compiles `vulkan-shaders-gen` for the
# host, runs it to produce `ggml-vulkan-shaders.{hpp,cpp}` from the .comp
# shader sources via the NDK's bundled glslc, then links the result + the
# Vulkan loader from the NDK sysroot into libwhisper.so.
option(WHISPER_VULKAN "Build Vulkan backend (vendored ggml-vulkan v1.7.5)" ON)

# Phase B opt-in (docs/GPU_SUPPORT_PLAN.md §7 Phase B). Sources are vendored
# under ggml/src/ggml-opencl/ but the CMake hook below is intentionally OFF
# by default — flipping it on is gated on a real Snapdragon-8-Gen-2 bench
# showing OpenCL beats Vulkan on Whisper Small by ≥15%. The dlopen-based
# libOpenCL.so loader has not been wired yet (see plan §3 Phase B step 3);
# turning ON without that work in place will fail to link.
option(WHISPER_OPENCL "Build OpenCL backend (vendored ggml-opencl v1.7.5; not yet wired)" OFF)

# Compile only what we need for an Android CPU-only build of whisper.cpp v1.7.5.
# Backends (CUDA / Metal / SYCL / Vulkan etc.) are deliberately omitted; ggml-cpu
# is the single backend by default. Vulkan opts in via WHISPER_VULKAN above.
set(SOURCE_FILES
    ${CMAKE_SOURCE_DIR}/whisper_jni.c
    ${CMAKE_SOURCE_DIR}/whisper_safe.cpp
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

if (WHISPER_VULKAN)
    set(GGML_VULKAN_DIR ${WHISPER_LIB_DIR}/ggml/src/ggml-vulkan)
    if (NOT EXISTS ${GGML_VULKAN_DIR}/ggml-vulkan.cpp)
        message(FATAL_ERROR
            "WHISPER_VULKAN=ON but ${GGML_VULKAN_DIR}/ggml-vulkan.cpp is missing. "
            "Vendor the upstream v1.7.5 ggml-vulkan/ tree first — see "
            "docs/GPU_SUPPORT_PLAN.md §3.")
    endif()

    # The NDK ships:
    #   * Vulkan headers in <NDK_SYSROOT>/usr/include
    #   * libvulkan.so in <NDK_SYSROOT>/usr/lib/<triple>/<api>/libvulkan.so
    #     (CMake's android.toolchain.cmake puts the right ones on the link
    #     path automatically — we just `target_link_libraries(... vulkan)`)
    #   * glslc at <NDK>/shader-tools/<host-tag>/glslc
    #
    # find_package(Vulkan) doesn't ship in the NDK CMake module path, so we
    # locate glslc by hand off ANDROID_NDK and lean on the sysroot for
    # headers + loader.
    if (NOT DEFINED ANDROID_NDK)
        message(FATAL_ERROR "ANDROID_NDK not defined — Vulkan backend requires the Android NDK build.")
    endif()
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(GGML_VK_HOST_TAG "darwin-x86_64")
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(GGML_VK_HOST_TAG "linux-x86_64")
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(GGML_VK_HOST_TAG "windows-x86_64")
    else()
        message(FATAL_ERROR "Unsupported host for Vulkan shader-gen: ${CMAKE_HOST_SYSTEM_NAME}")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${GGML_VK_HOST_TAG}/glslc")
    if (NOT EXISTS ${Vulkan_GLSLC_EXECUTABLE})
        message(FATAL_ERROR
            "glslc not found at ${Vulkan_GLSLC_EXECUTABLE}. NDK 26+ ships it under shader-tools/.")
    endif()

    # ---------------------------------------------------------------------
    # Host build of `vulkan-shaders-gen` via ExternalProject. The tool is
    # pure C++17 + Threads (it just shells out to glslc) — no Vulkan SDK
    # needed on the host.
    #
    # Why we resolve compilers explicitly (not via find_program):
    #   AGP runs CMake with the NDK toolchain already active, so a plain
    #   find_program(... NAMES clang) hits the NDK's prebuilt clang first.
    #   Compiling host code with NDK clang against macOS SDK libc++
    #   triggers a libc++/Clang version mismatch (e.g. `__builtin_clzg`
    #   missing). Pin to a known host toolchain instead.
    # ---------------------------------------------------------------------
    # Bypass the cache so an old (wrong) resolution from a previous configure
    # doesn't stick. We always resolve fresh per-configure here.
    unset(GGML_VK_HOST_C   CACHE)
    unset(GGML_VK_HOST_CXX CACHE)
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        # Apple Clang shipped via Xcode Command Line Tools — first match wins;
        # both /usr/bin/clang and the CLT path are launchers that resolve to
        # the active xcode-select toolchain.
        find_program(GGML_VK_HOST_C   NAMES clang
            HINTS /Library/Developer/CommandLineTools/usr/bin /usr/bin
            NO_CMAKE_FIND_ROOT_PATH NO_DEFAULT_PATH)
        find_program(GGML_VK_HOST_CXX NAMES clang++
            HINTS /Library/Developer/CommandLineTools/usr/bin /usr/bin
            NO_CMAKE_FIND_ROOT_PATH NO_DEFAULT_PATH)
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        find_program(GGML_VK_HOST_C   NAMES gcc cc clang
            HINTS /usr/bin /usr/local/bin
            NO_CMAKE_FIND_ROOT_PATH NO_DEFAULT_PATH)
        find_program(GGML_VK_HOST_CXX NAMES g++ c++ clang++
            HINTS /usr/bin /usr/local/bin
            NO_CMAKE_FIND_ROOT_PATH NO_DEFAULT_PATH)
    else()
        find_program(GGML_VK_HOST_C   NAMES clang cl gcc NO_CMAKE_FIND_ROOT_PATH)
        find_program(GGML_VK_HOST_CXX NAMES clang++ cl g++ NO_CMAKE_FIND_ROOT_PATH)
    endif()
    if (NOT GGML_VK_HOST_C OR NOT GGML_VK_HOST_CXX)
        message(FATAL_ERROR "No host C/C++ compiler found for vulkan-shaders-gen build.")
    endif()
    message(STATUS "vulkan-shaders-gen host compiler: ${GGML_VK_HOST_C} / ${GGML_VK_HOST_CXX}")

    # Use a dedicated build dir name keyed on the toolchain layout so an old
    # CMakeCache from a stale source dir (different file path between configure
    # attempts) doesn't trip "source does not match" errors on rebuild.
    set(GGML_VK_HOST_BUILD ${CMAKE_CURRENT_BINARY_DIR}/vk-shadersgen-host-v2)
    set(GGML_VK_HOST_BIN_DIR ${GGML_VK_HOST_BUILD}/bin)
    set(GGML_VK_HOST_TOOLCHAIN ${CMAKE_CURRENT_BINARY_DIR}/host-toolchain.cmake)

    # We don't need the upstream's coopmat/coopmat2 detection (they're NV-/
    # desktop-class extensions; Adreno/Mali don't expose them and the test
    # shaders run by upstream's CMake fail at configure time on glslc 1.3
    # that ships in NDK 26 anyway). Force them OFF — the generator emits
    # only the always-supported shaders. Integer-dot is similarly elided.
    #
    # We write our own host toolchain file (instead of using upstream's
    # cmake/host-toolchain.cmake.in template) so we can pin a compatible
    # macOS SDK. macOS 26 / Xcode 17's MacOSX26.4.sdk libc++ headers use
    # `__builtin_clzg` / `__builtin_ctzg`, which Apple Clang 21 doesn't
    # implement — pinning to the older MacOSX15.4 SDK side-steps the
    # incompatibility for this host-only build of vulkan-shaders-gen.
    set(GGML_VK_HOST_OSX_SYSROOT "")
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        foreach(_sdk
            "/Library/Developer/CommandLineTools/SDKs/MacOSX15.4.sdk"
            "/Library/Developer/CommandLineTools/SDKs/MacOSX15.sdk")
            if (EXISTS ${_sdk})
                set(GGML_VK_HOST_OSX_SYSROOT ${_sdk})
                break()
            endif()
        endforeach()
    endif()
    file(WRITE ${GGML_VK_HOST_TOOLCHAIN}
"# Auto-generated by app/src/main/cpp/CMakeLists.txt — host-only build
# of vulkan-shaders-gen for the vendored ggml-vulkan v1.7.5 backend.
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_COMPILER \"${GGML_VK_HOST_C}\")
set(CMAKE_CXX_COMPILER \"${GGML_VK_HOST_CXX}\")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY \"${GGML_VK_HOST_BIN_DIR}\")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
")
    if (GGML_VK_HOST_OSX_SYSROOT)
        file(APPEND ${GGML_VK_HOST_TOOLCHAIN}
"set(CMAKE_OSX_SYSROOT \"${GGML_VK_HOST_OSX_SYSROOT}\" CACHE PATH \"host build sysroot\" FORCE)\n")
        message(STATUS "vulkan-shaders-gen host sysroot: ${GGML_VK_HOST_OSX_SYSROOT}")
    endif()

    # The upstream `vulkan-shaders/CMakeLists.txt` is intended for `add_subdirectory`
    # use — it has no `cmake_minimum_required()` or `project()`. Use a tiny shim
    # CMakeLists in the host build dir so we can drive it as a standalone
    # ExternalProject.
    set(GGML_VK_HOST_SOURCE ${GGML_VK_HOST_BUILD}/source)
    file(MAKE_DIRECTORY ${GGML_VK_HOST_SOURCE})
    file(WRITE ${GGML_VK_HOST_SOURCE}/CMakeLists.txt
"cmake_minimum_required(VERSION 3.19)
project(vulkan_shaders_gen_host CXX)
add_subdirectory(${GGML_VULKAN_DIR}/vulkan-shaders ${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders)
")

    # The host build needs a generator + ninja path. Reuse the same Ninja that
    # AGP shipped (already on disk under the SDK's cmake/<ver>/bin/) so we
    # don't depend on a system install.
    if (CMAKE_MAKE_PROGRAM)
        set(GGML_VK_HOST_NINJA ${CMAKE_MAKE_PROGRAM})
    else()
        find_program(GGML_VK_HOST_NINJA NAMES ninja)
    endif()

    include(ExternalProject)
    ExternalProject_Add(
        vulkan-shaders-gen
        SOURCE_DIR  ${GGML_VK_HOST_SOURCE}
        BINARY_DIR  ${GGML_VK_HOST_BUILD}/build
        CMAKE_GENERATOR Ninja
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${GGML_VK_HOST_TOOLCHAIN}
            -DCMAKE_INSTALL_PREFIX=${GGML_VK_HOST_BUILD}
            -DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${GGML_VK_HOST_BIN_DIR}
            -DCMAKE_MAKE_PROGRAM=${GGML_VK_HOST_NINJA}
            -DGGML_VULKAN_COOPMAT_GLSLC_SUPPORT=OFF
            -DGGML_VULKAN_COOPMAT2_GLSLC_SUPPORT=OFF
        BUILD_COMMAND   ${CMAKE_COMMAND} --build .
        INSTALL_COMMAND ${CMAKE_COMMAND} --install .
        BUILD_BYPRODUCTS ${GGML_VK_HOST_BIN_DIR}/vulkan-shaders-gen
    )

    set(GGML_VK_GENSHADERS_CMD ${GGML_VK_HOST_BIN_DIR}/vulkan-shaders-gen)
    set(GGML_VK_GENERATED_HPP  ${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-shaders.hpp)
    set(GGML_VK_GENERATED_CPP  ${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-shaders.cpp)
    set(GGML_VK_SHADER_INPUT   ${GGML_VULKAN_DIR}/vulkan-shaders)
    set(GGML_VK_SHADER_SPV_DIR ${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders.spv)

    file(GLOB GGML_VK_SHADER_DEPS "${GGML_VK_SHADER_INPUT}/*.comp")

    add_custom_command(
        OUTPUT ${GGML_VK_GENERATED_HPP} ${GGML_VK_GENERATED_CPP}
        COMMAND ${GGML_VK_GENSHADERS_CMD}
            --glslc      ${Vulkan_GLSLC_EXECUTABLE}
            --input-dir  ${GGML_VK_SHADER_INPUT}
            --output-dir ${GGML_VK_SHADER_SPV_DIR}
            --target-hpp ${GGML_VK_GENERATED_HPP}
            --target-cpp ${GGML_VK_GENERATED_CPP}
            --no-clean
        DEPENDS ${GGML_VK_SHADER_DEPS} vulkan-shaders-gen
        COMMENT "Generating Vulkan SPIR-V shaders for ggml-vulkan"
        VERBATIM
    )

    # Add the Vulkan backend translation unit + the generated shader blob to
    # the whisper target. Both compile under Android's NDK toolchain.
    target_sources(whisper PRIVATE
        ${GGML_VULKAN_DIR}/ggml-vulkan.cpp
        ${GGML_VK_GENERATED_CPP}
        ${GGML_VK_GENERATED_HPP}
    )
    target_compile_definitions(whisper PUBLIC GGML_USE_VULKAN)
    # The generated header lives in the build dir; ggml-vulkan.cpp #includes
    # it relative to its own dir, so we need that on the include path too.
    #
    # Vulkan-Hpp note: NDK 26.3 ships a buggy `vulkan/vulkan_enums.hpp`
    # (v1.3.237 — has duplicate `MemoryMapFlagBits` definitions that fail
    # to compile). Vendor the upstream Vulkan-Headers v1.3.290 under
    # cpp/third_party/Vulkan-Headers/ and put it on the include path
    # *before* the NDK sysroot so the C++ wrapper resolves there. The
    # Vulkan loader (libvulkan.so) is still picked up from the NDK sysroot.
    target_include_directories(whisper PRIVATE
        ${GGML_VULKAN_DIR}
        ${CMAKE_CURRENT_BINARY_DIR}
        ${CMAKE_SOURCE_DIR}/third_party/Vulkan-Headers/include
    )
    # vulkan is in the NDK sysroot — link by name and the toolchain resolves it.
    target_link_libraries(whisper vulkan)
    add_dependencies(whisper vulkan-shaders-gen)
    message(STATUS "whisper: Vulkan backend enabled (glslc=${Vulkan_GLSLC_EXECUTABLE})")
endif()

if (WHISPER_OPENCL)
    set(GGML_OPENCL_DIR ${WHISPER_LIB_DIR}/ggml/src/ggml-opencl)
    if (NOT EXISTS ${GGML_OPENCL_DIR}/ggml-opencl.cpp)
        message(FATAL_ERROR
            "WHISPER_OPENCL=ON but ${GGML_OPENCL_DIR}/ggml-opencl.cpp is missing. "
            "Vendor the upstream v1.7.5 ggml-opencl/ tree first.")
    endif()
    # NOTE — incomplete. Per docs/GPU_SUPPORT_PLAN.md §3 Phase B, before
    # turning this on you also need:
    #   * a runtime dlopen("libOpenCL.so") loader (whisper.cpp's android
    #     example has one — port it in alongside this option)
    #   * the .cl kernels embedded as a header (similar to the
    #     vulkan-shaders-gen path — upstream's ggml-opencl/CMakeLists.txt
    #     uses python `embed_kernel.py`)
    #   * Adreno `CL_QCOM_*` extension probes
    # We deliberately don't wire those today — vendoring is in place so the
    # work has a launching pad once benches justify it.
    message(FATAL_ERROR
        "WHISPER_OPENCL=ON but the runtime libOpenCL loader + kernel embed "
        "pipeline have not been wired. See docs/GPU_SUPPORT_PLAN.md §3 Phase B.")
endif()

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
