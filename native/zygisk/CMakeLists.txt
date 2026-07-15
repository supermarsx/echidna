cmake_minimum_required(VERSION 3.22)

project(echidna-zygisk LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Configure Android ABIs when building via Gradle/ndk-build. When the generator
# does not expose ANDROID_ABI we fall back to building a generic host binary so
# the code can still be compiled in CI environments.
if(NOT ANDROID)
  message(WARNING "Building zygisk module without Android toolchain; using host platform for smoke testing.")
endif()

add_library(echidna SHARED
    src/api.cpp
    src/dsp/stream_handle_registry.cpp
    src/module.cpp
    src/audio/pcm_buffer_processor.cpp
    src/state/shared_state.cpp
    src/runtime/inline_hook.cpp
    src/hooks/hook_manager.cpp
    src/hooks/aaudio_callback_registry.cpp
    src/hooks/aaudio_hook_manager.cpp
    src/hooks/capture_buffer_router.cpp
    src/hooks/opensl_hook_manager.cpp
    src/hooks/audiorecord_hook_manager.cpp
    src/hooks/audioflinger_hook_manager.cpp
    src/hooks/libc_read_hook_manager.cpp
    src/hooks/tinyalsa_hook_manager.cpp
    src/hooks/audiohal_hook_manager.cpp
    src/hooks/audio_hook_orchestrator.cpp
    src/utils/proc_maps_scanner.cpp
    src/utils/plt_resolver.cpp
    src/utils/api_level_probe.cpp
    src/utils/config_shared_memory.cpp
    src/utils/telemetry_accumulator.cpp
    src/utils/process_utils.cpp
    src/jni/audio_bridge.cpp
    src/jni/native_bridge_runtime_zygisk.cpp
    src/runtime/profile_sync_protocol.cpp
    src/runtime/profile_sync_server.cpp
    src/runtime/telemetry_socket_exporter.cpp)

set_target_properties(echidna PROPERTIES
    OUTPUT_NAME echidna
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)

if(ANDROID AND DEFINED ANDROID_ABI)
  # Live under the NDK toolchain file (which sets ANDROID/ANDROID_ABI). Per-ABI
  # separation comes from the per-ABI build directory (build/<abi>) driven by
  # tools/build_native_ndk.sh, so the on-device path stays:
  #     build/<abi>/lib/libechidna.so
  # which the Magisk packager renames to zygisk/<abi>.so.
  target_compile_definitions(echidna PRIVATE ECHIDNA_TARGET_ABI="${ANDROID_ABI}")
  message(STATUS "Echidna Zygisk: Android ABI ${ANDROID_ABI} -> ${CMAKE_BINARY_DIR}/lib/libechidna.so")
endif()

target_include_directories(echidna
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/../include>
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/../dsp/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src)

# Zygisk module ABI header (native/zygisk/include/zygisk.hpp, provided by t2-e9):
# src/module.cpp includes it and emits REGISTER_ZYGISK_MODULE. The header lives
# under include/, already on the PUBLIC path above; this explicit PRIVATE entry
# makes the dependency unambiguous per e9's include-dir request.
target_include_directories(echidna PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(LOG_LIB log)
if(LOG_LIB)
    target_link_libraries(echidna PRIVATE ${LOG_LIB})
endif()

# src/jni/audio_bridge.cpp does `#include <jni.h>`. Provide the JNI headers so
# the module compiles. On an Android/NDK toolchain jni.h is part of the sysroot
# (no find_package needed); on a host toolchain (CI/dev) it comes from the JDK,
# so locate it via find_package(JNI). Keep it optional here: if no JDK is present
# (e.g. a Windows dev host without a JDK) configuration still succeeds, and the
# missing header only surfaces at compile time — CI installs a JDK (t2-e4) so the
# include path resolves there.
set(ECHIDNA_JNI_FOUND ${ANDROID})
if(NOT ANDROID)
    find_package(JNI QUIET)
    if(JNI_FOUND)
        set(ECHIDNA_JNI_FOUND ON)
        target_include_directories(echidna PRIVATE ${JNI_INCLUDE_DIRS})
    else()
        message(WARNING
            "JNI headers not found on this host; native/zygisk/src/jni/audio_bridge.cpp "
            "will fail to compile until a JDK (jni.h) is available. CI provides one.")
    endif()
endif()

# LSPosed loads a dedicated JNI bridge, not the Zygisk module. Keeping this
# target separate prevents Zygisk registration/lifecycle globals and hook state
# from being duplicated in arbitrary target applications. It links the sibling
# DSP library directly, so the Android loader records an explicit
# DT_NEEDED=libech_dsp.so dependency and fails before JNI initialization when
# the matching per-ABI DSP artifact is absent.
if(TARGET ech_dsp AND ECHIDNA_JNI_FOUND)
    add_library(echidna_shim_jni SHARED
        src/audio/pcm_buffer_processor.cpp
        src/jni/audio_bridge.cpp
        src/jni/native_bridge_runtime_standalone.cpp)
    set_target_properties(echidna_shim_jni PROPERTIES
        OUTPUT_NAME echidna_shim_jni
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
    target_include_directories(echidna_shim_jni
        PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/src
            ${CMAKE_CURRENT_SOURCE_DIR}/../include
            ${CMAKE_CURRENT_SOURCE_DIR}/../dsp/include
            ${JNI_INCLUDE_DIRS})
    target_link_libraries(echidna_shim_jni PRIVATE ech_dsp)
    if(LOG_LIB)
        target_link_libraries(echidna_shim_jni PRIVATE ${LOG_LIB})
    endif()
    if(ANDROID AND DEFINED ANDROID_ABI)
        target_compile_definitions(echidna_shim_jni
            PRIVATE ECHIDNA_TARGET_ABI="${ANDROID_ABI}")
        message(STATUS
            "Echidna shim JNI: Android ABI ${ANDROID_ABI} -> "
            "${CMAKE_BINARY_DIR}/lib/libechidna_shim_jni.so")
    endif()
endif()

install(TARGETS echidna
    LIBRARY DESTINATION lib)
if(TARGET echidna_shim_jni)
    install(TARGETS echidna_shim_jni LIBRARY DESTINATION lib)
endif()
install(DIRECTORY include/ DESTINATION include)

# Host-only smoke tests: the DSP-routing harness exercises the audio path on the
# host and must never cross-compile for an NDK/device build (it is a host
# executable, and it links ech_dsp from the sibling DSP project — only present in
# the aggregated super-build). Mirrors native/dsp's `BUILD_TESTING AND NOT
# ANDROID` guard; tools/build_native_ndk.sh also passes -DBUILD_TESTING=OFF.
if(BUILD_TESTING AND NOT ANDROID)
  add_subdirectory(tests)
endif()
